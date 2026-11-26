"""Android 发布版本号计算与 latest.json 更新清单生成脚本（纯 Python 标准库）。"""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import sys
from urllib.parse import urlsplit

SCHEMA_VERSION = 1
PLATFORM_ANDROID = "android"
MAX_MAJOR = 200_000
MAX_MINOR = 99
MAX_PATCH = 99
MAX_ANDROID_VERSION_CODE = 2_100_000_000
HASH_CHUNK_SIZE = 1024 * 1024

SEMVER_WITH_OPTIONAL_V_RE = re.compile(
    r"\Av?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)"
    r"(?:-((?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*)"
    r"(?:\.(?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*))*))?\Z"
)

UTC_ISO8601_RE = re.compile(r"\A\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z\Z")


def _parse_semver_components(version: str) -> tuple[str, int, int, int]:
    """解析带可选前导 v 的严格 SemVer，返回 (无前导v版本号, major, minor, patch)。"""
    if not isinstance(version, str):
        raise ValueError(f"版本号必须为字符串，实际为：{type(version).__name__}")
    match = SEMVER_WITH_OPTIONAL_V_RE.fullmatch(version)
    if match is None:
        raise ValueError(
            f"非法 SemVer 版本号：{version!r}（仅允许 X.Y.Z 或 X.Y.Z-prerelease，可选前导小写 v，禁止空白与 +build 元数据）"
        )
    normalized = version[1:] if version.startswith("v") else version
    return normalized, int(match.group(1)), int(match.group(2)), int(match.group(3))


def compute_version_code(version: str) -> int:
    """按 major * 10000 + minor * 100 + patch 计算确定性 Android versionCode。"""
    _, major, minor, patch = _parse_semver_components(version)
    if not (0 <= major <= MAX_MAJOR):
        raise ValueError(
            f"major 版本号越界：{major}（允许范围 0..{MAX_MAJOR}，原始输入：{version!r}）"
        )
    if not (0 <= minor <= MAX_MINOR):
        raise ValueError(
            f"minor 版本号越界：{minor}（允许范围 0..{MAX_MINOR}，原始输入：{version!r}）"
        )
    if not (0 <= patch <= MAX_PATCH):
        raise ValueError(
            f"patch 版本号越界：{patch}（允许范围 0..{MAX_PATCH}，原始输入：{version!r}）"
        )
    code = major * 10_000 + minor * 100 + patch
    if code <= 0:
        raise ValueError(f"计算得到的 versionCode 必须大于 0，原始输入：{version!r}")
    return code


