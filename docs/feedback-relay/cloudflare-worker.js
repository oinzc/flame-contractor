/**
 * Flame Contractor 轻量反馈中转服务（Cloudflare Worker 单文件版，零外部依赖）
 *
 * 作用：接收来自 Android App / Web / Windows 桌面端的应用内反馈 POST 请求，
 *       完成校验、限流、图片魔数验证与托管、以及敏感词兜底脱敏后，
 *       自动在目标 GitHub 仓库创建带标签与截图外链的 Issue。
 *       国内用户无需魔法、无需登录 GitHub 即可直接反馈；GitHub Token 仅保存在中转端 Secret 中。
 *
 * 环境变量（Secrets / Vars / Bindings）：
 * - GITHUB_TOKEN (Secret，必填)：Fine-Grained PAT，仅需授予目标仓库 `Issues: Read and write` 权限
 * - GITHUB_REPO  (Var，可选)：默认 `oinzc/flame-contractor`
 * - PUBLIC_BASE_URL (Var，可选)：图片公网访问基准 URL，默认使用当前请求的 `url.origin`
 * - RATE_LIMIT_MAX (Var，可选)：单 IP 时间窗内最大提交次数，默认 `5`
 * - RATE_LIMIT_WINDOW_SEC (Var，可选)：限流窗口秒数，默认 `600`（10 分钟）
 * - RATE_LIMIT_KV (可选 KV 命名空间)：绑定后跨节点共享限流计数；未绑定时自动退化为实例内限流
 * - FEEDBACK_IMAGES_R2 (可选 R2 存储桶)：优先用于托管反馈截图（永久存储、容量大）
 * - FEEDBACK_IMAGES_KV (可选 KV 命名空间)：免信用卡降级图片存储（TTL 180 天）；与 R2 同时存在时优先 R2
 */

const DEFAULT_REPO = 'oinzc/flame-contractor'
const MAX_BODY_BYTES = 4 * 1024 * 1024
const MAX_IMAGES = 3
const MAX_IMAGE_BYTES = 800 * 1024
const IMAGE_KV_TTL_SEC = 180 * 86400
const MIN_CONTENT_LEN = 5
const MAX_CONTENT_LEN = 2000
const MAX_TITLE_LEN = 100
const MAX_ENV_LEN = 400
const MAX_DIAGNOSTIC_LOG_LEN = 16_000

const IMAGE_PATH_REGEX = /^\/v1\/images\/([0-9a-f-]{36}\.(jpg|png|webp))$/i
const DATA_URL_IMAGE_PREFIX_REGEX = /^data:image\/[a-z0-9.+-]+(?:;[^;,]*)*;base64,/i
const BASE64_REGEX = /^[A-Za-z0-9+/]+={0,2}$/

const EXT_TO_CONTENT_TYPE = {
  jpg: 'image/jpeg',
  png: 'image/png',
  webp: 'image/webp',
}

const ALLOWED_IMAGE_CONTENT_TYPES = new Set(Object.values(EXT_TO_CONTENT_TYPE))

