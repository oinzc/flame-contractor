import test from 'node:test'
import assert from 'node:assert/strict'
import {
  DEFAULT_PERSISTED_FEEDBACK_DRAFT,
  FEEDBACK_DRAFT_STORAGE_KEY,
  FEEDBACK_LIMITS,
  arePersistedFeedbackDraftsEqual,
  buildFeedbackFallbackText,
  buildWebDiagnosticLog,
  clearPersistedFeedbackDraft,
  estimateBase64DecodedBytes,
  hasPersistedFeedbackDraft,
  loadPersistedFeedbackDraft,
  normalizeFeedbackEndpoint,
  normalizePersistedFeedbackDraft,
  savePersistedFeedbackDraft,
  submitInAppFeedback,
  validateDiagnosticLog,
  validateFeedbackDraft,
  validateFeedbackImages,
  type FeedbackDraftStorageLike,
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
    assert.equal(ok.hasDiagnosticLog, false)
  }
  const parsedBody = JSON.parse(capturedBody) as Record<string, unknown>
  assert.equal(parsedBody.kind, 'bug')
  assert.equal(parsedBody.client, 'desktop')
  assert.equal(parsedBody.contact, '')
  assert.equal(parsedBody.website, '')
  assert.equal('images' in parsedBody, false)
  assert.equal('diagnosticLog' in parsedBody, false)

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
  assert.equal(withImages.includes('--- 脱敏诊断日志 ---'), false)
})

test('validateDiagnosticLog and validateFeedbackDraft enforce 16000 character cap', () => {
  assert.equal(FEEDBACK_LIMITS.maxDiagnosticLog, 16_000)
  assert.equal(validateDiagnosticLog(undefined), null)
  assert.equal(validateDiagnosticLog(null), null)
  assert.equal(validateDiagnosticLog('   \n  '), null)
  assert.equal(validateDiagnosticLog('a'.repeat(16_000)), null)

  const overLimit = validateDiagnosticLog('a'.repeat(16_001))
  assert.ok(overLimit && overLimit.includes('16000'))

  const draftError = validateFeedbackDraft({
    title: '诊断日志超长测试',
    content: '附带的诊断日志超过了协议规定的最大长度',
    contact: '',
    diagnosticLog: 'x'.repeat(16_001),
  })
  assert.ok(draftError && draftError.includes('16000'))

  // Explicitly disabled diagnostic log does not block submission
  assert.equal(
    validateFeedbackDraft({
      title: '关闭附带诊断日志',
      content: '用户未勾选附带诊断日志时不拦截提交',
      contact: '',
      includeDiagnosticLog: false,
      diagnosticLog: 'x'.repeat(16_001),
    }),
    null,
  )
})

