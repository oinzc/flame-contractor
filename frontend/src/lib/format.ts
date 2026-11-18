import type { AccountStatus, FailureClass, JobStatus, ScheduleStatus } from '../types.ts'

export type StatusTone = 'neutral' | 'ok' | 'warn' | 'err' | 'spark'

export const ACCOUNT_STATUS_LABELS: Record<AccountStatus, string> = {
  pending_login: '待登录',
  healthy: '健康',
  running: '运行中',
  paused: '已暂停',
  auth_expired: '登录失效',
  verification_required: '需验证',
  dom_broken: '页面不兼容',
  cooldown: '冷却中',
}

export const ACCOUNT_STATUS_LIGHT_TONES: Record<AccountStatus, StatusTone> = {
  pending_login: 'warn',
  healthy: 'ok',
  running: 'ok',
  paused: 'neutral',
  auth_expired: 'err',
  verification_required: 'err',
  dom_broken: 'err',
  cooldown: 'warn',
}

export const JOB_STATUS_LABELS: Record<JobStatus, string> = {
  scheduled: '已排程',
  leased: '已租用',
  opening_chat: '打开会话',
  sending: '发送中',
  confirming: '确认中',
  succeeded: '已续焰',
  failed_retryable: '待重试',
  failed_terminal: '失败',
  unknown_requires_review: '待人工复核',
  cancelled: '已取消',
}

export const JOB_STATUS_LIGHT_TONES: Record<JobStatus, StatusTone> = {
  scheduled: 'neutral',
  leased: 'neutral',
  opening_chat: 'neutral',
  sending: 'neutral',
  confirming: 'neutral',
  succeeded: 'ok',
  failed_retryable: 'warn',
  failed_terminal: 'err',
  unknown_requires_review: 'warn',
  cancelled: 'neutral',
}

export const ATTENTION_ACCOUNT_STATUSES: AccountStatus[] = [
  'auth_expired',
  'verification_required',
  'dom_broken',
]

export const ATTENTION_ACCOUNT_HINTS: Record<string, string> = {
  auth_expired: '登录已失效，需本人重新扫码确认。',
  verification_required: '平台要求验证，请本人处理，不自动绕过。',
  dom_broken: '页面结构变化，已暂停自动发送并保留诊断。',
}

export const SCHEDULE_STATUS_LABELS: Record<ScheduleStatus, string> = {
  pending: '待发送',
  leased: '已租用',
  sending: '发送中',
  succeeded: '已发送',
  failed: '发送失败',
  unknown_requires_review: '待人工复核',
  skipped: '已跳过',
  cancelled: '已取消',
}

export const SCHEDULE_STATUS_LIGHT_TONES: Record<ScheduleStatus, StatusTone> = {
  pending: 'neutral',
  leased: 'neutral',
  sending: 'neutral',
  succeeded: 'ok',
  failed: 'err',
  unknown_requires_review: 'warn',
  skipped: 'neutral',
  cancelled: 'neutral',
}

export const FAILURE_CLASS_LABELS: Record<FailureClass, string> = {
  network: '网络不可用',
  auth: '登录失效',
  verification: '需要验证',
  target_uncertain: '好友未确认',
  dom_changed: '页面结构变化',
  unknown_outcome: '结果未确认',
  other: '其他原因',
}

/** 后端 SQLite 存 naive UTC（无时区后缀）；按 UTC 解析成本地 Date。 */
export function parseBackendTime(value: string): Date {
  if (/[Zz]|[+-]\d{2}:?\d{2}$/.test(value)) return new Date(value)
  return new Date(`${value}Z`)
}

export function formatDateTime(value: string | null): string {
  if (!value) return '—'
  const date = parseBackendTime(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', { hour12: false })
}

export function formatTimeHM(value: string | null): string {
  if (!value) return '—'
  const date = parseBackendTime(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleTimeString('zh-CN', { hour12: false })
}

export function formatDateOnly(value: string | null): string {
  if (!value) return '—'
  return value.slice(0, 10)
}
