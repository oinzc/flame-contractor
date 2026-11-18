import { useEffect, useMemo, useState } from 'react'
import { api, ApiError } from '../../api.ts'
import type { BackgroundTasksController } from '../../hooks/useBackgroundTasks.ts'
import { formatDateTime } from '../../lib/format.ts'
import { paginateItems } from '../../lib/pagination.ts'
import { sparkLabelForSnapshot } from '../../lib/spark.ts'
import type {
  Account,
  ContractTarget,
  MessageTemplate,
  ScanChatEntry,
  ScanHistoryItem,
  ScanProgress,
  ScanReconciliationCategory,
  TargetBatchResult,
} from '../../types.ts'
import { DrawerShell } from '../ui/DrawerShell.tsx'
import { PaginationBar } from '../ui/PaginationBar.tsx'
import { PillTabs, type PillTabItem } from '../ui/PillTabs.tsx'
import { SparkChip } from '../ui/SparkChip.tsx'
import { StatusPill } from '../ui/StatusPill.tsx'

export interface ScanReconcileDrawerProps {
  open: boolean
  account: Account | null
  targets: ContractTarget[]
  templates: MessageTemplate[]
  tasks: BackgroundTasksController
  onClose: () => void
  onChanged: () => Promise<void> | void
}

function isEntryImportable(entry: ScanChatEntry | undefined): boolean {
  if (!entry) return false
  if (entry.conversationType === 'group') {
    return entry.conversationStatus !== 'ambiguous'
  }
  return entry.identityStatus !== 'ambiguous'
}

