import { useState } from 'react'
import {
  describeAccountScheduleEffect,
  describeTargetScheduleEffect,
  formatClockHhmm,
  normalizeAccountWindowRange,
  parseClockHhmm,
  type AccountScheduleMode,
} from '../../lib/workspace.ts'
import type { Account } from '../../types.ts'
import { PillTabs } from './PillTabs.tsx'

const HOURS = Array.from({ length: 24 }, (_, idx) => String(idx).padStart(2, '0'))
const MINUTES = Array.from({ length: 60 }, (_, idx) => String(idx).padStart(2, '0'))

interface ClockTimeSelectProps {
  id: string
  label: string
  value: string
  onChange: (nextHhmm: string) => void
  maxMinutes?: number
  presets?: Array<{ label: string; value: string }>
}

function ClockTimeSelect({
  id,
  label,
  value,
  onChange,
  maxMinutes = 23 * 60 + 59,
  presets,
}: ClockTimeSelectProps) {
  const { hour, minute } = parseClockHhmm(value, '08:30')

  function emit(nextHour: number, nextMinute: number) {
    const total = Math.max(0, Math.min(maxMinutes, nextHour * 60 + nextMinute))
    onChange(formatClockHhmm(Math.floor(total / 60), total % 60))
  }

  return (
    <div className="fc-clock-field">
      <div className="fc-label" id={`${id}-label`}>
        {label}
      </div>
      <div className="fc-time-pair" role="group" aria-labelledby={`${id}-label`}>
        <select
          id={`${id}-hour`}
          className="fc-time-select fc-mono"
          aria-label={`${label}（小时）`}
          value={String(hour).padStart(2, '0')}
          onChange={(e) => emit(Number(e.target.value), minute)}
        >
          {HOURS.map((h) => (
            <option key={h} value={h}>
              {h} 时
            </option>
          ))}
        </select>
        <span className="fc-time-colon" aria-hidden="true">
          :
        </span>
        <select
          id={`${id}-minute`}
          className="fc-time-select fc-mono"
          aria-label={`${label}（分钟）`}
          value={String(minute).padStart(2, '0')}
          onChange={(e) => emit(hour, Number(e.target.value))}
        >
          {MINUTES.map((m) => (
            <option key={m} value={m}>
              {m} 分
            </option>
          ))}
        </select>
      </div>
      {presets && presets.length > 0 && (
        <div className="fc-time-presets">
          {presets.map((preset) => (
            <button
              key={preset.value}
              type="button"
              className={`fc-time-preset-btn fc-mono${value === preset.value ? ' is-active' : ''}`}
              onClick={() => onChange(preset.value)}
            >
              {preset.label}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}

export interface AccountScheduleTimeFieldProps {
  idPrefix: string
  mode: AccountScheduleMode
  windowStart: string
  windowEnd: string
  fixedTime: string
  onModeChange: (mode: AccountScheduleMode) => void
  onWindowChange: (start: string, end: string) => void
  onFixedTimeChange: (hhmm: string) => void
}

const WINDOW_PRESETS = [
  { label: '全天 08:30–22:30', start: '08:30', end: '22:30' },
  { label: '晚间 19:00–23:00', start: '19:00', end: '23:00' },
  { label: '午间 11:30–14:00', start: '11:30', end: '14:00' },
]

const FIXED_TIME_PRESETS = [
  { label: '09:00', value: '09:00' },
  { label: '12:30', value: '12:30' },
  { label: '20:30', value: '20:30' },
  { label: '22:00', value: '22:00' },
]

export function AccountScheduleTimeField({
  idPrefix,
  mode,
  windowStart,
  windowEnd,
  fixedTime,
  onModeChange,
  onWindowChange,
  onFixedTimeChange,
}: AccountScheduleTimeFieldProps) {
  const normalizedWindow = normalizeAccountWindowRange(windowStart, windowEnd, 'start')

  return (
    <div className="fc-schedule-control">
      <div className="fc-schedule-control-head">
        <span className="fc-label">自动续火时间</span>
        <PillTabs
          ariaLabel="自动续火时间方式"
          value={mode}
          onChange={onModeChange}
          items={[
            { key: 'window', label: '时间段内自动发（推荐）' },
            { key: 'fixed', label: '每天固定时刻发' },
          ]}
        />
      </div>

      {mode === 'window' ? (
        <>
          <div className="fc-time-pair-grid">
            <ClockTimeSelect
              id={`${idPrefix}-start`}
              label="开始时间"
              value={normalizedWindow.start}
              maxMinutes={23 * 60 + 54}
              onChange={(nextStart) => {
                const next = normalizeAccountWindowRange(nextStart, normalizedWindow.end, 'start')
                onWindowChange(next.start, next.end)
              }}
            />
            <ClockTimeSelect
              id={`${idPrefix}-end`}
              label="结束时间"
              value={normalizedWindow.end}
              maxMinutes={23 * 60 + 55}
              onChange={(nextEnd) => {
                const next = normalizeAccountWindowRange(normalizedWindow.start, nextEnd, 'end')
                onWindowChange(next.start, next.end)
              }}
            />
          </div>
          <div className="fc-time-presets" aria-label="常用时间段快捷选择">
            {WINDOW_PRESETS.map((preset) => {
              const active =
                normalizedWindow.start === preset.start &&
                normalizedWindow.end === preset.end
              return (
                <button
                  key={preset.label}
                  type="button"
                  className={`fc-time-preset-btn${active ? ' is-active' : ''}`}
                  onClick={() => onWindowChange(preset.start, preset.end)}
                >
                  {preset.label}
                </button>
              )
            })}
          </div>
        </>
      ) : (
        <ClockTimeSelect
          id={`${idPrefix}-fixed`}
          label="每天定时时刻"
          value={fixedTime || '20:30'}
          onChange={onFixedTimeChange}
          presets={FIXED_TIME_PRESETS}
        />
      )}

      <div className="fc-schedule-explain">
        {describeAccountScheduleEffect(
          mode,
          normalizedWindow.start,
          normalizedWindow.end,
          fixedTime || '20:30',
        )}
      </div>
    </div>
  )
}

export interface TargetScheduleTimeFieldProps {
  idPrefix: string
  account: Pick<Account, 'sendWindowStart' | 'sendWindowEnd' | 'sendTimeHhmm'>
  value: string
  onChange: (nextValue: string) => void
}

export function TargetScheduleTimeField({
  idPrefix,
  account,
  value,
  onChange,
}: TargetScheduleTimeFieldProps) {
  const [customDraft, setCustomDraft] = useState(value.trim() || '20:30')
  const mode: 'follow' | 'custom' = value.trim() ? 'custom' : 'follow'

  return (
    <div className="fc-schedule-control">
      <div className="fc-schedule-control-head">
        <span className="fc-label">发送时间规则</span>
        <PillTabs
          ariaLabel="好友发送时间规则"
          value={mode}
          onChange={(nextMode) => {
            if (nextMode === 'follow') {
              onChange('')
            } else {
              const nextTime = customDraft || '20:30'
              setCustomDraft(nextTime)
              onChange(nextTime)
            }
          }}
          items={[
            { key: 'follow', label: '跟随账号时间' },
            { key: 'custom', label: '单独定时' },
          ]}
        />
      </div>

      {mode === 'custom' && (
        <ClockTimeSelect
          id={`${idPrefix}-target-time`}
          label="该好友专属时刻"
          value={value.trim() || customDraft}
          onChange={(nextHhmm) => {
            setCustomDraft(nextHhmm)
            onChange(nextHhmm)
          }}
          presets={FIXED_TIME_PRESETS}
        />
      )}

      <div className="fc-schedule-explain">
        {describeTargetScheduleEffect(value, account)}
      </div>
    </div>
  )
}
