package app.particlebook.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle

/**
 * 图片封面：bitmap 就绪前只显示单色占位 + 书名首字，不闪 spinner、不闪空白。
 *
 * 详情页用默认样式（primaryContainer / titleLarge）；搜索结果行通过可选参数换成
 * primary 12% 底 + titleMedium，保持列表行更小号的占位字。加载失败（bitmap=null）
 * 同样回退首字块，不需要额外错误态。
 *
 * 无障碍：封面是否朗读由 [contentDescription] 决定——相邻已有书名文本时传 null，
 * 整块按装饰处理（TalkBack 跳过）；封面单独承载书名信息时传
 * [zlibCoverContentDescription] 的返回值。
 */
@Composable
fun ZlibCoverImage(
    bitmap: Bitmap?,
    title: String,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.small,
    contentDescription: String? = null,
    placeholderColor: Color = MaterialTheme.colorScheme.primaryContainer,
    placeholderContentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    placeholderTextStyle: TextStyle = MaterialTheme.typography.titleLarge,
) {
    val decorative = contentDescription == null
    Surface(
        modifier = if (decorative) modifier.clearAndSetSemantics {} else modifier,
        shape = shape,
        color = placeholderColor,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = title.firstOrNull()?.toString() ?: "?",
                    style = placeholderTextStyle,
                    color = placeholderContentColor,
                )
            }
        }
    }
}

/** 封面单独承载书名时的朗读文本；相邻已有书名文本的调用方直接传 null 走装饰语义。 */
internal fun zlibCoverContentDescription(title: String): String =
    if (title.isBlank()) "封面" else "《" + title + "》封面"
