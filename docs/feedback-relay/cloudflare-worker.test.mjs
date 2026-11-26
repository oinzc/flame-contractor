import test from 'node:test'
import assert from 'node:assert/strict'
import worker, { buildIssuePayload, detectImageFormat, sanitizeSensitiveText } from './cloudflare-worker.js'

const PNG_BYTES = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d])
const JPEG_BYTES = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46])
const WEBP_BYTES = new Uint8Array([
  0x52, 0x49, 0x46, 0x46, 0x1a, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50, 0x56, 0x50, 0x38, 0x20,
])

function toBase64(bytes) {
  return Buffer.from(bytes).toString('base64')
}

function createMockR2() {
  const store = new Map()
  const deleted = []
  return {
    store,
    deleted,
    async put(key, value, options = {}) {
      const bytes = value instanceof Uint8Array ? new Uint8Array(value) : new Uint8Array(value)
      store.set(key, {
        bytes,
        httpMetadata: options.httpMetadata || {},
      })
    },
    async get(key) {
      const entry = store.get(key)
      if (!entry) return null
      return {
        body: entry.bytes,
        httpMetadata: entry.httpMetadata,
        async arrayBuffer() {
          return entry.bytes.buffer.slice(entry.bytes.byteOffset, entry.bytes.byteOffset + entry.bytes.byteLength)
        },
      }
    },
    async delete(key) {
      deleted.push(key)
      store.delete(key)
    },
  }
}

function createMockKV() {
  const store = new Map()
  const deleted = []
  return {
    store,
    deleted,
    async put(key, value, options = {}) {
      const bytes = value instanceof Uint8Array ? new Uint8Array(value) : new Uint8Array(value)
      store.set(key, {
        bytes,
        expirationTtl: options.expirationTtl,
        metadata: options.metadata || null,
      })
    },
    async getWithMetadata(key, type) {
      const entry = store.get(key)
      if (!entry) return { value: null, metadata: null }
      if (type === 'arrayBuffer') {
        return {
          value: entry.bytes.buffer.slice(entry.bytes.byteOffset, entry.bytes.byteOffset + entry.bytes.byteLength),
          metadata: entry.metadata,
        }
      }
      return { value: Buffer.from(entry.bytes).toString('utf8'), metadata: entry.metadata }
    },
    async get(key, type) {
      const res = await this.getWithMetadata(key, type)
      return res.value
    },
    async delete(key) {
      deleted.push(key)
      store.delete(key)
    },
  }
}

let ipCounter = 1
function nextClientIp() {
  ipCounter += 1
  return `198.51.100.${ipCounter}`
}

function makeFeedbackRequest(payload, { url = 'https://feedback.example.com/v1/feedback', ip = nextClientIp() } = {}) {
  return new Request(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'CF-Connecting-IP': ip,
    },
    body: JSON.stringify(payload),
  })
}

async function withMockFetch(mockImpl, fn) {
  const originalFetch = globalThis.fetch
  globalThis.fetch = mockImpl
  try {
    return await fn()
  } finally {
    globalThis.fetch = originalFetch
  }
}

test('GET /health 与 GET / 返回存储能力与配置状态字段', async () => {
  const cases = [
    {
      name: '未配置 token 且无图片存储',
      env: {},
      expected: { configured: false, imageUploadSupported: false, imageStorage: 'none' },
    },
    {
      name: '仅绑定 KV',
      env: { GITHUB_TOKEN: 'tok', FEEDBACK_IMAGES_KV: createMockKV() },
      expected: { configured: true, imageUploadSupported: true, imageStorage: 'kv' },
    },
    {
      name: '仅绑定 R2',
      env: { GITHUB_TOKEN: 'tok', FEEDBACK_IMAGES_R2: createMockR2() },
      expected: { configured: true, imageUploadSupported: true, imageStorage: 'r2' },
    },
    {
      name: '同时绑定 R2 和 KV 时优先报告 r2',
      env: { GITHUB_TOKEN: 'tok', FEEDBACK_IMAGES_R2: createMockR2(), FEEDBACK_IMAGES_KV: createMockKV() },
      expected: { configured: true, imageUploadSupported: true, imageStorage: 'r2' },
    },
  ]

  for (const item of cases) {
    for (const path of ['/health', '/']) {
      const resp = await worker.fetch(new Request(`https://feedback.example.com${path}`), item.env)
      assert.equal(resp.status, 200, `${item.name} (${path})`)
      const data = await resp.json()
      assert.deepEqual(data, {
        ok: true,
        service: 'flame-contractor-feedback-relay',
        ...item.expected,
        diagnosticLogSupported: true,
        contactPubliclyDropped: true,
      })
    }
  }
})

