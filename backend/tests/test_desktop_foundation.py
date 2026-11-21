from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient
from sqlmodel import Session, select

from app.core.config import Settings
from app.core.security import hash_password
from app.execution.browser_runtime import BrowserRuntimeInfo, detect_browser_runtime
from app.main import create_app
from app.models import User
from app.notifications import BarkNotifier, mask_bark_url, validate_bark_url


def _make_settings(tmp_path: Path, **overrides) -> Settings:
    data_dir = tmp_path / "data"
    return Settings(
        database_url=f"sqlite:///{(data_dir / 'test.db').as_posix()}",
        profiles_dir=data_dir / "profiles",
        artifacts_dir=tmp_path / "artifacts",
        secret_key="test-secret-key-for-desktop-foundation-32bytes",
        serve_frontend=False,
        **overrides,
    )


def test_data_dir_resolves_paths_and_persists_generated_secret(tmp_path: Path):
    data_dir = tmp_path / "app-data"
    s1 = Settings(data_dir=data_dir)
    assert s1.database_url == f"sqlite:///{(data_dir / 'flame-contractor.db').as_posix()}"
    assert s1.profiles_dir == data_dir / "profiles"
    assert s1.artifacts_dir == data_dir / "artifacts"
    assert s1.secret_key != "flame-contractor-dev-secret-change-me"
    assert len(s1.secret_key) >= 32
    assert (data_dir / ".secret_key").is_file()

    # 第二次启动复用同一个持久密钥，确保既有会话/签名稳定。
    s2 = Settings(data_dir=data_dir)
    assert s2.secret_key == s1.secret_key

    # 显式指定 secret_key 时优先使用显式值。
    explicit = "explicit-secret-key-longer-than-thirty-two-chars"
    s3 = Settings(data_dir=data_dir, secret_key=explicit)
    assert s3.secret_key == explicit


def _make_app(tmp_path: Path, **overrides):
    app = create_app(settings=_make_settings(tmp_path, **overrides))
    app.state.disable_test_auto_auth = True
    return app


def test_desktop_handshake_auto_bootstraps_local_admin_and_rejects_bad_token(tmp_path: Path):
    token = "desktop-handshake-token-0123456789abcdef"
    app = _make_app(tmp_path, desktop_mode=True, desktop_token=token)

    with TestClient(app) as client:
        setup = client.get("/api/auth/setup")
        assert setup.status_code == 200
        assert setup.json() == {"needsSetup": False, "desktopMode": True}

        # 无令牌或错误令牌依然严格拒绝（防本机其他网页跨站调用）。
        assert client.get("/api/auth/me").status_code == 401
        assert (
            client.get("/api/auth/me", headers={"Authorization": "Bearer wrong-token"}).status_code
            == 401
        )

        me = client.get("/api/auth/me", headers={"Authorization": f"Bearer {token}"})
        assert me.status_code == 200
        body = me.json()
        assert body["username"] == "local"
        assert body["isSuperuser"] is True

        # 业务接口可直接通行。
        sched = client.get("/api/scheduler", headers={"Authorization": f"Bearer {token}"})
        assert sched.status_code == 200
        assert "browserRuntime" in sched.json()


def test_desktop_mode_reuses_existing_superuser_when_migrating_db(tmp_path: Path):
    token = "desktop-handshake-token-existing-admin-999"
    # 先以常规模式建库并写入一个管理员
    web_app = _make_app(tmp_path)
    with TestClient(web_app) as client:
        reg = client.post(
            "/api/auth/register",
            json={"username": "owner", "password": "owner-password-123"},
        )
        assert reg.status_code == 201

    desktop_app = _make_app(tmp_path, desktop_mode=True, desktop_token=token)
    with TestClient(desktop_app) as client:
        me = client.get("/api/auth/me", headers={"Authorization": f"Bearer {token}"})
        assert me.status_code == 200
        assert me.json()["username"] == "owner"
        with Session(desktop_app.state.engine) as session:
            users = session.exec(select(User)).all()
            assert len(users) == 1


def test_web_mode_ignores_desktop_token(tmp_path: Path):
    token = "desktop-handshake-token-should-not-work-in-web"
    app = _make_app(tmp_path, desktop_mode=False, desktop_token=token)
    with TestClient(app) as client:
        setup = client.get("/api/auth/setup")
        assert setup.status_code == 200
        assert setup.json() == {"needsSetup": True, "desktopMode": False}
        assert (
            client.get("/api/auth/me", headers={"Authorization": f"Bearer {token}"}).status_code
            == 401
        )


