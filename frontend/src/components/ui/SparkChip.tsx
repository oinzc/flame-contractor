import { formatDateTime } from '../../lib/format.ts'
import { describeSparkChip } from '../../lib/spark.ts'

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
  const view = describeSparkChip(
    { days: officialDays, kind: kind ?? null, label: label ?? null },
    scannedAt ? formatDateTime(scannedAt) : null,
  )

  return (
    <span className={`fc-status-pill tone-${view.tone} fc-tabular`} title={view.title}>
      <span aria-hidden="true">{view.glyph}</span>
      <span>{view.text}</span>
    </span>
  )
}
