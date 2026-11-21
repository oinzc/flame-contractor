import type {
  Account,
  ContractTarget,
  DrawerState,
  JobStatus,
  ScanChatEntry,
  ScanOfficialState,
  ScanProgress,
  ScanReconciliationCategory,
  ScanReconciliationItem,
  SendAttemptItem,
  SendJob,
  SendPreviewRead,
  WorkspaceView,
} from '../types.ts'
import {
  ATTENTION_ACCOUNT_HINTS,
  ATTENTION_ACCOUNT_STATUSES,
  JOB_STATUS_LABELS,
  JOB_STATUS_LIGHT_TONES,
  type StatusTone,
} from './format.ts'

export type SingleSendGuard = 'ready' | 'blocked_disabled' | 'blocked_review' | 'confirm_repeat'

export interface TargetDaySummary {
  targetId: string
  todayJob: SendJob | null
  todayStatusLabel: string
  todayTone: StatusTone
  todaySucceeded: boolean
  todayFailed: boolean
  hasUnresolvedReview: boolean
  unresolvedReviewJob: SendJob | null
  singleSendGuard: SingleSendGuard
}

export type AccountAttentionItem =
  | {
      id: string
      kind: 'account_status'
      accountStatus: Account['status']
      message: string
    }
  | {
      id: string
      kind: 'job_review'
      job: SendJob
      targetName: string
      businessDate: string
      isHistorical: boolean
      message: string
    }

export interface MergedSendPreviewRow {
  target: ContractTarget
  willSend: boolean
  renderedMessage: string | null
  skipReason: string | null
  tone: StatusTone
}

function sortJobsNewestFirst(jobs: SendJob[]): SendJob[] {
  return [...jobs].sort((a, b) => {
    const ta = Date.parse(a.updatedAt || a.createdAt) || 0
    const tb = Date.parse(b.updatedAt || b.createdAt) || 0
    return tb - ta
  })
}

export function deriveTargetDayMap(
  targets: ContractTarget[],
  accountJobs: SendJob[],
  unresolvedReviewJobs: SendJob[],
  todayBusinessDate: string,
): Map<string, TargetDaySummary> {
  const orderedTodayJobs = sortJobsNewestFirst(
    accountJobs.filter((job) => job.businessDate.slice(0, 10) === todayBusinessDate),
  )
  const orderedReviews = sortJobsNewestFirst(
    unresolvedReviewJobs.filter((job) => job.status === 'unknown_requires_review'),
  )

  const latestTodayByTarget = new Map<string, SendJob>()
  for (const job of orderedTodayJobs) {
    if (!latestTodayByTarget.has(job.targetId)) {
      latestTodayByTarget.set(job.targetId, job)
    }
  }

  const reviewByTarget = new Map<string, SendJob>()
  for (const job of orderedReviews) {
    if (!reviewByTarget.has(job.targetId)) {
      reviewByTarget.set(job.targetId, job)
    }
  }

  const map = new Map<string, TargetDaySummary>()
  for (const target of targets) {
    if (target.deletedAt !== null) continue
    const todayJob = latestTodayByTarget.get(target.id) ?? null
    const reviewJob =
      reviewByTarget.get(target.id) ??
      (todayJob?.status === 'unknown_requires_review' ? todayJob : null)
    const hasUnresolvedReview = reviewJob !== null

    let todayStatusLabel = '今日待发'
    let todayTone: StatusTone = 'neutral'
    const status: JobStatus | null = todayJob?.status ?? null

    if (hasUnresolvedReview) {
      todayStatusLabel =
        reviewJob.businessDate.slice(0, 10) === todayBusinessDate ? '待人工复核' : '历史待复核'
      todayTone = 'warn'
    } else if (!target.enabled) {
      todayStatusLabel = '已暂停'
      todayTone = 'neutral'
    } else if (status) {
      todayStatusLabel = JOB_STATUS_LABELS[status] ?? status
      todayTone = JOB_STATUS_LIGHT_TONES[status] ?? 'neutral'
    }

    const todaySucceeded = status === 'succeeded'
    const todayFailed = status === 'failed_retryable' || status === 'failed_terminal'

    let singleSendGuard: SingleSendGuard = 'ready'
    if (hasUnresolvedReview) {
      singleSendGuard = 'blocked_review'
    } else if (!target.enabled) {
      singleSendGuard = 'blocked_disabled'
    } else if (todaySucceeded) {
      singleSendGuard = 'confirm_repeat'
    }

    map.set(target.id, {
      targetId: target.id,
      todayJob,
      todayStatusLabel,
      todayTone,
      todaySucceeded,
      todayFailed,
      hasUnresolvedReview,
      unresolvedReviewJob: reviewJob,
      singleSendGuard,
    })
  }
  return map
}

