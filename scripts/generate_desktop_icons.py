"""生成 Flame Contractor 桌面端应用图标（纯 Python 标准库，无需外部图形依赖）。

视觉风格对齐工作台基线：碳黑圆角底板（#111317）+ 细腻微边框 + 暖橙色火花剪影（#f97316 / #fb923c）。
产物写入 `desktop/src-tauri/icons/`：32x32.png、128x128.png、128x128@2x.png、icon.ico。
"""

from __future__ import annotations

import math
from pathlib import Path
import struct
import zlib

REPO_ROOT = Path(__file__).resolve().parents[1]
ICONS_DIR = REPO_ROOT / "desktop" / "src-tauri" / "icons"


def _png_chunk(tag: bytes, data: bytes) -> bytes:
    crc = zlib.crc32(tag + data) & 0xFFFFFFFF
    return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", crc)


def _encode_png(width: int, height: int, rgba: bytes) -> bytes:
    stride = width * 4
    raw = bytearray()
    for y in range(height):
        raw.append(0)  # filter type 0
        start = y * stride
        raw.extend(rgba[start : start + stride])
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + _png_chunk(b"IHDR", ihdr)
        + _png_chunk(b"IDAT", zlib.compress(bytes(raw), level=9))
        + _png_chunk(b"IEND", b"")
    )


def _signed_rounded_rect(px: float, py: float, half_w: float, half_h: float, radius: float) -> float:
    qx = abs(px) - (half_w - radius)
    qy = abs(py) - (half_h - radius)
    return math.hypot(max(qx, 0.0), max(qy, 0.0)) + min(max(qx, qy), 0.0) - radius


def _in_outer_flame(nx: float, ny: float) -> bool:
    """光滑无断层的现代水滴火花剪影（顶部尖锐轻曳、底部圆润闭合）。"""
    # 火焰垂直区间：ny ∈ [-0.66, 0.64]
    y_top, y_bot = -0.66, 0.64
    if ny < y_top or ny > y_bot:
        return False
    t = (ny - y_top) / (y_bot - y_top)  # 0 = 焰尖, 1 = 焰底
    # 经典水滴轮廓：t^0.95 * sqrt(1 - t^2) 乘以缩放，底部天然圆润无台阶
    # 加入轻微 S 型中轴偏摆让火焰更有动态感
    center_x = -0.05 * math.sin(t * math.pi * 1.15) * (1.0 - t)
    half_width = 0.74 * math.pow(t, 0.85) * math.sqrt(max(0.0, 1.0 - t * t))
    # 底部半圆平滑衔接（u = (ny - 0.19) / 0.45）
    if ny > 0.19:
        u = (ny - 0.19) / 0.45
        if u <= 1.0:
            circle_hw = 0.45 * math.sqrt(max(0.0, 1.0 - u * u))
            # 平滑混合，绝不产生台阶
            blend = min(1.0, (ny - 0.19) / 0.18)
            half_width = half_width * (1.0 - blend) + max(half_width, circle_hw) * blend
    return abs(nx - center_x) <= half_width


def _in_inner_flame(nx: float, ny: float) -> bool:
    y_top, y_bot = -0.08, 0.56
    if ny < y_top or ny > y_bot:
        return False
    t = (ny - y_top) / (y_bot - y_top)
    center_x = 0.02 * math.sin(t * math.pi)
    half_width = 0.37 * math.pow(t, 0.88) * math.sqrt(max(0.0, 1.0 - t * t))
    if ny > 0.34:
        u = (ny - 0.34) / 0.22
        if u <= 1.0:
            circle_hw = 0.22 * math.sqrt(max(0.0, 1.0 - u * u))
            blend = min(1.0, (ny - 0.34) / 0.10)
            half_width = half_width * (1.0 - blend) + max(half_width, circle_hw) * blend
    return abs(nx - center_x) <= half_width


def _render_icon(size: int) -> bytes:
    """使用 4x4 超采样渲染高清晰 RGBA 像素缓冲。"""
    buf = bytearray(size * size * 4)
    samples = (0.125, 0.375, 0.625, 0.875)
    for y in range(size):
        for x in range(size):
            r_acc = g_acc = b_acc = a_acc = 0.0
            for sy in samples:
                for sx in samples:
                    nx = ((x + sx) / size) * 2.0 - 1.0
                    ny = ((y + sy) / size) * 2.0 - 1.0
                    d_bg = _signed_rounded_rect(nx, ny, 0.92, 0.92, 0.26)
                    if d_bg > 0.0:
                        continue
                    # 碳黑底板 + 1px 细腻边框
                    if d_bg > -0.045:
                        r, g, b, a = 42.0, 46.0, 54.0, 255.0
                    else:
                        # 极克制微纵深（顶略亮底略沉）
                        shade = 1.0 - 0.12 * ((ny + 1.0) * 0.5)
                        r, g, b, a = 19.0 * shade, 21.0 * shade, 26.0 * shade, 255.0

                    if _in_outer_flame(nx, ny):
                        # 外焰：#ea580c -> #fb923c 柔和渐变
                        blend = max(0.0, min(1.0, (0.62 - ny) / 1.24))
                        r = 234.0 + (251.0 - 234.0) * blend
                        g = 88.0 + (146.0 - 88.0) * blend
                        b = 12.0 + (60.0 - 12.0) * blend
                    if _in_inner_flame(nx, ny):
                        # 焰心：亮暖金 #fde047 -> #fed7aa
                        blend = max(0.0, min(1.0, (0.56 - ny) / 0.70))
                        r = 254.0
                        g = 215.0 + (240.0 - 215.0) * blend
                        b = 138.0 + (170.0 - 138.0) * blend

                    r_acc += r
                    g_acc += g
                    b_acc += b
                    a_acc += a

            inv = 1.0 / 16.0
            a_val = a_acc * inv
            idx = (y * size + x) * 4
            if a_val > 0.0:
                scale = 255.0 / a_val if a_val < 255.0 else 1.0
                buf[idx] = min(255, int(round(r_acc * inv * scale)))
                buf[idx + 1] = min(255, int(round(g_acc * inv * scale)))
                buf[idx + 2] = min(255, int(round(b_acc * inv * scale)))
                buf[idx + 3] = min(255, int(round(a_val)))
    return bytes(buf)


def _encode_ico(png_entries: list[tuple[int, bytes]]) -> bytes:
    """将多尺寸 PNG 封装为标准 Windows ICO（Vista+ 原生支持 PNG 帧）。"""
    header = struct.pack("<HHH", 0, 1, len(png_entries))
    directory = bytearray()
    offset = 6 + 16 * len(png_entries)
    payload = bytearray()
    for size, png_bytes in png_entries:
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


def main() -> None:
    ICONS_DIR.mkdir(parents=True, exist_ok=True)
    rendered: dict[int, bytes] = {}
    for size in (16, 32, 48, 128, 256):
        rendered[size] = _encode_png(size, size, _render_icon(size))

    (ICONS_DIR / "32x32.png").write_bytes(rendered[32])
    (ICONS_DIR / "128x128.png").write_bytes(rendered[128])
    (ICONS_DIR / "128x128@2x.png").write_bytes(rendered[256])
    (ICONS_DIR / "icon.png").write_bytes(rendered[256])
    (ICONS_DIR / "icon.ico").write_bytes(
        _encode_ico([(s, rendered[s]) for s in (16, 32, 48, 128, 256)])
    )
    print(f"Generated desktop icons in {ICONS_DIR}", flush=True)


if __name__ == "__main__":
    main()
