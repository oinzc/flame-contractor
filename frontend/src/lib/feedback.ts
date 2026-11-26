export type FeedbackKind = 'bug' | 'feature' | 'question'

export interface FeedbackKindMeta {
  kind: FeedbackKind
  label: string
  placeholder: string
}

export const FEEDBACK_KIND_OPTIONS: ReadonlyArray<FeedbackKindMeta> = [
  {
    kind: 'bug',
    label: 'Bug 反馈',
    placeholder: '描述你遇到的问题、触发时机与看到的提示信息（至少 5 个字）…',
  },
  {
    kind: 'feature',
    label: '功能建议',
    placeholder: '描述希望新增的功能或希望优化的使用场景（至少 5 个字）…',
  },
  {
    kind: 'question',
    label: '使用疑问',
    placeholder: '写下在使用、配置或部署过程中遇到的疑问（至少 5 个字）…',
  },
]

export const ALLOWED_FEEDBACK_IMAGE_MIME_TYPES = [
  'image/jpeg',
  'image/png',
  'image/webp',
] as const

export type FeedbackImageMimeType = (typeof ALLOWED_FEEDBACK_IMAGE_MIME_TYPES)[number]

export interface FeedbackImageAttachment {
  name: string
  mimeType: FeedbackImageMimeType
  dataBase64: string
}

export const FEEDBACK_LIMITS = {
  minContent: 5,
  maxContent: 2000,
  maxTitle: 100,
  maxContact: 100,
  maxDiagnosticLog: 16_000,
  maxImages: 3,
  maxImageBytes: 800 * 1024,
  maxImageDimension: 1600,
  submitTimeoutMs: 30_000,
} as const

export const FEEDBACK_DRAFT_STORAGE_KEY = 'flame_feedback_draft_v1'

export interface PersistedFeedbackDraft {
  kind: FeedbackKind
  title: string
  content: string
  includeEnv: boolean
  includeDiagnosticLog: boolean
  externalDiagnosticLog: string
}

export const DEFAULT_PERSISTED_FEEDBACK_DRAFT: Readonly<PersistedFeedbackDraft> = {
  kind: 'bug',
  title: '',
  content: '',
  includeEnv: true,
  includeDiagnosticLog: false,
  externalDiagnosticLog: '',
}

export interface FeedbackDraftStorageLike {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem(key: string): void
}

export interface FeedbackDraft {
  kind: FeedbackKind
  title: string
  content: string
  contact: string
  includeEnv: boolean
  includeDiagnosticLog?: boolean
  diagnosticLog?: string
  images?: FeedbackImageAttachment[]
}

export interface FeedbackSubmitPayload extends FeedbackDraft {
  client: 'desktop' | 'web'
  version: string
  environment: string
}

export type FeedbackSubmitOutcome =
  | {
      ok: true
      issueNumber: number | null
      issueUrl: string | null
      imageCount: number
      hasDiagnosticLog: boolean
      message: string
    }
  | {
      ok: false
      error: string
      retryable: boolean
    }

export interface WebDiagnosticLogOptions {
  version: string
  isDesktop: boolean
  browserRuntimeLabel?: string | null
  viewport?: { width: number; height: number; devicePixelRatio?: number } | null
  userAgent?: string | null
  pathname?: string | null
  platform?: string | null
  language?: string | null
  online?: boolean | null
  theme?: string | null
  timestamp?: Date | string | number
}

const BASE64_PATTERN = /^[A-Za-z0-9+/]+={0,2}$/

function isFeedbackKind(value: unknown): value is FeedbackKind {
  return value === 'bug' || value === 'feature' || value === 'question'
}

function resolveFeedbackDraftStorage(
  storage?: FeedbackDraftStorageLike | null,
): FeedbackDraftStorageLike | null {
  if (storage !== undefined) {
    return storage
  }
  try {
    if (typeof globalThis === 'undefined' || !('localStorage' in globalThis)) {
      return null
    }
    const candidate = globalThis.localStorage
    if (
      candidate &&
      typeof candidate.getItem === 'function' &&
      typeof candidate.setItem === 'function' &&
      typeof candidate.removeItem === 'function'
    ) {
      return candidate
    }
    return null
  } catch {
    return null
  }
}

