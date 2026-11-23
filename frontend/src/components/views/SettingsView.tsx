import { useEffect, useMemo, useRef, useState, type ChangeEvent, type FormEvent } from 'react'
import { api, ApiError } from '../../api.ts'
import {
  getDesktopAutostart,
  openDesktopDataDir,
  setDesktopAutostart,
  type DesktopBootstrap,
} from '../../lib/desktop.ts'
import {
  SCHEDULE_STATUS_LABELS,
  SCHEDULE_STATUS_LIGHT_TONES,
  formatDateTime,
} from '../../lib/format.ts'
import { paginateItems } from '../../lib/pagination.ts'
import type { ResolvedTheme, ThemePreference } from '../../lib/theme.ts'
import {
  MAX_BACKUP_UPLOAD_BYTES,
  describeBarkCardState,
  formatBackupImportSummary,
  parseBarkInputPreview,
  parseSchedulerSettingsForm,
  validateBarkUrlInput,
  type BackupImportSummaryView,
} from '../../lib/workspace.ts'
import type { Account, AuthSessionRead, SchedulerStatus, User } from '../../types.ts'
import { CloseIcon, DownloadIcon, SendIcon, UploadIcon } from '../ui/Icons.tsx'
import { PaginationBar } from '../ui/PaginationBar.tsx'
import { PillTabs, type PillTabItem } from '../ui/PillTabs.tsx'
import { StatusPill } from '../ui/StatusPill.tsx'
import { Switch } from '../ui/Switch.tsx'
import { ThemeSwitcher } from '../ui/ThemeSwitcher.tsx'

type SettingsTabKey = 'scheduler' | 'security' | 'admin'

