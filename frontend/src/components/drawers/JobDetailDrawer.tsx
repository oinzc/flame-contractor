import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api, ApiError } from '../../api.ts'
import {
  FAILURE_CLASS_LABELS,
  JOB_STATUS_LABELS,
  JOB_STATUS_LIGHT_TONES,
  formatAttemptStage,
  formatDateTime,
  parseBackendTime,
  type StatusTone,
} from '../../lib/format.ts'
import { formatSlotBadge } from '../../lib/pagination.ts'
import {
  pickDefaultScreenshotAttempt,
  shouldReloadOpenJobDetail,
} from '../../lib/workspace.ts'
import type {
  Account,
  AttemptStatus,
  ContractTarget,
  MessageTemplate,
  SendAttemptItem,
  SendJob,
  SendJobDetail,
} from '../../types.ts'
import { CloseIcon, ScanIcon } from '../ui/Icons.tsx'
import { DrawerShell } from '../ui/DrawerShell.tsx'
import { StatusPill } from '../ui/StatusPill.tsx'

export interface JobDetailDrawerProps {
  open: boolean
  jobId: string | null
  externalJobSnapshot?: Pick<SendJob, 'id' | 'status' | 'updatedAt'> | null
  accounts: Account[]
  targets: ContractTarget[]
  templates: MessageTemplate[]
  onClose: () => void
  onChanged: () => Promise<void> | void
}

const ATTEMPT_STATUS_LABELS: Record<AttemptStatus, string> = {
  running: '执行中',
  succeeded: '已确认',
  failed: '未确认/失败',
}

const ATTEMPT_STATUS_TONES: Record<AttemptStatus, StatusTone> = {
  running: 'neutral',
  succeeded: 'ok',
  failed: 'warn',
}

function formatDurationSeconds(attempt: SendAttemptItem): string | null {
  if (!attempt.finishedAt) return null
  const start = parseBackendTime(attempt.startedAt).getTime()
  const end = parseBackendTime(attempt.finishedAt).getTime()
  if (Number.isNaN(start) || Number.isNaN(end) || end < start) return null
  const sec = (end - start) / 1000
  return sec < 1 ? '<1s' : `${sec.toFixed(1)}s`
}

