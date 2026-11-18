import { useEffect, useState } from 'react'
import { api, ApiError } from '../../api.ts'
import type { Account, DryRunRead } from '../../types.ts'
import { DrawerShell } from '../ui/DrawerShell.tsx'
import { StatusPill } from '../ui/StatusPill.tsx'

export interface DryRunDrawerProps {
  open: boolean
  account: Account | null
  onClose: () => void
  onChanged: () => Promise<void> | void
}

export function DryRunDrawer({ open, account, onClose, onChanged }: DryRunDrawerProps) {
  const [result, setResult] = useState<DryRunRead | null>(null)
  const [running, setRunning] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    setResult(null)
    setError(null)
  }, [account?.id, open])

  if (!open || !account) return null

  async function handleRun() {
    if (!account || running) return
    setRunning(true)
    setError(null)
    try {
      const res = await api.dryRun(account.id)
      setResult(res)
      await onChanged()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '演练检查执行失败')
    } finally {
      setRunning(false)
    }
  }

  return (
    <DrawerShell
      open={open}
      title={`${account.displayName} · Dry Run 演练检查`}
      subtitle="只打开会话列表核对每位好友能否被唯一匹配，绝不发送任何消息"
      onClose={onClose}
      footer={
        <>
          <span className="fc-hint">演练不会改变累计天数或幂等锁</span>
          <button
            type="button"
            className="fc-btn fc-btn-primary"
            disabled={running || account.status !== 'healthy'}
            onClick={() => void handleRun()}
          >
            {running ? '正在核对会话…' : result ? '重新执行演练' : '开始演练检查'}
          </button>
        </>
      }
    >
      {account.status !== 'healthy' && (
        <div className="fc-alert is-warn">当前账号未处于健康在线状态，请先完成扫码登录。</div>
      )}
      {error && <div className="fc-alert is-error">{error}</div>}

      {!result ? (
        <div className="fc-empty">
          <div style={{ fontWeight: 600 }}>准备执行会话定位校验</div>
          <p className="fc-hint">
            点击右下角「开始演练检查」，后台将打开当前账号会话页逐个验证启用中的好友是否可见且无重名歧义。
          </p>
        </div>
      ) : (
        <>
          <div className={`fc-alert ${result.overall === 'ok' ? 'is-ok' : 'is-warn'}`}>
            演练结论：{result.overall === 'ok' ? '全部正常可读' : (result.detail ?? result.overall)}
          </div>

          <div className="fc-kv-list">
            {result.targets.map((t, i) => {
              const tone =
                t.status === 'found' ? 'ok' : t.status === 'ambiguous' ? 'warn' : 'err'
              const label =
                t.status === 'found' ? '唯一命中' : t.status === 'ambiguous' ? '同名歧义' : '未找到'
              return (
                <div key={`${t.target}-${i}`} className="fc-kv-row">
                  <div>
                    <div style={{ fontWeight: 600 }}>{t.target}</div>
                    {t.detail && <div className="fc-hint">{t.detail}</div>}
                  </div>
                  <StatusPill tone={tone}>{label}</StatusPill>
                </div>
              )
            })}
          </div>
        </>
      )}
    </DrawerShell>
  )
}