test('submitInAppFeedback handles diagnosticLog serialization, validation, and hasDiagnosticLog parsing', async () => {
  // 1. Whitespace-only or includeDiagnosticLog=false omits diagnosticLog field
  let capturedOmitted = ''
  const omittedRes = await submitInAppFeedback(
    'https://feedback.example.com/v1/feedback',
    {
      kind: 'bug',
      title: '不带日志提交',
      content: '纯文本提交时不应向服务端发送空的诊断日志字段',
      contact: '',
      includeEnv: true,
      includeDiagnosticLog: true,
      diagnosticLog: '   \n\t  ',
      client: 'web',
      version: 'v0.6.1',
      environment: 'Web / Docker 控制台',
    },
    async (_input, init) => {
      capturedOmitted = String(init?.body ?? '')
      return new Response(
        JSON.stringify({
          ok: true,
          issueNumber: 31,
          issueUrl: 'https://github.com/oinzc/flame-contractor/issues/31',
          imageCount: 0,
        }),
        { status: 201, headers: { 'Content-Type': 'application/json' } },
      )
    },
  )
  assert.equal(omittedRes.ok, true)
  if (omittedRes.ok) {
    assert.equal(omittedRes.hasDiagnosticLog, false)
  }
  const omittedBody = JSON.parse(capturedOmitted) as Record<string, unknown>
  assert.equal('diagnosticLog' in omittedBody, false)

  let capturedDisabled = ''
  await submitInAppFeedback(
    'https://feedback.example.com/v1/feedback',
    {
      kind: 'question',
      title: '禁用日志开关',
      content: '关闭开关后即便草稿里残留日志也不会发送',
      contact: '',
      includeEnv: false,
      includeDiagnosticLog: false,
      diagnosticLog: 'line 1\nline 2',
      client: 'desktop',
      version: 'v0.6.1',
      environment: '',
    },
    async (_input, init) => {
      capturedDisabled = String(init?.body ?? '')
      return new Response(JSON.stringify({ ok: true, issueNumber: 32 }), {
        status: 201,
        headers: { 'Content-Type': 'application/json' },
      })
    },
  )
  const disabledBody = JSON.parse(capturedDisabled) as Record<string, unknown>
  assert.equal('diagnosticLog' in disabledBody, false)

  // 2. Non-empty diagnosticLog is trimmed and serialized, and hasDiagnosticLog is parsed
  let capturedWithLog = ''
  const withLogRes = await submitInAppFeedback(
    'https://feedback.example.com/v1/feedback',
    {
      kind: 'bug',
      title: '附带诊断日志提交',
      content: '点击续火后提示页面结构变化，已附带脱敏日志',
      contact: '',
      includeEnv: true,
      includeDiagnosticLog: true,
      diagnosticLog: '  === 控制台脱敏诊断摘要 ===\n版本：v0.6.1  ',
      client: 'desktop',
      version: 'v0.6.1',
      environment: 'Windows 桌面端',
    },
    async (_input, init) => {
      capturedWithLog = String(init?.body ?? '')
      return new Response(
        JSON.stringify({
          ok: true,
          issueNumber: 33,
          issueUrl: 'https://github.com/oinzc/flame-contractor/issues/33',
          imageCount: 0,
          hasDiagnosticLog: true,
        }),
        { status: 201, headers: { 'Content-Type': 'application/json' } },
      )
    },
  )
  assert.equal(withLogRes.ok, true)
  if (withLogRes.ok) {
    assert.equal(withLogRes.issueNumber, 33)
    assert.equal(withLogRes.hasDiagnosticLog, true)
    assert.ok(withLogRes.message.includes('已附诊断日志'))
  }
  const withLogBody = JSON.parse(capturedWithLog) as Record<string, unknown>
  assert.equal(withLogBody.diagnosticLog, '=== 控制台脱敏诊断摘要 ===\n版本：v0.6.1')

  // 3. Over-limit diagnosticLog fails locally without calling fetch
  let fetchCalled = false
  const tooLongRes = await submitInAppFeedback(
    'https://feedback.example.com/v1/feedback',
    {
      kind: 'bug',
      title: '日志超限拦截',
      content: '诊断日志超过 16000 字符应在客户端提前拦截',
      contact: '',
      includeEnv: true,
      includeDiagnosticLog: true,
      diagnosticLog: 'z'.repeat(FEEDBACK_LIMITS.maxDiagnosticLog + 1),
      client: 'web',
      version: 'v0.6.1',
      environment: 'Web',
    },
    async () => {
      fetchCalled = true
      return new Response('{}', { status: 201 })
    },
  )
  assert.equal(fetchCalled, false)
  assert.equal(tooLongRes.ok, false)
  if (!tooLongRes.ok) {
    assert.equal(tooLongRes.retryable, false)
    assert.ok(tooLongRes.error.includes('16000'))
  }
})

test('buildWebDiagnosticLog outputs concise runtime digest without leaking sensitive data', () => {
  const summary = buildWebDiagnosticLog({
    version: 'v0.6.1',
    isDesktop: true,
    browserRuntimeLabel: '系统 Edge (WebView2)',
    viewport: { width: 1440, height: 900, devicePixelRatio: 1.25 },
    userAgent:
      'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36 Edg/134.0.0.0',
    pathname: 'https://user:pass@192.168.1.9:8080/settings?token=topsecret999&account=alice#hashSecret',
    platform: 'Win32',
    language: 'zh-CN',
    theme: 'dark',
    online: true,
    timestamp: '2026-11-25T08:30:00.000Z',
  })

  assert.ok(summary.includes('版本：v0.6.1'))
  assert.ok(summary.includes('模式：Windows 桌面端'))
  assert.ok(summary.includes('浏览器内核：系统 Edge (WebView2)'))
  assert.ok(summary.includes('视口尺寸：1440x900 (DPR 1.25)'))
  assert.ok(summary.includes('用户代理简摘：Edg/134.0.0.0 (Windows NT 10.0; Win64; x64)'))
  assert.ok(summary.includes('页面路径：/settings'))
  assert.ok(summary.includes('时间：2026-11-25T08:30:00.000Z'))
  assert.ok(summary.length <= FEEDBACK_LIMITS.maxDiagnosticLog)

  // Must strip origin, credentials, query params, and hash fragments
  assert.equal(summary.includes('192.168.1.9'), false)
  assert.equal(summary.includes('topsecret999'), false)
  assert.equal(summary.includes('alice'), false)
  assert.equal(summary.includes('hashSecret'), false)
  assert.equal(summary.includes('user:pass'), false)

  // Defensive redaction if sensitive strings appear in inputs
  const scrubbed = buildWebDiagnosticLog({
    version: 'v0.6.1',
    isDesktop: false,
    browserRuntimeLabel:
      'Custom https://api.day.app/MyBarkSecret99 sessionid=cookieVal123 MS4wLjABAAAA1234567890',
    userAgent: 'Agent Authorization: Bearer jwt_secret_token_999 ghp_123456789012345678901234',
    pathname: '/accounts/MS4wLjABAAAA1234567890/logs?device_key=dk_secret_888',
    timestamp: '2026-11-25T08:31:00.000Z',
  })
  assert.ok(scrubbed.includes('模式：Web / Docker 控制台'))
  assert.equal(scrubbed.includes('MyBarkSecret99'), false)
  assert.equal(scrubbed.includes('cookieVal123'), false)
  assert.equal(scrubbed.includes('MS4wLjABAAAA1234567890'), false)
  assert.equal(scrubbed.includes('jwt_secret_token_999'), false)
  assert.equal(scrubbed.includes('ghp_123456789012345678901234'), false)
  assert.equal(scrubbed.includes('dk_secret_888'), false)
  assert.ok(scrubbed.includes('[REDACTED_BARK_KEY]'))
  assert.ok(scrubbed.includes('[REDACTED_UID]'))
})

