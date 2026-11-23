"""阶段 3A：跨端 `.zip` 完整备份归档导出/导入、元数据剥离与安全防护测试。"""

from __future__ import annotations

import io
import json
import struct
import warnings
import zipfile
import zlib
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from sqlmodel import Session, select

from app import backup_archive
from app.backup_archive import (
    MAX_ARCHIVE_COMPRESSED_BYTES,
    MAX_ARCHIVE_ENTRIES,
    MAX_GIF_BYTES,
    strip_jpeg_metadata,
    strip_png_metadata,
    validate_and_sanitize_image,
)
from app.core.config import Settings
from app.models import Account, ContractTarget, MessageTemplate, TemplateType
from tests.fakes import create_account, make_app


# ---------------------------------------------------------------- synthetic image builders


def _png_chunk(chunk_type: bytes, data: bytes) -> bytes:
    crc = zlib.crc32(chunk_type + data) & 0xFFFFFFFF
    return struct.pack(">I", len(data)) + chunk_type + data + struct.pack(">I", crc)


def _make_png_with_metadata() -> bytes:
    ihdr = struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0)
    idat = zlib.compress(b"\x00\xff\x00\x00")
    return (
        b"\x89PNG\r\n\x1a\n"
        + _png_chunk(b"IHDR", ihdr)
        + _png_chunk(b"tEXt", b"Author\x00SecretUser")
        + _png_chunk(b"iTXt", b"Comment\x00\x00\x00\x00\x00PrivateNote")
        + _png_chunk(b"zTXt", b"Location\x00\x00" + zlib.compress(b"HomeAddress"))
        + _png_chunk(b"eXIf", b"MM\x00*\x00\x00\x00\x08GPSDATA")
        + _png_chunk(b"tIME", struct.pack(">HBBBBB", 2026, 11, 23, 12, 0, 0))
        + _png_chunk(b"IDAT", idat)
        + _png_chunk(b"IEND", b"")
    )


def _make_jpeg_with_metadata(*, orientation: int = 6) -> bytes:
    # APP0 (JFIF) - should be preserved
    jfif_payload = b"JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00"
    app0 = b"\xff\xe0" + struct.pack(">H", len(jfif_payload) + 2) + jfif_payload

    # APP1 (EXIF with Orientation + camera Make privacy string)
    make_bytes = b"SecretCameraPhone\x00"
    # TIFF header (8B) + 2 IFD entries (2 + 24 + 4 = 30B) -> offset 38 for Make string
    tiff = (
        b"MM"
        + struct.pack(">HI", 42, 8)
        + struct.pack(">H", 2)
        + struct.pack(">HHIHH", 0x0112, 3, 1, orientation, 0)
        + struct.pack(">HHII", 0x010F, 2, len(make_bytes), 38)
        + struct.pack(">I", 0)
        + make_bytes
    )
    exif_payload = b"Exif\x00\x00" + tiff
    app1_exif = b"\xff\xe1" + struct.pack(">H", len(exif_payload) + 2) + exif_payload

    # APP1 (XMP) - should be stripped completely
    xmp_payload = b"http://ns.adobe.com/xap/1.0/\x00<xmp>SecretGPSCoordinates</xmp>"
    app1_xmp = b"\xff\xe1" + struct.pack(">H", len(xmp_payload) + 2) + xmp_payload

    # APP13 (Photoshop/IPTC) - should be stripped
    app13_payload = b"Photoshop 3.0\x00SecretIPTCAuthor"
    app13 = b"\xff\xed" + struct.pack(">H", len(app13_payload) + 2) + app13_payload

    # COM - should be stripped
    com_payload = b"SecretJPEGComment"
    com = b"\xff\xfe" + struct.pack(">H", len(com_payload) + 2) + com_payload

    # Minimal SOS + byte-stuffed scan data + EOI
    sos_header = b"\x01\x01\x00\x00\x3f\x00"
    sos = b"\xff\xda" + struct.pack(">H", len(sos_header) + 2) + sos_header
    entropy = b"\x12\x34\xff\x00\x56\x78"

    return b"\xff\xd8" + app0 + app1_exif + app1_xmp + app13 + com + sos + entropy + b"\xff\xd9"


