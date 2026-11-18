import type { ReactNode } from 'react'
import type { StatusTone } from '../../lib/format.ts'

export interface StatusPillProps {
  tone?: StatusTone
  dot?: boolean
  mono?: boolean
  title?: string
  children: ReactNode
}

export function StatusPill({
  tone = 'neutral',
  dot = false,
  mono = false,
  title,
  children,
}: StatusPillProps) {
  return (
    <span
      className={`fc-status-pill tone-${tone}${mono ? ' fc-mono' : ''}`}
      title={title}
    >
      {dot && <span className={`fc-dot tone-${tone}`} aria-hidden="true" />}
      <span>{children}</span>
    </span>
  )
}
