"""全端统一版本号同步脚本与 GitHub Release 发版流水线一致性契约测试。"""

from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import subprocess
import sys

import pytest
import yaml

from app.core.config import Settings

REPO_ROOT = Path(__file__).resolve().parents[2]


def _load_bump_version_module():
    script_path = REPO_ROOT / "scripts" / "bump_version.py"
    assert script_path.is_file(), f"缺少版本同步脚本：{script_path}"
    spec = importlib.util.spec_from_file_location("bump_version", script_path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _write_bytes(path: Path, text: str, newline: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    normalized = text.replace("\r\n", "\n").replace("\n", newline)
    path.write_bytes(normalized.encode("utf-8"))


def _create_fixture_repo(root: Path, version: str = "0.3.0") -> dict[str, bytes]:
    """在临时目录构造包含全部 10 个版本声明文件的最小夹具仓库。"""
    files: dict[str, tuple[str, str]] = {
        "backend/app/core/config.py": (
            'class Settings:\n    app_name: str = "Flame Contractor"\n    version: str = "'
            + version
            + '"\n',
            "\n",
        ),
        "backend/pyproject.toml": (
            '[project]\nname = "flame-contractor-backend"\nversion = "'
            + version
            + '"\n\n[tool.pytest.ini_options]\naddopts = "-q"\n',
            "\r\n",
        ),
        "frontend/package.json": (
            json.dumps(
                {
                    "name": "flame-contractor-console",
                    "version": version,
                    "dependencies": {"react": "^19.2.8"},
                },
                indent=2,
            )
            + "\n",
            "\n",
        ),
        "frontend/package-lock.json": (
            json.dumps(
                {
                    "name": "flame-contractor-console",
                    "version": version,
                    "lockfileVersion": 3,
                    "packages": {
                        "": {"name": "flame-contractor-console", "version": version},
                        "node_modules/some-dep": {"version": version},
                    },
                },
                indent=2,
            )
            + "\n",
            "\r\n",
        ),
        "desktop/package.json": (
            json.dumps({"name": "flame-contractor-desktop", "version": version}, indent=2) + "\n",
            "\n",
        ),
        "desktop/package-lock.json": (
            json.dumps(
                {
                    "name": "flame-contractor-desktop",
                    "version": version,
                    "lockfileVersion": 3,
                    "packages": {
                        "": {"name": "flame-contractor-desktop", "version": version},
                        "node_modules/some-dep": {"version": version},
                    },
                },
                indent=2,
            )
            + "\n",
            "\n",
        ),
        "desktop/src-tauri/tauri.conf.json": (
            json.dumps(
                {
                    "productName": "Flame Contractor",
                    "version": version,
                    "bundle": {"resources": {"resources/backend": "backend"}},
                },
                indent=2,
            )
            + "\n",
            "\r\n",
        ),
        "desktop/src-tauri/Cargo.toml": (
            '[package]\nname = "flame-contractor-desktop"\nversion = "'
            + version
            + '"\nedition = "2021"\n\n[dependencies]\nserde = { version = "1" }\n',
            "\r\n",
        ),
        "desktop/src-tauri/Cargo.lock": (
            '[[package]]\nname = "web_atoms"\nversion = "'
            + version
            + '"\n\n[[package]]\nname = "flame-contractor-desktop"\nversion = "'
            + version
            + '"\ndependencies = [\n "serde",\n]\n',
            "\r\n",
        ),
        "android/app/build.gradle.kts": (
            'val requestedVersionName = (project.findProperty("versionName") as String?)?.takeIf { it.isNotBlank() } ?: "'
            + version
            + '-dev"\n',
            "\n",
        ),
    }
    raw_before: dict[str, bytes] = {}
    for rel, (content, nl) in files.items():
        path = root / rel
        _write_bytes(path, content, nl)
        raw_before[rel] = path.read_bytes()
    return raw_before


def test_validate_semver_accepts_valid_and_rejects_invalid():
    mod = _load_bump_version_module()

    for valid in ("0.4.0", "1.2.3", "0.5.0-rc.1", "1.0.0-beta.2"):
        assert mod.validate_semver(valid) == valid

    for invalid in ("v0.5.0", "0.5", "01.2.3", "0.5.0+build.1", "", "  ", "0.5.0;echo"):
        with pytest.raises(ValueError):
            mod.validate_semver(invalid)


def test_bump_and_check_updates_only_target_packages_and_preserves_third_party_and_line_endings(
    tmp_path: Path,
):
    mod = _load_bump_version_module()
    _create_fixture_repo(tmp_path, version="0.3.0")

    updated = mod.bump_repo_versions(tmp_path, "0.5.0")
    assert len(updated) == 10

    assert mod.check_repo_versions(tmp_path) == "0.5.0"
    assert mod.check_repo_versions(tmp_path, tag="v0.5.0") == "0.5.0"

    # 第三方依赖与非本仓 crate 版本号严禁被误改
    cargo_lock = (tmp_path / "desktop/src-tauri/Cargo.lock").read_bytes()
    assert b'name = "web_atoms"\r\nversion = "0.3.0"' in cargo_lock
    assert b'name = "flame-contractor-desktop"\r\nversion = "0.5.0"' in cargo_lock

    for lock_rel in ("frontend/package-lock.json", "desktop/package-lock.json"):
        lock_data = json.loads((tmp_path / lock_rel).read_text(encoding="utf-8"))
        assert lock_data["version"] == "0.5.0"
        assert lock_data["packages"][""]["version"] == "0.5.0"
        assert lock_data["packages"]["node_modules/some-dep"]["version"] == "0.3.0"

    gradle_text = (tmp_path / "android/app/build.gradle.kts").read_text(encoding="utf-8")
    assert '"0.5.0-dev"' in gradle_text

    # 保留原文件 CRLF 与 LF 换行符
    assert b"\r\n" in (tmp_path / "backend/pyproject.toml").read_bytes()
    assert b"\r\n" in (tmp_path / "desktop/src-tauri/tauri.conf.json").read_bytes()
    assert b"\r\n" in cargo_lock
    assert b"\r\n" not in (tmp_path / "backend/app/core/config.py").read_bytes()
    assert b"\r\n" not in (tmp_path / "frontend/package.json").read_bytes()
    assert b"\r\n" not in (tmp_path / "android/app/build.gradle.kts").read_bytes()


def test_check_detects_lockfile_android_and_tag_drift(tmp_path: Path):
    mod = _load_bump_version_module()

    # 1) frontend/package-lock.json 的 packages[""] 漂移
    _create_fixture_repo(tmp_path / "c1", version="0.5.0")
    lock_path = tmp_path / "c1/frontend/package-lock.json"
    lock_obj = json.loads(lock_path.read_text(encoding="utf-8"))
    lock_obj["packages"][""]["version"] = "0.4.0"
    lock_path.write_text(json.dumps(lock_obj, indent=2) + "\n", encoding="utf-8")
    with pytest.raises(ValueError, match="package-lock.json"):
        mod.check_repo_versions(tmp_path / "c1")

    # 2) android/app/build.gradle.kts 缺少 -dev 后缀或版本不一致
    _create_fixture_repo(tmp_path / "c2", version="0.5.0")
    gradle_path = tmp_path / "c2/android/app/build.gradle.kts"
    gradle_path.write_text(
        'val requestedVersionName = (project.findProperty("versionName") as String?)?.takeIf { it.isNotBlank() } ?: "0.5.0"\n',
        encoding="utf-8",
    )
    with pytest.raises(ValueError, match="build.gradle.kts|-dev"):
        mod.check_repo_versions(tmp_path / "c2")

    _create_fixture_repo(tmp_path / "c2b", version="0.5.0")
    (tmp_path / "c2b/android/app/build.gradle.kts").write_text(
        'val requestedVersionName = (project.findProperty("versionName") as String?)?.takeIf { it.isNotBlank() } ?: "0.4.0-dev"\n',
        encoding="utf-8",
    )
    with pytest.raises(ValueError, match="build.gradle.kts|不一致"):
        mod.check_repo_versions(tmp_path / "c2b")

    # 3) desktop/src-tauri/Cargo.lock 未更新
    _create_fixture_repo(tmp_path / "c3", version="0.5.0")
    cargo_lock_path = tmp_path / "c3/desktop/src-tauri/Cargo.lock"
    cargo_lock_path.write_text(
        cargo_lock_path.read_text(encoding="utf-8").replace(
            'name = "flame-contractor-desktop"\nversion = "0.5.0"',
            'name = "flame-contractor-desktop"\nversion = "0.4.0"',
        ),
        encoding="utf-8",
    )
    with pytest.raises(ValueError, match="Cargo.lock"):
        mod.check_repo_versions(tmp_path / "c3")

    # 4) Git tag 不匹配或缺少前导 v
    _create_fixture_repo(tmp_path / "c4", version="0.5.0")
    with pytest.raises(ValueError, match="tag|v0.4.0"):
        mod.check_repo_versions(tmp_path / "c4", tag="v0.4.0")
    with pytest.raises(ValueError, match="tag|v"):
        mod.check_repo_versions(tmp_path / "c4", tag="0.5.0")


def test_bump_is_atomic_when_target_file_is_missing_or_malformed(tmp_path: Path):
    mod = _load_bump_version_module()

    # 1) 缺失 Android 文件时其余 9 个文件不得被改写
    r1 = tmp_path / "missing_android"
    raw1 = _create_fixture_repo(r1, version="0.3.0")
    (r1 / "android/app/build.gradle.kts").unlink()
    with pytest.raises((FileNotFoundError, ValueError)):
        mod.bump_repo_versions(r1, "0.6.0")
    for rel, expected_bytes in raw1.items():
        if rel != "android/app/build.gradle.kts":
            assert (r1 / rel).read_bytes() == expected_bytes

    # 2) Cargo.toml 格式异常时其余 9 个文件不得被改写
    r2 = tmp_path / "malformed_cargo"
    raw2 = _create_fixture_repo(r2, version="0.3.0")
    (r2 / "desktop/src-tauri/Cargo.toml").write_text(
        '[package]\nname = "flame-contractor-desktop"\n',
        encoding="utf-8",
    )
    broken_cargo_bytes = (r2 / "desktop/src-tauri/Cargo.toml").read_bytes()
    with pytest.raises(ValueError):
        mod.bump_repo_versions(r2, "0.6.0")
    for rel, expected_bytes in raw2.items():
        if rel == "desktop/src-tauri/Cargo.toml":
            assert (r2 / rel).read_bytes() == broken_cargo_bytes
        else:
            assert (r2 / rel).read_bytes() == expected_bytes


def test_cli_check_and_set_subprocesses(tmp_path: Path):
    _create_fixture_repo(tmp_path, version="0.3.0")
    script = str(REPO_ROOT / "scripts" / "bump_version.py")

    p_set = subprocess.run(
        [sys.executable, script, "--root", str(tmp_path), "--set", "0.5.1"],
        capture_output=True,
        text=True,
    )
    assert p_set.returncode == 0, p_set.stderr

    p_ok = subprocess.run(
        [sys.executable, script, "--root", str(tmp_path), "--check", "--tag", "v0.5.1"],
        capture_output=True,
        text=True,
    )
    assert p_ok.returncode == 0, p_ok.stderr
    assert "0.5.1" in p_ok.stdout

    p_mismatch = subprocess.run(
        [sys.executable, script, "--root", str(tmp_path), "--check", "--tag", "v0.5.0"],
        capture_output=True,
        text=True,
    )
    assert p_mismatch.returncode != 0


def test_real_repo_versions_and_release_workflows_are_consistent():
    mod = _load_bump_version_module()
    assert mod.check_repo_versions(REPO_ROOT) == Settings().version

    changelog = (REPO_ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
    assert "## [v0.4.0]" in changelog

    desktop_wf = yaml.safe_load(
        (REPO_ROOT / ".github/workflows/release-desktop.yml").read_text(encoding="utf-8")
    )
    desktop_steps = desktop_wf["jobs"]["build-windows"]["steps"]
    desktop_check_idx = next(
        i
        for i, s in enumerate(desktop_steps)
        if "bump_version.py" in str(s.get("run", ""))
        and "--check" in str(s.get("run", ""))
        and "--tag" in str(s.get("run", ""))
    )
    desktop_build_idx = next(
        i
        for i, s in enumerate(desktop_steps)
        if s.get("working-directory") == "desktop" and "npm run build" in str(s.get("run", ""))
    )
    assert desktop_check_idx < desktop_build_idx
    desktop_upload_run = str(desktop_steps[-1].get("run", ""))
    assert "gh release view" in desktop_upload_run
    assert "gh release create" in desktop_upload_run
    assert "gh release upload" in desktop_upload_run

    android_wf = yaml.safe_load(
        (REPO_ROOT / ".github/workflows/release-android.yml").read_text(encoding="utf-8")
    )
    android_steps = android_wf["jobs"]["build-android"]["steps"]
    android_check_idx = next(
        i
        for i, s in enumerate(android_steps)
        if "bump_version.py" in str(s.get("run", ""))
        and "--check" in str(s.get("run", ""))
        and "--tag" in str(s.get("run", ""))
    )
    android_build_idx = next(
        i
        for i, s in enumerate(android_steps)
        if ":app:assembleRelease" in str(s.get("run", ""))
    )
    assert android_check_idx < android_build_idx
    android_upload_run = str(android_steps[-1].get("run", ""))
    assert "gh release view" in android_upload_run
    assert "gh release create" in android_upload_run
    assert "gh release upload" in android_upload_run
