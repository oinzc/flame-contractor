/**
 * Flame Contractor 轻量反馈中转服务（Cloudflare Worker 单文件版，零外部依赖）
 *
 * 作用：接收来自 Android App / Web / Windows 桌面端的应用内反馈 POST 请求，
 *       完成校验、限流与敏感词兜底脱敏后，自动在目标 GitHub 仓库创建带标签的 Issue。
 *       国内用户无需魔法、无需登录 GitHub 即可直接反馈；GitHub Token 仅保存在中转端 Secret 中。
 *
 * 环境变量（Secrets / Vars）：
 * - GITHUB_TOKEN (Secret，必填)：Fine-Grained PAT，仅需授予目标仓库 `Issues: Read and write` 权限
 * - GITHUB_REPO  (Var，可选)：默认 `oinzc/flame-contractor`
 * - RATE_LIMIT_MAX (Var，可选)：单 IP 时间窗内最大提交次数，默认 `5`
 * - RATE_LIMIT_WINDOW_SEC (Var，可选)：限流窗口秒数，默认 `600`（10 分钟）
 * - RATE_LIMIT_KV (可选 KV 命名空间)：绑定后跨节点共享限流计数；未绑定时自动退化为实例内限流
 */

const DEFAULT_REPO = 'oinzc/flame-contractor'
const MAX_BODY_BYTES = 16 * 1024
const MIN_CONTENT_LEN = 5
const MAX_CONTENT_LEN = 2000
const MAX_TITLE_LEN = 100
const MAX_CONTACT_LEN = 100
const MAX_ENV_LEN = 400

const memoryBuckets = new Map()

const KIND_META = {
  bug: { prefix: '[Bug] ', label: 'bug', display: 'Bug 反馈' },
  feature: { prefix: '[Feature] ', label: 'enhancement', display: '功能建议' },
  question: { prefix: '[Question] ', label: 'question', display: '使用疑问' },
}

const CLIENT_LABELS = {
  android: 'Android App',
  desktop: 'Windows 桌面端',
  web: 'Web / Docker 控制台',
}

function corsHeaders() {
  return {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'POST, GET, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type',
    'Access-Control-Max-Age': '86400',
  }
}

function jsonResponse(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'no-store',
      ...corsHeaders(),
    },
  })
}