export function ScanReconcileDrawer({
  open,
  account,
  targets,
  templates,
  tasks,
  onClose,
  onChanged,
}: ScanReconcileDrawerProps) {
  const [history, setHistory] = useState<ScanHistoryItem[]>([])
  const [accountLatestScan, setAccountLatestScan] = useState<ScanProgress | null>(null)
  const [selectedTaskId, setSelectedTaskId] = useState<string>('latest')
  const [category, setCategory] = useState<ScanReconciliationCategory>('new')
  const [page, setPage] = useState(1)
  const [selectedNewTitles, setSelectedNewTitles] = useState<Set<string>>(new Set())
  const [defaultTemplateId, setDefaultTemplateId] = useState('')
  const [batchSubmitting, setBatchSubmitting] = useState(false)
  const [deletingNoSpark, setDeletingNoSpark] = useState(false)
  const [batchResult, setBatchResult] = useState<TargetBatchResult | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!open || !account) return undefined
    let active = true
    setError(null)
    setBatchResult(null)
    setSelectedNewTitles(new Set())
    Promise.all([
      api.scanChatsHistory(account.id, 10).catch(() => []),
      api.latestScanChats(account.id).catch(() => null),
    ]).then(([items, latest]) => {
      if (!active) return
      setHistory(items)
      setAccountLatestScan(latest)
    })
    return () => {
      active = false
    }
  }, [open, account?.id, tasks.scanTask?.status])

  const accountTargets = useMemo(
    () => (account ? targets.filter((t) => t.accountId === account.id && t.deletedAt === null) : []),
    [account, targets],
  )

  const existingTitleSet = useMemo(
    () => new Set(accountTargets.map((t) => t.nicknameSnapshot)),
    [accountTargets],
  )

  const unboundUserTargetIds = useMemo(
    () =>
      accountTargets
        .filter((t) => t.conversationType !== 'group' && !t.platformUserId)
        .map((t) => t.id),
    [accountTargets],
  )

  const currentLiveTask =
    account && tasks.scanTask?.accountId === account.id
      ? tasks.scanTask
      : accountLatestScan?.accountId === account?.id
        ? accountLatestScan
        : null

  const viewedSnapshot = useMemo(() => {
    if (selectedTaskId === 'latest' && currentLiveTask) return currentLiveTask
    const found = history.find((h) => h.taskId === selectedTaskId)
    return found ?? currentLiveTask ?? history[0] ?? null
  }, [currentLiveTask, history, selectedTaskId])

  const isViewingLatestLive = Boolean(
    currentLiveTask && viewedSnapshot?.taskId === currentLiveTask.taskId,
  )

  const entriesByTitle = useMemo(() => {
    const m = new Map<string, ScanChatEntry>()
    for (const e of currentLiveTask?.entries ?? []) {
      m.set(e.title, e)
    }
    return m
  }, [currentLiveTask?.entries])

  // Auto-pick most actionable non-empty category when snapshot changes
  useEffect(() => {
    if (!viewedSnapshot?.reconciliation) return
    const c = viewedSnapshot.reconciliation.counts
    if (c.new > 0) setCategory('new')
    else if (c.changed > 0) setCategory('changed')
    else if (c.noSpark > 0) setCategory('no_spark')
    else if (c.notObserved > 0) setCategory('not_observed')
    else setCategory('unchanged')
  }, [viewedSnapshot?.taskId])

  const recon = viewedSnapshot?.reconciliation
  const noSparkTargets = useMemo(() => {
    const byId = new Map(accountTargets.map((t) => [t.id, t]))
    return (recon?.entries ?? [])
      .filter((item) => item.category === 'no_spark' && item.targetId)
      .map((item) => byId.get(item.targetId!))
      .filter((t): t is ContractTarget => Boolean(t))
  }, [accountTargets, recon?.entries])

  if (!open || !account) return null

  const scanRunning = tasks.scanTask?.status === 'running'
  const canStartScan = account.status === 'healthy' && !scanRunning

  const counts = recon?.counts ?? {
    changed: 0,
    noSpark: 0,
    new: 0,
    notObserved: 0,
    unchanged: 0,
  }
  const categoryItems = (recon?.entries ?? []).filter((item) => item.category === category)

  function toggleSelectTitle(title: string) {
    setSelectedNewTitles((prev) => {
      const next = new Set(prev)
      if (next.has(title)) next.delete(title)
      else next.add(title)
      return next
    })
  }

  async function handleStartScan() {
    if (!account) return
    setError(null)
    setBatchResult(null)
    try {
      await tasks.startManualScan(account.id)
      setSelectedTaskId('latest')
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '发起扫描失败')
    }
  }

  async function handleBatchImport() {
    if (!account || !isViewingLatestLive || selectedNewTitles.size === 0) return
    const picked = (currentLiveTask?.entries ?? []).filter((e) =>
      selectedNewTitles.has(e.title),
    )
    if (picked.length === 0) return
    setBatchSubmitting(true)
    setError(null)
    try {
      const res = await api.createTargetsBatch({
        accountId: account.id,
        templateId: defaultTemplateId || undefined,
        items: picked.map((entry) => ({
          nickname: entry.title,
          platformUserId: entry.platformUserId,
          platformHandle: entry.platformHandle,
          platformConversationId: entry.platformConversationId,
          conversationType: entry.conversationType,
          identityStatus: entry.identityStatus,
          conversationStatus: entry.conversationStatus,
          streakDays: entry.streakDays ?? undefined,
          sparkDays: entry.streakDays,
          sparkKind: entry.streakKind,
          sparkLabel: sparkLabelForSnapshot(entry),
        })),
      })
      setBatchResult(res)
      setSelectedNewTitles(new Set())
      await onChanged()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '批量导入失败')
    } finally {
      setBatchSubmitting(false)
    }
  }

  async function handleDeleteNoSpark() {
    if (noSparkTargets.length === 0 || deletingNoSpark) return
    const names = noSparkTargets.map((t) => t.nicknameSnapshot).join('、')
    if (
      !window.confirm(
        `确定移除本次扫描确认无火花的 ${noSparkTargets.length} 位好友？\n\n${names}\n\n移除后停止续火并从列表隐藏，历史任务保留审计。`,
      )
    ) {
      return
    }
    setDeletingNoSpark(true)
    setError(null)
    const results = await Promise.allSettled(
      noSparkTargets.map((t) => api.deleteTarget(t.id)),
    )
    const failed = noSparkTargets.filter((_, idx) => results[idx].status === 'rejected')
    setDeletingNoSpark(false)
    await onChanged()
    if (failed.length > 0) {
      setError(`部分好友移除失败：${failed.map((f) => f.nicknameSnapshot).join('、')}`)
    }
  }

  async function handleStartHydration() {
    if (!account || unboundUserTargetIds.length === 0) return
    if (
      !window.confirm(
        `将逐个打开 ${unboundUserTargetIds.length} 个尚未绑定稳定 ID 的单人会话以补采官方身份（只读不发消息，可能标记会话已读）。是否继续？`,
      )
    ) {
      return
    }
    setError(null)
    try {
      await tasks.startIdentityHydration(account.id, unboundUserTargetIds)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '启动身份补采失败')
    }
  }

  const tabs: PillTabItem<ScanReconciliationCategory>[] = [
    { key: 'new', label: '新会话', count: counts.new },
    { key: 'changed', label: '状态变化', count: counts.changed },
    { key: 'no_spark', label: '无火花', count: counts.noSpark },
    { key: 'not_observed', label: '未观察到', count: counts.notObserved },
    { key: 'unchanged', label: '无变化', count: counts.unchanged },
  ]

  return (
    <DrawerShell
      open={open}
      wide
      title={`${account.displayName} · 会话扫描与对账`}
      subtitle="只读扫描抖音会话列表，刷新官方火花快照并勾选导入新会话"
      onClose={onClose}
    >
      {error && <div className="fc-alert is-error">{error}</div>}
      {batchResult && (
        <div className="fc-alert is-ok">
          已成功导入 {batchResult.created.length} 人，刷新 {batchResult.refreshed.length} 人。
        </div>
      )}

      <div className="fc-card" style={{ padding: 14, gap: 10 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
          <div>
            <div style={{ fontWeight: 600, fontSize: 13.5 }}>
              {scanRunning
                ? `正在扫描：${tasks.scanTask?.stage ?? '读取中'}`
                : viewedSnapshot
                  ? `快照时间：${formatDateTime(viewedSnapshot.finishedAt ?? viewedSnapshot.startedAt)}`
                  : '尚无扫描快照'}
            </div>
            <div className="fc-hint">
              {account.status !== 'healthy'
                ? '账号需处于健康在线状态才可发起扫描'
                : '扫描完成后仅自动同步已有好友快照，陌生会话需在下方手动勾选导入'}
            </div>
          </div>

          <div style={{ display: 'flex', gap: 6 }}>
            {unboundUserTargetIds.length > 0 && account.status === 'healthy' && (
              <button
                type="button"
                className="fc-btn fc-btn-secondary fc-btn-sm"
                disabled={tasks.identityTask?.status === 'running'}
                onClick={() => void handleStartHydration()}
              >
                身份补采 ({unboundUserTargetIds.length})
              </button>
            )}
            {scanRunning && tasks.scanTask?.accountId === account.id ? (
              <button
                type="button"
                className="fc-btn fc-btn-secondary fc-btn-sm"
                onClick={() => void tasks.cancelManualScan()}
              >
                取消扫描
              </button>
            ) : (
              <button
                type="button"
                className="fc-btn fc-btn-primary fc-btn-sm"
                disabled={!canStartScan}
                onClick={() => void handleStartScan()}
              >
                开始扫描会话
              </button>
            )}
          </div>
        </div>

        {history.length > 1 && (
          <select
            className="fc-select"
            aria-label="切换查看历史扫描记录"
            value={selectedTaskId}
            onChange={(e) => setSelectedTaskId(e.target.value)}
          >
            <option value="latest">最新扫描快照</option>
            {history.map((item) => (
              <option key={item.taskId} value={item.taskId}>
                {formatDateTime(item.finishedAt ?? item.startedAt)} ·{' '}
                {item.source === 'automatic' ? '自动同步' : '手动扫描'}（{item.status}）
              </option>
            ))}
          </select>
        )}
      </div>

      {viewedSnapshot && recon?.schemaVersion === 1 ? (
        <>
          <PillTabs
            items={tabs}
            value={category}
            onChange={(next) => {
              setCategory(next)
              setPage(1)
            }}
            ariaLabel="扫描对账分类"
          />

          {category === 'new' && isViewingLatestLive && counts.new > 0 && (
            <div className="fc-preview-stage" style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap' }}>
              <select
                className="fc-select"
                style={{ width: 200, height: 32, fontSize: 12.5 }}
                aria-label="导入时默认绑定模板"
                value={defaultTemplateId}
                onChange={(e) => setDefaultTemplateId(e.target.value)}
              >
                <option value="">导入后暂不绑模板</option>
                {templates.map((t) => (
                  <option key={t.id} value={t.id}>
                    默认绑：{t.name}
                  </option>
                ))}
              </select>

              <button
                type="button"
                className="fc-btn fc-btn-primary fc-btn-sm"
                disabled={selectedNewTitles.size === 0 || batchSubmitting}
                onClick={() => void handleBatchImport()}
              >
                {batchSubmitting ? '导入中…' : `导入勾选会话 (${selectedNewTitles.size})`}
              </button>
            </div>
          )}

          {category === 'no_spark' && isViewingLatestLive && noSparkTargets.length > 0 && (
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
              <span className="fc-hint">以下已建档好友在本次扫描中确认为无火花：</span>
              <button
                type="button"
                className="fc-btn fc-btn-danger fc-btn-sm"
                disabled={deletingNoSpark}
                onClick={() => void handleDeleteNoSpark()}
              >
                {deletingNoSpark ? '移除中…' : `一键移除无火花好友 (${noSparkTargets.length})`}
              </button>
            </div>
          )}

          {categoryItems.length === 0 ? (
            <div className="fc-empty" style={{ padding: '24px 16px' }}>
              <span className="fc-hint">该分类下没有会话条目</span>
            </div>
          ) : (
            <>
              <div className="fc-kv-list">
                {paginateItems(categoryItems, page, 7).pageItems.map((item, idx) => {
                  const entry = entriesByTitle.get(item.title)
                  const alreadyExists = existingTitleSet.has(item.title)
                  const selectable =
                    category === 'new' &&
                    isViewingLatestLive &&
                    !alreadyExists &&
                    isEntryImportable(entry)

                  return (
                    <div key={`${item.title}-${idx}`} className="fc-kv-row">
                      <label
                        style={{
                          display: 'flex',
                          alignItems: 'center',
                          gap: 8,
                          minWidth: 0,
                          cursor: selectable ? 'pointer' : 'default',
                        }}
                      >
                        {category === 'new' && (
                          <input
                            type="checkbox"
                            checked={selectedNewTitles.has(item.title)}
                            disabled={!selectable}
                            onChange={() => toggleSelectTitle(item.title)}
                          />
                        )}
                        <div style={{ minWidth: 0 }}>
                          <div style={{ fontWeight: 600 }}>
                            {item.title}{' '}
                            {item.conversationType === 'group' && (
                              <span className="fc-nav-badge">群</span>
                            )}
                          </div>
                          {category === 'new' && (
                            <div className="fc-hint">
                              {alreadyExists
                                ? '已在当前账号好友列表中'
                                : !isViewingLatestLive
                                  ? '历史快照仅供查看，重新扫描后可勾选导入'
                                  : isEntryImportable(entry)
                                    ? '可安全导入'
                                    : '存在同名冲突，暂不可直接批量导入'}
                            </div>
                          )}
                        </div>
                      </label>

                      <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexShrink: 0 }}>
                        {category === 'changed' ? (
                          <>
                            <SparkChip
                              officialDays={item.previous.days}
                              kind={item.previous.kind}
                              label={item.previous.label}
                            />
                            <span style={{ color: 'var(--muted)' }}>→</span>
                            <SparkChip
                              officialDays={item.current.days}
                              kind={item.current.kind}
                              label={item.current.label}
                            />
                          </>
                        ) : (
                          <SparkChip
                            officialDays={item.current.days}
                            kind={item.current.kind}
                            label={item.current.label}
                          />
                        )}
                        {alreadyExists && category === 'new' && (
                          <StatusPill tone="ok">已导入</StatusPill>
                        )}
                      </div>
                    </div>
                  )
                })}
              </div>
              {categoryItems.length > 7 && (
                <PaginationBar
                  compact
                  page={paginateItems(categoryItems, page, 7).safePage}
                  totalPages={paginateItems(categoryItems, page, 7).totalPages}
                  totalItems={categoryItems.length}
                  rangeStart={paginateItems(categoryItems, page, 7).rangeStart}
                  rangeEnd={paginateItems(categoryItems, page, 7).rangeEnd}
                  onPageChange={setPage}
                />
              )}
            </>
          )}
        </>
      ) : (
        <div className="fc-empty">
          <div style={{ fontWeight: 600 }}>暂无对账明细</div>
          <p className="fc-hint">点击上方「开始扫描会话」，读取完成后在此展示新会话与火花变化。</p>
        </div>
      )}
    </DrawerShell>
  )
}