const IMAGE_STORAGE_UNAVAILABLE_ERROR =
  '当前反馈中转服务尚未绑定图片存储（FEEDBACK_IMAGES_R2 / FEEDBACK_IMAGES_KV），请先移除图片或联系维护者配置后重试'

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
    .replace(
      /\b(device_key)\s*=\s*(?:\[REDACTED[^\]]*\]|(["'])[^;&,\s"'`<>)}\]]+\2?|[^;&,\s"'`<>)}\]]+)/gi,
      '$1=[REDACTED]',
    )
    .replace(/(["']device_key["']\s*:\s*["'])[^"']+(["'])/gi, '$1[REDACTED]$2')
    .replace(/\b(Authorization\s*:\s*Bearer\s+)[^\s"'`]+/gi, '$1[REDACTED_TOKEN]')
    .replace(/\b(ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\b/g, '[REDACTED_GH_TOKEN]')
    .replace(/\bMS4wLjAB[A-Za-z0-9_-]{8,}/g, '[REDACTED_UID]')
    .replace(
      /(?<![0-9A-Za-z])(\+86[\s-]?)?(1[3-9]\d)\d{4}(\d{4})(?![0-9A-Za-z])/g,
      (_match, prefix = '', head, tail) => `${prefix}${head}****${tail}`,
    )
}

function escapeMarkdownCodeFences(raw) {
  if (!raw) return ''
  return String(raw).replace(/`{3,}/g, '` ` `')
}

function formatDiagnosticLogForIssue(raw) {
  if (typeof raw !== 'string') return ''
  const sanitized = sanitizeSensitiveText(raw).trim()
  if (!sanitized) return ''
  return escapeMarkdownCodeFences(sanitized)
}

function parseAndValidateDiagnosticLog(rawDiagnosticLog) {
  if (rawDiagnosticLog === undefined || rawDiagnosticLog === null) {
    return { diagnosticLog: '' }
  }
  if (typeof rawDiagnosticLog !== 'string') {
    return { error: '诊断日志参数必须为字符串' }
  }
  const trimmed = rawDiagnosticLog.trim()
  if (trimmed.length > MAX_DIAGNOSTIC_LOG_LEN) {
    return { error: `诊断日志请控制在 ${MAX_DIAGNOSTIC_LOG_LEN} 字以内` }
  }
  return { diagnosticLog: trimmed }
}

export function detectImageFormat(bytes) {
  if (!(bytes instanceof Uint8Array)) return null

  // JPEG: FF D8 FF
  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) {
    return { ext: 'jpg', contentType: 'image/jpeg' }
  }

  // PNG: 89 50 4E 47 0D 0A 1A 0A
  if (
    bytes.length >= 8 &&
    bytes[0] === 0x89 &&
    bytes[1] === 0x50 &&
    bytes[2] === 0x4e &&
    bytes[3] === 0x47 &&
    bytes[4] === 0x0d &&
    bytes[5] === 0x0a &&
    bytes[6] === 0x1a &&
    bytes[7] === 0x0a
  ) {
    return { ext: 'png', contentType: 'image/png' }
  }

  // WebP: RIFF (0..3) + WEBP (8..11)
  if (
    bytes.length >= 12 &&
    bytes[0] === 0x52 &&
    bytes[1] === 0x49 &&
    bytes[2] === 0x46 &&
    bytes[3] === 0x46 &&
    bytes[8] === 0x57 &&
    bytes[9] === 0x45 &&
    bytes[10] === 0x42 &&
    bytes[11] === 0x50
  ) {
    return { ext: 'webp', contentType: 'image/webp' }
  }

  return null
}

function decodeBase64ToBytes(rawBase64, index) {
  if (typeof rawBase64 !== 'string') {
    return { error: `第 ${index + 1} 张图片缺少有效的 Base64 数据` }
  }

  const trimmed = rawBase64.trim()
  const withoutPrefix = trimmed.replace(DATA_URL_IMAGE_PREFIX_REGEX, '')
  const cleaned = withoutPrefix.replace(/\s+/g, '')

  if (!cleaned) {
    return { error: `第 ${index + 1} 张图片内容为空` }
  }

  if (cleaned.length % 4 !== 0 || !BASE64_REGEX.test(cleaned)) {
    return { error: `第 ${index + 1} 张图片 Base64 格式不正确` }
  }

  const paddingLen = cleaned.endsWith('==') ? 2 : cleaned.endsWith('=') ? 1 : 0
  const estimatedBytes = (cleaned.length / 4) * 3 - paddingLen
  if (estimatedBytes <= 0) {
    return { error: `第 ${index + 1} 张图片内容为空` }
  }
  if (estimatedBytes > MAX_IMAGE_BYTES) {
    return { error: `第 ${index + 1} 张图片体积超过 ${Math.floor(MAX_IMAGE_BYTES / 1024)} KB 限制` }
  }

  let binary
  try {
    binary = atob(cleaned)
  } catch {
    return { error: `第 ${index + 1} 张图片 Base64 解码失败` }
  }

  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i += 1) {
    bytes[i] = binary.charCodeAt(i)
  }

  if (bytes.byteLength === 0) {
    return { error: `第 ${index + 1} 张图片内容为空` }
  }
  if (bytes.byteLength > MAX_IMAGE_BYTES) {
    return { error: `第 ${index + 1} 张图片体积超过 ${Math.floor(MAX_IMAGE_BYTES / 1024)} KB 限制` }
  }

  return { bytes }
}

