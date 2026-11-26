import type { ScanChatEntry, ScanOfficialState } from '../types.ts'
import type { StatusTone } from './format.ts'

export const SPARK_KIND_LABELS: Record<string, string> = {
  couple: '情侣火花',
  family: '家人火花',
  soulmate: '密友火花',
  mate: '搭子火花',
  bosom: '挚友火花',
  normal: '官方火花',
}

export type ScanSparkSnapshot = Pick<ScanChatEntry, 'streakDays' | 'streakLabel'> & {
  streakKind?: string | null
  preview?: string | null
}

/**
 * 严格基于抖音官方火花 DOM 容器（CONVERSATION_STREAK）字段判断是否存在官方火花：
 * - 正整数官方天数（streakDays > 0）
 * - 容器内官方状态文案（streakLabel，且不为「无火花」）
 * - 容器内官方 flame_icon 图标种类（streakKind）
 * 严禁读取聊天预览 preview 字段推断官方火花。
 */
export function hasOfficialSparkSnapshot(entry: ScanSparkSnapshot): boolean {
  const label = entry.streakLabel?.trim() || null
  if (label === '无火花') return false
  if (entry.streakDays != null && entry.streakDays > 0) return true
  if (label !== null) return true
  return Boolean(entry.streakKind?.trim())
}

/**
 * 推导写入后端的官方火花展示标签：
 * - 容器内有文字标签（如「重燃中 1/3」「点亮中 1/3」「N 天后消失」）时原样返回；
 * - 存在正整数天数或命中官方 flame_icon 图标时返回 null（不伪造文案，也不误判为无火花）；
 * - 其余无官方火花信号时返回「无火花」，严禁回退读取 preview。
 */
export function sparkLabelForSnapshot(entry: ScanSparkSnapshot): string | null {
  const label = entry.streakLabel?.trim()
  if (label) return label
  if (entry.streakDays != null && entry.streakDays > 0) return null
  if (entry.streakKind?.trim()) return null
  return '无火花'
}

export function sparkKindDisplayName(kind: string | null | undefined): string | null {
  const cleaned = kind?.trim().toLowerCase()
  if (!cleaned) return null
  return SPARK_KIND_LABELS[cleaned] ?? '官方火花'
}

export interface SparkChipPresentation {
  text: string
  glyph: string
  tone: StatusTone
  observedNoSpark: boolean
  unknown: boolean
  fading: boolean
  rekindling: boolean
  igniting: boolean
  title: string
}

export function describeSparkChip(
  state: Pick<ScanOfficialState, 'days' | 'kind' | 'label'>,
  formattedScannedAt?: string | null,
): SparkChipPresentation {
  const officialLabel = state.label?.trim() || null
  const officialKind = state.kind?.trim() || null
  const officialDays = state.days != null && state.days > 0 ? state.days : null
  const observedNoSpark = officialLabel === '无火花'
  const unknown =
    !observedNoSpark &&
    officialLabel === null &&
    officialDays === null &&
    officialKind === null

  const fading = Boolean(officialLabel?.includes('后消失'))
  const rekindling = Boolean(officialLabel?.includes('重燃中'))
  const igniting = Boolean(officialLabel?.includes('点亮中'))

  const kindText = sparkKindDisplayName(officialKind)
  const text = officialLabel ?? (officialDays != null ? `${officialDays}天` : kindText ?? '待扫描')

  let tone: StatusTone = 'spark'
  if (observedNoSpark) tone = 'err'
  else if (unknown) tone = 'neutral'
  else if (fading || rekindling || officialKind?.toLowerCase() === 'couple') tone = 'warn'

  const glyph = observedNoSpark ? '×' : unknown ? '·' : fading ? '⌛' : '🔥'
  const suffix = formattedScannedAt ? ` · 更新于 ${formattedScannedAt}` : ''

  const title = observedNoSpark
    ? `抖音官方状态：未检测到火花${suffix}`
    : officialLabel
      ? `抖音官方状态：${officialLabel}${suffix}`
      : officialDays != null
        ? `抖音官方状态：${officialDays} 天${suffix}`
        : kindText
          ? `抖音官方状态：${kindText}（仅检测到官方图标）${suffix}`
          : '尚未记录抖音官方火花快照，可通过会话扫描刷新'

  return {
    text,
    glyph,
    tone,
    observedNoSpark,
    unknown,
    fading,
    rekindling,
    igniting,
    title,
  }
}
