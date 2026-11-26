import { useCallback, useEffect, useRef, useState } from 'react'
import { api, ApiError } from '../api.ts'
import { sparkLabelForSnapshot } from '../lib/spark.ts'
import { shouldFinalizeRecoveredScan } from '../lib/workspace.ts'
import type {
  Account,
  ContractTarget,
  IdentityHydrationTaskState,
  ScanTaskState,
  SchedulerStatus,
  SendProgress,
  SendRead,
  SendTargetInput,
  TargetBatchResult,
} from '../types.ts'

const SYNCED_SCAN_IDS_KEY = 'flame_synced_scan_task_ids'
const MAX_SYNCED_IDS = 50

function readSyncedScanIds(): Set<string> {
  try {
    const raw = localStorage.getItem(SYNCED_SCAN_IDS_KEY)
    if (!raw) return new Set()
    const parsed = JSON.parse(raw) as unknown
    if (Array.isArray(parsed)) {
      return new Set(parsed.filter((id): id is string => typeof id === 'string'))
    }
  } catch {
    // ignore malformed storage
  }
  return new Set()
}

function recordSyncedScanId(taskId: string): void {
  const current = Array.from(readSyncedScanIds())
  const next = [...current.filter((id) => id !== taskId), taskId].slice(-MAX_SYNCED_IDS)
  try {
    localStorage.setItem(SYNCED_SCAN_IDS_KEY, JSON.stringify(next))
  } catch {
    // ignore storage quota errors
  }
}

export interface ActiveSendTaskState {
  accountId: string
  mode: 'account' | 'target'
  force: boolean
  targetName: string | null
  status: 'running' | 'completed' | 'failed'
  progress: SendProgress | null
  result: SendRead | null
  error: string | null
}

export interface BackgroundTasksController {
  scanTask: ScanTaskState | null
  scanVisible: boolean
  autoScanTask: ScanTaskState | null
  autoScanVisible: boolean
  identityTask: IdentityHydrationTaskState | null
  identityVisible: boolean
  sendTask: ActiveSendTaskState | null
  sendVisible: boolean
  startManualScan: (accountId: string) => Promise<void>
  cancelManualScan: () => Promise<void>
  startIdentityHydration: (accountId: string, targetIds: string[]) => Promise<void>
  cancelIdentityHydration: () => Promise<void>
  runAccountSend: (accountId: string, force: boolean) => Promise<SendRead | null>
  runTargetSend: (
    accountId: string,
    target: ContractTarget,
    input: SendTargetInput,
  ) => Promise<SendRead | null>
  dismissTask: (kind: 'scan' | 'autoScan' | 'identity' | 'send') => void
}