export function collectAccountReviewItems(
  account: Account | null,
  targets: ContractTarget[],
  unresolvedReviewJobs: SendJob[],
  todayBusinessDate: string,
): AccountAttentionItem[] {
  if (!account) return []
  const items: AccountAttentionItem[] = []

  if (ATTENTION_ACCOUNT_STATUSES.includes(account.status)) {
    items.push({
      id: `acct-${account.id}-${account.status}`,
      kind: 'account_status',
      accountStatus: account.status,
      message: ATTENTION_ACCOUNT_HINTS[account.status] ?? '账号状态需要人工检查。',
    })
  }

  const targetNames = new Map(targets.map((t) => [t.id, t.nicknameSnapshot]))
  const ordered = sortJobsNewestFirst(
    unresolvedReviewJobs.filter(
      (job) => job.accountId === account.id && job.status === 'unknown_requires_review',
    ),
  )

  for (const job of ordered) {
    const dateStr = job.businessDate.slice(0, 10)
    const isHistorical = dateStr !== todayBusinessDate
    const targetName = targetNames.get(job.targetId) ?? '未知会话'
    items.push({
      id: `review-${job.id}`,
      kind: 'job_review',
      job,
      targetName,
      businessDate: dateStr,
      isHistorical,
      message: `${targetName}（${isHistorical ? `历史 ${dateStr}` : '今日'}）发送结果未确认，系统不会自动重发。`,
    })
  }

  return items
}

export function mergeAccountSendPreview(
  targets: ContractTarget[],
  dayMap: Map<string, TargetDaySummary>,
  preview: SendPreviewRead | null,
  force: boolean,
): MergedSendPreviewRow[] {
  const previewByTarget = new Map(preview?.items.map((item) => [item.targetId, item]) ?? [])
  const activeTargets = targets.filter((t) => t.deletedAt === null)

  return activeTargets.map((target) => {
    const summary = dayMap.get(target.id)
    const pItem = previewByTarget.get(target.id)

    if (!target.enabled) {
      return {
        target,
        willSend: false,
        renderedMessage: null,
        skipReason: '未启用自动续火（跳过）',
        tone: 'neutral',
      }
    }

    if (summary?.hasUnresolvedReview) {
      return {
        target,
        willSend: false,
        renderedMessage: null,
        skipReason: '存在待人工复核任务，受安全保护跳过',
        tone: 'warn',
      }
    }

    if (!force && summary?.todaySucceeded) {
      return {
        target,
        willSend: false,
        renderedMessage: pItem?.message ?? null,
        skipReason: '今日已发送成功（幂等跳过）',
        tone: 'ok',
      }
    }

    if (!pItem) {
      return {
        target,
        willSend: false,
        renderedMessage: null,
        skipReason: target.templateId ? '正在读取预览或未命中条件' : '未绑定消息模板（跳过）',
        tone: 'neutral',
      }
    }

    if (pItem.reason) {
      return {
        target,
        willSend: false,
        renderedMessage: null,
        skipReason: pItem.reason,
        tone: 'neutral',
      }
    }

    return {
      target,
      willSend: Boolean(pItem.message),
      renderedMessage: pItem.message,
      skipReason: pItem.message ? null : '无可发送内容',
      tone: pItem.message ? 'ok' : 'neutral',
    }
  })
}

