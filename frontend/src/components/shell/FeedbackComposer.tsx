import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ChangeEvent,
  type ClipboardEvent as ReactClipboardEvent,
  type DragEvent,
  type FormEvent,
  type MouseEvent,
} from 'react'
import { isTauriDesktop, openDesktopExternalUrl } from '../../lib/desktop.ts'
import {
  FEEDBACK_KIND_OPTIONS,
  FEEDBACK_LIMITS,
  arePersistedFeedbackDraftsEqual,
  buildFeedbackFallbackText,
  buildWebDiagnosticLog,
  buildWebEnvSummary,
  clearPersistedFeedbackDraft,
  createDefaultPersistedFeedbackDraft,
  getConfiguredFeedbackEndpoint,
  hasPersistedFeedbackDraft,
  loadPersistedFeedbackDraft,
  savePersistedFeedbackDraft,
  submitInAppFeedback,
  validateDiagnosticLog,
  type FeedbackKind,
  type FeedbackSubmitOutcome,
  type FeedbackSubmitPayload,
  type PersistedFeedbackDraft,
} from '../../lib/feedback.ts'
import {
  extractClipboardImageFiles,
  formatImageByteSize,
  prepareFeedbackImageBatch,
  revokeFeedbackImagePreviews,
  type PreparedFeedbackImage,
} from '../../lib/feedbackImages.ts'
import { buildBugReportUrl } from '../../lib/links.ts'
import {
  CheckIcon,
  ChevronDownIcon,
  CloseIcon,
  CopyIcon,
  ExternalLinkIcon,
  FeedbackIcon,
  ImageIcon,
  ShieldAlertIcon,
} from '../ui/Icons.tsx'

export interface FeedbackComposerProps {
  isDesktop: boolean
  appVersionLabel: string
  browserRuntimeLabel?: string | null
  idPrefix?: string
  captureWindowPaste?: boolean
  showUnconfiguredNotice?: boolean
  onNotice?: (message: string) => void
  onSubmitted?: (outcome: Extract<FeedbackSubmitOutcome, { ok: true }>) => void
}

const FEEDBACK_DRAFT_SYNC_EVENT = 'flame-feedback-draft-sync'

interface FeedbackDraftSyncDetail {
  sourceId: string
  draft: PersistedFeedbackDraft
}

function dispatchFeedbackDraftSync(sourceId: string, draft: PersistedFeedbackDraft): void {
  if (typeof window === 'undefined' || typeof CustomEvent === 'undefined') return
  window.dispatchEvent(
    new CustomEvent<FeedbackDraftSyncDetail>(FEEDBACK_DRAFT_SYNC_EVENT, {
      detail: { sourceId, draft },
    }),
  )
}

function isEditableTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  const tag = target.tagName
  return tag === 'INPUT' || tag === 'TEXTAREA' || target.isContentEditable
}

function composeDiagnosticLogText(localSummary: string, externalLog: string): string {
  const cleanExternal = externalLog.trim()
  if (!cleanExternal) return localSummary
  const combined = `${localSummary}\n\n--- 附加诊断日志 ---\n${cleanExternal}`
  if (combined.length <= FEEDBACK_LIMITS.maxDiagnosticLog) {
    return combined
  }
  return cleanExternal
}