test('纯文本反馈完全向后兼容（未绑定图片存储仍可提交）', async () => {
  let capturedIssue = null
  const env = { GITHUB_TOKEN: 'ghp_test_secret_token_1234567890' }

  await withMockFetch(
    async (url, init) => {
      assert.equal(url, 'https://api.github.com/repos/oinzc/flame-contractor/issues')
      capturedIssue = JSON.parse(init.body)
      return new Response(
        JSON.stringify({ number: 77, html_url: 'https://github.com/oinzc/flame-contractor/issues/77' }),
        { status: 201, headers: { 'Content-Type': 'application/json' } },
      )
    },
    async () => {
      const resp = await worker.fetch(
        makeFeedbackRequest({
          kind: 'bug',
          title: '扫码后未跳转',
          content: '测试反馈内容，附带敏感信息 https://api.day.app/MySecretBarkKey123/hi',
          client: 'android',
          version: 'v0.5.0',
          environment: 'Android 14',
          includeEnv: true,
          website: '',
        }),
        env,
      )

      assert.equal(resp.status, 201)
      const data = await resp.json()
      assert.deepEqual(data, {
        ok: true,
        issueNumber: 77,
        issueUrl: 'https://github.com/oinzc/flame-contractor/issues/77',
        imageCount: 0,
        hasDiagnosticLog: false,
        message: '反馈已提交（#77），感谢你的帮助！',
      })
      assert.ok(capturedIssue)
      assert.equal(capturedIssue.title, '[Bug] 扫码后未跳转')
      assert.ok(capturedIssue.body.includes('[REDACTED_BARK_KEY]'))
      assert.ok(!capturedIssue.body.includes('### 附图'))
      assert.ok(!capturedIssue.body.includes('<details>'))
    },
  )

  // 空数组 images: [] 且走 POST / 同样向后兼容
  await withMockFetch(
    async () =>
      new Response(JSON.stringify({ number: 78, html_url: 'https://github.com/oinzc/flame-contractor/issues/78' }), {
        status: 201,
      }),
    async () => {
      const resp = await worker.fetch(
        makeFeedbackRequest(
          {
            kind: 'question',
            content: '这是一个不带图片的使用疑问反馈',
            images: [],
          },
          { url: 'https://feedback.example.com/' },
        ),
        env,
      )
      assert.equal(resp.status, 201)
      const data = await resp.json()
      assert.equal(data.imageCount, 0)
    },
  )
})

test('R2 优先上传、Issue 嵌入附图区块，以及 GET /v1/images/:filename 安全响应头', async () => {
  const r2 = createMockR2()
  const kv = createMockKV()
  let capturedIssue = null

  const env = {
    GITHUB_TOKEN: 'test-token',
    PUBLIC_BASE_URL: 'https://cdn.example.com///',
    FEEDBACK_IMAGES_R2: r2,
    FEEDBACK_IMAGES_KV: kv,
  }

  await withMockFetch(
    async (_url, init) => {
      capturedIssue = JSON.parse(init.body)
      return new Response(
        JSON.stringify({ number: 12, html_url: 'https://github.com/oinzc/flame-contractor/issues/12' }),
        { status: 201 },
      )
    },
    async () => {
      const resp = await worker.fetch(
        makeFeedbackRequest({
          kind: 'bug',
          content: '页面白屏截图如附件所示，请排查。',
          client: 'web',
          version: 'v0.6.0',
          images: [
            { name: 'a.png', mimeType: 'image/png', dataBase64: `data:image/png;base64,${toBase64(PNG_BYTES)}` },
            { name: 'b.webp', mimeType: 'image/webp', dataBase64: toBase64(WEBP_BYTES) },
            // 故意传入错误 mimeType，验证服务端完全依据魔数识别为 jpg / image/jpeg
            { name: 'c.png', mimeType: 'image/png', dataBase64: toBase64(JPEG_BYTES) },
          ],
        }),
        env,
      )

      assert.equal(resp.status, 201)
      const data = await resp.json()
      assert.equal(data.ok, true)
      assert.equal(data.issueNumber, 12)
      assert.equal(data.imageCount, 3)
      assert.equal(data.message, '反馈已提交（#12，含 3 张截图），感谢你的帮助！')
    },
  )

  // R2 写入 3 张，KV 未写入
  assert.equal(r2.store.size, 3)
  assert.equal(kv.store.size, 0)

  const storedKeys = [...r2.store.keys()]
  assert.match(storedKeys[0], /^[0-9a-f-]{36}\.png$/i)
  assert.match(storedKeys[1], /^[0-9a-f-]{36}\.webp$/i)
  assert.match(storedKeys[2], /^[0-9a-f-]{36}\.jpg$/i)

  // 检查 Issue Markdown 区块顺序：「反馈内容」 -> 「附图」 -> 「运行环境」
  const body = capturedIssue.body
  const contentIdx = body.indexOf('### 反馈内容')
  const imagesIdx = body.indexOf('### 附图')
  const envIdx = body.indexOf('### 运行环境（已脱敏）')
  assert.ok(contentIdx >= 0 && imagesIdx > contentIdx && envIdx > imagesIdx)
  assert.ok(body.includes(`![反馈截图 1](https://cdn.example.com/v1/images/${storedKeys[0]})`))
  assert.ok(body.includes(`![反馈截图 2](https://cdn.example.com/v1/images/${storedKeys[1]})`))
  assert.ok(body.includes(`![反馈截图 3](https://cdn.example.com/v1/images/${storedKeys[2]})`))

  // GET /v1/images/:filename 校验二进制与所有安全/CORS 响应头
  const imgResp = await worker.fetch(
    new Request(`https://feedback.example.com/v1/images/${storedKeys[2]}`),
    env,
  )
  assert.equal(imgResp.status, 200)
  assert.equal(imgResp.headers.get('content-type'), 'image/jpeg')
  assert.equal(imgResp.headers.get('x-content-type-options'), 'nosniff')
  assert.equal(imgResp.headers.get('content-security-policy'), "default-src 'none'; sandbox")
  assert.equal(imgResp.headers.get('cache-control'), 'public, max-age=31536000, immutable')
  assert.equal(imgResp.headers.get('cross-origin-resource-policy'), 'cross-origin')
  assert.equal(imgResp.headers.get('access-control-allow-origin'), '*')

  const fetchedBytes = new Uint8Array(await imgResp.arrayBuffer())
  assert.deepEqual(fetchedBytes, JPEG_BYTES)
})

