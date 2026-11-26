package app.particlebook.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.particlebook.android.reader.text.TextImage
import app.particlebook.android.reader.text.TextImageBytes
import app.particlebook.android.reader.text.TextImageSampling
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 插图解码结果。
 *
 * 「还没解完」与「解不出来」不分开存：[aspectRatio] 在解码前就从 bounds 拿到了，
 * 两种情况都渲染同一个尺寸的占位块，界面不会在解完的一瞬间跳高度。
 */
internal data class TextImageLoadState(
    val bitmap: ImageBitmap? = null,
    val aspectRatio: Float = 1.6f,
    val failed: Boolean = false,
)

/**
 * 正文里的一张插图。
 *
 * 解码放在 [Dispatchers.IO]，并且先 `inJustDecodeBounds` 拿原始尺寸、再按
 * [TextImageSampling.sampleSizeFor] 降采样——直接把 4000×3000 的扫描原图塞进
 * BitmapFactory 是 48 MB 一次，LazyColumn 来回滚两下就 OOM。
 *
 * 尺寸先用 bounds 算出的宽高比占位，解码完成后原地替换，正文不会因为图片加载而跳动；
 * 读不出来时留一个同尺寸的占位块并显示 alt，避免「这里什么都没有」的静默。
 */
@Composable
internal fun TextImageBlock(
    image: TextImage,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    var state by remember(image.key) { mutableStateOf(TextImageLoadState()) }
    LaunchedEffect(image.key) {
        state = withContext(Dispatchers.IO) { decodeTextImage(image) }
    }

    val shape = MaterialTheme.shapes.medium
    val bitmap = state.bitmap
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = image.alt.takeIf { it.isNotBlank() },
            contentScale = ContentScale.FillWidth,
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .aspectRatio(state.aspectRatio)
                .then(
                    if (onClick == null) Modifier
                    else Modifier.clickable(role = Role.Image) { onClick() },
                ),
        )
        return
    }

    // 解码中 / 解码失败共用同一个占位块：尺寸由 bounds 决定，占位不会突然撑高或塌缩。
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .aspectRatio(state.aspectRatio)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state.failed) {
                Icon(
                    imageVector = Icons.Outlined.BrokenImage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = image.alt.takeIf { it.isNotBlank() } ?: "这张图片无法显示",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * 读字节 → 读尺寸 → 采样解码。只在这一个地方碰 BitmapFactory，失败一律降级成占位。
 *
 * [maxEdgePx] 是长边上限：正文里给 [TextImageSampling.MAX_EDGE_PX]（1600，够填满一栏），
 * 全屏查看时给更大值（屏幕最长的边），否则放大到 2 倍就能看出像素块。
 */
internal fun decodeTextImage(
    image: TextImage,
    maxEdgePx: Int = TextImageSampling.MAX_EDGE_PX,
): TextImageLoadState {
    // BitmapFactory 只读输入；显示借用已有内嵌数组，只有导出才复制原图快照。
    val embedded = image.bytes
    val bytes = if (embedded == null) {
        TextImageBytes.readFile(image.path)
    } else {
        embedded.takeIf { it.isNotEmpty() && it.size <= TextImage.MAX_BYTES }
    }
    if (bytes == null || bytes.isEmpty()) {
        return TextImageLoadState(failed = true)
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) }
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0) {
        return TextImageLoadState(failed = true)
    }
    val aspect = (width.toFloat() / height.toFloat()).coerceIn(0.2f, 5f)
    val options = BitmapFactory.Options().apply {
        inSampleSize = TextImageSampling.sampleSizeFor(width, height, maxEdgePx)
        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
    }
    val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }
        .getOrNull()
        ?: return TextImageLoadState(aspectRatio = aspect, failed = true)
    return TextImageLoadState(bitmap = bitmap.asImageBitmap(), aspectRatio = aspect)
}
