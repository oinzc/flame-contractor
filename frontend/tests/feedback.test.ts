import test from 'node:test'
import assert from 'node:assert/strict'
import {
  FEEDBACK_LIMITS,
  buildFeedbackFallbackText,
  estimateBase64DecodedBytes,
  normalizeFeedbackEndpoint,
  submitInAppFeedback,
  validateFeedbackDraft,
  validateFeedbackImages,
} from '../src/lib/feedback.ts'
import {
  computeScaledDimensions,
  formatImageByteSize,
  inferSupportedImageMimeType,
  isSupportedImageFile,
  normalizeFeedbackImageFilename,
  stripBase64DataUrlPrefix,
} from '../src/lib/feedbackImages.ts'
import {
  buildIssuePayload,
  sanitizeSensitiveText,
} from '../../docs/feedback-relay/cloudflare-worker.js'

const TINY_PNG_BASE64 =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII='

test('validateFeedbackDraft enforces min/max bounds cleanly', () => {
  assert.ok(validateFeedbackDraft({ title: '', content: '太短', contact: '' }))
  assert.equal(
    validateFeedbackDraft({
      title: '建议',
      content: '希望支持在首页显示下次发送倒计时',
      contact: 'user@example.com',
    }),
    null,
  )
  assert.ok(
    validateFeedbackDraft({
      title: 'a'.repeat(101),
      content: '正常描述不少于五个字',
      contact: '',
    }),
  )
  assert.ok(
    validateFeedbackDraft({
      title: '',
      content: 'a'.repeat(2001),
      contact: '',
    }),
  )
})

test('validateFeedbackImages enforces count, mimeType, pure base64, and 800KB byte limits', () => {
  assert.equal(validateFeedbackImages(undefined), null)
  assert.equal(validateFeedbackImages([]), null)
  assert.equal(
    validateFeedbackImages([
      { name: 'shot-1.png', mimeType: 'image/png', dataBase64: TINY_PNG_BASE64 },
      { name: 'shot-2.jpg', mimeType: 'image/jpeg', dataBase64: TINY_PNG_BASE64 },
      { name: 'shot-3.webp', mimeType: 'image/webp', dataBase64: TINY_PNG_BASE64 },
    ]),
    null,
  )

  // Exceeds max 3 images
  const tooMany = validateFeedbackImages([
    { name: '1.png', mimeType: 'image/png', dataBase64: TINY_PNG_BASE64 },
    { name: '2.png', mimeType: 'image/png', dataBase64: TINY_PNG_BASE64 },
    { name: '3.png', mimeType: 'image/png', dataBase64: TINY_PNG_BASE64 },
    { name: '4.png', mimeType: 'image/png', dataBase64: TINY_PNG_BASE64 },
  ])
  assert.ok(tooMany && tooMany.includes('3'))

  // Unsupported MIME
  const badMime = validateFeedbackImages([
    { name: 'vector.svg', mimeType: 'image/svg+xml', dataBase64: TINY_PNG_BASE64 },
  ])
  assert.ok(badMime && badMime.includes('JPG'))

  // Empty base64
  const emptyBase64 = validateFeedbackImages([
    { name: 'empty.png', mimeType: 'image/png', dataBase64: '   ' },
  ])
  assert.ok(emptyBase64 && emptyBase64.includes('空'))

  // Rejects data URL prefix (must be pure Base64)
  const prefixed = validateFeedbackImages([
    {
      name: 'prefixed.png',
      mimeType: 'image/png',
      dataBase64: `data:image/png;base64,${TINY_PNG_BASE64}`,
    },
  ])
  assert.ok(prefixed && prefixed.includes('编码'))

  // Exceeds 800 KB decoded size
  const overBytes = FEEDBACK_LIMITS.maxImageBytes + 3
  const overBase64 = Buffer.alloc(overBytes, 0x41).toString('base64')
  assert.equal(estimateBase64DecodedBytes(overBase64), overBytes)
  const tooLarge = validateFeedbackImages([
    { name: 'huge.jpg', mimeType: 'image/jpeg', dataBase64: overBase64 },
  ])
  assert.ok(tooLarge && tooLarge.includes('800 KB'))

  // Exact 800 KB decoded size passes
  const exactBase64 = Buffer.alloc(FEEDBACK_LIMITS.maxImageBytes, 0x42).toString('base64')
  assert.equal(estimateBase64DecodedBytes(exactBase64), FEEDBACK_LIMITS.maxImageBytes)
  assert.equal(
    validateFeedbackImages([
      { name: 'exact.jpg', mimeType: 'image/jpeg', dataBase64: exactBase64 },
    ]),
    null,
  )
})

