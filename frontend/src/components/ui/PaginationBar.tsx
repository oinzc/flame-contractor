export interface PaginationBarProps {
  page: number
  totalPages: number
  totalItems: number
  rangeStart: number
  rangeEnd: number
  compact?: boolean
  onPageChange: (nextPage: number) => void
}

function buildPageNumbers(current: number, totalPages: number): (number | 'ellipsis')[] {
  if (totalPages <= 7) {
    return Array.from({ length: totalPages }, (_, i) => i + 1)
  }
  const pages: (number | 'ellipsis')[] = [1]
  const left = Math.max(2, current - 1)
  const right = Math.min(totalPages - 1, current + 1)

  if (left > 2) pages.push('ellipsis')
  for (let p = left; p <= right; p += 1) {
    pages.push(p)
  }
  if (right < totalPages - 1) pages.push('ellipsis')
  pages.push(totalPages)
  return pages
}

export function PaginationBar({
  page,
  totalPages,
  totalItems,
  rangeStart,
  rangeEnd,
  compact = false,
  onPageChange,
}: PaginationBarProps) {
  if (totalPages <= 1) return null

  const pageTokens = compact ? [] : buildPageNumbers(page, totalPages)

  return (
    <div
      className={`fc-pagination-bar${compact ? ' is-compact' : ''}`}
      role="navigation"
      aria-label="分页导航"
    >
      <span className="fc-hint fc-tabular">
        第 {rangeStart}–{rangeEnd} 项 · 共 {totalItems} 项（{page}/{totalPages} 页）
      </span>

      <div className="fc-pagination-controls">
        <button
          type="button"
          className="fc-btn fc-btn-secondary fc-btn-sm"
          disabled={page <= 1}
          onClick={() => onPageChange(page - 1)}
        >
          上一页
        </button>

        {pageTokens.map((token, idx) =>
          token === 'ellipsis' ? (
            <span key={`el-${idx}`} className="fc-hint" style={{ padding: '0 4px' }}>
              …
            </span>
          ) : (
            <button
              key={token}
              type="button"
              aria-current={token === page ? 'page' : undefined}
              className={`fc-page-num${token === page ? ' is-active' : ''}`}
              onClick={() => onPageChange(token)}
            >
              {token}
            </button>
          ),
        )}

        <button
          type="button"
          className="fc-btn fc-btn-secondary fc-btn-sm"
          disabled={page >= totalPages}
          onClick={() => onPageChange(page + 1)}
        >
          下一页
        </button>
      </div>
    </div>
  )
}
