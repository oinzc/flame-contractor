"""从品牌定稿母版一键生成 Flame Contractor 三端应用图标（Windows Tauri / Web / Android）。

默认读取仓库内无损定稿母版 `scripts/assets/brand-icon-master.png`（1024×1024，B-4P1 × SG-F3 朝阳琉璃宝石玫瑰窗），
统一输出以下三端资源：

- desktop (`desktop/src-tauri/icons/`):
  32x32.png、128x128.png、128x128@2x.png、icon.png、icon.ico（多帧 16/24/32/48/64/128/256）
- web (`frontend/public/`):
  favicon.ico（多帧 16/32/48）、favicon-32x32.png、apple-touch-icon.png（180×180 全出血）、brand-icon-512.png（512×512）
- android (`android/app/src/main/res/mipmap-*/ic_launcher_foreground.png`):
  mdpi(108) / hdpi(162) / xhdpi(216) / xxhdpi(324) / xxxhdpi(432)，按 76% 安全区标定 + 琉璃边缘无痕延展，
  确保 72dp 可见区与 66dp 圆形安全区在圆形/超椭圆/方圆角下均完整保留角色面部、耳链与领口金火徽。

用法：
    python scripts/generate_desktop_icons.py
    python scripts/generate_desktop_icons.py --targets desktop web
    python scripts/generate_desktop_icons.py --source path/to/master.png
"""

from __future__ import annotations

import argparse
from io import BytesIO
from pathlib import Path
import struct
from typing import Iterable

from PIL import Image, ImageDraw, ImageFilter

REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MASTER = REPO_ROOT / "scripts" / "assets" / "brand-icon-master.png"
DESKTOP_ICONS_DIR = REPO_ROOT / "desktop" / "src-tauri" / "icons"
WEB_PUBLIC_DIR = REPO_ROOT / "frontend" / "public"
ANDROID_RES_DIR = REPO_ROOT / "android" / "app" / "src" / "main" / "res"

ANDROID_ADAPTIVE_CONTENT_FRAC = 0.76
ANDROID_DENSITIES: tuple[tuple[str, int], ...] = (
    ("mipmap-mdpi", 108),
    ("mipmap-hdpi", 162),
    ("mipmap-xhdpi", 216),
    ("mipmap-xxhdpi", 324),
    ("mipmap-xxxhdpi", 432),
)


def _to_png_bytes(img: Image.Image) -> bytes:
    buf = BytesIO()
    img.save(buf, format="PNG", optimize=True)
    return buf.getvalue()


def _encode_ico(png_entries: Iterable[tuple[int, bytes]]) -> bytes:
    """将多尺寸 PNG 封装为标准 Windows ICO（Vista+ / Chromium 原生支持 PNG 帧）。"""
    entries = list(png_entries)
    header = struct.pack("<HHH", 0, 1, len(entries))
    directory = bytearray()
    offset = 6 + 16 * len(entries)
    payload = bytearray()
    for size, png_bytes in entries:
        dim = 0 if size >= 256 else size
        directory.extend(
            struct.pack(
                "<BBBBHHII",
                dim,
                dim,
                0,
                0,
                1,
                32,
                len(png_bytes),
                offset,
            )
        )
        payload.extend(png_bytes)
        offset += len(png_bytes)
    return header + bytes(directory) + bytes(payload)


def _render_rounded_icon(
    master: Image.Image,
    size: int,
    radius_frac: float = 0.22,
    border_rgba: tuple[int, int, int, int] | None = (255, 255, 255, 28),
) -> Image.Image:
    """生成带平滑抗锯齿圆角与细腻内描边的方圆角图标。"""
    # 4x 超采样生成圆角蒙版，消除小尺寸边缘锯齿
    scale = 4
    hi = size * scale
    radius = max(2, round(hi * radius_frac))
    mask_hi = Image.new("L", (hi, hi), 0)
    ImageDraw.Draw(mask_hi).rounded_rectangle(
        (0, 0, hi - 1, hi - 1), radius=radius, fill=255
    )
    mask = mask_hi.resize((size, size), Image.Resampling.LANCZOS)

    resized = master.resize((size, size), Image.Resampling.LANCZOS)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(resized, (0, 0), mask)

    if size >= 28 and border_rgba is not None:
        r_px = max(2, round(size * radius_frac))
        overlay = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        ImageDraw.Draw(overlay).rounded_rectangle(
            (0, 0, size - 1, size - 1), radius=r_px, outline=border_rgba, width=1
        )
        out = Image.alpha_composite(out, overlay)
    return out


