"""跨端标准 `.zip` 完整备份归档编解码器（纯 Python 3.12 标准库，零外部依赖）。

归档结构（无密码 PKZIP DEFLATED）：
- backup.json                     # UTF-8，schemaVersion=1（不输出宿主 image_path / 凭据 / 昵称）
- manifest.json                   # {"format_version": 1, "app": "flame-contractor", "images": {...}}
- template_images/<id>.<ext>      # 经魔数校验与隐私元数据剥离的真实图片（.jpg/.png/.webp/.gif）

导入同时自动识别 `.zip`（`PK\\x03\\x04`）与纯 `.json`，并严格防护 ZipSlip / ZipBomb 与伪装非图片文件。
"""

from __future__ import annotations

import io
import json
import re
import stat
import struct
import zipfile
from pathlib import Path
from typing import Any
from uuid import uuid4

from pydantic import ValidationError
from sqlmodel import Session

from .backup import BACKUP_SCHEMA_VERSION, export_backup, import_backup
from .core.config import PROJECT_ROOT, Settings
from .models import MessageTemplate, TemplateType
from .schemas import BackupImportResult, BackupRead

BACKUP_ARCHIVE_FORMAT_VERSION = 1
BACKUP_ARCHIVE_APP = "flame-contractor"

ZIP_MAGIC = b"PK\x03\x04"
ZIP_EMPTY_MAGIC = b"PK\x05\x06"

MAX_ARCHIVE_COMPRESSED_BYTES = 60 * 1024 * 1024  # 60 MB
MAX_ARCHIVE_ENTRIES = 200
MAX_BACKUP_JSON_BYTES = 20 * 1024 * 1024  # 20 MB
MAX_MANIFEST_JSON_BYTES = 2 * 1024 * 1024  # 2 MB
MAX_IMAGE_BYTES = 15 * 1024 * 1024  # 15 MB (15_728_640)
MAX_GIF_BYTES = 5 * 1024 * 1024  # 5 MB (5_242_880)
MAX_TOTAL_UNCOMPRESSED_BYTES = 80 * 1024 * 1024  # 80 MB
_READ_CHUNK_SIZE = 64 * 1024

IMAGE_ENTRY_RE = re.compile(
    r"^template_images/[A-Za-z0-9_-]{1,80}\.(jpg|jpeg|png|webp|gif)$"
)
_SAFE_STEM_RE = re.compile(r"^[A-Za-z0-9_-]{1,80}$")

_PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
_PNG_STRIP_CHUNKS = frozenset({b"eXIf", b"tEXt", b"zTXt", b"iTXt", b"tIME"})

_JPEG_SOI = b"\xff\xd8"
_JPEG_EOI = b"\xff\xd9"
_JPEG_MARKER_APP1 = 0xE1
_JPEG_MARKER_APP13 = 0xED
_JPEG_MARKER_COM = 0xFE
_JPEG_MARKER_SOS = 0xDA
_JPEG_MARKER_EOI = 0xD9
_JPEG_MARKER_TEM = 0x01


def resolve_template_images_dir(settings: Settings) -> Path:
    """解析受管图片模板目录。

    优先使用显式 data_dir；测试环境若重定向了 profiles_dir 则自动落到其同级目录下，
    生产默认配置回退到 PROJECT_ROOT / "data" / "template_images"。
    """
    if settings.data_dir is not None:
        return settings.data_dir / "template_images"
    default_profiles = PROJECT_ROOT / "data" / "profiles"
    if settings.profiles_dir != default_profiles:
        return settings.profiles_dir.parent / "template_images"
    return PROJECT_ROOT / "data" / "template_images"


# ---------------------------------------------------------------- image validation & sanitization


