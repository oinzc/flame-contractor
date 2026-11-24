# 应用内直连反馈中转服务部署指南（国内免魔法自动建 GitHub Issue）

本目录提供一个**零依赖单文件 Cloudflare Worker**（`cloudflare-worker.js`），用于接收来自 Android App、Windows 桌面端和 Web 控制台的应用内反馈表单，校验限流并兜底脱敏后，自动在 `oinzc/flame-contractor` 创建带 `in-app-feedback` 标签的 GitHub Issue。

> **为什么需要中转？**
> 1. 国内用户无需代理、无需注册/登录 GitHub 即可在应用内直接提交反馈；
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

### 第 3 步：绑定自有域名（确保中国大陆直连免魔法）

> 注意：Cloudflare 默认的 `*.workers.dev` 子域名在中国大陆部分网络下可能无法直连；绑定你自己的二级域名（例如 `feedback.yourdomain.com`）后即可国内稳定直连。

1. 在 Cloudflare Worker 的 **Settings → Domains & Routes → Add → Custom Domain** 填入自定义域名（如 `feedback.yourdomain.com`）。
2. 浏览器访问 `https://feedback.yourdomain.com/health`，看到以下 JSON 即就绪：
   ```json
   {"ok":true,"service":"flame-contractor-feedback-relay","configured":true}
   ```

### 第 4 步：一键注入到正式发版（Android APK + Windows 桌面端）

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

- `POST /v1/feedback`（`Content-Type: application/json`）
- 请求字段：
  ```json
  {
    "kind": "bug | feature | question",
    "title": "可选简短标题（≤100字）",
    "content": "必填详细描述（5–2000字）",
    "contact": "可选联系方式（≤100字）",
    "client": "android | desktop | web",
    "version": "v0.5.0",
    "environment": "脱敏运行环境摘要（可选）",
    "includeEnv": true,
    "website": ""
  }
  ```
- 成功响应（`201 Created`）：
  ```json
  {
    "ok": true,
    "issueNumber": 42,
    "issueUrl": "https://github.com/oinzc/flame-contractor/issues/42",
    "message": "反馈已提交（#42），感谢你的帮助！"
  }
  ```
