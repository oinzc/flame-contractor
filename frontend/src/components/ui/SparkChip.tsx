import { formatDateTime, type StatusTone } from '../../lib/format.ts'

export interface SparkChipProps {
  officialDays: number | null
  kind?: string | null
  label?: string | null
  scannedAt?: string | null
}

/**
 * 严格仅展示抖音官方会话扫描快照；绝不读取或回退显示本地 streakDays。
 */
export function SparkChip({ officialDays, kind, label, scannedAt }: SparkChipProps) {
  const officialLabel = label?.trim() || null
  const observedNoSpark = officialLabel === '无火花'
  const unknown = officialLabel === null && officialDays === null

  const text = officialLabel ?? (officialDays == null ? '待扫描' : `${officialDays}天`)
  const fading = Boolean(officialLabel?.includes('后消失'))

  let tone: StatusTone = 'spark'
  if (observedNoSpark) tone = 'err'
  else if (unknown) tone = 'neutral'
  else if (fading || kind === 'couple') tone = 'warn'

  const glyph = observedNoSpark ? '×' : unknown ? '·' : fading ? '⌛' : '🔥'

  const title = observedNoSpark
    ? `抖音官方状态：未检测到火花${scannedAt ? ` · 更新于 ${formatDateTime(scannedAt)}` : ''}`
    : officialLabel
      ? `抖音官方状态：${officialLabel}${scannedAt ? ` · 更新于 ${formatDateTime(scannedAt)}` : ''}`
      : officialDays != null
        ? `抖音官方状态：${officialDays} 天${scannedAt ? ` · 更新于 ${formatDateTime(scannedAt)}` : ''}`
        : '尚未记录抖音官方火花快照，可通过会话扫描刷新'

  return (
    <span className={`fc-status-pill tone-${tone} fc-tabular`} title={title}>
      <span aria-hidden="true">{glyph}</span>
      <span>{text}</span>
    </span>
  )
}