def detect_image_extension(data: bytes) -> str | None:
    """按文件魔数与大小上限识别真实图片扩展名；非支持图片或超限返回 None。"""
    size = len(data)
    if size < 4 or size > MAX_IMAGE_BYTES:
        return None
    if data.startswith(_PNG_SIGNATURE):
        return "png"
    if data.startswith(b"\xff\xd8\xff"):
        return "jpg"
    if size >= 14 and (data.startswith(b"GIF87a") or data.startswith(b"GIF89a")):
        if size > MAX_GIF_BYTES:
            return None
        return "gif"
    if size >= 12 and data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "webp"
    return None


def _extract_exif_orientation(app1_payload: bytes) -> int | None:
    """从 JPEG APP1 EXIF 数据块中仅提取 Orientation (0x0112)，不保留其余隐私信息。"""
    if not app1_payload.startswith(b"Exif\x00\x00") or len(app1_payload) < 14:
        return None
    tiff_start = 6
    byte_order = app1_payload[tiff_start : tiff_start + 2]
    if byte_order == b"MM":
        endian = ">"
    elif byte_order == b"II":
        endian = "<"
    else:
        return None

    magic, ifd0_offset = struct.unpack(
        endian + "HI", app1_payload[tiff_start + 2 : tiff_start + 8]
    )
    if magic != 42:
        return None

    ifd_pos = tiff_start + ifd0_offset
    if ifd_pos < tiff_start + 8 or ifd_pos + 2 > len(app1_payload):
        return None

    entry_count = struct.unpack(
        endian + "H", app1_payload[ifd_pos : ifd_pos + 2]
    )[0]
    for idx in range(min(entry_count, 256)):
        entry_pos = ifd_pos + 2 + idx * 12
        if entry_pos + 12 > len(app1_payload):
            break
        tag, field_type, count = struct.unpack(
            endian + "HHI", app1_payload[entry_pos : entry_pos + 8]
        )
        if tag == 0x0112 and field_type == 3 and count == 1:
            orientation = struct.unpack(
                endian + "H", app1_payload[entry_pos + 8 : entry_pos + 10]
            )[0]
            if 1 <= orientation <= 8:
                return orientation
            return None
    return None


def _build_minimal_orientation_app1(orientation: int) -> bytes:
    """构造仅包含 Orientation 标签的最小无隐私 EXIF APP1 段（不含 GPS/设备/时间/缩略图）。"""
    tiff_payload = (
        b"MM"
        + struct.pack(">HI", 42, 8)
        + struct.pack(">H", 1)
        + struct.pack(">HHIHH", 0x0112, 3, 1, orientation, 0)
        + struct.pack(">I", 0)
    )
    exif_payload = b"Exif\x00\x00" + tiff_payload
    return b"\xff\xe1" + struct.pack(">H", len(exif_payload) + 2) + exif_payload


