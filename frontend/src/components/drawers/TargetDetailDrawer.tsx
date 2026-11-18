import { useEffect, useMemo, useState } from 'react'
import { api, ApiError } from '../../api.ts'
import type { BackgroundTasksController } from '../../hooks/useBackgroundTasks.ts'
import {
  FAILURE_CLASS_LABELS,
  JOB_STATUS_LABELS,
  JOB_STATUS_LIGHT_TONES,
  formatDateTime,
} from '../../lib/format.ts'
import { previewTemplateForTarget, renderTemplateVariables } from '../../lib/preview.ts'
import type { TargetDaySummary } from '../../lib/workspace.ts'
import type { Account, ContractTarget, MessageTemplate, SendJob } from '../../types.ts'
import { DrawerShell } from '../ui/DrawerShell.tsx'
import { TrashIcon } from '../ui/Icons.tsx'
import { PillTabs } from '../ui/PillTabs.tsx'
import { SlashVariableInput } from '../ui/SlashVariableInput.tsx'
import { SparkChip } from '../ui/SparkChip.tsx'
import { StatusPill } from '../ui/StatusPill.tsx'

export interface TargetDetailDrawerProps {
  open: boolean
  account: Account
  target: ContractTarget | null
  templates: MessageTemplate[]
  daySummary: TargetDaySummary | undefined
  accountJobs: SendJob[]
  unresolvedReviewJobs: SendJob[]
  activeTab: 'config' | 'history'
  tasks: BackgroundTasksController
  onTabChange: (tab: 'config' | 'history') => void
  onClose: () => void
  onChanged: () => Promise<void> | void
}

const HHMM_RE = /^([01]\d|2[0-3]):[0-5]\d$/