def test_bark_url_validation_and_masking():
    assert validate_bark_url("https://api.day.app/AbCdEf123456") == "https://api.day.app/AbCdEf123456"
    assert validate_bark_url("https://api.day.app/AbCdEf123456/") == "https://api.day.app/AbCdEf123456"
    assert (
        validate_bark_url(
            "curl -X GET https://api.day.app/ExampleDeviceKey123456/title/body?group=example&ttl=600"
        )
        == "https://api.day.app/ExampleDeviceKey123456"
    )
    assert (
        validate_bark_url(
            "curl -X GET https://bark.example.com/prefix/ExampleDeviceKey123456/title/body?group=example&ttl=600"
        )
        == "https://bark.example.com/prefix/ExampleDeviceKey123456"
    )
    assert (
        validate_bark_url(
            'curl -X POST https://api.day.app/ExampleDeviceKey123456 \\\n  -d "title=title" \\\n  -d "body=body" \\\n  -d "group=example" \\\n  -d "ttl=600"'
        )
        == "https://api.day.app/ExampleDeviceKey123456"
    )
    assert (
        validate_bark_url(
            'curl -X POST https://api.day.app/push \\\n  -H "Content-Type: application/json" \\\n  -d \'{\n    "device_key": "ExampleDeviceKey123456",\n    "title": "title",\n    "body": "body",\n    "group": "example",\n    "ttl": 600\n  }\''
        )
        == "https://api.day.app/ExampleDeviceKey123456"
    )
    assert (
        validate_bark_url(
            'curl -X POST https://bark.example.com/sub/push -H "Content-Type: application/json" -d \'{"device_key": "ExampleDeviceKey123456"}\''
        )
        == "https://bark.example.com/sub/ExampleDeviceKey123456"
    )
    assert (
        validate_bark_url("https://api.day.app/ExampleDeviceKey123456/这里改成你自己的推送内容")
        == "https://api.day.app/ExampleDeviceKey123456"
    )

    assert mask_bark_url("https://api.day.app/AbCdEf123456") == "https://api.day.app/****3456"
    assert mask_bark_url("https://bark.example.com/custom/SecretKey99/push") == (
        "https://bark.example.com/custom/****ey99/push"
    )
    assert mask_bark_url("https://api.day.app/abc") == "https://api.day.app/****"
    assert mask_bark_url(None) is None

    for bad in (
        "ftp://api.day.app/key",
        "https://api.day.app/",
        "not-a-url",
        "https://api.day.app/push",
        "https://api.day.app/<你的设备Key>",
        'curl -X POST https://api.day.app/push -d \'{"title": "only"}\'',
        "https://api.day.app/" + ("x" * 260),
    ):
        try:
            validate_bark_url(bad)
        except ValueError:
            pass
        else:
            raise AssertionError(f"expected ValueError for {bad!r}")


def test_bark_url_editable_in_settings_persists_masks_and_excludes_backup(tmp_path: Path):
    secret_key = "MyUltraSecretDeviceKey9876"
    bark_url = f"https://api.day.app/{secret_key}"
    app = _make_app(tmp_path, bark_url=None)

    with TestClient(app) as client:
        reg = client.post(
            "/api/auth/register",
            json={"username": "admin", "password": "admin-password-123"},
        )
        assert reg.status_code == 201
        admin_headers = {"Authorization": f"Bearer {reg.json()['token']}"}

        # 普通用户无权修改全局 Bark 推送地址
        member_res = client.post(
            "/api/auth/users",
            headers=admin_headers,
            json={"username": "guest", "password": "guest-password-123", "isSuperuser": False},
        )
        assert member_res.status_code == 201
        guest_login = client.post(
            "/api/auth/login",
            json={"username": "guest", "password": "guest-password-123"},
        )
        guest_headers = {"Authorization": f"Bearer {guest_login.json()['token']}"}
        denied = client.patch(
            "/api/scheduler/settings",
            headers=guest_headers,
            json={"barkUrl": bark_url},
        )
        assert denied.status_code == 403

        # 初始未配置
        st0 = client.get("/api/scheduler", headers=admin_headers).json()
        assert st0["barkConfigured"] is False
        assert st0["barkMaskedUrl"] is None
        assert st0["barkSource"] == "none"

        # 非法地址返回 422
        bad = client.patch(
            "/api/scheduler/settings",
            headers=admin_headers,
            json={"barkUrl": "https://api.day.app/"},
        )
        assert bad.status_code == 422

        # 支持直接粘贴 Bark 官方 JSON curl 使用示例，保存后自动提取、热更新且脱敏
        json_curl_key = "JsonExampleDeviceKey8888"
        json_curl = (
            'curl -X POST https://api.day.app/push \\\n'
            '  -H "Content-Type: application/json" \\\n'
            f'  -d \'{{"device_key": "{json_curl_key}", "title": "title", "body": "body"}}\''
        )
        saved_curl = client.patch(
            "/api/scheduler/settings",
            headers=admin_headers,
            json={"barkUrl": json_curl},
        )
        assert saved_curl.status_code == 200
        assert saved_curl.json()["barkMaskedUrl"] == "https://api.day.app/****8888"
        assert app.state.scheduler._notifier.base_url == f"https://api.day.app/{json_curl_key}"
        assert json_curl_key not in saved_curl.text

        # 保存后热生效且脱敏，绝不泄露完整 device_key
        saved = client.patch(
            "/api/scheduler/settings",
            headers=admin_headers,
            json={"barkUrl": bark_url},
        )
        assert saved.status_code == 200
        body = saved.json()
        assert body["barkConfigured"] is True
        assert body["barkSource"] == "settings"
        assert body["barkMaskedUrl"] == "https://api.day.app/****9876"
        assert secret_key not in saved.text

        # 共享 notifier 已热更新（BrowserManager 与 Scheduler 同一个实例）
        assert app.state.scheduler._notifier.base_url == bark_url
        assert app.state.browser_manager._notifier.base_url == bark_url

        # 备份导出不包含 Bark 凭据
        backup = client.get("/api/backup", headers=admin_headers)
        assert backup.status_code == 200
        assert secret_key not in backup.text

    # 重启应用后自动从 app_setting 恢复
    app2 = _make_app(tmp_path, bark_url="https://api.day.app/EnvFallback1111")
    with TestClient(app2) as client2:
        login2 = client2.post(
            "/api/auth/login",
            json={"username": "admin", "password": "admin-password-123"},
        )
        headers2 = {"Authorization": f"Bearer {login2.json()['token']}"}
        st_reloaded = client2.get("/api/scheduler", headers=headers2).json()
        assert st_reloaded["barkConfigured"] is True
        assert st_reloaded["barkSource"] == "settings"
        assert st_reloaded["barkMaskedUrl"] == "https://api.day.app/****9876"

        # 清空页面配置后，自动回退到环境变量 FLAME_BARK_URL
        cleared = client2.patch(
            "/api/scheduler/settings",
            headers=headers2,
            json={"barkUrl": ""},
        )
        assert cleared.status_code == 200
        cbody = cleared.json()
        assert cbody["barkConfigured"] is True
        assert cbody["barkSource"] == "env"
        assert cbody["barkMaskedUrl"] == "https://api.day.app/****1111"


