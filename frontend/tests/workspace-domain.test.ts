import test from 'node:test'
import assert from 'node:assert/strict'
import { dateInAccountTimezone, weekdayToPoolBit } from '../src/lib/date.ts'
import { previewTemplateForTarget, renderTemplateVariables } from '../src/lib/preview.ts'
import {
  buildInitialOverridesDraft,
  collectAccountReviewItems,
  deriveTargetDayMap,
  describeBarkCardState,
  filterScanReconciliationItems,
  formatBackupImportSummary,
  hasObservedSpark,
  mergeAccountSendPreview,
  parseBarkInputPreview,
  parseSchedulerSettingsForm,
  parseSelectorOverridesDraft,
  pickDefaultScreenshotAttempt,
  resolveDrawerOnViewChange,
  selectAutoImportSparkTitles,
  shouldFinalizeRecoveredScan,
  shouldReloadOpenJobDetail,
  sortScanReconciliationItems,
  validateBarkUrlInput,
} from '../src/lib/workspace.ts'
import { formatAttemptStage } from '../src/lib/format.ts'
import { normalizeStatsSummary } from '../src/api.ts'
import {
  computePageSizeFromHeight,
  formatSlotBadge,
  paginateItems,
  resolveCardPageCapacity,
} from '../src/lib/pagination.ts'
import { normalizeThemePreference, resolveEffectiveTheme } from '../src/lib/theme.ts'

test('dateInAccountTimezone resolves Asia/Shanghai across UTC midnight', () => {
  const instant = new Date('2026-11-17T17:30:00Z') // 2026-11-18 01:30 CST
  assert.equal(dateInAccountTimezone(instant, 'Asia/Shanghai'), '2026-11-18')
  assert.equal(dateInAccountTimezone(instant, 'Invalid/Zone'), '2026-11-17')
})

test('weekdayToPoolBit maps Mon..Sun to bits 0..6 and Sunday 0 -> bit 6', () => {
  assert.equal(weekdayToPoolBit(1), 0)
  assert.equal(weekdayToPoolBit(6), 5)
  assert.equal(weekdayToPoolBit(0), 6)
})

test('previewTemplateForTarget handles weekdayMask=0 as everyday and filters Sunday bit 6', () => {
  const sunday = new Date('2026-11-15T12:00:00+08:00') // Sunday
  const tpl = {
    id: 'tpl-pool',
    name: '池',
    type: 'pool' as const,
    content: '',
    imagePath: null,
    stickerName: null,
    stickerLabel: null,
    stickerCategory: null,
    stickerFallbackIndex: null,
    version: 1,
    enabled: true,
    createdAt: '',
    updatedAt: '',
    poolItems: [
      { id: 'p1', content: '仅周一 {{target_name}}', weekdayMask: 1 << 0, enabled: true, lastUsedAt: null },
      { id: 'p2', content: '周日好 {{target_name}} 第{{streak_days}}天', weekdayMask: 1 << 6, enabled: true, lastUsedAt: null },
      { id: 'p3', content: '通用每天可用', weekdayMask: 0, enabled: true, lastUsedAt: null },
      { id: 'p4', content: '已停用候选', weekdayMask: 0, enabled: false, lastUsedAt: null },
    ],
  }
  const target = {
    id: 't1',
    accountId: 'a1',
    platformUserId: 'u1',
    platformHandle: null,
    platformConversationId: null,
    conversationType: 'user' as const,
    nicknameSnapshot: '林知夏',
    templateId: 'tpl-pool',
    enabled: true,
    sendTimeHhmm: null,
    sparkDays: 42,
    sparkKind: null,
    sparkLabel: '42天',
    sparkScannedAt: null,
    streakDays: 42,
    lastSentAt: null,
    lastConfirmedAt: null,
    deletedAt: null,
    createdAt: '',
    updatedAt: '',
  }
  const res = previewTemplateForTarget(tpl, target, sunday)
  assert.deepEqual(res.candidates, ['周日好 林知夏 第42天', '通用每天可用'])
  assert.equal(
    renderTemplateVariables('{{weekday}}', { targetName: '林知夏', streakDays: 42, now: sunday }),
    '星期日',
  )
})