export function sanitizeSensitiveText(raw) {
  if (!raw) return ''
  return String(raw)
    .replace(/(https?:\/\/api\.day\.app\/)([^/\s"'`<>]+)/gi, '$1[REDACTED_BARK_KEY]')
    .replace(/\b(sessionid|sessionid_ss|sid_tt|sid_guard|passport_csrf_token|ttwid)\s*=\s*[^;\s"'`]+/gi, '$1=[REDACTED]')
    .replace(/\b(Authorization\s*:\s*Bearer\s+)[^\s"'`]+/gi, '$1[REDACTED_TOKEN]')
    .replace(/\b(ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\b/g, '[REDACTED_GH_TOKEN]')
}

function deriveTitle(rawTitle, content, kindMeta) {
  const cleanTitle = sanitizeSensitiveText(rawTitle).replace(/[\r\n]+/g, ' ').trim()
  if (cleanTitle) {
    const truncated = cleanTitle.slice(0, MAX_TITLE_LEN)
    return truncated.startsWith('[') ? truncated : `${kindMeta.prefix}${truncated}`
  }
  const firstLine = sanitizeSensitiveText(content)
    .split(/[\r\n]+/)
    .map((line) => line.trim())
    .find((line) => line.length > 0) || '应用内用户反馈'
  const summary = firstLine.length > 42 ? `${firstLine.slice(0, 42)}…` : firstLine
  return `${kindMeta.prefix}${summary}`
}

async function checkRateLimit(ip, env) {
  const maxReq = Math.max(1, Number.parseInt(env.RATE_LIMIT_MAX || '5', 10) || 5)
  const windowSec = Math.max(60, Number.parseInt(env.RATE_LIMIT_WINDOW_SEC || '600', 10) || 600)
  const nowSec = Math.floor(Date.now() / 1000)
  const bucketKey = `rl:${ip}:${Math.floor(nowSec / windowSec)}`

  if (env.RATE_LIMIT_KV && typeof env.RATE_LIMIT_KV.get === 'function') {
    const current = Number.parseInt((await env.RATE_LIMIT_KV.get(bucketKey)) || '0', 10) || 0
    if (current >= maxReq) return false
    await env.RATE_LIMIT_KV.put(bucketKey, String(current + 1), { expirationTtl: windowSec + 30 })
    return true
  }

  // Fallback: isolate-local sliding window
  const cutoff = nowSec - windowSec
  const history = (memoryBuckets.get(ip) || []).filter((ts) => ts > cutoff)
  if (history.length >= maxReq) {
    memoryBuckets.set(ip, history)
    return false
  }
  history.push(nowSec)
  memoryBuckets.set(ip, history)
  if (memoryBuckets.size > 500) {
    const oldestKey = memoryBuckets.keys().next().value
    if (oldestKey) memoryBuckets.delete(oldestKey)
  }
  return true
}

export function buildIssuePayload(input) {
  const kindKey = Object.prototype.hasOwnProperty.call(KIND_META, input.kind) ? input.kind : 'bug'
  const kindMeta = KIND_META[kindKey]
  const clientKey = Object.prototype.hasOwnProperty.call(CLIENT_LABELS, input.client) ? input.client : 'web'
  const clientLabel = CLIENT_LABELS[clientKey]

  const content = sanitizeSensitiveText(input.content).trim()
  const title = deriveTitle(input.title || '', content, kindMeta)
  const version = sanitizeSensitiveText(input.version || '').trim().slice(0, 40) || '未提供'
  const contact = sanitizeSensitiveText(input.contact || '').trim().slice(0, MAX_CONTACT_LEN)
  const includeEnv = input.includeEnv !== false
  const envInfo = includeEnv ? sanitizeSensitiveText(input.environment || '').trim().slice(0, MAX_ENV_LEN) : ''

  const sections = [
    `> 来源：应用内直连反馈（\`${clientLabel}\` · ${kindMeta.display}）`,
    '',
    '### 反馈内容',
    content,
    '',
    '### 运行环境（已脱敏）',
    `- 客户端：${clientLabel}`,
    `- 版本：\`${version}\``,
  ]

  if (envInfo) {
    sections.push('', '```text', envInfo, '```')
  }

  if (contact) {
    sections.push('', '### 联系方式（用户自愿留存）', contact)
  }

  return {
    title,
    body: sections.join('\n'),
    labels: [kindMeta.label, 'in-app-feedback'],
  }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url)

    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: corsHeaders() })
    }

    if (request.method === 'GET' && (url.pathname === '/health' || url.pathname === '/')) {
      return jsonResponse({
        ok: true,
        service: 'flame-contractor-feedback-relay',
        configured: Boolean(env && env.GITHUB_TOKEN),
      })
    }

    if (request.method !== 'POST' || (url.pathname !== '/' && url.pathname !== '/v1/feedback')) {
      return jsonResponse({ ok: false, error: '接口路径不存在' }, 404)
    }

    if (!env || !env.GITHUB_TOKEN) {
      return jsonResponse({ ok: false, error: '反馈中转服务尚未配置 GITHUB_TOKEN' }, 503)
    }

    const contentLength = Number.parseInt(request.headers.get('content-length') || '0', 10)
    if (contentLength > MAX_BODY_BYTES) {
      return jsonResponse({ ok: false, error: '提交内容体积过大' }, 413)
    }

    const rawText = await request.text()
    if (rawText.length > MAX_BODY_BYTES) {
      return jsonResponse({ ok: false, error: '提交内容体积过大' }, 413)
    }

    let body
    try {
      body = JSON.parse(rawText)
    } catch {
      return jsonResponse({ ok: false, error: '请求体 JSON 格式不正确' }, 400)
    }

    if (!body || typeof body !== 'object') {
      return jsonResponse({ ok: false, error: '请求参数无效' }, 400)
    }

    // 蜜罐字段：正常客户端始终为空字符串，机器人填充则直接拦截
    if (typeof body.website === 'string' && body.website.trim().length > 0) {
      return jsonResponse({ ok: false, error: '请求被拦截' }, 400)
    }

    const rawContent = typeof body.content === 'string' ? body.content.trim() : ''
    if (rawContent.length < MIN_CONTENT_LEN) {
      return jsonResponse({ ok: false, error: `请至少输入 ${MIN_CONTENT_LEN} 个字的反馈描述` }, 400)
    }
    if (rawContent.length > MAX_CONTENT_LEN) {
      return jsonResponse({ ok: false, error: `反馈内容请控制在 ${MAX_CONTENT_LEN} 字以内` }, 400)
    }

    const clientIp =
      request.headers.get('CF-Connecting-IP') ||
      (request.headers.get('X-Forwarded-For') || '').split(',')[0].trim() ||
      'unknown'

    const allowed = await checkRateLimit(clientIp, env)
    if (!allowed) {
      return jsonResponse({ ok: false, error: '提交过于频繁，请稍后再试' }, 429)
    }

    const repo = (env.GITHUB_REPO || DEFAULT_REPO).trim()
    const issuePayload = buildIssuePayload(body)

    const ghResp = await fetch(`https://api.github.com/repos/${repo}/issues`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${env.GITHUB_TOKEN}`,
        Accept: 'application/vnd.github+json',
        'Content-Type': 'application/json',
        'X-GitHub-Api-Version': '2022-11-28',
        'User-Agent': 'flame-contractor-feedback-relay',
      },
      body: JSON.stringify(issuePayload),
    })

    if (!ghResp.ok) {
      return jsonResponse(
        { ok: false, error: `反馈提交暂时不可用（上游 HTTP ${ghResp.status}），请稍后重试` },
        502,
      )
    }

    const created = await ghResp.json()
    return jsonResponse(
      {
        ok: true,
        issueNumber: created.number ?? null,
        issueUrl: created.html_url ?? null,
        message: created.number
          ? `反馈已提交（#${created.number}），感谢你的帮助！`
          : '反馈已提交成功，感谢你的帮助！',
      },
      201,
    )
  },
}
