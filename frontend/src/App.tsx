import { useCallback, useEffect, useMemo, useState } from 'react'
import { api, ApiError, getToken, setToken } from './api.ts'
import { AccountManageDrawer } from './components/drawers/AccountManageDrawer.tsx'
import { CreateEntityDrawer } from './components/drawers/CreateEntityDrawer.tsx'
import { DryRunDrawer } from './components/drawers/DryRunDrawer.tsx'
import { JobDetailDrawer } from './components/drawers/JobDetailDrawer.tsx'
import { ScanReconcileDrawer } from './components/drawers/ScanReconcileDrawer.tsx'
import { SendAccountDrawer } from './components/drawers/SendAccountDrawer.tsx'
import { TargetDetailDrawer } from './components/drawers/TargetDetailDrawer.tsx'
import { TemplateEditorDrawer } from './components/drawers/TemplateEditorDrawer.tsx'
import { Sidebar } from './components/shell/Sidebar.tsx'
import { MenuIcon } from './components/ui/Icons.tsx'
import { JobsStatsView } from './components/views/JobsStatsView.tsx'
import { LoginView } from './components/views/LoginView.tsx'
import { SettingsView } from './components/views/SettingsView.tsx'
import { TargetsWorkspaceView } from './components/views/TargetsWorkspaceView.tsx'
import { TemplatesView } from './components/views/TemplatesView.tsx'
import { useBackgroundTasks } from './hooks/useBackgroundTasks.ts'
import { useThemePreference } from './hooks/useThemePreference.ts'
import { useWorkspaceUrlState } from './hooks/useWorkspaceUrlState.ts'
import { dateInAccountTimezone } from './lib/date.ts'
import { collectAccountReviewItems, deriveTargetDayMap } from './lib/workspace.ts'
import type {
  Account,
  ContractTarget,
  MessageTemplate,
  SchedulerStatus,
  SendJob,
  User,
} from './types.ts'