test('deriveTargetDayMap & collectAccountReviewItems surface historical unresolved review jobs and block single send', () => {
  const target = {
    id: 't1',
    accountId: 'a1',
    platformUserId: 'u1',
    platformHandle: null,
    platformConversationId: null,
    conversationType: 'user' as const,
    nicknameSnapshot: '陈默',
    templateId: 'tpl1',
    enabled: true,
    sendTimeHhmm: null,
    sparkDays: 19,
    sparkKind: null,
    sparkLabel: null,
    sparkScannedAt: null,
    streakDays: 19,
    lastSentAt: null,
    lastConfirmedAt: null,
    deletedAt: null,
    createdAt: '',
    updatedAt: '',
  }
  const oldReviewJob = {
    id: 'j-old',
    accountId: 'a1',
    targetId: 't1',
    templateId: 'tpl1',
    businessDate: '2026-11-15',
    messageSlotId: '2026-11-15:v1',
    status: 'unknown_requires_review' as const,
    scheduledAt: null,
    attemptCount: 1,
    failureClass: 'unknown_outcome' as const,
    confirmedAt: null,
    createdAt: '2026-11-15T10:00:00Z',
    updatedAt: '2026-11-15T10:01:00Z',
  }
  const dayMap = deriveTargetDayMap([target], [], [oldReviewJob], '2026-11-18')
  const summary = dayMap.get('t1')!
  assert.equal(summary.hasUnresolvedReview, true)
  assert.equal(summary.singleSendGuard, 'blocked_review')

  const account = {
    id: 'a1',
    displayName: '主号',
    status: 'healthy' as const,
    enabled: true,
    timezone: 'Asia/Shanghai',
    sendWindowStart: '08:00',
    sendWindowEnd: '22:00',
    sendTimeHhmm: null,
    lastVerifiedAt: null,
    lastSuccessAt: null,
    douyinNickname: null,
    createdAt: '',
    updatedAt: '',
  }
  const items = collectAccountReviewItems(account, [target], [oldReviewJob], '2026-11-18')
  assert.equal(items.length, 1)
  assert.equal(items[0].kind, 'job_review')
  if (items[0].kind === 'job_review') {
    assert.equal(items[0].isHistorical, true)
  }
})

test('mergeAccountSendPreview synthesizes disabled, succeeded, and review-protected skip reasons', () => {
  const mkTarget = (id: string, name: string, enabled: boolean) => ({
    id,
    accountId: 'a1',
    platformUserId: id,
    platformHandle: null,
    platformConversationId: null,
    conversationType: 'user' as const,
    nicknameSnapshot: name,
    templateId: 'tpl1',
    enabled,
    sendTimeHhmm: null,
    sparkDays: null,
    sparkKind: null,
    sparkLabel: null,
    sparkScannedAt: null,
    streakDays: 5,
    lastSentAt: null,
    lastConfirmedAt: null,
    deletedAt: null,
    createdAt: '',
    updatedAt: '',
  })
  const targets = [
    mkTarget('t1', '待发好友', true),
    mkTarget('t2', '已发好友', true),
    mkTarget('t3', '停用好友', false),
  ]
  const succeededJob = {
    id: 'j2',
    accountId: 'a1',
    targetId: 't2',
    templateId: 'tpl1',
    businessDate: '2026-11-18',
    messageSlotId: 's',
    status: 'succeeded' as const,
    scheduledAt: null,
    attemptCount: 1,
    failureClass: null,
    confirmedAt: null,
    createdAt: '2026-11-18T01:00:00Z',
    updatedAt: '2026-11-18T01:00:00Z',
  }
  const dayMap = deriveTargetDayMap(targets, [succeededJob], [], '2026-11-18')
  const preview = {
    accountId: 'a1',
    businessDate: '2026-11-18',
    items: [
      { targetId: 't1', targetName: '待发好友', templateId: 'tpl1', message: '早安', reason: null },
      { targetId: 't2', targetName: '已发好友', templateId: 'tpl1', message: '早安', reason: null },
    ],
  }
  const normalRows = mergeAccountSendPreview(targets, dayMap, preview, false)
  assert.equal(normalRows.find((r) => r.target.id === 't1')?.willSend, true)
  assert.equal(normalRows.find((r) => r.target.id === 't2')?.willSend, false)
  assert.match(normalRows.find((r) => r.target.id === 't2')?.skipReason ?? '', /今日已发/)
  assert.match(normalRows.find((r) => r.target.id === 't3')?.skipReason ?? '', /未启用/)

  const forcedRows = mergeAccountSendPreview(targets, dayMap, preview, true)
  assert.equal(forcedRows.find((r) => r.target.id === 't2')?.willSend, true)
})