def _validate_int_field(value: object, *, field_name: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValueError(f"{field_name} 必须为整数，实际为：{value!r}")
    return value


def _contains_forbidden_url_chars(value: str) -> bool:
    return any(ch.isspace() or ord(ch) < 32 or ord(ch) == 127 for ch in value)


def validate_https_url(
    url: str,
    *,
    field_name: str = "downloadUrl",
    allow_query_or_fragment: bool = True,
) -> str:
    """校验 URL 必须为合法 https:// 地址且不含任何空白或控制字符。"""
    if not isinstance(url, str) or not url:
        raise ValueError(f"{field_name} 不能为空")
    if _contains_forbidden_url_chars(url):
        raise ValueError(f"{field_name} 不能包含空白、换行或控制字符：{url!r}")
    if not url.startswith("https://"):
        raise ValueError(f"{field_name} 必须以 https:// 开头：{url!r}")

    parsed = urlsplit(url)
    if parsed.scheme != "https" or not parsed.netloc or not parsed.hostname:
        raise ValueError(f"{field_name} 缺少有效主机名：{url!r}")
    if parsed.username is not None or parsed.password is not None:
        raise ValueError(f"{field_name} 不允许包含内嵌凭据：{url!r}")
    try:
        _ = parsed.port
    except ValueError as exc:
        raise ValueError(f"{field_name} 端口号非法：{url!r}") from exc
    if not allow_query_or_fragment and (parsed.query or parsed.fragment):
        raise ValueError(f"{field_name} 不允许包含查询参数或锚点：{url!r}")
    return url


def validate_object_key(object_key: str) -> str:
    """校验对象存储相对路径键，拒绝空白、反斜杠、协议前缀与路径穿越。"""
    if not isinstance(object_key, str) or not object_key:
        raise ValueError("apk_object_key 不能为空")
    if _contains_forbidden_url_chars(object_key):
        raise ValueError(f"apk_object_key 不能包含空白、换行或控制字符：{object_key!r}")
    if "\\" in object_key or "://" in object_key or "?" in object_key or "#" in object_key:
        raise ValueError(f"apk_object_key 包含非法字符：{object_key!r}")

    normalized = object_key.lstrip("/")
    if not normalized or normalized.endswith("/"):
        raise ValueError(f"apk_object_key 必须指向具体文件路径：{object_key!r}")
    segments = normalized.split("/")
    if any(seg in ("", ".", "..") for seg in segments):
        raise ValueError(f"apk_object_key 包含非法路径段：{object_key!r}")
    return normalized


def build_download_url(
    *,
    base_url: str | None = None,
    apk_object_key: str | None = None,
    download_url: str | None = None,
) -> str:
    """根据显式 download_url 或 (base_url + apk_object_key) 拼装并校验 HTTPS 下载链接。"""
    if download_url is not None:
        if base_url is not None or apk_object_key is not None:
            raise ValueError("不能同时指定 download_url 与 base_url/apk_object_key")
        return validate_https_url(download_url, field_name="downloadUrl")

    if base_url is None or apk_object_key is None:
        raise ValueError("必须同时提供 base_url 与 apk_object_key（或直接提供 download_url）")

    validated_base = validate_https_url(
        base_url,
        field_name="baseUrl",
        allow_query_or_fragment=False,
    )
    normalized_key = validate_object_key(apk_object_key)
    joined = f"{validated_base.rstrip('/')}/{normalized_key}"
    return validate_https_url(joined, field_name="downloadUrl")


def compute_file_sha256_and_size(apk_path: Path | str) -> tuple[str, int]:
    """流式读取 APK 文件，返回 (64位小写 SHA-256, 精确字节数)。"""
    path = Path(apk_path)
    if not path.exists():
        raise FileNotFoundError(f"找不到 APK 文件：{path}")
    if not path.is_file():
        raise ValueError(f"APK 路径不是常规文件：{path}")

    hasher = hashlib.sha256()
    total_size = 0
    with path.open("rb") as fp:
        while True:
            chunk = fp.read(HASH_CHUNK_SIZE)
            if not chunk:
                break
            hasher.update(chunk)
            total_size += len(chunk)

    if total_size <= 0:
        raise ValueError(f"APK 文件为空（0 字节）：{path}")
    return hasher.hexdigest().lower(), total_size


def _validate_published_at(published_at: str | None) -> str:
    if published_at is None:
        return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    if not isinstance(published_at, str) or not UTC_ISO8601_RE.fullmatch(published_at):
        raise ValueError(
            f"publishedAt 格式非法：{published_at!r}（需为 YYYY-MM-DDTHH:MM:SSZ UTC 时间戳）"
        )
    try:
        datetime.strptime(published_at, "%Y-%m-%dT%H:%M:%SZ")
    except ValueError as exc:
        raise ValueError(f"publishedAt 日期时间无效：{published_at!r}") from exc
    return published_at


def build_android_latest_manifest(
    *,
    apk_path: Path | str,
    version_name: str,
    version_code: int | None = None,
    base_url: str | None = None,
    apk_object_key: str | None = None,
    download_url: str | None = None,
    release_page_url: str = "",
    changelog: str = "",
    min_version_code: int = 0,
    published_at: str | None = None,
) -> dict[str, object]:
    """基于真实 APK 文件构建符合 Android 客户端契约的 latest.json 清单对象。"""
    normalized_version_name, _, _, _ = _parse_semver_components(version_name)

    if version_code is None:
        resolved_version_code = compute_version_code(normalized_version_name)
    else:
        resolved_version_code = _validate_int_field(version_code, field_name="versionCode")
        if not (0 < resolved_version_code <= MAX_ANDROID_VERSION_CODE):
            raise ValueError(
                f"versionCode 越界：{resolved_version_code}（允许范围 1..{MAX_ANDROID_VERSION_CODE}）"
            )

    resolved_min_version_code = _validate_int_field(
        min_version_code,
        field_name="minVersionCode",
    )
    if resolved_min_version_code < 0:
        raise ValueError(f"minVersionCode 不能为负数：{resolved_min_version_code}")
    if resolved_min_version_code > resolved_version_code:
        raise ValueError(
            f"minVersionCode ({resolved_min_version_code}) 不能大于当前 versionCode ({resolved_version_code})"
        )

    resolved_published_at = _validate_published_at(published_at)
    sha256_hex, file_size = compute_file_sha256_and_size(apk_path)
    resolved_download_url = build_download_url(
        base_url=base_url,
        apk_object_key=apk_object_key,
        download_url=download_url,
    )

    if not isinstance(release_page_url, str):
        raise ValueError(f"releasePageUrl 必须为字符串，实际为：{type(release_page_url).__name__}")
    resolved_release_page_url = (
        validate_https_url(release_page_url, field_name="releasePageUrl")
        if release_page_url != ""
        else ""
    )

    if not isinstance(changelog, str):
        raise ValueError(f"changelog 必须为字符串，实际为：{type(changelog).__name__}")
    normalized_changelog = changelog.replace("\r\n", "\n").replace("\r", "\n").strip()

    return {
        "schemaVersion": SCHEMA_VERSION,
        "platform": PLATFORM_ANDROID,
        "versionName": normalized_version_name,
        "versionCode": resolved_version_code,
        "minVersionCode": resolved_min_version_code,
        "publishedAt": resolved_published_at,
        "fileSize": file_size,
        "sha256": sha256_hex,
        "downloadUrl": resolved_download_url,
        "releasePageUrl": resolved_release_page_url,
        "changelog": normalized_changelog,
    }


def _safe_reconfigure_stdio() -> None:
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if callable(reconfigure):
            try:
                reconfigure(encoding="utf-8", errors="replace")
            except OSError:
                pass


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="计算 Android 确定性 versionCode 并生成 latest.json 更新清单。"
    )
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument(
        "--compute-version-code",
        metavar="VERSION",
        dest="compute_version_code_ver",
        help="根据严格 SemVer 计算确定性 versionCode 并直接输出整数",
    )
    mode.add_argument(
        "--build-manifest",
        action="store_true",
        help="读取 APK 文件并生成 latest.json 更新清单",
    )

    parser.add_argument("--apk", type=Path, help="已签名的 APK 文件路径")
    parser.add_argument("--version-name", help="发布版本号（如 0.6.1）")
    parser.add_argument(
        "--version-code",
        type=int,
        default=None,
        help="整数 versionCode（未提供时按 --version-name 自动计算）",
    )
    parser.add_argument("--base-url", default=None, help="HTTPS 分发基础地址（如 https://dl.20190415.cloud）")
    parser.add_argument(
        "--apk-object-key",
        default=None,
        help="APK 相对对象键（如 android/releases/FlameContractor-0.6.1-android-universal-release.apk）",
    )
    parser.add_argument(
        "--download-url",
        default=None,
        help="可选直填 HTTPS APK 下载链接（与 --base-url/--apk-object-key 互斥）",
    )
    parser.add_argument("--release-page-url", default="", help="可选 HTTPS GitHub Release 页面地址")
    parser.add_argument("--changelog", default=None, help="内联更新说明文本")
    parser.add_argument("--changelog-file", type=Path, default=None, help="UTF-8 更新说明文件路径")
    parser.add_argument("--min-version-code", type=int, default=0, help="强制更新最低版本码（默认 0）")
    parser.add_argument(
        "--published-at",
        default=None,
        help="可选发布时间（YYYY-MM-DDTHH:MM:SSZ，默认取当前 UTC 时间）",
    )
    parser.add_argument("--output", type=Path, default=None, help="输出 latest.json 文件路径（省略则打印到 stdout）")
    return parser