function parseAndValidateImages(rawImages) {
  if (rawImages === undefined || rawImages === null) {
    return { images: [] }
  }
  if (!Array.isArray(rawImages)) {
    return { error: 'images 参数必须为图片数组' }
  }
  if (rawImages.length > MAX_IMAGES) {
    return { error: `最多只能上传 ${MAX_IMAGES} 张截图` }
  }

  const parsed = []
  for (let i = 0; i < rawImages.length; i += 1) {
    const item = rawImages[i]
    if (!item || typeof item !== 'object' || Array.isArray(item)) {
      return { error: `第 ${i + 1} 张图片参数无效` }
    }

    const decoded = decodeBase64ToBytes(item.dataBase64, i)
    if (decoded.error) {
      return { error: decoded.error }
    }

    const format = detectImageFormat(decoded.bytes)
    if (!format) {
      return { error: `第 ${i + 1} 张图片格式不支持，仅允许 JPG、PNG 或 WebP 图片` }
    }

    parsed.push({
      bytes: decoded.bytes,
      ext: format.ext,
      contentType: format.contentType,
    })
  }

  return { images: parsed }
}

function getPreferredImageStorage(env) {
  if (env && env.FEEDBACK_IMAGES_R2 && typeof env.FEEDBACK_IMAGES_R2.put === 'function') {
    return 'r2'
  }
  if (env && env.FEEDBACK_IMAGES_KV && typeof env.FEEDBACK_IMAGES_KV.put === 'function') {
    return 'kv'
  }
  return 'none'
}

async function storeImage(image, storage, env) {
  const filename = `${crypto.randomUUID()}.${image.ext}`

  if (storage === 'r2') {
    await env.FEEDBACK_IMAGES_R2.put(filename, image.bytes, {
      httpMetadata: {
        contentType: image.contentType,
      },
    })
    return { filename, storage: 'r2' }
  }

  if (storage === 'kv') {
    await env.FEEDBACK_IMAGES_KV.put(filename, image.bytes, {
      expirationTtl: IMAGE_KV_TTL_SEC,
      metadata: {
        contentType: image.contentType,
      },
    })
    return { filename, storage: 'kv' }
  }

  throw new Error('Image storage is not configured')
}

async function cleanupUploadedImages(uploadedImages, env) {
  if (!Array.isArray(uploadedImages) || uploadedImages.length === 0) return

  await Promise.allSettled(
    uploadedImages.map(async (item) => {
      if (item.storage === 'r2' && env && env.FEEDBACK_IMAGES_R2 && typeof env.FEEDBACK_IMAGES_R2.delete === 'function') {
        await env.FEEDBACK_IMAGES_R2.delete(item.filename)
      } else if (
        item.storage === 'kv' &&
        env &&
        env.FEEDBACK_IMAGES_KV &&
        typeof env.FEEDBACK_IMAGES_KV.delete === 'function'
      ) {
        await env.FEEDBACK_IMAGES_KV.delete(item.filename)
      }
    }),
  )
}

function normalizeImageContentType(rawType, fallback) {
  if (typeof rawType === 'string' && ALLOWED_IMAGE_CONTENT_TYPES.has(rawType.toLowerCase())) {
    return rawType.toLowerCase()
  }
  return fallback
}

async function readImageFromStorage(filename, fallbackContentType, env) {
  if (env && env.FEEDBACK_IMAGES_R2 && typeof env.FEEDBACK_IMAGES_R2.get === 'function') {
    const obj = await env.FEEDBACK_IMAGES_R2.get(filename)
    if (obj) {
      const body =
        obj.body !== undefined && obj.body !== null
          ? obj.body
          : typeof obj.arrayBuffer === 'function'
            ? await obj.arrayBuffer()
            : null
      if (body !== null) {
        const rawType = obj.httpMetadata && obj.httpMetadata.contentType
        return {
          body,
          contentType: normalizeImageContentType(rawType, fallbackContentType),
        }
      }
    }
  }

  if (env && env.FEEDBACK_IMAGES_KV) {
    if (typeof env.FEEDBACK_IMAGES_KV.getWithMetadata === 'function') {
      const result = await env.FEEDBACK_IMAGES_KV.getWithMetadata(filename, 'arrayBuffer')
      if (result && result.value !== null && result.value !== undefined) {
        const rawType = result.metadata && result.metadata.contentType
        return {
          body: result.value,
          contentType: normalizeImageContentType(rawType, fallbackContentType),
        }
      }
    } else if (typeof env.FEEDBACK_IMAGES_KV.get === 'function') {
      const value = await env.FEEDBACK_IMAGES_KV.get(filename, 'arrayBuffer')
      if (value !== null && value !== undefined) {
        return {
          body: value,
          contentType: fallbackContentType,
        }
      }
    }
  }

  return null
}

