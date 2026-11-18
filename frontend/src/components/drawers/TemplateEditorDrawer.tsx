import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { api, ApiError } from '../../api.ts'
import { WEEKDAY_POOL_LABELS } from '../../lib/date.ts'
import { previewTemplateForTarget } from '../../lib/preview.ts'
import type { ContractTarget, MessageTemplate } from '../../types.ts'
import { DrawerShell } from '../ui/DrawerShell.tsx'
import { PlusIcon, TrashIcon } from '../ui/Icons.tsx'
import { SlashVariableInput } from '../ui/SlashVariableInput.tsx'

export interface TemplateEditorDrawerProps {
  open: boolean
  templateId: string | null
  templates: MessageTemplate[]
  sampleTargets: ContractTarget[]
  onClose: () => void
  onSaved: (saved: MessageTemplate) => Promise<void> | void
}

interface PoolDraftRow {
  content: string
  weekdayMask: number
  enabled: boolean
}

export function TemplateEditorDrawer({
  open,
  templateId,
  templates,
  sampleTargets,
  onClose,
  onSaved,
}: TemplateEditorDrawerProps) {
  const editingTemplate = useMemo(
    () => (templateId ? templates.find((t) => t.id === templateId) ?? null : null),
    [templateId, templates],
  )

  const [name, setName] = useState('')
  const [type, setType] = useState<MessageTemplate['type']>('text')
  const [content, setContent] = useState('')
  const [imagePath, setImagePath] = useState('')
  const [stickerName, setStickerName] = useState('')
  const [stickerCategory, setStickerCategory] = useState('')
  const [poolItems, setPoolItems] = useState<PoolDraftRow[]>([
    { content: '', weekdayMask: 0, enabled: true },
  ])
  const [initialSnapshot, setInitialSnapshot] = useState('')
  const [sampleTargetId, setSampleTargetId] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!open) return
    setError(null)
    if (editingTemplate) {
      const nextPool =
        editingTemplate.type === 'pool' && editingTemplate.poolItems.length > 0
          ? editingTemplate.poolItems.map((p) => ({
              content: p.content,
              weekdayMask: p.weekdayMask,
              enabled: p.enabled,
            }))
          : [{ content: '', weekdayMask: 0, enabled: true }]
      const nextState = {
        name: editingTemplate.name,
        type: editingTemplate.type,
        content: editingTemplate.type === 'text' ? editingTemplate.content : '',
        imagePath: editingTemplate.type === 'image' ? (editingTemplate.imagePath ?? '') : '',
        stickerName: editingTemplate.type === 'sticker' ? (editingTemplate.stickerName ?? '') : '',
        stickerCategory:
          editingTemplate.type === 'sticker' ? (editingTemplate.stickerCategory ?? '') : '',
        poolItems: nextPool,
      }
      setName(nextState.name)
      setType(nextState.type)
      setContent(nextState.content)
      setImagePath(nextState.imagePath)
      setStickerName(nextState.stickerName)
      setStickerCategory(nextState.stickerCategory)
      setPoolItems(nextPool)
      setInitialSnapshot(JSON.stringify(nextState))
    } else {
      const emptyPool = [{ content: '', weekdayMask: 0, enabled: true }]
      const emptyState = {
        name: '',
        type: 'text' as const,
        content: '',
        imagePath: '',
        stickerName: '',
        stickerCategory: '',
        poolItems: emptyPool,
      }
      setName('')
      setType('text')
      setContent('')
      setImagePath('')
      setStickerName('')
      setStickerCategory('')
      setPoolItems(emptyPool)
      setInitialSnapshot(JSON.stringify(emptyState))
    }
  }, [open, editingTemplate])

  useEffect(() => {
    if (sampleTargets.length > 0 && !sampleTargets.some((t) => t.id === sampleTargetId)) {
      setSampleTargetId(sampleTargets[0].id)
    }
  }, [sampleTargetId, sampleTargets])

  const currentSnapshot = JSON.stringify({
    name,
    type,
    content,
    imagePath,
    stickerName,
    stickerCategory,
    poolItems,
  })
  const isDirty = open && currentSnapshot !== initialSnapshot

  const previewTarget = useMemo(
    () => sampleTargets.find((t) => t.id === sampleTargetId) ?? sampleTargets[0] ?? null,
    [sampleTargetId, sampleTargets],
  )

  const syntheticTemplate: MessageTemplate = useMemo(
    () => ({
      id: editingTemplate?.id ?? 'draft',
      name: name || '草稿模板',
      type,
      content,
      imagePath: imagePath || null,
      stickerName: stickerName || null,
      stickerLabel: null,
      stickerCategory: stickerCategory || null,
      stickerFallbackIndex: null,
      version: editingTemplate?.version ?? 1,
      enabled: editingTemplate?.enabled ?? true,
      poolItems: poolItems.map((p, idx) => ({
        id: `draft-${idx}`,
        content: p.content,
        weekdayMask: p.weekdayMask,
        enabled: p.enabled,
        lastUsedAt: null,
      })),
      createdAt: '',
      updatedAt: '',
    }),
    [content, editingTemplate, imagePath, name, poolItems, stickerCategory, stickerName, type],
  )

  const livePreview = useMemo(
    () => previewTemplateForTarget(syntheticTemplate, previewTarget),
    [previewTarget, syntheticTemplate],
  )

  if (!open) return null

  function handleRequestClose() {
    if (isDirty && !window.confirm('当前模板有未保存的改动，确定放弃并关闭吗？')) {
      return
    }
    onClose()
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    if (submitting || !name.trim()) return
    setSubmitting(true)
    setError(null)
    try {
      const cleanedPool =
        type === 'pool'
          ? poolItems
              .filter((p) => p.content.trim().length > 0)
              .map((p) => ({
                content: p.content.trim(),
                weekdayMask: p.weekdayMask,
                enabled: p.enabled,
              }))
          : undefined

      const saved = editingTemplate
        ? await api.patchTemplate(editingTemplate.id, {
            name: name.trim(),
            content: type === 'text' ? content.trim() : '',
            imagePath: type === 'image' ? imagePath.trim() : '',
            stickerName: type === 'sticker' ? stickerName.trim() : '',
            stickerCategory:
              type === 'sticker' && stickerCategory.trim() ? stickerCategory.trim() : '',
            poolItems: cleanedPool,
          })
        : await api.createTemplate({
            name: name.trim(),
            type,
            content: type === 'text' ? content.trim() : '',
            imagePath: type === 'image' ? imagePath.trim() : undefined,
            stickerName: type === 'sticker' ? stickerName.trim() : undefined,
            stickerCategory:
              type === 'sticker' && stickerCategory.trim() ? stickerCategory.trim() : undefined,
            poolItems: cleanedPool,
          })
      await onSaved(saved)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '保存模板失败')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <DrawerShell
      open={open}
      wide
      title={editingTemplate ? `编辑模板 · ${editingTemplate.name}` : '新建消息模板'}
      subtitle="键入 / 或点击变量胶囊插入动态字段，下方即时渲染今日真实效果"
      onClose={handleRequestClose}
      footer={
        <>
          <button type="button" className="fc-btn fc-btn-secondary" onClick={handleRequestClose}>
            取消
          </button>
          <button
            type="submit"
            form="fc-template-editor-form"
            className="fc-btn fc-btn-primary"
            disabled={submitting || !name.trim()}
          >
            {submitting ? '保存中…' : editingTemplate ? '保存修改' : '创建模板'}
          </button>
        </>
      }
    >
      {error && <div className="fc-alert is-error">{error}</div>}

      <form
        id="fc-template-editor-form"
        onSubmit={(e) => void handleSubmit(e)}
        style={{ display: 'flex', flexDirection: 'column', gap: 14 }}
      >
        <div style={{ display: 'grid', gridTemplateColumns: '1.4fr 1fr', gap: 10 }}>
          <div className="fc-field">
            <label className="fc-label" htmlFor="tpl-name">
              模板名称
            </label>
            <input
              id="tpl-name"
              className="fc-input"
              maxLength={60}
              placeholder="例如：早安日常问候"
              value={name}
              onChange={(e) => setName(e.target.value)}
              required
            />
          </div>

          <div className="fc-field">
            <label className="fc-label" htmlFor="tpl-type">
              模板类型
            </label>
            <select
              id="tpl-type"
              className="fc-select"
              value={type}
              disabled={Boolean(editingTemplate)}
              title={editingTemplate ? '模板类型创建后不可更改' : undefined}
              onChange={(e) => setType(e.target.value as MessageTemplate['type'])}
            >
              <option value="text">纯文字</option>
              <option value="pool">文案池轮换</option>
              <option value="sticker">抖音原生表情</option>
              <option value="image">本机图片</option>
            </select>
          </div>
        </div>

        {type === 'text' && (
          <div className="fc-field">
            <label className="fc-label" htmlFor="tpl-content">
              文字内容
            </label>
            <SlashVariableInput
              id="tpl-content"
              value={content}
              onChange={setContent}
            />
          </div>
        )}

        {type === 'pool' && (
          <div className="fc-field">
            <div className="fc-label">
              <span>候选文案池（按最少最近使用轮换）</span>
              <span className="fc-hint">星期全不选 = 每天可用</span>
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
              {poolItems.map((row, idx) => (
                <div key={idx} className="fc-card" style={{ padding: 12, gap: 8 }}>
                  <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                    <div style={{ flex: 1 }}>
                      <SlashVariableInput
                        multiline={false}
                        showChips={idx === 0}
                        value={row.content}
                        ariaLabel={`候选文案 ${idx + 1}`}
                        placeholder={`候选 ${idx + 1}（键入 / 插入变量）`}
                        onChange={(val) =>
                          setPoolItems((prev) =>
                            prev.map((item, i) => (i === idx ? { ...item, content: val } : item)),
                          )
                        }
                      />
                    </div>
                    {poolItems.length > 1 && (
                      <button
                        type="button"
                        className="fc-btn fc-btn-ghost fc-btn-icon"
                        aria-label={`删除候选 ${idx + 1}`}
                        onClick={() =>
                          setPoolItems((prev) => prev.filter((_, i) => i !== idx))
                        }
                      >
                        <TrashIcon />
                      </button>
                    )}
                  </div>

                  <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
                    <div className="fc-weekday-row" aria-label={`候选 ${idx + 1} 生效星期`}>
                      {WEEKDAY_POOL_LABELS.map((dayLabel, bit) => {
                        const on = (row.weekdayMask & (1 << bit)) !== 0
                        return (
                          <button
                            key={dayLabel}
                            type="button"
                            aria-pressed={on}
                            className={`fc-day-btn${on ? ' is-on' : ''}`}
                            onClick={() =>
                              setPoolItems((prev) =>
                                prev.map((item, i) =>
                                  i === idx
                                    ? { ...item, weekdayMask: item.weekdayMask ^ (1 << bit) }
                                    : item,
                                ),
                              )
                            }
                          >
                            {dayLabel}
                          </button>
                        )
                      })}
                    </div>
                    <span className="fc-hint">
                      {row.weekdayMask === 0 ? '每天可用' : '限定星期'}
                    </span>
                  </div>
                </div>
              ))}

              <button
                type="button"
                className="fc-btn fc-btn-secondary fc-btn-sm"
                style={{ alignSelf: 'flex-start' }}
                onClick={() =>
                  setPoolItems((prev) => [
                    ...prev,
                    { content: '', weekdayMask: 0, enabled: true },
                  ])
                }
              >
                <PlusIcon width={14} height={14} /> 添加候选文案
              </button>
            </div>
          </div>
        )}

        {type === 'image' && (
          <div className="fc-field">
            <label className="fc-label" htmlFor="tpl-img">
              本机图片绝对路径
            </label>
            <input
              id="tpl-img"
              className="fc-input fc-mono"
              placeholder="例如：D:/images/morning.png"
              value={imagePath}
              onChange={(e) => setImagePath(e.target.value)}
              required
            />
            <span className="fc-hint">须为运行本服务机器上可直接读取的本地图片文件。</span>
          </div>
        )}

        {type === 'sticker' && (
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
            <div className="fc-field">
              <label className="fc-label" htmlFor="tpl-sticker">
                抖音原生表情名称
              </label>
              <input
                id="tpl-sticker"
                className="fc-input"
                placeholder="例如：比心"
                value={stickerName}
                onChange={(e) => setStickerName(e.target.value)}
                required
              />
            </div>
            <div className="fc-field">
              <label className="fc-label" htmlFor="tpl-sticker-cat">
                <span>表情面板分类</span>
                <span className="fc-hint">可选</span>
              </label>
              <input
                id="tpl-sticker-cat"
                className="fc-input"
                placeholder="例如：常用"
                value={stickerCategory}
                onChange={(e) => setStickerCategory(e.target.value)}
              />
            </div>
          </div>
        )}
      </form>

      {/* Live Preview Stage */}
      <div className="fc-preview-stage">
        <div className="fc-preview-head">
          <span>实时效果预览</span>
          {sampleTargets.length > 0 && (
            <select
              className="fc-select"
              style={{ width: 'auto', height: 26, fontSize: 12, padding: '0 8px' }}
              aria-label="选择用于预览的好友"
              value={previewTarget?.id ?? ''}
              onChange={(e) => setSampleTargetId(e.target.value)}
            >
              {sampleTargets.map((t) => (
                <option key={t.id} value={t.id}>
                  代入好友：{t.nicknameSnapshot}（{t.streakDays}天）
                </option>
              ))}
            </select>
          )}
        </div>

        <div className="fc-chat-bubble">{livePreview.primaryText}</div>
        {livePreview.mutedNote && <div className="fc-hint">{livePreview.mutedNote}</div>}
      </div>
    </DrawerShell>
  )
}
