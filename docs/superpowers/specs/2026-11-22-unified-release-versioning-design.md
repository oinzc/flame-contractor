# 全端统一版本号与发版校验设计（方案 A）

- **日期**：2026-11-22
- **范围**：版本治理、CHANGELOG 补齐与 GitHub Actions 发版流水线校验（不修改业务运行时代码）

## 1. 意图与目标

1. 补齐 [CHANGELOG.md](../../../CHANGELOG.md) 中的 `v0.4.0` 变更记录，与已发布的 GitHub Release `v0.4.0` 保持一致。
2. 通过统一的命令行脚本与 CI 前置门禁，确保后续每次推送 `vX.Y.Z` 标签发布时，后端、Web 前端、桌面端（Tauri / Cargo）与 Android 端的版本号声明及构建产物严格同步，杜绝“Tag 为 `v0.4.0` 而桌面安装包仍为 `0.3.0`”的错位现象。

## 2. 方案比选（Approaches）

- **方案 A：全端统一版本号 `vX.Y.Z` + 一键升版脚本与 CI 一致性校验（已选定）**
  - **做法**：Docker、桌面端、Android 始终共用一套 `vX.Y.Z` 版本号；提供纯标准库脚本 `scripts/bump_version.py` 一次性同步全仓 10 处版本声明，并在发版流水线推送 `v*` tag 时强制校验 tag 与仓库声明一致。
  - **权衡**：开源用户访问 GitHub Latest Release 时可在同一页面直接获得版本一致的全端安装包与 Docker 镜像，维护心智最轻；代价是单端修复发正式版时其余端 CI 也会同步执行（公开仓 Actions 成本可忽略）。
- **方案 B：分端独立 Tag 发版（如 `vX.Y.Z` 与 `android-vX.Y.Z`）**
  - **权衡**：单端发版无需等待他端 CI，但 GitHub Releases 页面会出现多条版本线交错，容易导致下载页资产不全或版本号认知混乱。
- **方案 C：主版本统一 `vX.Y.Z` + 可选单端热修前缀**
  - **权衡**：兼顾常规对齐与单端热修，但工作流分支与 CHANGELOG 维护复杂度明显上升。

## 3. 非目标与红线

- 不修改或移动已发布的 `v0.4.0` Git tag 与现有 GitHub Release；本轮验证完成后不擅自 `git push` 或创建新 tag。
- 严禁破坏 `desktop/src-tauri/tauri.conf.json` 中的 `"resources": {"resources/backend": "backend"}` 目录映射。
- 严禁修改 `.github/workflows/publish-ghcr.yml`。
- 公开仓严禁写入个人设备型号、本地路径、真实昵称、Cookie 或任何密钥信息。

## 4. 纳管的 10 处版本声明

1. `backend/app/core/config.py`（`Settings.version`）
2. `backend/pyproject.toml`（`[project].version`）
3. `frontend/package.json`（顶层 `"version"`）
4. `frontend/package-lock.json`（顶层 `"version"` 与 `packages[""]["version"]`）
5. `desktop/package.json`（顶层 `"version"`）
6. `desktop/package-lock.json`（顶层 `"version"` 与 `packages[""]["version"]`）
7. `desktop/src-tauri/tauri.conf.json`（顶层 `"version"`）
8. `desktop/src-tauri/Cargo.toml`（`[package].version`）
9. `desktop/src-tauri/Cargo.lock`（仅匹配 `name = "flame-contractor-desktop"` 的 `[[package]]` 块）
10. `android/app/build.gradle.kts`（`requestedVersionName` 默认回退值 `"X.Y.Z-dev"`）

## 5. 脚本接口与流水线加固

- **`scripts/bump_version.py`（纯 Python 3.12 标准库）**：
  - `--check`：校验 10 处版本声明完全一致并输出当前版本号；存在漂移或格式错误时返回非零退出码。
  - `--check --tag <vX.Y.Z>`：在 `--check` 基础上校验标签形如 `v<version>` 且去掉前导 `v` 后严格等于全仓版本号。
  - `--set <X.Y.Z>`：先校验严格 SemVer 格式（支持可选 `-prerelease`，禁止前导 `v`、前导零与 `+build`），在内存中全部生成并校验新内容后再原子落盘，绝不误伤第三方依赖版本号并保持原文件 LF/CRLF 换行符。
  - `--root <PATH>`：支持指定目标仓库根目录以便测试夹具隔离验证。
- **CI 发版流水线加固**：
  - `.github/workflows/release-desktop.yml` 与 `.github/workflows/release-android.yml` 在构建前先执行 `bump_version.py --check`（tag 触发时附加 `--tag`）。
  - 两条工作流上传 Release 资产前统一使用竞态安全的 `gh release view || gh release create ... || gh release view` 兜底创建 Release。
