# Android 应用内自动更新与 Cloudflare R2 分发部署指南

本文档说明如何配置 **Cloudflare R2 + 自有域名**，配合 GitHub Actions（`.github/workflows/release-android.yml`）实现 Android 正式签名 APK 与 `latest.json` 更新清单的自动同步发布，让国内用户无需代理即可在应用内完成检查更新、SHA-256 校验与一键安装。

> **架构要点**
> 1. **零出口流量费 + 国内直连**：通过自有域名（如 `dl.20190415.cloud`）对外提供 R2 静态下载，避免依赖 GitHub Releases 直连或受限的 `*.r2.dev` 测试域；
> 2. **原子发版顺序**：CI 严格按照「先上传版本号隔离的 APK（长缓存）→ 再覆盖 `android/latest.json`（短缓存）」执行，防止客户端读到新清单时安装包尚未就绪；
> 3. **开源 Fork 友好**：未配置 R2 Secrets 或更新域名时，流水线仅打印 `::notice::` 优雅跳过 R2 同步，照常完成 GitHub Release 发布。

---

## 5 分钟部署步骤

### 第 1 步：创建 Cloudflare R2 存储桶

1. 登录 [Cloudflare Dashboard](https://dash.cloudflare.com/) → 左侧菜单 **R2 Object Storage** → 点击 **Create bucket**。
2. 填写存储桶名称（例如 `flame-contractor-releases`），Location 保持默认 **Automatic**（或选择 **Asia-Pacific (APAC)**），点击创建。
3. 本项目在桶内使用的对象路径规划如下（由 CI 自动写入，无需手动建目录）：
   - 历史/当前正式 APK：`android/releases/FlameContractor-<version>-android-universal-release.apk`（`Cache-Control: public, max-age=31536000, immutable`）
   - 最新版本清单：`android/latest.json`（`Cache-Control: no-cache, max-age=60`）

### 第 2 步：绑定自有域名（例如 `dl.20190415.cloud`）

> 请勿使用 R2 默认的 `*.r2.dev` 公开地址（官方明确标注仅限开发调试、存在限速且部分地区不稳定）。

1. 确保你的主域名（如 `20190415.cloud`）已托管在 Cloudflare DNS。
2. 进入刚创建的 R2 存储桶 → **Settings** → **Public access** → **Custom Domains** → 点击 **Connect Domain**。
3. 输入子域名（例如 `dl.20190415.cloud`），按提示确认后 Cloudflare 会自动创建 DNS 记录并签发 HTTPS 证书（通常 1–2 分钟内变为 **Active**）。
4. 保持默认的 `r2.dev` 子域名为禁用状态即可，仅通过自有 HTTPS 域名对外提供访问。

### 第 3 步：创建仅限该桶的 R2 API Token（最小权限）

1. 在 Cloudflare Dashboard → **R2 Object Storage** 右侧点击 **Manage R2 API Tokens** → **Create API token**。
2. 按以下最小权限配置：
   - **Token name**：如 `flame-contractor-android-release`
   - **Permissions**：选择 **Object Read & Write**（仅需对象读写，不要给 Admin 权限）
   - **Specify bucket(s)**：选择 **Apply to specific buckets only** → 仅勾选 `flame-contractor-releases`
   - **TTL**：按团队轮换周期设置（或 Forever）
3. 创建后立刻保存页面显示的以下 3 项凭据（Secret 仅展示一次），同时在 R2 概览页复制你的 **Account ID**：
   - `Account ID`（32 位十六进制字符串，用于拼装 `https://<Account ID>.r2.cloudflarestorage.com`）
   - `Access Key ID`
   - `Secret Access Key`

### 第 4 步：配置 GitHub Repository Variables 与 Secrets

打开 GitHub 仓库 → **Settings → Secrets and variables → Actions**，分别配置以下变量与密钥：

#### 1. Repository Variables（`Variables` 标签页）

| 变量名 | 必填 | 示例值 | 说明 |
| --- | --- | --- | --- |
| `FLAME_UPDATE_BASE_URL` | 推荐必填 | `https://dl.20190415.cloud` | R2 自有域名根地址（必须为 `https://`，建议不带尾斜杠）。CI 会自动拼出 APK 下载地址与默认清单地址 `${FLAME_UPDATE_BASE_URL}/android/latest.json`。 |
| `FLAME_UPDATE_MANIFEST_URL` | 可选 | `https://dl.20190415.cloud/android/latest.json` | 仅当客户端读取清单的 URL 与 `FLAME_UPDATE_BASE_URL` 默认推导路径不同时填写；配置后优先级高于自动推导。 |
| `FLAME_UPDATE_MIN_VERSION_CODE` | 可选 | `0` | 自动发版时写入 `latest.json` 的 `minVersionCode`，常规发版留空即可（默认 `0`）。 |

#### 2. Repository Secrets（`Secrets` 标签页）

| Secret 名 | 说明 |
| --- | --- |
| `CF_R2_ACCOUNT_ID` | 第 3 步记录的 Cloudflare Account ID |
| `CF_R2_ACCESS_KEY_ID` | 第 3 步创建的仅限单桶 R2 Access Key ID |
| `CF_R2_SECRET_ACCESS_KEY` | 第 3 步创建的仅限单桶 R2 Secret Access Key |
| `CF_R2_BUCKET` | 第 1 步创建的存储桶名称（如 `flame-contractor-releases`） |

> **未配置时的回退行为**
> - 只有同时配置了 4 个 `CF_R2_*` Secrets **且** `FLAME_UPDATE_BASE_URL` 非空时，CI 才会执行 R2 上传；否则仅打印 `::notice::` 跳过，不影响 GitHub Release。
> - 当 `FLAME_UPDATE_BASE_URL` 非空但未配置 `CF_R2_*` Secrets 时，`latest.json` 中的 `downloadUrl` 仍会指向 `FLAME_UPDATE_BASE_URL`，请务必**成对配置**变量与 Secrets，避免生成指向未上传文件的链接。
> - 当 `FLAME_UPDATE_BASE_URL` 为空时，`latest.json` 的 `downloadUrl` 自动回退为 GitHub Release 下载链接，客户端内置 `UPDATE_MANIFEST_URL` 为空字符串（不发起网络请求）。

### 第 5 步：发版验证与本地联调

1. 推送符合 SemVer 的正式 Tag（例如 `v0.6.1`）并确认 `Build Android Standalone APK` 工作流全部绿灯。
2. 线上冒烟校验：
   ```bash
   # 检查 latest.json 内容与缓存头
   curl -fsSL https://dl.20190415.cloud/android/latest.json
   curl -fsSI https://dl.20190415.cloud/android/latest.json

   # 检查对应版本 APK 可达性与 Content-Type / immutable 缓存头
   curl -fsSI https://dl.20190415.cloud/android/releases/FlameContractor-0.6.1-android-universal-release.apk
   ```
3. 本地 Android 构建联调：在 `android/local.properties`（已 gitignore）追加：
   ```properties
   update.manifest.url=https://dl.20190415.cloud/android/latest.json
   ```
   或在执行 Gradle 时通过 `-PupdateManifestUrl=https://dl.20190415.cloud/android/latest.json` / 环境变量 `FLAME_UPDATE_MANIFEST_URL` 注入。

---

## 确定性 `versionCode` 规则

正式 Tag 发版（`refs/tags/v*`）由 `scripts/android_release_manifest.py --compute-version-code` 按以下公式确定性生成整型 `versionCode`：

```text
versionCode = major * 10000 + minor * 100 + patch
```

- 约束：`0 <= major <= 200000`、`0 <= minor < 100`、`0 <= patch < 100`，且结果严格 `> 0`。
- 示例：`0.6.1` → `601`、`0.6.2` → `602`、`1.2.3` → `10203`（严格大于早期 `v0.6.0` 使用 `github.run_number` 产出的 `3`）。
- 手动 `workflow_dispatch` 非 Tag 调试构建仍保留 `github.run_number`，不会与正式发布混淆。

---

## `latest.json` 字段协议

客户端与发版脚本严格遵循以下 Schema（UTF-8 JSON，`Content-Type: application/json; charset=utf-8`）：

```json
{
  "schemaVersion": 1,
  "platform": "android",
  "versionName": "0.6.1",
  "versionCode": 601,
  "minVersionCode": 0,
  "publishedAt": "2026-11-26T00:00:00Z",
  "fileSize": 18234567,
  "sha256": "<64位小写hex>",
  "downloadUrl": "https://dl.20190415.cloud/android/releases/FlameContractor-0.6.1-android-universal-release.apk",
  "releasePageUrl": "https://github.com/oinzc/flame-contractor/releases/tag/v0.6.1",
  "changelog": "更新说明"
}
```

| 字段 | 类型 | 必填 | 说明与校验规则 |
| --- | --- | --- | --- |
| `schemaVersion` | integer | 是 | 协议版本号，当前固定为 `1`。 |
| `platform` | string | 是 | 固定为 `"android"`，防止跨端清单误读。 |
| `versionName` | string | 是 | 严格 SemVer（不含前导 `v`，如 `"0.6.1"`）。 |
| `versionCode` | integer | 是 | 确定性正整数版本码（如 `601`）。客户端要求 `remote.versionCode > currentVersionCode` 且 SemVer 不降级才判为有新版本。 |
| `minVersionCode` | integer | 是 | 最低要求版本码，默认 `0`（不强更）；必须满足 `0 <= minVersionCode <= versionCode`。 |
| `publishedAt` | string | 是 | UTC ISO-8601 时间戳（`YYYY-MM-DDTHH:MM:SSZ`）。 |
| `fileSize` | integer | 是 | 签名 APK 精确字节数（> 0），供下载进度展示与上限校验。 |
| `sha256` | string | 是 | 签名 APK 的 64 位小写十六进制 SHA-256 摘要；客户端下载完成后强制比对，不一致立即拦截安装并删除缓存包。 |
| `downloadUrl` | string | 是 | APK 下载地址，强制要求 `https://` 且不得包含空白或换行字符。 |
| `releasePageUrl` | string | 否 | 对应 GitHub Release 详情页 `https://` 地址（允许为空字符串 `""`）。 |
| `changelog` | string | 是 | 面向用户的更新说明文本（优先取 GitHub Release 正文，为空时回退至上一 Tag 至今的精简提交摘要）。 |

---

## `minVersionCode` 紧急强提醒用法

客户端判定是否触发「不可忽略的强制更新提醒」的条件为：

```text
isMandatory = isUpdateAvailable && minVersionCode > 0 && currentVersionCode < minVersionCode
```

适用场景：旧版本存在严重缺陷、安全问题或抖音协议变更导致续火失效，需要所有低于基线的客户端无视「忽略此版本」并持续强提醒升级。

### 操作方式 A：随紧急修复版一起发布（推荐）

1. 在 GitHub Repository Variables 中临时将 `FLAME_UPDATE_MIN_VERSION_CODE` 设为目标最低版本码（例如刚修复 `0.6.1` 的问题并发布 `v0.6.2`，则设为 `602`，要求所有 `< 602` 的旧端强制升级）。
2. 打 Tag 推送（如 `v0.6.2`）完成自动发版。
3. 确认线上 `latest.json` 生效后，可根据需要保留该基线或在下一次常规发版前改回 `0`。

### 操作方式 B：不重编 APK，直接热更新 R2 上的 `latest.json`

若修复版 `v0.6.2`（`versionCode=602`）已发布在 R2，仅需事后追加强提醒：

```bash
# 1. 拉取当前线上的 latest.json 并修改 minVersionCode（必须 <= versionCode）
curl -fsSL https://dl.20190415.cloud/android/latest.json -o latest.json
python3 -c '
import json, pathlib
p = pathlib.Path("latest.json")
data = json.loads(p.read_text(encoding="utf-8"))
data["minVersionCode"] = data["versionCode"]
p.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
'

# 2. 覆盖上传到 R2（注意设置短缓存头）
export AWS_ACCESS_KEY_ID="<CF_R2_ACCESS_KEY_ID>"
export AWS_SECRET_ACCESS_KEY="<CF_R2_SECRET_ACCESS_KEY>"
export AWS_DEFAULT_REGION="auto"
export AWS_REQUEST_CHECKSUM_CALCULATION="when_required"
export AWS_RESPONSE_CHECKSUM_VALIDATION="when_required"

aws s3 cp latest.json \
  "s3://<CF_R2_BUCKET>/android/latest.json" \
  --endpoint-url "https://<CF_R2_ACCOUNT_ID>.r2.cloudflarestorage.com" \
  --content-type "application/json; charset=utf-8" \
  --cache-control "no-cache, max-age=60"
```

> 切勿将 `minVersionCode` 设置为大于当前 `versionCode` 的数值；脚本会在生成阶段直接报错拦截。

---

## 发版回滚步骤

由于每个版本的 APK 都按版本号独立存放于 `android/releases/FlameContractor-<version>-android-universal-release.apk`，回滚时**不需要删除旧 APK**，只需将 `android/latest.json` 回指上一个稳定版本即可在 60 秒内止损：

1. **获取上一稳定版本的 `latest.json`**（例如从 `v0.6.1` 回滚到 `v0.6.0`，或在本地基于上一稳定版 APK 重新生成）：
   ```bash
   # 方式 1：直接从上一稳定版 GitHub Release 下载当时归档的 latest.json
   gh release download v0.6.0 -p "latest.json" -O rollback-latest.json

   # 方式 2：若历史 Release 未附带 latest.json，可下载该版签名 APK 后离线重建
   python3 scripts/android_release_manifest.py --build-manifest \
     --apk FlameContractor-0.6.0-android-universal-release.apk \
     --version-name 0.6.0 \
     --version-code 600 \
     --base-url https://dl.20190415.cloud \
     --apk-object-key android/releases/FlameContractor-0.6.0-android-universal-release.apk \
     --release-page-url https://github.com/oinzc/flame-contractor/releases/tag/v0.6.0 \
     --changelog "回滚至稳定版本 0.6.0" \
     --min-version-code 0 \
     --output rollback-latest.json
   ```
2. **核验目标 APK 在 R2 上可正常下载**（返回 `HTTP 200` 且大小与 `rollback-latest.json` 一致）：
   ```bash
   curl -fsSI "$(python3 -c 'import json; print(json.load(open("rollback-latest.json"))["downloadUrl"])')"
   ```
   若 R2 中缺少该稳定版 APK，先用 `aws s3 cp` 上传 APK（带 `application/vnd.android.package-archive` 与 `public, max-age=31536000, immutable`），再执行下一步。
3. **覆盖上传 `android/latest.json`**：
   ```bash
   export AWS_ACCESS_KEY_ID="<CF_R2_ACCESS_KEY_ID>"
   export AWS_SECRET_ACCESS_KEY="<CF_R2_SECRET_ACCESS_KEY>"
   export AWS_DEFAULT_REGION="auto"
   export AWS_REQUEST_CHECKSUM_CALCULATION="when_required"
   export AWS_RESPONSE_CHECKSUM_VALIDATION="when_required"

   aws s3 cp rollback-latest.json \
     "s3://<CF_R2_BUCKET>/android/latest.json" \
     --endpoint-url "https://<CF_R2_ACCOUNT_ID>.r2.cloudflarestorage.com" \
     --content-type "application/json; charset=utf-8" \
     --cache-control "no-cache, max-age=60"
   ```
4. **立即清缓存与收尾**：
   - 如需不等 60 秒 TTL，在 Cloudflare Dashboard → 对应域名 → **Caching → Configuration → Purge Cache → Custom Purge**，刷新 `https://dl.20190415.cloud/android/latest.json`。
   - 用 `curl -fsSL https://dl.20190415.cloud/android/latest.json` 确认已指向稳定版本。
   - 在 GitHub Releases 中将问题版本标注为撤回/Pre-release；由于 Android 系统不允许直接覆盖降级安装，已升级到问题版本的用户需等待发布**更高语义版本号**（如 `v0.6.2`）的修复版推送升级，或手动卸载后安装稳定版。