test('KV 降级上传（180 天 TTL + metadata）与读取，以及 R2 未命中时回退 KV', async () => {
  const kv = createMockKV()
  let capturedIssue = null
  const env = {
    GITHUB_TOKEN: 'test-token',
    FEEDBACK_IMAGES_KV: kv,
  }

  await withMockFetch(
    async (_url, init) => {
      capturedIssue = JSON.parse(init.body)
      return new Response(
        JSON.stringify({ number: 33, html_url: 'https://github.com/oinzc/flame-contractor/issues/33' }),
        { status: 201 },
      )
    },
    async () => {
      const resp = await worker.fetch(
        makeFeedbackRequest({
          kind: 'feature',
          content: '希望支持导出统计图表，附上示意图。',
          images: [{ dataBase64: toBase64(PNG_BYTES) }],
        }),
        env,
      )
      assert.equal(resp.status, 201)
      const data = await resp.json()
      assert.equal(data.imageCount, 1)
    },
  )

  assert.equal(kv.store.size, 1)
  const [filename, stored] = [...kv.store.entries()][0]
  assert.match(filename, /^[0-9a-f-]{36}\.png$/i)
  assert.equal(stored.expirationTtl, 180 * 86400)
  assert.deepEqual(stored.metadata, { contentType: 'image/png' })
  // 未配置 PUBLIC_BASE_URL 时回退 url.origin
  assert.ok(capturedIssue.body.includes(`![反馈截图 1](https://feedback.example.com/v1/images/${filename})`))

  // 从 KV 读取
  const imgResp = await worker.fetch(new Request(`https://feedback.example.com/v1/images/${filename}`), env)
  assert.equal(imgResp.status, 200)
  assert.equal(imgResp.headers.get('content-type'), 'image/png')
  assert.equal(imgResp.headers.get('x-content-type-options'), 'nosniff')
  assert.equal(imgResp.headers.get('content-security-policy'), "default-src 'none'; sandbox")
  assert.equal(imgResp.headers.get('cache-control'), 'public, max-age=31536000, immutable')
  assert.equal(imgResp.headers.get('cross-origin-resource-policy'), 'cross-origin')
  assert.deepEqual(new Uint8Array(await imgResp.arrayBuffer()), PNG_BYTES)

  // 当新挂载空 R2 时，读取旧 KV 图片依然按 R2 -> KV 顺序命中
  const emptyR2 = createMockR2()
  const fallbackResp = await worker.fetch(new Request(`https://feedback.example.com/v1/images/${filename}`), {
    ...env,
    FEEDBACK_IMAGES_R2: emptyR2,
  })
  assert.equal(fallbackResp.status, 200)
  assert.deepEqual(new Uint8Array(await fallbackResp.arrayBuffer()), PNG_BYTES)

  // 不存在的合法 UUID 返回 404
  const missingResp = await worker.fetch(
    new Request('https://feedback.example.com/v1/images/550e8400-e29b-41d4-a716-446655440000.png'),
    env,
  )
  assert.equal(missingResp.status, 404)
})

test('未绑定 R2/KV 存储时带图提交返回 503，不调用 GitHub API', async () => {
  let githubCalled = false
  const env = { GITHUB_TOKEN: 'test-token' }

  await withMockFetch(
    async () => {
      githubCalled = true
      return new Response('{}', { status: 201 })
    },
    async () => {
      const resp = await worker.fetch(
        makeFeedbackRequest({
          kind: 'bug',
          content: '带图提交测试未绑定存储场景。',
          images: [{ mimeType: 'image/png', dataBase64: toBase64(PNG_BYTES) }],
        }),
        env,
      )
      assert.equal(resp.status, 503)
      const data = await resp.json()
      assert.deepEqual(data, {
        ok: false,
        error:
          '当前反馈中转服务尚未绑定图片存储（FEEDBACK_IMAGES_R2 / FEEDBACK_IMAGES_KV），请先移除图片或联系维护者配置后重试',
      })
    },
  )

  assert.equal(githubCalled, false)
})