def test_browser_runtime_detection_priority(tmp_path: Path):
    fake_custom = tmp_path / "my-browser.exe"
    fake_custom.write_text("stub", encoding="utf-8")
    custom_info = detect_browser_runtime(explicit_executable=str(fake_custom))
    assert isinstance(custom_info, BrowserRuntimeInfo)
    assert custom_info.ready is True
    assert custom_info.kind == "custom"
    assert custom_info.executable_path == str(fake_custom)

    fake_edge = tmp_path / "msedge.exe"
    fake_edge.write_text("stub", encoding="utf-8")
    with patch("app.execution.browser_runtime._system_candidates", return_value=[("msedge", "Microsoft Edge", fake_edge)]):
        with patch("app.execution.browser_runtime._playwright_chromium_path", return_value=None):
            edge_info = detect_browser_runtime(platform_name="win32")
            assert edge_info.ready is True
            assert edge_info.kind == "msedge"
            assert edge_info.executable_path == str(fake_edge)

    with patch("app.execution.browser_runtime._system_candidates", return_value=[]):
        with patch("app.execution.browser_runtime._playwright_chromium_path", return_value=None):
            missing = detect_browser_runtime(platform_name="win32")
            assert missing.ready is False
            assert missing.kind == "missing"
            assert missing.executable_path is None


def test_desktop_packaging_config_and_workflow_are_consistent():
    import json

    repo_root = Path(__file__).resolve().parents[2]
    tauri_conf = json.loads(
        (repo_root / "desktop" / "src-tauri" / "tauri.conf.json").read_text(encoding="utf-8")
    )
    desktop_pkg = json.loads(
        (repo_root / "desktop" / "package.json").read_text(encoding="utf-8")
    )
    frontend_pkg = json.loads(
        (repo_root / "frontend" / "package.json").read_text(encoding="utf-8")
    )
    workflow_raw = (repo_root / ".github" / "workflows" / "release-desktop.yml").read_text(
        encoding="utf-8"
    )
    main_rs = (repo_root / "desktop" / "src-tauri" / "src" / "main.rs").read_text(
        encoding="utf-8"
    )

    assert tauri_conf["version"] == desktop_pkg["version"] == frontend_pkg["version"] == Settings().version
    assert tauri_conf["bundle"]["targets"] == ["nsis"]
    assert tauri_conf["bundle"]["windows"]["nsis"]["installMode"] == "currentUser"
    # Tauri v2 中使用 glob（/**/*）会把 PyInstaller 的 _internal 子目录拍平，必须按目录映射。
    assert tauri_conf["bundle"]["resources"] == {"resources/backend": "backend"}
    assert "scripts/build_desktop_backend.py" in workflow_raw
    assert "FLAME_DESKTOP_MODE" in workflow_raw
    assert "127.0.0.1" in workflow_raw
    # 桌面端数据目录优先跟随安装目录 data，不可写时回退 LOCALAPPDATA，支持 FLAME_DATA_DIR 覆盖。
    assert "FLAME_DATA_DIR" in main_rs
    assert "current_exe" in main_rs
    assert "is_dir_writable" in main_rs
    assert "LOCALAPPDATA" in main_rs
