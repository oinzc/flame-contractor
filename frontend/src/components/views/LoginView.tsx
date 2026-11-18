import { useEffect, useState, type FormEvent } from 'react'
import { api, ApiError, setToken } from '../../api.ts'
import type { ThemePreference } from '../../lib/theme.ts'
import { FlameIcon } from '../ui/Icons.tsx'
import { ThemeSwitcher } from '../ui/ThemeSwitcher.tsx'

export interface LoginViewProps {
  themePreference: ThemePreference
  onChangeTheme: (next: ThemePreference) => void
  onAuthed: () => void
}

export function LoginView({
  themePreference,
  onChangeTheme,
  onAuthed,
}: LoginViewProps) {
  const [needsSetup, setNeedsSetup] = useState<boolean | null>(null)
  const [username, setUsername] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [password, setPassword] = useState('')
  const [passwordConfirm, setPasswordConfirm] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const [recoveryOpen, setRecoveryOpen] = useState(false)
  const [recoveryNotice, setRecoveryNotice] = useState<string | null>(null)
  const [recoveryBusy, setRecoveryBusy] = useState(false)

  useEffect(() => {
    let active = true
    api
      .authSetupStatus()
      .then((res) => {
        if (active) setNeedsSetup(res.needsSetup)
      })
      .catch((err) => {
        if (active) {
          setError(err instanceof ApiError ? err.message : '无法连接本机控制面服务')
        }
      })
    return () => {
      active = false
    }
  }, [])

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (needsSetup === null || submitting) return
    setError(null)

    const cleanUser = username.trim()
    if (!cleanUser || !password) {
      setError('请输入用户名和密码')
      return
    }
    if (needsSetup && password.length < 8) {
      setError('管理员密码至少需要 8 个字符')
      return
    }
    if (needsSetup && password !== passwordConfirm) {
      setError('两次输入的密码不一致')
      return
    }

    setSubmitting(true)
    try {
      const auth = needsSetup
        ? await api.authInitialize({
            username: cleanUser,
            password,
            displayName: displayName.trim() || undefined,
          })
        : await api.authLogin({ username: cleanUser, password })
      setToken(auth.token)
      onAuthed()
    } catch (err) {
      if (needsSetup && err instanceof ApiError && err.status === 409) {
        setNeedsSetup(false)
        setPassword('')
        setPasswordConfirm('')
      }
      setError(err instanceof ApiError ? err.message : '登录操作失败，请重试')
    } finally {
      setSubmitting(false)
    }
  }

  async function handleRecovery() {
    if (!username.trim()) {
      setError('请先填写用户名再获取本机恢复说明')
      return
    }
    setRecoveryBusy(true)
    setError(null)
    try {
      const res = await api.authRecoverPassword({ username: username.trim() })
      setRecoveryNotice(res.message)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '无法获取恢复指引')
    } finally {
      setRecoveryBusy(false)
    }
  }

  return (
    <main className="fc-auth-shell">
      <section className="fc-auth-card" aria-labelledby="auth-heading">
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8, flexWrap: 'wrap' }}>
          <div className="fc-auth-brand">
            <span className="fc-auth-mark">
              <FlameIcon width={18} height={18} />
            </span>
            <div>
              <div style={{ fontWeight: 600, fontSize: 15, letterSpacing: '-0.01em' }}>
                Flame Contractor
              </div>
              <div style={{ fontSize: 12, color: 'var(--muted)' }}>本机续火工作台</div>
            </div>
          </div>
          <ThemeSwitcher value={themePreference} onChange={onChangeTheme} />
        </div>

        <div>
          <h1 id="auth-heading" style={{ fontSize: 20, fontWeight: 600, letterSpacing: '-0.02em' }}>
            {needsSetup === null ? '正在连接控制面…' : needsSetup ? '初始化本机管理员' : '登录控制台'}
          </h1>
          <p style={{ fontSize: 13, color: 'var(--muted)', marginTop: 4 }}>
            {needsSetup
              ? '首次启动，请创建用于访问本机续火工作台的管理员账号。'
              : '进入单账号续火工作台，查看今日待发与复核状态。'}
          </p>
        </div>

        {error && <div className="fc-alert is-error">{error}</div>}
        {recoveryNotice && <div className="fc-alert is-ok">{recoveryNotice}</div>}

        <form onSubmit={(e) => void handleSubmit(e)} style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          <div className="fc-field">
            <label className="fc-label" htmlFor="auth-username">
              用户名
            </label>
            <input
              id="auth-username"
              className="fc-input"
              autoComplete="username"
              autoFocus
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              disabled={needsSetup === null || submitting}
              required
            />
          </div>

          {needsSetup && (
            <div className="fc-field">
              <label className="fc-label" htmlFor="auth-display-name">
                <span>显示名称</span>
                <span className="fc-hint">可选</span>
              </label>
              <input
                id="auth-display-name"
                className="fc-input"
                value={displayName}
                onChange={(e) => setDisplayName(e.target.value)}
                disabled={submitting}
              />
            </div>
          )}

          <div className="fc-field">
            <label className="fc-label" htmlFor="auth-password">
              <span>密码</span>
              <button
                type="button"
                className="fc-btn fc-btn-ghost fc-btn-sm"
                style={{ height: 22, padding: '0 6px' }}
                onClick={() => setShowPassword((v) => !v)}
              >
                {showPassword ? '隐藏' : '显示'}
              </button>
            </label>
            <input
              id="auth-password"
              className="fc-input"
              type={showPassword ? 'text' : 'password'}
              autoComplete={needsSetup ? 'new-password' : 'current-password'}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              disabled={needsSetup === null || submitting}
              required
            />
          </div>

          {needsSetup && (
            <div className="fc-field">
              <label className="fc-label" htmlFor="auth-password-confirm">
                确认密码（至少 8 位）
              </label>
              <input
                id="auth-password-confirm"
                className="fc-input"
                type={showPassword ? 'text' : 'password'}
                autoComplete="new-password"
                value={passwordConfirm}
                onChange={(e) => setPasswordConfirm(e.target.value)}
                disabled={submitting}
                required
              />
            </div>
          )}

          <button
            type="submit"
            className="fc-btn fc-btn-primary"
            style={{ width: '100%', marginTop: 4 }}
            disabled={needsSetup === null || submitting}
          >
            {submitting ? '正在验证…' : needsSetup ? '创建管理员并进入' : '登录'}
          </button>
        </form>

        {needsSetup === false && (
          <div>
            <button
              type="button"
              className="fc-btn fc-btn-ghost fc-btn-sm"
              style={{ paddingLeft: 0 }}
              onClick={() => setRecoveryOpen((v) => !v)}
            >
              忘记控制台密码？
            </button>
            {recoveryOpen && (
              <div style={{ marginTop: 8, display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
                <span className="fc-hint">本机部署不走外部邮件，可获取命令行重置提示。</span>
                <button
                  type="button"
                  className="fc-btn fc-btn-secondary fc-btn-sm"
                  disabled={recoveryBusy}
                  onClick={() => void handleRecovery()}
                >
                  {recoveryBusy ? '读取中…' : '获取恢复说明'}
                </button>
              </div>
            )}
          </div>
        )}

        <div className="fc-auth-trust">
          <span>· 仅监听 127.0.0.1</span>
          <span>· 数据存本机 SQLite</span>
          <span>· 待复核不自动重发</span>
        </div>
      </section>
    </main>
  )
}