test('submitInAppFeedback posts JSON, serializes images, parses imageCount, and handles errors cleanly', async () => {
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

  // 1. Plain text feedback omits images and defaults imageCount to 0
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
    assert.equal(ok.imageCount, 0)
  }
  const parsedBody = JSON.parse(capturedBody) as Record<string, unknown>
  assert.equal(parsedBody.kind, 'bug')
  assert.equal(parsedBody.client, 'desktop')
  assert.equal(parsedBody.website, '')
  assert.equal('images' in parsedBody, false)

  // 2. Feedback with screenshots serializes images and parses imageCount
  let capturedWithImages = ''
  const okWithImages = await submitInAppFeedback(
    'https://feedback.example.com/v1/feedback',
    {
      kind: 'bug',
      title: '附带截图测试',
      content: '点击续火按钮后出现异常报错界面如图',
      contact: '',
      includeEnv: true,
      client: 'web',
      version: 'v0.5.0',
      environment: 'Web 控制台',
      images: [
        { name: 'screen-1.png', mimeType: 'image/png', dataBase64: TINY_PNG_BASE64 },
        { name: '  ', mimeType: 'image/jpeg', dataBase64: TINY_PNG_BASE64 },
      ],
    },
    async (_input, init) => {
      capturedWithImages = String(init?.body ?? '')
      return new Response(
        JSON.stringify({
          ok: true,
          issueNumber: 22,
          issueUrl: 'https://github.com/oinzc/flame-contractor/issues/22',
          imageCount: 2,
          message: '反馈已提交（#22，含 2 张截图），感谢你的帮助！',
        }),
        { status: 201, headers: { 'Content-Type': 'application/json' } },
      )
    },
  )

  assert.equal(okWithImages.ok, true)
  if (okWithImages.ok) {
    assert.equal(okWithImages.issueNumber, 22)
    assert.equal(okWithImages.imageCount, 2)
  }
  const parsedWithImages = JSON.parse(capturedWithImages) as {
    images?: Array<{ name: string; mimeType: string; dataBase64: string }>
  }
  assert.equal(parsedWithImages.images?.length, 2)
  assert.deepEqual(parsedWithImages.images?.[0], {
    name: 'screen-1.png',
    mimeType: 'image/png',
    dataBase64: TINY_PNG_BASE64,
  })
  assert.equal(parsedWithImages.images?.[1].name, 'screenshot-2.jpg')

  // 3. Invalid images fail client validation before calling fetch
  let fetchCalled = false
  const invalidImgOutcome = await submitInAppFeedback(
    'https://feedback.example.com/v1/feedback',
    {
      kind: 'bug',
      title: '',
      content: '测试非法截图是否在发送前就被本地拦截',
      contact: '',
      includeEnv: false,
      client: 'web',
      version: 'v0.5.0',
      environment: '',
      images: [
        { name: 'bad.png', mimeType: 'image/png', dataBase64: '' },
      ],
    },
    async () => {
      fetchCalled = true
      return new Response('{}', { status: 201 })
    },
  )
  assert.equal(fetchCalled, false)
  assert.equal(invalidImgOutcome.ok, false)
  if (!invalidImgOutcome.ok) {
    assert.equal(invalidImgOutcome.retryable, false)
  }

  // 4. Rate limit & 503 storage unbound error pass-through
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

  const storageUnbound = await submitInAppFeedback(
    'https://feedback.example.com/v1/feedback',
    {
      kind: 'bug',
      title: '',
      content: '测试服务端未绑定图片存储时的明确提示',
      contact: '',
      includeEnv: false,
      client: 'web',
      version: 'v0.5.0',
      environment: '',
      images: [{ name: 'shot.png', mimeType: 'image/png', dataBase64: TINY_PNG_BASE64 }],
    },
    async () =>
      new Response(
        JSON.stringify({
          ok: false,
          error: '当前反馈中转服务尚未绑定图片存储（FEEDBACK_IMAGES_R2 / FEEDBACK_IMAGES_KV）',
        }),
        { status: 503, headers: { 'Content-Type': 'application/json' } },
      ),
  )
  assert.equal(storageUnbound.ok, false)
  if (!storageUnbound.ok) {
    assert.ok(storageUnbound.error.includes('尚未绑定图片存储'))
    assert.equal(storageUnbound.retryable, true)
  }

  assert.equal(normalizeFeedbackEndpoint('javascript:alert(1)'), null)
  assert.equal(FEEDBACK_LIMITS.submitTimeoutMs, 30_000)
})

