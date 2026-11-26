# 应用内直连反馈中转服务部署指南（国内免魔法自动建 GitHub Issue）

本目录提供一个**零依赖单文件 Cloudflare Worker**（`cloudflare-worker.js`），用于接收来自 Android App、Windows 桌面端和 Web 控制台的应用内反馈表单，支持**纯文本、最多 3 张截图以及可选脱敏诊断日志**上传；完成校验、限流、图片魔数检测与托管以及敏感词兜底脱敏后，自动在 `oinzc/flame-contractor` 创建带 `in-app-feedback` 标签的 GitHub Issue。

> **为什么需要中转？**
> 1. 国内用户无需代理、无需注册/登录 GitHub 即可在应用内直接提交反馈与截图；
> 2. GitHub Token 仅保存在中转端 Secret，绝不写入开源仓库或客户端 APK / 安装包，防止滥用。

---

## 3 分钟部署步骤

### 第 1 步：创建最小权限 GitHub Token

1. 打开 GitHub → **Settings → Developer settings → Personal access tokens → Fine-grained tokens → Generate new token**。
2. **Repository access** 选择 **Only select repositories** → 仅勾选 `oinzc/flame-contractor`。
3. **Repository permissions** 仅勾选 **Issues → Read and write**（其余全部保持 `No access`）。
4. 复制生成的 `github_pat_...` 备用。

### 第 2 步：部署到 Cloudflare Workers（免费额度：每日 10 万次请求）

**方式 A：命令行一键部署（推荐）**

```bash
cd docs/feedback-relay
npx wrangler login
npx wrangler secret put GITHUB_TOKEN   # 粘贴第 1 步的 Fine-Grained Token
npx wrangler deploy
```

**方式 B：网页后台直接粘贴**