test('魔数校验拦截伪造 MIME、非法 Base64 与空图', async () => {
  const env = { GITHUB_TOKEN: 'test-token', FEEDBACK_IMAGES_KV: createMockKV() }

  // 1. 伪造 MIME: 声明 image/png，实际是 SVG/HTML 文本
  const svgPayload = Buffer.from('<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>').toString(
    'base64',
  )
  const fakeMimeResp = await worker.fetch(
    makeFeedbackRequest({
      content: '测试拦截伪造 MIME 的 SVG 负载',
      images: [{ name: 'evil.png', mimeType: 'image/png', dataBase64: `data:image/png;base64,${svgPayload}` }],
    }),
    env,
  )
  assert.equal(fakeMimeResp.status, 400)
  const fakeMimeBody = await fakeMimeResp.json()
  assert.equal(fakeMimeBody.ok, false)
  assert.match(fakeMimeBody.error, /格式不支持/)

  // 2. GIF 魔数不支持
  const gifBytes = Buffer.from('GIF89a\x01\x00\x01\x00')
  const gifResp = await worker.fetch(
    makeFeedbackRequest({
      content: '测试拦截 GIF 格式图片',
      images: [{ mimeType: 'image/gif', dataBase64: gifBytes.toString('base64') }],
    }),
    env,
  )
  assert.equal(gifResp.status, 400)

  // 3. 非法 Base64
  const badB64Resp = await worker.fetch(
    makeFeedbackRequest({
      content: '测试拦截非法 Base64 数据',
      images: [{ dataBase64: '!!!not_valid_base64!!!' }],
    }),
    env,
  )
  assert.equal(badB64Resp.status, 400)

  // 4. 空图
  const emptyResp = await worker.fetch(
    makeFeedbackRequest({
      content: '测试拦截空图片 Base64 数据',
      images: [{ dataBase64: 'data:image/png;base64,' }],
    }),
    env,
  )
  assert.equal(emptyResp.status, 400)

  // 直接校验 detectImageFormat
  assert.deepEqual(detectImageFormat(JPEG_BYTES), { ext: 'jpg', contentType: 'image/jpeg' })
  assert.deepEqual(detectImageFormat(PNG_BYTES), { ext: 'png', contentType: 'image/png' })
  assert.deepEqual(detectImageFormat(WEBP_BYTES), { ext: 'webp', contentType: 'image/webp' })
  assert.equal(detectImageFormat(new Uint8Array([0x00, 0x01, 0x02])), null)
})

test('限制拦截：超 3 张、单图超 800 KB、总请求超 4 MB，以及精确 800 KB 边界放行', async () => {
  const env = { GITHUB_TOKEN: 'test-token', FEEDBACK_IMAGES_KV: createMockKV() }

  // 超过 3 张 -> 400
  const fourImagesResp = await worker.fetch(
    makeFeedbackRequest({
      content: '测试超过 3 张截图的数量限制',
      images: [
        { dataBase64: toBase64(PNG_BYTES) },
        { dataBase64: toBase64(PNG_BYTES) },
        { dataBase64: toBase64(PNG_BYTES) },
        { dataBase64: toBase64(PNG_BYTES) },
      ],
    }),
    env,
  )
  assert.equal(fourImagesResp.status, 400)
  assert.match((await fourImagesResp.json()).error, /最多.*3/)

  // 单图 800 KB + 1 B -> 400
  const oversizedPng = new Uint8Array(800 * 1024 + 1)
  oversizedPng.set(PNG_BYTES, 0)
  const oversizedResp = await worker.fetch(
    makeFeedbackRequest({
      content: '测试单张图片超过 800KB 限制',
      images: [{ dataBase64: toBase64(oversizedPng) }],
    }),
    env,
  )
  assert.equal(oversizedResp.status, 400)
  assert.match((await oversizedResp.json()).error, /800 KB/)

  // 单图精确 800 KB -> 放行 201
  const exactLimitPng = new Uint8Array(800 * 1024)
  exactLimitPng.set(PNG_BYTES, 0)
  await withMockFetch(
    async () =>
      new Response(JSON.stringify({ number: 88, html_url: 'https://github.com/oinzc/flame-contractor/issues/88' }), {
        status: 201,
      }),
    async () => {
      const exactResp = await worker.fetch(
        makeFeedbackRequest({
          content: '测试单张图片精确 800KB 边界',
          images: [{ dataBase64: toBase64(exactLimitPng) }],
        }),
        env,
      )
      assert.equal(exactResp.status, 201)
    },
  )

  // 总请求体超 4 MB -> 413
  const hugeBody = JSON.stringify({ content: '测试超大请求体积', padding: 'a'.repeat(4 * 1024 * 1024 + 16) })
  const tooLargeResp = await worker.fetch(
    new Request('https://feedback.example.com/v1/feedback', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'CF-Connecting-IP': nextClientIp() },
      body: hugeBody,
    }),
    env,
  )
  assert.equal(tooLargeResp.status, 413)
})