def _make_gif() -> bytes:
    return b"GIF89a\x01\x00\x01\x00\x80\x00\x00\x00\x00\x00\xff\xff\xff!\xf9\x04\x01\x00\x00\x00\x00,\x00\x00\x00\x00\x01\x00\x01\x00\x00\x02\x02D\x01\x00;"


def _make_webp() -> bytes:
    body = b"WEBPVP8 " + struct.pack("<I", 4) + b"\x00\x00\x00\x00"
    return b"RIFF" + struct.pack("<I", len(body)) + body


def _make_zip_bytes(entries: list[tuple[str, bytes]]) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, mode="w", compression=zipfile.ZIP_DEFLATED) as zf:
        for name, data in entries:
            zf.writestr(name, data)
    return buf.getvalue()


def _minimal_backup_dict(templates: list[dict] | None = None) -> dict:
    return {
        "app": "flame-contractor",
        "schemaVersion": 1,
        "exportedAt": "2026-11-23T10:00:00Z",
        "data": {
            "accounts": [
                {
                    "id": "acct-1",
                    "display_name": "默认抖音号",
                    "status": "pending_login",
                    "enabled": True,
                    "timezone": "Asia/Shanghai",
                    "send_window_start": "20:00",
                    "send_window_end": "22:00",
                }
            ],
            "templates": templates or [],
            "poolItems": [],
            "targets": [],
            "schedules": [],
            "jobs": [],
            "attempts": [],
        },
    }


# ---------------------------------------------------------------- unit tests: sanitizer


def test_jpeg_and_png_metadata_stripping():
    jpeg_rot6 = _make_jpeg_with_metadata(orientation=6)
    cleaned_rot6 = strip_jpeg_metadata(jpeg_rot6)
    assert cleaned_rot6.startswith(b"\xff\xd8")
    assert cleaned_rot6.endswith(b"\xff\xd9")
    assert b"SecretCameraPhone" not in cleaned_rot6
    assert b"SecretGPSCoordinates" not in cleaned_rot6
    assert b"SecretIPTCAuthor" not in cleaned_rot6
    assert b"SecretJPEGComment" not in cleaned_rot6
    assert b"JFIF\x00" in cleaned_rot6
    # Orientation=6 保留最小无隐私 EXIF 标签
    assert b"\xff\xe1" in cleaned_rot6
    assert struct.pack(">HHIHH", 0x0112, 3, 1, 6, 0) in cleaned_rot6

    # Orientation=1（正向）时整段 APP1 直接移除
    jpeg_rot1 = _make_jpeg_with_metadata(orientation=1)
    cleaned_rot1 = strip_jpeg_metadata(jpeg_rot1)
    assert b"\xff\xe1" not in cleaned_rot1
    assert b"SecretCameraPhone" not in cleaned_rot1

    # PNG 隐私块全部剥离，保留 IHDR/IDAT/IEND
    png = _make_png_with_metadata()
    cleaned_png = strip_png_metadata(png)
    assert cleaned_png.startswith(b"\x89PNG\r\n\x1a\n")
    for forbidden in (b"tEXt", b"iTXt", b"zTXt", b"eXIf", b"tIME", b"SecretUser", b"GPSDATA"):
        assert forbidden not in cleaned_png
    assert b"IHDR" in cleaned_png
    assert b"IDAT" in cleaned_png
    assert b"IEND" in cleaned_png