export function createDefaultPersistedFeedbackDraft(): PersistedFeedbackDraft {
  return { ...DEFAULT_PERSISTED_FEEDBACK_DRAFT }
}

export function normalizePersistedFeedbackDraft(raw: unknown): PersistedFeedbackDraft {
  const defaults = createDefaultPersistedFeedbackDraft()
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
    return defaults
  }
  const obj = raw as Record<string, unknown>
  return {
    kind: isFeedbackKind(obj.kind) ? obj.kind : defaults.kind,
    title: typeof obj.title === 'string' ? obj.title.slice(0, FEEDBACK_LIMITS.maxTitle) : defaults.title,
    content:
      typeof obj.content === 'string' ? obj.content.slice(0, FEEDBACK_LIMITS.maxContent) : defaults.content,
    includeEnv: typeof obj.includeEnv === 'boolean' ? obj.includeEnv : defaults.includeEnv,
    includeDiagnosticLog:
      typeof obj.includeDiagnosticLog === 'boolean'
        ? obj.includeDiagnosticLog
        : defaults.includeDiagnosticLog,
    externalDiagnosticLog:
      typeof obj.externalDiagnosticLog === 'string'
        ? obj.externalDiagnosticLog
        : defaults.externalDiagnosticLog,
  }
}

export function arePersistedFeedbackDraftsEqual(
  a: PersistedFeedbackDraft,
  b: PersistedFeedbackDraft,
): boolean {
  return (
    a.kind === b.kind &&
    a.title === b.title &&
    a.content === b.content &&
    a.includeEnv === b.includeEnv &&
    a.includeDiagnosticLog === b.includeDiagnosticLog &&
    a.externalDiagnosticLog === b.externalDiagnosticLog
  )
}

export function hasPersistedFeedbackDraft(draft: PersistedFeedbackDraft): boolean {
  return !arePersistedFeedbackDraftsEqual(draft, DEFAULT_PERSISTED_FEEDBACK_DRAFT)
}

export function loadPersistedFeedbackDraft(
  storage?: FeedbackDraftStorageLike | null,
): PersistedFeedbackDraft {
  const resolvedStorage = resolveFeedbackDraftStorage(storage)
  if (!resolvedStorage) {
    return createDefaultPersistedFeedbackDraft()
  }
  try {
    const rawText = resolvedStorage.getItem(FEEDBACK_DRAFT_STORAGE_KEY)
    if (!rawText) {
      return createDefaultPersistedFeedbackDraft()
    }
    const parsed: unknown = JSON.parse(rawText)
    return normalizePersistedFeedbackDraft(parsed)
  } catch {
    return createDefaultPersistedFeedbackDraft()
  }
}

export function savePersistedFeedbackDraft(
  draft: PersistedFeedbackDraft,
  storage?: FeedbackDraftStorageLike | null,
): boolean {
  const resolvedStorage = resolveFeedbackDraftStorage(storage)
  if (!resolvedStorage) {
    return false
  }
  try {
    const normalized = normalizePersistedFeedbackDraft(draft)
    resolvedStorage.setItem(FEEDBACK_DRAFT_STORAGE_KEY, JSON.stringify(normalized))
    return true
  } catch {
    return false
  }
}

export function clearPersistedFeedbackDraft(
  storage?: FeedbackDraftStorageLike | null,
): boolean {
  const resolvedStorage = resolveFeedbackDraftStorage(storage)
  if (!resolvedStorage) {
    return false
  }
  try {
    resolvedStorage.removeItem(FEEDBACK_DRAFT_STORAGE_KEY)
    return true
  } catch {
    return false
  }
}

export function isAllowedFeedbackImageMimeType(value: string): value is FeedbackImageMimeType {
  return (ALLOWED_FEEDBACK_IMAGE_MIME_TYPES as readonly string[]).includes(value)
}

