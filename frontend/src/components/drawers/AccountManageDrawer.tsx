import { useEffect, useState, type FormEvent } from 'react'
import { api, ApiError } from '../../api.ts'
import { useQrLogin } from '../../hooks/useQrLogin.ts'
import { ACCOUNT_STATUS_LABELS, ACCOUNT_STATUS_LIGHT_TONES } from '../../lib/format.ts'
import type { AccountScheduleMode } from '../../lib/workspace.ts'
import type { Account } from '../../types.ts'
import { DrawerShell } from '../ui/DrawerShell.tsx'
import { AccountScheduleTimeField } from '../ui/ScheduleTimeControl.tsx'
import { StatusPill } from '../ui/StatusPill.tsx'

export interface AccountManageDrawerProps {
  open: boolean
  account: Account | null
  onClose: () => void
  onChanged: () => Promise<void> | void
  onDeleted: () => Promise<void> | void
}

const LOGIN_STATE_TEXT: Record<string, string> = {
  idle: '未启动',
  preparing: '正在准备登录二维码（约需数秒）…',
  waiting_scan: '请打开抖音 App 扫描下方二维码',
  verification_required: '扫码成功，平台要求进一步身份验证',
  succeeded: '登录授权成功',
  failed: '登录失败',
  expired: '二维码已过期，请重新获取',
}

