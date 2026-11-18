import { useEffect, useMemo, useState } from 'react'
import { api, ApiError } from '../../api.ts'
import type { BackgroundTasksController } from '../../hooks/useBackgroundTasks.ts'
import { mergeAccountSendPreview, type TargetDaySummary } from '../../lib/workspace.ts'
import type { Account, ContractTarget, SendPreviewRead } from '../../types.ts'
import { DrawerShell } from '../ui/DrawerShell.tsx'
import { StatusPill } from '../ui/StatusPill.tsx'

export interface SendAccountDrawerProps {
  open: boolean
  account: Account | null
  targets: ContractTarget[]
  dayMap: Map<string, TargetDaySummary>
  tasks: BackgroundTasksController
  onClose: () => void
}

export function SendAccountDrawer({
  open,
  account,
  targets,
  dayMap,
  tasks,
  onClose,
}: SendAccountDrawerProps) {
  const [preview, setPreview] = useState<SendPreviewRead | null>(null)
  const [loading, setLoading] = useState(false)
  const [force, setForce] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!open || !account) return undefined
    let active = true
    setLoading(true)
    setError(null)
    setForce(false)
    api
      .sendPreview(account.id)
      .then((res) => {
        if (active) setPreview(res)
      })
      .catch((err) => {
        if (active) {
          setError(err instanceof ApiError ? err.message : '读取整号发送预览失败')
        }
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
  }, [open, account?.id])

  const accountTargets = useMemo(
    () => (account ? targets.filter((t) => t.accountId === account.id && t.deletedAt === null) : []),
    [account, targets],
  )

  const rows = useMemo(
    () => mergeAccountSendPreview(accountTargets, dayMap, preview, force),
    [accountTargets, dayMap, preview, force],
  )

  if (!open || !account) return null

  const willSendCount = rows.filter((r) => r.willSend).length
  const canStart = account.enabled && account.status === 'healthy' && willSendCount > 0 && !submitting

  async function handleStartSend() {
    if (!account || !canStart) return
    setSubmitting(true)
    // Start root-level send task; user may keep drawer open or close it freely
    void tasks.runAccountSend(account.id, force).finally(() => {
      setSubmitting(false)
    })
    onClose()
  }

  return (
    <DrawerShell
      open={open}
      title={`${account.displayName} · 今日群发预览`}
      subtitle="发送前逐位核对今日命中内容与幂等跳过原因"
      onClose={onClose}
      footer={
        <>
          <label style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 12.5, cursor: 'pointer' }}>
            <input
              type="checkbox"
              checked={force}
              onChange={(e) => setForce(e.target.checked)}
            />
            <span>强制重发（忽略今日已成功记录）</span>
          </label>
          <button
            type="button"
            className="fc-btn fc-btn-primary"
            disabled={!canStart}
            onClick={() => void handleStartSend()}
          >
            确认发送 ({willSendCount})
          </button>
        </>
      }
    >
      {!account.enabled && (
        <div className="fc-alert is-warn">当前抖音账号处于暂停状态，请先在顶部开关启用后再群发。</div>
      )}
      {account.status !== 'healthy' && (
        <div className="fc-alert is-error">当前抖音账号未处于健康在线态，请先完成扫码授权。</div>
      )}
      {force && (
        <div className="fc-alert is-warn">
          已开启强制重发：今日已成功发送过的好友也会重新收到一条消息；但存在待人工复核（unknown_requires_review）的好友仍受安全保护绝不重发。
        </div>
      )}
      {error && <div className="fc-alert is-error">{error}</div>}

      {loading ? (
        <div className="fc-hint">正在计算今日每位好友的渲染文案…</div>
      ) : rows.length === 0 ? (
        <div className="fc-empty">
          <div>当前账号暂无好友</div>
        </div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          {rows.map((row) => (
            <div key={row.target.id} className="fc-preview-stage" style={{ padding: '11px 13px' }}>
              <div className="fc-preview-head" style={{ marginBottom: 2 }}>
                <span style={{ color: 'var(--ink)', fontWeight: 600, fontSize: 13.5 }}>
                  {row.target.nicknameSnapshot}
                </span>
                <StatusPill tone={row.willSend ? 'ok' : row.tone}>
                  {row.willSend ? '本次将发送' : row.skipReason ?? '跳过'}
                </StatusPill>
              </div>
              {row.renderedMessage && (
                <div
                  style={{
                    fontSize: 13,
                    color: row.willSend ? 'var(--body)' : 'var(--muted)',
                  }}
                >
                  {row.renderedMessage}
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </DrawerShell>
  )
}