export function estimateBase64DecodedBytes(rawBase64: string): number {
  const clean = rawBase64.trim()
  if (!clean) return 0
  let padding = 0
  if (clean.endsWith('==')) padding = 2
  else if (clean.endsWith('=')) padding = 1
  return Math.max(0, Math.floor((clean.length * 3) / 4) - padding)
}

export function validateFeedbackImages(
  images?: ReadonlyArray<{ name?: unknown; mimeType?: unknown; dataBase64?: unknown }>,
): string | null {
  if (!images || images.length === 0) return null
  if (images.length > FEEDBACK_LIMITS.maxImages) {
    return `最多只能附带 ${FEEDBACK_LIMITS.maxImages} 张截图`
  }

  for (let i = 0; i < images.length; i += 1) {
    const item = images[i]
    if (!item || typeof item !== 'object') {
      return '存在无效的截图附件，请移除后重试'
    }
    if (typeof item.mimeType !== 'string' || !isAllowedFeedbackImageMimeType(item.mimeType)) {
      return '仅支持 JPG、PNG 或 WebP 格式的图片'
    }
    const cleanBase64 = typeof item.dataBase64 === 'string' ? item.dataBase64.trim() : ''
    if (!cleanBase64) {
      return '截图数据不能为空，请移除后重试'
    }
    if (
      cleanBase64.startsWith('data:') ||
      cleanBase64.length % 4 !== 0 ||
      !BASE64_PATTERN.test(cleanBase64)
    ) {
      return '截图编码格式无效，请重新选择图片'
    }
    const decodedBytes = estimateBase64DecodedBytes(cleanBase64)
    if (decodedBytes <= 0) {
      return '截图数据不能为空，请移除后重试'
    }
    if (decodedBytes > FEEDBACK_LIMITS.maxImageBytes) {
      return `单张截图不能超过 ${Math.round(FEEDBACK_LIMITS.maxImageBytes / 1024)} KB，请压缩或移除后重试`
    }
  }

  return null
}

export function normalizeFeedbackEndpoint(raw?: string | null): string | null {
  const clean = (raw ?? '').trim()
  if (!clean) return null
  try {
    const parsed = new URL(clean)
    if (parsed.protocol !== 'https:' && parsed.protocol !== 'http:') return null
    return parsed.toString()
  } catch {
    return null
  }
}

export function getConfiguredFeedbackEndpoint(): string | null {
  const env = (import.meta as unknown as { env?: Record<string, string | undefined> }).env
  return normalizeFeedbackEndpoint(env?.VITE_FEEDBACK_ENDPOINT)
}

export function validateDiagnosticLog(raw?: string | null): string | null {
  if (raw === undefined || raw === null) return null
  if (typeof raw !== 'string') {
    return '诊断日志格式无效，请输入纯文本内容'
  }
  const clean = raw.trim()
  if (clean.length > FEEDBACK_LIMITS.maxDiagnosticLog) {
    return `诊断日志请控制在 ${FEEDBACK_LIMITS.maxDiagnosticLog} 字以内`
  }
  return null
}

function resolveEffectiveDiagnosticLog(
  payload: Pick<FeedbackDraft, 'includeDiagnosticLog' | 'diagnosticLog'>,
): string {
  if (payload.includeDiagnosticLog === false) return ''
  if (typeof payload.diagnosticLog !== 'string') return ''
  return payload.diagnosticLog.trim()
}

