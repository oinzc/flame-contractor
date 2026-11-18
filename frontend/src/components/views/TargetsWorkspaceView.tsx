import { useEffect, useMemo, useState } from 'react'
import { api, ApiError } from '../../api.ts'
import { useCardPaginationCapacity } from '../../hooks/useViewportPageSize.ts'
import { formatDateTime } from '../../lib/format.ts'
import { paginateItems } from '../../lib/pagination.ts'
import type { AccountAttentionItem, TargetDaySummary } from '../../lib/workspace.ts'
import type {
  Account,
  ContractTarget,
  DrawerState,
  MessageTemplate,
  SendSchedule,
} from '../../types.ts'
import { AccountHeader } from '../shell/AccountHeader.tsx'
import { NeedsYouBanner } from '../shell/NeedsYouBanner.tsx'
import { PlusIcon } from '../ui/Icons.tsx'
import { PaginationBar } from '../ui/PaginationBar.tsx'
import { PillTabs, type PillTabItem } from '../ui/PillTabs.tsx'
import { SparkChip } from '../ui/SparkChip.tsx'
import { StatusPill } from '../ui/StatusPill.tsx'
import { Switch } from '../ui/Switch.tsx'

type TargetFilterKey = 'all' | 'pending' | 'succeeded' | 'attention' | 'disabled'

export interface TargetsWorkspaceViewProps {
  account: Account | null
  targets: ContractTarget[]
  templates: MessageTemplate[]
  dayMap: Map<string, TargetDaySummary>
  attentionItems: AccountAttentionItem[]
  todaySchedule: SendSchedule | null
  drawer: DrawerState
  onOpenDrawer: (drawer: DrawerState) => void
  onChanged: () => Promise<void> | void
}

