import { useState } from 'react'
import { api, ApiError } from '../../api.ts'
import type { DrawerState, MessageTemplate } from '../../types.ts'
import { PlusIcon, TrashIcon } from '../ui/Icons.tsx'
import { StatusPill } from '../ui/StatusPill.tsx'
import { Switch } from '../ui/Switch.tsx'

export interface TemplatesViewProps {
  templates: MessageTemplate[]
  drawer: DrawerState
  onOpenDrawer: (drawer: DrawerState) => void
  onChanged: () => Promise<void> | void
}

const TYPE_LABELS: Record<MessageTemplate['type'], string> = {
  text: '纯文字',
  pool: '文案池轮换',
  sticker: '抖音原生表情',
  image: '本机图片',
}

function summarizeTemplate(tpl: MessageTemplate): string {
  if (tpl.type === 'text') return tpl.content || '（空文本）'
  if (tpl.type === 'image') return `本机图片路径：${tpl.imagePath ?? '—'}`
  if (tpl.type === 'sticker') {
    return `原生表情：「${tpl.stickerName ?? '—'}」${tpl.stickerCategory ? `（分类：${tpl.stickerCategory}）` : ''}`
  }
  return `候选文案池：共 ${tpl.poolItems?.length ?? 0} 条轮换规则`
}

export function TemplatesView({
  templates,
  drawer,
  onOpenDrawer,
  onChanged,
}: TemplatesViewProps) {
  const [busyId, setBusyId] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  async function handleToggle(tpl: MessageTemplate, next: boolean) {
    setBusyId(tpl.id)
    setError(null)
    try {
      await api.patchTemplate(tpl.id, { enabled: next })
      await onChanged()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '切换模板状态失败')
    } finally {
      setBusyId(null)
    }
  }

  async function handleDelete(tpl: MessageTemplate) {
    if (
      !window.confirm(
        `确定删除模板「${tpl.name}」？已被好友绑定或历史任务引用的模板将被后端拒绝删除。`,
      )
    ) {
      return
    }
    setBusyId(tpl.id)
    setError(null)
    try {
      await api.deleteTemplate(tpl.id)
      if (drawer.kind === 'template-editor' && drawer.templateId === tpl.id) {
        onOpenDrawer({ kind: 'closed' })
      }
      await onChanged()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '删除模板失败')
    } finally {
      setBusyId(null)
    }
  }

  return (
    <>
      <header className="fc-page-header">
        <div>
          <div className="fc-page-title-row">
            <h1 className="fc-page-title">消息模板库</h1>
            <StatusPill tone="neutral">跨抖音号共享</StatusPill>
          </div>
          <p className="fc-page-meta">
            点击任意规则卡片即可在右侧打开分屏实时预览，支持四个安全动态变量与星期掩码轮换。
          </p>
        </div>

        <div className="fc-page-actions">
          <button
            type="button"
            className="fc-btn fc-btn-primary"
            onClick={() => onOpenDrawer({ kind: 'template-editor', templateId: null })}
          >
            <PlusIcon /> 新建模板
          </button>
        </div>
      </header>

      <div className="fc-scroll" style={{ paddingTop: 18 }}>
        {error && (
          <div className="fc-alert is-error" style={{ marginBottom: 12 }}>
            {error}
          </div>
        )}

        {templates.length === 0 ? (
          <div className="fc-empty">
            <h2 style={{ fontSize: 16, fontWeight: 600 }}>还没有消息模板</h2>
            <p className="fc-hint">新建文字、文案池、原生表情或图片模板后即可绑定给任意好友。</p>
            <button
              type="button"
              className="fc-btn fc-btn-primary"
              style={{ marginTop: 6 }}
              onClick={() => onOpenDrawer({ kind: 'template-editor', templateId: null })}
            >
              <PlusIcon /> 创建第一个模板
            </button>
          </div>
        ) : (
          <div className="fc-rule-list">
            {templates.map((tpl) => {
              const selected =
                drawer.kind === 'template-editor' && drawer.templateId === tpl.id
              return (
                <div
                  key={tpl.id}
                  className={`fc-rule-card${selected ? ' is-selected' : ''}`}
                  tabIndex={0}
                  onClick={() =>
                    onOpenDrawer({ kind: 'template-editor', templateId: tpl.id })
                  }
                  onKeyDown={(e) => {
                    if (e.target !== e.currentTarget) return
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault()
                      onOpenDrawer({ kind: 'template-editor', templateId: tpl.id })
                    }
                  }}
                >
                  <div style={{ minWidth: 0 }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                      <span style={{ fontWeight: 600, fontSize: 14.5 }}>{tpl.name}</span>
                      <StatusPill tone="neutral">{TYPE_LABELS[tpl.type]}</StatusPill>
                      <span className="fc-mono" style={{ fontSize: 11.5, color: 'var(--muted)' }}>
                        v{tpl.version}
                      </span>
                      {!tpl.enabled && <StatusPill tone="warn">已停用</StatusPill>}
                    </div>
                    <div
                      style={{
                        marginTop: 4,
                        fontSize: 13,
                        color: 'var(--muted)',
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                        whiteSpace: 'nowrap',
                      }}
                    >
                      {summarizeTemplate(tpl)}
                    </div>
                  </div>

                  <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexShrink: 0 }}>
                    <Switch
                      checked={tpl.enabled}
                      disabled={busyId === tpl.id}
                      stopPropagation
                      label={`启用模板 ${tpl.name}`}
                      onChange={(next) => void handleToggle(tpl, next)}
                    />
                    <button
                      type="button"
                      className="fc-btn fc-btn-ghost fc-btn-icon"
                      aria-label={`删除模板 ${tpl.name}`}
                      title="删除模板"
                      disabled={busyId === tpl.id}
                      onClick={(e) => {
                        e.stopPropagation()
                        void handleDelete(tpl)
                      }}
                    >
                      <TrashIcon />
                    </button>
                  </div>
                </div>
              )
            })}
          </div>
        )}
      </div>
    </>
  )
}
