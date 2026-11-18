import type {
  Account,
  ContractTarget,
  JobStatus,
  ScanProgress,
  SendJob,
  SendPreviewRead,
} from '../types.ts'
import {
  ATTENTION_ACCOUNT_HINTS,
  ATTENTION_ACCOUNT_STATUSES,
  JOB_STATUS_LABELS,
  JOB_STATUS_LIGHT_TONES,
  type StatusTone,
} from './format.ts'

export type SingleSendGuard = 'ready' | 'blocked_disabled' | 'blocked_review' | 'confirm_repeat'

export interface TargetDaySummary {
  targetId: string
  todayJob: SendJob | null
  todayStatusLabel: string
  todayTone: StatusTone
  todaySucceeded: boolean
  todayFailed: boolean
  hasUnresolvedReview: boolean
  unresolvedReviewJob: SendJob | null
  singleSendGuard: SingleSendGuard
}

export type AccountAttentionItem =
  | {
      id: string
      kind: 'account_status'
      accountStatus: Account['status']
      message: string
    }
  | {
      id: string
      kind: 'job_review'
      job: SendJob
      targetName: string
      businessDate: string
      isHistorical: boolean
      message: string
    }

export interface MergedSendPreviewRow {
  target: ContractTarget
  willSend: boolean
  renderedMessage: string | null
  skipReason: string | null
  tone: StatusTone
}

function sortJobsNewestFirst(jobs: SendJob[]): SendJob[] {
  return [...jobs].sort((a, b) => {
    const ta = Date.parse(a.updatedAt || a.createdAt) || 0
    const tb = Date.parse(b.updatedAt || b.createdAt) || 0
    return tb - ta
  })
}

export function deriveTargetDayMap(
  targets: ContractTarget[],
  accountJobs: SendJob[],
  unresolvedReviewJobs: SendJob[],
  todayBusinessDate: string,
): Map<string, TargetDaySummary> {
  const orderedTodayJobs = sortJobsNewestFirst(
    accountJobs.filter((job) => job.businessDate.slice(0, 10) === todayBusinessDate),
  )
  const orderedReviews = sortJobsNewestFirst(
    unresolvedReviewJobs.filter((job) => job.status === 'unknown_requires_review'),
  )

  const latestTodayByTarget = new Map<string, SendJob>()
  for (const job of orderedTodayJobs) {
    if (!latestTodayByTarget.has(job.targetId)) {
      latestTodayByTarget.set(job.targetId, job)
    }
  }

  const reviewByTarget = new Map<string, SendJob>()
  for (const job of orderedReviews) {
    if (!reviewByTarget.has(job.targetId)) {
      reviewByTarget.set(job.targetId, job)
    }
  }

  const map = new Map<string, TargetDaySummary>()
  for (const target of targets) {
    if (target.deletedAt !== null) continue
    const todayJob = latestTodayByTarget.get(target.id) ?? null
    const reviewJob =
      reviewByTarget.get(target.id) ??
      (todayJob?.status === 'unknown_requires_review' ? todayJob : null)
    const hasUnresolvedReview = reviewJob !== null

    let todayStatusLabel = '今日待发'
    let todayTone: StatusTone = 'neutral'
    const status: JobStatus | null = todayJob?.status ?? null

    if (hasUnresolvedReview) {
      todayStatusLabel =
        reviewJob.businessDate.slice(0, 10) === todayBusinessDate ? '待人工复核' : '历史待复核'
      todayTone = 'warn'
    } else if (!target.enabled) {
      todayStatusLabel = '已暂停'
      todayTone = 'neutral'
    } else if (status) {
      todayStatusLabel = JOB_STATUS_LABELS[status] ?? status
      todayTone = JOB_STATUS_LIGHT_TONES[status] ?? 'neutral'
    }

    const todaySucceeded = status === 'succeeded'
    const todayFailed = status === 'failed_retryable' || status === 'failed_terminal'

    let singleSendGuard: SingleSendGuard = 'ready'
    if (hasUnresolvedReview) {
      singleSendGuard = 'blocked_review'
    } else if (!target.enabled) {
      singleSendGuard = 'blocked_disabled'
    } else if (todaySucceeded) {
      singleSendGuard = 'confirm_repeat'
    }

    map.set(target.id, {
      targetId: target.id,
      todayJob,
      todayStatusLabel,
      todayTone,
      todaySucceeded,
      todayFailed,
      hasUnresolvedReview,
      unresolvedReviewJob: reviewJob,
      singleSendGuard,
    })
  }
  return map
}

