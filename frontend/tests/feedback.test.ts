import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildFeedbackFallbackText,
  normalizeFeedbackEndpoint,
  submitInAppFeedback,
  validateFeedbackDraft,
} from '../src/lib/feedback.ts'
import {
  buildIssuePayload,
  sanitizeSensitiveText,
} from '../../docs/feedback-relay/cloudflare-worker.js'

test('validateFeedbackDraft enforces min/max bounds cleanly', () => {
  assert.ok(validateFeedbackDraft({ title: '', content: '太短', contact: '' }))
  assert.equal(
    validateFeedbackDraft({ title: '建议', content: '希望支持在首页显示下次发送倒计时', contact: 'user@example.com' }),
    null,
  )
  assert.ok(validateFeedbackDraft({ title: 'a'.repeat(101), content: '正常描述不少于五个字', contact: '' }))
  assert.ok(validateFeedbackDraft({ title: '', content: 'a'.repeat(2001), contact: '' }))
})

test('submitInAppFeedback posts JSON and handles success and rate limit cleanly', async () => {
  let capturedBody = ''
  const fakeFetch: typeof fetch = async (_input, init) => {
    capturedBody = String(init?.body ?? '')
    return new Response(
      JSON.stringify({
        ok: true,
        issueNumber: 18,
        issueUrl: 'https://github.com/oinzc/flame-contractor/issues/18',
        message: '反馈已提交（#18），感谢你的帮助！',
      }),
      { status: 201, headers: { 'Content-Type': 'application/json' } },
    )
  }

  const ok = await submitInAppFeedback(
    'https://feedback.example.com/v1/feedback',
    {
      kind: 'bug',
      title: '桌面端托盘问题',
      content: '点击最小化到托盘后再点开偶发空白',
      contact: '',
      includeEnv: true,
      client: 'desktop',
      version: 'v0.5.0',
      environment: 'Windows 桌面端',
    },
    fakeFetch,
  )

  assert.equal(ok.ok, true)
  if (ok.ok) {
    assert.equal(ok.issueNumber, 18)
    assert.equal(ok.issueUrl, 'https://github.com/oinzc/flame-contractor/issues/18')
  }
  const parsedBody = JSON.parse(capturedBody) as Record<string, unknown>
  assert.equal(parsedBody.kind, 'bug')
  assert.equal(parsedBody.client, 'desktop')
  assert.equal(parsedBody.website, '')

  const rateLimited = await submitInAppFeedback(
    'https://feedback.example.com/v1/feedback',
    {
      kind: 'feature',
      title: '',
      content: '测试限流响应文案是否正确返回',
      contact: '',
      includeEnv: false,
      client: 'web',
      version: 'v0.5.0',
      environment: '',
    },
    async () =>
      new Response(JSON.stringify({ ok: false, error: '提交过于频繁，请稍后再试' }), {
        status: 429,
        headers: { 'Content-Type': 'application/json' },
      }),
  )
  assert.equal(rateLimited.ok, false)
  if (!rateLimited.ok) {
    assert.equal(rateLimited.error, '提交过于频繁，请稍后再试')
    assert.equal(rateLimited.retryable, true)
  }

  assert.equal(normalizeFeedbackEndpoint('javascript:alert(1)'), null)
  assert.ok(
    buildFeedbackFallbackText({
      kind: 'question',
      title: '部署疑问',
      content: '如何配置国内可直连中转',
      contact: '',
      includeEnv: true,
      client: 'web',
      version: 'v0.5.0',
      environment: '版本：v0.5.0',
    }).includes('【使用疑问】部署疑问'),
  )
})

test('cloudflare-worker relay sanitizes accidental secrets and formats issue payload', () => {
  const scrubbed = sanitizeSensitiveText(
    'bark https://api.day.app/SecretKey12345 cookie sessionid=abc999 token ghp_123456789012345678901234',
  )
  assert.ok(!scrubbed.includes('SecretKey12345'))
  assert.ok(!scrubbed.includes('abc999'))
  assert.ok(!scrubbed.includes('ghp_123456789012345678901234'))
  assert.ok(scrubbed.includes('[REDACTED_BARK_KEY]'))
  assert.ok(scrubbed.includes('[REDACTED]'))
  assert.ok(scrubbed.includes('[REDACTED_GH_TOKEN]'))

  const issue = buildIssuePayload({
    kind: 'feature',
    title: '',
    content: '希望安卓小组件增加更多配色\n顺便优化刷新速度',
    contact: '',
    client: 'android',
    version: 'v0.5.0',
    environment: 'Android 15 (API 35)',
    includeEnv: true,
  })
  assert.equal(issue.title, '[Feature] 希望安卓小组件增加更多配色')
  assert.deepEqual(issue.labels, ['enhancement', 'in-app-feedback'])
  assert.ok(issue.body.includes('Android App'))
  assert.ok(issue.body.includes('Android 15 (API 35)'))
})
