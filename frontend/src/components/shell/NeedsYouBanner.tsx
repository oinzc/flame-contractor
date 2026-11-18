import { useState } from 'react'
import { api, ApiError } from '../../api.ts'
import { paginateItems } from '../../lib/pagination.ts'
import type { AccountAttentionItem } from '../../lib/workspace.ts'
import type { DrawerState } from '../../types.ts'
import { PaginationBar } from '../ui/PaginationBar.tsx'

export interface NeedsYouBannerProps {
  items: AccountAttentionItem[]
  onOpenDrawer: (drawer: DrawerState) => void
  onChanged: () => Promise<void> | void
}

export function NeedsYouBanner({ items, onOpenDrawer, onChanged }: NeedsYouBannerProps) {
  const [busyJobId, setBusyJobId] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [page, setPage] = useState(1)

  if (items.length === 0) return null

  const paged = paginateItems(items, page, 2)

  async function handleConfirm(jobId: string) {
    setBusyJobId(jobId)
    setError(null)
    try {
      await api.confirmJobSent(jobId)
      await onChanged()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '确认失败，请重试')
    } finally {
      setBusyJobId(null)
    }
  }

  async function handleRelease(jobId: string) {
    setBusyJobId(jobId)
    setError(null)
    try {
      await api.releaseJob(jobId)
      await onChanged()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '放行失败，请重试')
    } finally {
      setBusyJobId(null)
    }
  }

  return (
    <section className="fc-needs-you" aria-label="待你确认事项">
      {error && <div className="fc-alert is-error">{error}</div>}
      {paged.pageItems.map((item) => {
        if (item.kind === 'account_status') {
          return (
            <div key={item.id} className="fc-needs-you-row">
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <span className="fc-nav-badge is-warn">账号需处理</span>
                <span>{item.message}</span>
              </div>
              <button
                type="button"
                className="fc-btn fc-btn-primary fc-btn-sm"
                onClick={() => onOpenDrawer({ kind: 'account-manage' })}
              >
                去扫码处理
              </button>
            </div>
          )
        }

        const busy = busyJobId === item.job.id
        return (
          <div key={item.id} className="fc-needs-you-row">
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <span className="fc-nav-badge is-warn">待人工复核</span>
              <span>{item.message}</span>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
              <button
                type="button"
                className="fc-btn fc-btn-secondary fc-btn-sm"
                onClick={() =>
                  onOpenDrawer({
                    kind: 'job-detail',
                    jobId: item.job.id,
                  })
                }
              >
                核对记录
              </button>
              <button
                type="button"
                className="fc-btn fc-btn-secondary fc-btn-sm"
                disabled={busy}
                onClick={() => void handleRelease(item.job.id)}
              >
                释放重算
              </button>
              <button
                type="button"
                className="fc-btn fc-btn-primary fc-btn-sm"
                disabled={busy}
                onClick={() => void handleConfirm(item.job.id)}
              >
                确认已发
              </button>
            </div>
          </div>
        )
      })}
      {paged.totalPages > 1 && (
        <PaginationBar
          compact
          page={paged.safePage}
          totalPages={paged.totalPages}
          totalItems={paged.total}
          rangeStart={paged.rangeStart}
          rangeEnd={paged.rangeEnd}
          onPageChange={setPage}
        />
      )}
    </section>
  )
}