export default function App() {
  const [currentUser, setCurrentUser] = useState<User | null>(null)
  const [authInitializing, setAuthInitializing] = useState(true)

  const [accounts, setAccounts] = useState<Account[]>([])
  const [targets, setTargets] = useState<ContractTarget[]>([])
  const [templates, setTemplates] = useState<MessageTemplate[]>([])
  const [schedulerStatus, setSchedulerStatus] = useState<SchedulerStatus | null>(null)
  const [accountJobs, setAccountJobs] = useState<SendJob[]>([])
  const [unresolvedReviewJobs, setUnresolvedReviewJobs] = useState<SendJob[]>([])
  const [refreshRevision, setRefreshRevision] = useState(0)
  const [globalError, setGlobalError] = useState<string | null>(null)
  const [mobileNavOpen, setMobileNavOpen] = useState(false)

  const {
    preference: themePreference,
    resolvedTheme,
    setPreference: setThemePreference,
  } = useThemePreference()

  const {
    selectedAccountId,
    setSelectedAccountId,
    view,
    setView,
    drawer,
    setDrawer,
    closeDrawer,
  } = useWorkspaceUrlState(accounts, Boolean(currentUser))

  const checkAuth = useCallback(async () => {
    const token = getToken()
    if (!token) {
      setCurrentUser(null)
      setAuthInitializing(false)
      return
    }
    try {
      const user = await api.authMe()
      setCurrentUser(user)
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        setToken(null)
      }
      setCurrentUser(null)
    } finally {
      setAuthInitializing(false)
    }
  }, [])

  useEffect(() => {
    void checkAuth()
  }, [checkAuth])

  const refresh = useCallback(async () => {
    if (!currentUser) return
    try {
      const [acctList, targetList, templateList, sched] = await Promise.all([
        api.accounts(),
        api.targets(),
        api.templates(),
        api.schedulerStatus().catch(() => null),
      ])
      setAccounts(acctList)
      setTargets(targetList)
      setTemplates(templateList)
      if (sched) setSchedulerStatus(sched)
      setGlobalError(null)

      const activeId =
        acctList.find((a) => a.id === selectedAccountId)?.id ?? acctList[0]?.id ?? ''
      if (activeId) {
        const [recentPage, reviewPage] = await Promise.all([
          api.listJobs({ accountId: activeId, limit: 100 }),
          api.listJobs({
            accountId: activeId,
            status: 'unknown_requires_review',
            limit: 100,
          }),
        ])
        setAccountJobs(recentPage.items)
        setUnresolvedReviewJobs(reviewPage.items)
      } else {
        setAccountJobs([])
        setUnresolvedReviewJobs([])
      }
      setRefreshRevision((n) => n + 1)
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        setToken(null)
        setCurrentUser(null)
        return
      }
      setGlobalError(err instanceof ApiError ? err.message : '同步本机控制台状态失败')
    }
  }, [currentUser, selectedAccountId])

  useEffect(() => {
    if (!currentUser) return
    void refresh()
  }, [currentUser, refresh])

  // 15s foreground workspace refresh
  useEffect(() => {
    if (!currentUser) return undefined
    const timer = window.setInterval(() => {
      if (!document.hidden) void refresh()
    }, 15000)
    return () => window.clearInterval(timer)
  }, [currentUser, refresh])

  // 3s lightweight scheduler status poll when enabled
  useEffect(() => {
    if (!currentUser || !schedulerStatus?.enabled) return undefined
    let cancelled = false
    const timer = window.setInterval(() => {
      if (document.hidden) return
      api
        .schedulerStatus()
        .then((next) => {
          if (!cancelled) setSchedulerStatus(next)
        })
        .catch(() => {
          /* silent background poll */
        })
    }, 3000)
    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [currentUser, schedulerStatus?.enabled])

  const tasks = useBackgroundTasks({
    accounts,
    targets,
    schedulerStatus,
    onDataChanged: refresh,
  })

  const selectedAccount = useMemo(
    () => accounts.find((a) => a.id === selectedAccountId) ?? accounts[0] ?? null,
    [accounts, selectedAccountId],
  )

  const accountTargets = useMemo(
    () =>
      selectedAccount
        ? targets.filter((t) => t.accountId === selectedAccount.id && t.deletedAt === null)
        : [],
    [selectedAccount, targets],
  )

  const todayBusinessDate = dateInAccountTimezone(
    new Date(),
    selectedAccount?.timezone ?? 'Asia/Shanghai',
  )

  const dayMap = useMemo(
    () =>
      deriveTargetDayMap(
        accountTargets,
        accountJobs,
        unresolvedReviewJobs,
        todayBusinessDate,
      ),
    [accountJobs, accountTargets, todayBusinessDate, unresolvedReviewJobs],
  )

  const attentionItems = useMemo(
    () =>
      collectAccountReviewItems(
        selectedAccount,
        accountTargets,
        unresolvedReviewJobs,
        todayBusinessDate,
      ),
    [accountTargets, selectedAccount, todayBusinessDate, unresolvedReviewJobs],
  )

  const todaySchedule = useMemo(
    () =>
      selectedAccount
        ? (schedulerStatus?.todaySchedules.find(
            (s) => s.accountId === selectedAccount.id,
          ) ?? null)
        : null,
    [schedulerStatus?.todaySchedules, selectedAccount],
  )

  const activeTargetForDrawer = useMemo(
    () =>
      drawer.kind === 'target-detail'
        ? (accountTargets.find((t) => t.id === drawer.targetId) ?? null)
        : null,
    [accountTargets, drawer],
  )

  async function handleLogout() {
    try {
      await api.authLogout()
    } catch {
      /* ignore */
    }
    setToken(null)
    setCurrentUser(null)
  }

  if (authInitializing) {
    return (
      <main className="fc-auth-shell">
        <div className="fc-hint">正在连接本机控制面…</div>
      </main>
    )
  }

  if (!currentUser) {
    return (
      <LoginView
        themePreference={themePreference}
        onChangeTheme={setThemePreference}
        onAuthed={() => void checkAuth()}
      />
    )
  }

  return (
    <div className="fc-app">
      {/* Mobile Header (< 768px) */}
      <div className="fc-mobile-topbar">
        <button
          type="button"
          className="fc-btn fc-btn-secondary fc-btn-icon"
          aria-label="打开导航菜单"
          onClick={() => setMobileNavOpen(true)}
        >
          <MenuIcon />
        </button>
        <div style={{ fontWeight: 600, fontSize: 14, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
          {selectedAccount ? selectedAccount.displayName : 'Flame Contractor'}
        </div>
        <div style={{ width: 30 }} />
      </div>

      <div className="fc-workspace">
        <Sidebar
          accounts={accounts}
          selectedAccountId={selectedAccount?.id ?? ''}
          view={view}
          enabledTargetsCount={accountTargets.filter((t) => t.enabled).length}
          attentionCount={attentionItems.length}
          templatesCount={templates.length}
          mobileOpen={mobileNavOpen}
          themePreference={themePreference}
          tasks={tasks}
          onCloseMobile={() => setMobileNavOpen(false)}
          onSelectAccount={(id) => {
            setSelectedAccountId(id)
            if (view === 'templates' || view === 'settings') {
              setView('targets')
            }
          }}
          onSelectView={setView}
          onChangeTheme={setThemePreference}
          onCreateAccount={() => setDrawer({ kind: 'create-account' })}
          onInspectScan={() => {
            setView('targets')
            setDrawer({ kind: 'scan-reconcile' })
          }}
        />

        <main className="fc-main">
          {globalError && (
            <div className="fc-alert is-error" style={{ margin: '12px 24px 0' }}>
              {globalError}
            </div>
          )}

          {view === 'targets' && (
            <TargetsWorkspaceView
              account={selectedAccount}
              targets={targets}
              templates={templates}
              dayMap={dayMap}
              attentionItems={attentionItems}
              todaySchedule={todaySchedule}
              drawer={drawer}
              onOpenDrawer={setDrawer}
              onChanged={refresh}
            />
          )}

          {view === 'jobs' && (
            <JobsStatsView
              accounts={accounts}
              selectedAccount={selectedAccount}
              targets={targets}
              templates={templates}
              selectedJobId={drawer.kind === 'job-detail' ? drawer.jobId : null}
              refreshRevision={refreshRevision}
              onInspectJob={(jobId) => setDrawer({ kind: 'job-detail', jobId })}
              onChanged={refresh}
            />
          )}

          {view === 'templates' && (
            <TemplatesView
              templates={templates}
              drawer={drawer}
              onOpenDrawer={setDrawer}
              onChanged={refresh}
            />
          )}

          {view === 'settings' && (
            <SettingsView
              user={currentUser}
              accounts={accounts}
              schedulerStatus={schedulerStatus}
              themePreference={themePreference}
              resolvedTheme={resolvedTheme}
              onChangeTheme={setThemePreference}
              onChanged={refresh}
              onLogout={() => void handleLogout()}
            />
          )}
        </main>

        {/* Unified Right Context Drawer */}
        {selectedAccount && drawer.kind === 'target-detail' && (
          <TargetDetailDrawer
            open
            account={selectedAccount}
            target={activeTargetForDrawer}
            templates={templates}
            daySummary={activeTargetForDrawer ? dayMap.get(activeTargetForDrawer.id) : undefined}
            accountJobs={accountJobs}
            unresolvedReviewJobs={unresolvedReviewJobs}
            activeTab={drawer.tab}
            tasks={tasks}
            onTabChange={(tab) =>
              setDrawer({ kind: 'target-detail', targetId: drawer.targetId, tab })
            }
            onInspectJob={(jobId) => setDrawer({ kind: 'job-detail', jobId })}
            onClose={closeDrawer}
            onChanged={refresh}
          />
        )}

        <JobDetailDrawer
          open={drawer.kind === 'job-detail'}
          jobId={drawer.kind === 'job-detail' ? drawer.jobId : null}
          externalJobSnapshot={
            drawer.kind === 'job-detail'
              ? ([...unresolvedReviewJobs, ...accountJobs].find((j) => j.id === drawer.jobId) ??
                null)
              : null
          }
          accounts={accounts}
          targets={targets}
          templates={templates}
          onClose={closeDrawer}
          onChanged={refresh}
        />

        <SendAccountDrawer
          open={drawer.kind === 'send-account'}
          account={selectedAccount}
          targets={targets}
          dayMap={dayMap}
          tasks={tasks}
          onClose={closeDrawer}
        />

        <ScanReconcileDrawer
          open={drawer.kind === 'scan-reconcile'}
          account={selectedAccount}
          targets={targets}
          templates={templates}
          tasks={tasks}
          onClose={closeDrawer}
          onChanged={refresh}
        />

        <AccountManageDrawer
          open={drawer.kind === 'account-manage'}
          account={selectedAccount}
          onClose={closeDrawer}
          onChanged={refresh}
          onDeleted={refresh}
        />

        <DryRunDrawer
          open={drawer.kind === 'dry-run'}
          account={selectedAccount}
          onClose={closeDrawer}
          onChanged={refresh}
        />

        <CreateEntityDrawer
          mode={
            drawer.kind === 'create-account' || drawer.kind === 'create-target'
              ? drawer.kind
              : null
          }
          selectedAccount={selectedAccount}
          templates={templates}
          onClose={closeDrawer}
          onAccountCreated={async (created) => {
            await refresh()
            setSelectedAccountId(created.id)
            setView('targets')
            setDrawer({ kind: 'account-manage' })
          }}
          onTargetCreated={refresh}
        />

        <TemplateEditorDrawer
          open={drawer.kind === 'template-editor'}
          templateId={drawer.kind === 'template-editor' ? drawer.templateId : null}
          templates={templates}
          sampleTargets={accountTargets.length > 0 ? accountTargets : targets.filter((t) => t.deletedAt === null)}
          onClose={closeDrawer}
          onSaved={async (saved) => {
            await refresh()
            setDrawer({ kind: 'template-editor', templateId: saved.id })
          }}
        />
      </div>
    </div>
  )
}
