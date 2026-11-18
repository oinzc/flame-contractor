"""GHCR 多架构发布脚本、GitHub Actions 流水线与 Compose / Dockerignore 安全契约测试。"""

from __future__ import annotations

import importlib.util
from pathlib import Path
import subprocess
import sys

import yaml

REPO_ROOT = Path(__file__).resolve().parents[2]


def _load_ghcr_tags_module():
    script_path = REPO_ROOT / "scripts" / "docker" / "ghcr_tags.py"
    assert script_path.is_file(), f"缺少标签计算脚本：{script_path}"
    spec = importlib.util.spec_from_file_location("ghcr_tags", script_path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_compute_ghcr_metadata_handles_main_semver_prerelease_branch_and_uppercase_owner(
    tmp_path: Path,
):
    mod = _load_ghcr_tags_module()

    # 1) 大写用户名 Fork + main 分支推送 -> 全小写镜像名 + latest + sha-<7>
    main_meta = mod.compute_ghcr_metadata(
        repository="MyUser/Flame-Contractor",
        ref="refs/heads/main",
        sha="96e3d2e1234567890abcdef1234567890abcdef1",
    )
    assert main_meta["image"] == "ghcr.io/myuser/flame-contractor"
    assert main_meta["short_sha"] == "96e3d2e"
    assert main_meta["tags"] == [
        "ghcr.io/myuser/flame-contractor:sha-96e3d2e",
        "ghcr.io/myuser/flame-contractor:latest",
    ]

    # 2) 正式语义版本标签 v0.2.0 -> v0.2.0 + 0.2.0 + latest + sha-<7>
    release_meta = mod.compute_ghcr_metadata(
        repository="oinzc/flame-contractor",
        ref="refs/tags/v0.2.0",
        sha="96e3d2e1234567890abcdef1234567890abcdef1",
    )
    assert release_meta["tags"] == [
        "ghcr.io/oinzc/flame-contractor:sha-96e3d2e",
        "ghcr.io/oinzc/flame-contractor:v0.2.0",
        "ghcr.io/oinzc/flame-contractor:0.2.0",
        "ghcr.io/oinzc/flame-contractor:latest",
    ]

    # 3) 预发布版本标签 v0.2.0-rc.1 -> 严禁覆盖 latest
    prerelease_meta = mod.compute_ghcr_metadata(
        repository="oinzc/flame-contractor",
        ref="refs/tags/v0.2.0-rc.1",
        sha="96e3d2e1234567890abcdef1234567890abcdef1",
    )
    assert prerelease_meta["tags"] == [
        "ghcr.io/oinzc/flame-contractor:sha-96e3d2e",
        "ghcr.io/oinzc/flame-contractor:v0.2.0-rc.1",
        "ghcr.io/oinzc/flame-contractor:0.2.0-rc.1",
    ]
    assert "ghcr.io/oinzc/flame-contractor:latest" not in prerelease_meta["tags"]

    # 4) 特性分支手动触发 workflow_dispatch -> 仅 sha-<7>，不覆盖 latest
    branch_meta = mod.compute_ghcr_metadata(
        repository="oinzc/flame-contractor",
        ref="refs/heads/feat/custom-test",
        sha="abcdef0123456789",
    )
    assert branch_meta["tags"] == ["ghcr.io/oinzc/flame-contractor:sha-abcdef0"]

    # 5) CLI --github-output 输出契约
    out_file = tmp_path / "github_output.txt"
    proc = subprocess.run(
        [
            sys.executable,
            str(REPO_ROOT / "scripts" / "docker" / "ghcr_tags.py"),
            "--repository",
            "oinzc/flame-contractor",
            "--ref",
            "refs/tags/v0.2.0",
            "--sha",
            "96e3d2e1234567",
            "--github-output",
            str(out_file),
        ],
        check=True,
        capture_output=True,
        text=True,
    )
    assert proc.returncode == 0
    lines = dict(
        line.split("=", 1)
        for line in out_file.read_text(encoding="utf-8").splitlines()
        if "=" in line
    )
    assert lines["image"] == "ghcr.io/oinzc/flame-contractor"
    assert lines["short_sha"] == "96e3d2e"
    assert "ghcr.io/oinzc/flame-contractor:v0.2.0" in lines["tags"].split()


def test_workflows_compose_and_dockerignore_enforce_ghcr_and_loopback_contracts():
    # 1) publish-ghcr.yml 契约
    publish_path = REPO_ROOT / ".github" / "workflows" / "publish-ghcr.yml"
    assert publish_path.is_file(), "缺少 .github/workflows/publish-ghcr.yml"
    publish_raw = publish_path.read_text(encoding="utf-8")
    publish_doc = yaml.safe_load(publish_raw)

    triggers = publish_doc.get("on") or publish_doc.get(True)
    assert isinstance(triggers, dict)
    assert "pull_request" not in triggers
    assert "workflow_dispatch" in triggers
    assert triggers["push"]["branches"] == ["main"]
    assert triggers["push"]["tags"] == ["v*"]

    # 顶层最小权限：仅读源码，仅构建/合并 job 声明 packages: write
    assert publish_doc["permissions"] == {"contents": "read"}
    jobs = publish_doc["jobs"]
    assert set(jobs.keys()) == {
        "verify",
        "build-amd64",
        "build-arm64",
        "publish-manifest",
    }
    assert jobs["build-amd64"]["runs-on"] == "ubuntu-latest"
    assert jobs["build-arm64"]["runs-on"] == "ubuntu-24.04-arm"
    assert jobs["build-amd64"]["permissions"]["packages"] == "write"
    assert jobs["build-arm64"]["permissions"]["packages"] == "write"
    assert jobs["publish-manifest"]["permissions"]["packages"] == "write"
    assert set(jobs["publish-manifest"]["needs"]) == {"build-amd64", "build-arm64"}

    # 仅使用已升级的 @v7 官方基础 Action，包含 /health 冒烟、OCI source 标签与多架构合并
    for line in publish_raw.splitlines():
        stripped = line.strip()
        if stripped.startswith("uses:") or stripped.startswith("- uses:"):
            action_ref = stripped.split("uses:", 1)[1].strip()
            assert action_ref in {
                "actions/checkout@v7",
                "actions/setup-python@v7",
                "actions/setup-node@v7",
            }, f"不允许引入未验证的 Action：{action_ref}"

    assert "/health" in publish_raw
    assert "--provenance=false" in publish_raw
    assert "--sbom=false" in publish_raw
    assert "org.opencontainers.image.source" in publish_raw
    assert 'index:org.opencontainers.image.source=' in publish_raw
    assert "docker buildx imagetools create" in publish_raw

    # 2) ci.yml 移除重复的无产出 docker-build
    ci_doc = yaml.safe_load(
        (REPO_ROOT / ".github" / "workflows" / "ci.yml").read_text(encoding="utf-8")
    )
    assert set(ci_doc["jobs"].keys()) == {"backend-tests", "frontend-build"}

    # 3) compose.yaml 兼顾 GHCR 预构建镜像与本地 build: .，严格绑定 127.0.0.1
    compose_doc = yaml.safe_load(
        (REPO_ROOT / "compose.yaml").read_text(encoding="utf-8")
    )
    app_svc = compose_doc["services"]["app"]
    assert (
        app_svc["image"]
        == "${FLAME_IMAGE:-ghcr.io/oinzc/flame-contractor:latest}"
    )
    assert app_svc["build"] == "."
    ports = app_svc["ports"]
    assert len(ports) == 1 and ports[0].startswith("127.0.0.1:")

    # 4) .dockerignore 与 Dockerfile 防私密文件泄露与防覆盖容器内 node_modules
    ignore_lines = [
        ln.strip()
        for ln in (REPO_ROOT / ".dockerignore").read_text(encoding="utf-8").splitlines()
        if ln.strip() and not ln.strip().startswith("#")
    ]
    assert ignore_lines[0] == "**"
    for required_rule in (
        "backend/*",
        "frontend/*",
        "scripts/*",
        "**/.env",
        "**/node_modules",
        "**/.venv",
    ):
        assert required_rule in ignore_lines, f".dockerignore 缺少隔离规则：{required_rule}"
    for forbidden in ("!.env", "!data", "!artifacts", "!docs", "!AGENTS.md"):
        assert not any(ln.startswith(forbidden) for ln in ignore_lines)

    dockerfile_raw = (REPO_ROOT / "Dockerfile").read_text(encoding="utf-8")
    assert "COPY frontend/ ./" not in dockerfile_raw
    assert "COPY backend/ ./" not in dockerfile_raw

    # 5) README 免源码 compose 示例保留防空目录挂载保护与日志轮转
    readme_raw = (REPO_ROOT / "README.md").read_text(encoding="utf-8")
    assert "create_host_path: false" in readme_raw
    assert 'max-size: "10m"' in readme_raw
    assert "10001" in readme_raw