test('shouldFinalizeRecoveredScan skips historical completed scans already recorded in syncedIds', () => {
  const completedScan = {
    taskId: 'scan-1',
    accountId: 'a1',
    source: 'manual' as const,
    status: 'completed' as const,
    total: 10,
    completed: 10,
    stage: 'done',
    currentItem: null,
    round: 1,
    maxRounds: 1,
    overall: 'ok' as const,
    detail: null,
    entries: [],
    reconciliation: {
      schemaVersion: 1,
      counts: { changed: 0, noSpark: 0, new: 0, notObserved: 0, unchanged: 0 },
      entries: [],
    },
    startedAt: null,
    finishedAt: null,
  }
  assert.equal(shouldFinalizeRecoveredScan(completedScan, new Set(['scan-1'])), false)
  assert.equal(shouldFinalizeRecoveredScan(completedScan, new Set()), true)
})

test('normalizeStatsSummary maps snake_case window_days and null successRate safely', () => {
  const stats = normalizeStatsSummary({
    window_days: 30,
    daily: [],
    currentSuccessStreak: 0,
    bestSuccessStreak: 0,
    totalJobs: 0,
    succeededJobs: 0,
    successRate: null,
  })
  assert.equal(stats.windowDays, 30)
  assert.equal(stats.successRate, null)
})

test('parseSchedulerSettingsForm enforces 1..168 scan hours and preserves 0 grace minutes', () => {
  assert.deepEqual(parseSchedulerSettingsForm('24', '0', false), {
    ok: true,
    patch: {
      autoScanIntervalHours: 24,
      fixedTimeGraceMinutes: 0,
      scheduledAlwaysSend: false,
      scheduledSkipOfficialLitToday: false,
    },
  })
  assert.deepEqual(parseSchedulerSettingsForm('12', '30', false, true), {
    ok: true,
    patch: {
      autoScanIntervalHours: 12,
      fixedTimeGraceMinutes: 30,
      scheduledAlwaysSend: false,
      scheduledSkipOfficialLitToday: true,
    },
  })
  const zeroScan = parseSchedulerSettingsForm('0', '30', true)
  assert.equal(zeroScan.ok, false)
  const overScan = parseSchedulerSettingsForm('200', '30', false)
  assert.equal(overScan.ok, false)
})

test('theme helpers normalize preference and resolve system dark/light preference', () => {
  assert.equal(normalizeThemePreference(null), 'system')
  assert.equal(normalizeThemePreference('invalid'), 'system')
  assert.equal(normalizeThemePreference('dark'), 'dark')
  assert.equal(normalizeThemePreference('light'), 'light')

  assert.equal(resolveEffectiveTheme('light', true), 'light')
  assert.equal(resolveEffectiveTheme('dark', false), 'dark')
  assert.equal(resolveEffectiveTheme('system', true), 'dark')
  assert.equal(resolveEffectiveTheme('system', false), 'light')
})

test('computePageSizeFromHeight and paginateItems fit viewport without overflow and clamp pages', () => {
  // 640px body height with 48px rows -> 13 rows fit cleanly without scrolling
  assert.equal(computePageSizeFromHeight(640, 48, 5, 25), 13)
  assert.equal(computePageSizeFromHeight(120, 48, 5, 25), 5)

  const items = Array.from({ length: 15 }, (_, i) => `item-${i + 1}`)
  const page2 = paginateItems(items, 2, 10)
  assert.equal(page2.totalPages, 2)
  assert.equal(page2.safePage, 2)
  assert.equal(page2.rangeStart, 11)
  assert.equal(page2.rangeEnd, 15)
  assert.equal(page2.pageItems.length, 5)

  // Filtering down clamps out-of-range page 3 -> page 1
  const clamped = paginateItems(['only-one'], 3, 10)
  assert.equal(clamped.safePage, 1)
  assert.deepEqual(clamped.pageItems, ['only-one'])
})

