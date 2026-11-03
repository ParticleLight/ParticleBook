package app.particlebook.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Z-Library 搜索结果行（P1.5 §7.7：搜索结果 / 书单详情 / 分类列表共用一套行样式）。
 *
 * 只接收展示字段，不依赖 zlib 模型：搜索页传 ZBook 字段，发现面传 UI 模型字段。
 * 44×60dp 封面（缺图/加载失败回退主色占位块 + 书名首字）+ 标题 2 行 + 元信息 1 行
 * +（有评分/出版社时）副元信息 1 行，整行 ≥72dp。
 *
 * 封面走 ZlibCoverCache（内存 16MB + 磁盘 + 网络），与详情页同源缓存；
 * 加载是异步的，bitmap 就绪前显示首字占位，不闪 spinner、不闪空白。
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
    coverUrl: String? = null,
    coverKey: String = "",
    rating: String = "",
    publisher: String = "",
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ResultRowCover(
            cacheKey = coverKey,
            coverUrl = coverUrl,
            title = title,
        )
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
            val extra = bookSecondaryMetaLine(rating, publisher)
            if (extra.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = extra,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}


/** 结果行封面：44×60dp，复用 ZlibCoverCache；失败/无图回退首字块。 */
@Composable
private fun ResultRowCover(
    cacheKey: String,
    coverUrl: String?,
    title: String,
) {
    val context = LocalContext.current.applicationContext
    val targetWidthPx = with(LocalDensity.current) { 44.dp.roundToPx() }
    val key = cacheKey.ifBlank { coverUrl.orEmpty() }
    // 内存命中直接当 produceState 初值：列表滚回可见区时不再闪一帧首字占位，也避免重复请求。
    val bitmap by produceState<Bitmap?>(
        initialValue = ZlibCoverCache.peek(key),
        key,
        coverUrl,
    ) {
        if (value == null && key.isNotEmpty()) {
            value = ZlibCoverCache.load(context, key, coverUrl, targetWidthPx)
        }
    }
    ZlibCoverImage(
        bitmap = bitmap,
        title = title,
        modifier = Modifier.size(width = 44.dp, height = 60.dp),
        shape = RoundedCornerShape(Spacing.xs),
        placeholderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        placeholderContentColor = MaterialTheme.colorScheme.primary,
        placeholderTextStyle = MaterialTheme.typography.titleMedium,
    )
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

/**
 * 副元信息（A3）：★评分 · 出版社。缺省字段跳过；评分只接受正数（站点 0 / 空 = 未评分）。
 * 行宽由调用方 Text(maxLines = 1, ellipsis) 截断，评分放最前保证不被截掉。
 */
internal fun bookSecondaryMetaLine(rating: String, publisher: String): String {
    val parts = ArrayList<String>(2)
    rating.trim().toDoubleOrNull()?.takeIf { it > 0.0 }?.let {
        parts += "★" + String.format(Locale.US, "%.1f", it)
    }
    publisher.trim().takeIf { it.isNotEmpty() }?.let { parts += it }
    return parts.joinToString(" · ")
}

/**
 * 封面原始值 -> 可下载的绝对 URL（A2）。
 *
 * - 绝对 http(s) CDN：直通；
 * - 协议相对 //host/x：补 https:；
 * - 站内相对 /img/x：拼当前镜像 base；
 * - 空值 / cover-not-exists 占位图 / 其他脏值：null（UI 走首字占位）。
 */
internal fun resolveZlibCoverUrl(raw: String?, mirrorBase: String?): String? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return null
    val lower = value.lowercase()
    if (lower.contains("cover-not-exists")) return null
    return when {
        lower.startsWith("http://") || lower.startsWith("https://") -> value
        value.startsWith("//") -> "https:" + value
        value.startsWith("/") -> {
            val base = mirrorBase?.trim()?.trimEnd('/').orEmpty()
            val baseLower = base.lowercase()
            if (baseLower.startsWith("http://") || baseLower.startsWith("https://")) {
                base + value
            } else {
                null
            }
        }
        else -> null
    }
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
