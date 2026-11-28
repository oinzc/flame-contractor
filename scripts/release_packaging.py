"""发版打包与校验和生成工具（Windows 绿色便携版 .zip 与 SHA256SUMS 清单，纯 Python 标准库）。"""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import re
import sys
import zipfile

SAFE_VERSION_RE = re.compile(r"^[0-9A-Za-z._-]+$")

PORTABLE_README_TEXT = (
    "Flame Contractor · 焰之契约者（Windows 绿色便携版）\n"
    "==================================================\n\n"
    "1. 解压本压缩包到任意可写文件夹（不要放在 C:\\Program Files 等只读目录）。\n"
    "2. 双击 Flame Contractor.exe 即可直接启动使用，无需安装或 Docker。\n"
    "3. 数据库、抖音登录态与运行日志默认保存在同级 data/ 目录；升级时保留 data/ 文件夹即可保留全部数据。\n"
    "4. 首次运行如遇 Windows SmartScreen 提示，请点击「更多信息 -> 仍要运行」。\n"
    "5. 如需卸载，在托盘右键选择「彻底退出」后直接删除整个文件夹即可。\n"
)


def _validate_version(version: str) -> str:
    if (
        not isinstance(version, str)
        or not SAFE_VERSION_RE.fullmatch(version)
        or ".." in version
    ):
        raise ValueError(f"非法版本号格式：{version!r}")
    return version


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        while True:
            chunk = fh.read(1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def build_windows_portable_zip(
    exe_path: Path,
    backend_dir: Path,
    version: str,
    out_dir: Path,
) -> Path:
    """将 Tauri 主程序、Sidecar 后端与便携说明打包为可解压即用的 Windows x64 便携版 .zip。"""
    clean_version = _validate_version(version)
    if not exe_path.is_file():
        raise FileNotFoundError(f"缺少桌面端可执行文件：{exe_path}")
    if not backend_dir.is_dir():
        raise FileNotFoundError(f"缺少桌面端后端目录：{backend_dir}")
    sidecar_exe = backend_dir / "flame-backend.exe"
    if not sidecar_exe.is_file():
        raise FileNotFoundError(f"缺少桌面端后端主程序：{sidecar_exe}")

    out_dir.mkdir(parents=True, exist_ok=True)
    zip_path = out_dir / f"FlameContractor-{clean_version}-windows-x64-portable.zip"
    root_folder = f"FlameContractor-{clean_version}-portable"

    backend_files = sorted(
        (p for p in backend_dir.rglob("*") if p.is_file()),
        key=lambda p: p.relative_to(backend_dir).as_posix(),
    )

    with zipfile.ZipFile(
        zip_path, mode="w", compression=zipfile.ZIP_DEFLATED, compresslevel=6
    ) as zf:
        zf.write(exe_path, arcname=f"{root_folder}/Flame Contractor.exe")
        webview_loader = exe_path.parent / "WebView2Loader.dll"
        if webview_loader.is_file():
            zf.write(webview_loader, arcname=f"{root_folder}/WebView2Loader.dll")
        for item in backend_files:
            rel_posix = item.relative_to(backend_dir).as_posix()
            zf.write(item, arcname=f"{root_folder}/backend/{rel_posix}")
        zf.writestr(
            f"{root_folder}/README-portable.txt",
            PORTABLE_README_TEXT.encode("utf-8"),
        )

    return zip_path


def write_sha256sums(output_path: Path, files: list[Path]) -> Path:
    """按 GNU sha256sum 标准格式输出 UTF-8 LF 校验和清单文件。"""
    if not files:
        raise ValueError("必须至少提供一个待校验文件")

    seen_names: set[str] = set()
    entries: list[tuple[str, Path]] = []
    for f in files:
        if not f.is_file():
            raise FileNotFoundError(f"待校验文件不存在：{f}")
        if f.name in seen_names:
            raise ValueError(f"存在重名文件，无法写入唯一基名校验表：{f.name}")
        seen_names.add(f.name)
        entries.append((f.name, f))

    entries.sort(key=lambda item: item[0])
    lines = [f"{_sha256_file(path)}  {name}\n" for name, path in entries]

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_bytes("".join(lines).encode("utf-8"))
    return output_path


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
    parser = argparse.ArgumentParser(description="Flame Contractor 发版打包与校验和工具")
    sub = parser.add_subparsers(dest="command", required=True)

    p_win = sub.add_parser("windows-portable", help="打包 Windows 绿色便携版 .zip")
    p_win.add_argument("--exe", type=Path, required=True, help="Tauri release 主程序路径")
    p_win.add_argument("--backend-dir", type=Path, required=True, help="Sidecar 后端目录")
    p_win.add_argument("--version", type=str, required=True, help="发布版本号（不含 v 前缀）")
    p_win.add_argument("--out-dir", type=Path, required=True, help="输出目录")

    p_sum = sub.add_parser("sha256sums", help="生成标准 GNU SHA256SUMS 校验文件")
    p_sum.add_argument("--output", type=Path, required=True, help="输出校验文件路径")
    p_sum.add_argument("--files", type=Path, nargs="+", required=True, help="待校验文件列表")

    args = parser.parse_args(argv)
    try:
        if args.command == "windows-portable":
            out_zip = build_windows_portable_zip(
                exe_path=args.exe,
                backend_dir=args.backend_dir,
                version=args.version,
                out_dir=args.out_dir,
            )
            print(f"PORTABLE_ZIP_OK: {out_zip}")
        elif args.command == "sha256sums":
            out_txt = write_sha256sums(output_path=args.output, files=list(args.files))
            print(f"SHA256SUMS_OK: {out_txt} ({len(args.files)} files)")
    except (FileNotFoundError, ValueError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