function getRawPathname(rawUrl) {
  const schemeIdx = rawUrl.indexOf('://')
  const pathStart = schemeIdx >= 0 ? rawUrl.indexOf('/', schemeIdx + 3) : rawUrl.indexOf('/')
  if (pathStart < 0) return '/'
  const queryIdx = rawUrl.indexOf('?', pathStart)
  const hashIdx = rawUrl.indexOf('#', pathStart)
  let end = rawUrl.length
  if (queryIdx >= 0 && queryIdx < end) end = queryIdx
  if (hashIdx >= 0 && hashIdx < end) end = hashIdx
  return rawUrl.slice(pathStart, end)
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

export function buildIssuePayload(input, imageUrls = []) {
  const kindKey = Object.prototype.hasOwnProperty.call(KIND_META, input.kind) ? input.kind : 'bug'
  const kindMeta = KIND_META[kindKey]
  const clientKey = Object.prototype.hasOwnProperty.call(CLIENT_LABELS, input.client) ? input.client : 'web'
  const clientLabel = CLIENT_LABELS[clientKey]

  const content = sanitizeSensitiveText(input.content).trim()
  const title = deriveTitle(input.title || '', content, kindMeta)
  const version = sanitizeSensitiveText(input.version || '').trim().slice(0, 40) || '未提供'
  // 隐私加固：彻底忽略/丢弃旧版客户端传入的 input.contact，绝不写入公开 GitHub Issue
  const includeEnv = input.includeEnv !== false
  const envInfo = includeEnv ? sanitizeSensitiveText(input.environment || '').trim().slice(0, MAX_ENV_LEN) : ''
  const diagnosticLog = formatDiagnosticLogForIssue(input.diagnosticLog)

  const sections = [
    `> 来源：应用内直连反馈（\`${clientLabel}\` · ${kindMeta.display}）`,
    '',
    '### 反馈内容',
    content,
  ]

  if (Array.isArray(imageUrls) && imageUrls.length > 0) {
    sections.push(
      '',
      '### 附图',
      ...imageUrls.map((imageUrl, index) => `![反馈截图 ${index + 1}](${imageUrl})`),
    )
  }

  sections.push(
    '',
    '### 运行环境（已脱敏）',
    `- 客户端：${clientLabel}`,
    `- 版本：\`${version}\``,
  )

  if (envInfo) {
    sections.push('', '```text', envInfo, '```')
  }

  if (diagnosticLog) {
    sections.push(
      '',
      '<details>',
      '<summary>展开脱敏诊断日志（供排查定位）</summary>',
      '',
      '```text',
      diagnosticLog,
      '```',
      '',
      '</details>',
    )
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
        imageUploadSupported: Boolean(env && (env.FEEDBACK_IMAGES_R2 || env.FEEDBACK_IMAGES_KV)),
        imageStorage: env && env.FEEDBACK_IMAGES_R2 ? 'r2' : env && env.FEEDBACK_IMAGES_KV ? 'kv' : 'none',
        diagnosticLogSupported: true,
        contactPubliclyDropped: true,
      })
    }

    if (request.method === 'GET') {
      const rawPathname = getRawPathname(request.url)
      const imageMatch = rawPathname === url.pathname ? IMAGE_PATH_REGEX.exec(url.pathname) : null
      if (imageMatch) {
        const filename = imageMatch[1]
        const ext = imageMatch[2].toLowerCase()
        const fallbackContentType = EXT_TO_CONTENT_TYPE[ext] || 'application/octet-stream'
        const found = await readImageFromStorage(filename, fallbackContentType, env)
        if (!found) {
          return jsonResponse({ ok: false, error: '图片不存在或已过期' }, 404)
        }
        return new Response(found.body, {
          status: 200,
          headers: {
            'Content-Type': found.contentType,
            'X-Content-Type-Options': 'nosniff',
            'Content-Security-Policy': "default-src 'none'; sandbox",
            'Cache-Control': 'public, max-age=31536000, immutable',
            'Cross-Origin-Resource-Policy': 'cross-origin',
            ...corsHeaders(),
          },
        })
      }
    }

    if (request.method !== 'POST' || (url.pathname !== '/' && url.pathname !== '/v1/feedback')) {
      return jsonResponse({ ok: false, error: '接口路径不存在' }, 404)
    }

    if (!env || !env.GITHUB_TOKEN) {
      return jsonResponse({ ok: false, error: '反馈中转服务尚未配置 GITHUB_TOKEN' }, 503)
    }

    const contentLength = Number.parseInt(request.headers.get('content-length') || '0', 10)
    if (Number.isFinite(contentLength) && contentLength > MAX_BODY_BYTES) {
      return jsonResponse({ ok: false, error: '提交内容体积过大' }, 413)
    }

    let rawText
    if (typeof request.arrayBuffer === 'function') {
      const rawBytes = new Uint8Array(await request.arrayBuffer())
      if (rawBytes.byteLength > MAX_BODY_BYTES) {
        return jsonResponse({ ok: false, error: '提交内容体积过大' }, 413)
      }
      rawText = new TextDecoder().decode(rawBytes)
    } else {
      rawText = await request.text()
      if (new TextEncoder().encode(rawText).byteLength > MAX_BODY_BYTES) {
        return jsonResponse({ ok: false, error: '提交内容体积过大' }, 413)
      }
    }

    let body
    try {
      body = JSON.parse(rawText)
    } catch {
      return jsonResponse({ ok: false, error: '请求体 JSON 格式不正确' }, 400)
    }

    if (!body || typeof body !== 'object' || Array.isArray(body)) {
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

    const diagnosticLogValidation = parseAndValidateDiagnosticLog(body.diagnosticLog)
    if (diagnosticLogValidation.error) {
      return jsonResponse({ ok: false, error: diagnosticLogValidation.error }, 400)
    }
    const sanitizedDiagnosticLog = formatDiagnosticLogForIssue(diagnosticLogValidation.diagnosticLog)

    const imageValidation = parseAndValidateImages(body.images)
    if (imageValidation.error) {
      return jsonResponse({ ok: false, error: imageValidation.error }, 400)
    }
    const parsedImages = imageValidation.images

    const storage = parsedImages.length > 0 ? getPreferredImageStorage(env) : 'none'
    if (parsedImages.length > 0 && storage === 'none') {
      return jsonResponse({ ok: false, error: IMAGE_STORAGE_UNAVAILABLE_ERROR }, 503)
    }

    const clientIp =
      request.headers.get('CF-Connecting-IP') ||
      (request.headers.get('X-Forwarded-For') || '').split(',')[0].trim() ||
      'unknown'

    const allowed = await checkRateLimit(clientIp, env)
    if (!allowed) {
      return jsonResponse({ ok: false, error: '提交过于频繁，请稍后再试' }, 429)
    }

    const uploadedImages = []
    if (parsedImages.length > 0) {
      try {
        for (const image of parsedImages) {
          const stored = await storeImage(image, storage, env)
          uploadedImages.push(stored)
        }
      } catch {
        await cleanupUploadedImages(uploadedImages, env)
        return jsonResponse({ ok: false, error: '图片上传失败，请稍后重试' }, 502)
      }
    }

    const configuredPublicBase =
      env && typeof env.PUBLIC_BASE_URL === 'string' ? env.PUBLIC_BASE_URL.trim() : ''
    const publicBaseUrl = (configuredPublicBase || url.origin).replace(/\/+$/, '')
    const imageUrls = uploadedImages.map((item) => `${publicBaseUrl}/v1/images/${item.filename}`)

    const repo = (env.GITHUB_REPO || DEFAULT_REPO).trim()
    const issuePayload = buildIssuePayload(body, imageUrls)

    let ghResp
    try {
      ghResp = await fetch(`https://api.github.com/repos/${repo}/issues`, {
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
    } catch {
      await cleanupUploadedImages(uploadedImages, env)
      return jsonResponse(
        { ok: false, error: '反馈提交暂时不可用（上游网络异常），请稍后重试' },
        502,
      )
    }

    if (!ghResp.ok) {
      await cleanupUploadedImages(uploadedImages, env)
      return jsonResponse(
        { ok: false, error: `反馈提交暂时不可用（上游 HTTP ${ghResp.status}），请稍后重试` },
        502,
      )
    }

    const created = await ghResp.json()
    const imageCount = uploadedImages.length
    const imageSummary = imageCount > 0 ? `，含 ${imageCount} 张截图` : ''
    return jsonResponse(
      {
        ok: true,
        issueNumber: created.number ?? null,
        issueUrl: created.html_url ?? null,
        imageCount,
        hasDiagnosticLog: Boolean(sanitizedDiagnosticLog),
        message: created.number
          ? `反馈已提交（#${created.number}${imageSummary}），感谢你的帮助！`
          : imageCount > 0
            ? `反馈已提交成功（含 ${imageCount} 张截图），感谢你的帮助！`
            : '反馈已提交成功，感谢你的帮助！',
      },
      201,
    )
  },
}
