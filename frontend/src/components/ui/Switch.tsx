import type React from 'react'

export interface SwitchProps {
  checked: boolean
  disabled?: boolean
  label: string
  stopPropagation?: boolean
  onChange: (next: boolean) => void
}

export function Switch({
  checked,
  disabled = false,
  label,
  stopPropagation = false,
  onChange,
}: SwitchProps) {
  function handleClick(event: React.MouseEvent<HTMLButtonElement>) {
    if (stopPropagation) event.stopPropagation()
    if (disabled) return
    onChange(!checked)
  }

  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      className={`fc-switch${checked ? ' is-on' : ''}`}
      onClick={handleClick}
    >
      <span className="fc-switch-thumb" />
    </button>
  )
}