test('GET /v1/images/:filename 严格拦截路径穿越与非白名单扩展名', async () => {
  let getCalled = false
  const env = {
    FEEDBACK_IMAGES_R2: {
      async get() {
        getCalled = true
        return null
      },
    },
  }

  const maliciousUrls = [
    'https://feedback.example.com/v1/images/../../etc/passwd',
    'https://feedback.example.com/v1/images/%2e%2e%2f550e8400-e29b-41d4-a716-446655440000.png',
    'https://feedback.example.com/v1/images/..%2f550e8400-e29b-41d4-a716-446655440000.png',
    'https://feedback.example.com/v1/images/..%5c550e8400-e29b-41d4-a716-446655440000.png',
    'https://feedback.example.com/v1/images/550e8400-e29b-41d4-a716-446655440000.png/extra',
    'https://feedback.example.com/v1/images/550e8400-e29b-41d4-a716-446655440000.svg',
    'https://feedback.example.com/v1/images/550e8400-e29b-41d4-a716-446655440000.html',
    'https://feedback.example.com/v1/images/not-a-uuid.png',
  ]

  for (const url of maliciousUrls) {
    const resp = await worker.fetch(new Request(url), env)
    assert.equal(resp.status, 404, `Expected 404 for ${url}`)
  }

  // 即使收到未经 URL 归一化的原始路径穿越请求对象，也必须直接拦截而不触碰存储
  const rawTraversalResp = await worker.fetch(
    {
      method: 'GET',
      url: 'https://feedback.example.com/v1/images/sub/../550e8400-e29b-41d4-a716-446655440000.png',
      headers: new Headers(),
    },
    env,
  )
  assert.equal(rawTraversalResp.status, 404)

  assert.equal(getCalled, false)
})

test('GitHub Issue 创建失败时自动清理已上传的图片（R2 与 KV 均验证）', async () => {
  // 1. 上游返回 HTTP 500 -> 清理 R2 对象
  const r2 = createMockR2()
  await withMockFetch(
    async () => new Response('upstream error', { status: 500 }),
    async () => {
      const resp = await worker.fetch(
        makeFeedbackRequest({
          content: '测试 GitHub 返回 500 时自动回滚 R2 图片',
          images: [{ dataBase64: toBase64(PNG_BYTES) }, { dataBase64: toBase64(JPEG_BYTES) }],
        }),
        { GITHUB_TOKEN: 'test-token', FEEDBACK_IMAGES_R2: r2 },
      )
      assert.equal(resp.status, 502)
      const data = await resp.json()
      assert.equal(data.ok, false)
      assert.match(data.error, /HTTP 500/)
    },
  )
  assert.equal(r2.deleted.length, 2)
  assert.equal(r2.store.size, 0)

  // 2. 上游网络异常抛错 -> 清理 KV 对象
  const kv = createMockKV()
  await withMockFetch(
    async () => {
      throw new Error('network down')
    },
    async () => {
      const resp = await worker.fetch(
        makeFeedbackRequest({
          content: '测试 GitHub 网络异常时自动回滚 KV 图片',
          images: [{ dataBase64: toBase64(WEBP_BYTES) }],
        }),
        { GITHUB_TOKEN: 'test-token', FEEDBACK_IMAGES_KV: kv },
      )
      assert.equal(resp.status, 502)
    },
  )
  assert.equal(kv.deleted.length, 1)
  assert.equal(kv.store.size, 0)

  // 3. 清理过程本身抛错时不影响 502 错误返回（best-effort）
  const flakyR2 = createMockR2()
  flakyR2.delete = async () => {
    throw new Error('delete failed')
  }
  await withMockFetch(
    async () => new Response('bad gateway', { status: 502 }),
    async () => {
      const resp = await worker.fetch(
        makeFeedbackRequest({
          content: '测试清理删除抛错时不掩盖原始错误返回',
          images: [{ dataBase64: toBase64(PNG_BYTES) }],
        }),
        { GITHUB_TOKEN: 'test-token', FEEDBACK_IMAGES_R2: flakyR2 },
      )
      assert.equal(resp.status, 502)
      const data = await resp.json()
      assert.equal(data.ok, false)
    },
  )
})

test('buildIssuePayload 与 sanitizeSensitiveText 辅助函数验证', () => {
  assert.equal(
    sanitizeSensitiveText('Authorization: Bearer secret_abc ghp_12345678901234567890'),
    'Authorization: Bearer [REDACTED_TOKEN] [REDACTED_GH_TOKEN]',
  )

  const issue = buildIssuePayload(
    { kind: 'bug', content: '界面错位问题', client: 'desktop', version: 'v0.6.0' },
    ['https://feedback.example.com/v1/images/550e8400-e29b-41d4-a716-446655440000.png'],
  )
  assert.ok(issue.body.includes('### 附图\n![反馈截图 1](https://feedback.example.com/v1/images/550e8400-e29b-41d4-a716-446655440000.png)'))
  assert.ok(!issue.body.includes('<details>'))
  assert.deepEqual(issue.labels, ['bug', 'in-app-feedback'])
})