test('buildFeedbackFallbackText appends screenshot notice only when images are attached', () => {
  const withoutImages = buildFeedbackFallbackText({
    kind: 'question',
    title: '部署疑问',
    content: '如何配置国内可直连中转',
    contact: '',
    includeEnv: true,
    client: 'web',
    version: 'v0.5.0',
    environment: '版本：v0.5.0',
  })
  assert.ok(withoutImages.includes('【使用疑问】部署疑问'))
  assert.equal(withoutImages.includes('张截图'), false)

  const withImages = buildFeedbackFallbackText({
    kind: 'bug',
    title: '页面显示异常',
    content: '切换深色主题后卡片边框颜色偏暗',
    contact: 'dev@example.com',
    includeEnv: true,
    client: 'desktop',
    version: 'v0.5.0',
    environment: 'Windows 桌面端',
    images: [
      { name: 'a.png', mimeType: 'image/png', dataBase64: TINY_PNG_BASE64 },
      { name: 'b.jpg', mimeType: 'image/jpeg', dataBase64: TINY_PNG_BASE64 },
    ],
  })
  assert.ok(withImages.includes('2 张截图'))
  assert.ok(withImages.includes('复制文本不含图片'))
  assert.ok(withImages.includes('手动补传'))
})

test('feedbackImages pure helpers scale dimensions, strip prefixes, and validate file types', () => {
  assert.deepEqual(computeScaledDimensions(800, 600, 1600), { width: 800, height: 600 })
  assert.deepEqual(computeScaledDimensions(3200, 1600, 1600), { width: 1600, height: 800 })
  assert.deepEqual(computeScaledDimensions(1000, 2000, 1600), { width: 800, height: 1600 })

  assert.equal(
    stripBase64DataUrlPrefix(`data:image/png;base64,${TINY_PNG_BASE64}\n`),
    TINY_PNG_BASE64,
  )
  assert.equal(stripBase64DataUrlPrefix(TINY_PNG_BASE64), TINY_PNG_BASE64)

  assert.equal(formatImageByteSize(512), '512 B')
  assert.equal(formatImageByteSize(214 * 1024), '214 KB')
  assert.equal(formatImageByteSize(1.5 * 1024 * 1024), '1.5 MB')

  assert.equal(inferSupportedImageMimeType({ type: 'image/png', name: 'a.png' }), 'image/png')
  assert.equal(inferSupportedImageMimeType({ type: 'image/jpeg', name: 'a.jpg' }), 'image/jpeg')
  assert.equal(inferSupportedImageMimeType({ type: '', name: 'capture.WEBP' }), 'image/webp')
  assert.equal(isSupportedImageFile({ type: 'image/svg+xml', name: 'x.svg' }), false)
  assert.equal(isSupportedImageFile({ type: 'text/plain', name: 'note.txt' }), false)

  assert.equal(normalizeFeedbackImageFilename('屏幕截图.PNG', 'image/jpeg', 1), '屏幕截图.jpg')
  assert.equal(normalizeFeedbackImageFilename('', 'image/webp', 2), 'screenshot-2.webp')
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