def test_validate_and_sanitize_image_rejects_non_images_and_oversized_gif():
    for bad in (b"", b"SECRET_KEY=abc\n", b"SQLite format 3\x00" + b"\x00" * 64, b"<svg></svg>"):
        with pytest.raises(ValueError):
            validate_and_sanitize_image(bad)

    huge_gif = b"GIF89a" + b"\x00" * (MAX_GIF_BYTES + 10)
    with pytest.raises(ValueError, match="GIF"):
        validate_and_sanitize_image(huge_gif)

    ext_gif, _ = validate_and_sanitize_image(_make_gif())
    assert ext_gif == "gif"
    ext_webp, _ = validate_and_sanitize_image(_make_webp())
    assert ext_webp == "webp"


# ---------------------------------------------------------------- API roundtrip & security


def test_backup_archive_roundtrip_all_image_formats_and_sanitization(tmp_path):
    """完整 `.zip` 导出与空库导入往返：4 种图片全部打包、去隐私、去宿主路径并恢复至受管目录。"""
    src_dir = tmp_path / "src_data"
    src_settings = Settings(data_dir=src_dir)
    img_dir = tmp_path / "user_pics"
    img_dir.mkdir()

    jpg_path = img_dir / "photo.jpeg"
    png_path = img_dir / "card.png"
    gif_path = img_dir / "anim.gif"
    webp_path = img_dir / "sticker.webp"
    jpg_path.write_bytes(_make_jpeg_with_metadata(orientation=6))
    png_path.write_bytes(_make_png_with_metadata())
    gif_path.write_bytes(_make_gif())
    webp_path.write_bytes(_make_webp())

    app1 = make_app(src_settings, [])
    with TestClient(app1) as client1:
        acct = create_account(client1, "主账号")
        with Session(app1.state.engine) as session:
            db_acct = session.get(Account, acct["id"])
            assert db_acct is not None
            db_acct.douyin_nickname = "私密抖音昵称"
            session.add(db_acct)
            session.commit()

        t_jpg = client1.post(
            "/api/templates",
            json={"name": "JPG模板", "type": "image", "imagePath": str(jpg_path)},
        ).json()
        t_png = client1.post(
            "/api/templates",
            json={"name": "PNG模板", "type": "image", "imagePath": str(png_path)},
        ).json()
        t_gif = client1.post(
            "/api/templates",
            json={"name": "GIF模板", "type": "image", "imagePath": str(gif_path)},
        ).json()
        t_webp = client1.post(
            "/api/templates",
            json={"name": "WEBP模板", "type": "image", "imagePath": str(webp_path)},
        ).json()
        t_text = client1.post(
            "/api/templates",
            json={"name": "文本模板", "type": "text", "content": "早安 {{target_name}}"},
        ).json()
        client1.post(
            "/api/targets",
            json={
                "accountId": acct["id"],
                "nicknameSnapshot": "好友A",
                "templateId": t_jpg["id"],
                "streakDays": 12,
            },
        )

        # 原有纯 JSON 导出保持不受影响
        plain_resp = client1.get("/api/backup")
        assert plain_resp.status_code == 200
        plain_jpg = next(
            t for t in plain_resp.json()["data"]["templates"] if t["id"] == t_jpg["id"]
        )
        assert plain_jpg["image_path"] == str(jpg_path)

        # 新 `.zip` 归档导出
        zip_resp = client1.get("/api/backup/archive")
        assert zip_resp.status_code == 200
        assert zip_resp.headers["content-type"] == "application/zip"
        assert "flame-backup-" in zip_resp.headers.get("content-disposition", "")
        assert ".zip" in zip_resp.headers.get("content-disposition", "")
        archive_bytes = zip_resp.content
        assert archive_bytes[:4] == b"PK\x03\x04"

    # 校验压缩包内部结构、宿主路径脱敏与图片隐私剥离
    with zipfile.ZipFile(io.BytesIO(archive_bytes), "r") as zf:
        names = set(zf.namelist())
        assert "backup.json" in names
        assert "manifest.json" in names
        assert f"template_images/{t_jpg['id']}.jpg" in names
        assert f"template_images/{t_png['id']}.png" in names
        assert f"template_images/{t_gif['id']}.gif" in names
        assert f"template_images/{t_webp['id']}.webp" in names

        backup_json = json.loads(zf.read("backup.json").decode("utf-8"))
        assert backup_json["schemaVersion"] == 1
        assert "私密抖音昵称" not in zf.read("backup.json").decode("utf-8")
        for tpl in backup_json["data"]["templates"]:
            if tpl["type"] == "image":
                assert tpl["image_path"] is None

        manifest = json.loads(zf.read("manifest.json").decode("utf-8"))
        assert manifest["format_version"] == 1
        assert manifest["app"] == "flame-contractor"
        assert manifest["images"] == {
            t_jpg["id"]: f"template_images/{t_jpg['id']}.jpg",
            t_png["id"]: f"template_images/{t_png['id']}.png",
            t_gif["id"]: f"template_images/{t_gif['id']}.gif",
            t_webp["id"]: f"template_images/{t_webp['id']}.webp",
        }

        bundled_jpg = zf.read(f"template_images/{t_jpg['id']}.jpg")
        assert b"SecretCameraPhone" not in bundled_jpg
        assert b"SecretGPSCoordinates" not in bundled_jpg
        assert b"SecretIPTCAuthor" not in bundled_jpg
        assert b"SecretJPEGComment" not in bundled_jpg

        bundled_png = zf.read(f"template_images/{t_png['id']}.png")
        assert b"SecretUser" not in bundled_png
        assert b"GPSDATA" not in bundled_png

    # 空库导入 `.zip`
    dst_dir = tmp_path / "dst_data"
    dst_settings = Settings(data_dir=dst_dir)
    app2 = make_app(dst_settings, [])
    with TestClient(app2) as client2:
        imp = client2.post(
            "/api/backup/import-archive",
            content=archive_bytes,
            headers={"Content-Type": "application/zip"},
        )
        assert imp.status_code == 200, imp.text
        body = imp.json()
        assert body["inserted"]["accounts"] == 1
        assert body["inserted"]["templates"] == 5
        assert body["inserted"]["targets"] == 1
        assert body["restoredImages"] == 4
        assert body["missingImageTemplates"] == 0

        managed_dir = (dst_dir / "template_images").resolve()
        with Session(app2.state.engine) as session:
            tpls = {t.name: t for t in session.exec(select(MessageTemplate)).all()}
            assert tpls["文本模板"].id == t_text["id"]
            for name, expected_ext in (
                ("JPG模板", ".jpg"),
                ("PNG模板", ".png"),
                ("GIF模板", ".gif"),
                ("WEBP模板", ".webp"),
            ):
                tpl = tpls[name]
                assert tpl.image_path is not None
                saved = Path(tpl.image_path).resolve()
                assert saved.parent == managed_dir
                assert saved.suffix == expected_ext
                assert saved.is_file()
                assert saved.stat().st_size > 0

            target = session.exec(select(ContractTarget)).one()
            assert target.template_id == tpls["JPG模板"].id