export function JobDetailDrawer({
  open,
  jobId,
  externalJobSnapshot = null,
  accounts,
  targets,
  templates,
  onClose,
  onChanged,
}: JobDetailDrawerProps) {
  const [detail, setDetail] = useState<SendJobDetail | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const [selectedAttemptNo, setSelectedAttemptNo] = useState<number | null>(null)
  const [shotBlobUrl, setShotBlobUrl] = useState<string | null>(null)
  const [shotLoading, setShotLoading] = useState(false)
  const [shotLoadState, setShotLoadState] = useState<'idle' | 'ready' | 'expired' | 'error'>(
    'idle',
  )
  const [lightboxOpen, setLightboxOpen] = useState(false)
  const [actionBusy, setActionBusy] = useState<'confirm' | 'release' | null>(null)

  const detailGenRef = useRef(0)
  const shotGenRef = useRef(0)
  const blobUrlRef = useRef<string | null>(null)

  const replaceBlobUrl = useCallback((nextUrl: string | null) => {
    const prev = blobUrlRef.current
    blobUrlRef.current = nextUrl
    setShotBlobUrl(nextUrl)
    if (prev && prev !== nextUrl) {
      URL.revokeObjectURL(prev)
    }
  }, [])

  useEffect(() => {
    return () => {
      if (blobUrlRef.current) {
        URL.revokeObjectURL(blobUrlRef.current)
        blobUrlRef.current = null
      }
    }
  }, [])

  const loadDetail = useCallback(
    async (id: string, preserveSelection = false) => {
      detailGenRef.current += 1
      const gen = detailGenRef.current
      setLoading(true)
      setError(null)
      try {
        const res = await api.jobDetail(id)
        if (gen !== detailGenRef.current) return
        setDetail(res)
        setSelectedAttemptNo((prev) => {
          if (preserveSelection && prev !== null && res.attempts.some((a) => a.attemptNo === prev)) {
            return prev
          }
          return pickDefaultScreenshotAttempt(res.attempts)
        })
      } catch (err) {
        if (gen !== detailGenRef.current) return
        setError(err instanceof ApiError ? err.message : '加载任务诊断详情失败')
      } finally {
        if (gen === detailGenRef.current) {
          setLoading(false)
        }
      }
    },
    [],
  )

  useEffect(() => {
    detailGenRef.current += 1
    shotGenRef.current += 1
    setLightboxOpen(false)
    setDetail(null)
    setSelectedAttemptNo(null)
    setShotLoading(false)
    setShotLoadState('idle')
    setActionBusy(null)
    setError(null)
    replaceBlobUrl(null)
    if (!open || !jobId) {
      setLoading(false)
      return
    }
    void loadDetail(jobId, false)
  }, [jobId, loadDetail, open, replaceBlobUrl])

  useEffect(() => {
    if (!open || !jobId || !detail || actionBusy !== null) return
    if (shouldReloadOpenJobDetail(detail, externalJobSnapshot ?? null)) {
      void loadDetail(jobId, true)
    }
  }, [actionBusy, detail, externalJobSnapshot, jobId, loadDetail, open])

  const selectedAttempt = useMemo(
    () => detail?.attempts.find((a) => a.attemptNo === selectedAttemptNo) ?? null,
    [detail, selectedAttemptNo],
  )

  const activeDetailId = detail?.id ?? null
  const activeAttemptNo = selectedAttempt?.attemptNo ?? null
  const activeScreenshotState = selectedAttempt?.screenshotState ?? null

  useEffect(() => {
    shotGenRef.current += 1
    const gen = shotGenRef.current

    if (!open || !activeDetailId || activeAttemptNo === null || !activeScreenshotState) {
      replaceBlobUrl(null)
      setShotLoading(false)
      setShotLoadState('idle')
      return
    }

    if (activeScreenshotState === 'expired') {
      replaceBlobUrl(null)
      setShotLoading(false)
      setShotLoadState('expired')
      return
    }

    if (activeScreenshotState !== 'available') {
      replaceBlobUrl(null)
      setShotLoading(false)
      setShotLoadState('idle')
      return
    }

    setShotLoading(true)
    setShotLoadState('idle')
    api
      .jobAttemptScreenshot(activeDetailId, activeAttemptNo)
      .then((blob) => {
        if (gen !== shotGenRef.current) return
        replaceBlobUrl(URL.createObjectURL(blob))
        setShotLoadState('ready')
      })
      .catch((err) => {
        if (gen !== shotGenRef.current) return
        replaceBlobUrl(null)
        if (err instanceof ApiError && err.status === 410) {
          setShotLoadState('expired')
        } else {
          setShotLoadState('error')
        }
      })
      .finally(() => {
        if (gen === shotGenRef.current) {
          setShotLoading(false)
        }
      })
  }, [activeAttemptNo, activeDetailId, activeScreenshotState, open, replaceBlobUrl])

  const handleShellClose = useCallback(() => {
    if (lightboxOpen) {
      setLightboxOpen(false)
      return
    }
    onClose()
  }, [lightboxOpen, onClose])

  async function handleReviewAction(action: 'confirm' | 'release') {
    if (!detail) return
    setActionBusy(action)
    setError(null)
    try {
      if (action === 'confirm') {
        await api.confirmJobSent(detail.id)
      } else {
        await api.releaseJob(detail.id)
      }
      await loadDetail(detail.id, true)
      await onChanged()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '执行复核操作失败')
    } finally {
      setActionBusy(null)
    }
  }

  const accountName = useMemo(
    () => accounts.find((a) => a.id === detail?.accountId)?.displayName ?? '所属账号',
    [accounts, detail?.accountId],
  )
  const targetName = useMemo(
    () => targets.find((t) => t.id === detail?.targetId)?.nicknameSnapshot ?? '续火会话',
    [detail?.targetId, targets],
  )
  const templateName = useMemo(() => {
    if (!detail) return '—'
    if (!detail.templateId) return '临时直发文字'
    return templates.find((t) => t.id === detail.templateId)?.name ?? '已绑定模板'
  }, [detail, templates])

  const isReview = detail?.status === 'unknown_requires_review'

  const footer = isReview ? (
    <>
      <span className="fc-hint" style={{ marginRight: 'auto' }}>
        请对照截图与手机聊天记录核实
      </span>
      <button
        type="button"
        className="fc-btn fc-btn-secondary"
        disabled={actionBusy !== null}
        onClick={() => void handleReviewAction('release')}
      >
        {actionBusy === 'release' ? '释放中…' : '释放重算'}
      </button>
      <button
        type="button"
        className="fc-btn fc-btn-primary"
        disabled={actionBusy !== null}
        onClick={() => void handleReviewAction('confirm')}
      >
        {actionBusy === 'confirm' ? '确认中…' : '确认已发'}
      </button>
    </>
  ) : undefined

  const title = detail ? `${targetName} · 任务诊断` : '任务诊断与复核'
  const subtitle = detail
    ? `${accountName} · 业务日 ${detail.businessDate.slice(0, 10)} · ${formatSlotBadge(detail.messageSlotId)}`
    : '加载任务与执行记录…'

  return (
    <>
      <DrawerShell
        open={open}
        title={title}
        subtitle={subtitle}
        footer={footer}
        onClose={handleShellClose}
      >
        {error && <div className="fc-alert is-error">{error}</div>}

        {loading && !detail ? (
          <div className="fc-hint">正在读取任务尝试记录与诊断状态…</div>
        ) : !detail ? (
          !error && <div className="fc-hint">未找到该任务的诊断详情。</div>
        ) : (
          <>
            {isReview && (
              <div className="fc-alert is-warn">
                发送终态未确认，系统已暂停自动重试以防重复打扰。若下方现场图或手机端已见到消息气泡，请点「确认已发」；若确认未发出，点「释放重算」解锁重发。
              </div>
            )}

            <div className="fc-kv-list">
              <div className="fc-kv-row">
                <span style={{ color: 'var(--muted)' }}>当前状态</span>
                <div style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
                  <StatusPill tone={JOB_STATUS_LIGHT_TONES[detail.status]}>
                    {JOB_STATUS_LABELS[detail.status]}
                  </StatusPill>
                  {detail.failureClass && (
                    <span style={{ fontSize: 12, color: 'var(--warn)' }}>
                      {FAILURE_CLASS_LABELS[detail.failureClass]}
                    </span>
                  )}
                </div>
              </div>
              <div className="fc-kv-row">
                <span style={{ color: 'var(--muted)' }}>消息来源</span>
                <span style={{ fontWeight: 500 }}>{templateName}</span>
              </div>
              <div className="fc-kv-row">
                <span style={{ color: 'var(--muted)' }}>最后更新</span>
                <span className="fc-mono">{formatDateTime(detail.updatedAt)}</span>
              </div>
            </div>

            <section className="fc-shot-stage" aria-label="现场诊断截图">
              <div className="fc-shot-head">
                <span style={{ fontWeight: 600 }}>
                  {selectedAttempt
                    ? `现场诊断截图 · 第 ${selectedAttempt.attemptNo} 次尝试`
                    : '现场诊断截图'}
                </span>
                {shotLoadState === 'ready' && shotBlobUrl && (
                  <button
                    type="button"
                    className="fc-btn fc-btn-secondary fc-btn-sm"
                    style={{ height: 24, padding: '0 8px' }}
                    onClick={() => setLightboxOpen(true)}
                  >
                    <ScanIcon width={13} height={13} /> 放大查看
                  </button>
                )}
              </div>

              <div className="fc-shot-viewport">
                {shotLoading ? (
                  <div className="fc-shot-placeholder">正在加载现场截图…</div>
                ) : shotLoadState === 'ready' && shotBlobUrl ? (
                  <button
                    type="button"
                    className="fc-shot-img-btn"
                    onClick={() => setLightboxOpen(true)}
                    aria-label="放大查看现场诊断截图"
                  >
                    <img
                      src={shotBlobUrl}
                      alt={`${targetName} 第 ${selectedAttempt?.attemptNo ?? 1} 次尝试现场截图`}
                      className="fc-shot-img"
                    />
                  </button>
                ) : shotLoadState === 'expired' ? (
                  <div className="fc-shot-placeholder">
                    <span style={{ fontWeight: 600, color: 'var(--body)' }}>
                      该次尝试的现场截图已超过保留期并自动清理
                    </span>
                    <span>历史任务状态与尝试审计记录仍完整保留。</span>
                  </div>
                ) : shotLoadState === 'error' ? (
                  <div className="fc-shot-placeholder">读取现场截图失败，请稍后重试。</div>
                ) : (
                  <div className="fc-shot-placeholder">
                    <span>本次执行未产生异常现场截图</span>
                    <span style={{ fontSize: 11.5 }}>
                      仅定位失败、发送异常或结果未确认时截取现场留证，正常成功发送不留存聊天画面。
                    </span>
                  </div>
                )}
              </div>
            </section>

            <section style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              <div
                style={{
                  fontSize: 12,
                  fontWeight: 600,
                  color: 'var(--muted)',
                  display: 'flex',
                  justifyContent: 'space-between',
                }}
              >
                <span>执行与审计时间线（{detail.attempts.length}）</span>
                <span>按先后顺序排列</span>
              </div>

              {detail.attempts.length === 0 ? (
                <div className="fc-shot-placeholder" style={{ border: '1px dashed var(--hairline)', borderRadius: 'var(--radius-md)' }}>
                  尚未产生执行尝试记录
                </div>
              ) : (
                <div className="fc-attempt-list">
                  {detail.attempts.map((att) => {
                    const hasShot = att.screenshotState !== 'none'
                    const isSelected = selectedAttemptNo === att.attemptNo
                    const dur = formatDurationSeconds(att)
                    return (
                      <div
                        key={att.id}
                        className={`fc-attempt-row${isSelected && hasShot ? ' is-selected' : ''}`}
                      >
                        <div style={{ display: 'flex', flexDirection: 'column', gap: 2, minWidth: 0 }}>
                          <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
                            <span className="fc-mono" style={{ fontWeight: 600 }}>
                              #{att.attemptNo}
                            </span>
                            <span style={{ fontWeight: 600 }}>{formatAttemptStage(att.stage)}</span>
                            {att.failureClass && (
                              <span style={{ fontSize: 11.5, color: 'var(--warn)' }}>
                                · {FAILURE_CLASS_LABELS[att.failureClass]}
                              </span>
                            )}
                          </div>
                          <div className="fc-mono" style={{ fontSize: 11.5, color: 'var(--muted)' }}>
                            {formatDateTime(att.startedAt)}
                            {dur ? ` · 耗时 ${dur}` : ''}
                          </div>
                        </div>

                        <div style={{ display: 'inline-flex', alignItems: 'center', gap: 6, flexShrink: 0 }}>
                          {hasShot && (
                            <button
                              type="button"
                              className="fc-btn fc-btn-ghost fc-btn-sm"
                              style={{ height: 24, padding: '0 8px' }}
                              onClick={() => {
                                setLightboxOpen(false)
                                setSelectedAttemptNo(att.attemptNo)
                              }}
                            >
                              {att.screenshotState === 'available'
                                ? isSelected
                                  ? '当前截图'
                                  : '查看截图'
                                : '截图已清理'}
                            </button>
                          )}
                          <StatusPill tone={ATTEMPT_STATUS_TONES[att.status]}>
                            {ATTEMPT_STATUS_LABELS[att.status]}
                          </StatusPill>
                        </div>
                      </div>
                    )
                  })}
                </div>
              )}
            </section>
          </>
        )}
      </DrawerShell>

      {lightboxOpen && shotBlobUrl && (
        <div
          className="fc-lightbox-backdrop"
          role="dialog"
          aria-modal="true"
          aria-label="原尺寸现场诊断截图"
          onClick={() => setLightboxOpen(false)}
        >
          <div className="fc-lightbox-dialog" onClick={(e) => e.stopPropagation()}>
            <header className="fc-lightbox-header">
              <span>
                {targetName} · 第 {selectedAttempt?.attemptNo ?? 1} 次尝试现场截图
              </span>
              <button
                type="button"
                className="fc-btn fc-btn-ghost fc-btn-icon"
                aria-label="关闭大图预览"
                title="关闭大图预览 (Esc)"
                onClick={() => setLightboxOpen(false)}
              >
                <CloseIcon />
              </button>
            </header>
            <div className="fc-lightbox-body">
              <img
                src={shotBlobUrl}
                alt={`${targetName} 原尺寸现场诊断截图`}
                className="fc-lightbox-img"
              />
            </div>
          </div>
        </div>
      )}
    </>
  )
}
