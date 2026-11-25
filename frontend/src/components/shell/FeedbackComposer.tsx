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
  buildFeedbackFallbackText,
  buildWebEnvSummary,
  getConfiguredFeedbackEndpoint,
  submitInAppFeedback,
  type FeedbackKind,
  type FeedbackSubmitOutcome,
  type FeedbackSubmitPayload,
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

function isEditableTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  const tag = target.tagName
  return tag === 'INPUT' || tag === 'TEXTAREA' || target.isContentEditable
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

  const [kind, setKind] = useState<FeedbackKind>('bug')
  const [title, setTitle] = useState('')
  const [content, setContent] = useState('')
  const [contact, setContact] = useState('')
  const [includeEnv, setIncludeEnv] = useState(true)
  const [images, setImages] = useState<PreparedFeedbackImage[]>([])
  const [processingImages, setProcessingImages] = useState(false)
  const [imageError, setImageError] = useState<string | null>(null)
  const [isDragOver, setIsDragOver] = useState(false)

  const [submitting, setSubmitting] = useState(false)
  const [outcome, setOutcome] = useState<FeedbackSubmitOutcome | null>(null)
  const [copiedDraft, setCopiedDraft] = useState(false)
  const [copyFeedbackError, setCopyFeedbackError] = useState<string | null>(null)

  const fileInputRef = useRef<HTMLInputElement | null>(null)
  const copyTimerRef = useRef<number | null>(null)
  const dragDepthRef = useRef(0)
  const mountedRef = useRef(true)
  const imagesRef = useRef<PreparedFeedbackImage[]>([])
  const previewUrlsRef = useRef<Set<string>>(new Set())
  const processingRef = useRef(false)
  const submittingRef = useRef(false)

  imagesRef.current = images
  processingRef.current = processingImages
  submittingRef.current = submitting

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
      if (copyTimerRef.current !== null) {
        window.clearTimeout(copyTimerRef.current)
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

  function buildCurrentPayload(): FeedbackSubmitPayload {
    return {
      kind,
      title,
      content,
      contact,
      includeEnv,
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

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (submitting || processingImages) return
    setSubmitting(true)
    setOutcome(null)
    setCopyFeedbackError(null)
    try {
      const result = await submitInAppFeedback(feedbackEndpoint, buildCurrentPayload())
      if (!mountedRef.current) return
      setOutcome(result)
      if (result.ok) {
        setTitle('')
        setContent('')
        clearAllImages()
        onSubmitted?.(result)
      }
    } finally {
      if (mountedRef.current) {
        setSubmitting(false)
      }
    }
  }

  const remainingSlots = FEEDBACK_LIMITS.maxImages - images.length
  const hasDraft = Boolean(title || content || contact || images.length > 0)
  const titleId = `${idPrefix}-title`
  const contentId = `${idPrefix}-content`
  const contactId = `${idPrefix}-contact`
  const fileInputId = `${idPrefix}-images`
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
          <span>详细描述（必填）</span>
          <span className="fc-hint fc-mono">
            {content.trim().length}/{FEEDBACK_LIMITS.maxContent}
          </span>
        </label>
        <textarea
          id={contentId}
          rows={3}
          maxLength={FEEDBACK_LIMITS.maxContent}
          className="fc-textarea fc-feedback-textarea"
          placeholder={activeKindMeta?.placeholder}
          value={content}
          disabled={submitting}
          onChange={(e) => {
            setContent(e.target.value)
            if (outcome && !outcome.ok) setOutcome(null)
          }}
        />
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
          <span>截图将随公开 GitHub Issue 展示，请先遮挡隐私或敏感信息</span>
        </div>
      </div>

      <div className="fc-field">
        <label className="fc-label" htmlFor={contactId}>
          <span>联系方式</span>
          <span className="fc-hint">可选，公开 Issue 可见</span>
        </label>
        <input
          id={contactId}
          className="fc-input"
          maxLength={FEEDBACK_LIMITS.maxContact}
          placeholder="邮箱或 GitHub ID，介意可留空"
          value={contact}
          disabled={submitting}
          onChange={(e) => setContact(e.target.value)}
        />
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
              </span>
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
            {!outcome.ok && (
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
              disabled={
                submitting ||
                processingImages ||
                content.trim().length < FEEDBACK_LIMITS.minContent
              }
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
              disabled={!content.trim()}
              onClick={() => void handleCopyDraft()}
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
              setTitle('')
              setContent('')
              setContact('')
              clearAllImages()
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
