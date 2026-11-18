import { useEffect, useState } from 'react'
import { api, ApiError } from '../../api.ts'
import {
  buildInitialOverridesDraft,
  parseSelectorOverridesDraft,
} from '../../lib/workspace.ts'
import type {
  Account,
  DryRunRead,
  SelectorBundleRead,
  SelectorProbeItem,
  SelectorProbeRead,
} from '../../types.ts'
import { DrawerShell } from '../ui/DrawerShell.tsx'
import { PillTabs, type PillTabItem } from '../ui/PillTabs.tsx'
import { StatusPill } from '../ui/StatusPill.tsx'

type DryRunTab = 'targets' | 'probe'

export interface DryRunDrawerProps {
  open: boolean
  account: Account | null
  canManageSelectors?: boolean
  onClose: () => void
  onChanged: () => Promise<void> | void
}

function probeStatusPresentation(status: SelectorProbeItem['status']): {
  tone: 'ok' | 'warn' | 'err' | 'neutral'
  label: string
} {
  switch (status) {
    case 'ok':
      return { tone: 'ok', label: '已命中' }
    case 'optional_when_empty':
      return { tone: 'neutral', label: '按需渲染' }
    case 'skipped':
      return { tone: 'neutral', label: '已跳过' }
    case 'missing':
      return { tone: 'err', label: '未命中' }
  }
}

