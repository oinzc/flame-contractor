import { useState } from 'react'
import type { BackgroundTasksController } from '../../hooks/useBackgroundTasks.ts'
import type { Account } from '../../types.ts'
import { CloseIcon } from '../ui/Icons.tsx'

export interface BackgroundTaskStackProps {
  accounts: Account[]
  tasks: BackgroundTasksController
  onInspectScan: (accountId: string) => void
}

function ratio(completed: number, total: number | null): number {
  if (!total || total <= 0) return completed > 0 ? 0.45 : 0.15
  return Math.min(1, Math.max(0, completed / total))
}

export function BackgroundTaskStack({
  accounts,
  tasks,
  onInspectScan,
}: BackgroundTaskStackProps) {
  const [expandedKey, setExpandedKey] = useState<string | null>(null)
  const accountName = (id: string) => accounts.find((a) => a.id === id)?.displayName ?? '账号'

  const cards: {
    key: 'scan' | 'autoScan' | 'identity' | 'send'
    title: string
    percentText: string
    scale: number
    detail: string
    isError: boolean
    canCancel: boolean
    onCancel?: () => void
    onInspect?: () => void
  }[] = []

  if (tasks.scanVisible && tasks.scanTask) {
    const s = tasks.scanTask
    const pct = s.total ? `${Math.round(ratio(s.completed, s.total) * 100)}%` : `${s.completed} 项`
    cards.push({
      key: 'scan',
      title: `会话扫描 · ${accountName(s.accountId)}`,
      percentText: s.status === 'running' ? pct : s.status === 'completed' ? '已完成' : '需查看',
      scale: s.status === 'completed' ? 1 : ratio(s.completed, s.total),
      detail: s.syncError ?? s.pollError ?? s.currentItem ?? s.stage,
      isError: s.status === 'failed' || s.syncStatus === 'failed',
      canCancel: s.status === 'running',
      onCancel: () => void tasks.cancelManualScan(),
      onInspect: () => onInspectScan(s.accountId),
    })
  }

  if (tasks.autoScanVisible && tasks.autoScanTask) {
    const s = tasks.autoScanTask
    cards.push({
      key: 'autoScan',
      title: `自动同步 · ${accountName(s.accountId)}`,
      percentText: s.status === 'running' ? '进行中' : '已完成',
      scale: s.status === 'completed' ? 1 : ratio(s.completed, s.total),
      detail: s.currentItem ?? s.stage,
      isError: s.status === 'failed',
      canCancel: false,
    })
  }

  if (tasks.identityVisible && tasks.identityTask) {
    const idTask = tasks.identityTask
    cards.push({
      key: 'identity',
      title: `身份补采 · ${idTask.completed}/${idTask.total}`,
      percentText: idTask.status === 'running' ? '采集中' : '结束',
      scale: ratio(idTask.completed, idTask.total),
      detail: idTask.currentItem ?? idTask.stage,
      isError: idTask.status === 'failed',
      canCancel: idTask.status === 'running',
      onCancel: () => void tasks.cancelIdentityHydration(),
    })
  }

  if (tasks.sendVisible && tasks.sendTask) {
    const st = tasks.sendTask
    const prog = st.progress
    cards.push({
      key: 'send',
      title:
        st.mode === 'target'
          ? `单发 · ${st.targetName ?? ''}`
          : `群发队列 · ${accountName(st.accountId)}`,
      percentText:
        st.status === 'running'
          ? prog
            ? `${prog.completed}/${prog.total || 1}`
            : '发送中'
          : st.status === 'completed'
            ? '完成'
            : '需查看',
      scale: st.status === 'completed' ? 1 : ratio(prog?.completed ?? 0, prog?.total ?? 1),
      detail: st.error ?? prog?.currentTarget ?? prog?.stage ?? '已完成投递流程',
      isError: st.status === 'failed',
      canCancel: false,
    })
  }

  if (cards.length === 0) return null

  return (
    <div className="fc-bg-stack" aria-live="polite">
      {cards.map((card) => {
        const expanded = expandedKey === card.key
        return (
          <div
            key={card.key}
            className={`fc-bg-card${card.isError ? ' is-error' : ''}`}
          >
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                gap: 6,
                cursor: 'pointer',
              }}
              onClick={() => setExpandedKey(expanded ? null : card.key)}
            >
              <span style={{ fontSize: 12, fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {card.title}
              </span>
              <div style={{ display: 'flex', alignItems: 'center', gap: 4, flexShrink: 0 }}>
                <span className="fc-mono" style={{ fontSize: 11, color: 'var(--muted)' }}>
                  {card.percentText}
                </span>
                {!card.canCancel && (
                  <button
                    type="button"
                    className="fc-btn fc-btn-ghost fc-btn-icon"
                    style={{ width: 20, height: 20 }}
                    aria-label="关闭任务提示"
                    onClick={(e) => {
                      e.stopPropagation()
                      tasks.dismissTask(card.key)
                    }}
                  >
                    <CloseIcon width={12} height={12} />
                  </button>
                )}
              </div>
            </div>

            <div className="fc-bg-bar">
              <div
                className="fc-bg-fill"
                style={{ transform: `scaleX(${card.scale})` }}
              />
            </div>

            {expanded && (
              <div style={{ marginTop: 8, fontSize: 11.5, color: 'var(--muted)', display: 'flex', flexDirection: 'column', gap: 6 }}>
                <div>{card.detail}</div>
                <div style={{ display: 'flex', gap: 6 }}>
                  {card.onInspect && (
                    <button
                      type="button"
                      className="fc-btn fc-btn-secondary fc-btn-sm"
                      style={{ height: 24, fontSize: 11.5 }}
                      onClick={card.onInspect}
                    >
                      打开对账
                    </button>
                  )}
                  {card.canCancel && card.onCancel && (
                    <button
                      type="button"
                      className="fc-btn fc-btn-secondary fc-btn-sm"
                      style={{ height: 24, fontSize: 11.5 }}
                      onClick={card.onCancel}
                    >
                      取消
                    </button>
                  )}
                </div>
              </div>
            )}
          </div>
        )
      })}
    </div>
  )
}
