import test from 'node:test'
import assert from 'node:assert/strict'
import {
  describeSparkChip,
  hasOfficialSparkSnapshot,
  sparkKindDisplayName,
  sparkLabelForSnapshot,
} from '../src/lib/spark.ts'
import {
  filterScanReconciliationItems,
  hasObservedSpark,
  selectAutoImportSparkTitles,
  sortScanReconciliationItems,
} from '../src/lib/workspace.ts'

test('sparkLabelForSnapshot and hasOfficialSparkSnapshot never infer official spark from preview text', () => {
  const previewOnlyCases = [
    '已读 · [续火花]',
    '记得续火花',
    '2 天后火花将消失',
    '火花',
    '[比心]',
    '',
  ]

  for (const preview of previewOnlyCases) {
    const snapshot = {
      preview,
      streakDays: null,
      streakKind: null,
      streakLabel: null,
    }
    assert.equal(hasOfficialSparkSnapshot(snapshot), false, `preview=${preview}`)
    assert.equal(sparkLabelForSnapshot(snapshot), '无火花', `preview=${preview}`)
  }

  // streakDays 为 0 且无其他官方容器信号时也不视为官方火花
  assert.equal(
    hasOfficialSparkSnapshot({
      preview: '已读 · [续火花]',
      streakDays: 0,
      streakKind: null,
      streakLabel: null,
    }),
    false,
  )
  assert.equal(
    sparkLabelForSnapshot({
      preview: '已读 · [续火花]',
      streakDays: 0,
      streakKind: null,
      streakLabel: null,
    }),
    '无火花',
  )
})

test('sparkLabelForSnapshot and hasOfficialSparkSnapshot preserve all official labels, days and 6 flame icon kinds', () => {
  assert.equal(
    hasOfficialSparkSnapshot({ streakDays: 515, streakKind: null, streakLabel: null }),
    true,
  )
  assert.equal(
    sparkLabelForSnapshot({ streakDays: 515, streakKind: null, streakLabel: null }),
    null,
  )

  for (const label of ['重燃中 1/3', '点亮中 1/3', '5 天后消失', '2 天后火花将消失']) {
    const entry = {
      streakDays: null,
      streakKind: 'normal',
      streakLabel: label,
      preview: '普通消息',
    }
    assert.equal(hasOfficialSparkSnapshot(entry), true, label)
    assert.equal(sparkLabelForSnapshot(entry), label, label)
  }

  // 全部 6 类官方图标仅图标无文字兜底：判定为有官方火花，且不伪造标签也不误写「无火花」
  for (const kind of ['couple', 'family', 'soulmate', 'mate', 'bosom', 'normal']) {
    const iconOnly = {
      streakDays: null,
      streakKind: kind,
      streakLabel: null,
      preview: '',
    }
    assert.equal(hasOfficialSparkSnapshot(iconOnly), true, kind)
    assert.equal(sparkLabelForSnapshot(iconOnly), null, kind)
  }
})

test('describeSparkChip renders official days, special labels, all 6 icon-only kinds, no-spark and unscanned states', () => {
  const daysView = describeSparkChip({ days: 42, kind: 'normal', label: null })
  assert.equal(daysView.text, '42天')
  assert.equal(daysView.tone, 'spark')
  assert.equal(daysView.glyph, '🔥')

  const rekindleView = describeSparkChip({ days: null, kind: 'normal', label: '重燃中 1/3' })
  assert.equal(rekindleView.text, '重燃中 1/3')
  assert.equal(rekindleView.tone, 'warn')
  assert.equal(rekindleView.rekindling, true)

  const igniteView = describeSparkChip({ days: null, kind: 'mate', label: '点亮中 1/3' })
  assert.equal(igniteView.text, '点亮中 1/3')
  assert.equal(igniteView.tone, 'spark')
  assert.equal(igniteView.igniting, true)

  const fadingView = describeSparkChip({ days: null, kind: 'normal', label: '3 天后消失' })
  assert.equal(fadingView.text, '3 天后消失')
  assert.equal(fadingView.tone, 'warn')
  assert.equal(fadingView.glyph, '⌛')
  assert.equal(fadingView.fading, true)

  const expectedKinds: Record<string, string> = {
    couple: '情侣火花',
    family: '家人火花',
    soulmate: '密友火花',
    mate: '搭子火花',
    bosom: '挚友火花',
    normal: '官方火花',
  }
  for (const [kind, label] of Object.entries(expectedKinds)) {
    assert.equal(sparkKindDisplayName(kind), label)
    const iconOnlyView = describeSparkChip({ days: null, kind, label: null })
    assert.equal(iconOnlyView.text, label)
    assert.equal(iconOnlyView.unknown, false)
    assert.equal(iconOnlyView.observedNoSpark, false)
    assert.equal(iconOnlyView.glyph, '🔥')
    assert.equal(iconOnlyView.tone, kind === 'couple' ? 'warn' : 'spark')
  }

  const noSparkView = describeSparkChip({ days: null, kind: null, label: '无火花' })
  assert.equal(noSparkView.text, '无火花')
  assert.equal(noSparkView.tone, 'err')
  assert.equal(noSparkView.glyph, '×')
  assert.equal(noSparkView.observedNoSpark, true)

  const unscannedView = describeSparkChip({ days: null, kind: null, label: null })
  assert.equal(unscannedView.text, '待扫描')
  assert.equal(unscannedView.tone, 'neutral')
  assert.equal(unscannedView.glyph, '·')
  assert.equal(unscannedView.unknown, true)
})