export function shouldFinalizeRecoveredScan(
  task: ScanProgress,
  syncedIds: ReadonlySet<string>,
): boolean {
  if (task.source === 'automatic') return false
  if (task.status !== 'completed') return false
  return !syncedIds.has(task.taskId)
}

export type SchedulerFormValidation =
  | {
      ok: true
      patch: {
        autoScanIntervalHours: number
        fixedTimeGraceMinutes: number
        scheduledAlwaysSend: boolean
      }
    }
  | { ok: false; error: string }

export function parseSchedulerSettingsForm(
  scanHoursRaw: string,
  graceMinutesRaw: string,
  scheduledAlwaysSend: boolean,
): SchedulerFormValidation {
  const hours = Number.parseInt(scanHoursRaw.trim(), 10)
  if (Number.isNaN(hours) || hours < 1 || hours > 168) {
    return { ok: false, error: '自动扫描间隔须为 1 – 168 小时之间的整数' }
  }
  const grace = Number.parseInt(graceMinutesRaw.trim(), 10)
  if (Number.isNaN(grace) || grace < 0 || grace > 1440) {
    return { ok: false, error: '固定时刻宽限期须为 0 – 1440 分钟之间的整数（0 表示不宽限）' }
  }
  return {
    ok: true,
    patch: {
      autoScanIntervalHours: hours,
      fixedTimeGraceMinutes: grace,
      scheduledAlwaysSend,
    },
  }
}

export function pickDefaultScreenshotAttempt(attempts: SendAttemptItem[]): number | null {
  for (let i = attempts.length - 1; i >= 0; i -= 1) {
    if (attempts[i].screenshotState === 'available') {
      return attempts[i].attemptNo
    }
  }
  for (let i = attempts.length - 1; i >= 0; i -= 1) {
    if (attempts[i].screenshotState === 'expired') {
      return attempts[i].attemptNo
    }
  }
  return null
}

export function resolveDrawerOnViewChange(
  prevView: WorkspaceView,
  nextView: WorkspaceView,
  current: DrawerState,
): DrawerState {
  if (prevView === nextView) return current
  if (nextView === 'templates' && current.kind === 'template-editor') return current
  if (
    nextView === 'targets' &&
    current.kind !== 'template-editor' &&
    current.kind !== 'job-detail'
  ) {
    return current
  }
  return { kind: 'closed' }
}

export function shouldReloadOpenJobDetail(
  loaded: Pick<SendJob, 'id' | 'status' | 'updatedAt'> | null,
  externalSnapshot: Pick<SendJob, 'id' | 'status' | 'updatedAt'> | null,
): boolean {
  if (!loaded || !externalSnapshot) return false
  if (loaded.id !== externalSnapshot.id) return false
  return (
    loaded.status !== externalSnapshot.status ||
    loaded.updatedAt !== externalSnapshot.updatedAt
  )
}

export interface BarkCardState {
  configured: boolean
  badgeLabel: '已启用' | '未配置'
  sourceLabel: string | null
  maskedUrl: string | null
  hint: string
  canSendTest: boolean
  canClearOverride: boolean
}