test('buildFeedbackFallbackText appends diagnostic log block only when attached', () => {
  const logText = '=== 控制台脱敏诊断摘要 ===\n版本：v0.6.1\n视口尺寸：1280x800'
  const withLog = buildFeedbackFallbackText({
    kind: 'bug',
    title: '页面渲染异常',
    content: '在窄屏窗口打开设置页时顶部按钮换行异常',
    contact: 'dev@example.com',
    includeEnv: true,
    includeDiagnosticLog: true,
    diagnosticLog: `  ${logText}  `,
    client: 'web',
    version: 'v0.6.1',
    environment: 'Web / Docker 控制台',
  })
  assert.ok(withLog.includes('--- 运行环境（已脱敏）---'))
  assert.ok(withLog.includes('--- 脱敏诊断日志 ---'))
  assert.ok(withLog.includes(logText))
  assert.ok(
    withLog.indexOf('--- 运行环境（已脱敏）---') < withLog.indexOf('--- 脱敏诊断日志 ---'),
  )
  assert.equal(withLog.includes('联系方式'), false)
  assert.equal(withLog.includes('dev@example.com'), false)

  const disabledLog = buildFeedbackFallbackText({
    kind: 'bug',
    title: '未勾选日志',
    content: '未勾选附带诊断日志时不应出现在复制文本里',
    contact: '',
    includeEnv: true,
    includeDiagnosticLog: false,
    diagnosticLog: logText,
    client: 'web',
    version: 'v0.6.1',
    environment: 'Web / Docker 控制台',
  })
  assert.equal(disabledLog.includes('--- 脱敏诊断日志 ---'), false)
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
    title: '联系 13812345678 咨询小组件',
    content: '希望安卓小组件增加更多配色，时间戳 1732596627000\n顺便优化刷新速度',
    contact: 'legacy@example.com',
    client: 'android',
    version: 'v0.5.0',
    environment: 'Android 15 (API 35)',
    includeEnv: true,
  })
  assert.equal(issue.title, '[Feature] 联系 138****5678 咨询小组件')
  assert.deepEqual(issue.labels, ['enhancement', 'in-app-feedback'])
  assert.ok(issue.body.includes('Android App'))
  assert.ok(issue.body.includes('Android 15 (API 35)'))
  assert.ok(issue.body.includes('1732596627000'))
  assert.equal(issue.body.includes('### 联系方式'), false)
  assert.equal(issue.body.includes('legacy@example.com'), false)
})

function createMemoryStorage(): FeedbackDraftStorageLike & { store: Map<string, string> } {
  const store = new Map<string, string>()
  return {
    store,
    getItem(key: string) {
      return store.get(key) ?? null
    },
    setItem(key: string, value: string) {
      store.set(key, value)
    },
    removeItem(key: string) {
      store.delete(key)
    },
  }
}