test('formatSlotBadge condenses verbose UUID slot strings into readable single-line labels', () => {
  assert.equal(
    formatSlotBadge('0f15bc8b-a4b7-4cb6-b58d-f16753eedf94:v1:force:1784255cb525'),
    'v1 · 手动发送',
  )
  assert.equal(formatSlotBadge('2026-11-18:v2'), 'v2 · 每日排程')
})

test('resolveCardPageCapacity avoids footer oscillation on boundary item counts', () => {
  const opts = { headerPx: 38, footerPx: 44, rowPx: 47, minRows: 5, maxRows: 25 }
  // 660px card: withoutFooter = floor((660-38)/47) = 13, withFooter = floor((660-38-44)/47) = 12
  assert.deepEqual(resolveCardPageCapacity(660, 13, opts), {
    pageSize: 13,
    showPagination: false,
  })
  assert.deepEqual(resolveCardPageCapacity(660, 14, opts), {
    pageSize: 12,
    showPagination: true,
  })
})

test('formatAttemptStage maps known pipeline and manual audit stages and falls back safely', () => {
  assert.equal(formatAttemptStage('opening_chat'), '定位会话')
  assert.equal(formatAttemptStage('sending'), '发送消息')
  assert.equal(formatAttemptStage('confirming'), '确认回执')
  assert.equal(formatAttemptStage('manual_confirm_sent'), '人工确认已发')
  assert.equal(formatAttemptStage('manual_release'), '人工释放重算')
  assert.equal(formatAttemptStage('custom_stage'), 'custom_stage')
})

test('pickDefaultScreenshotAttempt prefers latest available shot then latest expired shot and returns null when none', () => {
  const base = {
    stage: 'confirming',
    status: 'failed' as const,
    failureClass: 'unknown_outcome' as const,
    startedAt: '2026-11-18T10:00:00Z',
    finishedAt: '2026-11-18T10:00:05Z',
  }

  assert.equal(
    pickDefaultScreenshotAttempt([
      { ...base, id: 'a1', attemptNo: 1, screenshotState: 'available' },
      { ...base, id: 'a2', attemptNo: 2, screenshotState: 'expired' },
    ]),
    1,
  )

  assert.equal(
    pickDefaultScreenshotAttempt([
      { ...base, id: 'a1', attemptNo: 1, screenshotState: 'available' },
      { ...base, id: 'a2', attemptNo: 2, screenshotState: 'available' },
      { ...base, id: 'a3', attemptNo: 3, stage: 'manual_confirm_sent', status: 'succeeded', failureClass: null, screenshotState: 'none' },
    ]),
    2,
  )

  assert.equal(
    pickDefaultScreenshotAttempt([
      { ...base, id: 'a1', attemptNo: 1, screenshotState: 'expired' },
      { ...base, id: 'a2', attemptNo: 2, screenshotState: 'none' },
    ]),
    1,
  )

  assert.equal(
    pickDefaultScreenshotAttempt([
      { ...base, id: 'a1', attemptNo: 1, screenshotState: 'none' },
    ]),
    null,
  )
})

test('resolveDrawerOnViewChange closes job-detail across view switches while preserving same-view clicks', () => {
  const jobDrawer = { kind: 'job-detail' as const, jobId: 'j1' }
  assert.deepEqual(resolveDrawerOnViewChange('jobs', 'jobs', jobDrawer), jobDrawer)
  assert.deepEqual(resolveDrawerOnViewChange('targets', 'targets', jobDrawer), jobDrawer)
  assert.deepEqual(resolveDrawerOnViewChange('jobs', 'targets', jobDrawer), { kind: 'closed' })
  assert.deepEqual(resolveDrawerOnViewChange('targets', 'jobs', jobDrawer), { kind: 'closed' })
})