def test_export_archive_skips_non_image_host_files_and_missing_paths(tmp_path):
    """防止宿主敏感文件（.env / .db）被误当图片模板打包导出，缺失文件静默跳过不中断。"""
    src_settings = Settings(data_dir=tmp_path / "data")
    fake_env = tmp_path / ".env"
    fake_env.write_text("FLAME_SECRET_KEY=super-secret-value\n", encoding="utf-8")
    fake_db = tmp_path / "flame.db"
    fake_db.write_bytes(b"SQLite format 3\x00" + b"\x00" * 128)
    missing_path = tmp_path / "does-not-exist.png"

    app = make_app(src_settings, [])
    with TestClient(app) as client:
        create_account(client)
        client.post(
            "/api/templates",
            json={"name": "窃读env", "type": "image", "imagePath": str(fake_env)},
        )
        client.post(
            "/api/templates",
            json={"name": "窃读db", "type": "image", "imagePath": str(fake_db)},
        )
        client.post(
            "/api/templates",
            json={"name": "缺失图", "type": "image", "imagePath": str(missing_path)},
        )

        resp = client.get("/api/backup/archive")
        assert resp.status_code == 200
        assert b"super-secret-value" not in resp.content
        assert b"SQLite format 3" not in resp.content

        with zipfile.ZipFile(io.BytesIO(resp.content), "r") as zf:
            assert set(zf.namelist()) == {"backup.json", "manifest.json"}
            manifest = json.loads(zf.read("manifest.json").decode("utf-8"))
            assert manifest["images"] == {}


