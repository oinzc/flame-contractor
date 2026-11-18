export interface PaginatedSlice<T> {
  pageItems: T[]
  safePage: number
  totalPages: number
  total: number
  rangeStart: number
  rangeEnd: number
}

export function computePageSizeFromHeight(
  availablePx: number,
  rowHeightPx: number,
  minRows = 4,
  maxRows = 30,
): number {
  if (!Number.isFinite(availablePx) || availablePx <= 0 || rowHeightPx <= 0) {
    return minRows
  }
  const raw = Math.floor(availablePx / rowHeightPx)
  return Math.max(minRows, Math.min(maxRows, raw))
}

export interface CardCapacityOptions {
  headerPx?: number
  footerPx?: number
  rowPx: number
  minRows?: number
  maxRows?: number
}

/**
 * 基于外层稳定卡片高度计算单屏容量，避免“渲染底部分页栏导致可用高度减少 -> 页容量抖动”死循环：
 * - 当总数 <= 无底栏容量时：全部直接一屏展示，不渲染分页栏；
 * - 当总数 > 无底栏容量时：扣除底部分页栏高度计算每页条数并固定展示底部分页栏。
 */
export function resolveCardPageCapacity(
  cardHeightPx: number,
  totalItems: number | null,
  options: CardCapacityOptions,
): { pageSize: number; showPagination: boolean } {
  const {
    headerPx = 38,
    footerPx = 42,
    rowPx,
    minRows = 4,
    maxRows = 30,
  } = options

  if (!Number.isFinite(cardHeightPx) || cardHeightPx <= 0) {
    const fallback = Math.max(minRows, 8)
    return {
      pageSize: fallback,
      showPagination: totalItems !== null ? totalItems > fallback : false,
    }
  }

  const withoutFooter = computePageSizeFromHeight(
    cardHeightPx - headerPx,
    rowPx,
    minRows,
    maxRows,
  )
  const withFooter = computePageSizeFromHeight(
    cardHeightPx - headerPx - footerPx,
    rowPx,
    minRows,
    maxRows,
  )

  if (totalItems === null) {
    return { pageSize: withFooter, showPagination: false }
  }

  if (totalItems <= withoutFooter) {
    return { pageSize: withoutFooter, showPagination: false }
  }

  return { pageSize: withFooter, showPagination: true }
}

export function paginateItems<T>(
  items: readonly T[],
  page: number,
  pageSize: number,
): PaginatedSlice<T> {
  const safeSize = Math.max(1, pageSize)
  const total = items.length
  const totalPages = Math.max(1, Math.ceil(total / safeSize))
  const safePage = Math.min(totalPages, Math.max(1, Math.floor(page) || 1))
  const startIdx = (safePage - 1) * safeSize
  const pageItems = items.slice(startIdx, startIdx + safeSize)

  return {
    pageItems,
    safePage,
    totalPages,
    total,
    rangeStart: total === 0 ? 0 : startIdx + 1,
    rangeEnd: startIdx + pageItems.length,
  }
}

/**
 * 将冗长的后端幂等槽位标识（如 `<uuid>:v1:force:<nonce>` 或 `2026-11-18:v2`）格式化为单行短标签，
 * 避免表格单元格意外折行撑高行距。
 */
export function formatSlotBadge(slot: string): string {
  const versionMatch = slot.match(/:v(\d+)/)
  const version = versionMatch ? `v${versionMatch[1]}` : null
  if (slot.includes(':force:') || slot.startsWith('manual')) {
    return version ? `${version} · 手动发送` : '手动发送'
  }
  if (/^\d{4}-\d{2}-\d{2}:v\d+$/.test(slot)) {
    return `${version ?? 'v1'} · 每日排程`
  }
  if (version) {
    return `${version} · 自动排程`
  }
  return slot.length > 22 ? `${slot.slice(0, 18)}…` : slot
}