def strip_jpeg_metadata(data: bytes) -> bytes:
    """剥离 JPEG 中的 APP13、COM 与隐私 APP1（EXIF/XMP）；仅在非正向时保留最小方向标签。"""
    n = len(data)
    if n < 4 or not data.startswith(b"\xff\xd8\xff"):
        raise ValueError("JPEG 文件头无效")

    out = bytearray(_JPEG_SOI)
    pos = 2
    orientation_emitted = False
    saw_sos = False
    saw_eoi = False

    while pos < n:
        if data[pos] != 0xFF:
            raise ValueError("JPEG 分段标记损坏")
        while pos < n and data[pos] == 0xFF:
            pos += 1
        if pos >= n:
            raise ValueError("JPEG 分段标记截断")
        marker = data[pos]
        pos += 1

        if marker == _JPEG_MARKER_EOI:
            out.extend(_JPEG_EOI)
            saw_eoi = True
            break
        if marker == _JPEG_MARKER_TEM or 0xD0 <= marker <= 0xD7:
            out.extend((0xFF, marker))
            continue
        if marker == 0x00 or marker == 0xD8:
            raise ValueError("JPEG 分段结构无效")

        if pos + 2 > n:
            raise ValueError("JPEG 分段长度截断")
        seg_len = struct.unpack(">H", data[pos : pos + 2])[0]
        if seg_len < 2 or pos + seg_len > n:
            raise ValueError("JPEG 分段长度越界")
        seg_end = pos + seg_len

        if marker == _JPEG_MARKER_SOS:
            saw_sos = True
            scan_pos = seg_end
            while scan_pos < n:
                if data[scan_pos] != 0xFF:
                    scan_pos += 1
                    continue
                marker_start = scan_pos
                while scan_pos < n and data[scan_pos] == 0xFF:
                    scan_pos += 1
                if scan_pos >= n:
                    scan_pos = n
                    break
                nxt = data[scan_pos]
                if nxt == 0x00 or 0xD0 <= nxt <= 0xD7:
                    scan_pos += 1
                    continue
                scan_pos = marker_start
                break
            out.extend((0xFF, marker))
            out.extend(data[pos:scan_pos])
            pos = scan_pos
            continue

        if marker in (_JPEG_MARKER_APP13, _JPEG_MARKER_COM):
            pos = seg_end
            continue

        if marker == _JPEG_MARKER_APP1:
            if not orientation_emitted:
                orientation = _extract_exif_orientation(data[pos + 2 : seg_end])
                if orientation is not None and orientation != 1:
                    out.extend(_build_minimal_orientation_app1(orientation))
                    orientation_emitted = True
            pos = seg_end
            continue

        out.extend((0xFF, marker))
        out.extend(data[pos:seg_end])
        pos = seg_end

    if not saw_sos and not saw_eoi:
        raise ValueError("JPEG 缺少有效图像数据")
    if not saw_eoi:
        out.extend(_JPEG_EOI)
    return bytes(out)


def strip_png_metadata(data: bytes) -> bytes:
    """剥离 PNG 中的 eXIf / tEXt / zTXt / iTXt / tIME 元数据块并校验基本结构。"""
    n = len(data)
    if not data.startswith(_PNG_SIGNATURE):
        raise ValueError("PNG 文件头无效")

    out = bytearray(_PNG_SIGNATURE)
    pos = len(_PNG_SIGNATURE)
    saw_ihdr = False
    saw_iend = False

    while pos < n:
        if pos + 12 > n:
            raise ValueError("PNG 数据块头截断")
        length = struct.unpack(">I", data[pos : pos + 4])[0]
        chunk_type = data[pos + 4 : pos + 8]
        chunk_end = pos + 12 + length
        if chunk_end > n:
            raise ValueError("PNG 数据块长度越界")

        if pos == len(_PNG_SIGNATURE):
            if chunk_type != b"IHDR":
                raise ValueError("PNG 首个数据块必须为 IHDR")
            saw_ihdr = True

        if chunk_type not in _PNG_STRIP_CHUNKS:
            out.extend(data[pos:chunk_end])

        pos = chunk_end
        if chunk_type == b"IEND":
            saw_iend = True
            break

    if not saw_ihdr or not saw_iend:
        raise ValueError("PNG 结构不完整")
    return bytes(out)


def validate_and_sanitize_image(data: bytes) -> tuple[str, bytes]:
    """校验真实图片魔数/大小并剥离隐私元数据，返回 (规范扩展名, 清理后的字节)。"""
    size = len(data)
    if size == 0:
        raise ValueError("图片文件为空")
    if size > MAX_IMAGE_BYTES:
        raise ValueError("图片超过 15 MB 上限")
    if (data.startswith(b"GIF87a") or data.startswith(b"GIF89a")) and size > MAX_GIF_BYTES:
        raise ValueError("GIF 图片超过 5 MB 上限")

    ext = detect_image_extension(data)
    if ext is None:
        raise ValueError("不支持的图片格式或文件已损坏（仅支持 JPG / PNG / WEBP / GIF）")

    if ext == "jpg":
        return ext, strip_jpeg_metadata(data)
    if ext == "png":
        return ext, strip_png_metadata(data)
    return ext, data