def _build_android_adaptive_432(
    master: Image.Image, content_frac: float = ANDROID_ADAPTIVE_CONTENT_FRAC
) -> Image.Image:
    """构造 432×432（108dp @ xxxhdpi）自适应图标前景：76% 核心画面 + 琉璃边缘无痕延展。"""
    canvas_size = 432
    inner_size = round(canvas_size * content_frac)
    pad = (canvas_size - inner_size) // 2

    # 外圈延展：柔和琉璃氛围底，桌面视差晃动时绝不露黑边
    bleed = master.resize(
        (canvas_size, canvas_size), Image.Resampling.LANCZOS
    ).filter(ImageFilter.GaussianBlur(14))
    core = master.resize((inner_size, inner_size), Image.Resampling.LANCZOS)

    # 仅羽化核心外圈 8px，确保 72dp 可见区（±36dp）完全落在 100% 锐利区内
    feather_px = 8
    mask = Image.new("L", (inner_size, inner_size), 255)
    draw = ImageDraw.Draw(mask)
    for k in range(feather_px):
        alpha = int(255 * (k + 1) / feather_px)
        draw.rectangle(
            (k, k, inner_size - 1 - k, inner_size - 1 - k), outline=alpha
        )

    canvas = bleed.copy()
    canvas.paste(core, (pad, pad), mask)
    return canvas


def generate_desktop_icons(master: Image.Image) -> None:
    DESKTOP_ICONS_DIR.mkdir(parents=True, exist_ok=True)
    rendered: dict[int, bytes] = {}
    for size in (16, 24, 32, 48, 64, 128, 256):
        is_tiny = size <= 24
        img = _render_rounded_icon(
            master,
            size,
            radius_frac=0.18 if is_tiny else 0.22,
            border_rgba=None if is_tiny else (255, 255, 255, 28),
        )
        rendered[size] = _to_png_bytes(img)

    (DESKTOP_ICONS_DIR / "32x32.png").write_bytes(rendered[32])
    (DESKTOP_ICONS_DIR / "128x128.png").write_bytes(rendered[128])
    (DESKTOP_ICONS_DIR / "128x128@2x.png").write_bytes(rendered[256])
    (DESKTOP_ICONS_DIR / "icon.png").write_bytes(rendered[256])
    (DESKTOP_ICONS_DIR / "icon.ico").write_bytes(
        _encode_ico((s, rendered[s]) for s in (16, 24, 32, 48, 64, 128, 256))
    )
    print(f"[desktop] Generated icons in {DESKTOP_ICONS_DIR}", flush=True)


def generate_web_icons(master: Image.Image) -> None:
    WEB_PUBLIC_DIR.mkdir(parents=True, exist_ok=True)
    ico_entries: list[tuple[int, bytes]] = []
    for size in (16, 32, 48):
        is_tiny = size <= 24
        img = _render_rounded_icon(
            master,
            size,
            radius_frac=0.18 if is_tiny else 0.22,
            border_rgba=None if is_tiny else (255, 255, 255, 28),
        )
        png = _to_png_bytes(img)
        ico_entries.append((size, png))
        if size == 32:
            (WEB_PUBLIC_DIR / "favicon-32x32.png").write_bytes(png)

    (WEB_PUBLIC_DIR / "favicon.ico").write_bytes(_encode_ico(ico_entries))

    # iOS/macOS 会自动为 apple-touch-icon 添加圆角蒙版，必须输出全出血正方形
    apple_icon = master.resize((180, 180), Image.Resampling.LANCZOS)
    (WEB_PUBLIC_DIR / "apple-touch-icon.png").write_bytes(_to_png_bytes(apple_icon))

    # 高清 512×512 全出血正方形供 Web 登录页 / 分享卡片使用（容器自行裁剪圆角）
    brand_512 = master.resize((512, 512), Image.Resampling.LANCZOS)
    (WEB_PUBLIC_DIR / "brand-icon-512.png").write_bytes(_to_png_bytes(brand_512))
    print(f"[web] Generated favicons & brand assets in {WEB_PUBLIC_DIR}", flush=True)


def generate_android_icons(master: Image.Image) -> None:
    adaptive_432 = _build_android_adaptive_432(master)
    for folder, size in ANDROID_DENSITIES:
        out_dir = ANDROID_RES_DIR / folder
        out_dir.mkdir(parents=True, exist_ok=True)
        img = (
            adaptive_432
            if size == 432
            else adaptive_432.resize((size, size), Image.Resampling.LANCZOS)
        )
        (out_dir / "ic_launcher_foreground.png").write_bytes(_to_png_bytes(img))
    print(f"[android] Generated adaptive foreground mipmaps in {ANDROID_RES_DIR}", flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Generate Flame Contractor icons for Windows Tauri, Web, and Android."
    )
    parser.add_argument(
        "--source",
        type=Path,
        default=DEFAULT_MASTER,
        help="Path to the square master brand icon PNG/JPG (default: scripts/assets/brand-icon-master.png)",
    )
    parser.add_argument(
        "--targets",
        nargs="+",
        choices=("all", "desktop", "web", "android"),
        default=["all"],
        help="Target platforms to generate (default: all)",
    )
    args = parser.parse_args()

    source_path = args.source.resolve()
    if not source_path.is_file():
        raise FileNotFoundError(f"Master brand icon not found: {source_path}")

    master = Image.open(source_path).convert("RGBA")
    if master.width != master.height:
        raise ValueError(
            f"Master brand icon must be square, got {master.width}x{master.height}"
        )

    targets = (
        {"desktop", "web", "android"}
        if "all" in args.targets
        else set(args.targets)
    )
    if "desktop" in targets:
        generate_desktop_icons(master)
    if "web" in targets:
        generate_web_icons(master)
    if "android" in targets:
        generate_android_icons(master)


if __name__ == "__main__":
    main()