export function validateFeedbackDraft(
  draft: Pick<FeedbackDraft, 'title' | 'content' | 'contact'> & {
    images?: ReadonlyArray<{ name?: unknown; mimeType?: unknown; dataBase64?: unknown }>
    diagnosticLog?: string | null
    includeDiagnosticLog?: boolean
  },
): string | null {
  const cleanContent = draft.content.trim()
  if (cleanContent.length < FEEDBACK_LIMITS.minContent) {
    return `请至少输入 ${FEEDBACK_LIMITS.minContent} 个字的反馈内容`
  }
  if (cleanContent.length > FEEDBACK_LIMITS.maxContent) {
    return `反馈内容请控制在 ${FEEDBACK_LIMITS.maxContent} 字以内`
  }
  if (draft.title.trim().length > FEEDBACK_LIMITS.maxTitle) {
    return `标题请控制在 ${FEEDBACK_LIMITS.maxTitle} 字以内`
  }
  if (draft.contact.trim().length > FEEDBACK_LIMITS.maxContact) {
    return `联系方式请控制在 ${FEEDBACK_LIMITS.maxContact} 字以内`
  }
  if (draft.includeDiagnosticLog !== false) {
    const diagnosticLogError = validateDiagnosticLog(draft.diagnosticLog)
    if (diagnosticLogError) return diagnosticLogError
  }
  return validateFeedbackImages(draft.images)
}

