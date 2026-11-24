package app.particlebook.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.particlebook.android.reader.ReaderAnnotation
import app.particlebook.android.reader.ReaderAnnotationType
import app.particlebook.android.reader.ReaderLocator
import app.particlebook.android.reader.epub.EpubSelection
import app.particlebook.android.reader.epub.layout.EpubChapterLayout
import app.particlebook.android.reader.epub.render.EpubHighlight
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

internal fun annotationTypeLabel(type: ReaderAnnotationType): String = when (type) {
    ReaderAnnotationType.HIGHLIGHT -> "高亮"
    ReaderAnnotationType.NOTE -> "笔记"
    ReaderAnnotationType.BOOKMARK -> "书签"
    ReaderAnnotationType.PDF -> "PDF 标注"
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
                        .background(composeArgb(color.argb)),
                )
            }
        }
        TextButton(onClick = onNote) { Text("笔记") }
        TextButton(onClick = onCopy) { Text("复制") }
    }
}

/** 标注侧栏；store 为空时也显示入口和空态，不崩溃。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EpubAnnotationSheet(
    annotations: List<ReaderAnnotation>,
    loading: Boolean,
    onDismiss: () -> Unit,
    onJump: (ReaderAnnotation) -> Unit,
    onEditNote: (ReaderAnnotation) -> Unit,
    onDelete: (ReaderAnnotation) -> Unit,
    /** 非空且确实有标注时才显示「导出」；null 表示这个入口不需要导出。 */
    onExport: (() -> Unit)? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        contentWindowInsets = { WindowInsets(0) },
        // M3 浮层：extraLarge 顶部圆角 + surfaceContainerLow 底，和阅读器其它浮层同档。
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        ImmersiveDialogWindow()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .heightIn(max = 560.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.lg, end = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "标注（" + annotations.size + "）",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = Spacing.sm),
                )
                if (onExport != null && annotations.isNotEmpty()) {
                    TextButton(
                        onClick = onExport,
                        modifier = Modifier.heightIn(min = Spacing.minTouchTarget),
                    ) { Text("导出") }
                }
            }
            when {
                loading -> {
                    Text(
                        text = "正在读取标注…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Spacing.lg),
                    )
                }
                annotations.isEmpty() -> {
                    Text(
                        text = "暂无标注。选中正文后点底栏颜色或「笔记」即可保存。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Spacing.lg),
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                    ) {
                        items(annotations) { annotation ->
                            AnnotationRow(
                                annotation = annotation,
                                onJump = { onJump(annotation) },
                                onEditNote = { onEditNote(annotation) },
                                onDelete = { onDelete(annotation) },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.padding(bottom = Spacing.lg))
        }
    }
}

@Composable
private fun AnnotationRow(
    annotation: ReaderAnnotation,
    onJump: () -> Unit,
    onEditNote: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onJump)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 颜色圆点只是标注类型的视觉索引（真实颜色见上方内容色说明），尺寸走 token。
            Box(
                modifier = Modifier
                    .size(Spacing.xs)
                    .clip(CircleShape)
                    .background(composeArgb(EpubAnnotationPalette.argbFor(annotation.color))),
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = annotationTypeLabel(annotation.type),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = formatAnnotationTime(annotation.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (annotation.locatorError != null) {
            Text(
                text = "位置不可用",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (annotation.quote.isNotBlank()) {
            Text(
                text = "“" + annotation.quote + "”",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (annotation.note.isNotBlank()) {
            Text(
                text = "笔记：" + annotation.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row {
            if (annotation.type == ReaderAnnotationType.NOTE) {
                TextButton(onClick = onEditNote) { Text("编辑笔记") }
            }
            TextButton(onClick = onDelete) { Text("删除") }
        }
    }
}

/** 笔记新建/编辑对话框；空白笔记不允许保存。 */
@Composable
internal fun EpubNoteEditorDialog(
    title: String,
    initialText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(initialText) { mutableStateOf(initialText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 8,
                placeholder = { Text("写点笔记…") },
            )
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onConfirm(text.trim()) },
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 删除标注的二次确认。 */
@Composable
internal fun EpubDeleteAnnotationDialog(
    annotation: ReaderAnnotation,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除这条标注？") },
        text = {
            Text(
                "“" + annotation.quote.ifBlank { annotationTypeLabel(annotation.type) } +
                    "”删除后不可恢复。",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("删除") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun formatAnnotationTime(millis: Long): String {
    if (millis <= 0L) return ""
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
}

private fun composeArgb(argb: Int): Color = Color(argb.toLong() and 0xFFFFFFFFL)