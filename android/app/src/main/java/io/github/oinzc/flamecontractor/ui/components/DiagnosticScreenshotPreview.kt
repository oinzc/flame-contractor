package io.github.oinzc.flamecontractor.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.ScreenshotMonitor
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.oinzc.flamecontractor.core.storage.DiagnosticScreenshotStore
import io.github.oinzc.flamecontractor.core.storage.TemplateImageStoreUtils
import io.github.oinzc.flamecontractor.ui.theme.FlameThemeTokens
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface ScreenshotLoadState {
    data object Loading : ScreenshotLoadState
    data class Loaded(val bitmap: ImageBitmap) : ScreenshotLoadState
    data object Missing : ScreenshotLoadState
}

/**
 * 离屏执行现场诊断截图缩略图卡片与全屏双指缩放/拖拽灯箱：
 * - 在 `Dispatchers.IO` 异步采样解码 `File`，不阻塞主线程；
 * - 截图缺失或已过 7 天自动清理时优雅静默隐藏（或按需展示极简提示）。
 */
@Composable
fun DiagnosticScreenshotCard(
    file: File?,
    title: String = "现场诊断截图",
    modifier: Modifier = Modifier,
    refreshKey: Long = 0L,
    showMissingHint: Boolean = false,
    missingHintText: String = "暂无可用现场截图（可能未触发渲染或已超过 7 天自动清理）",
) {
    val palette = FlameThemeTokens.palette
    val filePath = file?.absolutePath
    var loadState by remember(filePath, refreshKey) {
        mutableStateOf<ScreenshotLoadState>(
            if (file == null) ScreenshotLoadState.Missing else ScreenshotLoadState.Loading,
        )
    }
    var showLightbox by remember(filePath, refreshKey) { mutableStateOf(false) }

    LaunchedEffect(filePath, refreshKey) {
        if (file == null) {
            loadState = ScreenshotLoadState.Missing
            return@LaunchedEffect
        }
        loadState = ScreenshotLoadState.Loading
        val decoded = withContext(Dispatchers.IO) {
            decodeSampledScreenshot(
                file = file,
                maxEdgePx = DiagnosticScreenshotStore.TARGET_WIDTH_PX,
            )
        }
        loadState = if (decoded != null) {
            ScreenshotLoadState.Loaded(decoded)
        } else {
            ScreenshotLoadState.Missing
        }
    }

    when (val state = loadState) {
        ScreenshotLoadState.Missing -> {
            if (!showMissingHint) return
            Surface(
                modifier = modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = palette.surfaceSubtle,
                border = BorderStroke(1.dp, palette.hairline),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ImageNotSupported,
                        contentDescription = null,
                        tint = palette.inkTertiary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = missingHintText,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkTertiary,
                    )
                }
            }
        }

        ScreenshotLoadState.Loading -> {
            Surface(
                modifier = modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = palette.surfaceSubtle,
                border = BorderStroke(1.dp, palette.hairline),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(15.dp),
                        strokeWidth = 2.dp,
                        color = palette.inkSecondary,
                    )
                    Text(
                        text = "正在加载$title…",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkSecondary,
                    )
                }
            }
        }

        is ScreenshotLoadState.Loaded -> {
            Surface(
                modifier = modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { showLightbox = true },
                shape = RoundedCornerShape(14.dp),
                color = palette.surfaceSubtle,
                border = BorderStroke(1.dp, palette.hairline),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ScreenshotMonitor,
                            contentDescription = null,
                            tint = palette.inkSecondary,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ZoomIn,
                                contentDescription = null,
                                tint = palette.inkTertiary,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = "点击放大核对现场画面",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.inkTertiary,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 10f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(palette.surface)
                            .border(1.dp, palette.hairline, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            bitmap = state.bitmap,
                            contentDescription = title,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            if (showLightbox) {
                DiagnosticScreenshotLightboxDialog(
                    bitmap = state.bitmap,
                    title = title,
                    onDismiss = { showLightbox = false },
                )
            }
        }
    }
}

@Composable
private fun DiagnosticScreenshotLightboxDialog(
    bitmap: ImageBitmap,
    title: String,
    onDismiss: () -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { viewportSize = it }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val nextScale = (scale * zoom).coerceIn(1f, 5f)
                            if (nextScale <= 1.01f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                val maxOffsetX = (viewportSize.width * (nextScale - 1f)) / 2f
                                val maxOffsetY = (viewportSize.height * (nextScale - 1f)) / 2f
                                val nextX = (offset.x + pan.x).coerceIn(-maxOffsetX, maxOffsetX)
                                val nextY = (offset.y + pan.y).coerceIn(-maxOffsetY, maxOffsetY)
                                scale = nextScale
                                offset = Offset(nextX, nextY)
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = bitmap,
                    contentDescription = title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp, vertical = 64.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.58f))
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "双指缩放与拖拽核对画面细节 · 仅存本机沙盒 7 天自动清理",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (scale > 1.02f) {
                    Surface(
                        onClick = {
                            scale = 1f
                            offset = Offset.Zero
                        },
                        shape = RoundedCornerShape(999.dp),
                        color = Color.White.copy(alpha = 0.16f),
                        contentColor = Color.White,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.RestartAlt,
                                contentDescription = "重置缩放",
                                modifier = Modifier.size(15.dp),
                            )
                            Text(
                                text = "${(scale * 100f).roundToInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }

                Surface(
                    onClick = onDismiss,
                    modifier = Modifier.size(36.dp),
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.18f),
                    contentColor = Color.White,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "关闭现场截图预览",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
                shape = RoundedCornerShape(999.dp),
                color = Color.White.copy(alpha = 0.14f),
                contentColor = Color.White,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (scale > 1.02f) "当前缩放 ${(scale * 100f).roundToInt()}% · 可拖动查看" else "双指捏合可放大核对聊天记录与按钮状态",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.88f),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
            }
        }
    }
}

private fun decodeSampledScreenshot(
    file: File,
    maxEdgePx: Int,
): ImageBitmap? = try {
    if (!file.isFile || file.length() <= 0L) {
        null
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            val options = BitmapFactory.Options().apply {
                inSampleSize = TemplateImageStoreUtils.computeInSampleSize(
                    widthPx = bounds.outWidth,
                    heightPx = bounds.outHeight,
                    maxEdgePx = maxEdgePx,
                )
            }
            BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
        }
    }
} catch (_: Throwable) {
    null
}