function sanitizeDiagnosticField(raw: string): string {
  if (!raw) return ''
  return raw
    .replace(/(https?:\/\/api\.day\.app\/)([^/\s"'`<>]+)/gi, '$1[REDACTED_BARK_KEY]')
    .replace(
      /\b(sessionid|sessionid_ss|sid_tt|sid_guard|passport_csrf_token|ttwid|cookie|token|access_token|refresh_token|device_key|secret|password)\s*=\s*[^;\s&"'`]+/gi,
      '$1=[REDACTED]',
    )
    .replace(/\b(Authorization\s*:\s*Bearer\s+)[^\s"'`]+/gi, '$1[REDACTED_TOKEN]')
    .replace(/\b(ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\b/g, '[REDACTED_GH_TOKEN]')
    .replace(/\bMS4wLjAB[A-Za-z0-9_-]{8,}\b/g, '[REDACTED_UID]')
    .replace(/`{3,}/g, '` ` `')
}

function sanitizeDiagnosticPathname(raw?: string | null): string {
  const source =
    raw !== undefined
      ? (raw ?? '')
      : typeof window !== 'undefined' && window.location
        ? window.location.pathname
        : ''
  let path = source.trim()
  if (!path) return '/'
  if (/^[a-zA-Z][a-zA-Z0-9+.-]*:\/\//.test(path)) {
    try {
      path = new URL(path).pathname || '/'
    } catch {
      path = '/'
    }
  } else {
    path = path.split(/[?#]/, 1)[0] || '/'
  }
  if (!path.startsWith('/')) {
    path = `/${path}`
  }
  return sanitizeDiagnosticField(path).replace(/[\r\n]+/g, '').slice(0, 120)
}

function summarizeUserAgent(raw?: string | null): string {
  const source =
    raw !== undefined
      ? (raw ?? '')
      : typeof navigator !== 'undefined' && typeof navigator.userAgent === 'string'
        ? navigator.userAgent
        : ''
  const clean = sanitizeDiagnosticField(source).replace(/\s+/g, ' ').trim()
  if (!clean) return '未知'

  const edgeMatch = clean.match(/\bEdg(?:A|iOS)?\/[\d.]+/)
  const operaMatch = clean.match(/\bOPR\/[\d.]+/)
  const firefoxMatch = clean.match(/\bFirefox\/[\d.]+/)
  const chromeMatch = clean.match(/\b(?:Chrome|CriOS)\/[\d.]+/)
  const safariVersionMatch = clean.match(/\bVersion\/([\d.]+).*\bSafari\/[\d.]+/)
  const safariMatch = clean.match(/\bSafari\/[\d.]+/)

  const browserToken =
    edgeMatch?.[0] ??
    operaMatch?.[0] ??
    firefoxMatch?.[0] ??
    chromeMatch?.[0] ??
    (safariVersionMatch ? `Safari/${safariVersionMatch[1]}` : safariMatch?.[0] ?? null)

  const parenMatch = clean.match(/\(([^)]+)\)/)
  let osToken: string | null = null
  if (parenMatch) {
    const inner = parenMatch[1]
    const winMatch = inner.match(/Windows NT [\d.]+(?:;\s*(?:Win64;\s*x64|WOW64|ARM64))?/i)
    const macMatch = inner.match(/Mac OS X [\d_.]+/i)
    const androidMatch = inner.match(/Android [\d.]+/i)
    const iosMatch = inner.match(/CPU (?:iPhone )?OS [\d_]+/i)
    const linuxMatch = inner.match(/Linux(?:\s+[a-z0-9_]+)?/i)
    const crosMatch = inner.match(/CrOS\s+[a-z0-9_]+/i)
    osToken =
      winMatch?.[0] ??
      macMatch?.[0] ??
      androidMatch?.[0] ??
      iosMatch?.[0] ??
      crosMatch?.[0] ??
      linuxMatch?.[0] ??
      null
  }

  if (browserToken) {
    return osToken ? `${browserToken} (${osToken})` : browserToken
  }
  return clean.slice(0, 96)
}

function formatDiagnosticViewport(
  viewport?: { width: number; height: number; devicePixelRatio?: number } | null,
): string {
  const resolved =
    viewport !== undefined
      ? viewport
      : typeof window !== 'undefined' &&
          typeof window.innerWidth === 'number' &&
          typeof window.innerHeight === 'number'
        ? {
            width: window.innerWidth,
            height: window.innerHeight,
            devicePixelRatio: window.devicePixelRatio,
          }
        : null

  if (
    !resolved ||
    !Number.isFinite(resolved.width) ||
    !Number.isFinite(resolved.height) ||
    resolved.width <= 0 ||
    resolved.height <= 0
  ) {
    return '未知'
  }
  const dpr =
    typeof resolved.devicePixelRatio === 'number' &&
    Number.isFinite(resolved.devicePixelRatio) &&
    resolved.devicePixelRatio > 0
      ? ` (DPR ${Number(resolved.devicePixelRatio.toFixed(2))})`
      : ''
  return `${Math.round(resolved.width)}x${Math.round(resolved.height)}${dpr}`
}

function formatDiagnosticTimestamp(timestamp?: Date | string | number): string {
  if (timestamp !== undefined) {
    const parsed = timestamp instanceof Date ? timestamp : new Date(timestamp)
    if (Number.isFinite(parsed.getTime())) {
      return parsed.toISOString()
    }
  }
  return new Date().toISOString()
}

export function buildWebDiagnosticLog(options: WebDiagnosticLogOptions): string {
  const version =
    sanitizeDiagnosticField(options.version || '')
      .replace(/[\r\n]+/g, ' ')
      .trim() || '未知'
  const mode = options.isDesktop ? 'Windows 桌面端' : 'Web / Docker 控制台'
  const browserRuntime = options.browserRuntimeLabel
    ? sanitizeDiagnosticField(options.browserRuntimeLabel)
        .replace(/[\r\n]+/g, ' ')
        .trim()
    : ''
  const viewportText = formatDiagnosticViewport(options.viewport)
  const userAgentDigest = summarizeUserAgent(options.userAgent)
  const pathname = sanitizeDiagnosticPathname(options.pathname)
  const timestamp = formatDiagnosticTimestamp(options.timestamp)

  const resolvedPlatform =
    options.platform !== undefined
      ? (options.platform ?? '').trim()
      : typeof navigator !== 'undefined' && navigator.platform
        ? navigator.platform.trim()
        : ''
  const resolvedLanguage =
    options.language !== undefined
      ? (options.language ?? '').trim()
      : typeof navigator !== 'undefined' && navigator.language
        ? navigator.language.trim()
        : ''
  const resolvedOnline =
    options.online !== undefined
      ? options.online
      : typeof navigator !== 'undefined' && typeof navigator.onLine === 'boolean'
        ? navigator.onLine
        : null
  const resolvedTheme =
    options.theme !== undefined
      ? (options.theme ?? '').trim()
      : typeof document !== 'undefined'
        ? (document.documentElement?.dataset?.theme ?? 'light').trim()
        : ''

  const lines = [
    '=== 控制台脱敏诊断摘要 ===',
    `时间：${timestamp}`,
    `模式：${mode}`,
    `版本：${version}`,
  ]
  if (browserRuntime) {
    lines.push(`浏览器内核：${browserRuntime}`)
  }
  lines.push(
    `页面路径：${pathname}`,
    `视口尺寸：${viewportText}`,
    `用户代理简摘：${userAgentDigest}`,
  )

  const runtimeExtras: string[] = []
  if (resolvedPlatform) {
    runtimeExtras.push(`平台 ${sanitizeDiagnosticField(resolvedPlatform).replace(/[\r\n]+/g, ' ')}`)
  }
  if (resolvedLanguage) {
    runtimeExtras.push(`语言 ${sanitizeDiagnosticField(resolvedLanguage).replace(/[\r\n]+/g, ' ')}`)
  }
  if (resolvedTheme) {
    runtimeExtras.push(`主题 ${sanitizeDiagnosticField(resolvedTheme).replace(/[\r\n]+/g, ' ')}`)
  }
  if (resolvedOnline !== null) {
    runtimeExtras.push(resolvedOnline ? '网络在线' : '网络离线')
  }
  if (runtimeExtras.length > 0) {
    lines.push(`运行状态：${runtimeExtras.join(' · ')}`)
  }
  lines.push('说明：已脱敏，不含账号、好友昵称、Cookie 或密钥信息')

  return lines.join('\n').slice(0, FEEDBACK_LIMITS.maxDiagnosticLog)
}

export function buildWebEnvSummary(options: {
  version: string
  isDesktop: boolean
  browserRuntimeLabel?: string | null
}): string {
  const lines = [
    `客户端：${options.isDesktop ? 'Windows 桌面端' : 'Web / Docker 控制台'}`,
    `版本：${options.version}`,
  ]
  if (options.browserRuntimeLabel) {
    lines.push(`浏览器内核：${options.browserRuntimeLabel}`)
  }
  if (typeof navigator !== 'undefined' && navigator.platform) {
    lines.push(`平台：${navigator.platform}`)
  }
  lines.push('（已脱敏，不含账号、好友与密钥信息）')
  return lines.join('\n')
}

export function buildFeedbackFallbackText(payload: FeedbackSubmitPayload): string {
  const kindLabel =
    FEEDBACK_KIND_OPTIONS.find((item) => item.kind === payload.kind)?.label ?? '问题反馈'
  const lines = [
    `【${kindLabel}】${payload.title.trim() || '应用内反馈'}`,
    '',
    payload.content.trim(),
  ]
  const imageCount = payload.images?.length ?? 0
  if (imageCount > 0) {
    lines.push(
      '',
      `（本地已附带 ${imageCount} 张截图；复制文本不含图片，前往 GitHub 提交时请手动补传截图）`,
    )
  }
  if (payload.includeEnv && payload.environment.trim()) {
    lines.push('', '--- 运行环境（已脱敏）---', payload.environment.trim())
  }
  const effectiveDiagnosticLog = resolveEffectiveDiagnosticLog(payload)
  if (effectiveDiagnosticLog) {
    lines.push('', '--- 脱敏诊断日志 ---', effectiveDiagnosticLog)
  }
  return lines.join('\n')
}

function defaultImageExtension(mimeType: FeedbackImageMimeType): string {
  if (mimeType === 'image/png') return 'png'
  if (mimeType === 'image/webp') return 'webp'
  return 'jpg'
}

export async function submitInAppFeedback(
  endpointRaw: string | null,
  payload: FeedbackSubmitPayload,
  fetchImpl: typeof fetch = fetch,
): Promise<FeedbackSubmitOutcome> {
  const validationError = validateFeedbackDraft(payload)
  if (validationError) {
    return { ok: false, error: validationError, retryable: false }
  }

  const endpoint = normalizeFeedbackEndpoint(endpointRaw)
  if (!endpoint) {
    return {
      ok: false,
      error: '当前构建未绑定直连反馈中转地址，请先点击「复制完整反馈」或前往 GitHub 提交。',
      retryable: false,
    }
  }

  const serializedImages =
    payload.images && payload.images.length > 0
      ? payload.images.map((img, idx) => ({
          name:
            img.name.trim().slice(0, 120) ||
            `screenshot-${idx + 1}.${defaultImageExtension(img.mimeType)}`,
          mimeType: img.mimeType,
          dataBase64: img.dataBase64.trim(),
        }))
      : undefined

  const requestBody: Record<string, unknown> = {
    kind: payload.kind,
    title: payload.title.trim(),
    content: payload.content.trim(),
    contact: '',
    client: payload.client,
    version: payload.version.trim(),
    environment: payload.includeEnv ? payload.environment.trim() : '',
    includeEnv: payload.includeEnv,
    website: '',
  }
  const effectiveDiagnosticLog = resolveEffectiveDiagnosticLog(payload)
  if (effectiveDiagnosticLog) {
    requestBody.diagnosticLog = effectiveDiagnosticLog
  }
  if (serializedImages) {
    requestBody.images = serializedImages
  }

  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), FEEDBACK_LIMITS.submitTimeoutMs)

  try {
    const response = await fetchImpl(endpoint, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
      },
      body: JSON.stringify(requestBody),
      signal: controller.signal,
    })

    let parsed: Record<string, unknown> = {}
    try {
      parsed = (await response.json()) as Record<string, unknown>
    } catch {
      parsed = {}
    }

    if (response.ok) {
      const issueNumber = typeof parsed.issueNumber === 'number' ? parsed.issueNumber : null
      const issueUrl =
        typeof parsed.issueUrl === 'string' && parsed.issueUrl.trim() ? parsed.issueUrl : null
      const imageCount =
        typeof parsed.imageCount === 'number' &&
        Number.isFinite(parsed.imageCount) &&
        parsed.imageCount >= 0
          ? Math.floor(parsed.imageCount)
          : 0
      const hasDiagnosticLog = Boolean(parsed.hasDiagnosticLog)
      const detailParts: string[] = []
      if (imageCount > 0) detailParts.push(`含 ${imageCount} 张截图`)
      if (hasDiagnosticLog) detailParts.push('已附诊断日志')
      const detailSuffix = detailParts.length > 0 ? detailParts.join('、') : ''
      const defaultMessage = issueNumber
        ? detailSuffix
          ? `反馈已提交（#${issueNumber}，${detailSuffix}），感谢你的帮助！`
          : `反馈已提交（#${issueNumber}），感谢你的帮助！`
        : detailSuffix
          ? `反馈已提交成功（${detailSuffix}），感谢你的帮助！`
          : '反馈已提交成功，感谢你的帮助！'
      const message =
        typeof parsed.message === 'string' && parsed.message.trim()
          ? parsed.message
          : defaultMessage
      return { ok: true, issueNumber, issueUrl, imageCount, hasDiagnosticLog, message }
    }

    const serverError =
      typeof parsed.error === 'string' && parsed.error.trim() ? parsed.error : null
    const fallbackError =
      response.status === 429
        ? '提交过于频繁，请稍后再试'
        : response.status === 413
          ? '提交内容或截图体积过大，请减少图片后重试'
          : response.status === 503
            ? '反馈服务暂未就绪，请稍后再试'
            : `提交失败（HTTP ${response.status}），请稍后重试`
    return {
      ok: false,
      error: serverError ?? fallbackError,
      retryable: response.status >= 500 || response.status === 429,
    }
  } catch (err) {
    const aborted = err instanceof Error && err.name === 'AbortError'
    return {
      ok: false,
      error: aborted
        ? '提交超时，请稍后重试（已保留你填写的内容与截图）'
        : '网络连接失败，请稍后重试或复制已填反馈内容',
      retryable: true,
    }
  } finally {
    clearTimeout(timer)
  }
}
