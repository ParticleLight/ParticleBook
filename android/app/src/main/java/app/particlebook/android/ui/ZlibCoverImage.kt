package app.particlebook.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * 图片封面：bitmap 就绪前只显示单色占位 + 书名首字，不闪 spinner、不闪空白。
 *
 * 详情页用默认样式（primaryContainer / titleLarge）；搜索结果行通过可选参数换成
 * primary 12% 底 + titleMedium，保持列表行更小号的占位字。加载失败（bitmap=null）
 * 同样回退首字块，不需要额外错误态。
 */
@Composable
fun ZlibCoverImage(
    bitmap: Bitmap?,
    title: String,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Radius.chip),
    placeholderColor: Color = MaterialTheme.colorScheme.primaryContainer,
    placeholderContentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    placeholderTextStyle: TextStyle = MaterialTheme.typography.titleLarge,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = placeholderColor,
    ) {
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "《" + title + "》封面",
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            androidx.compose.foundation.layout.Box(
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