def _read_and_sanitize_local_image(raw_path: str | None) -> tuple[str, bytes] | None:
    """安全读取本机图片模板文件：仅允许存在的普通真图，非图/缺失/超限一律跳过。"""
    if not raw_path or not isinstance(raw_path, str):
        return None
    cleaned = raw_path.strip()
    if not cleaned:
        return None
    path = Path(cleaned)
    try:
        st = path.stat()
    except OSError:
        return None
    if not stat.S_ISREG(st.st_mode):
        return None
    if st.st_size <= 0 or st.st_size > MAX_IMAGE_BYTES:
        return None
    try:
        with path.open("rb") as fh:
            raw = fh.read(MAX_IMAGE_BYTES + 1)
    except OSError:
        return None
    if len(raw) == 0 or len(raw) > MAX_IMAGE_BYTES:
        return None
    try:
        return validate_and_sanitize_image(raw)
    except ValueError:
        return None


# ---------------------------------------------------------------- archive export


def _make_archive_image_entry(
    template_id: str, ext: str, used_entries: set[str]
) -> str:
    stem = template_id if _SAFE_STEM_RE.match(template_id) else "img"
    candidate = f"template_images/{stem}.{ext}"
    if candidate not in used_entries:
        used_entries.add(candidate)
        return candidate
    idx = 2
    while True:
        candidate = f"template_images/{stem[:70]}_{idx}.{ext}"
        if candidate not in used_entries:
            used_entries.add(candidate)
            return candidate
        idx += 1


def export_backup_archive(session: Session, user_id: str) -> bytes:
    """导出包含已清理图片模板的标准 `.zip` 完整备份包。"""
    backup = export_backup(session, user_id)
    backup_dict = backup.model_dump(mode="json", by_alias=True)

    # 隐私与宿主路径边界：.zip 内 backup.json 不含 douyin_nickname / artifact_ref / 宿主 image_path
    for account_row in backup_dict["data"].get("accounts") or []:
        if isinstance(account_row, dict):
            account_row.pop("douyin_nickname", None)
    for attempt_row in backup_dict["data"].get("attempts") or []:
        if isinstance(attempt_row, dict):
            attempt_row.pop("artifact_ref", None)

    images_manifest: dict[str, str] = {}
    bundled_images: list[tuple[str, bytes]] = []
    used_entries: set[str] = set()

    for tpl_row in backup_dict["data"].get("templates") or []:
        if not isinstance(tpl_row, dict):
            continue
        tpl_type = str(tpl_row.get("type") or "").lower()
        orig_path = tpl_row.get("image_path")
        tpl_id = tpl_row.get("id")
        if tpl_type == TemplateType.IMAGE.value:
            tpl_row["image_path"] = None
            if isinstance(tpl_id, str) and tpl_id:
                sanitized = _read_and_sanitize_local_image(orig_path)
                if sanitized is not None:
                    ext, img_bytes = sanitized
                    entry_name = _make_archive_image_entry(tpl_id, ext, used_entries)
                    images_manifest[tpl_id] = entry_name
                    bundled_images.append((entry_name, img_bytes))

    manifest_dict = {
        "format_version": BACKUP_ARCHIVE_FORMAT_VERSION,
        "app": BACKUP_ARCHIVE_APP,
        "images": images_manifest,
    }

    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, mode="w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.writestr(
            "backup.json",
            json.dumps(backup_dict, ensure_ascii=False, indent=2).encode("utf-8"),
        )
        zf.writestr(
            "manifest.json",
            json.dumps(manifest_dict, ensure_ascii=False, indent=2).encode("utf-8"),
        )
        for entry_name, img_bytes in bundled_images:
            zf.writestr(entry_name, img_bytes)
    return buffer.getvalue()


# ---------------------------------------------------------------- archive import