1. 登录 [Cloudflare Dashboard](https://dash.cloudflare.com/) → **Workers & Pages → Create Worker**。
2. 将 `cloudflare-worker.js` 全文粘贴部署。
3. 在该 Worker 的 **Settings → Variables and Secrets** 中新增 Secret：`GITHUB_TOKEN = <你的 Token>`（可选加变量 `GITHUB_REPO = oinzc/flame-contractor`）。

### 第 3 步：1 分钟绑定图片存储（支持反馈附图，可选）

> 未绑定图片存储时，**纯文本反馈仍可正常提交**；只有携带截图的请求才会返回 HTTP `503` 提示。推荐优先使用 Workers KV（零门槛免信用卡）。

#### 方案 A：绑定 Workers KV（推荐，免费且无需绑定信用卡）

1. **网页后台 1 分钟绑定**：
   - 打开 Cloudflare Dashboard → **Storage & Databases → Workers KV → Create**，新建命名空间（如 `flame-feedback-images`）。
   - 进入你的 Worker → **Settings → Bindings → Add → KV namespace**：
     - Variable name 填写 `FEEDBACK_IMAGES_KV`
     - KV namespace 选择刚创建的命名空间并保存部署。
2. **命令行方式**：
   ```bash
   npx wrangler kv namespace create FEEDBACK_IMAGES_KV
   ```
   将输出的 `id` 填入 `wrangler.toml` 中取消注释的 `FEEDBACK_IMAGES_KV` 配置块，然后重新 `npx wrangler deploy`。
3. KV 托管的截图自动设置 `180` 天过期（`expirationTtl: 15552000`），足够覆盖 Issue 排查周期。

#### 方案 B：绑定 Cloudflare R2（可选，适合永久存储与更大容量）

1. 若已开通 R2，可执行 `npx wrangler r2 bucket create flame-feedback-images`（或在 Dashboard → **R2 Object Storage** 创建 Bucket）。
2. 在 Worker 的 **Settings → Bindings → Add → R2 bucket** 绑定变量名 `FEEDBACK_IMAGES_R2`（或取消注释 `wrangler.toml` 中的 `[[r2_buckets]]`）。
3. **优先级与平滑迁移**：若同时绑定 `FEEDBACK_IMAGES_R2` 与 `FEEDBACK_IMAGES_KV`，新上传图片优先写入 **R2**；读取 `GET /v1/images/:filename` 时按 **R2 → KV** 顺序自动兜底，便于后续从 KV 无痛迁移至 R2。

### 第 4 步：绑定自有域名（确保中国大陆直连免魔法）

> 注意：Cloudflare 默认的 `*.workers.dev` 子域名在中国大陆部分网络下可能无法直连；绑定你自己的二级域名（例如 `feedback.yourdomain.com`）后即可国内稳定直连，同时 Issue 里的截图外链也会自动使用该域名。

1. 在 Cloudflare Worker 的 **Settings → Domains & Routes → Add → Custom Domain** 填入自定义域名（如 `feedback.yourdomain.com`）。
   - 如需通过反代或独立域名暴露图片链接，可在 Vars 中可选设置 `PUBLIC_BASE_URL = https://feedback.yourdomain.com`（未设置时默认使用当前请求的 `url.origin`）。
2. 浏览器访问 `https://feedback.yourdomain.com/health`，看到以下 JSON 即就绪：
   ```json
   {
     "ok": true,
     "service": "flame-contractor-feedback-relay",
     "configured": true,
     "imageUploadSupported": true,
     "imageStorage": "kv",
     "diagnosticLogSupported": true,
     "contactPubliclyDropped": true
   }
   ```
   其中 `imageStorage` 取值为 `"r2"` / `"kv"` / `"none"`，`contactPubliclyDropped: true` 表示服务端已启用隐私加固策略，彻底丢弃任何客户端传入的 `contact` 字段，绝不写入公开 Issue。

### 第 5 步：一键注入到正式发版（Android APK + Windows 桌面端）

打开 GitHub 仓库 `oinzc/flame-contractor` → **Settings → Secrets and variables → Actions → Variables**，新增一个 Repository Variable：

- Name：`FLAME_FEEDBACK_ENDPOINT`
- Value：`https://feedback.yourdomain.com/v1/feedback`

配置完成后，后续每次打 Tag（如 `v0.5.0`）触发 GitHub Actions 发布构建时，**Android APK** 和 **Windows 桌面安装包**会自动将该地址打入客户端，无需修改任何代码。

### 本地联调验证

- **Android 本地构建**：在 `android/local.properties`（已 gitignore）追加一行：
  ```properties
  feedback.endpoint=https://feedback.yourdomain.com/v1/feedback
  ```
  或运行 Gradle 时传入 `-PfeedbackEndpoint=https://feedback.yourdomain.com/v1/feedback`。
- **Web / 桌面端本地构建**：在 `frontend/.env.local`（已 gitignore）写入：
  ```env
  VITE_FEEDBACK_ENDPOINT=https://feedback.yourdomain.com/v1/feedback
  ```

---

## 接口协议说明

### 1. 健康检查：`GET /health`（兼容 `GET /`）

```json
{
  "ok": true,
  "service": "flame-contractor-feedback-relay",
  "configured": true,
  "imageUploadSupported": true,
  "imageStorage": "r2",
  "diagnosticLogSupported": true,
  "contactPubliclyDropped": true
}
```

### 2. 提交反馈：`POST /v1/feedback`（兼容 `POST /`）

- `Content-Type: application/json`，总请求体上限 **4 MB**（超限返回 `413`）。
- 请求字段：
  ```json
  {
    "kind": "bug | feature | question",
    "title": "可选简短标题（≤100字）",
    "content": "必填详细描述（5–2000字）",
    "contact": "已废弃（仅为兼容旧版客户端保留，服务端静默丢弃，绝不写入公开 Issue）",
    "client": "android | desktop | web",
    "version": "v0.6.1",
    "environment": "脱敏运行环境摘要（可选）",
    "includeEnv": true,
    "website": "",
    "diagnosticLog": "可选脱敏诊断日志（≤16000字）",
    "images": [
      {
        "name": "screenshot.png",
        "mimeType": "image/png",
        "dataBase64": "纯 Base64 或 data:image/png;base64,..."
      }
    ]
  }
  ```
  - `contact` 字段仅为向后兼容旧版客户端（v0.5.0–v0.6.2）而容忍传入（不会返回 `400`），服务端会**彻底忽略并丢弃**，绝不再向公开 GitHub Issue 写入任何联系方式区块或内容。
  - `diagnosticLog` 为可选字符串字段（可省略、为 `null` 或为空字符串）：去除首尾空白后上限 **16,000** 字符（非字符串或超限均返回 HTTP `400`）。服务端会再次执行敏感信息脱敏，并将正文中的连续三个及以上反引号（`` ``` ``）转义为 `` ` ` ` `` 防止 Markdown 代码块逃逸，再以 `<details><summary>展开脱敏诊断日志（供排查定位）</summary>...</details>` 折叠插入「运行环境（已脱敏）」之后；未附带日志时不输出该折叠区块。
  - `images` 为可选字段（可省略或为空数组 `[]`）：最多 **3** 张，单张解码后体积不超过 **800 KB**，仅允许 **JPG / PNG / WebP**。
  - 若携带图片但服务端未绑定 `FEEDBACK_IMAGES_R2` 或 `FEEDBACK_IMAGES_KV`，返回 HTTP `503` 及中文错误提示；纯文本反馈不受影响。
- 成功响应（`201 Created`）：
  ```json
  {
    "ok": true,
    "issueNumber": 42,
    "issueUrl": "https://github.com/oinzc/flame-contractor/issues/42",
    "imageCount": 1,
    "hasDiagnosticLog": true,
    "message": "反馈已提交（#42，含 1 张截图），感谢你的帮助！"
  }
  ```
  有附图时，生成的 GitHub Issue 正文会在「反馈内容」与「运行环境（已脱敏）」之间自动插入 `### 附图` 区块并嵌入 Markdown 图片外链；有脱敏诊断日志时，会在「运行环境（已脱敏）」之后以折叠 `<details>` 区块展示。

### 3. 读取托管图片：`GET /v1/images/:filename`

- 文件名由服务端通过 `crypto.randomUUID()` 生成，仅严格匹配 `/^\/v1\/images\/([0-9a-f-]{36}\.(jpg|png|webp))$/i`。
- 命中返回 `200 OK` 及图片二进制流，未命中或非法路径返回 `404`。

---

## 安全策略

- **魔数校验（Magic Bytes）**：服务端忽略客户端声明的 `mimeType` 与文件名后缀，强制检查解码后文件头魔数（JPEG `FF D8 FF`、PNG `89 50 4E 47 0D 0A 1A 0A`、WebP `RIFF....WEBP`），拦截伪造 MIME、SVG/HTML 脚本或畸形 Base64。
- **防路径穿越与沙箱响应头**：图片路由仅放行 UUID + 白名单扩展名，并固定返回 `X-Content-Type-Options: nosniff`、`Content-Security-Policy: default-src 'none'; sandbox`、`Cross-Origin-Resource-Policy: cross-origin`、`Cache-Control: public, max-age=31536000, immutable` 及 CORS 头。
- **失败自动清理**：若 GitHub Issue 创建失败（上游返回非 2xx 或网络异常），Worker 会尽力（best-effort）删除本次已写入 R2/KV 的图片对象，避免孤立垃圾文件残留。
- **隐私加固与零联系方式落库**：服务端彻底忽略并丢弃 `contact` 字段（兼容旧版客户端不报 `400`），绝不向公开 Issue 输出任何联系方式区块。
- **脱敏、防注入与限流**：服务端对全部文本与 `diagnosticLog` 执行二次正则脱敏（Bark URL Key、`device_key=...`、Cookie、Bearer Token、GitHub Token、抖音 `sec_uid`、以及独立出现的中国大陆 11 位手机号自动打码为前 3 后 4 格式如 `138****5678`，同时避免误伤 13 位毫秒时间戳与长数字/十六进制 ID），对诊断日志连续反引号做防 Markdown 代码块逃逸处理，并保持按 IP 限流机制不变。
