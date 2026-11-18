import { useEffect, type ReactNode } from 'react'
import { CloseIcon } from './Icons.tsx'

export interface DrawerTab<K extends string = string> {
  key: K
  label: string
}

export interface DrawerShellProps<K extends string = string> {
  open: boolean
  wide?: boolean
  title: string
  subtitle?: string
  headerActions?: ReactNode
  tabs?: DrawerTab<K>[]
  activeTab?: K
  onTabChange?: (tab: K) => void
  footer?: ReactNode
  onClose: () => void
  children: ReactNode
}

export function DrawerShell<K extends string = string>({
  open,
  wide = false,
  title,
  subtitle,
  headerActions,
  tabs,
  activeTab,
  onTabChange,
  footer,
  onClose,
  children,
}: DrawerShellProps<K>) {
  useEffect(() => {
    if (!open) return undefined
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        onClose()
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [open, onClose])

  if (!open) return null

  return (
    <>
      <div className="fc-drawer-backdrop" onClick={onClose} aria-hidden="true" />
      <aside className={`fc-drawer${wide ? ' is-wide' : ''}`} aria-label={title}>
        <header className="fc-drawer-header">
          <div style={{ minWidth: 0 }}>
            <h2 className="fc-drawer-title">{title}</h2>
            {subtitle && <p className="fc-drawer-subtitle">{subtitle}</p>}
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexShrink: 0 }}>
            {headerActions}
            <button
              type="button"
              className="fc-btn fc-btn-ghost fc-btn-icon"
              onClick={onClose}
              title="关闭面板 (Esc)"
              aria-label="关闭面板"
            >
              <CloseIcon />
            </button>
          </div>
        </header>

        {tabs && tabs.length > 0 && onTabChange && (
          <div className="fc-drawer-tabs" role="tablist">
            {tabs.map((tab) => {
              const active = tab.key === activeTab
              return (
                <button
                  key={tab.key}
                  type="button"
                  role="tab"
                  aria-selected={active}
                  className={`fc-drawer-tab${active ? ' is-active' : ''}`}
                  onClick={() => onTabChange(tab.key)}
                >
                  {tab.label}
                </button>
              )
            })}
          </div>
        )}

        <div className="fc-drawer-body">{children}</div>

        {footer && <footer className="fc-drawer-footer">{footer}</footer>}
      </aside>
    </>
  )
}
