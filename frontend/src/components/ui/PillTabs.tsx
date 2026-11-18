export interface PillTabItem<K extends string> {
  key: K
  label: string
  count?: number
}

export interface PillTabsProps<K extends string> {
  items: PillTabItem<K>[]
  value: K
  onChange: (key: K) => void
  ariaLabel: string
}

export function PillTabs<K extends string>({
  items,
  value,
  onChange,
  ariaLabel,
}: PillTabsProps<K>) {
  return (
    <div className="fc-pill-group" role="tablist" aria-label={ariaLabel}>
      {items.map((item) => {
        const active = item.key === value
        return (
          <button
            key={item.key}
            type="button"
            role="tab"
            aria-selected={active}
            className={`fc-pill-tab${active ? ' is-active' : ''}`}
            onClick={() => onChange(item.key)}
          >
            <span>{item.label}</span>
            {item.count !== undefined && (
              <span style={{ opacity: active ? 0.85 : 0.65 }}>{item.count}</span>
            )}
          </button>
        )
      })}
    </div>
  )
}