export function collectAccountReviewItems(
  account: Account | null,
  targets: ContractTarget[],
  unresolvedReviewJobs: SendJob[],
  todayBusinessDate: string,
): AccountAttentionItem[] {
  if (!account) return []
  const items: AccountAttentionItem[] = []

  if (ATTENTION_ACCOUNT_STATUSES.includes(account.status)) {
    items.push({
      id: `acct-${account.id}-${account.status}`,
      kind: 'account_status',
      accountStatus: account.status,
      message: ATTENTION_ACCOUNT_HINTS[account.status] ?? '账号状态需要人工检查。',
    })
  }

  const targetNames = new Map(targets.map((t) => [t.id, t.nicknameSnapshot]))
  const ordered = sortJobsNewestFirst(
    unresolvedReviewJobs.filter(
      (job) => job.accountId === account.id && job.status === 'unknown_requires_review',
    ),
  )

  for (const job of ordered) {
    const dateStr = job.businessDate.slice(0, 10)
    const isHistorical = dateStr !== todayBusinessDate
    const targetName = targetNames.get(job.targetId) ?? '未知会话'
    items.push({
      id: `review-${job.id}`,
      kind: 'job_review',
      job,
      targetName,
      businessDate: dateStr,
      isHistorical,
      message: `${targetName}（${isHistorical ? `历史 ${dateStr}` : '今日'}）发送结果未确认，系统不会自动重发。`,
    })
  }

  return items
}

export function mergeAccountSendPreview(
  targets: ContractTarget[],
  dayMap: Map<string, TargetDaySummary>,
  preview: SendPreviewRead | null,
  force: boolean,
): MergedSendPreviewRow[] {
  const previewByTarget = new Map(preview?.items.map((item) => [item.targetId, item]) ?? [])
  const activeTargets = targets.filter((t) => t.deletedAt === null)

  return activeTargets.map((target) => {
    const summary = dayMap.get(target.id)
    const pItem = previewByTarget.get(target.id)

    if (!target.enabled) {
      return {
        target,
        willSend: false,
        renderedMessage: null,
        skipReason: '未启用自动续火（跳过）',
        tone: 'neutral',
      }
    }

    if (summary?.hasUnresolvedReview) {
      return {
        target,
        willSend: false,
        renderedMessage: null,
        skipReason: '存在待人工复核任务，受安全保护跳过',
        tone: 'warn',
      }
    }

    if (!force && summary?.todaySucceeded) {
      return {
        target,
        willSend: false,
        renderedMessage: pItem?.message ?? null,
        skipReason: '今日已发送成功（幂等跳过）',
        tone: 'ok',
      }
    }

    if (!pItem) {
      return {
        target,
        willSend: false,
        renderedMessage: null,
        skipReason: target.templateId ? '正在读取预览或未命中条件' : '未绑定消息模板（跳过）',
        tone: 'neutral',
      }
    }

    if (pItem.reason) {
      return {
        target,
        willSend: false,
        renderedMessage: null,
        skipReason: pItem.reason,
        tone: 'neutral',
      }
    }

    return {
      target,
      willSend: Boolean(pItem.message),
      renderedMessage: pItem.message,
      skipReason: pItem.message ? null : '无可发送内容',
      tone: pItem.message ? 'ok' : 'neutral',
    }
  })
}

export function shouldFinalizeRecoveredScan(
  task: ScanProgress,
  syncedIds: ReadonlySet<string>,
): boolean {
  if (task.source === 'automatic') return false
  if (task.status !== 'completed') return false
  return !syncedIds.has(task.taskId)
}

export type SchedulerFormValidation =
  | {
      ok: true
      patch: {
        autoScanIntervalHours: number
        fixedTimeGraceMinutes: number
        scheduledAlwaysSend: boolean
      }
    }
  | { ok: false; error: string }

export function parseSchedulerSettingsForm(
  scanHoursRaw: string,
  graceMinutesRaw: string,
  scheduledAlwaysSend: boolean,
): SchedulerFormValidation {
  const hours = Number.parseInt(scanHoursRaw.trim(), 10)
  if (Number.isNaN(hours) || hours < 1 || hours > 168) {
    return { ok: false, error: '自动扫描间隔须为 1 – 168 小时之间的整数' }
  }
  const grace = Number.parseInt(graceMinutesRaw.trim(), 10)
  if (Number.isNaN(grace) || grace < 0 || grace > 1440) {
    return { ok: false, error: '固定时刻宽限期须为 0 – 1440 分钟之间的整数（0 表示不宽限）' }
  }
  return {
    ok: true,
    patch: {
      autoScanIntervalHours: hours,
      fixedTimeGraceMinutes: grace,
      scheduledAlwaysSend,
    },
  }
}