export function describeBarkCardState(
  status:
    | {
        barkConfigured?: boolean
        barkMaskedUrl?: string | null
        barkSource?: 'settings' | 'env' | 'none'
      }
    | null
    | undefined,
): BarkCardState {
  const configured = Boolean(status?.barkConfigured)
  const source = status?.barkSource ?? (configured ? 'env' : 'none')
  const maskedUrl = status?.barkMaskedUrl ?? null
  if (configured) {
    const sourceLabel = source === 'settings' ? '页面配置' : '环境变量 FLAME_BARK_URL'
    return {
      configured: true,
      badgeLabel: '已启用',
      sourceLabel,
      maskedUrl,
      hint:
        source === 'settings'
          ? '登录失效、待复核与好友火花告急将推送至手机（私密 Key 已脱敏保存，不进入 JSON 备份）'
          : '登录失效、待复核与好友火花告急将推送至手机（当前读取环境变量 FLAME_BARK_URL，可在下方直接覆盖）',
      canSendTest: true,
      canClearOverride: source === 'settings',
    }
  }
  return {
    configured: false,
    badgeLabel: '未配置',
    sourceLabel: null,
    maskedUrl: null,
    hint: '填写 Bark 推送地址后，登录失效、待复核与好友火花告急将第一时间推送到手机',
    canSendTest: false,
    canClearOverride: false,
  }
}

