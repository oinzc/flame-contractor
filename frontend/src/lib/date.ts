export const WEEKDAY_POOL_LABELS = ['一', '二', '三', '四', '五', '六', '日'] as const

const JS_WEEKDAY_ZH = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六'] as const

/**
 * JS Date.getDay(): 0=周日, 1=周一 .. 6=周六
 * 后端 PoolItem.weekdayMask: bit 0=周一 (1<<0) .. bit 6=周日 (1<<6)
 */
export function weekdayToPoolBit(jsDay: number): number {
  const normalized = ((jsDay % 7) + 7) % 7
  return (normalized + 6) % 7
}

export function formatWeekdayZh(now: Date): string {
  return JS_WEEKDAY_ZH[now.getDay()] ?? '星期一'
}

/**
 * 按账号配置的 IANA 时区折算当天业务日期 YYYY-MM-DD；非法时区安全回退到 UTC 日期。
 */
export function dateInAccountTimezone(now: Date, timeZone: string): string {
  try {
    const parts = new Intl.DateTimeFormat('en-CA', {
      timeZone: timeZone || 'Asia/Shanghai',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
    }).formatToParts(now)
    const year = parts.find((p) => p.type === 'year')?.value
    const month = parts.find((p) => p.type === 'month')?.value
    const day = parts.find((p) => p.type === 'day')?.value
    if (year && month && day) {
      return `${year}-${month}-${day}`
    }
  } catch {
    // fallback to UTC ISO slice below
  }
  return now.toISOString().slice(0, 10)
}