def main(argv: list[str] | None = None) -> int:
    _safe_reconfigure_stdio()
    parser = _build_parser()
    args = parser.parse_args(argv)

    try:
        if args.compute_version_code_ver is not None:
            print(compute_version_code(args.compute_version_code_ver))
            return 0

        if args.apk is None:
            raise ValueError("--build-manifest 模式必须提供 --apk")
        if args.version_name is None:
            raise ValueError("--build-manifest 模式必须提供 --version-name")
        if args.changelog is not None and args.changelog_file is not None:
            raise ValueError("不能同时指定 --changelog 与 --changelog-file")

        if args.changelog_file is not None:
            if not args.changelog_file.is_file():
                raise FileNotFoundError(f"找不到更新说明文件：{args.changelog_file}")
            changelog_text = args.changelog_file.read_bytes().decode("utf-8")
        else:
            changelog_text = args.changelog if args.changelog is not None else ""

        manifest = build_android_latest_manifest(
            apk_path=args.apk,
            version_name=args.version_name,
            version_code=args.version_code,
            base_url=args.base_url,
            apk_object_key=args.apk_object_key,
            download_url=args.download_url,
            release_page_url=args.release_page_url,
            changelog=changelog_text,
            min_version_code=args.min_version_code,
            published_at=args.published_at,
        )

        rendered = json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
        if args.output is not None:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_bytes(rendered.encode("utf-8"))
            print(
                f"MANIFEST_OK: {args.output} "
                f"(versionName={manifest['versionName']}, "
                f"versionCode={manifest['versionCode']}, "
                f"fileSize={manifest['fileSize']}, "
                f"sha256={manifest['sha256']})"
            )
        else:
            sys.stdout.write(rendered)
    except (FileNotFoundError, ValueError, OSError, UnicodeDecodeError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
