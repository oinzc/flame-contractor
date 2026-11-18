import { useEffect, useState, type FormEvent } from 'react'
import { api, ApiError } from '../../api.ts'
import {
  SCHEDULE_STATUS_LABELS,
  SCHEDULE_STATUS_LIGHT_TONES,
  formatDateTime,
} from '../../lib/format.ts'
import { parseSchedulerSettingsForm } from '../../lib/workspace.ts'
import type { ResolvedTheme, ThemePreference } from '../../lib/theme.ts'
import type { Account, AuthSessionRead, SchedulerStatus, User } from '../../types.ts'
import { DownloadIcon } from '../ui/Icons.tsx'
import { StatusPill } from '../ui/StatusPill.tsx'
import { Switch } from '../ui/Switch.tsx'
import { ThemeSwitcher } from '../ui/ThemeSwitcher.tsx'

export interface SettingsViewProps {
  user: User
  accounts: Account[]
  schedulerStatus: SchedulerStatus | null
  themePreference: ThemePreference
  resolvedTheme: ResolvedTheme
  onChangeTheme: (next: ThemePreference) => void
  onChanged: () => Promise<void> | void
  onLogout: () => void
}

export function SettingsView({
  user,
  accounts,
  schedulerStatus,
  themePreference,
  resolvedTheme,
  onChangeTheme,
  onChanged,
  onLogout,
}: SettingsViewProps) {
  // Scheduler form
  const [scanInterval, setScanInterval] = useState('6')
  const [graceMinutes, setGraceMinutes] = useState('30')
  const [alwaysSend, setAlwaysSend] = useState(false)
  const [savingScheduler, setSavingScheduler] = useState(false)
  const [togglingPause, setTogglingPause] = useState(false)

  // Profile & password
  const [displayName, setDisplayName] = useState(user.displayName ?? '')
  const [email, setEmail] = useState(user.email ?? '')
  const [pwCurrent, setPwCurrent] = useState('')
  const [pwNew, setPwNew] = useState('')
  const [pwConfirm, setPwConfirm] = useState('')
  const [sessions, setSessions] = useState<AuthSessionRead[]>([])

  // Superuser admin
  const [adminUsers, setAdminUsers] = useState<User[]>([])
  const [newUsername, setNewUsername] = useState('')
  const [newUserPw, setNewUserPw] = useState('')
  const [newUserSuper, setNewUserSuper] = useState(false)

  const [notice, setNotice] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!schedulerStatus) return
    setScanInterval(String(schedulerStatus.autoScanIntervalHours))
    setGraceMinutes(String(schedulerStatus.fixedTimeGraceMinutes))
    setAlwaysSend(schedulerStatus.scheduledAlwaysSend)
  }, [schedulerStatus])

  const loadSessions = () => {
    api
      .authSessions()
      .then(setSessions)
      .catch(() => {
        /* ignore */
      })
  }

  const loadAdminUsers = () => {
    if (!user.isSuperuser) return
    api
      .authListUsers()
      .then(setAdminUsers)
      .catch(() => {
        /* ignore */
      })
  }

  useEffect(() => {
    loadSessions()
    loadAdminUsers()
  }, [user.isSuperuser])

  const accountNames = new Map(accounts.map((a) => [a.id, a.displayName]))

  async function handleTogglePause() {
    if (!schedulerStatus || togglingPause) return
    setTogglingPause(true)
    setError(null)
    try {
      if (schedulerStatus.paused) await api.schedulerResume()
      else await api.schedulerPause()
      await onChanged()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '切换全局调度状态失败')
    } finally {
      setTogglingPause(false)
    }
  }

  async function handleSaveScheduler(e: FormEvent) {
    e.preventDefault()
    if (savingScheduler) return
    const parsed = parseSchedulerSettingsForm(scanInterval, graceMinutes, alwaysSend)
    if (!parsed.ok) {
      setError(parsed.error)
      return
    }
    setSavingScheduler(true)
    setError(null)
    setNotice(null)
    try {
      await api.schedulerUpdateSettings(parsed.patch)
      setNotice('全局调度设置已更新')
      await onChanged()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '保存调度设置失败')
    } finally {
      setSavingScheduler(false)
    }
  }

  async function handleSaveProfile(e: FormEvent) {
    e.preventDefault()
    setError(null)
    setNotice(null)
    try {
      await api.authUpdateMe({
        displayName: displayName.trim() || null,
        email: email.trim() || null,
      })
      setNotice('个人资料已保存')
      await onChanged()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '保存个人资料失败')
    }
  }

  async function handleChangePassword(e: FormEvent) {
    e.preventDefault()
    if (pwNew.length < 8) {
      setError('新密码至少需要 8 个字符')
      return
    }
    if (pwNew !== pwConfirm) {
      setError('两次输入的新密码不一致')
      return
    }
    setError(null)
    setNotice(null)
    try {
      await api.authChangePassword({ currentPassword: pwCurrent, newPassword: pwNew })
      setPwCurrent('')
      setPwNew('')
      setPwConfirm('')
      setNotice('密码已更新')
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '修改密码失败')
    }
  }

  async function handleCreateUser(e: FormEvent) {
    e.preventDefault()
    if (!newUsername.trim() || newUserPw.length < 8) {
      setError('新建用户需填写用户名且密码不少于 8 位')
      return
    }
    setError(null)
    setNotice(null)
    try {
      await api.authAdminCreateUser({
        username: newUsername.trim(),
        password: newUserPw,
        isSuperuser: newUserSuper,
      })
      setNewUsername('')
      setNewUserPw('')
      setNewUserSuper(false)
      setNotice('已创建新控制台用户')
      loadAdminUsers()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '创建用户失败')
    }
  }

  return (
    <>
      <header className="fc-page-header">
        <div>
          <h1 className="fc-page-title">系统与调度</h1>
          <p className="fc-page-meta">
            管理全站后台自动续火调度器、本地配置备份以及控制台会话安全。
          </p>
        </div>

        <div className="fc-page-actions">
          <button type="button" className="fc-btn fc-btn-secondary" onClick={onLogout}>
            退出控制台
          </button>
        </div>
      </header>

      <div className="fc-scroll">
        <div className="fc-settings-stack">
          {error && <div className="fc-alert is-error">{error}</div>}
          {notice && <div className="fc-alert is-ok">{notice}</div>}

          {/* 0. Appearance Theme */}
          <section className="fc-card">
            <div className="fc-settings-row" style={{ flexWrap: 'wrap' }}>
              <div>
                <h2 style={{ fontSize: 15.5, fontWeight: 600 }}>界面外观主题</h2>
                <p className="fc-hint">
                  支持浅色、深色或跟随系统自动切换（当前生效：{resolvedTheme === 'dark' ? '深色' : '浅色'}）
                </p>
              </div>
              <ThemeSwitcher value={themePreference} onChange={onChangeTheme} />
            </div>
          </section>

          {/* 1. Global Scheduler */}
          <section className="fc-card">
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <div>
                <h2 style={{ fontSize: 15.5, fontWeight: 600 }}>全局自动调度器</h2>
                <p className="fc-hint">控制后台自动扫描会话快照与定时触发续火</p>
              </div>
              {schedulerStatus && (
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <StatusPill tone={schedulerStatus.paused ? 'warn' : 'ok'} dot>
                    {schedulerStatus.paused ? '已全局暂停' : '自动调度运行中'}
                  </StatusPill>
                  <Switch
                    checked={!schedulerStatus.paused}
                    disabled={togglingPause}
                    label="启用全局自动调度"
                    onChange={() => void handleTogglePause()}
                  />
                </div>
              )}
            </div>

            <form
              onSubmit={(e) => void handleSaveScheduler(e)}
              style={{ display: 'flex', flexDirection: 'column', gap: 12, paddingTop: 6 }}
            >
              <div className="fc-form-grid-2">
                <div className="fc-field">
                  <label className="fc-label" htmlFor="st-scan-hours">
                    自动会话扫描间隔（1–168 小时）
                  </label>
                  <input
                    id="st-scan-hours"
                    type="number"
                    min={1}
                    max={168}
                    className="fc-input fc-mono"
                    value={scanInterval}
                    onChange={(e) => setScanInterval(e.target.value)}
                  />
                </div>

                <div className="fc-field">
                  <label className="fc-label" htmlFor="st-grace">
                    固定时刻宽限期（0–1440 分钟，0 = 不宽限）
                  </label>
                  <input
                    id="st-grace"
                    type="number"
                    min={0}
                    max={1440}
                    className="fc-input fc-mono"
                    value={graceMinutes}
                    onChange={(e) => setGraceMinutes(e.target.value)}
                  />
                </div>
              </div>

              <div className="fc-settings-row" style={{ borderBottom: 'none', paddingBottom: 0 }}>
                <div>
                  <div style={{ fontWeight: 500 }}>到点强制再发一遍（scheduledAlwaysSend）</div>
                  <div className="fc-hint">开启后定时排程到点忽略当天已成功记录；默认建议关闭</div>
                </div>
                <Switch
                  checked={alwaysSend}
                  label="到点强制再发一遍"
                  onChange={setAlwaysSend}
                />
              </div>

              <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
                <button
                  type="submit"
                  className="fc-btn fc-btn-primary fc-btn-sm"
                  disabled={savingScheduler}
                >
                  {savingScheduler ? '保存中…' : '保存调度设置'}
                </button>
              </div>
            </form>

            {schedulerStatus && schedulerStatus.todaySchedules.length > 0 && (
              <div className="fc-kv-list">
                {schedulerStatus.todaySchedules.map((sch) => (
                  <div key={sch.id} className="fc-kv-row">
                    <span>
                      <strong>{accountNames.get(sch.accountId) ?? sch.accountId}</strong> ·{' '}
                      <span className="fc-mono">{formatDateTime(sch.scheduledAt)}</span>
                    </span>
                    <StatusPill tone={SCHEDULE_STATUS_LIGHT_TONES[sch.status]}>
                      {SCHEDULE_STATUS_LABELS[sch.status]}
                    </StatusPill>
                  </div>
                ))}
              </div>
            )}
          </section>

          {/* 2. Data Backup */}
          <section className="fc-card">
            <div className="fc-settings-row">
              <div>
                <h2 style={{ fontSize: 15.5, fontWeight: 600 }}>本地配置备份</h2>
                <p className="fc-hint">导出当前用户的账号窗口、好友契约与全部消息模板（JSON，不含浏览器 Cookie）</p>
              </div>
              <button
                type="button"
                className="fc-btn fc-btn-secondary"
                onClick={() =>
                  void api.downloadBackup().catch((err) =>
                    setError(err instanceof ApiError ? err.message : '备份导出失败'),
                  )
                }
              >
                <DownloadIcon /> 导出 JSON 备份
              </button>
            </div>
          </section>

          {/* 3. Profile, Password & Sessions */}
          <section className="fc-card">
            <h2 style={{ fontSize: 15.5, fontWeight: 600 }}>
              控制台账户安全（{user.username}）
            </h2>

            <form
              onSubmit={(e) => void handleSaveProfile(e)}
              className="fc-form-grid-profile"
            >
              <div className="fc-field">
                <label className="fc-label" htmlFor="pf-name">
                  显示名称
                </label>
                <input
                  id="pf-name"
                  className="fc-input"
                  value={displayName}
                  onChange={(e) => setDisplayName(e.target.value)}
                />
              </div>
              <div className="fc-field">
                <label className="fc-label" htmlFor="pf-email">
                  备注邮箱
                </label>
                <input
                  id="pf-email"
                  className="fc-input"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                />
              </div>
              <button type="submit" className="fc-btn fc-btn-secondary">
                更新资料
              </button>
            </form>

            <form
              onSubmit={(e) => void handleChangePassword(e)}
              className="fc-form-grid-pw"
            >
              <div className="fc-field">
                <label className="fc-label" htmlFor="pw-cur">
                  当前密码
                </label>
                <input
                  id="pw-cur"
                  type="password"
                  className="fc-input"
                  value={pwCurrent}
                  onChange={(e) => setPwCurrent(e.target.value)}
                  required
                />
              </div>
              <div className="fc-field">
                <label className="fc-label" htmlFor="pw-new">
                  新密码
                </label>
                <input
                  id="pw-new"
                  type="password"
                  className="fc-input"
                  value={pwNew}
                  onChange={(e) => setPwNew(e.target.value)}
                  required
                />
              </div>
              <div className="fc-field">
                <label className="fc-label" htmlFor="pw-cfm">
                  确认新密码
                </label>
                <input
                  id="pw-cfm"
                  type="password"
                  className="fc-input"
                  value={pwConfirm}
                  onChange={(e) => setPwConfirm(e.target.value)}
                  required
                />
              </div>
              <button type="submit" className="fc-btn fc-btn-secondary">
                修改密码
              </button>
            </form>

            {sessions.length > 0 && (
              <div className="fc-kv-list">
                {sessions.map((s) => (
                  <div key={s.id} className="fc-kv-row">
                    <div>
                      <span className="fc-mono">{s.ipAddress ?? '127.0.0.1'}</span> · 最近活跃{' '}
                      {formatDateTime(s.lastSeenAt ?? s.createdAt)}
                      {s.current && (
                        <span style={{ marginLeft: 8, color: 'var(--ok)', fontWeight: 600 }}>
                          当前会话
                        </span>
                      )}
                    </div>
                    {!s.current && !s.revokedAt && (
                      <button
                        type="button"
                        className="fc-btn fc-btn-secondary fc-btn-sm"
                        onClick={() =>
                          void api.authRevokeSession(s.id).then(loadSessions)
                        }
                      >
                        吊销
                      </button>
                    )}
                  </div>
                ))}
              </div>
            )}
          </section>

          {/* 4. Superuser User Management */}
          {user.isSuperuser && (
            <section className="fc-card">
              <h2 style={{ fontSize: 15.5, fontWeight: 600 }}>管理员专区 · 本地用户管理</h2>

              <form
                onSubmit={(e) => void handleCreateUser(e)}
                className="fc-form-grid-admin"
              >
                <div className="fc-field">
                  <label className="fc-label" htmlFor="au-name">
                    新用户名
                  </label>
                  <input
                    id="au-name"
                    className="fc-input"
                    value={newUsername}
                    onChange={(e) => setNewUsername(e.target.value)}
                  />
                </div>
                <div className="fc-field">
                  <label className="fc-label" htmlFor="au-pw">
                    初始密码（≥8位）
                  </label>
                  <input
                    id="au-pw"
                    type="password"
                    className="fc-input"
                    value={newUserPw}
                    onChange={(e) => setNewUserPw(e.target.value)}
                  />
                </div>
                <label style={{ display: 'inline-flex', alignItems: 'center', gap: 6, height: 36, fontSize: 12.5 }}>
                  <input
                    type="checkbox"
                    checked={newUserSuper}
                    onChange={(e) => setNewUserSuper(e.target.checked)}
                  />
                  超管
                </label>
                <button type="submit" className="fc-btn fc-btn-secondary">
                  创建用户
                </button>
              </form>

              {adminUsers.length > 0 && (
                <div className="fc-kv-list">
                  {adminUsers.map((u) => (
                    <div key={u.id} className="fc-kv-row">
                      <span>
                        <strong>{u.username}</strong>{' '}
                        {u.isSuperuser && <StatusPill tone="neutral">管理员</StatusPill>}
                        <span className="fc-hint" style={{ marginLeft: 8 }}>
                          创建于 {formatDateTime(u.createdAt)}
                        </span>
                      </span>
                      {u.id !== user.id && (
                        <div style={{ display: 'flex', gap: 6 }}>
                          <button
                            type="button"
                            className="fc-btn fc-btn-secondary fc-btn-sm"
                            onClick={() => {
                              const nextPw = window.prompt(
                                `为用户「${u.username}」设置新密码（至少 8 位）：`,
                              )
                              if (!nextPw || nextPw.length < 8) return
                              void api
                                .authAdminUpdateUser(u.id, { password: nextPw })
                                .then(() => setNotice(`已重置 ${u.username} 的密码`))
                                .catch((err) =>
                                  setError(err instanceof ApiError ? err.message : '重置密码失败'),
                                )
                            }}
                          >
                            重置密码
                          </button>
                          <button
                            type="button"
                            className="fc-btn fc-btn-secondary fc-btn-sm"
                            onClick={() =>
                              void api
                                .authAdminUpdateUser(u.id, { isSuperuser: !u.isSuperuser })
                                .then(loadAdminUsers)
                                .catch((err) =>
                                  setError(err instanceof ApiError ? err.message : '变更权限失败'),
                                )
                            }
                          >
                            {u.isSuperuser ? '取消超管' : '设为超管'}
                          </button>
                        </div>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </section>
          )}
        </div>
      </div>
    </>
  )
}
