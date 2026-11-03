package app.particlebook.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Z-Library 搜索结果行（P1.5 §7.7：搜索结果 / 书单详情 / 分类列表共用一套行样式）。
 *
 * 只接收展示字段，不依赖 zlib 模型：搜索页传 ZBook 字段，发现面传 UI 模型字段。
 * 44×60dp 主色占位块（书名首字）+ 标题 2 行 + 元信息 1 行，整行 ≥72dp。
 */
@Composable
internal fun ZlibResultRow(
    title: String,
    author: String,
    language: String,
    extension: String,
    fileSize: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 44.dp, height = 60.dp)
                .clip(RoundedCornerShape(Spacing.xs))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = title.firstOrNull()?.toString() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.size(Spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = bookMetaLine(author, language, extension, fileSize),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 作者 · 语言 · 格式 · 大小（缺省字段跳过；作者为空显示「未知作者」）。 */
internal fun bookMetaLine(
    author: String,
    language: String,
    extension: String,
    fileSize: String,
): String {
    val parts = ArrayList<String>(4)
    parts += author.takeIf { it.isNotBlank() } ?: "未知作者"
    if (language.isNotBlank()) parts += language
    if (extension.isNotBlank()) parts += extension.uppercase()
    if (fileSize.isNotBlank()) parts += fileSize
    return parts.joinToString(" · ")
}

/** 从结果 href（/book/<hash>/<slug>.html 或绝对 URL）解析详情页参数。 */
internal fun parseBookRef(href: String): Pair<String, String?>? {
    val segments = runCatching {
        android.net.Uri.parse(href.trim()).pathSegments
    }.getOrNull() ?: return null
    val bookIndex = segments.indexOf("book")
    if (bookIndex < 0 || bookIndex + 1 >= segments.size) return null
    val hash = segments[bookIndex + 1].takeIf { it.isNotBlank() } ?: return null
    val slug = segments.getOrNull(bookIndex + 2)
        ?.removeSuffix(".html")
        ?.takeIf { it.isNotBlank() }
    return hash to slug
}
