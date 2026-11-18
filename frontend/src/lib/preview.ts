import type { ContractTarget, MessageTemplate, PoolItem } from '../types.ts'
import { formatWeekdayZh, weekdayToPoolBit } from './date.ts'

export const SAFE_TEMPLATE_VARIABLES = [
  { token: '{{target_name}}', label: '好友昵称', example: '林知夏' },
  { token: '{{streak_days}}', label: '累计天数', example: '42' },
  { token: '{{weekday}}', label: '星期几', example: '星期三' },
  { token: '{{date}}', label: '今日日期', example: '2026/11/18' },
] as const

export interface PreviewContext {
  targetName: string
  streakDays: number
  now?: Date
}

export interface TemplatePreviewResult {
  kind: MessageTemplate['type']
  primaryText: string
  candidates: string[]
  mutedNote: string | null
}

export function renderTemplateVariables(raw: string, ctx: PreviewContext): string {
  const now = ctx.now ?? new Date()
  return raw
    .replaceAll('{{target_name}}', ctx.targetName)
    .replaceAll('{{streak_days}}', String(ctx.streakDays))
    .replaceAll('{{weekday}}', formatWeekdayZh(now))
    .replaceAll('{{date}}', now.toLocaleDateString('zh-CN'))
}

export function resolveTodayPoolCandidates(items: PoolItem[], jsDay: number): PoolItem[] {
  const bit = weekdayToPoolBit(jsDay)
  return items.filter(
    (item) =>
      item.enabled !== false &&
      item.content.trim().length > 0 &&
      (item.weekdayMask === 0 || (item.weekdayMask & (1 << bit)) !== 0),
  )
}

export function previewTemplateForTarget(
  template: MessageTemplate,
  target: ContractTarget | null,
  now: Date = new Date(),
): TemplatePreviewResult {
  const ctx: PreviewContext = {
    targetName: target?.nicknameSnapshot || '示例好友',
    streakDays: target?.streakDays ?? 7,
    now,
  }

  if (template.type === 'text') {
    const rendered = renderTemplateVariables(template.content, ctx)
    return {
      kind: 'text',
      primaryText: rendered || '（空白文本模板）',
      candidates: rendered ? [rendered] : [],
      mutedNote: null,
    }
  }

  if (template.type === 'image') {
    const pathText = template.imagePath?.trim() || '未配置路径'
    return {
      kind: 'image',
      primaryText: `图片投递：${pathText}`,
      candidates: [pathText],
      mutedNote: '发送时直接读取本机图片文件投递给该会话',
    }
  }

  if (template.type === 'sticker') {
    const sticker = template.stickerName?.trim() || '未指定表情'
    const cat = template.stickerCategory?.trim()
    return {
      kind: 'sticker',
      primaryText: `抖音原生表情：「${sticker}」${cat ? `（分类：${cat}）` : ''}`,
      candidates: [sticker],
      mutedNote: '发送时在表情面板匹配名称后直接点击投递',
    }
  }

  const matched = resolveTodayPoolCandidates(template.poolItems ?? [], now.getDay())
  const renderedCandidates = matched.map((item) => renderTemplateVariables(item.content, ctx))
  if (renderedCandidates.length === 0) {
    return {
      kind: 'pool',
      primaryText: `今日（${formatWeekdayZh(now)}）无匹配的启用候选文案`,
      candidates: [],
      mutedNote: '请检查候选条目的星期勾选（全不选代表每天可用）',
    }
  }
  return {
    kind: 'pool',
    primaryText: renderedCandidates[0],
    candidates: renderedCandidates,
    mutedNote:
      renderedCandidates.length > 1
        ? `今日共命中 ${renderedCandidates.length} 条候选，后端按「最少最近使用」轮换发送`
        : '今日唯一命中候选文案',
  }
}