test('persisted feedback draft helpers save, load, clear, and ignore legacy contact cleanly', () => {
  const storage = createMemoryStorage()

  // Empty storage yields default draft
  const initial = loadPersistedFeedbackDraft(storage)
  assert.deepEqual(initial, DEFAULT_PERSISTED_FEEDBACK_DRAFT)
  assert.equal(hasPersistedFeedbackDraft(initial), false)

  // Save & reload all 6 persisted fields
  const draft = {
    kind: 'feature' as const,
    title: '支持深色模式自动跟随',
    content: '希望弹窗关闭后再次打开依然保留刚才写的建议内容',
    includeEnv: false,
    includeDiagnosticLog: true,
    externalDiagnosticLog: '=== Android 诊断日志 ===\n状态：正常',
  }
  assert.equal(hasPersistedFeedbackDraft(draft), true)
  assert.equal(savePersistedFeedbackDraft(draft, storage), true)
  assert.ok(storage.store.has(FEEDBACK_DRAFT_STORAGE_KEY))

  const restored = loadPersistedFeedbackDraft(storage)
  assert.deepEqual(restored, draft)
  assert.equal(arePersistedFeedbackDraftsEqual(restored, draft), true)

  // Clear removes persisted draft and reverts load to defaults
  assert.equal(clearPersistedFeedbackDraft(storage), true)
  assert.equal(storage.store.has(FEEDBACK_DRAFT_STORAGE_KEY), false)
  assert.deepEqual(loadPersistedFeedbackDraft(storage), DEFAULT_PERSISTED_FEEDBACK_DRAFT)

  // Normalization drops extra/legacy fields (such as contact) and clamps invalid values
  const normalized = normalizePersistedFeedbackDraft({
    kind: 'unknown',
    title: 't'.repeat(FEEDBACK_LIMITS.maxTitle + 20),
    content: 'c'.repeat(FEEDBACK_LIMITS.maxContent + 20),
    contact: '13812345678',
    includeEnv: 'not-bool',
    includeDiagnosticLog: 1,
    externalDiagnosticLog: 42,
  })
  assert.equal(normalized.kind, 'bug')
  assert.equal(normalized.title.length, FEEDBACK_LIMITS.maxTitle)
  assert.equal(normalized.content.length, FEEDBACK_LIMITS.maxContent)
  assert.equal(normalized.includeEnv, true)
  assert.equal(normalized.includeDiagnosticLog, false)
  assert.equal(normalized.externalDiagnosticLog, '')
  assert.equal('contact' in normalized, false)
})

test('persisted feedback draft helpers degrade safely on corrupt JSON or unavailable storage', () => {
  const storage = createMemoryStorage()

  // Corrupt JSON
  storage.setItem(FEEDBACK_DRAFT_STORAGE_KEY, '{invalid-json')
  assert.deepEqual(loadPersistedFeedbackDraft(storage), DEFAULT_PERSISTED_FEEDBACK_DRAFT)

  // Non-object JSON payloads
  for (const weird of ['null', '[]', '"text"', '123', 'true']) {
    storage.setItem(FEEDBACK_DRAFT_STORAGE_KEY, weird)
    assert.deepEqual(loadPersistedFeedbackDraft(storage), DEFAULT_PERSISTED_FEEDBACK_DRAFT)
  }

  // Explicitly unavailable storage (null)
  assert.deepEqual(loadPersistedFeedbackDraft(null), DEFAULT_PERSISTED_FEEDBACK_DRAFT)
  assert.equal(savePersistedFeedbackDraft(DEFAULT_PERSISTED_FEEDBACK_DRAFT, null), false)
  assert.equal(clearPersistedFeedbackDraft(null), false)

  // Throwing storage (e.g. SecurityError / QuotaExceededError)
  const throwingStorage: FeedbackDraftStorageLike = {
    getItem() {
      throw new Error('SecurityError')
    },
    setItem() {
      throw new Error('QuotaExceededError')
    },
    removeItem() {
      throw new Error('SecurityError')
    },
  }
  assert.deepEqual(loadPersistedFeedbackDraft(throwingStorage), DEFAULT_PERSISTED_FEEDBACK_DRAFT)
  assert.equal(savePersistedFeedbackDraft(DEFAULT_PERSISTED_FEEDBACK_DRAFT, throwingStorage), false)
  assert.equal(clearPersistedFeedbackDraft(throwingStorage), false)
})

test('submitInAppFeedback always sends empty contact even if caller passes a legacy contact value', async () => {
  let capturedBody = ''
  const result = await submitInAppFeedback(
    'https://feedback.example.com/v1/feedback',
    {
      kind: 'bug',
      title: '协议兼容测试',
      content: '确认客户端不会向服务端外发任何联系方式',
      contact: '13812345678',
      includeEnv: false,
      client: 'web',
      version: 'v0.6.2',
      environment: '',
    },
    async (_input, init) => {
      capturedBody = String(init?.body ?? '')
      return new Response(JSON.stringify({ ok: true, issueNumber: 99 }), {
        status: 201,
        headers: { 'Content-Type': 'application/json' },
      })
    },
  )
  assert.equal(result.ok, true)
  const parsed = JSON.parse(capturedBody) as Record<string, unknown>
  assert.equal(parsed.contact, '')
})
