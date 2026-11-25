import {
  FEEDBACK_LIMITS,
  estimateBase64DecodedBytes,
  isAllowedFeedbackImageMimeType,
  type FeedbackImageAttachment,
  type FeedbackImageMimeType,
} from './feedback.ts'

export interface PreparedFeedbackImage extends FeedbackImageAttachment {
  id: string
  sizeBytes: number
  width: number
  height: number
  previewUrl: string
}

export interface PrepareFeedbackImageBatchResult {
  added: PreparedFeedbackImage[]
  errors: string[]
}

const EXTENSION_MIME_MAP: Record<string, FeedbackImageMimeType> = {
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  png: 'image/png',
  webp: 'image/webp',
}

const MIME_TO_EXTENSION: Record<FeedbackImageMimeType, string> = {
  'image/jpeg': 'jpg',
  'image/png': 'png',
  'image/webp': 'webp',
}

const WEBP_QUALITY_STEPS = [0.88, 0.78, 0.68, 0.56, 0.45] as const
const JPEG_QUALITY_STEPS = [0.88, 0.78, 0.68, 0.56, 0.45] as const
const FALLBACK_DOWNSCALE_DIMENSION = 1200
const FALLBACK_JPEG_QUALITIES = [0.68, 0.54, 0.42] as const

export function computeScaledDimensions(
  width: number,
  height: number,
  maxDimension: number = FEEDBACK_LIMITS.maxImageDimension,
): { width: number; height: number } {
  const safeW = Math.max(1, Math.round(width))
  const safeH = Math.max(1, Math.round(height))
  const longest = Math.max(safeW, safeH)
  if (longest <= maxDimension) {
    return { width: safeW, height: safeH }
  }
  const scale = maxDimension / longest
  return {
    width: Math.max(1, Math.round(safeW * scale)),
    height: Math.max(1, Math.round(safeH * scale)),
  }
}

export function stripBase64DataUrlPrefix(raw: string): string {
  const trimmed = raw.trim()
  const commaIdx = trimmed.indexOf(',')
  const body =
    trimmed.startsWith('data:') && commaIdx >= 0 ? trimmed.slice(commaIdx + 1) : trimmed
  return body.replace(/\s+/g, '')
}

export function formatImageByteSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B'
  if (bytes < 1024) return `${Math.round(bytes)} B`
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

export function inferSupportedImageMimeType(
  file: Pick<File, 'type' | 'name'>,
): FeedbackImageMimeType | null {
  const rawType = (file.type || '').trim().toLowerCase()
  if (rawType === 'image/jpg') return 'image/jpeg'
  if (isAllowedFeedbackImageMimeType(rawType)) return rawType
  if (rawType && rawType.startsWith('image/')) {
    return null
  }
  const cleanName = (file.name || '').trim().toLowerCase()
  const dotIdx = cleanName.lastIndexOf('.')
  if (dotIdx >= 0 && dotIdx < cleanName.length - 1) {
    const ext = cleanName.slice(dotIdx + 1)
    if (ext in EXTENSION_MIME_MAP) {
      return EXTENSION_MIME_MAP[ext]
    }
  }
  return null
}

export function isSupportedImageFile(file: Pick<File, 'type' | 'name'>): boolean {
  return inferSupportedImageMimeType(file) !== null
}