export function TargetsWorkspaceView({
  account,
  targets,
  templates,
  dayMap,
  attentionItems,
  todaySchedule,
  drawer,
  onOpenDrawer,
  onChanged,
}: TargetsWorkspaceViewProps) {
  const [filter, setFilter] = useState<TargetFilterKey>('all')
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(1)
  const [togglingAccount, setTogglingAccount] = useState(false)
  const [busyTargetId, setBusyTargetId] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)

  const templatesById = useMemo(
    () => new Map(templates.map((tpl) => [tpl.id, tpl])),
    [templates],
  )

  const activeTargets = useMemo(
    () =>
      account
        ? targets.filter((t) => t.accountId === account.id && t.deletedAt === null)
        : [],
    [account, targets],
  )

  const counts = useMemo(() => {
    let pending = 0
    let succeeded = 0
    let attention = 0
    let disabled = 0
    for (const t of activeTargets) {
      const s = dayMap.get(t.id)
      if (!t.enabled) {
        disabled += 1
        continue
      }
      if (s?.hasUnresolvedReview || s?.todayFailed) {
        attention += 1
      } else if (s?.todaySucceeded) {
        succeeded += 1
      } else {
        pending += 1
      }
    }
    return {
      all: activeTargets.length,
      pending,
      succeeded,
      attention,
      disabled,
    }
  }, [activeTargets, dayMap])

  const filteredTargets = useMemo(() => {
    const q = search.trim().toLowerCase()
    return activeTargets.filter((t) => {
      const s = dayMap.get(t.id)
      if (filter === 'disabled' && t.enabled) return false
      if (filter === 'attention' && !(t.enabled && (s?.hasUnresolvedReview || s?.todayFailed))) {
        return false
      }
      if (filter === 'succeeded' && !(t.enabled && s?.todaySucceeded && !s?.hasUnresolvedReview)) {
        return false
      }
      if (
        filter === 'pending' &&
        !(t.enabled && !s?.todaySucceeded && !s?.todayFailed && !s?.hasUnresolvedReview)
      ) {
        return false
      }
      if (!q) return true
      return (
        t.nicknameSnapshot.toLowerCase().includes(q) ||
        (t.platformHandle?.toLowerCase().includes(q) ?? false)
      )
    })
  }, [activeTargets, dayMap, filter, search])

  useEffect(() => {
    setPage(1)
  }, [account?.id, filter, search])

  const { cardRef, pageSize, showPagination } = useCardPaginationCapacity(
    filteredTargets.length,
    {
      headerPx: 38,
      footerPx: 44,
      rowPx: 47,
      minRows: 5,
      maxRows: 25,
    },
  )

  const paged = useMemo(
    () => paginateItems(filteredTargets, page, pageSize),
    [filteredTargets, page, pageSize],
  )

  if (!account) {
    return (
      <div className="fc-stage" style={{ paddingTop: 48 }}>
        <div className="fc-empty">
          <h2 style={{ fontSize: 17, fontWeight: 600 }}>还没有配置抖音账号</h2>
          <p style={{ color: 'var(--muted)', maxWidth: 420 }}>
            先创建一个抖音账号配置并在右侧完成二维码授权，随后即可扫描会话列表并批量导入续火好友。
          </p>
          <button
            type="button"
            className="fc-btn fc-btn-primary"
            style={{ marginTop: 8 }}
            onClick={() => onOpenDrawer({ kind: 'create-account' })}
          >
            <PlusIcon /> 新增首个抖音账号
          </button>
        </div>
      </div>
    )
  }

  async function handleToggleAccount(next: boolean) {
    if (!account) return
    setTogglingAccount(true)
    setActionError(null)
    try {
      await api.patchAccount(account.id, { enabled: next })
      await onChanged()
    } catch (err) {
      setActionError(err instanceof ApiError ? err.message : '更新账号状态失败')
    } finally {
      setTogglingAccount(false)
    }
  }

  async function handleToggleTarget(target: ContractTarget, next: boolean) {
    setBusyTargetId(target.id)
    setActionError(null)
    try {
      await api.patchTarget(target.id, { enabled: next })
      await onChanged()
    } catch (err) {
      setActionError(err instanceof ApiError ? err.message : '更新好友启用状态失败')
    } finally {
      setBusyTargetId(null)
    }
  }

  const filterTabs: PillTabItem<TargetFilterKey>[] = [
    { key: 'all', label: '全部', count: counts.all },
    { key: 'pending', label: '今日待发', count: counts.pending },
    { key: 'succeeded', label: '已续火', count: counts.succeeded },
    { key: 'attention', label: '待复核/异常', count: counts.attention },
    { key: 'disabled', label: '已停用', count: counts.disabled },
  ]

  const unresolvedReviewCount = attentionItems.filter((i) => i.kind === 'job_review').length

  return (
    <>
      <AccountHeader
        account={account}
        todaySucceededCount={counts.succeeded}
        todayPendingCount={counts.pending}
        unresolvedReviewCount={unresolvedReviewCount}
        todaySchedule={todaySchedule}
        togglingAccount={togglingAccount}
        onToggleAccount={(next) => void handleToggleAccount(next)}
        onOpenDrawer={onOpenDrawer}
      />

      <NeedsYouBanner
        items={attentionItems}
        onOpenDrawer={onOpenDrawer}
        onChanged={onChanged}
      />

      <div className="fc-toolbar">
        <PillTabs
          items={filterTabs}
          value={filter}
          onChange={setFilter}
          ariaLabel="筛选续火好友状态"
        />

        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
          <input
            type="search"
            className="fc-input fc-input-search"
            placeholder="搜索昵称或抖音号…"
            aria-label="搜索好友"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
          <button
            type="button"
            className="fc-btn fc-btn-secondary"
            style={{ height: 34 }}
            onClick={() => onOpenDrawer({ kind: 'create-target' })}
          >
            <PlusIcon width={14} height={14} /> 手动添加
          </button>
        </div>
      </div>

      <div className="fc-stage">
        {actionError && <div className="fc-alert is-error">{actionError}</div>}

        {activeTargets.length === 0 ? (
          <div className="fc-empty">
            <h2 style={{ fontSize: 16, fontWeight: 600 }}>当前账号还没有续火好友</h2>
            <p style={{ color: 'var(--muted)', maxWidth: 440 }}>
              推荐先点击右上角「扫描对账」自动读取抖音会话列表并勾选导入，也可以手动添加精确会话名称。
            </p>
            <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
              <button
                type="button"
                className="fc-btn fc-btn-primary"
                onClick={() => onOpenDrawer({ kind: 'scan-reconcile' })}
              >
                打开扫描对账
              </button>
              <button
                type="button"
                className="fc-btn fc-btn-secondary"
                onClick={() => onOpenDrawer({ kind: 'create-target' })}
              >
                手动添加好友
              </button>
            </div>
          </div>
        ) : filteredTargets.length === 0 ? (
          <div className="fc-empty">
            <div style={{ fontWeight: 600 }}>没有匹配当前筛选条件的好友</div>
            <p style={{ color: 'var(--muted)' }}>可以切换上方状态胶囊或清空搜索关键词。</p>
          </div>
        ) : (
          <div className="fc-paged-card" ref={cardRef}>
            <div className="fc-paged-body">
              <table className="fc-table" aria-label="当前账号续火好友列表">
                <thead>
                  <tr>
                    <th>好友 / 会话</th>
                    <th>官方火花快照（仅展示）</th>
                    <th>本地累计</th>
                    <th>绑定模板 · 时刻</th>
                    <th>今日状态</th>
                    <th style={{ textAlign: 'right' }}>启用</th>
                  </tr>
                </thead>
                <tbody>
                  {paged.pageItems.map((target) => {
                    const summary = dayMap.get(target.id)
                    const tpl = target.templateId ? templatesById.get(target.templateId) : null
                    const selected =
                      drawer.kind === 'target-detail' && drawer.targetId === target.id
                    const boundIdentity = Boolean(
                      target.platformUserId || target.platformConversationId,
                    )

                    return (
                      <tr
                        key={target.id}
                        className={`fc-row-clickable${selected ? ' is-selected' : ''}`}
                        tabIndex={0}
                        onClick={() =>
                          onOpenDrawer({
                            kind: 'target-detail',
                            targetId: target.id,
                            tab: 'config',
                          })
                        }
                        onKeyDown={(e) => {
                          if (e.target !== e.currentTarget) return
                          if (e.key === 'Enter' || e.key === ' ') {
                            e.preventDefault()
                            onOpenDrawer({
                              kind: 'target-detail',
                              targetId: target.id,
                              tab: 'config',
                            })
                          }
                        }}
                      >
                        <td>
                          <div style={{ display: 'flex', alignItems: 'center', gap: 6, lineHeight: '18px' }}>
                            <span style={{ fontWeight: 600, color: 'var(--ink)' }}>
                              {target.nicknameSnapshot}
                            </span>
                            {target.conversationType === 'group' && (
                              <span className="fc-nav-badge">群聊</span>
                            )}
                          </div>
                          <div style={{ fontSize: 11.5, lineHeight: '15px', color: 'var(--muted)', marginTop: 1 }}>
                            {target.platformHandle
                              ? `@${target.platformHandle}`
                              : boundIdentity
                                ? '已绑定稳定会话身份'
                                : '未绑定稳定 ID（可补采）'}
                          </div>
                        </td>

                        <td>
                          <div style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
                            <SparkChip
                              officialDays={target.sparkDays}
                              kind={target.sparkKind}
                              label={target.sparkLabel}
                              scannedAt={target.sparkScannedAt}
                            />
                            {target.sparkScannedAt && (
                              <span className="fc-mono" style={{ fontSize: 11, color: 'var(--muted-soft)' }}>
                                {formatDateTime(target.sparkScannedAt)}
                              </span>
                            )}
                          </div>
                        </td>

                        <td>
                          <span className="fc-mono" style={{ fontWeight: 600 }}>
                            {target.streakDays} 天
                          </span>
                        </td>

                        <td>
                          <div style={{ fontWeight: 500, lineHeight: '17px' }}>
                            {tpl ? (
                              <>
                                {tpl.name}
                                {!tpl.enabled && (
                                  <span style={{ color: 'var(--warn)', fontSize: 12 }}>（模板已停用）</span>
                                )}
                              </>
                            ) : (
                              <span style={{ color: 'var(--warn)' }}>未绑定模板</span>
                            )}
                          </div>
                          <div className="fc-mono" style={{ fontSize: 11.5, lineHeight: '15px', color: 'var(--muted)' }}>
                            {target.sendTimeHhmm ? `独立 ${target.sendTimeHhmm}` : '跟随账号'}
                          </div>
                        </td>

                        <td>
                          <StatusPill tone={summary?.todayTone ?? 'neutral'}>
                            {summary?.todayStatusLabel ?? '今日待发'}
                          </StatusPill>
                        </td>

                        <td style={{ textAlign: 'right' }}>
                          <Switch
                            checked={target.enabled}
                            disabled={busyTargetId === target.id}
                            stopPropagation
                            label={`启用好友 ${target.nicknameSnapshot}`}
                            onChange={(next) => void handleToggleTarget(target, next)}
                          />
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>

            {showPagination && (
              <PaginationBar
                page={paged.safePage}
                totalPages={paged.totalPages}
                totalItems={paged.total}
                rangeStart={paged.rangeStart}
                rangeEnd={paged.rangeEnd}
                onPageChange={setPage}
              />
            )}
          </div>
        )}
      </div>
    </>
  )
}
