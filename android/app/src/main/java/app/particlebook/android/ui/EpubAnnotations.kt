package app.particlebook.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.particlebook.android.reader.ReaderAnnotation
import app.particlebook.android.reader.ReaderAnnotationType
import app.particlebook.android.reader.ReaderLocator
import app.particlebook.android.reader.epub.EpubSelection
import app.particlebook.android.reader.epub.layout.EpubChapterLayout
import app.particlebook.android.reader.epub.render.EpubHighlight

/**
 * 高亮/笔记颜色。key 持久化到 ReaderAnnotation.color；argb 由渲染层直接画。
 *
 * 这里是**内容色**而不是界面色：argb 会落进 EpubHighlight 交给排版层在非 Composable 的绘制阶段落笔，
 * 且必须跨主题/跨会话稳定（同一本书的高亮不能换个主题就变色）。因此它不能读 colorScheme，
 * 也不能换成 M3 语义角色；界面 chrome 的颜色一律走 colorScheme，二者不混用。
 */
internal data class EpubAnnotationColor(
    val key: String,
    val label: String,
    val argb: Int,
)

internal object EpubAnnotationPalette {
    val colors: List<EpubAnnotationColor> = listOf(
        EpubAnnotationColor("yellow", "黄色", 0x55FFD54F),
        EpubAnnotationColor("green", "绿色", 0x5566BB6A),
        EpubAnnotationColor("blue", "蓝色", 0x5564B5F6),
        EpubAnnotationColor("pink", "粉色", 0x55F48FB1),
    )

    val default: EpubAnnotationColor
        get() = colors.first()

    /** 笔记默认用蓝色，和高亮默认黄色区分。 */
    val note: EpubAnnotationColor
        get() = colors.first { it.key == "blue" }

    fun byKey(key: String?): EpubAnnotationColor =
        colors.firstOrNull { it.key == key } ?: default

    fun argbFor(key: String?): Int = byKey(key).argb
}

/**
 * 选区 -> 持久化标注。start 是块内起点，length=end-start；
 * 选区必须落在同一块（EpubPageView 的选区模型即如此）。
 */
internal fun annotationFromSelection(
    bookId: Long,
    type: ReaderAnnotationType,
    selection: EpubSelection,
    color: String?,
    note: String,
    now: Long,
): ReaderAnnotation? {
    val start = selection.start
    val end = selection.end
    if (start.chapterHref != end.chapterHref || start.blockIndex != end.blockIndex) return null
    val length = end.charOffset - start.charOffset
    if (length <= 0 || selection.text.isEmpty()) return null
    return ReaderAnnotation(
        bookId = bookId,
        type = type,
        locator = start.copy(length = length),
        quote = selection.text,
        note = note,
        color = color,
        createdAt = now,
        updatedAt = now,
    )
}

/** 编辑笔记时保留原 id/locator/quote/颜色，只改 note 与 updatedAt。 */
internal fun annotationWithNote(
    annotation: ReaderAnnotation,
    note: String,
    now: Long,
): ReaderAnnotation = annotation.copy(
    note = note,
    createdAt = if (annotation.createdAt > 0) annotation.createdAt else now,
    updatedAt = now,
)

/**
 * 当前页要绘制的标注高亮。只取 HIGHLIGHT/NOTE、同章、同页块、length>0 的记录；
 * 颜色按 key 映射，未知 key 回退黄色。
 */
internal fun annotationsOnPage(
    annotations: List<ReaderAnnotation>,
    layout: EpubChapterLayout,
    pageIndex: Int,
): List<EpubHighlight> {
    val page = layout.pages.getOrNull(pageIndex) ?: return emptyList()
    val blockIndices = page.slices.mapTo(HashSet()) { it.blockIndex }
    if (blockIndices.isEmpty()) return emptyList()
    val chapterPath = layout.chapterHref.substringBefore('#')
    return annotations.mapNotNull { annotation ->
        if (annotation.type != ReaderAnnotationType.HIGHLIGHT &&
            annotation.type != ReaderAnnotationType.NOTE
        ) {
            return@mapNotNull null
        }
        // locatorError 行只有占位 locator，不能拿来匹配页面/画高亮。
        if (annotation.locatorError != null) return@mapNotNull null
        val locator = annotation.locator as? ReaderLocator.Epub ?: return@mapNotNull null
        if (locator.chapterHref.substringBefore('#') != chapterPath) return@mapNotNull null
        if (locator.blockIndex !in blockIndices || locator.length <= 0) return@mapNotNull null
        val end = (locator.charOffset.toLong() + locator.length)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        if (end <= locator.charOffset) return@mapNotNull null
        EpubHighlight(
            blockIndex = locator.blockIndex,
            startChar = locator.charOffset,
            endChar = end,
            colorArgb = EpubAnnotationPalette.argbFor(annotation.color),
        )
    }
}

/**
 * 底栏选区工具条：4 色高亮 + 笔记 + 复制。始终在固定 64dp 底栏内，
 * 不因为出现选区而改变正文高度（否则页数会变、进度会错）。
 */
@Composable
internal fun EpubSelectionToolbar(
    selectedLength: Int,
    onHighlight: (String) -> Unit,
    onNote: () -> Unit,
    onCopy: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "已选 " + selectedLength + " 字",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(Spacing.sm))
        // 4 色色点在 64dp 底栏里：横向宽度受底栏限制（还要给「已选 n 字」和两个动作让位），
        // 所以色点 24dp（Spacing.lg）、命中区纵向撑满 48dp（Spacing.minTouchTarget）；
        // 色点本身用内容色（有意图的持久化色），命中区不留裸 hex。
        EpubAnnotationPalette.colors.forEach { color ->
            Box(
                modifier = Modifier
                    .size(width = Spacing.xl, height = Spacing.minTouchTarget)
                    .clickable(role = Role.Button) { onHighlight(color.key) }
                    .semantics { contentDescription = color.label + "高亮" },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(Spacing.lg)
                        .clip(CircleShape)
                        .background(annotationColor(color.key)),
                )
            }
        }
        TextButton(onClick = onNote) { Text("笔记") }
        TextButton(onClick = onCopy) { Text("复制") }
    }
}