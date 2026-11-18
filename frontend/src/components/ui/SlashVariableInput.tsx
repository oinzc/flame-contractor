import { useRef, useState, type KeyboardEvent } from 'react'
import { SAFE_TEMPLATE_VARIABLES } from '../../lib/preview.ts'

export interface SlashVariableInputProps {
  id?: string
  value: string
  onChange: (next: string) => void
  multiline?: boolean
  placeholder?: string
  maxLength?: number
  ariaLabel?: string
  showChips?: boolean
}

export function SlashVariableInput({
  id,
  value,
  onChange,
  multiline = true,
  placeholder,
  maxLength = 1000,
  ariaLabel,
  showChips = true,
}: SlashVariableInputProps) {
  const [menuOpen, setMenuOpen] = useState(false)
  const [slashIndex, setSlashIndex] = useState<number | null>(null)
  const [activeIdx, setActiveIdx] = useState(0)
  const inputRef = useRef<HTMLTextAreaElement | HTMLInputElement | null>(null)

  function insertToken(token: string, replaceSlashAt: number | null = null) {
    const el = inputRef.current
    if (!el) {
      onChange(`${value}${token}`)
      setMenuOpen(false)
      return
    }
    const start = replaceSlashAt !== null ? replaceSlashAt : (el.selectionStart ?? value.length)
    const end = el.selectionEnd ?? value.length
    const next = `${value.slice(0, start)}${token}${value.slice(end)}`
    onChange(next)
    setMenuOpen(false)
    setSlashIndex(null)
    requestAnimationFrame(() => {
      el.focus()
      const cursor = start + token.length
      el.setSelectionRange(cursor, cursor)
    })
  }

  function handleTextChange(nextVal: string, selectionStart: number | null) {
    onChange(nextVal)
    const caret = selectionStart ?? nextVal.length
    const prevChar = caret > 0 ? nextVal[caret - 1] : ''
    const beforePrev = caret > 1 ? nextVal[caret - 2] : ' '
    if (prevChar === '/' && (caret === 1 || /\s/.test(beforePrev))) {
      setSlashIndex(caret - 1)
      setActiveIdx(0)
      setMenuOpen(true)
    } else if (menuOpen) {
      setMenuOpen(false)
      setSlashIndex(null)
    }
  }

  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement | HTMLInputElement>) {
    if (!menuOpen) return
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      setActiveIdx((i) => (i + 1) % SAFE_TEMPLATE_VARIABLES.length)
    } else if (event.key === 'ArrowUp') {
      event.preventDefault()
      setActiveIdx((i) => (i - 1 + SAFE_TEMPLATE_VARIABLES.length) % SAFE_TEMPLATE_VARIABLES.length)
    } else if (event.key === 'Enter') {
      event.preventDefault()
      insertToken(SAFE_TEMPLATE_VARIABLES[activeIdx].token, slashIndex)
    } else if (event.key === 'Escape') {
      event.preventDefault()
      event.stopPropagation()
      setMenuOpen(false)
      setSlashIndex(null)
    }
  }

  return (
    <div className="fc-slash-wrap">
      {showChips && (
        <div className="fc-var-chips" aria-label="快捷插入变量">
          {SAFE_TEMPLATE_VARIABLES.map((v) => (
            <button
              key={v.token}
              type="button"
              className="fc-var-chip"
              onClick={() => insertToken(v.token, null)}
            >
              + {v.label} <code style={{ opacity: 0.75 }}>{v.token}</code>
            </button>
          ))}
        </div>
      )}

      {multiline ? (
        <textarea
          ref={(node) => {
            inputRef.current = node
          }}
          id={id}
          className="fc-textarea"
          value={value}
          maxLength={maxLength}
          placeholder={placeholder ?? '输入文案，键入 / 可快速插入好友昵称、累计天数等安全变量'}
          aria-label={ariaLabel}
          onChange={(e) => handleTextChange(e.target.value, e.target.selectionStart)}
          onKeyDown={handleKeyDown}
          onBlur={() => window.setTimeout(() => setMenuOpen(false), 150)}
        />
      ) : (
        <input
          ref={(node) => {
            inputRef.current = node
          }}
          id={id}
          type="text"
          className="fc-input"
          value={value}
          maxLength={maxLength}
          placeholder={placeholder ?? '输入候选文案，键入 / 插入变量'}
          aria-label={ariaLabel}
          onChange={(e) => handleTextChange(e.target.value, e.target.selectionStart)}
          onKeyDown={handleKeyDown}
          onBlur={() => window.setTimeout(() => setMenuOpen(false), 150)}
        />
      )}

      {menuOpen && (
        <div className="fc-slash-menu" role="listbox" aria-label="插入安全变量">
          {SAFE_TEMPLATE_VARIABLES.map((v, idx) => (
            <button
              key={v.token}
              type="button"
              role="option"
              aria-selected={idx === activeIdx}
              className={`fc-slash-option${idx === activeIdx ? ' is-active' : ''}`}
              onMouseDown={(e) => {
                e.preventDefault()
                insertToken(v.token, slashIndex)
              }}
            >
              <span>{v.label}</span>
              <span className="fc-mono" style={{ color: 'var(--muted)' }}>
                {v.token}
              </span>
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