def _validate_zip_entry_name(name: str, *, is_dir: bool) -> None:
    if not name:
        raise ValueError("备份压缩包包含空文件名条目")
    if any(ord(ch) < 0x20 or ord(ch) == 0x7F for ch in name):
        raise ValueError("备份压缩包条目名包含非法控制字符")
    if name.startswith(("/", "\\")) or "\\" in name or ":" in name:
        raise ValueError(f"备份压缩包条目路径非法: {name}")
    if ".." in name.split("/") or ".." in name:
        raise ValueError(f"备份压缩包条目包含路径穿越片段: {name}")
    if is_dir:
        if name != "template_images/":
            raise ValueError(f"备份压缩包包含不允许的目录条目: {name}")
        return
    if name in ("backup.json", "manifest.json"):
        return
    if IMAGE_ENTRY_RE.match(name):
        return
    raise ValueError(f"备份压缩包包含不允许的条目: {name}")


def _read_zip_entry_bounded(
    zf: zipfile.ZipFile,
    info: zipfile.ZipInfo,
    *,
    max_entry_bytes: int,
    remaining_total_bytes: int,
    label: str,
) -> bytes:
    """按块流式读取 ZIP 条目并真实统计解压字节数，防御伪造头部的 ZipBomb。"""
    buf = bytearray()
    with zf.open(info, "r") as stream:
        while True:
            chunk = stream.read(_READ_CHUNK_SIZE)
            if not chunk:
                break
            buf.extend(chunk)
            if len(buf) > max_entry_bytes:
                raise ValueError(f"{label} 解压大小超过上限")
            if len(buf) > remaining_total_bytes:
                raise ValueError("备份压缩包总解压大小超过 80 MB 上限")
    return bytes(buf)


def _parse_backup_json_bytes(raw: bytes) -> BackupRead:
    if len(raw) > MAX_BACKUP_JSON_BYTES:
        raise ValueError("backup.json 超过 20 MB 上限")
    try:
        text = raw.decode("utf-8-sig")
    except UnicodeDecodeError:
        raise ValueError("备份文件不是有效的 UTF-8 编码") from None
    try:
        parsed = json.loads(text)
    except json.JSONDecodeError as exc:
        raise ValueError(f"备份 JSON 格式无效: {exc.msg}") from None
    if not isinstance(parsed, dict):
        raise ValueError("备份 JSON 顶层必须是对象")
    try:
        backup = BackupRead.model_validate(parsed)
    except ValidationError as exc:
        first = exc.errors()[0] if exc.errors() else None
        msg = first.get("msg") if isinstance(first, dict) else str(exc)
        raise ValueError(f"备份数据结构校验失败: {msg}") from None
    if backup.app != BACKUP_ARCHIVE_APP:
        raise ValueError(f"不支持的备份应用标识: {backup.app}")
    if backup.schema_version != BACKUP_SCHEMA_VERSION:
        raise ValueError(f"不支持的备份版本: {backup.schema_version}")
    return backup