export function AccountManageDrawer({
  open,
  account,
  onClose,
  onChanged,
  onDeleted,
}: AccountManageDrawerProps) {
  const [displayName, setDisplayName] = useState('')
  const [scheduleMode, setScheduleMode] = useState<AccountScheduleMode>('window')
  const [windowStart, setWindowStart] = useState('08:30')
  const [windowEnd, setWindowEnd] = useState('22:30')
  const [fixedTimeDraft, setFixedTimeDraft] = useState('20:30')
  const [saving, setSaving] = useState(false)
  const [saveNotice, setSaveNotice] = useState<string | null>(null)
  const [saveError, setSaveError] = useState<string | null>(null)

  const [smsCode, setSmsCode] = useState('')
  const [showWindowLogin, setShowWindowLogin] = useState(false)
  const [showDanger, setShowDanger] = useState(false)

  const qr = useQrLogin(account?.id ?? null, open, onChanged)

  useEffect(() => {
    if (!account) return
    setDisplayName(account.displayName)
    setScheduleMode(account.sendTimeHhmm?.trim() ? 'fixed' : 'window')
    setWindowStart(account.sendWindowStart)
    setWindowEnd(account.sendWindowEnd)
    setFixedTimeDraft(account.sendTimeHhmm?.trim() || '20:30')
    setSaveNotice(null)
    setSaveError(null)
    setSmsCode('')
  }, [account?.id, account?.displayName, account?.sendWindowStart, account?.sendWindowEnd, account?.sendTimeHhmm])

  if (!open || !account) return null

  const qrRunning =
    qr.qrStatus?.state === 'preparing' ||
    qr.qrStatus?.state === 'waiting_scan' ||
    qr.qrStatus?.state === 'verification_required'

  const winRunning =
    qr.windowStatus?.state === 'preparing' ||
    qr.windowStatus?.state === 'waiting_scan' ||
    qr.windowStatus?.state === 'verification_required'

  async function handleSaveSettings(e: FormEvent) {
    e.preventDefault()
    if (!account || !displayName.trim() || saving) return
    setSaving(true)
    setSaveError(null)
    setSaveNotice(null)
    try {
      await api.patchAccount(account.id, {
        displayName: displayName.trim(),
        sendWindowStart: windowStart,
        sendWindowEnd: windowEnd,
        sendTimeHhmm: scheduleMode === 'fixed' ? (fixedTimeDraft.trim() || '20:30') : null,
      })
      setSaveNotice('账号参数已保存')
      await onChanged()
    } catch (err) {
      setSaveError(err instanceof ApiError ? err.message : '保存账号设置失败')
    } finally {
      setSaving(false)
    }
  }

  async function handleDelete() {
    if (!account) return
    if (
      !window.confirm(
        `确定彻底删除抖音账号「${account.displayName}」及其关联配置？此操作不可撤销。`,
      )
    ) {
      return
    }
    try {
      await api.deleteAccount(account.id)
      onClose()
      await onDeleted()
    } catch (err) {
      setSaveError(err instanceof ApiError ? err.message : '删除账号失败')
    }
  }

  return (
    <DrawerShell
      open={open}
      title={`${account.displayName} · 扫码与配置`}
      subtitle={`当前状态：${ACCOUNT_STATUS_LABELS[account.status]}`}
      onClose={onClose}
    >
      {qr.error && <div className="fc-alert is-error">{qr.error}</div>}
      {saveError && <div className="fc-alert is-error">{saveError}</div>}
      {saveNotice && <div className="fc-alert is-ok">{saveNotice}</div>}

      {/* QR Login Stage */}
      <div className="fc-card" style={{ padding: 15 }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
          <div>
            <div style={{ fontWeight: 600, fontSize: 14 }}>网页内截图扫码授权</div>
            <div className="fc-hint">无需切换桌面窗口，扫码及风控短信验证均可在此完成</div>
          </div>
          <StatusPill tone={ACCOUNT_STATUS_LIGHT_TONES[account.status]} dot>
            {ACCOUNT_STATUS_LABELS[account.status]}
          </StatusPill>
        </div>

        {qr.qrStatus && (
          <div className="fc-preview-stage" style={{ alignItems: 'center', textAlign: 'center' }}>
            <div style={{ fontWeight: 600, fontSize: 13 }}>
              {LOGIN_STATE_TEXT[qr.qrStatus.state] ?? qr.qrStatus.state}
            </div>
            {qr.qrStatus.detail && <div className="fc-hint">{qr.qrStatus.detail}</div>}

            {qr.qrBlobUrl && (
              <img
                src={qr.qrBlobUrl}
                alt="抖音登录二维码截图"
                style={{
                  width: 196,
                  height: 196,
                  objectFit: 'contain',
                  background: '#ffffff',
                  border: '1px solid var(--hairline)',
                  borderRadius: 10,
                  padding: 6,
                }}
              />
            )}

            {qr.qrStatus.state === 'verification_required' && (
              <div style={{ width: '100%', display: 'flex', flexDirection: 'column', gap: 8, textAlign: 'left', marginTop: 4 }}>
                {qr.verifyAutoOption ? (
                  <div className="fc-hint">已自动触发「{qr.verifyAutoOption}」，请填写收到的验证码：</div>
                ) : (
                  qr.verifyOptions.length > 0 && (
                    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                      {qr.verifyOptions.map((opt) => (
                        <button
                          key={opt}
                          type="button"
                          className="fc-btn fc-btn-secondary fc-btn-sm"
                          disabled={qr.verifyBusy}
                          onClick={() => void qr.selectVerifyOption(opt)}
                        >
                          {opt}
                        </button>
                      ))}
                    </div>
                  )
                )}

                <div style={{ display: 'flex', gap: 6 }}>
                  <input
                    className="fc-input fc-mono"
                    placeholder="输入短信验证码"
                    value={smsCode}
                    onChange={(e) => setSmsCode(e.target.value)}
                  />
                  <button
                    type="button"
                    className="fc-btn fc-btn-primary"
                    disabled={qr.verifyBusy || !smsCode.trim()}
                    onClick={async () => {
                      const ok = await qr.submitVerifyCode(smsCode)
                      if (ok) setSmsCode('')
                    }}
                  >
                    提交
                  </button>
                </div>

                {qr.verifyFeedback && <div className="fc-hint">{qr.verifyFeedback}</div>}
              </div>
            )}
          </div>
        )}

        <div style={{ display: 'flex', gap: 8, justifyContent: 'space-between', alignItems: 'center' }}>
          <button
            type="button"
            className="fc-btn fc-btn-ghost fc-btn-sm"
            style={{ paddingLeft: 0 }}
            onClick={() => setShowWindowLogin((v) => !v)}
          >
            {showWindowLogin ? '收起本机窗口登录' : '改用本机浏览器窗口登录'}
          </button>

          <div style={{ display: 'flex', gap: 8 }}>
            {(qrRunning || winRunning) && (
              <button
                type="button"
                className="fc-btn fc-btn-secondary fc-btn-sm"
                onClick={() => void qr.cancelAllLogin()}
              >
                取消扫码
              </button>
            )}
            <button
              type="button"
              className="fc-btn fc-btn-primary fc-btn-sm"
              disabled={qrRunning}
              onClick={() => void qr.startQr()}
            >
              {qrRunning ? '等待扫码中…' : '获取登录二维码'}
            </button>
          </div>
        </div>

        {showWindowLogin && (
          <div style={{ paddingTop: 10, borderTop: '1px solid var(--hairline-soft)', display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
            <span className="fc-hint">
              {qr.windowStatus?.detail ?? '在本机桌面直接弹出受控 Chromium 窗口完成登录'}
            </span>
            <button
              type="button"
              className="fc-btn fc-btn-secondary fc-btn-sm"
              disabled={winRunning}
              onClick={() => void qr.startWindow()}
            >
              {winRunning ? '窗口等待中…' : '打开登录窗口'}
            </button>
          </div>
        )}
      </div>

      {/* Account Window Settings */}
      <form
        onSubmit={(e) => void handleSaveSettings(e)}
        className="fc-card"
        style={{ padding: 15, gap: 12 }}
      >
        <div style={{ fontWeight: 600, fontSize: 14 }}>账号名称与发送时段</div>

        <div className="fc-field">
          <label className="fc-label" htmlFor="am-name">
            显示名称
          </label>
          <input
            id="am-name"
            className="fc-input"
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            required
          />
        </div>

        <AccountScheduleTimeField
          idPrefix="am"
          mode={scheduleMode}
          windowStart={windowStart}
          windowEnd={windowEnd}
          fixedTime={fixedTimeDraft}
          onModeChange={setScheduleMode}
          onWindowChange={(nextStart, nextEnd) => {
            setWindowStart(nextStart)
            setWindowEnd(nextEnd)
          }}
          onFixedTimeChange={setFixedTimeDraft}
        />

        <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
          <button type="submit" className="fc-btn fc-btn-primary fc-btn-sm" disabled={saving}>
            {saving ? '保存中…' : '保存设置'}
          </button>
        </div>
      </form>

      {/* Danger Zone */}
      <div>
        <button
          type="button"
          className="fc-btn fc-btn-ghost fc-btn-sm"
          style={{ color: 'var(--muted)' }}
          onClick={() => setShowDanger((v) => !v)}
        >
          {showDanger ? '收起高级危险操作' : '展开删除账号选项…'}
        </button>
        {showDanger && (
          <div className="fc-card" style={{ marginTop: 8, padding: 14, borderColor: 'var(--err-border)' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12 }}>
              <span className="fc-hint">永久移除该抖音账号及本地会话关联数据</span>
              <button
                type="button"
                className="fc-btn fc-btn-danger fc-btn-sm"
                onClick={() => void handleDelete()}
              >
                删除账号
              </button>
            </div>
          </div>
        )}
      </div>
    </DrawerShell>
  )
}