export function TargetDetailDrawer({
  open,
  account,
  target,
  templates,
  daySummary,
  accountJobs,
  unresolvedReviewJobs,
  activeTab,
  tasks,
  onTabChange,
  onClose,
  onChanged,
}: TargetDetailDrawerProps) {
  const [templateId, setTemplateId] = useState('')
  const [sendTimeHhmm, setSendTimeHhmm] = useState('')
  const [savingRule, setSavingRule] = useState(false)

  const [sendMode, setSendMode] = useState<'template' | 'text'>('template')
  const [sendTemplateId, setSendTemplateId] = useState('')
  const [customText, setCustomText] = useState('')
  const [sendingNow, setSendingNow] = useState(false)
  const [busyJobId, setBusyJobId] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!target) return
    setTemplateId(target.templateId ?? '')
    setSendTimeHhmm(target.sendTimeHhmm ?? '')
    setSendTemplateId(target.templateId ?? templates.find((t) => t.enabled)?.id ?? '')
    setNotice(null)
    setError(null)
  }, [target?.id, target?.templateId, target?.sendTimeHhmm, templates])

  const targetJobs = useMemo(() => {
    if (!target) return []
    const byId = new Map<string, SendJob>()
    for (const j of [...unresolvedReviewJobs, ...accountJobs]) {
      if (j.targetId === target.id) byId.set(j.id, j)
    }
    return Array.from(byId.values()).sort((a, b) => {
      const ta = Date.parse(a.updatedAt || a.createdAt) || 0
      const tb = Date.parse(b.updatedAt || b.createdAt) || 0
      return tb - ta
    })
  }, [accountJobs, unresolvedReviewJobs, target])

  if (!open || !target) return null

  const templatesById = new Map(templates.map((t) => [t.id, t]))
  const previewTpl = sendTemplateId ? templatesById.get(sendTemplateId) ?? null : null
  const preview =
    sendMode === 'template'
      ? previewTpl
        ? previewTemplateForTarget(previewTpl, target)
        : null
      : {
          kind: 'text' as const,
          primaryText:
            renderTemplateVariables(customText, {
              targetName: target.nicknameSnapshot,
              streakDays: target.streakDays,
            }) || '（请在上方输入临时发送文案）',
          candidates: [],
          mutedNote: null,
        }

  const pausedBlocked = !account.enabled || !target.enabled
  const reviewBlocked = Boolean(daySummary?.hasUnresolvedReview)
  const repeatToday = Boolean(daySummary?.todaySucceeded && !reviewBlocked && !pausedBlocked)
  const canSend =
    !pausedBlocked &&
    !reviewBlocked &&
    !sendingNow &&
    (sendMode === 'template' ? Boolean(sendTemplateId) : customText.trim().length > 0)

  async function handleSaveRule() {
    if (!target) return
    const cleanTime = sendTimeHhmm.trim()
    if (cleanTime !== '' && !HHMM_RE.test(cleanTime)) {
      setError('精确发送时刻格式需为 HH:MM（例如 20:30）')
      return
    }
    setSavingRule(true)
    setError(null)
    setNotice(null)
    try {
      await api.patchTarget(target.id, {
        templateId: templateId || null,
        sendTimeHhmm: cleanTime === '' ? null : cleanTime,
      })
      setSendTemplateId(templateId)
      setNotice('好友规则已保存')
      await onChanged()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '保存规则失败')
    } finally {
      setSavingRule(false)
    }
  }

  async function handleDeleteTarget() {
    if (!target) return
    if (
      !window.confirm(
        `确定移除好友「${target.nicknameSnapshot}」？\n移除后停止自动续火并隐藏该好友，历史发送记录仍保留备查。`,
      )
    ) {
      return
    }
    try {
      await api.deleteTarget(target.id)
      onClose()
      await onChanged()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '删除好友失败')
    }
  }

  async function handleSingleSend() {
    if (!target || !canSend) return
    if (
      repeatToday &&
      !window.confirm(
        `「${target.nicknameSnapshot}」今日已有成功发送记录，确定要再次额外发送一条消息吗？`,
      )
    ) {
      return
    }
    setSendingNow(true)
    setError(null)
    setNotice(null)
    const payload =
      sendMode === 'template'
        ? { templateId: sendTemplateId }
        : { content: customText.trim() }
    const res = await tasks.runTargetSend(account.id, target, payload)
    setSendingNow(false)
    if (res?.overall === 'ok') {
      setNotice('单人消息已投递完成')
    } else if (res) {
      setError(res.detail ?? '投递结束，部分状态需在发送记录中人工核对')
    }
  }

  async function handleJobAction(jobId: string, action: 'confirm' | 'release') {
    setBusyJobId(jobId)
    setError(null)
    try {
      if (action === 'confirm') await api.confirmJobSent(jobId)
      else await api.releaseJob(jobId)
      await onChanged()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '任务状态更新失败')
    } finally {
      setBusyJobId(null)
    }
  }

  const footer =
    activeTab === 'config' ? (
      <>
        <span style={{ fontSize: 12, color: reviewBlocked ? 'var(--warn)' : 'var(--muted)' }}>
          {reviewBlocked
            ? '存在待人工复核任务，禁止盲目重发'
            : pausedBlocked
              ? '请先启用当前账号与该好友'
              : repeatToday
                ? '今日已成功发送过，再次点击需二次确认'
                : '发送结果将写入今日幂等记录'}
        </span>
        <button
          type="button"
          className="fc-btn fc-btn-primary"
          disabled={!canSend}
          onClick={() => void handleSingleSend()}
        >
          {sendingNow ? '发送中…' : repeatToday ? '今日已发 · 再次发送' : '立即发给 TA'}
        </button>
      </>
    ) : undefined

  return (
    <DrawerShell
      open={open}
      title={target.nicknameSnapshot}
      subtitle={`${target.platformHandle ? `@${target.platformHandle} · ` : ''}本地累计 ${target.streakDays} 天`}
      headerActions={
        <button
          type="button"
          className="fc-btn fc-btn-ghost fc-btn-icon"
          title="移除此好友"
          aria-label="移除此好友"
          onClick={() => void handleDeleteTarget()}
        >
          <TrashIcon />
        </button>
      }
      tabs={[
        { key: 'config', label: '规则与直发' },
        { key: 'history', label: `发送记录 (${targetJobs.length})` },
      ]}
      activeTab={activeTab}
      onTabChange={onTabChange}
      footer={footer}
      onClose={onClose}
    >
      {error && <div className="fc-alert is-error">{error}</div>}
      {notice && <div className="fc-alert is-ok">{notice}</div>}

      {activeTab === 'config' ? (
        <>
          <div className="fc-kv-list">
            <div className="fc-kv-row">
              <span style={{ color: 'var(--muted)' }}>官方火花快照（只读）</span>
              <SparkChip
                officialDays={target.sparkDays}
                kind={target.sparkKind}
                label={target.sparkLabel}
                scannedAt={target.sparkScannedAt}
              />
            </div>
            <div className="fc-kv-row">
              <span style={{ color: 'var(--muted)' }}>身份核验状态</span>
              <span>
                {target.platformUserId || target.platformConversationId
                  ? '稳定会话身份已绑定'
                  : '仅昵称匹配（可在扫描对账中补采）'}
              </span>
            </div>
          </div>

          <div className="fc-card" style={{ padding: 14, gap: 12 }}>
            <div style={{ fontWeight: 600, fontSize: 13.5 }}>日常续火规则配置</div>

            <div className="fc-field">
              <label className="fc-label" htmlFor="td-template">
                默认绑定模板
              </label>
              <select
                id="td-template"
                className="fc-select"
                value={templateId}
                onChange={(e) => setTemplateId(e.target.value)}
              >
                <option value="">— 未绑定模板 —</option>
                {templates.map((tpl) => (
                  <option key={tpl.id} value={tpl.id}>
                    {tpl.name}
                    {!tpl.enabled ? '（已停用）' : ''}
                  </option>
                ))}
              </select>
            </div>

            <div className="fc-field">
              <label className="fc-label" htmlFor="td-hhmm">
                <span>精确发送时刻覆写（HH:MM）</span>
                <span className="fc-hint">留空则跟随当前抖音号时段</span>
              </label>
              <input
                id="td-hhmm"
                className="fc-input fc-mono"
                placeholder="跟随账号"
                value={sendTimeHhmm}
                onChange={(e) => setSendTimeHhmm(e.target.value)}
              />
            </div>

            <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
              <button
                type="button"
                className="fc-btn fc-btn-secondary fc-btn-sm"
                disabled={savingRule}
                onClick={() => void handleSaveRule()}
              >
                {savingRule ? '保存中…' : '保存规则修改'}
              </button>
            </div>
          </div>

          <div className="fc-field">
            <div className="fc-label">
              <span>即时单发与效果预览</span>
              <PillTabs
                items={[
                  { key: 'template', label: '按模板' },
                  { key: 'text', label: '临时文字' },
                ]}
                value={sendMode}
                onChange={setSendMode}
                ariaLabel="选择单发消息来源"
              />
            </div>

            {sendMode === 'template' ? (
              <select
                className="fc-select"
                aria-label="本次单发使用的模板"
                value={sendTemplateId}
                onChange={(e) => setSendTemplateId(e.target.value)}
              >
                <option value="">请选择要发送的模板…</option>
                {templates
                  .filter((t) => t.enabled)
                  .map((tpl) => (
                    <option key={tpl.id} value={tpl.id}>
                      {tpl.name}
                    </option>
                  ))}
              </select>
            ) : (
              <SlashVariableInput
                value={customText}
                onChange={setCustomText}
                ariaLabel="临时发送文案"
              />
            )}
          </div>

          <div className="fc-preview-stage">
            <div className="fc-preview-head">
              <span>按「{target.nicknameSnapshot}」真实数据渲染今日效果</span>
              <span className="fc-mono">{preview?.kind.toUpperCase() ?? 'PREVIEW'}</span>
            </div>
            <div className="fc-chat-bubble">
              {preview ? preview.primaryText : '请先选择一条启用中的消息模板'}
            </div>
            {preview?.mutedNote && <div className="fc-hint">{preview.mutedNote}</div>}
          </div>
        </>
      ) : targetJobs.length === 0 ? (
        <div className="fc-empty" style={{ padding: '28px 16px' }}>
          <div style={{ fontWeight: 600 }}>暂无该好友的近期发送记录</div>
          <p className="fc-hint">自动排程或手动单发后，状态与回执时间会记录在此处。</p>
        </div>
      ) : (
        <div className="fc-kv-list">
          {targetJobs.map((job) => {
            const tplName = job.templateId
              ? (templatesById.get(job.templateId)?.name ?? '模板')
              : '临时直发文字'
            const isReview = job.status === 'unknown_requires_review'
            const busy = busyJobId === job.id
            return (
              <div
                key={job.id}
                className="fc-kv-row"
                style={{ flexDirection: 'column', alignItems: 'stretch', gap: 6 }}
              >
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <span className="fc-mono" style={{ fontWeight: 600 }}>
                    {job.businessDate.slice(0, 10)} · {formatDateTime(job.updatedAt)}
                  </span>
                  <StatusPill tone={JOB_STATUS_LIGHT_TONES[job.status]}>
                    {JOB_STATUS_LABELS[job.status]}
                  </StatusPill>
                </div>
                <div style={{ color: 'var(--muted)', display: 'flex', justifyContent: 'space-between', gap: 8 }}>
                  <span>{tplName}</span>
                  {job.failureClass && (
                    <span style={{ color: 'var(--warn)' }}>
                      原因：{FAILURE_CLASS_LABELS[job.failureClass]}
                    </span>
                  )}
                </div>
                {isReview && (
                  <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 6, marginTop: 2 }}>
                    <button
                      type="button"
                      className="fc-btn fc-btn-secondary fc-btn-sm"
                      disabled={busy}
                      onClick={() => void handleJobAction(job.id, 'release')}
                    >
                      释放重算
                    </button>
                    <button
                      type="button"
                      className="fc-btn fc-btn-primary fc-btn-sm"
                      disabled={busy}
                      onClick={() => void handleJobAction(job.id, 'confirm')}
                    >
                      确认已发
                    </button>
                  </div>
                )}
              </div>
            )
          })}
        </div>
      )}
    </DrawerShell>
  )
}
