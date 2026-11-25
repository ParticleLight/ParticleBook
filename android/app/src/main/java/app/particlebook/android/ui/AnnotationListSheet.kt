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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.particlebook.android.reader.ReaderAnnotation
import app.particlebook.android.reader.ReaderAnnotationType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把一个标注的持久化颜色转成 ARGB（0xAARRGGBB）。
 *
 * 两种来源必须同时吃得下：EPUB 存的是调色板 key（`yellow`/`green`/`blue`/`pink`），
 * PDF 选区直接存十六进制（`#FFF59D`）。未知/空/畸形值一律回退默认高亮色 ——
 * 颜色是内容色，不能因为读到一条旧数据就让整行或整块画不出来。
 *
 * 6 位十六进制按不透明补齐 0xFF，8 位原样返回 —— alpha 留给调用方决定：
 * 列表色点要实色，页面上落笔的色块则统一压到 [PdfAnnotationHighlights] 那档不透明度。
 *
 * 返回 Long 而不是 `Color`：这个换算要在 JVM 单元测试里跑，不该把 Compose 图形类型拖进去。
 */
internal fun annotationArgb(color: String?): Long {
    val raw = color?.trim().orEmpty()
    if (raw.startsWith("#")) {
        val body = raw.substring(1)
        // 只认 6 位 RGB 与 8 位 ARGB。字符校验是必要的：toLongOrNull(16) 会吞掉前导 + / -，
        // 而全角数字/字母只能靠逐字符白名单挡在门外（它内部只认 ASCII digit）。
        if (body.length == 6 || body.length == 8) {
            val digitsOnly = body.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }
            val parsed = if (digitsOnly) body.toLongOrNull(16) else null
            if (parsed != null) {
                return if (body.length == 6) 0xFF000000L or parsed else parsed
            }
        }
    }
    return EpubAnnotationPalette.argbFor(raw).toLong() and 0xFFFFFFFFL
}

/**
 * 标注颜色圆点/整块填充；内容色不随主题变。
 *
 * 直接沿用 [annotationArgb] 的 alpha：调色板 key 自带 0x55（EPUB 的高亮一直是这个浓度），
 * 十六进制则是不透明的。色点只是"哪一类颜色"的索引，不要求两者视觉等重；
 * 页面上真正落笔的色块由绘制层统一压到自己的不透明度。
 */
internal fun annotationColor(color: String?): Color = Color(annotationArgb(color))

/** 标注类型的中文标签；DB 里 kind 是冻结枚举，未知值不在这里兜底。 */
internal fun annotationTypeLabel(type: ReaderAnnotationType): String = when (type) {
    ReaderAnnotationType.HIGHLIGHT -> "高亮"
    ReaderAnnotationType.NOTE -> "笔记"
    ReaderAnnotationType.BOOKMARK -> "书签"
    ReaderAnnotationType.PDF -> "PDF 标注"
}

/**
 * 标注侧栏：EPUB 与 PDF 共用的列表。
 *
 * 两侧的差异只有空态提示（选 EPUB 正文 vs 在 PDF 页面上长按拖动），
 * 因此由调用方传 [emptyHint]，其余（标题计数、导出入口、跳转/编辑/删除）完全一致。
 *
 * @param onExport 非空且确实有标注时才显示「导出」；null 表示这个入口不需要导出。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AnnotationListSheet(
    annotations: List<ReaderAnnotation>,
    loading: Boolean,
    emptyHint: String,
    onDismiss: () -> Unit,
    onJump: (ReaderAnnotation) -> Unit,
    onEditNote: (ReaderAnnotation) -> Unit,
    onDelete: (ReaderAnnotation) -> Unit,
    onExport: (() -> Unit)? = null,
    /** 可选的位置副标题（例如 PDF 的「第 N 页」）：多条引文相近时靠它分辨。返回 null 表示不显示。 */
    subtitleOf: ((ReaderAnnotation) -> String?)? = null,
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
                        text = emptyHint,
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
                                subtitle = subtitleOf?.invoke(annotation),
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
    subtitle: String?,
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
                    .background(annotationColor(annotation.color), CircleShape),
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
        if (subtitle != null) {
            Text(
                text = subtitle,
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
internal fun AnnotationNoteEditorDialog(
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
internal fun AnnotationDeleteDialog(
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