export function normalizeFeedbackImageFilename(
  rawName: string | null | undefined,
  mimeType: FeedbackImageMimeType,
  fallbackIndex = 1,
): string {
  const ext = MIME_TO_EXTENSION[mimeType]
  const clean = (rawName ?? '')
    .trim()
    .replace(/[/\\:*?"<>|\r\n]+/g, '_')
  if (!clean) {
    return `screenshot-${fallbackIndex}.${ext}`
  }
  const dotIdx = clean.lastIndexOf('.')
  const stem = (dotIdx > 0 ? clean.slice(0, dotIdx) : clean).slice(0, 80).trim()
  return `${stem || `screenshot-${fallbackIndex}`}.${ext}`
}

export function extractClipboardImageFiles(
  clipboardData: Pick<DataTransfer, 'items' | 'files'> | null | undefined,
): File[] {
  if (!clipboardData) return []
  const collected: File[] = []
  if (clipboardData.items && clipboardData.items.length > 0) {
    for (let i = 0; i < clipboardData.items.length; i += 1) {
      const item = clipboardData.items[i]
      if (item.kind === 'file' && item.type.startsWith('image/')) {
        const file = item.getAsFile()
        if (file) collected.push(file)
      }
    }
  }
  if (collected.length > 0) return collected
  if (clipboardData.files && clipboardData.files.length > 0) {
    for (let i = 0; i < clipboardData.files.length; i += 1) {
      const file = clipboardData.files[i]
      if (file.type.startsWith('image/')) {
        collected.push(file)
      }
    }
  }
  return collected
}

export function revokeFeedbackImagePreviews(
  images: ReadonlyArray<Pick<PreparedFeedbackImage, 'previewUrl'>>,
): void {
  if (typeof URL === 'undefined' || typeof URL.revokeObjectURL !== 'function') return
  for (const image of images) {
    if (image.previewUrl) {
      URL.revokeObjectURL(image.previewUrl)
    }
  }
}

interface DecodedImageSource {
  source: CanvasImageSource
  width: number
  height: number
  dispose: () => void
}

async function decodeImageSource(file: File): Promise<DecodedImageSource> {
  if (typeof createImageBitmap === 'function') {
    try {
      const bitmap = await createImageBitmap(file)
      return {
        source: bitmap,
        width: bitmap.width,
        height: bitmap.height,
        dispose: () => bitmap.close(),
      }
    } catch {
      // Fallback to HTMLImageElement below
    }
  }

  if (typeof Image === 'undefined' || typeof URL === 'undefined') {
    throw new Error('当前环境不支持图片解析')
  }

  const objectUrl = URL.createObjectURL(file)
  try {
    const img = await new Promise<HTMLImageElement>((resolve, reject) => {
      const el = new Image()
      el.onload = () => resolve(el)
      el.onerror = () => reject(new Error('decode-failed'))
      el.src = objectUrl
    })
    return {
      source: img,
      width: img.naturalWidth || img.width,
      height: img.naturalHeight || img.height,
      dispose: () => URL.revokeObjectURL(objectUrl),
    }
  } catch {
    URL.revokeObjectURL(objectUrl)
    throw new Error('无法解析该图片文件，请确认图片未损坏')
  }
}

function renderToCanvas(
  source: CanvasImageSource,
  width: number,
  height: number,
  fillWhiteBackground: boolean,
): HTMLCanvasElement {
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const ctx = canvas.getContext('2d')
  if (!ctx) {
    throw new Error('当前浏览器不支持画布重编码，无法处理截图')
  }
  if (fillWhiteBackground) {
    ctx.fillStyle = '#ffffff'
    ctx.fillRect(0, 0, width, height)
  }
  ctx.imageSmoothingEnabled = true
  ctx.imageSmoothingQuality = 'high'
  ctx.drawImage(source, 0, 0, width, height)
  return canvas
}

function canvasToBlob(
  canvas: HTMLCanvasElement,
  mimeType: FeedbackImageMimeType,
  quality?: number,
): Promise<Blob | null> {
  return new Promise((resolve) => {
    canvas.toBlob((blob) => resolve(blob), mimeType, quality)
  })
}

function blobToBase64(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => {
      if (typeof reader.result !== 'string') {
        reject(new Error('读取压缩后的截图失败'))
        return
      }
      resolve(stripBase64DataUrlPrefix(reader.result))
    }
    reader.onerror = () => reject(new Error('读取压缩后的截图失败'))
    reader.readAsDataURL(blob)
  })
}

function createImageId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `fb-img-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`
}

async function encodeWithinLimit(
  decoded: DecodedImageSource,
  preferredMime: FeedbackImageMimeType,
): Promise<{
  blob: Blob
  mimeType: FeedbackImageMimeType
  width: number
  height: number
}> {
  const target = computeScaledDimensions(
    decoded.width,
    decoded.height,
    FEEDBACK_LIMITS.maxImageDimension,
  )

  const transparentCanvas =
    preferredMime === 'image/png' || preferredMime === 'image/webp'
      ? renderToCanvas(decoded.source, target.width, target.height, false)
      : null

  // 1. Preserve crisp PNG screenshots when already within 800 KB
  if (preferredMime === 'image/png' && transparentCanvas) {
    const pngBlob = await canvasToBlob(transparentCanvas, 'image/png')
    if (pngBlob && pngBlob.size > 0 && pngBlob.size <= FEEDBACK_LIMITS.maxImageBytes) {
      return { blob: pngBlob, mimeType: 'image/png', width: target.width, height: target.height }
    }
  }

  // 2. Try WebP quality ladder for PNG/WebP sources (verifying browser supports WebP encoding)
  if ((preferredMime === 'image/png' || preferredMime === 'image/webp') && transparentCanvas) {
    for (const quality of WEBP_QUALITY_STEPS) {
      const webpBlob = await canvasToBlob(transparentCanvas, 'image/webp', quality)
      if (!webpBlob || webpBlob.type !== 'image/webp') {
        break
      }
      if (webpBlob.size > 0 && webpBlob.size <= FEEDBACK_LIMITS.maxImageBytes) {
        return {
          blob: webpBlob,
          mimeType: 'image/webp',
          width: target.width,
          height: target.height,
        }
      }
    }
  }

  // 3. Encode as JPEG with white background and stepped quality reduction
  const jpegCanvas = renderToCanvas(decoded.source, target.width, target.height, true)
  for (const quality of JPEG_QUALITY_STEPS) {
    const jpegBlob = await canvasToBlob(jpegCanvas, 'image/jpeg', quality)
    if (jpegBlob && jpegBlob.size > 0 && jpegBlob.size <= FEEDBACK_LIMITS.maxImageBytes) {
      return { blob: jpegBlob, mimeType: 'image/jpeg', width: target.width, height: target.height }
    }
  }

  // 4. Final fallback for extremely noisy high-resolution images: step down to 1200px
  if (Math.max(target.width, target.height) > FALLBACK_DOWNSCALE_DIMENSION) {
    const compact = computeScaledDimensions(
      decoded.width,
      decoded.height,
      FALLBACK_DOWNSCALE_DIMENSION,
    )
    const compactCanvas = renderToCanvas(decoded.source, compact.width, compact.height, true)
    for (const quality of FALLBACK_JPEG_QUALITIES) {
      const jpegBlob = await canvasToBlob(compactCanvas, 'image/jpeg', quality)
      if (jpegBlob && jpegBlob.size > 0 && jpegBlob.size <= FEEDBACK_LIMITS.maxImageBytes) {
        return {
          blob: jpegBlob,
          mimeType: 'image/jpeg',
          width: compact.width,
          height: compact.height,
        }
      }
    }
  }

  throw new Error('图片压缩后仍超过 800 KB，请裁剪后再试')
}

