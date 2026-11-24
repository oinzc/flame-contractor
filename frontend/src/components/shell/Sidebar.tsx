import type { MouseEvent } from 'react'
import type { BackgroundTasksController } from '../../hooks/useBackgroundTasks.ts'
import { isTauriDesktop, openDesktopExternalUrl } from '../../lib/desktop.ts'
import { PROJECT_LINKS } from '../../lib/links.ts'
import type { ThemePreference } from '../../lib/theme.ts'
import type { Account, WorkspaceView } from '../../types.ts'
import {
  FeedbackIcon,
  FlameIcon,
  GithubIcon,
  HistoryIcon,
  SettingsIcon,
  TemplateIcon,
} from '../ui/Icons.tsx'
import { ThemeSwitcher } from '../ui/ThemeSwitcher.tsx'
import { AccountSwitcher } from './AccountSwitcher.tsx'
import { BackgroundTaskStack } from './BackgroundTaskStack.tsx'

export interface SidebarProps {
  accounts: Account[]
  selectedAccountId: string
  view: WorkspaceView
  enabledTargetsCount: number
  attentionCount: number
  templatesCount: number
  mobileOpen: boolean
  themePreference: ThemePreference
  tasks: BackgroundTasksController
  onCloseMobile: () => void
  onSelectAccount: (id: string) => void
  onSelectView: (view: WorkspaceView) => void
  onChangeTheme: (next: ThemePreference) => void
  onCreateAccount: () => void
  onInspectScan: (accountId: string) => void
}

export function Sidebar({
  accounts,
  selectedAccountId,
  view,
  enabledTargetsCount,
  attentionCount,
  templatesCount,
  mobileOpen,
  themePreference,
  tasks,
  onCloseMobile,
  onSelectAccount,
  onSelectView,
  onChangeTheme,
  onCreateAccount,
  onInspectScan,
}: SidebarProps) {
  function navTo(next: WorkspaceView) {
    onSelectView(next)
    onCloseMobile()
  }

  function handleExternalClick(event: MouseEvent<HTMLAnchorElement>, url: string) {
    if (!isTauriDesktop()) return
    event.preventDefault()
    void openDesktopExternalUrl(url)
  }

  function handleOpenInAppFeedback() {
    navTo('settings')
    window.setTimeout(() => {
      const el = document.getElementById('fc-about-support-heading')
      el?.scrollIntoView({ behavior: 'smooth', block: 'start' })
    }, 80)
  }

  return (
    <>
      <div
        className={`fc-sidebar-backdrop${mobileOpen ? ' is-open' : ''}`}
        onClick={onCloseMobile}
        aria-hidden="true"
      />
      <aside className={`fc-sidebar${mobileOpen ? ' is-mobile-open' : ''}`} aria-label="主导航">
        <AccountSwitcher
          accounts={accounts}
          selectedAccountId={selectedAccountId}
          onSelectAccount={(id) => {
            onSelectAccount(id)
            onCloseMobile()
          }}
          onCreateAccount={() => {
            onCreateAccount()
            onCloseMobile()
          }}
        />

        <div className="fc-nav-section-label">当前抖音号工作区</div>
        <nav className="fc-nav-list" aria-label="当前账号功能">
          <button
            type="button"
            className={`fc-nav-item${view === 'targets' ? ' is-active' : ''}`}
            onClick={() => navTo('targets')}
          >
            <span className="fc-nav-item-main">
              <FlameIcon />
              <span>续火好友</span>
            </span>
            {attentionCount > 0 ? (
              <span className="fc-nav-badge is-warn">{attentionCount} 待处理</span>
            ) : (
              <span className="fc-nav-badge">{enabledTargetsCount}</span>
            )}
          </button>

          <button
            type="button"
            className={`fc-nav-item${view === 'jobs' ? ' is-active' : ''}`}
            onClick={() => navTo('jobs')}
          >
            <span className="fc-nav-item-main">
              <HistoryIcon />
              <span>记录与统计</span>
            </span>
            <span className="fc-nav-badge">本号</span>
          </button>
        </nav>

        <div className="fc-nav-section-label">全局通用（跨号共享）</div>
        <nav className="fc-nav-list" aria-label="全局功能">
          <button
            type="button"
            className={`fc-nav-item${view === 'templates' ? ' is-active' : ''}`}
            onClick={() => navTo('templates')}
          >
            <span className="fc-nav-item-main">
              <TemplateIcon />
              <span>模板库</span>
            </span>
            <span className="fc-nav-badge">{templatesCount}</span>
          </button>

          <button
            type="button"
            className={`fc-nav-item${view === 'settings' ? ' is-active' : ''}`}
            onClick={() => navTo('settings')}
          >
            <span className="fc-nav-item-main">
              <SettingsIcon />
              <span>系统与调度</span>
            </span>
          </button>
        </nav>

        <div className="fc-sidebar-spacer" />

        <BackgroundTaskStack
          accounts={accounts}
          tasks={tasks}
          onInspectScan={(accountId) => {
            onSelectAccount(accountId)
            onInspectScan(accountId)
            onCloseMobile()
          }}
        />

        <div style={{ marginTop: 10 }}>
          <ThemeSwitcher value={themePreference} onChange={onChangeTheme} compact />
        </div>

        <div className="fc-sidebar-links" aria-label="项目源码与问题反馈">
          <a
            href={PROJECT_LINKS.repoUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="fc-sidebar-ext-link"
            onClick={(e) => handleExternalClick(e, PROJECT_LINKS.repoUrl)}
          >
            <GithubIcon width={13} height={13} />
            <span>GitHub 源码</span>
          </a>
          <button
            type="button"
            className="fc-sidebar-ext-link fc-sidebar-ext-btn"
            onClick={handleOpenInAppFeedback}
          >
            <FeedbackIcon width={13} height={13} />
            <span>反馈问题</span>
          </button>
        </div>
      </aside>
    </>
  )
}
