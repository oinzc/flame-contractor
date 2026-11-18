import { useCallback, useEffect, useMemo, useState } from 'react'
import { api, ApiError } from '../../api.ts'
import {
  FAILURE_CLASS_LABELS,
  JOB_STATUS_LABELS,
  JOB_STATUS_LIGHT_TONES,
  formatDateTime,
} from '../../lib/format.ts'
import type {
  Account,
  ContractTarget,
  JobsPageData,
  MessageTemplate,
  StatsSummary,
} from '../../types.ts'
import { DownloadIcon } from '../ui/Icons.tsx'
import { PillTabs, type PillTabItem } from '../ui/PillTabs.tsx'
import { StatusPill } from '../ui/StatusPill.tsx'

export interface JobsStatsViewProps {
  accounts: Account[]
  selectedAccount: Account | null
  targets: ContractTarget[]
  templates: MessageTemplate[]
  onChanged: () => Promise<void> | void
}

type ScopeKey = 'current' | 'all'
type StatusFilterKey = '' | 'succeeded' | 'failed_retryable,failed_terminal' | 'unknown_requires_review'

const PAGE_SIZE = 20

export function JobsStatsView({
  accounts,
  selectedAccount,
  targets,
  templates,
  onChanged,
}: JobsStatsViewProps) {
  const [scope, setScope] = useState<ScopeKey>(selectedAccount ? 'current' : 'all')
  const [statusFilter, setStatusFilter] = useState<StatusFilterKey>('')
  const [offset, setOffset] = useState(0)
  const [reloadTick, setReloadTick] = useState(0)

  const [jobsData, setJobsData] = useState<JobsPageData | null>(null)
  const [stats, setStats] = useState<StatsSummary | null>(null)
  const [loading, setLoading] = useState(true)
  const [busyJobId, setBusyJobId] = useState<string | null>(null)
  const [exporting, setExporting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const effectiveAccountId =
    scope === 'current' && selectedAccount ? selectedAccount.id : undefined

  useEffect(() => {
    setOffset(0)
  }, [effectiveAccountId, statusFilter])

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    Promise.all([
      api.listJobs({
        limit: PAGE_SIZE,
        offset,
        accountId: effectiveAccountId,
        status: statusFilter || undefined,
      }),
      api.statsSummary().catch(() => null),
    ])
      .then(([pageData, statsRes]) => {
        if (cancelled) return
        setJobsData(pageData)
        if (statsRes) setStats(statsRes)
      })
      .catch((err) => {
        if (!cancelled) {
          setError(err instanceof ApiError ? err.message : '加载任务记录失败')
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [effectiveAccountId, offset, reloadTick, statusFilter])

  const reloadLocal = useCallback(() => {
    setReloadTick((n) => n + 1)
  }, [])

  const accountsById = useMemo(() => new Map(accounts.map((a) => [a.id, a])), [accounts])
  const targetsById = useMemo(() => new Map(targets.map((t) => [t.id, t])), [targets])
  const templatesById = useMemo(() => new Map(templates.map((t) => [t.id, t])), [templates])

  const statusCounts = jobsData?.statusCounts ?? {}
  const failedCount =
    (statusCounts.failed_retryable ?? 0) + (statusCounts.failed_terminal ?? 0)
  const allCount = Object.values(statusCounts).reduce((sum, n) => sum + n, 0)

  const statusTabs: PillTabItem<StatusFilterKey>[] = [
    { key: '', label: '全部', count: allCount },
    { key: 'succeeded', label: '已续焰', count: statusCounts.succeeded ?? 0 },
    { key: 'failed_retryable,failed_terminal', label: '失败', count: failedCount },
    {
      key: 'unknown_requires_review',
      label: '待复核',
      count: statusCounts.unknown_requires_review ?? 0,
    },
  ]

  async function handleJobReview(jobId: string, action: 'confirm' | 'release') {
    setBusyJobId(jobId)
    setError(null)
    try {
      if (action === 'confirm') await api.confirmJobSent(jobId)
      else await api.releaseJob(jobId)
      reloadLocal()
      await onChanged()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '处理待复核任务失败')
    } finally {
      setBusyJobId(null)
    }
  }

  async function handleExportCsv() {
    setExporting(true)
    setError(null)
    try {
      await api.downloadStatsCsv()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '导出 CSV 失败')
    } finally {
      setExporting(false)
    }
  }

  const dailySlice = stats?.daily.slice(-14) ?? []
  const maxDailyTotal = Math.max(1, ...dailySlice.map((d) => d.total))

  return (
    <>
      <header className="fc-page-header">
        <div>
          <div className="fc-page-title-row">
            <h1 className="fc-page-title">记录与统计</h1>
            {selectedAccount && scope === 'current' && (
              <StatusPill tone="neutral">{selectedAccount.displayName}</StatusPill>
            )}
          </div>
          <p className="fc-page-meta">
            汇总近期成功率趋势与每次续火投递流水，待人工复核项可在此直接闭环。
          </p>
        </div>

        <div className="fc-page-actions">
          <button
            type="button"
            className="fc-btn fc-btn-secondary"
            disabled={exporting}
            onClick={() => void handleExportCsv()}
          >
            <DownloadIcon /> {exporting ? '导出中…' : '导出全站 CSV'}
          </button>
        </div>
      </header>

      <div className="fc-toolbar">
        <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', alignItems: 'center' }}>
          {selectedAccount && (
            <PillTabs<ScopeKey>
              items={[
                { key: 'current', label: `仅看「${selectedAccount.displayName}」` },
                { key: 'all', label: '全部账号汇总' },
              ]}
              value={scope}
              onChange={setScope}
              ariaLabel="切换任务记录账号范围"
            />
          )}
          <PillTabs<StatusFilterKey>
            items={statusTabs}
            value={statusFilter}
            onChange={setStatusFilter}
            ariaLabel="按任务状态筛选"
          />
        </div>
      </div>

      <div className="fc-scroll">
        {error && (
          <div className="fc-alert is-error" style={{ marginBottom: 12 }}>
            {error}
          </div>
        )}

        {stats && (
          <div className="fc-stats-grid">
            <div className="fc-stat-card">
              <div className="fc-stat-label">全站近 {stats.windowDays} 日成功率</div>
              <div className="fc-stat-value">
                {stats.successRate !== null ? `${(stats.successRate * 100).toFixed(1)}%` : '—'}
              </div>
              <div className="fc-hint">
                共 {stats.succeededJobs} / {stats.totalJobs} 次成功
              </div>
            </div>

            <div className="fc-stat-card">
              <div className="fc-stat-label">全站连续全成天数</div>
              <div className="fc-stat-value">{stats.currentSuccessStreak} 天</div>
              <div className="fc-hint">历史最佳纪录 {stats.bestSuccessStreak} 天</div>
            </div>

            <div className="fc-stat-card">
              <div className="fc-stat-label">
                {scope === 'current' && selectedAccount ? '本号待人工复核' : '全站待人工复核'}
              </div>
              <div
                className="fc-stat-value"
                style={{
                  color:
                    (statusCounts.unknown_requires_review ?? 0) > 0
                      ? 'var(--warn)'
                      : 'var(--ink)',
                }}
              >
                {statusCounts.unknown_requires_review ?? 0}
              </div>
              <div className="fc-hint">未确认前绝不自动重发</div>
            </div>

            <div className="fc-stat-card">
              <div className="fc-stat-label">全站近期每日投递分布</div>
              {dailySlice.length === 0 ? (
                <div className="fc-hint" style={{ marginTop: 10 }}>
                  暂无按日统计数据
                </div>
              ) : (
                <div className="fc-mini-bars" aria-label="近期每日投递柱状分布">
                  {dailySlice.map((row) => {
                    const heightPct = Math.max(14, Math.round((row.total / maxDailyTotal) * 100))
                    const hasIssue = row.failed > 0 || row.unknown > 0
                    return (
                      <div
                        key={row.date}
                        className={`fc-mini-bar${hasIssue ? ' has-issue' : ''}`}
                        style={{ height: `${heightPct}%` }}
                        title={`${row.date}：成功 ${row.succeeded} / 失败 ${row.failed} / 待复核 ${row.unknown}`}
                      />
                    )
                  })}
                </div>
              )}
            </div>
          </div>
        )}

        {loading && !jobsData ? (
          <div className="fc-hint">正在加载发送记录…</div>
        ) : !jobsData || jobsData.items.length === 0 ? (
          <div className="fc-empty">
            <div style={{ fontWeight: 600 }}>当前筛选条件下暂无任务记录</div>
            <p className="fc-hint">切换状态胶囊或账号范围可查看其他流水。</p>
          </div>
        ) : (
          <>
            <div className="fc-table-wrap">
              <table className="fc-table" aria-label="发送任务流水表">
                <thead>
                  <tr>
                    <th>业务日期 · 更新时间</th>
                    {scope === 'all' && <th>所属账号</th>}
                    <th>发送对象</th>
                    <th>模板 · 幂等槽位</th>
                    <th>状态与原因</th>
                    <th style={{ textAlign: 'right' }}>操作</th>
                  </tr>
                </thead>
                <tbody>
                  {jobsData.items.map((job) => {
                    const acctName = accountsById.get(job.accountId)?.displayName ?? '已删账号'
                    const targetName =
                      targetsById.get(job.targetId)?.nicknameSnapshot ?? '已移除会话'
                    const tplName = job.templateId
                      ? (templatesById.get(job.templateId)?.name ?? '模板')
                      : '临时直发文字'
                    const isReview = job.status === 'unknown_requires_review'
                    const busy = busyJobId === job.id

                    return (
                      <tr key={job.id}>
                        <td className="fc-mono">
                          <div style={{ fontWeight: 600 }}>{job.businessDate.slice(0, 10)}</div>
                          <div style={{ fontSize: 11.5, color: 'var(--muted)' }}>
                            {formatDateTime(job.updatedAt)}
                          </div>
                        </td>
                        {scope === 'all' && <td>{acctName}</td>}
                        <td style={{ fontWeight: 600 }}>{targetName}</td>
                        <td>
                          <div>{tplName}</div>
                          <div className="fc-mono" style={{ fontSize: 11.5, color: 'var(--muted)' }}>
                            {job.messageSlotId}
                          </div>
                        </td>
                        <td>
                          <StatusPill tone={JOB_STATUS_LIGHT_TONES[job.status]}>
                            {JOB_STATUS_LABELS[job.status]}
                          </StatusPill>
                          {job.failureClass && (
                            <div style={{ fontSize: 11.5, color: 'var(--warn)', marginTop: 2 }}>
                              {FAILURE_CLASS_LABELS[job.failureClass]}
                            </div>
                          )}
                        </td>
                        <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                          {isReview ? (
                            <div style={{ display: 'inline-flex', gap: 6 }}>
                              <button
                                type="button"
                                className="fc-btn fc-btn-secondary fc-btn-sm"
                                disabled={busy}
                                onClick={() => void handleJobReview(job.id, 'release')}
                              >
                                释放重算
                              </button>
                              <button
                                type="button"
                                className="fc-btn fc-btn-primary fc-btn-sm"
                                disabled={busy}
                                onClick={() => void handleJobReview(job.id, 'confirm')}
                              >
                                确认已发
                              </button>
                            </div>
                          ) : (
                            <span className="fc-hint">—</span>
                          )}
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>

            {jobsData.total > PAGE_SIZE && (
              <div
                style={{
                  marginTop: 14,
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                }}
              >
                <span className="fc-hint fc-tabular">
                  显示第 {offset + 1} – {Math.min(offset + PAGE_SIZE, jobsData.total)} 条，共{' '}
                  {jobsData.total} 条
                </span>
                <div style={{ display: 'flex', gap: 8 }}>
                  <button
                    type="button"
                    className="fc-btn fc-btn-secondary fc-btn-sm"
                    disabled={offset === 0}
                    onClick={() => setOffset((prev) => Math.max(0, prev - PAGE_SIZE))}
                  >
                    上一页
                  </button>
                  <button
                    type="button"
                    className="fc-btn fc-btn-secondary fc-btn-sm"
                    disabled={offset + PAGE_SIZE >= jobsData.total}
                    onClick={() => setOffset((prev) => prev + PAGE_SIZE)}
                  >
                    下一页
                  </button>
                </div>
              </div>
            )}
          </>
        )}
      </div>
    </>
  )
}