test('diagnosticLog 折叠渲染、顺序位置、防反引号注入与二次脱敏', async () => {
  let capturedIssue = null
  const env = { GITHUB_TOKEN: 'test-token' }

  const rawDiagnosticLog = [
    '[2026-11-25 16:00:00] target=https://www.douyin.com/user/MS4wLjABAAAA9876543210_xyz-AB',
    'cookie: sessionid=secret_sid_001; ttwid=1%7Csecret_ttwid_999',
    'bark: https://api.day.app/MyBarkUrlKey999/test',
    'bark_form: https://bark.selfhost.local/push?device_key=MyDeviceKey888&title=ping',
    'bark_json: {"device_key": "MyJsonDeviceKey777"}',
    'auth: Authorization: Bearer jwt_secret_token_123',
    'gh: ghp_123456789012345678901234',
    '```markdown',
    '</details>',
    '````',
    '# injected heading',
    '```',
  ].join('\n')

  await withMockFetch(
    async (_url, init) => {
      capturedIssue = JSON.parse(init.body)
      return new Response(
        JSON.stringify({ number: 91, html_url: 'https://github.com/oinzc/flame-contractor/issues/91' }),
        { status: 201 },
      )
    },
    async () => {
      const resp = await worker.fetch(
        makeFeedbackRequest({
          kind: 'bug',
          title: '自动发送超时',
          content: '开启定时发送后提示超时，已附上脱敏诊断日志协助排查。',
          client: 'android',
          version: 'v0.6.1',
          environment: 'Android 15 (API 35)',
          includeEnv: true,
          contact: 'tester@example.com',
          diagnosticLog: rawDiagnosticLog,
        }),
        env,
      )

      assert.equal(resp.status, 201)
      const data = await resp.json()
      assert.deepEqual(data, {
        ok: true,
        issueNumber: 91,
        issueUrl: 'https://github.com/oinzc/flame-contractor/issues/91',
        imageCount: 0,
        hasDiagnosticLog: true,
        message: '反馈已提交（#91），感谢你的帮助！',
      })
    },
  )

  assert.ok(capturedIssue)
  const body = capturedIssue.body

  // 区块顺序：运行环境 -> 折叠诊断日志（且旧客户端传入的 contact 绝不写入公开 Issue）
  const envIdx = body.indexOf('### 运行环境（已脱敏）')
  const detailsIdx = body.indexOf('<details>\n<summary>展开脱敏诊断日志（供排查定位）</summary>\n\n```text\n')
  const detailsEndIdx = body.indexOf('\n```\n\n</details>')
  assert.ok(envIdx >= 0, '应包含运行环境区块')
  assert.ok(detailsIdx > envIdx, '折叠日志应位于运行环境之后')
  assert.ok(detailsEndIdx > detailsIdx, '折叠日志应正确闭合')
  assert.equal(body.includes('### 联系方式'), false, '公开 Issue 不得包含联系方式区块')
  assert.equal(body.includes('tester@example.com'), false, '旧客户端传入的 contact 内容必须被彻底丢弃')

  // 二次脱敏校验：不得残留原始敏感值
  for (const secret of [
    'MS4wLjABAAAA9876543210_xyz-AB',
    'secret_sid_001',
    'secret_ttwid_999',
    'MyBarkUrlKey999',
    'MyDeviceKey888',
    'MyJsonDeviceKey777',
    'jwt_secret_token_123',
    'ghp_123456789012345678901234',
  ]) {
    assert.ok(!body.includes(secret), `敏感信息未脱敏: ${secret}`)
  }

  assert.ok(body.includes('https://www.douyin.com/user/[REDACTED_UID]'))
  assert.ok(body.includes('sessionid=[REDACTED]'))
  assert.ok(body.includes('ttwid=[REDACTED]'))
  assert.ok(body.includes('https://api.day.app/[REDACTED_BARK_KEY]/test'))
  assert.ok(body.includes('device_key=[REDACTED]&title=ping'))
  assert.ok(body.includes('{"device_key": "[REDACTED]"}'))
  assert.ok(body.includes('Authorization: Bearer [REDACTED_TOKEN]'))
  assert.ok(body.includes('[REDACTED_GH_TOKEN]'))

  // 防 Markdown 代码块逃逸：提取折叠块内日志文本，验证不存在连续三个及以上反引号
  const logStart = detailsIdx + '<details>\n<summary>展开脱敏诊断日志（供排查定位）</summary>\n\n```text\n'.length
  const embeddedLog = body.slice(logStart, detailsEndIdx)
  assert.ok(!/`{3,}/.test(embeddedLog), '日志正文中不得残留连续 3 个及以上反引号')
  assert.ok(embeddedLog.includes('` ` `markdown'))
  assert.ok(embeddedLog.includes('\n` ` `\n# injected heading'))
})

test('sanitizeSensitiveText 覆盖 sec_uid、device_key 脱敏且多次调用幂等', () => {
  const sample = [
    'uid=MS4wLjABAAAA12345678_-abcd',
    'short=MS4wLjAB12345',
    'device_key=BarkKey001&title=hi',
    'device_key = "BarkKey002"; other=1',
    '[device_key=BarkKey003]',
    'query="a=1&device_key=BarkKey004"',
    '{"device_key": "BarkKey005"}',
    'Cookie: sessionid=s1; sid_tt=s2; ttwid=t1',
    'https://api.day.app/UrlBarkKey99/msg',
  ].join('\n')

  const once = sanitizeSensitiveText(sample)
  assert.ok(once.includes('uid=[REDACTED_UID]'))
  assert.ok(once.includes('short=MS4wLjAB12345'), '不足 8 位后缀的 MS4wLjAB 前缀不应误脱敏')
  assert.ok(once.includes('device_key=[REDACTED]&title=hi'))
  assert.ok(once.includes('device_key=[REDACTED]; other=1'))
  assert.ok(once.includes('[device_key=[REDACTED]]'))
  assert.ok(once.includes('query="a=1&device_key=[REDACTED]"'))
  assert.ok(once.includes('{"device_key": "[REDACTED]"}'))
  assert.ok(once.includes('sessionid=[REDACTED]'))
  assert.ok(once.includes('sid_tt=[REDACTED]'))
  assert.ok(once.includes('ttwid=[REDACTED]'))
  assert.ok(once.includes('https://api.day.app/[REDACTED_BARK_KEY]/msg'))

  // 幂等性：客户端先脱敏、服务端二次脱敏不得破坏占位符
  const twice = sanitizeSensitiveText(once)
  assert.equal(twice, once)
})

test('diagnosticLog 参数校验：非字符串与超 16000 字返回 400，边界 16000 字与空值放行', async () => {
  let githubCalls = 0
  const env = { GITHUB_TOKEN: 'test-token' }

  await withMockFetch(
    async () => {
      githubCalls += 1
      return new Response(
        JSON.stringify({ number: 100 + githubCalls, html_url: `https://github.com/oinzc/flame-contractor/issues/${100 + githubCalls}` }),
        { status: 201 },
      )
    },
    async () => {
      // 1. 非字符串类型 -> 400 且不触发 GitHub API
      for (const badValue of [123, true, false, ['log'], { text: 'log' }]) {
        const resp = await worker.fetch(
          makeFeedbackRequest({
            content: '测试非字符串 diagnosticLog 拦截',
            diagnosticLog: badValue,
          }),
          env,
        )
        assert.equal(resp.status, 400, `Expected 400 for ${JSON.stringify(badValue)}`)
        const data = await resp.json()
        assert.equal(data.ok, false)
        assert.ok(typeof data.error === 'string' && data.error.length > 0)
      }

      // 2. trim() 后超 16000 字 -> 400 且返回清晰中文提示
      const overLimitResp = await worker.fetch(
        makeFeedbackRequest({
          content: '测试超长诊断日志被拦截',
          diagnosticLog: 'x'.repeat(16_001),
        }),
        env,
      )
      assert.equal(overLimitResp.status, 400)
      const overLimitData = await overLimitResp.json()
      assert.equal(overLimitData.ok, false)
      assert.match(overLimitData.error, /16000/)
      assert.equal(githubCalls, 0)

      // 3. trim() 后精确 16000 字 -> 放行 201 且 hasDiagnosticLog: true
      const exactResp = await worker.fetch(
        makeFeedbackRequest({
          content: '测试精确 16000 字边界放行',
          diagnosticLog: `  ${'a'.repeat(16_000)}  `,
        }),
        env,
      )
      assert.equal(exactResp.status, 201)
      const exactData = await exactResp.json()
      assert.equal(exactData.hasDiagnosticLog, true)

      // 4. null / 空字符串 / 纯空白字符串 -> 放行 201 且 hasDiagnosticLog: false
      for (const emptyValue of [null, '', '   \n\t  ']) {
        const resp = await worker.fetch(
          makeFeedbackRequest({
            content: '测试空诊断日志向后兼容放行',
            diagnosticLog: emptyValue,
          }),
          env,
        )
        assert.equal(resp.status, 201)
        const data = await resp.json()
        assert.equal(data.hasDiagnosticLog, false)
      }
    },
  )

  assert.equal(githubCalls, 4)
})

test('隐私加固：兼容旧版客户端传入 contact 返回 201，但彻底丢弃不写入公开 Issue', async () => {
  const capturedIssues = []
  const env = { GITHUB_TOKEN: 'test-token' }

  const legacyCases = [
    { version: 'v0.5.0', contact: '13812345678' },
    { version: 'v0.6.2', contact: 'wechat_private_id_99' },
    { version: 'v0.6.0', contact: 'x'.repeat(260) },
    { version: 'v0.5.1', contact: { phone: '13900001111' } },
  ]

  await withMockFetch(
    async (_url, init) => {
      capturedIssues.push(JSON.parse(init.body))
      const num = 200 + capturedIssues.length
      return new Response(
        JSON.stringify({ number: num, html_url: `https://github.com/oinzc/flame-contractor/issues/${num}` }),
        { status: 201 },
      )
    },
    async () => {
      for (const item of legacyCases) {
        const resp = await worker.fetch(
          makeFeedbackRequest({
            kind: 'bug',
            title: '旧版本反馈兼容测试',
            content: '旧版客户端携带联系方式字段提交不应报错',
            contact: item.contact,
            client: 'android',
            version: item.version,
          }),
          env,
        )
        assert.equal(resp.status, 201, `旧版本 ${item.version} 传 contact 应返回 201`)
      }
    },
  )

  assert.equal(capturedIssues.length, legacyCases.length)
  for (const issue of capturedIssues) {
    assert.equal(issue.body.includes('### 联系方式'), false)
    assert.equal(issue.body.includes('13812345678'), false)
    assert.equal(issue.body.includes('138****5678'), false)
    assert.equal(issue.body.includes('wechat_private_id_99'), false)
    assert.equal(issue.body.includes('13900001111'), false)
    assert.equal(issue.body.includes('x'.repeat(50)), false)
  }

  const directIssue = buildIssuePayload({
    kind: 'question',
    title: '直接调用 buildIssuePayload 测试',
    content: '确认直接构造载荷时也会丢弃联系方式字段',
    contact: 'private@example.com',
    client: 'web',
    version: 'v0.6.2',
  })
  assert.equal(directIssue.body.includes('### 联系方式'), false)
  assert.equal(directIssue.body.includes('private@example.com'), false)
})

test('隐私加固：中国大陆 11 位手机号自动脱敏为前 3 后 4，且不误伤 13 位时间戳、长 ID 与 hex 摘要', async () => {
  // 1. 直接测试 sanitizeSensitiveText 的命中与不误伤边界
  const raw = [
    '独立手机号 13899887766 结束',
    '中文紧邻：联系我13987654321或15911112222谢谢',
    '带区号与符号：tel:18600001234 (+86 17711112222) +86-19933334444 +8616655556666',
    '13位毫秒时间戳：ts=1732596627000 created=1700000000000 pseudo=1381234567890',
    '长数字ID与86前缀13位：order=8613812345678 uid=9876543213812345678 twelve=138123456789 leading0=013812345678',
    '非号段与短号：short=1381234567 invalid2=12812345678 invalid1=11812345678',
    'hex与字母紧邻：sha=deadbeef13812345678 tail=13812345678cafe wrap=a13812345678b id13812345678 13812345678px',
  ].join('\n')

  const scrubbed = sanitizeSensitiveText(raw)

  assert.ok(scrubbed.includes('独立手机号 138****7766 结束'))
  assert.ok(scrubbed.includes('中文紧邻：联系我139****4321或159****2222谢谢'))
  assert.ok(
    scrubbed.includes('带区号与符号：tel:186****1234 (+86 177****2222) +86-199****4444 +86166****6666'),
  )
  for (const rawPhone of [
    '13899887766',
    '13987654321',
    '15911112222',
    '18600001234',
    '17711112222',
    '19933334444',
    '16655556666',
  ]) {
    assert.equal(scrubbed.includes(rawPhone), false, `手机号未脱敏: ${rawPhone}`)
  }

  for (const preserved of [
    'ts=1732596627000',
    'created=1700000000000',
    'pseudo=1381234567890',
    'order=8613812345678',
    'uid=9876543213812345678',
    'twelve=138123456789',
    'leading0=013812345678',
    'short=1381234567',
    'invalid2=12812345678',
    'invalid1=11812345678',
    'sha=deadbeef13812345678',
    'tail=13812345678cafe',
    'wrap=a13812345678b',
    'id13812345678',
    '13812345678px',
  ]) {
    assert.ok(scrubbed.includes(preserved), `非手机号被误改: ${preserved}`)
  }

  // 多次调用幂等
  assert.equal(sanitizeSensitiveText(scrubbed), scrubbed)

  // 2. 端到端测试标题、正文、诊断日志中的手机号自动打码
  let capturedIssue = null
  const env = { GITHUB_TOKEN: 'test-token' }
  await withMockFetch(
    async (_url, init) => {
      capturedIssue = JSON.parse(init.body)
      return new Response(
        JSON.stringify({ number: 210, html_url: 'https://github.com/oinzc/flame-contractor/issues/210' }),
        { status: 201 },
      )
    },
    async () => {
      const resp = await worker.fetch(
        makeFeedbackRequest({
          kind: 'bug',
          title: '账号 13812345678 登录失败',
          content: '我的手机号是13900005678，报错时间戳 1732596627000，流水号 2026112613812345678。',
          contact: '18899990000',
          diagnosticLog: 'ts=1732596627000 phone=15822223333 hash=abcdef13812345678',
          client: 'web',
          version: 'v0.6.2',
        }),
        env,
      )
      assert.equal(resp.status, 201)
    },
  )

  assert.ok(capturedIssue)
  assert.equal(capturedIssue.title, '[Bug] 账号 138****5678 登录失败')
  assert.ok(capturedIssue.body.includes('我的手机号是139****5678'))
  assert.ok(capturedIssue.body.includes('报错时间戳 1732596627000'))
  assert.ok(capturedIssue.body.includes('流水号 2026112613812345678'))
  assert.ok(capturedIssue.body.includes('phone=158****3333'))
  assert.ok(capturedIssue.body.includes('hash=abcdef13812345678'))
  assert.equal(capturedIssue.body.includes('13900005678'), false)
  assert.equal(capturedIssue.body.includes('15822223333'), false)
  assert.equal(capturedIssue.body.includes('18899990000'), false)
  assert.equal(capturedIssue.body.includes('188****0000'), false)
  assert.equal(capturedIssue.body.includes('### 联系方式'), false)
})
