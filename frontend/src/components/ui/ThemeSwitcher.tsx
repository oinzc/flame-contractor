import type { ThemePreference } from '../../lib/theme.ts'
import { MonitorIcon, MoonIcon, SunIcon } from './Icons.tsx'

export interface ThemeSwitcherProps {
  value: ThemePreference
  onChange: (next: ThemePreference) => void
  compact?: boolean
}

const OPTIONS: {
  key: ThemePreference
  label: string
  shortLabel: string
  Icon: typeof SunIcon
}[] = [
  { key: 'light', label: '浅色', shortLabel: '浅色', Icon: SunIcon },
  { key: 'dark', label: '深色', shortLabel: '深色', Icon: MoonIcon },
  { key: 'system', label: '跟随系统', shortLabel: '系统', Icon: MonitorIcon },
]

export function ThemeSwitcher({ value, onChange, compact = false }: ThemeSwitcherProps) {
  return (
    <div
      className={`fc-theme-switcher${compact ? ' is-compact' : ''}`}
      role="radiogroup"
      aria-label="界面主题切换"
    >
      {OPTIONS.map(({ key, label, shortLabel, Icon }) => {
        const active = value === key
        return (
          <button
            key={key}
            type="button"
            role="radio"
            aria-checked={active}
            title={`切换主题：${label}`}
            className={`fc-theme-btn${active ? ' is-active' : ''}`}
            onClick={() => onChange(key)}
          >
            <Icon width={13.5} height={13.5} />
            <span>{compact ? shortLabel : label}</span>
          </button>
        )
      })}
    </div>
  )
}
