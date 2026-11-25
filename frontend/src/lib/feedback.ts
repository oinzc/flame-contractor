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
  maxImages: 3,
  maxImageBytes: 800 * 1024,
  maxImageDimension: 1600,
  submitTimeoutMs: 30_000,
} as const

export interface FeedbackDraft {
  kind: FeedbackKind
  title: string
  content: string
  contact: string
  includeEnv: boolean
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
      message: string
    }
  | {
      ok: false
      error: string
      retryable: boolean
    }

const BASE64_PATTERN = /^[A-Za-z0-9+/]+={0,2}$/

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

export function validateFeedbackDraft(
  draft: Pick<FeedbackDraft, 'title' | 'content' | 'contact'> & {
    images?: ReadonlyArray<{ name?: unknown; mimeType?: unknown; dataBase64?: unknown }>
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
  return validateFeedbackImages(draft.images)
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
  if (payload.contact.trim()) {
    lines.push('', `联系方式：${payload.contact.trim()}`)
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
    contact: payload.contact.trim(),
    client: payload.client,
    version: payload.version.trim(),
    environment: payload.includeEnv ? payload.environment.trim() : '',
    includeEnv: payload.includeEnv,
    website: '',
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
      const defaultMessage = issueNumber
        ? imageCount > 0
          ? `反馈已提交（#${issueNumber}，含 ${imageCount} 张截图），感谢你的帮助！`
          : `反馈已提交（#${issueNumber}），感谢你的帮助！`
        : imageCount > 0
          ? `反馈已提交成功（含 ${imageCount} 张截图），感谢你的帮助！`
          : '反馈已提交成功，感谢你的帮助！'
      const message =
        typeof parsed.message === 'string' && parsed.message.trim()
          ? parsed.message
          : defaultMessage
      return { ok: true, issueNumber, issueUrl, imageCount, message }
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