test('shouldReloadOpenJobDetail detects external status or timestamp updates for the active job', () => {
  assert.equal(
    shouldReloadOpenJobDetail(
      { id: 'j1', status: 'unknown_requires_review', updatedAt: '2026-11-18T08:00:00Z' },
      { id: 'j1', status: 'succeeded', updatedAt: '2026-11-18T08:01:00Z' },
    ),
    true,
  )
  assert.equal(
    shouldReloadOpenJobDetail(
      { id: 'j1', status: 'unknown_requires_review', updatedAt: '2026-11-18T08:00:00Z' },
      { id: 'j1', status: 'unknown_requires_review', updatedAt: '2026-11-18T08:00:00Z' },
    ),
    false,
  )
  assert.equal(shouldReloadOpenJobDetail(null, null), false)
})

test('describeBarkCardState reflects configured status, source, masked preview and validates Bark URL input', () => {
  const unconf = describeBarkCardState(null)
  assert.equal(unconf.configured, false)
  assert.equal(unconf.badgeLabel, '未配置')
  assert.equal(unconf.canSendTest, false)
  assert.equal(unconf.canClearOverride, false)

  const envConf = describeBarkCardState({
    barkConfigured: true,
    barkSource: 'env',
    barkMaskedUrl: 'https://api.day.app/****1234',
  })
  assert.equal(envConf.configured, true)
  assert.equal(envConf.badgeLabel, '已启用')
  assert.equal(envConf.canSendTest, true)
  assert.equal(envConf.canClearOverride, false)
  assert.equal(envConf.maskedUrl, 'https://api.day.app/****1234')

  const uiConf = describeBarkCardState({
    barkConfigured: true,
    barkSource: 'settings',
    barkMaskedUrl: 'https://api.day.app/****9876',
  })
  assert.equal(uiConf.canClearOverride, true)
  assert.equal(uiConf.sourceLabel, '页面配置')

  assert.deepEqual(validateBarkUrlInput('  https://api.day.app/MyDeviceKey123  '), {
    ok: true,
    value: 'https://api.day.app/MyDeviceKey123',
  })
  assert.deepEqual(
    validateBarkUrlInput(
      'curl -X GET https://api.day.app/ExampleDeviceKey123456/title/body?group=example&ttl=600',
    ),
    {
      ok: true,
      value: 'https://api.day.app/ExampleDeviceKey123456',
    },
  )
  assert.deepEqual(
    validateBarkUrlInput(
      'curl -X POST https://api.day.app/ExampleDeviceKey123456 \\\n  -d "title=title" \\\n  -d "body=body"',
    ),
    {
      ok: true,
      value: 'https://api.day.app/ExampleDeviceKey123456',
    },
  )
  const jsonExample = [
    'curl -X POST https://api.day.app/push \\',
    '  -H "Content-Type: application/json" \\',
    '  -d \'{',
    '    "device_key": "ExampleDeviceKey123456",',
    '    "title": "title",',
    '    "body": "body",',
    '    "group": "example",',
    '    "ttl": 600',
    '  }\'',
  ].join('\n')
  assert.deepEqual(validateBarkUrlInput(jsonExample), {
    ok: true,
    value: 'https://api.day.app/ExampleDeviceKey123456',
  })
  const preview = parseBarkInputPreview(jsonExample)
  assert.equal(preview.ok, true)
  if (preview.ok) {
    assert.equal(preview.masked, 'https://api.day.app/****3456')
    assert.equal(preview.extractedFromExample, true)
    assert.ok(!preview.masked.includes('ExampleDeviceKey123456'))
  }
  assert.deepEqual(
    validateBarkUrlInput('https://api.day.app/ExampleDeviceKey123456/这里改成你自己的推送内容'),
    {
      ok: true,
      value: 'https://api.day.app/ExampleDeviceKey123456',
    },
  )
  assert.equal(validateBarkUrlInput('').ok, false)
  assert.equal(validateBarkUrlInput('ftp://api.day.app/abc').ok, false)
  assert.equal(validateBarkUrlInput('https://api.day.app/').ok, false)
  assert.equal(validateBarkUrlInput('https://api.day.app/push').ok, false)
  assert.equal(validateBarkUrlInput('https://api.day.app/<你的设备Key>').ok, false)
  assert.equal(
    validateBarkUrlInput('curl -X POST https://api.day.app/push -d \'{"title": "missing"}\'').ok,
    false,
  )
})

