import { useEffect, useRef, type MouseEvent } from 'react'
import { isTauriDesktop, openDesktopExternalUrl } from '../../lib/desktop.ts'
import { PROJECT_LINKS } from '../../lib/links.ts'
import { CloseIcon, ExternalLinkIcon, FeedbackIcon, GithubIcon } from '../ui/Icons.tsx'
import { StatusPill } from '../ui/StatusPill.tsx'
import { FeedbackComposer } from './FeedbackComposer.tsx'

export interface FeedbackModalProps {
  open: boolean
  isDesktop: boolean
  appVersionLabel: string
  browserRuntimeLabel?: string | null
  onClose: () => void
}

const FOCUSABLE_SELECTOR =
  'button:not(:disabled):not([tabindex="-1"]), [href]:not([tabindex="-1"]), input:not(:disabled):not([tabindex="-1"]), select:not(:disabled):not([tabindex="-1"]), textarea:not(:disabled):not([tabindex="-1"]), [tabindex]:not([tabindex="-1"])'

export function FeedbackModal({
  open,
  isDesktop,
  appVersionLabel,
  browserRuntimeLabel = null,
  onClose,
}: FeedbackModalProps) {
  const dialogRef = useRef<HTMLDivElement | null>(null)
  const previousFocusRef = useRef<HTMLElement | null>(null)
  const backdropPointerDownRef = useRef(false)
  const onCloseRef = useRef(onClose)
  onCloseRef.current = onClose

  useEffect(() => {
    if (!open) return undefined

    previousFocusRef.current =
      document.activeElement instanceof HTMLElement ? document.activeElement : null

    const rafId = window.requestAnimationFrame(() => {
      dialogRef.current?.focus({ preventScroll: true })
    })

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        event.preventDefault()
        event.stopPropagation()
        onCloseRef.current()
        return
      }
      if (event.key !== 'Tab') return
      const container = dialogRef.current
      if (!container) return
      const focusable = Array.from(
        container.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR),
      ).filter((el) => el.offsetParent !== null || el === document.activeElement)
      if (focusable.length === 0) {
        event.preventDefault()
        container.focus()
        return
      }
      const first = focusable[0]
      const last = focusable[focusable.length - 1]
      const active = document.activeElement
      if (event.shiftKey) {
        if (active === first || active === container || !container.contains(active)) {
          event.preventDefault()
          last.focus()
        }
      } else if (active === last || !container.contains(active)) {
        event.preventDefault()
        first.focus()
      }
    }

    window.addEventListener('keydown', handleKeyDown, true)
    return () => {
      window.cancelAnimationFrame(rafId)
      window.removeEventListener('keydown', handleKeyDown, true)
      const prev = previousFocusRef.current
      if (prev && prev.isConnected) {
        prev.focus({ preventScroll: true })
      }
    }
  }, [open])

  if (!open) return null

  function handleExternalClick(event: MouseEvent<HTMLAnchorElement>, url: string) {
    if (!isTauriDesktop()) return
    event.preventDefault()
    void openDesktopExternalUrl(url).catch(() => {
      window.open(url, '_blank', 'noopener,noreferrer')
    })
  }

  return (
    <div
      className="fc-modal-backdrop"
      onMouseDown={(event) => {
        backdropPointerDownRef.current = event.target === event.currentTarget
      }}
      onClick={(event) => {
        if (backdropPointerDownRef.current && event.target === event.currentTarget) {
          onClose()
        }
        backdropPointerDownRef.current = false
      }}
    >
      <div
        ref={dialogRef}
        className="fc-modal-dialog fc-feedback-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="fc-feedback-modal-title"
        aria-describedby="fc-feedback-modal-desc"
        tabIndex={-1}
      >
        <header className="fc-modal-header">
          <div className="fc-modal-header-main">
            <span className="fc-modal-title-icon" aria-hidden="true">
              <FeedbackIcon width={16} height={16} />
            </span>
            <div style={{ minWidth: 0 }}>
              <div className="fc-modal-title-row">
                <h2 id="fc-feedback-modal-title" className="fc-modal-title">
                  问题反馈与建议
                </h2>
                <StatusPill tone="neutral">{appVersionLabel}</StatusPill>
              </div>
              <p id="fc-feedback-modal-desc" className="fc-modal-subtitle">
                提交后自动创建 GitHub Issue，支持拖拽或 Ctrl+V 直接粘贴截图
              </p>
            </div>
          </div>

          <button
            type="button"
            className="fc-btn fc-btn-ghost fc-btn-icon"
            onClick={onClose}
            title="关闭弹窗 (Esc)"
            aria-label="关闭问题反馈弹窗"
          >
            <CloseIcon width={16} height={16} />
          </button>
        </header>

        <div className="fc-modal-body">
          <FeedbackComposer
            idPrefix="fc-modal-fb"
            isDesktop={isDesktop}
            appVersionLabel={appVersionLabel}
            browserRuntimeLabel={browserRuntimeLabel}
            captureWindowPaste
            showUnconfiguredNotice
          />
        </div>

        <footer className="fc-modal-footer">
          <a
            href={PROJECT_LINKS.issuesUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="fc-sidebar-ext-link"
            onClick={(e) => handleExternalClick(e, PROJECT_LINKS.issuesUrl)}
          >
            <GithubIcon width={13} height={13} />
            <span>浏览历史 Issues</span>
            <ExternalLinkIcon width={12} height={12} />
          </a>
          <span className="fc-hint">按 Esc 关闭</span>
        </footer>
      </div>
    </div>
  )
}