export async function prepareFeedbackImageFile(
  file: File,
  fallbackIndex = 1,
): Promise<PreparedFeedbackImage> {
  if (!file) {
    throw new Error('未检测到有效的图片文件')
  }
  const rawType = (file.type || '').trim().toLowerCase()
  const inferredMime = inferSupportedImageMimeType(file)
  if (!inferredMime) {
    if (rawType.startsWith('image/')) {
      throw new Error('暂仅支持 PNG、JPG、WebP 格式的图片')
    }
    throw new Error('所选文件不是图片，请上传 PNG、JPG 或 WebP 格式截图')
  }
  if (file.size <= 0) {
    throw new Error('所选图片文件为空，请重新选择')
  }

  const decoded = await decodeImageSource(file)
  try {
    if (decoded.width <= 0 || decoded.height <= 0) {
      throw new Error('无法读取图片尺寸，请确认文件未损坏')
    }
    const encoded = await encodeWithinLimit(decoded, inferredMime)
    const dataBase64 = await blobToBase64(encoded.blob)
    const decodedBytes = estimateBase64DecodedBytes(dataBase64)
    if (decodedBytes <= 0 || decodedBytes > FEEDBACK_LIMITS.maxImageBytes) {
      throw new Error('图片压缩后仍超过 800 KB，请裁剪后再试')
    }

    const previewUrl = URL.createObjectURL(encoded.blob)
    return {
      id: createImageId(),
      name: normalizeFeedbackImageFilename(file.name, encoded.mimeType, fallbackIndex),
      mimeType: encoded.mimeType,
      dataBase64,
      sizeBytes: encoded.blob.size,
      width: encoded.width,
      height: encoded.height,
      previewUrl,
    }
  } finally {
    decoded.dispose()
  }
}

export async function prepareFeedbackImageBatch(
  files: ReadonlyArray<File>,
  existingCount: number,
): Promise<PrepareFeedbackImageBatchResult> {
  if (files.length === 0) {
    return { added: [], errors: [] }
  }

  const remainingSlots = FEEDBACK_LIMITS.maxImages - existingCount
  if (remainingSlots <= 0) {
    return {
      added: [],
      errors: [`最多只能附带 ${FEEDBACK_LIMITS.maxImages} 张截图，请先移除已有截图后再添加`],
    }
  }

  const added: PreparedFeedbackImage[] = []
  const errors: string[] = []
  let overflowCount = 0

  for (let i = 0; i < files.length; i += 1) {
    const file = files[i]
    if (added.length >= remainingSlots) {
      overflowCount += 1
      continue
    }
    try {
      const prepared = await prepareFeedbackImageFile(file, existingCount + added.length + 1)
      added.push(prepared)
    } catch (err) {
      const label = file.name ? `「${file.name}」` : '所选文件'
      const reason = err instanceof Error ? err.message : '图片处理失败'
      errors.push(`${label}：${reason}`)
    }
  }

  if (overflowCount > 0) {
    errors.push(
      `最多支持附带 ${FEEDBACK_LIMITS.maxImages} 张截图，已忽略超出的 ${overflowCount} 张`,
    )
  }

  return { added, errors }
}