test('reconciliation auto-selection excludes preview-only no-spark chats and includes all official spark states', () => {
  const emptyPrev = { days: null, kind: null, label: null, scannedAt: null }
  const reconItems = [
    {
      targetId: null,
      title: '仅预览含续火花',
      conversationType: 'user' as const,
      category: 'new' as const,
      previous: emptyPrev,
      current: { days: null, kind: null, label: '无火花', scannedAt: null },
    },
    {
      targetId: null,
      title: '重燃中好友',
      conversationType: 'user' as const,
      category: 'new' as const,
      previous: emptyPrev,
      current: { days: null, kind: 'normal', label: '重燃中 1/3', scannedAt: null },
    },
    {
      targetId: null,
      title: '点亮中好友',
      conversationType: 'user' as const,
      category: 'new' as const,
      previous: emptyPrev,
      current: { days: null, kind: 'mate', label: '点亮中 1/3', scannedAt: null },
    },
    {
      targetId: null,
      title: '消失倒计时好友',
      conversationType: 'user' as const,
      category: 'new' as const,
      previous: emptyPrev,
      current: { days: null, kind: 'couple', label: '2 天后消失', scannedAt: null },
    },
    {
      targetId: null,
      title: '仅图标密友',
      conversationType: 'user' as const,
      category: 'new' as const,
      previous: emptyPrev,
      current: { days: null, kind: 'soulmate', label: null, scannedAt: null },
    },
  ]

  const mkEntry = (title: string, preview = '') => ({
    title,
    platformUserId: `u-${title}`,
    platformHandle: null,
    platformConversationId: null,
    conversationType: 'user' as const,
    identityStatus: 'bound' as const,
    identitySource: null,
    identityCandidateCount: 1,
    identityDetail: null,
    conversationStatus: 'not_observed' as const,
    conversationCandidateCount: 0,
    conversationDetail: null,
    preview,
    streakDays: null,
    streakKind: null,
    streakLabel: null,
  })

  const entriesByTitle = new Map([
    ['仅预览含续火花', mkEntry('仅预览含续火花', '已读 · [续火花]')],
    ['重燃中好友', mkEntry('重燃中好友')],
    ['点亮中好友', mkEntry('点亮中好友')],
    ['消失倒计时好友', mkEntry('消失倒计时好友')],
    ['仅图标密友', mkEntry('仅图标密友')],
  ])

  assert.equal(hasObservedSpark(reconItems[0].current), false)
  assert.equal(hasObservedSpark(reconItems[1].current), true)
  assert.equal(hasObservedSpark(reconItems[2].current), true)
  assert.equal(hasObservedSpark(reconItems[3].current), true)
  assert.equal(hasObservedSpark(reconItems[4].current), true)

  const sorted = sortScanReconciliationItems(reconItems, 'new', new Set())
  assert.equal(sorted[sorted.length - 1].title, '仅预览含续火花')

  assert.deepEqual(
    selectAutoImportSparkTitles(reconItems, entriesByTitle, new Set()),
    ['重燃中好友', '点亮中好友', '消失倒计时好友', '仅图标密友'],
  )

  assert.deepEqual(
    filterScanReconciliationItems(reconItems, '密友火花', entriesByTitle).map((item) => item.title),
    ['仅图标密友'],
  )
})
