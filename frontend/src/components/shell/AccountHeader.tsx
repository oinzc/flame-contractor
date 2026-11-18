import {
  ACCOUNT_STATUS_LABELS,
  ACCOUNT_STATUS_LIGHT_TONES,
  SCHEDULE_STATUS_LABELS,
} from '../../lib/format.ts'
import type { Account, DrawerState, SendSchedule } from '../../types.ts'
import { QrIcon, ScanIcon, SendIcon } from '../ui/Icons.tsx'
import { StatusPill } from '../ui/StatusPill.tsx'
import { Switch } from '../ui/Switch.tsx'

export interface AccountHeaderProps {
  account: Account
  todaySucceededCount: number
  todayPendingCount: number
  unresolvedReviewCount: number
  todaySchedule: SendSchedule | null
  togglingAccount: boolean
  onToggleAccount: (enabled: boolean) => void
  onOpenDrawer: (drawer: DrawerState) => void
}

export function AccountHeader({
  account,
  todaySucceededCount,
  todayPendingCount,
  unresolvedReviewCount,
  todaySchedule,
  togglingAccount,
  onToggleAccount,
  onOpenDrawer,
}: AccountHeaderProps) {
  const tone = ACCOUNT_STATUS_LIGHT_TONES[account.status] ?? 'neutral'
  const timeText = account.sendTimeHhmm
    ? `定时 ${account.sendTimeHhmm}`
    : `窗口 ${account.sendWindowStart}–${account.sendWindowEnd}`

  return (
    <header className="fc-page-header">
      <div>
        <div className="fc-page-title-row">
          <h1 className="fc-page-title">{account.displayName}</h1>
          <StatusPill tone={tone} dot>
            {ACCOUNT_STATUS_LABELS[account.status]}
          </StatusPill>
          <button
            type="button"
            className="fc-btn fc-btn-secondary fc-btn-sm fc-mono"
            style={{ height: 26, fontWeight: 500 }}
            onClick={() => onOpenDrawer({ kind: 'account-manage' })}
            title="点击调整发送窗口或固定发送时刻"
          >
            {timeText}
          </button>
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 12.5, color: 'var(--muted)' }}>
            <Switch
              checked={account.enabled}
              disabled={togglingAccount}
              label={`启用抖音账号 ${account.displayName}`}
              onChange={onToggleAccount}
            />
            <span>{account.enabled ? '自动续火已开' : '账号已暂停'}</span>
          </span>
        </div>

        <div className="fc-page-meta fc-tabular">
          {account.douyinNickname && <span>抖音身份：{account.douyinNickname}</span>}
          <span>今日已续火 {todaySucceededCount}</span>
          <span>·</span>
          <span>待发 {todayPendingCount}</span>
          {unresolvedReviewCount > 0 && (
            <>
              <span>·</span>
              <span style={{ color: 'var(--warn)', fontWeight: 600 }}>
                待复核 {unresolvedReviewCount}
              </span>
            </>
          )}
          {todaySchedule && (
            <>
              <span>·</span>
              <span>今日排程：{SCHEDULE_STATUS_LABELS[todaySchedule.status]}</span>
            </>
          )}
        </div>
      </div>

      <div className="fc-page-actions">
        <button
          type="button"
          className="fc-btn fc-btn-secondary"
          onClick={() => onOpenDrawer({ kind: 'account-manage' })}
        >
          <QrIcon /> 账号与扫码
        </button>
        <button
          type="button"
          className="fc-btn fc-btn-secondary"
          onClick={() => onOpenDrawer({ kind: 'dry-run' })}
        >
          演练检查
        </button>
        <button
          type="button"
          className="fc-btn fc-btn-secondary"
          onClick={() => onOpenDrawer({ kind: 'scan-reconcile' })}
        >
          <ScanIcon /> 扫描对账
        </button>
        <button
          type="button"
          className="fc-btn fc-btn-primary"
          onClick={() => onOpenDrawer({ kind: 'send-account' })}
        >
          <SendIcon /> 今日群发预览
        </button>
      </div>
    </header>
  )
}
