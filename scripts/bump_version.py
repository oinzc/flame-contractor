"""全端统一版本号校验与同步脚本（纯 Python 标准库）。"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys
from typing import Callable

SEMVER_RE = re.compile(
    r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)"
    r"(?:-((?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*)"
    r"(?:\.(?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*))*))?$"
)


def validate_semver(version: str) -> str:
    """校验严格 SemVer 字符串（不含前导 v 与 +build 元数据）。"""
    if not isinstance(version, str) or not SEMVER_RE.fullmatch(version):
        raise ValueError(
            f"非法版本号：{version!r}（仅允许 X.Y.Z 或 X.Y.Z-prerelease，禁止前导 v 与空白）"
        )
    return version


def _read_utf8_exact(path: Path) -> str:
    if not path.is_file():
        raise FileNotFoundError(f"缺少目标版本文件：{path}")
    return path.read_bytes().decode("utf-8")


def _extract_section_span(text: str, section_name: str, rel_path: str) -> tuple[int, int]:
    header_re = re.compile(rf"(?m)^\[{re.escape(section_name)}\][ \t]*\r?$")
    match = header_re.search(text)
    if match is None:
        raise ValueError(f"{rel_path} 中缺少 [{section_name}] 配置段")
    start = match.end()
    next_header = re.search(r"(?m)^\[[^\r\n]+\][ \t]*\r?$", text[start:])
    end = start + next_header.start() if next_header else len(text)
    return start, end


def _extract_settings_span(text: str, rel_path: str) -> tuple[int, int]:
    match = re.search(r"(?m)^class\s+Settings\b[^\r\n]*:\r?$", text)
    if match is None:
        raise ValueError(f"{rel_path} 中缺少 class Settings 定义")
    start = match.end()
    next_top = re.search(r"(?m)^(?:class|def|async\s+def|@)\b", text[start:])
    end = start + next_top.start() if next_top else len(text)
    return start, end


def _extract_cargo_lock_desktop_span(text: str, rel_path: str) -> tuple[int, int]:
    pkg_re = re.compile(r"(?m)^\[\[package\]\][ \t]*\r?$")
    matches = list(pkg_re.finditer(text))
    found: list[tuple[int, int]] = []
    for idx, m in enumerate(matches):
        start = m.end()
        end = matches[idx + 1].start() if idx + 1 < len(matches) else len(text)
        block = text[start:end]
        if re.search(r'(?m)^name\s*=\s*"flame-contractor-desktop"[ \t]*\r?$', block):
            found.append((start, end))
    if len(found) != 1:
        raise ValueError(
            f"{rel_path} 中预期恰好存在 1 个 flame-contractor-desktop 包段，实际找到 {len(found)} 个"
        )
    return found[0]


def _replace_single_in_span(
    text: str,
    span: tuple[int, int],
    pattern: re.Pattern[str],
    rel_path: str,
    new_raw_value: str | None = None,
) -> tuple[str, str]:
    start, end = span
    sub = text[start:end]
    matches = list(pattern.finditer(sub))
    if len(matches) != 1:
        raise ValueError(
            f"{rel_path} 中预期恰好匹配 1 处版本号声明，实际匹配 {len(matches)} 处"
        )
    m = matches[0]
    old_val = m.group(2)
    if new_raw_value is None:
        return old_val, text
    abs_start = start + m.start(2)
    abs_end = start + m.end(2)
    updated = text[:abs_start] + new_raw_value + text[abs_end:]
    return old_val, updated


def _find_json_string_spans(
    text: str,
    target_paths: tuple[tuple[str, ...], ...],
    rel_path: str,
) -> dict[tuple[str, ...], tuple[int, int, str]]:
    """在不改写 JSON 格式的前提下，精确定位指定键路径的字符串字面量内容起止下标。"""
    n = len(text)
    idx = 0
    results: dict[tuple[str, ...], tuple[int, int, str]] = {}
    targets = set(target_paths)

    def skip_ws(pos: int) -> int:
        while pos < n and text[pos] in " \t\r\n":
            pos += 1
        return pos

    def read_string(pos: int) -> tuple[str, int, int, int]:
        assert text[pos] == '"'
        inner_start = pos + 1
        cur = inner_start
        while cur < n:
            ch = text[cur]
            if ch == "\\":
                cur += 2
                continue
            if ch == '"':
                val = json.loads(text[pos : cur + 1])
                return val, inner_start, cur, cur + 1
            cur += 1
        raise ValueError(f"{rel_path} 存在未闭合 JSON 字符串")

    def walk_value(pos: int, path: tuple[str, ...]) -> int:
        pos = skip_ws(pos)
        if pos >= n:
            raise ValueError(f"{rel_path} JSON 意外结束")
        ch = text[pos]
        if ch == "{":
            pos += 1
            pos = skip_ws(pos)
            if pos < n and text[pos] == "}":
                return pos + 1
            while pos < n:
                pos = skip_ws(pos)
                if text[pos] != '"':
                    raise ValueError(f"{rel_path} JSON 对象键名格式错误")
                key, _, _, pos = read_string(pos)
                pos = skip_ws(pos)
                if pos >= n or text[pos] != ":":
                    raise ValueError(f"{rel_path} JSON 对象缺少冒号")
                pos = walk_value(pos + 1, path + (key,))
                pos = skip_ws(pos)
                if pos < n and text[pos] == ",":
                    pos += 1
                    continue
                if pos < n and text[pos] == "}":
                    return pos + 1
                raise ValueError(f"{rel_path} JSON 对象分隔符错误")
        if ch == "[":
            pos += 1
            pos = skip_ws(pos)
            if pos < n and text[pos] == "]":
                return pos + 1
            arr_idx = 0
            while pos < n:
                pos = walk_value(pos, path + (f"[{arr_idx}]",))
                arr_idx += 1
                pos = skip_ws(pos)
                if pos < n and text[pos] == ",":
                    pos += 1
                    continue
                if pos < n and text[pos] == "]":
                    return pos + 1
                raise ValueError(f"{rel_path} JSON 数组分隔符错误")
        if ch == '"':
            val, inner_start, inner_end, next_pos = read_string(pos)
            if path in targets:
                if path in results:
                    raise ValueError(f"{rel_path} 中路径 {path!r} 重复出现")
                results[path] = (inner_start, inner_end, val)
            return next_pos
        # 数字 / true / false / null
        end_pos = pos
        while end_pos < n and text[end_pos] not in ",}] \t\r\n":
            end_pos += 1
        return end_pos

    walk_value(0, ())
    return results


def _read_and_maybe_update_json(
    text: str,
    rel_path: str,
    paths: tuple[tuple[str, ...], ...],
    new_version: str | None,
) -> tuple[dict[str, str], str]:
    try:
        parsed_before = json.loads(text)
    except json.JSONDecodeError as exc:
        raise ValueError(f"{rel_path} 不是合法 JSON：{exc}") from exc

    spans = _find_json_string_spans(text, paths, rel_path)
    extracted: dict[str, str] = {}
    for p in paths:
        key_label = rel_path if p == ("version",) else f'{rel_path}#packages[""]'
        if p not in spans:
            raise ValueError(f"{rel_path} 缺少预期字段路径 {'.'.join(p) or key_label}")
        extracted[key_label] = spans[p][2]

    if new_version is None:
        return extracted, text

    # 从后往前替换，保持前序下标稳定
    updated = text
    for p in sorted(paths, key=lambda item: spans[item][0], reverse=True):
        s_start, s_end, _ = spans[p]
        encoded_inner = json.dumps(new_version)[1:-1]
        updated = updated[:s_start] + encoded_inner + updated[s_end:]

    parsed_after = json.loads(updated)
    for p in paths:
        cur_before = parsed_before
        cur_after = parsed_after
        for seg in p[:-1]:
            cur_before = cur_before[seg]
            cur_after = cur_after[seg]
        cur_before[p[-1]] = new_version
        assert cur_after[p[-1]] == new_version
    if parsed_after != parsed_before:
        raise ValueError(f"{rel_path} 更新后存在预期外的 JSON 结构变化")

    return extracted, updated


class _RegexTarget:
    def __init__(
        self,
        rel_path: str,
        span_fn: Callable[[str, str], tuple[int, int]],
        pattern: re.Pattern[str],
        suffix: str = "",
    ) -> None:
        self.rel_path = rel_path
        self.span_fn = span_fn
        self.pattern = pattern
        self.suffix = suffix

    def process(self, text: str, new_version: str | None) -> tuple[dict[str, str], str]:
        span = self.span_fn(text, self.rel_path)
        new_raw = f"{new_version}{self.suffix}" if new_version is not None else None
        raw_old, updated = _replace_single_in_span(
            text, span, self.pattern, self.rel_path, new_raw
        )
        if self.suffix:
            if not raw_old.endswith(self.suffix):
                raise ValueError(
                    f"{self.rel_path} 默认版本号必须以 {self.suffix!r} 结尾，当前为：{raw_old!r}"
                )
            version_val = raw_old.removesuffix(self.suffix)
        else:
            version_val = raw_old
        return {self.rel_path: version_val}, updated


_REGEX_TARGETS: tuple[_RegexTarget, ...] = (
    _RegexTarget(
        rel_path="backend/app/core/config.py",
        span_fn=_extract_settings_span,
        pattern=re.compile(r'(?m)^([ \t]*version:\s*str\s*=\s*")([^"]+)(")'),
    ),
    _RegexTarget(
        rel_path="backend/pyproject.toml",
        span_fn=lambda txt, rel: _extract_section_span(txt, "project", rel),
        pattern=re.compile(r'(?m)^(version\s*=\s*")([^"]+)(")'),
    ),
    _RegexTarget(
        rel_path="desktop/src-tauri/Cargo.toml",
        span_fn=lambda txt, rel: _extract_section_span(txt, "package", rel),
        pattern=re.compile(r'(?m)^(version\s*=\s*")([^"]+)(")'),
    ),
    _RegexTarget(
        rel_path="desktop/src-tauri/Cargo.lock",
        span_fn=_extract_cargo_lock_desktop_span,
        pattern=re.compile(r'(?m)^(version\s*=\s*")([^"]+)(")'),
    ),
    _RegexTarget(
        rel_path="android/app/build.gradle.kts",
        span_fn=lambda txt, _rel: (0, len(txt)),
        pattern=re.compile(
            r'(?m)^([ \t]*val\s+requestedVersionName\s*=[^\r\n]*\?:\s*")([^"]+)(")'
        ),
        suffix="-dev",
    ),
)

_JSON_TARGETS: tuple[tuple[str, tuple[tuple[str, ...], ...]], ...] = (
    ("frontend/package.json", (("version",),)),
    ("frontend/package-lock.json", (("version",), ("packages", "", "version"))),
    ("desktop/package.json", (("version",),)),
    ("desktop/package-lock.json", (("version",), ("packages", "", "version"))),
    ("desktop/src-tauri/tauri.conf.json", (("version",),)),
)


def read_repo_versions(repo_root: Path) -> dict[str, str]:
    """读取仓库内全部 10 个版本声明文件中的版本号（共 12 个检查点）。"""
    versions: dict[str, str] = {}
    for target in _REGEX_TARGETS:
        text = _read_utf8_exact(repo_root / target.rel_path)
        extracted, _ = target.process(text, None)
        versions.update(extracted)
    for rel_path, json_paths in _JSON_TARGETS:
        text = _read_utf8_exact(repo_root / rel_path)
        extracted, _ = _read_and_maybe_update_json(text, rel_path, json_paths, None)
        versions.update(extracted)
    return versions


def check_repo_versions(repo_root: Path, tag: str | None = None) -> str:
    """校验全仓库版本声明完全一致；如提供 Git tag，一并校验与 tag 对齐。"""
    versions = read_repo_versions(repo_root)
    for key, val in versions.items():
        try:
            validate_semver(val)
        except ValueError as exc:
            raise ValueError(f"{key} 版本号格式非法：{val!r}") from exc

    unique = sorted(set(versions.values()))
    if len(unique) != 1:
        detail = "; ".join(f"{k}={v}" for k, v in versions.items())
        raise ValueError(f"仓库各端版本号不一致：{detail}")

    unified = unique[0]
    if tag is not None:
        if not tag.startswith("v"):
            raise ValueError(f"Git tag 必须以 'v' 开头，实际为：{tag!r}")
        tag_version = tag[1:]
        validate_semver(tag_version)
        if tag_version != unified:
            raise ValueError(
                f"Git tag {tag!r} 对应的版本 {tag_version} 与仓库声明版本 {unified} 不一致"
            )
    return unified


def bump_repo_versions(repo_root: Path, new_version: str) -> list[Path]:
    """原子同步全仓库全部 10 个版本声明文件到指定版本号。"""
    validate_semver(new_version)
    planned_writes: list[tuple[Path, bytes]] = []

    for target in _REGEX_TARGETS:
        path = repo_root / target.rel_path
        text = _read_utf8_exact(path)
        _, updated = target.process(text, new_version)
        planned_writes.append((path, updated.encode("utf-8")))

    for rel_path, json_paths in _JSON_TARGETS:
        path = repo_root / rel_path
        text = _read_utf8_exact(path)
        _, updated = _read_and_maybe_update_json(text, rel_path, json_paths, new_version)
        planned_writes.append((path, updated.encode("utf-8")))

    # 所有解析与新内容生成全部成功后才落盘，避免部分写入污染工作区。
    written: list[Path] = []
    for path, content_bytes in planned_writes:
        path.write_bytes(content_bytes)
        written.append(path)
    return written


def _safe_reconfigure_stdio() -> None:
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if callable(reconfigure):
            try:
                reconfigure(errors="replace")
            except OSError:
                pass


def main(argv: list[str] | None = None) -> int:
    _safe_reconfigure_stdio()
    parser = argparse.ArgumentParser(description="校验并同步 Flame Contractor 全端版本号。")
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
        help="仓库根目录路径",
    )
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true", help="校验全端版本声明是否一致")
    mode.add_argument("--set", metavar="VERSION", dest="new_version", help="同步全端至目标版本")
    parser.add_argument(
        "--tag",
        default=None,
        help="可选 Git tag（如 v0.4.0），仅可与 --check 同用，校验其与仓库版本一致",
    )
    args = parser.parse_args(argv)

    if args.tag is not None and not args.check:
        print("错误：--tag 只能与 --check 一起使用", file=sys.stderr)
        return 1

    try:
        if args.check:
            version = check_repo_versions(args.root, tag=args.tag)
            if args.tag:
                print(f"VERSION_CHECK_OK: {version} (tag={args.tag})")
            else:
                print(f"VERSION_CHECK_OK: {version}")
        else:
            updated = bump_repo_versions(args.root, args.new_version)
            verified = check_repo_versions(args.root)
            print(f"VERSION_BUMP_OK: {verified} ({len(updated)} files updated)")
    except (FileNotFoundError, ValueError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