test('scan reconciliation sorting, filtering and auto-selection prioritize new spark chats', () => {
  const emptyPrev = { days: null, kind: null, label: null, scannedAt: null }
  assert.equal(hasObservedSpark({ days: 88, kind: 'normal', label: '88天' }), true)
  assert.equal(hasObservedSpark({ days: null, kind: 'normal', label: '3 天后消失' }), true)
  assert.equal(hasObservedSpark({ days: null, kind: null, label: '无火花' }), false)
  assert.equal(hasObservedSpark(emptyPrev), false)

  const reconItems = [
    {
      targetId: null,
      title: '普通无火花A',
      conversationType: 'user' as const,
      category: 'new' as const,
      previous: emptyPrev,
      current: { days: null, kind: null, label: '无火花', scannedAt: null },
    },
    {
      targetId: null,
      title: '已导入高火花',
      conversationType: 'user' as const,
      category: 'new' as const,
      previous: emptyPrev,
      current: { days: 500, kind: 'normal', label: '500天', scannedAt: null },
    },
    {
      targetId: null,
      title: '新倒计时好友',
      conversationType: 'user' as const,
      category: 'new' as const,
      previous: emptyPrev,
      current: { days: null, kind: 'normal', label: '2 天后消失', scannedAt: null },
    },
    {
      targetId: null,
      title: '新火花朋友B',
      conversationType: 'group' as const,
      category: 'new' as const,
      previous: emptyPrev,
      current: { days: 120, kind: 'normal', label: '120天', scannedAt: null },
    },
    {
      targetId: null,
      title: '同名冲突火花',
      conversationType: 'user' as const,
      category: 'new' as const,
      previous: emptyPrev,
      current: { days: 300, kind: 'normal', label: '300天', scannedAt: null },
    },
  ]

  const mkEntry = (
    title: string,
    opts?: {
      conversationType?: 'user' | 'group'
      identityStatus?: 'bound' | 'ambiguous' | 'not_observed'
      conversationStatus?: 'bound' | 'ambiguous' | 'not_observed'
      platformHandle?: string | null
    },
  ) => ({
    title,
    platformUserId: 'u-' + title,
    platformHandle: opts?.platformHandle ?? null,
    platformConversationId: opts?.conversationType === 'group' ? 'c-' + title : null,
    conversationType: opts?.conversationType ?? ('user' as const),
    identityStatus: opts?.identityStatus ?? ('bound' as const),
    identitySource: null,
    identityCandidateCount: 1,
    identityDetail: null,
    conversationStatus: opts?.conversationStatus ?? ('bound' as const),
    conversationCandidateCount: 1,
    conversationDetail: null,
    preview: '',
    streakDays: null,
    streakKind: null,
    streakLabel: null,
  })

  const entriesByTitle = new Map([
    ['普通无火花A', mkEntry('普通无火花A')],
    ['已导入高火花', mkEntry('已导入高火花')],
    ['新倒计时好友', mkEntry('新倒计时好友', { platformHandle: 'dy_handle_99' })],
    ['新火花朋友B', mkEntry('新火花朋友B', { conversationType: 'group' })],
    ['同名冲突火花', mkEntry('同名冲突火花', { identityStatus: 'ambiguous' })],
  ])
  const existingTitles = new Set(['已导入高火花'])

  // 排序：未导入有火花(数字降序 -> 无数字标签) -> 已导入有火花 -> 无火花
  const sorted = sortScanReconciliationItems(reconItems, 'new', existingTitles)
  assert.deepEqual(
    sorted.map((i) => i.title),
    ['同名冲突火花', '新火花朋友B', '新倒计时好友', '已导入高火花', '普通无火花A'],
  )

  // 搜索：匹配昵称、抖音号、官方标签与群聊筛选
  assert.deepEqual(
    filterScanReconciliationItems(sorted, 'dy_handle_99', entriesByTitle).map((i) => i.title),
    ['新倒计时好友'],
  )
  assert.deepEqual(
    filterScanReconciliationItems(sorted, '天后消失', entriesByTitle).map((i) => i.title),
    ['新倒计时好友'],
  )
  assert.deepEqual(
    filterScanReconciliationItems(sorted, '群', entriesByTitle).map((i) => i.title),
    ['新火花朋友B'],
  )

  // 自动勾选：只选未导入、有火花且无同名冲突的条目
  assert.deepEqual(
    selectAutoImportSparkTitles(reconItems, entriesByTitle, existingTitles),
    ['新倒计时好友', '新火花朋友B'],
  )
})