def test_import_archive_auto_detects_plain_json_and_reports_missing_images(tmp_path):
    """`POST /api/backup/import-archive` 自动识别纯 `.json`，清零宿主路径并报告缺图模板数。"""
    app = make_app(Settings(data_dir=tmp_path / "dst"), [])
    plain_json = _minimal_backup_dict(
        templates=[
            {
                "id": "tpl-img-only-json",
                "name": "纯JSON图模板",
                "type": "image",
                "content": "",
                "image_path": "C:/Users/host/secret.png",
                "enabled": True,
            }
        ]
    )

    with TestClient(app) as client:
        resp = client.post(
            "/api/backup/import-archive",
            content=json.dumps(plain_json).encode("utf-8"),
            headers={"Content-Type": "application/json"},
        )
        assert resp.status_code == 200, resp.text
        body = resp.json()
        assert body["inserted"]["templates"] == 1
        assert body["restoredImages"] == 0
        assert body["missingImageTemplates"] == 1

        with Session(app.state.engine) as session:
            tpl = session.exec(select(MessageTemplate)).one()
            assert tpl.image_path is None


def test_import_archive_remaps_natural_key_merge_and_cross_user_isolation(tmp_path):
    """自然键 (user_id, name) 同类型合并、不同类型重命名以及跨用户固定 ID 隔离均正确绑定图片。"""
    data_dir = tmp_path / "shared_data"
    app = make_app(Settings(data_dir=data_dir), [])
    app.state.disable_test_auto_auth = True

    png_bytes = _make_png_with_metadata()
    webp_bytes = _make_webp()
    backup_obj = _minimal_backup_dict(
        templates=[
            {
                "id": "android-img-1",
                "name": "早安图",
                "type": "image",
                "content": "",
                "enabled": True,
            },
            {
                "id": "android-img-2",
                "name": "同名冲突",
                "type": "image",
                "content": "",
                "enabled": True,
            },
        ]
    )
    manifest_obj = {
        "format_version": 1,
        "app": "flame-contractor",
        "images": {
            "android-img-1": "template_images/android-img-1.png",
            "android-img-2": "template_images/android-img-2.webp",
        },
    }
    zip_bytes = _make_zip_bytes(
        [
            ("backup.json", json.dumps(backup_obj).encode("utf-8")),
            ("manifest.json", json.dumps(manifest_obj).encode("utf-8")),
            ("template_images/android-img-1.png", png_bytes),
            ("template_images/android-img-2.webp", webp_bytes),
        ]
    )

    with TestClient(app) as client:
        reg_a = client.post(
            "/api/auth/register",
            json={"username": "alice", "password": "alicepassword123"},
        )
        headers_a = {"Authorization": f"Bearer {reg_a.json()['token']}"}
        alice_id = reg_a.json()["user"]["id"]

        client.post(
            "/api/auth/users",
            headers=headers_a,
            json={"username": "bob", "password": "bobpassword123", "isSuperuser": False},
        )
        login_b = client.post(
            "/api/auth/login",
            json={"username": "bob", "password": "bobpassword123"},
        )
        headers_b = {"Authorization": f"Bearer {login_b.json()['token']}"}
        bob_id = login_b.json()["user"]["id"]

        # Alice 本地先创建同名同类型图片模板 + 同名不同类型文本模板
        local_img = client.post(
            "/api/templates",
            headers=headers_a,
            json={"name": "早安图", "type": "image", "imagePath": str(tmp_path / "gone.png")},
        ).json()
        client.post(
            "/api/templates",
            headers=headers_a,
            json={"name": "同名冲突", "type": "text", "content": "原文本"},
        )

        imp_a = client.post(
            "/api/backup/import-archive",
            content=zip_bytes,
            headers={**headers_a, "Content-Type": "application/zip"},
        )
        assert imp_a.status_code == 200, imp_a.text
        assert imp_a.json()["updated"]["templates"] == 1
        assert imp_a.json()["inserted"]["templates"] == 1
        assert imp_a.json()["restoredImages"] == 2
        assert imp_a.json()["missingImageTemplates"] == 0

        # Bob 导入同一份固定 ID 归档，应独立重映射并各自落盘
        imp_b = client.post(
            "/api/backup/import-archive",
            content=zip_bytes,
            headers={**headers_b, "Content-Type": "application/zip"},
        )
        assert imp_b.status_code == 200, imp_b.text
        assert imp_b.json()["inserted"]["templates"] == 2
        assert imp_b.json()["restoredImages"] == 2

        with Session(app.state.engine) as session:
            alice_tpls = {
                t.name: t
                for t in session.exec(
                    select(MessageTemplate).where(MessageTemplate.user_id == alice_id)
                ).all()
            }
            bob_tpls = {
                t.name: t
                for t in session.exec(
                    select(MessageTemplate).where(MessageTemplate.user_id == bob_id)
                ).all()
            }

            # Alice 的自然键合并保留了本地 ID，同时更新为新恢复的 PNG 文件
            merged = alice_tpls["早安图"]
            assert merged.id == local_img["id"]
            assert merged.image_path is not None
            assert Path(merged.image_path).is_file()
            assert Path(merged.image_path).suffix == ".png"

            # Alice 的类型冲突项自动改名并成功绑定 WEBP 图片
            renamed = alice_tpls["同名冲突 (导入)"]
            assert renamed.type == TemplateType.IMAGE
            assert renamed.image_path is not None
            assert Path(renamed.image_path).is_file()
            assert Path(renamed.image_path).suffix == ".webp"

            # Bob 与 Alice 的模板 ID 和受管图片路径完全隔离
            assert bob_tpls["早安图"].id != merged.id
            assert bob_tpls["早安图"].image_path != merged.image_path
            assert Path(bob_tpls["早安图"].image_path).is_file()