const MAX_BARK_URL_LEN = 255
const MAX_BARK_RAW_INPUT_LEN = 2048
const CURL_URL_RE = /https?:\/\/[^\s'"`\\]+/i
const JSON_DEVICE_KEY_RE =
  /(?:\\?["'])device_key(?:\\?["'])\s*:\s*(?:\\?["'])([^"'\\]+)(?:\\?["'])/
const FORM_DEVICE_KEY_RE = /(?:^|[&\s'"?])device_key=([^&\s'"]+)/
const PERCENT_ENCODED_RE = /%[0-9a-fA-F]{2}/

function extractBarkDeviceKeyFromBody(rawInput: string): string {
  const start = rawInput.indexOf('{')
  const end = rawInput.lastIndexOf('}')
  if (start !== -1 && end > start) {
    try {
      const parsed = JSON.parse(rawInput.slice(start, end + 1)) as unknown
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
        const rawKey = (parsed as Record<string, unknown>).device_key
        if (typeof rawKey === 'string' && rawKey.trim()) {
          return rawKey.trim()
        }
      }
    } catch {
      // 回退到正则提取，兼容带转义引号或不完整片段的 curl 示例
    }
  }
  const jsonMatch = rawInput.match(JSON_DEVICE_KEY_RE)
  if (jsonMatch?.[1]?.trim()) return jsonMatch[1].trim()
  const formMatch = rawInput.match(FORM_DEVICE_KEY_RE)
  if (formMatch?.[1]?.trim()) return formMatch[1].trim()
  return ''
}

function isExampleMessageSegment(segment: string): boolean {
  const lower = segment.toLowerCase()
  if (lower === 'title' || lower === 'body') return true
  if (PERCENT_ENCODED_RE.test(segment)) return true
  return /[^\x00-\x7f]/.test(segment)
}

function decodeSegmentSafe(segment: string): string {
  try {
    return decodeURIComponent(segment)
  } catch {
    return segment
  }
}

export function maskBarkUrlPreview(rawUrl: string): string {
  let parsed: URL
  try {
    parsed = new URL(rawUrl)
  } catch {
    return '****'
  }
  const segments = parsed.pathname.split('/').filter(Boolean)
  for (let i = segments.length - 1; i >= 0; i -= 1) {
    if (segments[i].toLowerCase() !== 'push') {
      const key = segments[i]
      segments[i] = key.length > 4 ? `****${key.slice(-4)}` : '****'
      break
    }
  }
  const maskedPath = segments.length > 0 ? `/${segments.join('/')}` : ''
  return `${parsed.protocol}//${parsed.host}${maskedPath}`
}

export type BarkInputPreview =
  | {
      ok: true
      value: string
      masked: string
      extractedFromExample: boolean
    }
  | { ok: false; error: string }

export function parseBarkInputPreview(raw: string): BarkInputPreview {
  const cleaned = raw.trim()
  if (!cleaned) {
    return { ok: false, error: '请输入 Bark 推送地址，或直接粘贴 Bark App 中的 curl 使用示例' }
  }
  if (cleaned.length > MAX_BARK_RAW_INPUT_LEN) {
    return { ok: false, error: `输入内容长度不能超过 ${MAX_BARK_RAW_INPUT_LEN} 个字符` }
  }

  const isCurl = /^curl\b/i.test(cleaned) || /\s/.test(cleaned)
  let candidateUrl = cleaned
  if (isCurl) {
    const match = cleaned.match(CURL_URL_RE)
    if (!match) {
      return { ok: false, error: '未能识别到 http:// 或 https:// Bark 服务地址' }
    }
    candidateUrl = match[0].replace(/[,;]+$/, '')
  }

  let parsed: URL
  try {
    parsed = new URL(candidateUrl)
  } catch {
    return { ok: false, error: 'Bark 推送地址格式无效，请以 https:// 或 http:// 开头' }
  }
  if (parsed.protocol !== 'https:' && parsed.protocol !== 'http:') {
    return { ok: false, error: 'Bark 推送地址必须以 https:// 或 http:// 开头' }
  }

  let segments = parsed.pathname.split('/').filter(Boolean)
  const bodyKey = isCurl ? extractBarkDeviceKeyFromBody(cleaned) : ''

  if (bodyKey && (segments.length === 0 || segments[segments.length - 1].toLowerCase() === 'push')) {
    if (segments.length > 0 && segments[segments.length - 1].toLowerCase() === 'push') {
      segments.pop()
    }
    segments.push(bodyKey)
  } else if (
    segments.length >= 3 &&
    segments[segments.length - 2].toLowerCase() === 'title' &&
    segments[segments.length - 1].toLowerCase() === 'body'
  ) {
    segments = segments.slice(0, -2)
  } else if (
    parsed.host.toLowerCase() === 'api.day.app' &&
    segments.length >= 2 &&
    segments[0].toLowerCase() !== 'push' &&
    !(segments.length === 2 && segments[1].toLowerCase() === 'push')
  ) {
    segments = [segments[0]]
  } else if (isCurl && !/-X\s+POST\b/i.test(cleaned)) {
    while (
      segments.length >= 2 &&
      segments[segments.length - 1].toLowerCase() !== 'push' &&
      isExampleMessageSegment(segments[segments.length - 1])
    ) {
      segments.pop()
    }
  }

  let deviceKey = ''
  for (let i = segments.length - 1; i >= 0; i -= 1) {
    if (segments[i].toLowerCase() !== 'push') {
      deviceKey = segments[i]
      break
    }
  }
  if (!deviceKey) {
    return {
      ok: false,
      error: 'Bark 推送地址缺少设备 Key，请按 https://api.day.app/<device_key> 填写或粘贴完整示例',
    }
  }

  const decodedKey = decodeSegmentSafe(deviceKey)
  if (/[<>{}/?#\s]/.test(deviceKey) || /[<>{}/?#\s]/.test(decodedKey)) {
    return { ok: false, error: '请将示例中的占位文本替换为真实的 Bark 设备 Key' }
  }

  const normalized = `${parsed.protocol}//${parsed.host}/${segments.join('/')}`
  if (normalized.length > MAX_BARK_URL_LEN) {
    return { ok: false, error: `Bark 推送地址长度不能超过 ${MAX_BARK_URL_LEN} 个字符` }
  }

  return {
    ok: true,
    value: normalized,
    masked: maskBarkUrlPreview(normalized),
    extractedFromExample: normalized !== cleaned,
  }
}

export function validateBarkUrlInput(
  raw: string,
): { ok: true; value: string } | { ok: false; error: string } {
  const parsed = parseBarkInputPreview(raw)
  return parsed.ok ? { ok: true, value: parsed.value } : parsed
}

export function hasObservedSpark(
  state: Pick<ScanOfficialState, 'days' | 'kind' | 'label'> | null | undefined,
): boolean {
  if (!state) return false
  const label = state.label?.trim() || null
  if (label === '无火花') return false
  if (state.days != null && state.days > 0) return true
  if (label) return true
  if (state.kind) return true
  return false
}

export function isScanEntryImportable(entry: ScanChatEntry | undefined): boolean {
  if (!entry) return false
  if (entry.conversationType === 'group') {
    return entry.conversationStatus !== 'ambiguous'
  }
  return entry.identityStatus !== 'ambiguous'
}

function compareSparkDaysDesc(aDays: number | null, bDays: number | null): number {
  const aHas = aDays != null
  const bHas = bDays != null
  if (aHas && bHas) return bDays - aDays
  if (aHas) return -1
  if (bHas) return 1
  return 0
}

export function sortScanReconciliationItems(
  items: ScanReconciliationItem[],
  category: ScanReconciliationCategory,
  existingTitleSet: ReadonlySet<string>,
): ScanReconciliationItem[] {
  return items
    .map((item, idx) => ({ item, idx }))
    .sort((a, b) => {
      if (category === 'new') {
        const aSpark = hasObservedSpark(a.item.current)
        const bSpark = hasObservedSpark(b.item.current)
        const aImported = existingTitleSet.has(a.item.title)
        const bImported = existingTitleSet.has(b.item.title)
        const aTier = (aSpark ? 0 : 2) + (aImported ? 1 : 0)
        const bTier = (bSpark ? 0 : 2) + (bImported ? 1 : 0)
        if (aTier !== bTier) return aTier - bTier
        if (aSpark && bSpark) {
          const daysCmp = compareSparkDaysDesc(a.item.current.days, b.item.current.days)
          if (daysCmp !== 0) return daysCmp
        }
        return a.idx - b.idx
      }

      const aCurSpark = hasObservedSpark(a.item.current)
      const bCurSpark = hasObservedSpark(b.item.current)
      const aPrevSpark = hasObservedSpark(a.item.previous)
      const bPrevSpark = hasObservedSpark(b.item.previous)
      const aTier = aCurSpark ? 0 : aPrevSpark ? 1 : 2
      const bTier = bCurSpark ? 0 : bPrevSpark ? 1 : 2
      if (aTier !== bTier) return aTier - bTier
      if (aTier < 2) {
        const aDays = a.item.current.days ?? a.item.previous.days
        const bDays = b.item.current.days ?? b.item.previous.days
        const daysCmp = compareSparkDaysDesc(aDays, bDays)
        if (daysCmp !== 0) return daysCmp
      }
      return a.idx - b.idx
    })
    .map((entry) => entry.item)
}

export function filterScanReconciliationItems(
  items: ScanReconciliationItem[],
  query: string,
  entriesByTitle: ReadonlyMap<string, ScanChatEntry>,
): ScanReconciliationItem[] {
  const q = query.trim().toLowerCase()
  if (!q) return items
  return items.filter((item) => {
    if (item.title.toLowerCase().includes(q)) return true
    const entry = entriesByTitle.get(item.title)
    if (entry?.platformHandle?.toLowerCase().includes(q)) return true
    if (item.current.label?.toLowerCase().includes(q)) return true
    if (item.previous.label?.toLowerCase().includes(q)) return true
    if (item.current.days != null && `${item.current.days}天`.includes(q)) return true
    if (item.previous.days != null && `${item.previous.days}天`.includes(q)) return true
    if (item.conversationType === 'group' && (q === '群' || q === '群聊')) return true
    return false
  })
}

export function selectAutoImportSparkTitles(
  reconEntries: ScanReconciliationItem[],
  entriesByTitle: ReadonlyMap<string, ScanChatEntry>,
  existingTitleSet: ReadonlySet<string>,
): string[] {
  const picked: string[] = []
  const seen = new Set<string>()
  for (const item of reconEntries) {
    if (item.category !== 'new') continue
    if (seen.has(item.title) || existingTitleSet.has(item.title)) continue
    if (!hasObservedSpark(item.current)) continue
    if (!isScanEntryImportable(entriesByTitle.get(item.title))) continue
    seen.add(item.title)
    picked.push(item.title)
  }
  return picked
}

const CORE_EDITABLE_SELECTOR_KEYS = [
  'CONVERSATION_LIST',
  'CONVERSATION_ITEM',
  'CONVERSATION_TITLE',
  'SEARCH_INPUTS',
  'RIGHT_TITLE',
  'CHAT_EDITOR_SELECTORS',
  'SEND_BUTTONS',
  'STICKER_BUTTONS',
  'STICKER_PANELS',
  'STICKER_ITEM',
  'STICKER_ITEM_NAME',
] as const

export function buildInitialOverridesDraft(
  baseSelectors: Record<string, string | string[]>,
  currentOverrides: Record<string, string | string[]>,
): string {
  const seed: Record<string, string | string[]> = {}
  for (const key of CORE_EDITABLE_SELECTOR_KEYS) {
    if (key in baseSelectors) {
      seed[key] = baseSelectors[key]
    }
  }
  for (const [key, value] of Object.entries(currentOverrides)) {
    seed[key] = value
  }
  return JSON.stringify(seed, null, 2)
}

function sameSelectorValue(
  a: string | string[] | undefined,
  b: string | string[],
): boolean {
  if (typeof a === 'string' && typeof b === 'string') {
    return a === b
  }
  if (Array.isArray(a) && Array.isArray(b)) {
    return a.length === b.length && a.every((val, idx) => val === b[idx])
  }
  return false
}

export function parseSelectorOverridesDraft(
  rawJson: string,
  baseSelectors: Record<string, string | string[]>,
  editableKeys: string[],
):
  | { ok: true; overrides: Record<string, string | string[]> }
  | { ok: false; error: string } {
  let parsed: unknown
  try {
    parsed = JSON.parse(rawJson)
  } catch {
    return { ok: false, error: 'JSON 语法无效，请检查引号、逗号与括号是否配对' }
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return { ok: false, error: '覆盖配置顶层必须是 JSON 对象（键值对）' }
  }

  const editableSet = new Set(editableKeys)
  const deltas: Record<string, string | string[]> = {}

  for (const [key, rawVal] of Object.entries(parsed as Record<string, unknown>)) {
    if (key === 'CHAT_URL' || !editableSet.has(key)) {
      return { ok: false, error: `键名 "${key}" 不支持在线覆盖或拼写有误` }
    }
    const expected = baseSelectors[key]
    if (typeof expected === 'string') {
      if (typeof rawVal !== 'string') {
        return { ok: false, error: `键名 "${key}" 必须填写单个字符串选择器` }
      }
      const trimmed = rawVal.trim()
      if (!trimmed || trimmed.length > 512 || /[\r\n\0]/.test(trimmed)) {
        return { ok: false, error: `键名 "${key}" 不能为空、换行或超过 512 个字符` }
      }
      if (!sameSelectorValue(expected, trimmed)) {
        deltas[key] = trimmed
      }
    } else if (Array.isArray(expected)) {
      if (!Array.isArray(rawVal) || rawVal.length === 0 || rawVal.length > 20) {
        return { ok: false, error: `键名 "${key}" 必须填写包含 1..20 条选择器的字符串数组` }
      }
      const cleanedArr: string[] = []
      for (const item of rawVal) {
        if (typeof item !== 'string') {
          return { ok: false, error: `键名 "${key}" 数组内每一项都必须是字符串` }
        }
        const trimmed = item.trim()
        if (!trimmed || trimmed.length > 512 || /[\r\n\0]/.test(trimmed)) {
          return { ok: false, error: `键名 "${key}" 数组内包含空字符串或非法换行` }
        }
        cleanedArr.push(trimmed)
      }
      if (!sameSelectorValue(expected, cleanedArr)) {
        deltas[key] = cleanedArr
      }
    }
  }

  return { ok: true, overrides: deltas }
}
