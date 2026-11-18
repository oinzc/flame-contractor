import { useEffect, useRef, useState } from 'react'
import { ACCOUNT_STATUS_LABELS, ACCOUNT_STATUS_LIGHT_TONES } from '../../lib/format.ts'
import type { Account } from '../../types.ts'
import { ChevronDownIcon, PlusIcon } from '../ui/Icons.tsx'

export interface AccountSwitcherProps {
  accounts: Account[]
  selectedAccountId: string
  onSelectAccount: (id: string) => void
  onCreateAccount: () => void
}

export function AccountSwitcher({
  accounts,
  selectedAccountId,
  onSelectAccount,
  onCreateAccount,
}: AccountSwitcherProps) {
  const [open, setOpen] = useState(false)
  const rootRef = useRef<HTMLDivElement | null>(null)

  const current = accounts.find((a) => a.id === selectedAccountId) ?? accounts[0] ?? null

  useEffect(() => {
    if (!open) return undefined
    function handleOutside(e: MouseEvent) {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) {
        setOpen(false)
      }
    }
    function handleKey(e: KeyboardEvent) {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', handleOutside)
    window.addEventListener('keydown', handleKey)
    return () => {
      document.removeEventListener('mousedown', handleOutside)
      window.removeEventListener('keydown', handleKey)
    }
  }, [open])

  if (!current) {
    return (
      <div className="fc-acct-switcher">
        <button
          type="button"
          className="fc-btn fc-btn-primary"
          style={{ width: '100%' }}
          onClick={onCreateAccount}
        >
          <PlusIcon /> 添加首个抖音账号
        </button>
      </div>
    )
  }

  const tone = ACCOUNT_STATUS_LIGHT_TONES[current.status] ?? 'neutral'
  const shortChar = current.displayName.trim().slice(0, 1) || '号'

  return (
    <div className="fc-acct-switcher" ref={rootRef}>
      <button
        type="button"
        className="fc-acct-trigger"
        aria-expanded={open}
        aria-haspopup="listbox"
        onClick={() => setOpen((v) => !v)}
      >
        <span className="fc-acct-avatar">{shortChar}</span>
        <div className="fc-acct-meta">
          <div className="fc-acct-name">
            <span>{current.displayName}</span>
            <span className={`fc-dot tone-${tone}`} aria-hidden="true" />
          </div>
          <div className="fc-acct-sub">
            {current.douyinNickname ? `${current.douyinNickname} · ` : ''}
            {ACCOUNT_STATUS_LABELS[current.status]}
          </div>
        </div>
        <ChevronDownIcon style={{ color: 'var(--muted)', flexShrink: 0 }} />
      </button>

      {open && (
        <div className="fc-acct-popover" role="listbox" aria-label="切换抖音账号">
          {accounts.map((acct) => {
            const acctTone = ACCOUNT_STATUS_LIGHT_TONES[acct.status] ?? 'neutral'
            const selected = acct.id === current.id
            return (
              <button
                key={acct.id}
                type="button"
                role="option"
                aria-selected={selected}
                className={`fc-acct-option${selected ? ' is-selected' : ''}`}
                onClick={() => {
                  onSelectAccount(acct.id)
                  setOpen(false)
                }}
              >
                <div style={{ minWidth: 0 }}>
                  <div style={{ whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                    {acct.displayName}
                  </div>
                  <div style={{ fontSize: 11.5, color: 'var(--muted)' }}>
                    {acct.douyinNickname ? `${acct.douyinNickname} · ` : ''}
                    {ACCOUNT_STATUS_LABELS[acct.status]}
                  </div>
                </div>
                <span className={`fc-dot tone-${acctTone}`} aria-hidden="true" />
              </button>
            )
          })}
          <div className="fc-acct-divider" />
          <button
            type="button"
            className="fc-acct-option"
            onClick={() => {
              setOpen(false)
              onCreateAccount()
            }}
          >
            <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontWeight: 500 }}>
              <PlusIcon width={14} height={14} /> 新增抖音账号…
            </span>
          </button>
        </div>
      )}
    </div>
  )
}