def test_import_archive_rejects_zipslip_zipbomb_fake_images_and_oversized_uploads(
    tmp_path, monkeypatch
):
    """安全防线：ZipSlip、非法条目、伪装图片、超多条目、ZipBomb 与超 60MB 上传均被拒绝。"""
    data_dir = tmp_path / "sec_data"
    app = make_app(Settings(data_dir=data_dir), [])
    valid_backup = json.dumps(_minimal_backup_dict()).encode("utf-8")

    with TestClient(app) as client:
        # 1. 空请求体 -> 422
        assert client.post("/api/backup/import-archive", content=b"").status_code == 422

        # 2. ZipSlip 与不允许的条目 -> 422
        bad_entry_names = [
            "../evil.txt",
            "/etc/passwd",
            "C:/evil.png",
            "template_images/sub/a.png",
            "template_images/evil.svg",
            "template_images/bad\x00name.png",
            "extra.txt",
        ]
        for bad_name in bad_entry_names:
            z = _make_zip_bytes([("backup.json", valid_backup), (bad_name, b"x")])
            resp = client.post("/api/backup/import-archive", content=z)
            assert resp.status_code == 422, f"Expected 422 for {bad_name!r}, got {resp.status_code}"

        # 3. 缺少 backup.json -> 422
        z_no_backup = _make_zip_bytes([("manifest.json", b"{}")])
        assert client.post("/api/backup/import-archive", content=z_no_backup).status_code == 422

        # 4. 重复条目 -> 422
        buf = io.BytesIO()
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(buf, "w") as zf:
                zf.writestr("backup.json", valid_backup)
                zf.writestr("backup.json", valid_backup)
        assert client.post("/api/backup/import-archive", content=buf.getvalue()).status_code == 422

        # 5. 超过 200 条目 -> 422
        many_entries = [("backup.json", valid_backup)] + [
            (f"template_images/img_{i}.png", _make_png_with_metadata())
            for i in range(MAX_ARCHIVE_ENTRIES)
        ]
        z_many = _make_zip_bytes(many_entries)
        assert client.post("/api/backup/import-archive", content=z_many).status_code == 422

        # 6. 伪装成 .png 的非图片文件 -> 422，且数据库保持干净回滚
        img_backup = _minimal_backup_dict(
            templates=[
                {
                    "id": "tpl-fake",
                    "name": "伪装图",
                    "type": "image",
                    "content": "",
                    "enabled": True,
                }
            ]
        )
        z_fake_img = _make_zip_bytes(
            [
                ("backup.json", json.dumps(img_backup).encode("utf-8")),
                ("template_images/tpl-fake.png", b"NOT_A_REAL_PNG_PAYLOAD"),
            ]
        )
        resp_fake = client.post("/api/backup/import-archive", content=z_fake_img)
        assert resp_fake.status_code == 422
        with Session(app.state.engine) as session:
            assert session.exec(select(MessageTemplate)).all() == []

        # 7. ZipBomb：backup.json 解压后超过 20 MB（零字节压缩后仅几十 KB，秒级触发真实流式计数拦截）
        bomb_json = b" " * (21 * 1024 * 1024)
        z_bomb = _make_zip_bytes([("backup.json", bomb_json)])
        assert len(z_bomb) < 100 * 1024
        resp_bomb = client.post("/api/backup/import-archive", content=z_bomb)
        assert resp_bomb.status_code == 422

        # 8. 超过 60 MB 压缩上传上限 -> 413（通过较小 monkeypatch 限额验证真实流式截断）
        monkeypatch.setattr("app.api.backup.MAX_ARCHIVE_COMPRESSED_BYTES", 64)
        monkeypatch.setattr(backup_archive, "MAX_ARCHIVE_COMPRESSED_BYTES", 64)
        resp_too_big = client.post("/api/backup/import-archive", content=b"x" * 128)
        assert resp_too_big.status_code == 413
        assert MAX_ARCHIVE_COMPRESSED_BYTES == 60 * 1024 * 1024


def test_import_archive_supports_directory_entry_and_manifestless_stem_fallback(tmp_path):
    """兼容包含空 template_images/ 目录条目及无 manifest.json 时按文件名 stem 回退绑定。"""
    app = make_app(Settings(data_dir=tmp_path / "fallback_data"), [])
    backup_obj = _minimal_backup_dict(
        templates=[
            {
                "id": "tpl-stem-1",
                "name": "回退图",
                "type": "image",
                "content": "",
                "enabled": True,
            }
        ]
    )
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("template_images/", b"")
        zf.writestr("backup.json", json.dumps(backup_obj).encode("utf-8"))
        zf.writestr("template_images/tpl-stem-1.png", _make_png_with_metadata())

    with TestClient(app) as client:
        resp = client.post("/api/backup/import-archive", content=buf.getvalue())
        assert resp.status_code == 200, resp.text
        body = resp.json()
        assert body["restoredImages"] == 1
        assert body["missingImageTemplates"] == 0

        with Session(app.state.engine) as session:
            tpl = session.exec(select(MessageTemplate)).one()
            assert tpl.image_path is not None
            assert Path(tpl.image_path).is_file()