test('parseSelectorOverridesDraft extracts changed keys and rejects invalid types or CHAT_URL', () => {
  const base = {
    CHAT_URL: 'https://www.douyin.com/chat',
    CHAT_EDITOR: 'div[data-slate-editor="true"]',
    STICKER_BUTTONS: ['svg.messageMsgInputiconAction', 'button[aria-label*="表情"]'],
    SEND_BUTTONS: ['[class*="publishBtn"]'],
  }
  const editableKeys = ['CHAT_EDITOR', 'STICKER_BUTTONS', 'SEND_BUTTONS']

  // 默认草稿预填常用核心键；即使已有单项覆盖，草稿也保留其余核心键供对照编辑
  const draft = buildInitialOverridesDraft(
    {
      ...base,
      STICKER_ITEM_NAME: '.emojiDesc',
    },
    { CHAT_EDITOR: '.custom-editor' },
  )
  assert.equal(typeof draft, 'string')
  const draftObj = JSON.parse(draft) as Record<string, unknown>
  assert.equal(draftObj.CHAT_EDITOR, '.custom-editor')
  assert.ok('STICKER_BUTTONS' in draftObj)
  assert.ok('STICKER_ITEM_NAME' in draftObj)

  // 仅提取相对 base 发生变化的键
  const parsed = parseSelectorOverridesDraft(
    JSON.stringify({
      CHAT_EDITOR: 'div[data-slate-editor="true"]',
      STICKER_BUTTONS: ['.dy-new-emoji-btn'],
    }),
    base,
    editableKeys,
  )
  assert.equal(parsed.ok, true)
  if (parsed.ok) {
    assert.deepEqual(parsed.overrides, {
      STICKER_BUTTONS: ['.dy-new-emoji-btn'],
    })
  }

  // 拦截非法 JSON、CHAT_URL 越界与数组/字符串类型错配
  assert.equal(parseSelectorOverridesDraft('{bad', base, editableKeys).ok, false)
  assert.equal(
    parseSelectorOverridesDraft(
      JSON.stringify({ CHAT_URL: 'https://evil.example.com' }),
      base,
      editableKeys,
    ).ok,
    false,
  )
  assert.equal(
    parseSelectorOverridesDraft(
      JSON.stringify({ STICKER_BUTTONS: '.not-an-array' }),
      base,
      editableKeys,
    ).ok,
    false,
  )
  assert.equal(
    parseSelectorOverridesDraft(
      JSON.stringify({ CHAT_EDITOR: ['.not-a-string'] }),
      base,
      editableKeys,
    ).ok,
    false,
  )
})

test('formatBackupImportSummary formats counts, restored images, and missing-image guidance', () => {
  const fullZip = formatBackupImportSummary({
    inserted: { accounts: 1, targets: 3, templates: 2, pool_items: 0, jobs: 4, attempts: 4, schedules: 0 },
    updated: { accounts: 0, targets: 1, templates: 1, pool_items: 0, jobs: 0, attempts: 0, schedules: 0 },
    restoredImages: 2,
    missingImageTemplates: 0,
  })
  assert.equal(fullZip.tone, 'ok')
  assert.match(fullZip.headline, /新增 14/)
  assert.match(fullZip.headline, /合并更新 2/)
  assert.match(fullZip.countsDetail, /账号 1/)
  assert.match(fullZip.countsDetail, /好友 4/)
  assert.match(fullZip.countsDetail, /模板 3/)
  assert.equal(fullZip.imagesDetail, '已恢复图片模板：2 张')
  assert.equal(fullZip.missingImagesHint, null)

  const jsonMissing = formatBackupImportSummary({
    inserted: { accounts: 1, templates: 1 },
    updated: {},
    restoredImages: 0,
    missingImageTemplates: 1,
  })
  assert.equal(jsonMissing.tone, 'warn')
  assert.equal(jsonMissing.imagesDetail, null)
  assert.match(jsonMissing.missingImagesHint ?? '', /1 个图片模板/)
})
