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
        message: '反馈已提交（#77），感谢你的帮助！',
      })
      assert.ok(capturedIssue)
      assert.equal(capturedIssue.title, '[Bug] 扫码后未跳转')
      assert.ok(capturedIssue.body.includes('[REDACTED_BARK_KEY]'))
      assert.ok(!capturedIssue.body.includes('### 附图'))
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
  assert.deepEqual(issue.labels, ['bug', 'in-app-feedback'])
})