export function DryRunDrawer({
  open,
  account,
  canManageSelectors = false,
  onClose,
  onChanged,
}: DryRunDrawerProps) {
  const [tab, setTab] = useState<DryRunTab>('targets')

  // Tab 1: Dry Run target check
  const [result, setResult] = useState<DryRunRead | null>(null)
  const [running, setRunning] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // Tab 2: Page structure probe + persistent selector overrides
  const [probeResult, setProbeResult] = useState<SelectorProbeRead | null>(null)
  const [probing, setProbing] = useState(false)
  const [probeError, setProbeError] = useState<string | null>(null)

  const [bundle, setBundle] = useState<SelectorBundleRead | null>(null)
  const [editorOpen, setEditorOpen] = useState(false)
  const [draftJson, setDraftJson] = useState('')
  const [savingOverrides, setSavingOverrides] = useState(false)
  const [overrideNotice, setOverrideNotice] = useState<string | null>(null)
  const [overrideError, setOverrideError] = useState<string | null>(null)

  useEffect(() => {
    setResult(null)
    setError(null)
    setProbeResult(null)
    setProbeError(null)
    setOverrideNotice(null)
    setOverrideError(null)
    setEditorOpen(false)
    if (!open) {
      setTab('targets')
    }
  }, [account?.id, open])

  useEffect(() => {
    if (!open || tab !== 'probe') return
    let cancelled = false
    api
      .selectorsBundle()
      .then((data) => {
        if (cancelled) return
        setBundle(data)
        setDraftJson(buildInitialOverridesDraft(data.base_selectors, data.overrides))
      })
      .catch((err) => {
        if (cancelled) return
        setOverrideError(err instanceof ApiError ? err.message : '读取当前定位词配置失败')
      })
    return () => {
      cancelled = true
    }
  }, [open, tab])

  if (!open || !account) return null

  const tabItems: PillTabItem<DryRunTab>[] = [
    { key: 'targets', label: '好友定位演练' },
    {
      key: 'probe',
      label: '页面体检与定位词',
      count: bundle?.has_overrides ? Object.keys(bundle.overrides).length : undefined,
    },
  ]

  async function handleRunTargets() {
    if (!account || running) return
    setRunning(true)
    setError(null)
    try {
      const res = await api.dryRun(account.id)
      setResult(res)
      await onChanged()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '演练检查执行失败')
    } finally {
      setRunning(false)
    }
  }

  async function handleRunProbe() {
    if (!account || probing) return
    setProbing(true)
    setProbeError(null)
    setOverrideNotice(null)
    try {
      const res = await api.probeSelectors(account.id)
      setProbeResult(res)
      await onChanged()
    } catch (err) {
      setProbeError(err instanceof ApiError ? err.message : '页面结构体检执行失败')
    } finally {
      setProbing(false)
    }
  }

  async function handleSaveOverrides() {
    if (!bundle || savingOverrides) return
    setOverrideError(null)
    setOverrideNotice(null)
    const parsed = parseSelectorOverridesDraft(
      draftJson,
      bundle.base_selectors,
      bundle.editable_keys,
    )
    if (!parsed.ok) {
      setOverrideError(parsed.error)
      return
    }
    setSavingOverrides(true)
    try {
      const updated = await api.saveSelectorOverrides(parsed.overrides)
      setBundle(updated)
      setDraftJson(buildInitialOverridesDraft(updated.base_selectors, updated.overrides))
      const count = Object.keys(updated.overrides).length
      setOverrideNotice(
        count > 0
          ? `已保存 ${count} 项自定义定位词至数据卷并立即生效（无需重启容器）`
          : '所填内容与内置默认一致，已保持内置默认配置',
      )
    } catch (err) {
      setOverrideError(err instanceof ApiError ? err.message : '保存定位词覆盖失败')
    } finally {
      setSavingOverrides(false)
    }
  }

  async function handleResetOverrides() {
    if (!bundle || savingOverrides) return
    setSavingOverrides(true)
    setOverrideError(null)
    setOverrideNotice(null)
    try {
      const updated = await api.clearSelectorOverrides()
      setBundle(updated)
      setDraftJson(buildInitialOverridesDraft(updated.base_selectors, updated.overrides))
      setOverrideNotice('已清空自定义覆盖项，恢复为镜像内置默认定位词')
    } catch (err) {
      setOverrideError(err instanceof ApiError ? err.message : '恢复默认定位词失败')
    } finally {
      setSavingOverrides(false)
    }
  }

  return (
    <DrawerShell
      open={open}
      title={`${account.displayName} · 演练与页面体检`}
      subtitle={
        tab === 'targets'
          ? '只打开会话列表核对每位好友能否被唯一匹配，绝不发送任何消息'
          : '零副作用探测抖音聊天框与「比心」表情面板，支持将热修定位词持久化至数据卷'
      }
      onClose={onClose}
      footer={
        tab === 'targets' ? (
          <>
            <span className="fc-hint">演练不会改变累计天数或幂等锁</span>
            <button
              type="button"
              className="fc-btn fc-btn-primary"
              disabled={running || account.status !== 'healthy'}
              onClick={() => void handleRunTargets()}
            >
              {running ? '正在核对会话…' : result ? '重新执行演练' : '开始演练检查'}
            </button>
          </>
        ) : (
          <>
            <span className="fc-hint">全程零输入、零点击表情项、零发送</span>
            <button
              type="button"
              className="fc-btn fc-btn-primary"
              disabled={probing || account.status !== 'healthy'}
              onClick={() => void handleRunProbe()}
            >
              {probing ? '正在探测页面…' : probeResult ? '重新执行页面体检' : '开始页面体检'}
            </button>
          </>
        )
      }
    >
      <div style={{ marginBottom: 12 }}>
        <PillTabs
          items={tabItems}
          value={tab}
          onChange={setTab}
          ariaLabel="演练与页面体检视图切换"
        />
      </div>

      {account.status !== 'healthy' && (
        <div className="fc-alert is-warn">当前账号未处于健康在线状态，请先完成扫码登录。</div>
      )}

      {tab === 'targets' ? (
        <>
          {error && <div className="fc-alert is-error">{error}</div>}
          {!result ? (
            <div className="fc-empty">
              <div style={{ fontWeight: 600 }}>准备执行会话定位校验</div>
              <p className="fc-hint">
                点击右下角「开始演练检查」，后台将打开当前账号会话页逐个验证启用中的好友是否可见且无重名歧义。
              </p>
            </div>
          ) : (
            <>
              <div className={`fc-alert ${result.overall === 'ok' ? 'is-ok' : 'is-warn'}`}>
                演练结论：{result.overall === 'ok' ? '全部正常可读' : (result.detail ?? result.overall)}
              </div>

              <div className="fc-kv-list">
                {result.targets.map((t, i) => {
                  const tone =
                    t.status === 'found' ? 'ok' : t.status === 'ambiguous' ? 'warn' : 'err'
                  const label =
                    t.status === 'found' ? '唯一命中' : t.status === 'ambiguous' ? '同名歧义' : '未找到'
                  return (
                    <div key={`${t.target}-${i}`} className="fc-kv-row">
                      <div>
                        <div style={{ fontWeight: 600 }}>{t.target}</div>
                        {t.detail && <div className="fc-hint">{t.detail}</div>}
                      </div>
                      <StatusPill tone={tone}>{label}</StatusPill>
                    </div>
                  )
                })}
              </div>
            </>
          )}
        </>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          {probeError && <div className="fc-alert is-error">{probeError}</div>}

          {!probeResult ? (
            <div className="fc-empty" style={{ padding: '20px 16px' }}>
              <div style={{ fontWeight: 600 }}>一键核对抖音聊天页与表情面板结构</div>
              <p className="fc-hint">
                点击右下角「开始页面体检」，后台将以只读模式核对会话列表、搜索框、消息输入框、表情面板按钮及「比心」贴纸是否正常命中。
              </p>
            </div>
          ) : (
            <>
              <div
                className={`fc-alert ${
                  probeResult.overall === 'ok'
                    ? probeResult.heartStickerFound === false
                      ? 'is-warn'
                      : 'is-ok'
                    : probeResult.overall === 'degraded'
                      ? 'is-warn'
                      : 'is-error'
                }`}
              >
                {probeResult.overall === 'ok' ? (
                  <>
                    页面结构体检通过
                    {probeResult.sampleConversation
                      ? `（样本会话：${probeResult.sampleConversation}）`
                      : ''}
                    {probeResult.heartStickerFound === true
                      ? ' · 「比心」表情已确认可见'
                      : probeResult.heartStickerFound === false
                        ? ' · 首屏未匹配到「比心」描述文本，请核对 STICKER_ITEM_NAME'
                        : ''}
                  </>
                ) : (
                  <>体检结论：{probeResult.detail ?? probeResult.overall}</>
                )}
              </div>

              <div className="fc-kv-list">
                {probeResult.items.map((item) => {
                  const badge =
                    item.key === 'STICKER_ITEM' &&
                    item.status === 'ok' &&
                    probeResult.heartStickerFound === false
                      ? { tone: 'warn' as const, label: '待核对名称' }
                      : probeStatusPresentation(item.status)
                  return (
                    <div key={item.key} className="fc-kv-row" style={{ alignItems: 'flex-start' }}>
                      <div style={{ minWidth: 0, flex: 1 }}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
                          <span style={{ fontWeight: 600 }}>{item.label}</span>
                          <code
                            className="fc-mono"
                            style={{ fontSize: 11.5, color: 'var(--text-muted)' }}
                          >
                            {item.key}
                          </code>
                        </div>
                        {item.matchedSelector && (
                          <div
                            className="fc-mono"
                            style={{
                              fontSize: 11.5,
                              color: 'var(--text-secondary)',
                              marginTop: 2,
                              wordBreak: 'break-all',
                            }}
                          >
                            命中：{item.matchedSelector}
                          </div>
                        )}
                        {item.detail && <div className="fc-hint">{item.detail}</div>}
                      </div>
                      <StatusPill tone={badge.tone}>{badge.label}</StatusPill>
                    </div>
                  )
                })}
              </div>
            </>
          )}

          {/* Persistent Selector Override Card */}
          <section
            style={{
              border: '1px solid var(--hairline)',
              borderRadius: 'var(--radius-md)',
              padding: '12px 14px',
              background: 'var(--surface-soft)',
              display: 'flex',
              flexDirection: 'column',
              gap: 10,
            }}
          >
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                gap: 8,
                flexWrap: 'wrap',
              }}
            >
              <div style={{ minWidth: 0 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                  <span style={{ fontWeight: 600, fontSize: 14 }}>定位规则热修（持久卷）</span>
                  <StatusPill tone={bundle?.has_overrides ? 'warn' : 'neutral'} dot>
                    {bundle?.has_overrides
                      ? `已自定义 ${Object.keys(bundle.overrides).length} 项`
                      : '内置默认'}
                  </StatusPill>
                  {bundle?.bundle_digest && (
                    <span className="fc-mono fc-hint" style={{ fontSize: 11.5 }}>
                      #{bundle.bundle_digest}
                    </span>
                  )}
                </div>
                <div className="fc-hint" style={{ marginTop: 2 }}>
                  抖音微调网页类名时可在此直接覆盖，保存在 data 卷中，重启或升级镜像均不丢失
                </div>
              </div>

              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                {bundle?.has_overrides && canManageSelectors && (
                  <button
                    type="button"
                    className="fc-btn fc-btn-secondary fc-btn-sm"
                    disabled={savingOverrides}
                    onClick={() => void handleResetOverrides()}
                  >
                    恢复内置默认
                  </button>
                )}
                <button
                  type="button"
                  className="fc-btn fc-btn-secondary fc-btn-sm"
                  disabled={!bundle}
                  onClick={() => {
                    setOverrideError(null)
                    setOverrideNotice(null)
                    setEditorOpen((prev) => !prev)
                  }}
                >
                  {editorOpen ? '收起定位词 JSON' : canManageSelectors ? '编辑定位词覆盖' : '查看当前定位词'}
                </button>
              </div>
            </div>

            {overrideNotice && <div className="fc-alert is-ok">{overrideNotice}</div>}
            {overrideError && <div className="fc-alert is-error">{overrideError}</div>}

            {editorOpen && bundle && (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                <textarea
                  className="fc-textarea fc-mono"
                  aria-label="定位词覆盖 JSON"
                  rows={10}
                  readOnly={!canManageSelectors}
                  value={draftJson}
                  onChange={(e) => setDraftJson(e.target.value)}
                  style={{ fontSize: 12, lineHeight: 1.5 }}
                />
                <div
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    gap: 8,
                    flexWrap: 'wrap',
                  }}
                >
                  <span className="fc-hint">
                    {canManageSelectors
                      ? '仅保存与内置默认不同的键值；CHAT_URL 已锁定禁止覆盖'
                      : '当前账号为普通成员，仅平台管理员可修改全局定位词'}
                  </span>
                  {canManageSelectors && (
                    <button
                      type="button"
                      className="fc-btn fc-btn-secondary fc-btn-sm"
                      disabled={savingOverrides}
                      onClick={() => void handleSaveOverrides()}
                    >
                      {savingOverrides ? '保存中…' : '保存覆盖并立即生效'}
                    </button>
                  )}
                </div>
              </div>
            )}
          </section>
        </div>
      )}
    </DrawerShell>
  )
}