export function FeedbackComposer({
  isDesktop,
  appVersionLabel,
  browserRuntimeLabel = null,
  idPrefix = 'fc-fb',
  captureWindowPaste = false,
  showUnconfiguredNotice = false,
  onNotice,
  onSubmitted,
}: FeedbackComposerProps) {
  const feedbackEndpoint = useMemo(() => getConfiguredFeedbackEndpoint(), [])
  const bugReportUrl = useMemo(
    () => buildBugReportUrl({ version: appVersionLabel, isDesktop }),
    [appVersionLabel, isDesktop],
  )

  const initialDraft = useMemo(() => loadPersistedFeedbackDraft(), [])
  const [kind, setKind] = useState<FeedbackKind>(initialDraft.kind)
  const [title, setTitle] = useState(initialDraft.title)
  const [content, setContent] = useState(initialDraft.content)
  const [includeEnv, setIncludeEnv] = useState(initialDraft.includeEnv)
  const [includeDiagnosticLog, setIncludeDiagnosticLog] = useState(
    initialDraft.includeDiagnosticLog,
  )
  const [diagExpanded, setDiagExpanded] = useState(false)
  const [externalDiagnosticLog, setExternalDiagnosticLog] = useState(
    initialDraft.externalDiagnosticLog,
  )
  const [diagnosticLogError, setDiagnosticLogError] = useState<string | null>(null)
  const [copiedDiagnosticLog, setCopiedDiagnosticLog] = useState(false)
  const [diagSnapshotTick, setDiagSnapshotTick] = useState(0)
  const [images, setImages] = useState<PreparedFeedbackImage[]>([])
  const [processingImages, setProcessingImages] = useState(false)
  const [imageError, setImageError] = useState<string | null>(null)
  const [isDragOver, setIsDragOver] = useState(false)

  const [submitting, setSubmitting] = useState(false)
  const [outcome, setOutcome] = useState<FeedbackSubmitOutcome | null>(null)
  const [contentError, setContentError] = useState<string | null>(null)
  const [copiedDraft, setCopiedDraft] = useState(false)
  const [copyFeedbackError, setCopyFeedbackError] = useState<string | null>(null)

  const instanceIdRef = useRef(`${idPrefix}-${Math.random().toString(36).slice(2)}`)
  const lastSyncedDraftRef = useRef<PersistedFeedbackDraft>(initialDraft)
  const fileInputRef = useRef<HTMLInputElement | null>(null)
  const contentRef = useRef<HTMLTextAreaElement | null>(null)
  const diagTextareaRef = useRef<HTMLTextAreaElement | null>(null)
  const copyTimerRef = useRef<number | null>(null)
  const copyDiagTimerRef = useRef<number | null>(null)
  const dragDepthRef = useRef(0)
  const mountedRef = useRef(true)
  const imagesRef = useRef<PreparedFeedbackImage[]>([])
  const previewUrlsRef = useRef<Set<string>>(new Set())
  const processingRef = useRef(false)
  const submittingRef = useRef(false)

  imagesRef.current = images
  processingRef.current = processingImages
  submittingRef.current = submitting

  const currentDraft = useMemo<PersistedFeedbackDraft>(
    () => ({
      kind,
      title,
      content,
      includeEnv,
      includeDiagnosticLog,
      externalDiagnosticLog,
    }),
    [kind, title, content, includeEnv, includeDiagnosticLog, externalDiagnosticLog],
  )

  useEffect(() => {
    if (arePersistedFeedbackDraftsEqual(currentDraft, lastSyncedDraftRef.current)) {
      return
    }
    lastSyncedDraftRef.current = currentDraft
    savePersistedFeedbackDraft(currentDraft)
    dispatchFeedbackDraftSync(instanceIdRef.current, currentDraft)
  }, [currentDraft])

  useEffect(() => {
    if (typeof window === 'undefined') return undefined
    function handleDraftSync(event: Event) {
      const detail = (event as CustomEvent<FeedbackDraftSyncDetail>).detail
      if (!detail || detail.sourceId === instanceIdRef.current || !detail.draft) {
        return
      }
      if (arePersistedFeedbackDraftsEqual(detail.draft, lastSyncedDraftRef.current)) {
        return
      }
      lastSyncedDraftRef.current = detail.draft
      setKind(detail.draft.kind)
      setTitle(detail.draft.title)
      setContent(detail.draft.content)
      setIncludeEnv(detail.draft.includeEnv)
      setIncludeDiagnosticLog(detail.draft.includeDiagnosticLog)
      setExternalDiagnosticLog(detail.draft.externalDiagnosticLog)
    }
    window.addEventListener(FEEDBACK_DRAFT_SYNC_EVENT, handleDraftSync)
    return () => window.removeEventListener(FEEDBACK_DRAFT_SYNC_EVENT, handleDraftSync)
  }, [])

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
      if (copyTimerRef.current !== null) {
        window.clearTimeout(copyTimerRef.current)
      }
      if (copyDiagTimerRef.current !== null) {
        window.clearTimeout(copyDiagTimerRef.current)
      }
      if (typeof URL !== 'undefined' && typeof URL.revokeObjectURL === 'function') {
        for (const url of previewUrlsRef.current) {
          URL.revokeObjectURL(url)
        }
      }
      previewUrlsRef.current.clear()
    }
  }, [])

  function handleExternalClick(event: MouseEvent<HTMLAnchorElement>, url: string) {
    if (!isTauriDesktop()) return
    event.preventDefault()
    void openDesktopExternalUrl(url).catch(() => {
      window.open(url, '_blank', 'noopener,noreferrer')
    })
  }

  const clearAllImages = useCallback(() => {
    const current = imagesRef.current
    if (current.length > 0) {
      revokeFeedbackImagePreviews(current)
      for (const item of current) {
        previewUrlsRef.current.delete(item.previewUrl)
      }
    }
    setImages([])
    setImageError(null)
  }, [])

  const removeImageById = useCallback((id: string) => {
    setImages((prev) => {
      const target = prev.find((item) => item.id === id)
      if (target) {
        revokeFeedbackImagePreviews([target])
        previewUrlsRef.current.delete(target.previewUrl)
      }
      return prev.filter((item) => item.id !== id)
    })
    setImageError(null)
  }, [])

  const handleAddFiles = useCallback(async (incoming: File[]) => {
    if (incoming.length === 0 || submittingRef.current || processingRef.current) return
    processingRef.current = true
    setProcessingImages(true)
    setImageError(null)
    setOutcome((prev) => (prev && !prev.ok ? null : prev))

    try {
      const result = await prepareFeedbackImageBatch(incoming, imagesRef.current.length)
      if (!mountedRef.current) {
        revokeFeedbackImagePreviews(result.added)
        return
      }
      if (result.added.length > 0) {
        for (const item of result.added) {
          previewUrlsRef.current.add(item.previewUrl)
        }
        setImages((prev) => [...prev, ...result.added])
      }
      if (result.errors.length > 0) {
        setImageError(result.errors.join('；'))
      }
    } finally {
      processingRef.current = false
      if (mountedRef.current) {
        setProcessingImages(false)
      }
    }
  }, [])

  const handleClipboardData = useCallback(
    (
      clipboardData: DataTransfer | null,
      target: EventTarget | null,
      preventDefault: () => void,
    ) => {
      if (!clipboardData || submittingRef.current || processingRef.current) return
      const pastedImages = extractClipboardImageFiles(clipboardData)
      if (pastedImages.length === 0) return

      const plainText = clipboardData.getData('text/plain').trim()
      if (plainText && isEditableTarget(target)) {
        return
      }

      preventDefault()
      void handleAddFiles(pastedImages)
    },
    [handleAddFiles],
  )

  useEffect(() => {
    if (!captureWindowPaste) return undefined
    function onWindowPaste(event: globalThis.ClipboardEvent) {
      handleClipboardData(event.clipboardData, event.target, () => event.preventDefault())
    }
    window.addEventListener('paste', onWindowPaste)
    return () => window.removeEventListener('paste', onWindowPaste)
  }, [captureWindowPaste, handleClipboardData])

  function handleFormPaste(event: ReactClipboardEvent<HTMLFormElement>) {
    if (captureWindowPaste) return
    handleClipboardData(event.clipboardData, event.target, () => event.preventDefault())
  }

  function handleFileInputChange(event: ChangeEvent<HTMLInputElement>) {
    const selected = event.target.files ? Array.from(event.target.files) : []
    event.target.value = ''
    if (selected.length > 0) {
      void handleAddFiles(selected)
    }
  }

  function handleDragEnter(event: DragEvent<HTMLDivElement>) {
    if (!event.dataTransfer.types.includes('Files')) return
    event.preventDefault()
    dragDepthRef.current += 1
    setIsDragOver(true)
  }

  function handleDragOver(event: DragEvent<HTMLDivElement>) {
    if (!event.dataTransfer.types.includes('Files')) return
    event.preventDefault()
    event.dataTransfer.dropEffect = 'copy'
  }

  function handleDragLeave(event: DragEvent<HTMLDivElement>) {
    if (!event.dataTransfer.types.includes('Files')) return
    event.preventDefault()
    dragDepthRef.current = Math.max(0, dragDepthRef.current - 1)
    if (dragDepthRef.current === 0) {
      setIsDragOver(false)
    }
  }

  function handleDrop(event: DragEvent<HTMLDivElement>) {
    event.preventDefault()
    dragDepthRef.current = 0
    setIsDragOver(false)
    const dropped = event.dataTransfer.files ? Array.from(event.dataTransfer.files) : []
    if (dropped.length > 0) {
      void handleAddFiles(dropped)
    }
  }

  const localDiagnosticSummary = useMemo(() => {
    void diagSnapshotTick
    return buildWebDiagnosticLog({
      version: appVersionLabel,
      isDesktop,
      browserRuntimeLabel,
    })
  }, [appVersionLabel, browserRuntimeLabel, diagSnapshotTick, isDesktop])

  const previewDiagnosticLog = useMemo(
    () => composeDiagnosticLogText(localDiagnosticSummary, externalDiagnosticLog),
    [externalDiagnosticLog, localDiagnosticSummary],
  )

  function buildFreshDiagnosticLog(): string {
    const freshSummary = buildWebDiagnosticLog({
      version: appVersionLabel,
      isDesktop,
      browserRuntimeLabel,
    })
    return composeDiagnosticLogText(freshSummary, externalDiagnosticLog)
  }

  function buildCurrentPayload(): FeedbackSubmitPayload {
    const diagnosticLog = includeDiagnosticLog ? buildFreshDiagnosticLog() : undefined
    return {
      kind,
      title,
      content,
      contact: '',
      includeEnv,
      includeDiagnosticLog,
      diagnosticLog,
      client: isDesktop ? 'desktop' : 'web',
      version: appVersionLabel,
      environment: buildWebEnvSummary({
        version: appVersionLabel,
        isDesktop,
        browserRuntimeLabel,
      }),
      images:
        images.length > 0
          ? images.map((img) => ({
              name: img.name,
              mimeType: img.mimeType,
              dataBase64: img.dataBase64,
            }))
          : undefined,
    }
  }

  async function handleCopyDraft() {
    const text = buildFeedbackFallbackText(buildCurrentPayload())
    setCopyFeedbackError(null)
    try {
      await navigator.clipboard.writeText(text)
      setCopiedDraft(true)
      if (copyTimerRef.current !== null) {
        window.clearTimeout(copyTimerRef.current)
      }
      copyTimerRef.current = window.setTimeout(() => {
        setCopiedDraft(false)
      }, 1800)
    } catch {
      const msg = '无法访问剪贴板，请手动复制已填写内容'
      setCopyFeedbackError(msg)
      onNotice?.(msg)
    }
  }

  async function handleCopyDiagnosticLog() {
    const text = buildFreshDiagnosticLog()
    setCopyFeedbackError(null)
    try {
      await navigator.clipboard.writeText(text)
      setCopiedDiagnosticLog(true)
      if (copyDiagTimerRef.current !== null) {
        window.clearTimeout(copyDiagTimerRef.current)
      }
      copyDiagTimerRef.current = window.setTimeout(() => {
        setCopiedDiagnosticLog(false)
      }, 1800)
    } catch {
      const msg = '无法访问剪贴板，请展开诊断摘要后手动复制'
      setCopyFeedbackError(msg)
      onNotice?.(msg)
    }
  }

  function buildShortContentMessage(trimmedLen: number): string {
    if (trimmedLen <= 0) {
      return `请先填写至少 ${FEEDBACK_LIMITS.minContent} 个字的详细描述再提交`
    }
    const remaining = Math.max(1, FEEDBACK_LIMITS.minContent - trimmedLen)
    return `详细描述还差 ${remaining} 个字即可提交（至少 ${FEEDBACK_LIMITS.minContent} 个字）`
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (submitting || processingImages) return
    const trimmedLen = content.trim().length
    if (trimmedLen < FEEDBACK_LIMITS.minContent) {
      const msg = buildShortContentMessage(trimmedLen)
      setContentError(msg)
      setOutcome({ ok: false, error: msg, retryable: false })
      contentRef.current?.focus()
      contentRef.current?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
      return
    }
    const payload = buildCurrentPayload()
    if (includeDiagnosticLog) {
      const diagErr = validateDiagnosticLog(payload.diagnosticLog)
      if (diagErr) {
        setDiagnosticLogError(diagErr)
        setDiagExpanded(true)
        setOutcome({ ok: false, error: diagErr, retryable: false })
        window.requestAnimationFrame(() => {
          diagTextareaRef.current?.focus()
          diagTextareaRef.current?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
        })
        return
      }
    }
    setContentError(null)
    setDiagnosticLogError(null)
    setSubmitting(true)
    setOutcome(null)
    setCopyFeedbackError(null)
    try {
      const result = await submitInAppFeedback(feedbackEndpoint, payload)
      if (result.ok) {
        const emptyDraft = createDefaultPersistedFeedbackDraft()
        lastSyncedDraftRef.current = emptyDraft
        clearPersistedFeedbackDraft()
        dispatchFeedbackDraftSync(instanceIdRef.current, emptyDraft)
        if (!mountedRef.current) return
        setOutcome(result)
        setKind(emptyDraft.kind)
        setTitle(emptyDraft.title)
        setContent(emptyDraft.content)
        setIncludeEnv(emptyDraft.includeEnv)
        setIncludeDiagnosticLog(emptyDraft.includeDiagnosticLog)
        setExternalDiagnosticLog(emptyDraft.externalDiagnosticLog)
        setContentError(null)
        setDiagnosticLogError(null)
        clearAllImages()
        onSubmitted?.(result)
        return
      }
      if (!mountedRef.current) return
      setOutcome(result)
    } finally {
      if (mountedRef.current) {
        setSubmitting(false)
      }
    }
  }

  const remainingSlots = FEEDBACK_LIMITS.maxImages - images.length
  const trimmedContentLen = content.trim().length
  const missingContentChars = Math.max(0, FEEDBACK_LIMITS.minContent - trimmedContentLen)
  const diagnosticLogLen = previewDiagnosticLog.length
  const diagnosticLogTooLong = diagnosticLogLen > FEEDBACK_LIMITS.maxDiagnosticLog
  const hasCopyableText = Boolean(title.trim() || content.trim())
  const hasDraft = hasPersistedFeedbackDraft(currentDraft) || images.length > 0
  const titleId = `${idPrefix}-title`
  const contentId = `${idPrefix}-content`
  const fileInputId = `${idPrefix}-images`
  const diagPanelId = `${idPrefix}-diag-panel`
  const diagInputId = `${idPrefix}-diag-input`
  const activeKindMeta = FEEDBACK_KIND_OPTIONS.find((opt) => opt.kind === kind)

  return (
    <form
      className="fc-feedback-form"
      onSubmit={(e) => void handleSubmit(e)}
      onPaste={handleFormPaste}
    >
      {showUnconfiguredNotice && !feedbackEndpoint && (
        <div className="fc-feedback-mode-note">
          当前构建未绑定直连反馈中转地址，你可先填写内容与截图说明，再点击「复制完整反馈」前往 GitHub 提交。
        </div>
      )}

      <div className="fc-feedback-kinds" role="group" aria-label="反馈类型">
        {FEEDBACK_KIND_OPTIONS.map((opt) => (
          <button
            key={opt.kind}
            type="button"
            className={`fc-feedback-kind-pill${kind === opt.kind ? ' is-active' : ''}`}
            aria-pressed={kind === opt.kind}
            disabled={submitting}
            onClick={() => {
              setKind(opt.kind)
              if (outcome && !outcome.ok) setOutcome(null)
            }}
          >
            {opt.label}
          </button>
        ))}
      </div>

      <div className="fc-field">
        <label className="fc-label" htmlFor={titleId}>
          <span>简短标题</span>
          <span className="fc-hint">可选</span>
        </label>
        <input
          id={titleId}
          className="fc-input"
          maxLength={FEEDBACK_LIMITS.maxTitle}
          placeholder="例如：定时发送后统计未即时刷新"
          value={title}
          disabled={submitting}
          onChange={(e) => setTitle(e.target.value)}
        />
      </div>

      <div className="fc-field">
        <label className="fc-label" htmlFor={contentId}>
          <span>详细描述（必填，至少 {FEEDBACK_LIMITS.minContent} 字）</span>
          <span
            className={`fc-hint fc-mono fc-feedback-char-count${
              contentError ? ' is-err' : trimmedContentLen > 0 && missingContentChars > 0 ? ' is-short' : ''
            }`}
          >
            {trimmedContentLen > 0 && missingContentChars > 0
              ? `还差 ${missingContentChars} 字 · ${trimmedContentLen}/${FEEDBACK_LIMITS.maxContent}`
              : `${trimmedContentLen}/${FEEDBACK_LIMITS.maxContent}`}
          </span>
        </label>
        <textarea
          ref={contentRef}
          id={contentId}
          rows={3}
          maxLength={FEEDBACK_LIMITS.maxContent}
          className={`fc-textarea fc-feedback-textarea${contentError ? ' is-invalid' : ''}`}
          aria-invalid={Boolean(contentError)}
          aria-describedby={contentError ? `${contentId}-error` : undefined}
          placeholder={activeKindMeta?.placeholder}
          value={content}
          disabled={submitting}
          onChange={(e) => {
            const next = e.target.value
            setContent(next)
            const nextTrimmedLen = next.trim().length
            if (nextTrimmedLen >= FEEDBACK_LIMITS.minContent) {
              setContentError(null)
            } else if (contentError) {
              setContentError(buildShortContentMessage(nextTrimmedLen))
            }
            if (outcome && !outcome.ok) setOutcome(null)
          }}
        />
        {contentError && (
          <div id={`${contentId}-error`} className="fc-feedback-field-error" role="alert">
            {contentError}
          </div>
        )}
      </div>

      {/* Screenshot Upload Section */}
      <div className="fc-field">
        <div className="fc-label">
          <span>问题截图（可选）</span>
          <span className="fc-hint fc-mono">
            {images.length}/{FEEDBACK_LIMITS.maxImages} 张 · 单张 ≤ 800 KB
          </span>
        </div>

        <div
          className={`fc-feedback-dropzone${isDragOver ? ' is-dragover' : ''}${
            images.length > 0 ? ' is-compact' : ''
          }${remainingSlots <= 0 ? ' is-full' : ''}`}
          onDragEnter={handleDragEnter}
          onDragOver={handleDragOver}
          onDragLeave={handleDragLeave}
          onDrop={handleDrop}
        >
          <input
            ref={fileInputRef}
            id={fileInputId}
            type="file"
            accept="image/png,image/jpeg,image/webp"
            multiple
            tabIndex={-1}
            aria-hidden="true"
            className="fc-sr-only"
            disabled={submitting || processingImages || remainingSlots <= 0}
            onChange={handleFileInputChange}
          />

          <button
            type="button"
            className="fc-feedback-dropzone-trigger"
            disabled={submitting || processingImages || remainingSlots <= 0}
            onClick={() => fileInputRef.current?.click()}
          >
            <span className="fc-feedback-dropzone-icon" aria-hidden="true">
              <ImageIcon width={16} height={16} />
            </span>
            <span className="fc-feedback-dropzone-copy">
              <span className="fc-feedback-dropzone-title">
                {processingImages
                  ? '正在压缩截图并清除 EXIF…'
                  : isDragOver
                    ? '松开鼠标即可添加截图'
                    : remainingSlots <= 0
                      ? `已达到 ${FEEDBACK_LIMITS.maxImages} 张截图上限`
                      : images.length > 0
                        ? `继续添加截图（还可添加 ${remainingSlots} 张）`
                        : '点击选择截图、拖拽至此，或直接按 Ctrl+V 粘贴'}
              </span>
              <span className="fc-feedback-dropzone-sub">
                支持 PNG / JPG / WebP · 自动缩放至 1600px 内并清除 EXIF 元数据
              </span>
            </span>
            {remainingSlots > 0 && !processingImages && (
              <kbd className="fc-feedback-kbd fc-mono">Ctrl+V</kbd>
            )}
          </button>
        </div>

        {imageError && (
          <div className="fc-feedback-image-error" role="alert">
            <span>{imageError}</span>
            <button
              type="button"
              className="fc-feedback-inline-link"
              onClick={() => setImageError(null)}
            >
              关闭
            </button>
          </div>
        )}

        {images.length > 0 && (
          <ul className="fc-feedback-thumbs" aria-label="已选择的反馈截图">
            {images.map((item) => (
              <li key={item.id} className="fc-feedback-thumb">
                <div className="fc-feedback-thumb-frame">
                  <img
                    src={item.previewUrl}
                    alt={item.name}
                    className="fc-feedback-thumb-img"
                  />
                  <button
                    type="button"
                    className="fc-feedback-thumb-remove"
                    aria-label={`移除截图 ${item.name}`}
                    title={`移除 ${item.name}`}
                    disabled={submitting}
                    onClick={() => removeImageById(item.id)}
                  >
                    <CloseIcon width={12} height={12} />
                  </button>
                </div>
                <div className="fc-feedback-thumb-meta">
                  <span className="fc-feedback-thumb-name" title={item.name}>
                    {item.name}
                  </span>
                  <span className="fc-feedback-thumb-size fc-mono">
                    {formatImageByteSize(item.sizeBytes)}
                  </span>
                </div>
              </li>
            ))}
          </ul>
        )}

        <div className="fc-feedback-privacy">
          <ShieldAlertIcon width={14} height={14} className="fc-feedback-privacy-icon" />
          <span>
            反馈内容与截图会公开显示在 GitHub Issues，请勿填写手机号、微信号等个人隐私（截图也请先遮挡敏感信息）；问题修复将通过版本更新推送。
          </span>
        </div>
      </div>

      <label className="fc-feedback-env-check">
        <input
          type="checkbox"
          checked={includeEnv}
          disabled={submitting}
          onChange={(e) => setIncludeEnv(e.target.checked)}
        />
        <span>附带脱敏运行环境信息（仅版本号与浏览器内核，不含账号与好友隐私）</span>
      </label>

      <div className="fc-feedback-diag">
        <div className="fc-feedback-diag-bar">
          <label className="fc-feedback-env-check fc-feedback-diag-check">
            <input
              type="checkbox"
              checked={includeDiagnosticLog}
              disabled={submitting}
              onChange={(e) => {
                const next = e.target.checked
                setIncludeDiagnosticLog(next)
                if (!next) {
                  setDiagnosticLogError(null)
                } else {
                  setDiagSnapshotTick((n) => n + 1)
                }
                if (outcome && !outcome.ok) setOutcome(null)
              }}
            />
            <span>
              附带脱敏诊断日志
              {externalDiagnosticLog.trim()
                ? '（含已粘贴日志）'
                : '（可选本机摘要或粘贴他端日志）'}
            </span>
          </label>

          <div className="fc-feedback-diag-actions">
            <button
              type="button"
              className={`fc-feedback-diag-btn${diagExpanded ? ' is-active' : ''}`}
              aria-expanded={diagExpanded}
              aria-controls={diagPanelId}
              disabled={submitting}
              onClick={() => {
                setDiagExpanded((prev) => {
                  if (!prev) setDiagSnapshotTick((n) => n + 1)
                  return !prev
                })
              }}
            >
              <span>{diagExpanded ? '收起' : '预览 / 粘贴'}</span>
              <ChevronDownIcon
                width={12}
                height={12}
                className={`fc-feedback-diag-chevron${diagExpanded ? ' is-open' : ''}`}
              />
            </button>
            <button
              type="button"
              className={`fc-feedback-diag-btn${copiedDiagnosticLog ? ' is-copied' : ''}`}
              disabled={submitting}
              onClick={() => void handleCopyDiagnosticLog()}
              title="复制完整脱敏诊断摘要，可直接发给维护者"
            >
              {copiedDiagnosticLog ? (
                <>
                  <CheckIcon width={12} height={12} />
                  <span>已复制</span>
                </>
              ) : (
                <>
                  <CopyIcon width={12} height={12} />
                  <span>复制摘要</span>
                </>
              )}
            </button>
          </div>
        </div>

        {diagExpanded && (
          <div id={diagPanelId} className="fc-feedback-diag-panel">
            <div className="fc-feedback-diag-section">
              <div className="fc-feedback-diag-head">
                <span>本机脱敏诊断摘要（自动生成）</span>
                <span className="fc-hint">不含 Cookie、Token 或好友昵称</span>
              </div>
              <pre className="fc-feedback-diag-pre fc-mono">{localDiagnosticSummary}</pre>
            </div>

            <div className="fc-feedback-diag-section">
              <label className="fc-feedback-diag-head" htmlFor={diagInputId}>
                <span>补充或粘贴他端诊断日志（可选）</span>
                <span
                  className={`fc-hint fc-mono fc-feedback-char-count${
                    diagnosticLogError || diagnosticLogTooLong ? ' is-err' : ''
                  }`}
                >
                  {diagnosticLogLen}/{FEEDBACK_LIMITS.maxDiagnosticLog}
                </span>
              </label>
              <textarea
                ref={diagTextareaRef}
                id={diagInputId}
                rows={3}
                className={`fc-textarea fc-mono fc-feedback-diag-textarea${
                  diagnosticLogError || diagnosticLogTooLong ? ' is-invalid' : ''
                }`}
                aria-invalid={Boolean(diagnosticLogError || diagnosticLogTooLong)}
                aria-describedby={diagnosticLogError ? `${diagInputId}-error` : undefined}
                placeholder="可粘贴从 Android 端或其他设备复制的脱敏诊断日志；留空时仅附带上方本机摘要…"
                value={externalDiagnosticLog}
                disabled={submitting}
                onChange={(e) => {
                  const next = e.target.value
                  setExternalDiagnosticLog(next)
                  if (next.trim() && !includeDiagnosticLog) {
                    setIncludeDiagnosticLog(true)
                  }
                  const nextComposed = composeDiagnosticLogText(localDiagnosticSummary, next)
                  setDiagnosticLogError(validateDiagnosticLog(nextComposed))
                  if (outcome && !outcome.ok) setOutcome(null)
                }}
              />
              <div className="fc-feedback-diag-foot">
                <span className="fc-hint">
                  将以折叠块写入 Issue，上限 {FEEDBACK_LIMITS.maxDiagnosticLog} 字
                </span>
                {externalDiagnosticLog.trim() && (
                  <button
                    type="button"
                    className="fc-feedback-inline-link"
                    disabled={submitting}
                    onClick={() => {
                      setExternalDiagnosticLog('')
                      setDiagnosticLogError(null)
                    }}
                  >
                    清空粘贴日志
                  </button>
                )}
              </div>
              {diagnosticLogError && (
                <div
                  id={`${diagInputId}-error`}
                  className="fc-feedback-field-error"
                  role="alert"
                >
                  {diagnosticLogError}
                </div>
              )}
            </div>
          </div>
        )}
      </div>

      {copyFeedbackError && (
        <div className="fc-feedback-status is-err" role="alert">
          <div>{copyFeedbackError}</div>
        </div>
      )}

      {outcome && (
        <div
          className={`fc-feedback-status ${outcome.ok ? 'is-ok' : 'is-err'}`}
          role={outcome.ok ? 'status' : 'alert'}
        >
          <div>{outcome.ok ? outcome.message : outcome.error}</div>
          <div className="fc-feedback-status-actions">
            {outcome.ok && outcome.issueNumber !== null && (
              <span className="fc-mono fc-feedback-issue-tag">
                #{outcome.issueNumber}
                {outcome.imageCount > 0 ? ` · ${outcome.imageCount} 张截图` : ''}
                {outcome.hasDiagnosticLog ? ' · 已附诊断日志' : ''}
              </span>
            )}
            {outcome.ok && outcome.issueNumber === null && outcome.hasDiagnosticLog && (
              <span className="fc-mono fc-feedback-issue-tag">已附诊断日志</span>
            )}
            {outcome.ok && outcome.issueUrl && (
              <a
                href={outcome.issueUrl}
                target="_blank"
                rel="noopener noreferrer"
                className="fc-feedback-inline-link"
                onClick={(e) => handleExternalClick(e, outcome.issueUrl as string)}
              >
                在 GitHub 查看进展 →
              </a>
            )}
            {!outcome.ok && hasCopyableText && (
              <button
                type="button"
                className="fc-feedback-inline-link"
                onClick={() => void handleCopyDraft()}
              >
                {copiedDraft ? '✓ 已复制反馈文本' : '复制已填反馈内容'}
              </button>
            )}
          </div>
        </div>
      )}

      <div className="fc-support-actions">
        {feedbackEndpoint ? (
          <>
            <button
              type="submit"
              className="fc-btn fc-btn-primary fc-btn-sm"
              disabled={submitting || processingImages}
            >
              <FeedbackIcon width={14} height={14} />
              {submitting ? '提交中…' : '直接提交反馈'}
            </button>
            <button
              type="button"
              className="fc-btn fc-btn-secondary fc-btn-sm"
              disabled={submitting || !content.trim()}
              onClick={() => void handleCopyDraft()}
            >
              {copiedDraft ? (
                <>
                  <CheckIcon width={14} height={14} /> 已复制文本
                </>
              ) : (
                <>
                  <CopyIcon width={14} height={14} /> 复制文本
                </>
              )}
            </button>
          </>
        ) : (
          <>
            <button
              type="button"
              className="fc-btn fc-btn-primary fc-btn-sm"
              disabled={processingImages}
              onClick={() => {
                if (!content.trim()) {
                  const emptyMsg = '请先填写反馈内容后再复制'
                  setContentError(emptyMsg)
                  contentRef.current?.focus()
                  return
                }
                void handleCopyDraft()
              }}
            >
              {copiedDraft ? (
                <>
                  <CheckIcon width={14} height={14} /> 已复制反馈
                </>
              ) : (
                <>
                  <CopyIcon width={14} height={14} /> 复制完整反馈
                </>
              )}
            </button>
            <a
              href={bugReportUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="fc-btn fc-btn-secondary fc-btn-sm"
              onClick={(e) => handleExternalClick(e, bugReportUrl)}
            >
              <span>GitHub 提交</span>
              <ExternalLinkIcon width={13} height={13} />
            </a>
          </>
        )}

        {hasDraft && !submitting && !processingImages && (
          <button
            type="button"
            className="fc-btn fc-btn-ghost fc-btn-sm"
            onClick={() => {
              const emptyDraft = createDefaultPersistedFeedbackDraft()
              lastSyncedDraftRef.current = emptyDraft
              clearPersistedFeedbackDraft()
              dispatchFeedbackDraftSync(instanceIdRef.current, emptyDraft)
              setKind(emptyDraft.kind)
              setTitle(emptyDraft.title)
              setContent(emptyDraft.content)
              setIncludeEnv(emptyDraft.includeEnv)
              setIncludeDiagnosticLog(emptyDraft.includeDiagnosticLog)
              setExternalDiagnosticLog(emptyDraft.externalDiagnosticLog)
              setDiagnosticLogError(null)
              clearAllImages()
              setContentError(null)
              setOutcome(null)
              setCopyFeedbackError(null)
            }}
          >
            清空
          </button>
        )}
      </div>
    </form>
  )
}