export interface SettingsViewProps {
  user: User
  accounts: Account[]
  schedulerStatus: SchedulerStatus | null
  desktopBootstrap?: DesktopBootstrap | null
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
  desktopBootstrap = null,
  themePreference,
  resolvedTheme,
  onChangeTheme,
  onChanged,
  onLogout,
}: SettingsViewProps) {
  const isDesktop = Boolean(desktopBootstrap?.desktopMode)
  const [tab, setTab] = useState<SettingsTabKey>('scheduler')

  // Scheduler form
  const [scanInterval, setScanInterval] = useState('6')
  const [graceMinutes, setGraceMinutes] = useState('30')
  const [alwaysSend, setAlwaysSend] = useState(false)
  const [savingScheduler, setSavingScheduler] = useState(false)
  const [togglingPause, setTogglingPause] = useState(false)
  const [barkUrlInput, setBarkUrlInput] = useState('')
  const [savingBark, setSavingBark] = useState(false)
  const [testingBark, setTestingBark] = useState(false)
  const [autostartEnabled, setAutostartEnabled] = useState(false)
  const [togglingAutostart, setTogglingAutostart] = useState(false)
  const [schedulePage, setSchedulePage] = useState(1)

  // Profile & password
  const [displayName, setDisplayName] = useState(user.displayName ?? '')
  const [email, setEmail] = useState(user.email ?? '')
  const [pwCurrent, setPwCurrent] = useState('')
  const [pwNew, setPwNew] = useState('')
  const [pwConfirm, setPwConfirm] = useState('')
  const [sessions, setSessions] = useState<AuthSessionRead[]>([])
  const [sessionPage, setSessionPage] = useState(1)

  // Superuser admin
  const [adminUsers, setAdminUsers] = useState<User[]>([])
  const [adminPage, setAdminPage] = useState(1)
  const [newUsername, setNewUsername] = useState('')
  const [newUserPw, setNewUserPw] = useState('')
  const [newUserSuper, setNewUserSuper] = useState(false)

  const [notice, setNotice] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  // Local backup & restore (.zip / .json)
  const [backupAction, setBackupAction] = useState<'idle' | 'export-zip' | 'export-json' | 'import'>('idle')
  const [backupImportResult, setBackupImportResult] = useState<{
    fileName: string
    summary: BackupImportSummaryView
  } | null>(null)
  const [backupError, setBackupError] = useState<string | null>(null)
  const backupFileInputRef = useRef<HTMLInputElement | null>(null)

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
    if (!isDesktop) {
      loadSessions()
      loadAdminUsers()
      return
    }
    getDesktopAutostart()
      .then(setAutostartEnabled)
      .catch(() => {
        /* ignore */
      })
  }, [isDesktop, user.isSuperuser])

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

  async function handleSaveBark(e: FormEvent) {
    e.preventDefault()
    if (savingBark) return
    const parsed = validateBarkUrlInput(barkUrlInput)
    if (!parsed.ok) {
      setError(parsed.error)
      return
    }
    setSavingBark(true)
    setError(null)
    setNotice(null)
    try {
      await api.schedulerUpdateSettings({ barkUrl: parsed.value })
      setBarkUrlInput('')
      setNotice('Bark 推送地址已保存并立即生效')
      await onChanged()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '保存 Bark 推送地址失败')
    } finally {
      setSavingBark(false)
    }
  }

  async function handleClearBark() {
    if (savingBark) return
    setSavingBark(true)
    setError(null)
    setNotice(null)
    try {
      const updated = await api.schedulerUpdateSettings({ barkUrl: '' })
      setBarkUrlInput('')
      setNotice(
        updated.barkSource === 'env'
          ? '已清除页面配置，当前回退使用环境变量 FLAME_BARK_URL'
          : '已清空 Bark 推送配置',
      )
      await onChanged()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '清空 Bark 推送地址失败')
    } finally {
      setSavingBark(false)
    }
  }

  async function handleToggleAutostart(next: boolean) {
    if (togglingAutostart) return
    setTogglingAutostart(true)
    setError(null)
    try {
      const result = await setDesktopAutostart(next)
      setAutostartEnabled(result)
      setNotice(result ? '已开启开机自动启动' : '已关闭开机自动启动')
    } catch (err) {
      setError(err instanceof Error ? err.message : '更新开机自启设置失败')
    } finally {
      setTogglingAutostart(false)
    }
  }

  async function handleTestBark() {
    if (testingBark) return
    setTestingBark(true)
    setError(null)
    setNotice(null)
    try {
      const res = await api.schedulerTestNotification()
      if (res.ok) {
        setNotice(res.detail)
      } else {
        setError(res.detail)
      }
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '发送测试推送失败')
    } finally {
      setTestingBark(false)
    }
  }

  const barkState = describeBarkCardState(schedulerStatus)
  const barkPreview = useMemo(
    () => (barkUrlInput.trim() ? parseBarkInputPreview(barkUrlInput) : null),
    [barkUrlInput],
  )

  const backupBusy = backupAction !== 'idle'

  async function handleExportBackupArchive() {
    if (backupBusy) return
    setBackupAction('export-zip')
    setBackupError(null)
    try {
      await api.downloadBackupArchive()
    } catch (err) {
      setBackupError(err instanceof ApiError ? err.message : '完整备份导出失败')
    } finally {
      setBackupAction('idle')
    }
  }

  async function handleExportBackupJson() {
    if (backupBusy) return
    setBackupAction('export-json')
    setBackupError(null)
    try {
      await api.downloadBackup()
    } catch (err) {
      setBackupError(err instanceof ApiError ? err.message : 'JSON 备份导出失败')
    } finally {
      setBackupAction('idle')
    }
  }

  async function handleImportBackupFile(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    e.target.value = ''
    if (!file || backupBusy) return
    if (file.size <= 0) {
      setBackupError('所选备份文件为空，请重新选择')
      return
    }
    if (file.size > MAX_BACKUP_UPLOAD_BYTES) {
      setBackupError('备份文件超过 60 MB 上限，无法导入')
      return
    }
    setBackupAction('import')
    setBackupError(null)
    setBackupImportResult(null)
    try {
      const result = await api.importBackupArchive(file)
      setBackupImportResult({
        fileName: file.name,
        summary: formatBackupImportSummary(result),
      })
      await onChanged()
    } catch (err) {
      setBackupError(err instanceof ApiError ? err.message : '备份导入恢复失败')
    } finally {
      setBackupAction('idle')
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

  const settingsTabs: PillTabItem<SettingsTabKey>[] = isDesktop
    ? [{ key: 'scheduler', label: '调度与桌面设置' }]
    : [
        { key: 'scheduler', label: '调度与外观' },
        { key: 'security', label: '账户与会话', count: sessions.length || undefined },
        ...(user.isSuperuser
          ? [{ key: 'admin' as const, label: '用户管理', count: adminUsers.length || undefined }]
          : []),
      ]

  const pagedSchedules = paginateItems(
    schedulerStatus?.todaySchedules ?? [],
    schedulePage,
    4,
  )
  const pagedSessions = paginateItems(sessions, sessionPage, 5)
  const pagedAdminUsers = paginateItems(adminUsers, adminPage, 5)

  return (
    <>
      <header className="fc-page-header">
        <div>
          <h1 className="fc-page-title">系统与调度</h1>
          <p className="fc-page-meta">
            管理全站后台自动续火调度器、界面主题备份以及控制台会话安全。
          </p>
        </div>

        {!isDesktop && (
          <div className="fc-page-actions">
            <button type="button" className="fc-btn fc-btn-secondary" onClick={onLogout}>
              退出控制台
            </button>
          </div>
        )}
      </header>

      <div className="fc-toolbar">
        <PillTabs
          items={settingsTabs}
          value={tab}
          onChange={(next) => {
            setTab(next)
            setError(null)
            setNotice(null)
          }}
          ariaLabel="切换系统设置分组"
        />
      </div>

      <div className="fc-stage is-settings">
        {error && <div className="fc-alert is-error">{error}</div>}
        {notice && <div className="fc-alert is-ok">{notice}</div>}

        {tab === 'scheduler' && (
          <div className="fc-settings-grid">
            {/* Left: Global Scheduler + Desktop Runtime + Backup */}
            <div className="fc-settings-col">
              <section className="fc-card">
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
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
                  style={{ display: 'flex', flexDirection: 'column', gap: 12, paddingTop: 4 }}
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
                        固定时刻宽限期（0–1440 分钟）
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

                {pagedSchedules.total > 0 && (
                  <>
                    <div className="fc-kv-list">
                      {pagedSchedules.pageItems.map((sch) => (
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
                    {pagedSchedules.totalPages > 1 && (
                      <PaginationBar
                        compact
                        page={pagedSchedules.safePage}
                        totalPages={pagedSchedules.totalPages}
                        totalItems={pagedSchedules.total}
                        rangeStart={pagedSchedules.rangeStart}
                        rangeEnd={pagedSchedules.rangeEnd}
                        onPageChange={setSchedulePage}
                      />
                    )}
                  </>
                )}
              </section>

              {isDesktop && desktopBootstrap && (
                <section className="fc-card">
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
                    <div>
                      <h2 style={{ fontSize: 15.5, fontWeight: 600 }}>桌面应用与运行环境</h2>
                      <p className="fc-hint">关闭窗口将缩进右下角系统托盘，继续准点续火</p>
                    </div>
                    {schedulerStatus?.browserRuntime && (
                      <StatusPill tone={schedulerStatus.browserRuntime.ready ? 'ok' : 'warn'} dot>
                        {schedulerStatus.browserRuntime.label}
                      </StatusPill>
                    )}
                  </div>

                  <div className="fc-settings-row" style={{ marginTop: 8 }}>
                    <div>
                      <div style={{ fontWeight: 500 }}>开机自动启动</div>
                      <div className="fc-hint">登录 Windows 后自动在托盘就绪，避免遗忘开启续火</div>
                    </div>
                    <Switch
                      checked={autostartEnabled}
                      disabled={togglingAutostart}
                      label="开机自动启动"
                      onChange={(next) => void handleToggleAutostart(next)}
                    />
                  </div>

                  <div className="fc-settings-row" style={{ borderBottom: 'none', paddingBottom: 0, flexWrap: 'wrap' }}>
                    <div style={{ minWidth: 0, flex: '1 1 200px' }}>
                      <div style={{ fontWeight: 500 }}>本机数据目录</div>
                      <div className="fc-hint">
                        默认跟随安装目录下的 data 文件夹（只读目录时回退系统目录），升级不丢数据
                      </div>
                      <div className="fc-hint fc-mono" style={{ wordBreak: 'break-all', marginTop: 2 }}>
                        {desktopBootstrap.dataDir}
                      </div>
                    </div>
                    <button
                      type="button"
                      className="fc-btn fc-btn-secondary fc-btn-sm"
                      onClick={() => void openDesktopDataDir()}
                    >
                      打开目录
                    </button>
                  </div>
                </section>
              )}

              <section className="fc-card">
                <div>
                  <h2 style={{ fontSize: 15.5, fontWeight: 600 }}>本地配置备份</h2>
                  <p className="fc-hint" style={{ marginTop: 3 }}>
                    支持导出与导入账号窗口、好友契约、全部消息模板（含图片模板）与历史记录；兼容跨端 .zip 与 .json，不含登录 Cookie 或密钥
                  </p>
                </div>

                <div className="fc-backup-actions">
                  <button
                    type="button"
                    className="fc-btn fc-btn-primary fc-btn-sm"
                    disabled={backupBusy}
                    onClick={() => void handleExportBackupArchive()}
                  >
                    <DownloadIcon />
                    {backupAction === 'export-zip' ? '打包导出中…' : '导出完整备份 (.zip 含图)'}
                  </button>
                  <button
                    type="button"
                    className="fc-btn fc-btn-secondary fc-btn-sm"
                    disabled={backupBusy}
                    onClick={() => void handleExportBackupJson()}
                  >
                    <DownloadIcon />
                    {backupAction === 'export-json' ? '导出中…' : '仅导出 JSON'}
                  </button>
                  <button
                    type="button"
                    className="fc-btn fc-btn-secondary fc-btn-sm"
                    disabled={backupBusy}
                    onClick={() => backupFileInputRef.current?.click()}
                  >
                    <UploadIcon />
                    {backupAction === 'import' ? '正在导入恢复…' : '导入备份恢复'}
                  </button>
                  <input
                    ref={backupFileInputRef}
                    type="file"
                    accept=".zip,.json,application/zip,application/json"
                    aria-label="选择备份文件（支持 .zip 或 .json）"
                    style={{ display: 'none' }}
                    onChange={(e) => void handleImportBackupFile(e)}
                  />
                </div>

                {backupError && (
                  <div className="fc-alert is-error" role="alert">
                    {backupError}
                  </div>
                )}

                {backupImportResult && (
                  <div
                    className={`fc-backup-result is-${backupImportResult.summary.tone}`}
                    role="status"
                  >
                    <div className="fc-backup-result-head">
                      <div className="fc-backup-result-title">
                        <span>{backupImportResult.summary.headline}</span>
                        <span className="fc-mono fc-backup-result-file">
                          {backupImportResult.fileName}
                        </span>
                      </div>
                      <button
                        type="button"
                        className="fc-btn fc-btn-ghost fc-btn-icon"
                        aria-label="关闭导入结果提示"
                        onClick={() => setBackupImportResult(null)}
                      >
                        <CloseIcon width={14} height={14} />
                      </button>
                    </div>
                    <div className="fc-backup-result-meta">
                      <div>{backupImportResult.summary.countsDetail}</div>
                      {backupImportResult.summary.imagesDetail && (
                        <div>{backupImportResult.summary.imagesDetail}</div>
                      )}
                      {backupImportResult.summary.missingImagesHint && (
                        <div className="fc-backup-result-warn">
                          {backupImportResult.summary.missingImagesHint}
                        </div>
                      )}
                    </div>
                  </div>
                )}
              </section>
            </div>

            {/* Right: Appearance + Bark Notification */}
            <div className="fc-settings-col">
              <section className="fc-card">
                <div className="fc-settings-row" style={{ flexWrap: 'wrap' }}>
                  <div>
                    <h2 style={{ fontSize: 15.5, fontWeight: 600 }}>界面外观主题</h2>
                    <p className="fc-hint">
                      浅色、深色或跟随系统（当前生效：{resolvedTheme === 'dark' ? '深色' : '浅色'}）
                    </p>
                  </div>
                  <ThemeSwitcher value={themePreference} onChange={onChangeTheme} />
                </div>
              </section>

              <section className="fc-card">
                <div className="fc-settings-row" style={{ flexWrap: 'wrap', borderBottom: 'none', paddingBottom: 0 }}>
                  <div style={{ minWidth: 0, flex: '1 1 220px' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                      <h2 style={{ fontSize: 15.5, fontWeight: 600 }}>Bark 手机通知（iOS）</h2>
                      <StatusPill tone={barkState.configured ? 'ok' : 'neutral'} dot>
                        {barkState.badgeLabel}
                      </StatusPill>
                      {barkState.sourceLabel && (
                        <span className="fc-hint">{barkState.sourceLabel}</span>
                      )}
                    </div>
                    <p className="fc-hint" style={{ marginTop: 3 }}>
                      {barkState.hint}
                    </p>
                    {barkState.maskedUrl && (
                      <div className="fc-mono fc-bark-masked">{barkState.maskedUrl}</div>
                    )}
                  </div>
                  <button
                    type="button"
                    className="fc-btn fc-btn-secondary fc-btn-sm"
                    disabled={!barkState.canSendTest || testingBark}
                    onClick={() => void handleTestBark()}
                  >
                    <SendIcon /> {testingBark ? '推送中…' : '发送测试推送'}
                  </button>
                </div>

                {user.isSuperuser && (
                  <>
                    <details className="fc-bark-guide">
                      <summary className="fc-bark-guide-summary">
                        如何获取 Bark 推送地址？（支持直接粘贴使用示例）
                      </summary>
                      <ol className="fc-bark-guide-steps">
                        <li>在 iPhone App Store 安装并打开 <strong>Bark</strong>，允许通知权限。</li>
                        <li>
                          在 Bark 首页「使用示例」卡片中任选 <code>GET</code> / <code>POST</code> /{' '}
                          <code>JSON</code>，直接点<strong>「复制」</strong>。
                        </li>
                        <li>
                          粘贴到下方输入框，系统会自动提取服务地址与设备 Key；点击「保存地址」后可点「发送测试推送」验证。
                        </li>
                      </ol>
                    </details>

                    <form
                      onSubmit={(e) => void handleSaveBark(e)}
                      className="fc-bark-form"
                    >
                      <div className="fc-field" style={{ flex: 1 }}>
                        <label className="fc-label" htmlFor="st-bark-url">
                          Bark 推送地址或使用示例（保存后即热生效，仅脱敏回显）
                        </label>
                        <textarea
                          id="st-bark-url"
                          rows={3}
                          autoComplete="off"
                          spellCheck={false}
                          className="fc-textarea fc-mono fc-bark-textarea"
                          placeholder={'https://api.day.app/<你的设备Key>\n或直接粘贴 Bark 首页复制的 curl 示例（GET / POST / JSON）'}
                          value={barkUrlInput}
                          onChange={(e) => setBarkUrlInput(e.target.value)}
                        />
                        {barkPreview && (
                          <div
                            className={`fc-bark-preview ${barkPreview.ok ? 'is-ok' : 'is-err'}`}
                          >
                            {barkPreview.ok ? (
                              <>
                                <span>
                                  {barkPreview.extractedFromExample
                                    ? '已自动识别为：'
                                    : '识别地址：'}
                                </span>
                                <code className="fc-mono">{barkPreview.masked}</code>
                              </>
                            ) : (
                              <span>{barkPreview.error}</span>
                            )}
                          </div>
                        )}
                      </div>
                      <div className="fc-bark-actions">
                        <button
                          type="submit"
                          className="fc-btn fc-btn-primary fc-btn-sm"
                          disabled={savingBark || !barkUrlInput.trim()}
                        >
                          {savingBark ? '保存中…' : '保存地址'}
                        </button>
                        {barkState.canClearOverride && (
                          <button
                            type="button"
                            className="fc-btn fc-btn-secondary fc-btn-sm"
                            disabled={savingBark}
                            onClick={() => void handleClearBark()}
                          >
                            清空页面配置
                          </button>
                        )}
                      </div>
                    </form>
                  </>
                )}
              </section>
            </div>
          </div>
        )}

        {tab === 'security' && (
          <div className="fc-settings-grid">
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
            </section>

            <section className="fc-card">
              <div>
                <h2 style={{ fontSize: 15.5, fontWeight: 600 }}>活跃登录会话</h2>
                <p className="fc-hint">可随时吊销其他设备或历史浏览器的登录态</p>
              </div>

              {pagedSessions.total === 0 ? (
                <div className="fc-hint">暂无活跃会话记录</div>
              ) : (
                <>
                  <div className="fc-kv-list">
                    {pagedSessions.pageItems.map((s) => (
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
                  {pagedSessions.totalPages > 1 && (
                    <PaginationBar
                      compact
                      page={pagedSessions.safePage}
                      totalPages={pagedSessions.totalPages}
                      totalItems={pagedSessions.total}
                      rangeStart={pagedSessions.rangeStart}
                      rangeEnd={pagedSessions.rangeEnd}
                      onPageChange={setSessionPage}
                    />
                  )}
                </>
              )}
            </section>
          </div>
        )}

        {tab === 'admin' && user.isSuperuser && (
          <div className="fc-settings-grid">
            <section className="fc-card">
              <div>
                <h2 style={{ fontSize: 15.5, fontWeight: 600 }}>创建控制台用户</h2>
                <p className="fc-hint">新建本地账号后可独立管理各自的抖音号与好友列表</p>
              </div>

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
            </section>

            <section className="fc-card">
              <div>
                <h2 style={{ fontSize: 15.5, fontWeight: 600 }}>本地控制台用户列表</h2>
                <p className="fc-hint">支持重置密码与切换管理员权限</p>
              </div>

              {pagedAdminUsers.total === 0 ? (
                <div className="fc-hint">暂无用户数据</div>
              ) : (
                <>
                  <div className="fc-kv-list">
                    {pagedAdminUsers.pageItems.map((u) => (
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
                  {pagedAdminUsers.totalPages > 1 && (
                    <PaginationBar
                      compact
                      page={pagedAdminUsers.safePage}
                      totalPages={pagedAdminUsers.totalPages}
                      totalItems={pagedAdminUsers.total}
                      rangeStart={pagedAdminUsers.rangeStart}
                      rangeEnd={pagedAdminUsers.rangeEnd}
                      onPageChange={setAdminPage}
                    />
                  )}
                </>
              )}
            </section>
          </div>
        )}
      </div>
    </>
  )
}
