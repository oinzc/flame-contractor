import { useState, type FormEvent } from 'react'
import { api, ApiError } from '../../api.ts'
import type { Account, MessageTemplate } from '../../types.ts'
import { DrawerShell } from '../ui/DrawerShell.tsx'

export interface CreateEntityDrawerProps {
  mode: 'create-account' | 'create-target' | null
  selectedAccount: Account | null
  templates: MessageTemplate[]
  onClose: () => void
  onAccountCreated: (account: Account) => Promise<void> | void
  onTargetCreated: () => Promise<void> | void
}

const DEFAULT_TZ =
  Intl.DateTimeFormat().resolvedOptions().timeZone || 'Asia/Shanghai'

export function CreateEntityDrawer({
  mode,
  selectedAccount,
  templates,
  onClose,
  onAccountCreated,
  onTargetCreated,
}: CreateEntityDrawerProps) {
  // Account form state
  const [displayName, setDisplayName] = useState('')
  const [timezone, setTimezone] = useState(DEFAULT_TZ)
  const [windowStart, setWindowStart] = useState('08:30')
  const [windowEnd, setWindowEnd] = useState('22:30')
  const [sendTimeHhmm, setSendTimeHhmm] = useState('')

  // Target form state
  const [nickname, setNickname] = useState('')
  const [conversationType, setConversationType] = useState<'user' | 'group'>('user')
  const [platformHandle, setPlatformHandle] = useState('')
  const [templateId, setTemplateId] = useState('')
  const [streakDays, setStreakDays] = useState('0')

  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  if (!mode) return null

  async function handleCreateAccount(e: FormEvent) {
    e.preventDefault()
    if (!displayName.trim() || submitting) return
    setSubmitting(true)
    setError(null)
    try {
      const created = await api.createAccount({
        displayName: displayName.trim(),
        timezone: timezone.trim() || 'Asia/Shanghai',
        sendWindowStart: windowStart,
        sendWindowEnd: windowEnd,
        sendTimeHhmm: sendTimeHhmm.trim() || null,
      })
      setDisplayName('')
      await onAccountCreated(created)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '创建抖音账号失败')
    } finally {
      setSubmitting(false)
    }
  }

  async function handleCreateTarget(e: FormEvent) {
    e.preventDefault()
    if (!selectedAccount || !nickname.trim() || submitting) return
    setSubmitting(true)
    setError(null)
    try {
      await api.createTarget({
        accountId: selectedAccount.id,
        nicknameSnapshot: nickname.trim(),
        conversationType,
        platformHandle: platformHandle.trim() || undefined,
        templateId: templateId || undefined,
        streakDays: Math.max(0, Number.parseInt(streakDays || '0', 10) || 0),
      })
      setNickname('')
      setPlatformHandle('')
      setStreakDays('0')
      onClose()
      await onTargetCreated()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '手动添加好友失败')
    } finally {
      setSubmitting(false)
    }
  }

  if (mode === 'create-account') {
    return (
      <DrawerShell
        open
        title="新增抖音账号"
        subtitle="创建配置后将自动切换至该账号并打开二维码授权"
        onClose={onClose}
        footer={
          <>
            <button type="button" className="fc-btn fc-btn-secondary" onClick={onClose}>
              取消
            </button>
            <button
              type="submit"
              form="fc-create-account-form"
              className="fc-btn fc-btn-primary"
              disabled={submitting || !displayName.trim()}
            >
              {submitting ? '创建中…' : '创建并去扫码'}
            </button>
          </>
        }
      >
        {error && <div className="fc-alert is-error">{error}</div>}
        <form
          id="fc-create-account-form"
          onSubmit={(e) => void handleCreateAccount(e)}
          style={{ display: 'flex', flexDirection: 'column', gap: 14 }}
        >
          <div className="fc-field">
            <label className="fc-label" htmlFor="ca-name">
              账号备注名称
            </label>
            <input
              id="ca-name"
              className="fc-input"
              autoFocus
              maxLength={48}
              placeholder="例如：日常大号 / 同学小号"
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
              required
            />
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
            <div className="fc-field">
              <label className="fc-label" htmlFor="ca-start">
                窗口开始
              </label>
              <input
                id="ca-start"
                type="time"
                className="fc-input fc-mono"
                value={windowStart}
                onChange={(e) => setWindowStart(e.target.value)}
                required
              />
            </div>
            <div className="fc-field">
              <label className="fc-label" htmlFor="ca-end">
                窗口结束
              </label>
              <input
                id="ca-end"
                type="time"
                className="fc-input fc-mono"
                value={windowEnd}
                onChange={(e) => setWindowEnd(e.target.value)}
                required
              />
            </div>
          </div>

          <div className="fc-field">
            <label className="fc-label" htmlFor="ca-fixed">
              <span>固定发送时刻</span>
              <span className="fc-hint">留空则在发送窗口内随机取时</span>
            </label>
            <input
              id="ca-fixed"
              type="time"
              className="fc-input fc-mono"
              value={sendTimeHhmm}
              onChange={(e) => setSendTimeHhmm(e.target.value)}
            />
          </div>

          <div className="fc-field">
            <label className="fc-label" htmlFor="ca-tz">
              时区
            </label>
            <input
              id="ca-tz"
              className="fc-input fc-mono"
              value={timezone}
              onChange={(e) => setTimezone(e.target.value)}
              required
            />
          </div>
        </form>
      </DrawerShell>
    )
  }

  return (
    <DrawerShell
      open
      title={`手动添加好友 · ${selectedAccount?.displayName ?? ''}`}
      subtitle="名称须与抖音会话列表标题完全一致（更推荐通过「扫描对账」直接勾选导入）"
      onClose={onClose}
      footer={
        <>
          <button type="button" className="fc-btn fc-btn-secondary" onClick={onClose}>
            取消
          </button>
          <button
            type="submit"
            form="fc-create-target-form"
            className="fc-btn fc-btn-primary"
            disabled={submitting || !nickname.trim() || !selectedAccount}
          >
            {submitting ? '添加中…' : '保存好友'}
          </button>
        </>
      }
    >
      {error && <div className="fc-alert is-error">{error}</div>}
      <form
        id="fc-create-target-form"
        onSubmit={(e) => void handleCreateTarget(e)}
        style={{ display: 'flex', flexDirection: 'column', gap: 14 }}
      >
        <div className="fc-field">
          <label className="fc-label" htmlFor="ct-nick">
            抖音会话显示名称（备注名或昵称）
          </label>
          <input
            id="ct-nick"
            className="fc-input"
            autoFocus
            maxLength={80}
            value={nickname}
            onChange={(e) => setNickname(e.target.value)}
            required
          />
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
          <div className="fc-field">
            <label className="fc-label" htmlFor="ct-type">
              会话类型
            </label>
            <select
              id="ct-type"
              className="fc-select"
              value={conversationType}
              onChange={(e) => setConversationType(e.target.value as 'user' | 'group')}
            >
              <option value="user">单人好友</option>
              <option value="group">群聊会话</option>
            </select>
          </div>
          <div className="fc-field">
            <label className="fc-label" htmlFor="ct-streak">
              初始本地天数
            </label>
            <input
              id="ct-streak"
              type="number"
              min={0}
              className="fc-input fc-mono"
              value={streakDays}
              onChange={(e) => setStreakDays(e.target.value)}
            />
          </div>
        </div>

        <div className="fc-field">
          <label className="fc-label" htmlFor="ct-handle">
            <span>公开抖音号</span>
            <span className="fc-hint">可选</span>
          </label>
          <input
            id="ct-handle"
            className="fc-input"
            value={platformHandle}
            onChange={(e) => setPlatformHandle(e.target.value)}
          />
        </div>

        <div className="fc-field">
          <label className="fc-label" htmlFor="ct-tpl">
            绑定消息模板
          </label>
          <select
            id="ct-tpl"
            className="fc-select"
            value={templateId}
            onChange={(e) => setTemplateId(e.target.value)}
          >
            <option value="">— 暂不绑定 —</option>
            {templates.map((t) => (
              <option key={t.id} value={t.id}>
                {t.name}
              </option>
            ))}
          </select>
        </div>
      </form>
    </DrawerShell>
  )
}