def _parse_manifest_images(
    manifest_bytes: bytes | None,
    raw_image_entries: dict[str, bytes],
) -> dict[str, str]:
    """解析 manifest.json 的 template_id -> entry_path 映射，并补充按文件名 stem 回退匹配。"""
    source_to_entry: dict[str, str] = {}
    referenced_entries: set[str] = set()

    if manifest_bytes is not None:
        try:
            manifest_obj = json.loads(manifest_bytes.decode("utf-8-sig"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            raise ValueError("manifest.json 格式无效") from None
        if not isinstance(manifest_obj, dict):
            raise ValueError("manifest.json 顶层必须是对象")
        fmt_ver = manifest_obj.get("format_version")
        if fmt_ver is not None and fmt_ver != BACKUP_ARCHIVE_FORMAT_VERSION:
            raise ValueError(f"不支持的归档清单版本: {fmt_ver}")
        app_id = manifest_obj.get("app")
        if app_id is not None and app_id != BACKUP_ARCHIVE_APP:
            raise ValueError(f"不支持的归档应用标识: {app_id}")
        images_field = manifest_obj.get("images")
        if images_field is not None:
            if not isinstance(images_field, dict):
                raise ValueError("manifest.json 的 images 字段必须为对象")
            for src_tid, entry_path in images_field.items():
                if not isinstance(src_tid, str) or not src_tid.strip():
                    raise ValueError("manifest.json 包含无效的模板 ID")
                if not isinstance(entry_path, str):
                    raise ValueError("manifest.json 的图片路径必须为字符串")
                _validate_zip_entry_name(entry_path, is_dir=False)
                if not IMAGE_ENTRY_RE.match(entry_path):
                    raise ValueError(f"manifest.json 引用了不允许的图片路径: {entry_path}")
                if entry_path in raw_image_entries:
                    source_to_entry[src_tid] = entry_path
                    referenced_entries.add(entry_path)

    for entry_path in raw_image_entries:
        if entry_path in referenced_entries:
            continue
        stem = Path(entry_path).stem
        if stem and stem not in source_to_entry:
            source_to_entry[stem] = entry_path

    return source_to_entry


def _extract_zip_archive(
    raw_bytes: bytes,
) -> tuple[BackupRead, dict[str, tuple[str, bytes]]]:
    try:
        zf = zipfile.ZipFile(io.BytesIO(raw_bytes), "r")
    except zipfile.BadZipFile:
        raise ValueError("无效的 ZIP 备份压缩包") from None

    with zf:
        infos = zf.infolist()
        if len(infos) > MAX_ARCHIVE_ENTRIES:
            raise ValueError(
                f"备份压缩包条目数超过上限（最多 {MAX_ARCHIVE_ENTRIES} 项）"
            )

        total_uncompressed = 0
        seen_names: set[str] = set()
        backup_json_bytes: bytes | None = None
        manifest_json_bytes: bytes | None = None
        raw_image_entries: dict[str, bytes] = {}

        for info in infos:
            name = info.filename
            if info.flag_bits & 0x1:
                raise ValueError("不支持加密的备份压缩包")
            mode = (info.external_attr >> 16) & 0o170000
            if mode == stat.S_IFLNK:
                raise ValueError(f"备份压缩包包含不支持的符号链接条目: {name}")

            is_dir = info.is_dir()
            _validate_zip_entry_name(name, is_dir=is_dir)
            if is_dir:
                if info.file_size != 0:
                    raise ValueError(f"备份压缩包目录条目大小异常: {name}")
                continue

            if name in seen_names:
                raise ValueError(f"备份压缩包包含重复条目: {name}")
            seen_names.add(name)

            remaining = MAX_TOTAL_UNCOMPRESSED_BYTES - total_uncompressed
            if name == "backup.json":
                content = _read_zip_entry_bounded(
                    zf,
                    info,
                    max_entry_bytes=MAX_BACKUP_JSON_BYTES,
                    remaining_total_bytes=remaining,
                    label="backup.json",
                )
                total_uncompressed += len(content)
                backup_json_bytes = content
            elif name == "manifest.json":
                content = _read_zip_entry_bounded(
                    zf,
                    info,
                    max_entry_bytes=MAX_MANIFEST_JSON_BYTES,
                    remaining_total_bytes=remaining,
                    label="manifest.json",
                )
                total_uncompressed += len(content)
                manifest_json_bytes = content
            else:
                entry_limit = (
                    MAX_GIF_BYTES if name.lower().endswith(".gif") else MAX_IMAGE_BYTES
                )
                content = _read_zip_entry_bounded(
                    zf,
                    info,
                    max_entry_bytes=entry_limit,
                    remaining_total_bytes=remaining,
                    label=name,
                )
                total_uncompressed += len(content)
                raw_image_entries[name] = content

    if backup_json_bytes is None:
        raise ValueError("备份压缩包缺少 backup.json")

    backup = _parse_backup_json_bytes(backup_json_bytes)
    source_to_entry = _parse_manifest_images(manifest_json_bytes, raw_image_entries)

    # 先校验并剥离所有打包图片，确保在任何 DB/磁盘写入前拦截非法或伪装图片
    validated_by_entry: dict[str, tuple[str, bytes]] = {}
    for entry_path, img_raw in raw_image_entries.items():
        validated_by_entry[entry_path] = validate_and_sanitize_image(img_raw)

    images_by_source_id: dict[str, tuple[str, bytes]] = {
        src_id: validated_by_entry[entry_path]
        for src_id, entry_path in source_to_entry.items()
        if entry_path in validated_by_entry
    }
    return backup, images_by_source_id


def _has_existing_image_file(image_path: str | None) -> bool:
    if not image_path:
        return False
    try:
        st = Path(image_path).stat()
        return stat.S_ISREG(st.st_mode) and st.st_size > 0
    except OSError:
        return False


def import_backup_archive(
    session: Session,
    raw_bytes: bytes,
    user_id: str,
    settings: Settings,
) -> BackupImportResult:
    """自动识别 `.zip` 或 `.json` 备份并导入当前用户空间，恢复图片模板至受管目录。"""
    if not raw_bytes:
        raise ValueError("备份文件内容为空")
    if len(raw_bytes) > MAX_ARCHIVE_COMPRESSED_BYTES:
        raise ValueError("备份文件超过 60 MB 上限")

    is_zip = raw_bytes[:4] == ZIP_MAGIC or raw_bytes[:4] == ZIP_EMPTY_MAGIC
    if is_zip:
        backup, images_by_source_id = _extract_zip_archive(raw_bytes)
    else:
        backup = _parse_backup_json_bytes(raw_bytes)
        images_by_source_id = {}

    # 收集图片模板的源 ID，并清零外部传入的 image_path，防止跨主机路径注入或读取宿主文件
    incoming_image_source_ids: list[str] = []
    for tpl_row in backup.data.templates:
        if not isinstance(tpl_row, dict):
            continue
        tpl_type = str(tpl_row.get("type") or "").lower()
        if tpl_type == TemplateType.IMAGE.value:
            tpl_row["image_path"] = None
            src_id = tpl_row.get("id")
            if isinstance(src_id, str) and src_id:
                incoming_image_source_ids.append(src_id)

    template_id_map: dict[str, str] = {}
    written_files: list[Path] = []
    try:
        base_result = import_backup(
            session,
            backup,
            user_id,
            template_id_map_out=template_id_map,
            commit=False,
        )

        images_dir = resolve_template_images_dir(settings)
        restored_local_ids: set[str] = set()
        missing_local_ids: set[str] = set()

        for src_id in incoming_image_source_ids:
            local_id = template_id_map.get(src_id)
            if not local_id:
                continue
            template = session.get(MessageTemplate, local_id)
            if (
                template is None
                or template.user_id != user_id
                or template.type != TemplateType.IMAGE
            ):
                continue

            img_item = images_by_source_id.get(src_id)
            if img_item is not None:
                ext, sanitized_bytes = img_item
                images_dir.mkdir(parents=True, exist_ok=True)
                dest = images_dir / f"{uuid4()}.{ext}"
                dest.write_bytes(sanitized_bytes)
                written_files.append(dest)
                template.image_path = str(dest)
                session.add(template)
                restored_local_ids.add(local_id)
                missing_local_ids.discard(local_id)
            elif local_id not in restored_local_ids and not _has_existing_image_file(
                template.image_path
            ):
                missing_local_ids.add(local_id)

        session.commit()
    except Exception:
        session.rollback()
        for path in written_files:
            try:
                path.unlink(missing_ok=True)
            except OSError:
                pass
        raise

    return BackupImportResult(
        inserted=base_result.inserted,
        updated=base_result.updated,
        restored_images=len(restored_local_ids),
        missing_image_templates=len(missing_local_ids),
    )