export function useBackgroundTasks(params: {
  accounts: Account[]
  targets: ContractTarget[]
  schedulerStatus: SchedulerStatus | null
  onDataChanged: () => Promise<void> | void
}): BackgroundTasksController {
  const { accounts, targets, schedulerStatus, onDataChanged } = params

  const [scanTask, setScanTask] = useState<ScanTaskState | null>(null)
  const [scanVisible, setScanVisible] = useState(false)
  const [autoScanTask, setAutoScanTask] = useState<ScanTaskState | null>(null)
  const [autoScanVisible, setAutoScanVisible] = useState(false)
  const [identityTask, setIdentityTask] = useState<IdentityHydrationTaskState | null>(null)
  const [identityVisible, setIdentityVisible] = useState(false)
  const [sendTask, setSendTask] = useState<ActiveSendTaskState | null>(null)
  const [sendVisible, setSendVisible] = useState(false)

  const scanRecoveryRef = useRef(false)
  const identityRecoveryRef = useRef(false)
  const finalizedScanRef = useRef<string | null>(null)
  const autoScanHandledRef = useRef<string | null>(null)
  const identityHandledRef = useRef<string | null>(null)
  const targetsRef = useRef(targets)
  targetsRef.current = targets

  // Recover latest scan snapshot once accounts load
  useEffect(() => {
    if (scanRecoveryRef.current || accounts.length === 0 || scanTask !== null) return undefined
    scanRecoveryRef.current = true
    let cancelled = false

    async function recover() {
      const snapshots = await Promise.all(
        accounts.map(async (acct) => {
          try {
            return await api.latestScanChats(acct.id)
          } catch {
            return null
          }
        }),
      )
      if (cancelled) return
      const valid = snapshots
        .filter((s): s is NonNullable<typeof s> => s !== null)
        .sort((a, b) => {
          const ta = Date.parse(a.finishedAt ?? a.startedAt ?? '') || 0
          const tb = Date.parse(b.finishedAt ?? b.startedAt ?? '') || 0
          return tb - ta
        })

      const syncedIds = readSyncedScanIds()
      const manual = valid.find((s) => s.source !== 'automatic')
      if (manual) {
        const needsFinalize = shouldFinalizeRecoveredScan(manual, syncedIds)
        setScanTask({
          ...manual,
          syncStatus: needsFinalize ? 'pending' : 'done',
          batchResult: null,
          syncError: null,
          pollError: null,
        })
        if (manual.status === 'running') {
          setScanVisible(true)
        }
      }

      const automatic = valid.find((s) => s.source === 'automatic')
      if (automatic && (automatic.status === 'running' || automatic.status === 'interrupted')) {
        setAutoScanTask({
          ...automatic,
          syncStatus: 'done',
          batchResult: null,
          syncError: null,
          pollError: null,
        })
        setAutoScanVisible(true)
      }
    }

    void recover()
    return () => {
      cancelled = true
    }
  }, [accounts, scanTask])

  // Recover latest identity hydration once
  useEffect(() => {
    if (identityRecoveryRef.current || accounts.length === 0 || identityTask !== null) {
      return undefined
    }
    identityRecoveryRef.current = true
    let cancelled = false

    async function recover() {
      const snapshots = await Promise.all(
        accounts.map(async (acct) => {
          try {
            return await api.latestIdentityHydration(acct.id)
          } catch {
            return null
          }
        }),
      )
      if (cancelled) return
      const latest = snapshots
        .filter((s): s is NonNullable<typeof s> => s !== null)
        .sort((a, b) => {
          const ta = Date.parse(a.finishedAt ?? a.startedAt ?? '') || 0
          const tb = Date.parse(b.finishedAt ?? b.startedAt ?? '') || 0
          return tb - ta
        })[0]
      if (latest && latest.status === 'running') {
        setIdentityTask({ ...latest, pollError: null })
        setIdentityVisible(true)
      }
    }

    void recover()
    return () => {
      cancelled = true
    }
  }, [accounts, identityTask])

  // Poll running manual scan
  const scanTaskId = scanTask?.taskId ?? null
  const scanStatus = scanTask?.status ?? null
  const scanAccountId = scanTask?.accountId ?? null
  useEffect(() => {
    if (!scanTaskId || !scanAccountId || scanStatus !== 'running') return undefined
    let cancelled = false
    let timer: number | null = null

    const poll = async () => {
      try {
        const next = await api.scanChatsProgress(scanAccountId, scanTaskId)
        if (cancelled) return
        setScanTask((prev) =>
          prev && prev.taskId === scanTaskId
            ? {
                ...prev,
                ...next,
                syncStatus: next.status === 'completed' ? 'pending' : prev.syncStatus,
                pollError: null,
              }
            : prev,
        )
        if (next.status === 'running') {
          timer = window.setTimeout(() => void poll(), 800)
        }
      } catch (err) {
        if (cancelled) return
        if (err instanceof ApiError && err.status === 404) {
          setScanTask((prev) =>
            prev && prev.taskId === scanTaskId
              ? {
                  ...prev,
                  status: 'failed',
                  stage: '扫描任务失效',
                  overall: 'error',
                  detail: '后端扫描任务已失效，请重新发起扫描',
                  currentItem: null,
                  pollError: null,
                }
              : prev,
          )
          return
        }
        setScanTask((prev) =>
          prev && prev.taskId === scanTaskId
            ? { ...prev, pollError: '进度读取暂时失败，自动重试中…' }
            : prev,
        )
        timer = window.setTimeout(() => void poll(), 1200)
      }
    }

    void poll()
    return () => {
      cancelled = true
      if (timer !== null) window.clearTimeout(timer)
    }
  }, [scanTaskId, scanAccountId, scanStatus])

  // Finalize manual scan ONLY once per taskId and ONLY for already-existing targets
  useEffect(() => {
    if (!scanTask || scanTask.status !== 'completed' || scanTask.syncStatus !== 'pending') return
    if (finalizedScanRef.current === scanTask.taskId) return
    const syncedIds = readSyncedScanIds()
    if (!shouldFinalizeRecoveredScan(scanTask, syncedIds)) {
      setScanTask((prev) => (prev ? { ...prev, syncStatus: 'done' } : prev))
      return
    }
    finalizedScanRef.current = scanTask.taskId

    async function finalize(task: ScanTaskState) {
      const acctTargets = targetsRef.current.filter(
        (t) => t.accountId === task.accountId && t.deletedAt === null,
      )
      const existingNames = new Set(acctTargets.map((t) => t.nicknameSnapshot))
      const existingUserIds = new Set(
        acctTargets.map((t) => t.platformUserId).filter((v): v is string => Boolean(v)),
      )
      const existingConvIds = new Set(
        acctTargets.map((t) => t.platformConversationId).filter((v): v is string => Boolean(v)),
      )
      const matchedEntries = task.entries.filter(
        (e) =>
          existingNames.has(e.title) ||
          (e.platformUserId != null && existingUserIds.has(e.platformUserId)) ||
          (e.platformConversationId != null && existingConvIds.has(e.platformConversationId)),
      )

      let batchResult: TargetBatchResult | null = null
      try {
        if (matchedEntries.length > 0) {
          batchResult = await api.createTargetsBatch({
            accountId: task.accountId,
            items: matchedEntries.map((entry) => ({
              nickname: entry.title,
              platformUserId: entry.platformUserId,
              platformHandle: entry.platformHandle,
              platformConversationId: entry.platformConversationId,
              conversationType: entry.conversationType,
              identityStatus: entry.identityStatus,
              conversationStatus: entry.conversationStatus,
              streakDays:
                entry.streakDays != null && entry.streakDays > 0 ? entry.streakDays : undefined,
              sparkDays:
                entry.streakDays != null && entry.streakDays > 0 ? entry.streakDays : null,
              sparkKind: entry.streakKind,
              sparkLabel: sparkLabelForSnapshot(entry),
            })),
          })
        }
        recordSyncedScanId(task.taskId)
        await onDataChanged()
        setScanTask((prev) =>
          prev && prev.taskId === task.taskId
            ? { ...prev, syncStatus: 'done', batchResult, syncError: null }
            : prev,
        )
      } catch (err) {
        const msg =
          err instanceof ApiError
            ? `扫描完成，但同步官方快照失败：${err.message}`
            : '扫描完成，但同步官方快照失败'
        setScanTask((prev) =>
          prev && prev.taskId === task.taskId
            ? { ...prev, syncStatus: 'failed', syncError: msg }
            : prev,
        )
      }
    }

    void finalize(scanTask)
  }, [scanTask, onDataChanged])

  // Discover & poll automatic scans from schedulerStatus
  useEffect(() => {
    const runningAuto = schedulerStatus?.autoScans.find(
      (s) => s.status === 'running' && s.taskId,
    )
    if (!runningAuto?.taskId || autoScanTask?.taskId === runningAuto.taskId) return undefined
    let cancelled = false
    api
      .scanChatsProgress(runningAuto.accountId, runningAuto.taskId)
      .then((next) => {
        if (cancelled || next.source !== 'automatic') return
        setAutoScanTask({
          ...next,
          syncStatus: next.status === 'completed' ? 'pending' : 'done',
          batchResult: null,
          syncError: null,
          pollError: null,
        })
        setAutoScanVisible(true)
      })
      .catch(() => {
        /* ignore transient race */
      })
    return () => {
      cancelled = true
    }
  }, [schedulerStatus, autoScanTask?.taskId])

  const autoTaskId = autoScanTask?.taskId ?? null
  const autoStatus = autoScanTask?.status ?? null
  const autoAccountId = autoScanTask?.accountId ?? null
  useEffect(() => {
    if (!autoTaskId || !autoAccountId || autoStatus !== 'running') return undefined
    let cancelled = false
    let timer: number | null = null

    const poll = async () => {
      try {
        const next = await api.scanChatsProgress(autoAccountId, autoTaskId)
        if (cancelled) return
        setAutoScanTask((prev) =>
          prev && prev.taskId === autoTaskId
            ? {
                ...prev,
                ...next,
                syncStatus: next.status === 'completed' ? 'done' : prev.syncStatus,
                pollError: null,
              }
            : prev,
        )
        if (next.status === 'running') {
          timer = window.setTimeout(() => void poll(), 800)
        }
      } catch {
        if (!cancelled) timer = window.setTimeout(() => void poll(), 1500)
      }
    }

    void poll()
    return () => {
      cancelled = true
      if (timer !== null) window.clearTimeout(timer)
    }
  }, [autoTaskId, autoAccountId, autoStatus])

  useEffect(() => {
    if (!autoScanTask || autoScanTask.status === 'running') return
    if (autoScanHandledRef.current === autoScanTask.taskId) return
    autoScanHandledRef.current = autoScanTask.taskId
    void onDataChanged()
  }, [autoScanTask, onDataChanged])

  // Poll identity hydration
  const idTaskId = identityTask?.taskId ?? null
  const idStatus = identityTask?.status ?? null
  const idAccountId = identityTask?.accountId ?? null
  useEffect(() => {
    if (!idTaskId || !idAccountId || idStatus !== 'running') return undefined
    let cancelled = false
    let timer: number | null = null

    const poll = async () => {
      try {
        const next = await api.identityHydrationProgress(idAccountId, idTaskId)
        if (cancelled) return
        setIdentityTask((prev) =>
          prev?.taskId === idTaskId ? { ...prev, ...next, pollError: null } : prev,
        )
        if (next.status === 'running') {
          timer = window.setTimeout(() => void poll(), 800)
        }
      } catch (err) {
        if (cancelled) return
        if (err instanceof ApiError && err.status === 404) {
          setIdentityTask((prev) =>
            prev?.taskId === idTaskId
              ? { ...prev, status: 'failed', stage: '任务失效', detail: '身份补采任务已结束' }
              : prev,
          )
          return
        }
        timer = window.setTimeout(() => void poll(), 1200)
      }
    }

    void poll()
    return () => {
      cancelled = true
      if (timer !== null) window.clearTimeout(timer)
    }
  }, [idTaskId, idAccountId, idStatus])

  useEffect(() => {
    if (!identityTask || identityTask.status === 'running') return
    if (identityHandledRef.current === identityTask.taskId) return
    identityHandledRef.current = identityTask.taskId
    void onDataChanged()
  }, [identityTask, onDataChanged])

  // Auto-hide clean completions after 4.5s
  useEffect(() => {
    if (!scanVisible || !scanTask || scanTask.status !== 'completed' || scanTask.syncStatus !== 'done') {
      return undefined
    }
    const t = window.setTimeout(() => setScanVisible(false), 4500)
    return () => window.clearTimeout(t)
  }, [scanVisible, scanTask])

  useEffect(() => {
    if (!autoScanVisible || !autoScanTask || autoScanTask.status !== 'completed') return undefined
    const t = window.setTimeout(() => setAutoScanVisible(false), 4500)
    return () => window.clearTimeout(t)
  }, [autoScanVisible, autoScanTask])

  useEffect(() => {
    if (!identityVisible || !identityTask || identityTask.status !== 'completed') return undefined
    const t = window.setTimeout(() => setIdentityVisible(false), 4500)
    return () => window.clearTimeout(t)
  }, [identityVisible, identityTask])

  useEffect(() => {
    if (!sendVisible || !sendTask || sendTask.status !== 'completed') return undefined
    const t = window.setTimeout(() => setSendVisible(false), 4500)
    return () => window.clearTimeout(t)
  }, [sendVisible, sendTask])

  // Poll sendProgress while sendTask is running
  const sendRunningAccount = sendTask?.status === 'running' ? sendTask.accountId : null
  useEffect(() => {
    if (!sendRunningAccount) return undefined
    let cancelled = false
    let timer: number | null = null

    const poll = async () => {
      try {
        const prog = await api.sendProgress(sendRunningAccount)
        if (cancelled) return
        setSendTask((prev) => (prev ? { ...prev, progress: prog } : prev))
      } catch {
        /* ignore transient progress error while POST is in flight */
      }
      if (!cancelled) {
        timer = window.setTimeout(() => void poll(), 800)
      }
    }

    void poll()
    return () => {
      cancelled = true
      if (timer !== null) window.clearTimeout(timer)
    }
  }, [sendRunningAccount])

  const startManualScan = useCallback(async (accountId: string) => {
    finalizedScanRef.current = null
    const started = await api.startScanChats(accountId)
    setScanTask({
      ...started,
      syncStatus: started.status === 'completed' ? 'pending' : 'done',
      batchResult: null,
      syncError: null,
      pollError: null,
    })
    setScanVisible(true)
  }, [])

  const cancelManualScan = useCallback(async () => {
    if (!scanTask || scanTask.status !== 'running') return
    const next = await api.cancelScanChats(scanTask.accountId, scanTask.taskId)
    setScanTask((prev) => (prev ? { ...prev, ...next, syncStatus: 'done' } : prev))
  }, [scanTask])

  const startIdentityHydration = useCallback(async (accountId: string, targetIds: string[]) => {
    identityHandledRef.current = null
    const started = await api.startIdentityHydration(accountId, targetIds)
    setIdentityTask({ ...started, pollError: null })
    setIdentityVisible(true)
  }, [])

  const cancelIdentityHydration = useCallback(async () => {
    if (!identityTask || identityTask.status !== 'running') return
    const next = await api.cancelIdentityHydration(identityTask.accountId, identityTask.taskId)
    setIdentityTask((prev) => (prev ? { ...prev, ...next } : prev))
  }, [identityTask])

  const runAccountSend = useCallback(
    async (accountId: string, force: boolean): Promise<SendRead | null> => {
      setSendTask({
        accountId,
        mode: 'account',
        force,
        targetName: null,
        status: 'running',
        progress: null,
        result: null,
        error: null,
      })
      setSendVisible(true)
      try {
        const res = await api.sendNow(accountId, { force })
        setSendTask((prev) =>
          prev
            ? {
                ...prev,
                status: res.overall === 'ok' ? 'completed' : 'failed',
                result: res,
                error: res.overall === 'ok' ? null : (res.detail ?? '部分或全部发送需查看明细'),
              }
            : prev,
        )
        await onDataChanged()
        return res
      } catch (err) {
        const msg = err instanceof ApiError ? err.message : '群发执行失败'
        setSendTask((prev) => (prev ? { ...prev, status: 'failed', error: msg } : prev))
        await onDataChanged()
        return null
      }
    },
    [onDataChanged],
  )

  const runTargetSend = useCallback(
    async (
      accountId: string,
      target: ContractTarget,
      input: SendTargetInput,
    ): Promise<SendRead | null> => {
      setSendTask({
        accountId,
        mode: 'target',
        force: false,
        targetName: target.nicknameSnapshot,
        status: 'running',
        progress: null,
        result: null,
        error: null,
      })
      setSendVisible(true)
      try {
        const res = await api.sendTarget(accountId, target.id, input)
        setSendTask((prev) =>
          prev
            ? {
                ...prev,
                status: res.overall === 'ok' ? 'completed' : 'failed',
                result: res,
                error: res.overall === 'ok' ? null : (res.detail ?? '发送结果需人工确认'),
              }
            : prev,
        )
        await onDataChanged()
        return res
      } catch (err) {
        const msg = err instanceof ApiError ? err.message : '单人发送失败'
        setSendTask((prev) => (prev ? { ...prev, status: 'failed', error: msg } : prev))
        await onDataChanged()
        return null
      }
    },
    [onDataChanged],
  )

  const dismissTask = useCallback((kind: 'scan' | 'autoScan' | 'identity' | 'send') => {
    if (kind === 'scan') setScanVisible(false)
    else if (kind === 'autoScan') setAutoScanVisible(false)
    else if (kind === 'identity') setIdentityVisible(false)
    else setSendVisible(false)
  }, [])

  return {
    scanTask,
    scanVisible,
    autoScanTask,
    autoScanVisible,
    identityTask,
    identityVisible,
    sendTask,
    sendVisible,
    startManualScan,
    cancelManualScan,
    startIdentityHydration,
    cancelIdentityHydration,
    runAccountSend,
    runTargetSend,
    dismissTask,
  }
}
