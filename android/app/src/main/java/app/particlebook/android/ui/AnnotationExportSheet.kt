package app.particlebook.android.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.particlebook.android.reader.export.AnnotationExportCsv
import app.particlebook.android.reader.export.AnnotationExportDocument
import app.particlebook.android.reader.export.AnnotationExportEntry
import app.particlebook.android.reader.export.AnnotationExportKind
import app.particlebook.android.reader.export.AnnotationExportOptions
import app.particlebook.android.reader.export.AnnotationExportResult
import app.particlebook.android.reader.export.AnnotationExportRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 导出格式：两者内容一致，只是排版不同（Markdown 给人读，CSV 给表格工具）。 */
private enum class ExportFormat { MARKDOWN, CSV }

/** 弹层里「将导出几条」与各类计数。 */
private class ExportCounts(val bookmarks: Int, val highlights: Int, val notes: Int) {
    val total: Int get() = bookmarks + highlights + notes
}

private fun countsOf(document: AnnotationExportDocument): ExportCounts {
    var bookmarks = 0
    var highlights = 0
    var notes = 0
    for (section in document.sections) {
        for (entry in section.entries) {
            when (entry.kind) {
                AnnotationExportKind.BOOKMARK -> bookmarks++
                AnnotationExportKind.HIGHLIGHT -> highlights++
                AnnotationExportKind.NOTE -> notes++
            }
        }
    }
    return ExportCounts(bookmarks, highlights, notes)
}

/** 过滤掉被关掉的类型后还剩几条：UI 的「将导出」与空态都靠它，不重复数一遍 document。 */
private fun exportedCountOf(document: AnnotationExportDocument, options: AnnotationExportOptions): Int {
    var count = 0
    for (section in document.sections) {
        for (entry in section.entries) {
            if (isIncluded(entry, options)) count++
        }
    }
    return count
}

private fun isIncluded(entry: AnnotationExportEntry, options: AnnotationExportOptions): Boolean = when (entry.kind) {
    AnnotationExportKind.BOOKMARK -> options.includeBookmarks
    AnnotationExportKind.HIGHLIGHT -> options.includeHighlights
    AnnotationExportKind.NOTE -> options.includeNotes
}

/**
 * 标注导出弹层：选要包含的类型，再选「另存为文件」或「分享文本」。
 *
 * 内容渲染在 [AnnotationExportRenderer]（纯 JVM、可单测），这里只做三件事：
 * 收集选项、把结果交给 SAF 落盘、把同一份文本交给系统分享。落盘失败不改任何状态，
 * 只回报一句提示——导出是只读操作，没有需要回滚的写入。
 *
 * 类型开关默认跟随「这本书到底有哪些标注」：没有书签时那个 chip 直接禁用，
 * 免得用户勾了一个必然导出 0 条的选项再怀疑功能坏了。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AnnotationExportSheet(
    document: AnnotationExportDocument,
    onDismiss: () -> Unit,
    onNotify: (String) -> Unit,
) {
    val counts = remember(document) { countsOf(document) }
    var includeBookmarks by remember(document) { mutableStateOf(counts.bookmarks > 0) }
    var includeHighlights by remember(document) { mutableStateOf(counts.highlights > 0) }
    var includeNotes by remember(document) { mutableStateOf(counts.notes > 0) }

    val options = AnnotationExportOptions(
        includeBookmarks = includeBookmarks,
        includeHighlights = includeHighlights,
        includeNotes = includeNotes,
    )
    var format by remember(document) { mutableStateOf(ExportFormat.MARKDOWN) }
    val result = remember(document, options, format) {
        when (format) {
            ExportFormat.MARKDOWN -> AnnotationExportRenderer.renderMarkdown(document, options)
            ExportFormat.CSV -> AnnotationExportCsv.renderCsv(document, options)
        }
    }
    val exportedCount = remember(document, options) { exportedCountOf(document, options) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 选中的目标 URI 由 SAF 回调带回，payload 先存下来：回调参数只有 uri，render 结果不能塞进去。
    var pending by remember { mutableStateOf<AnnotationExportResult?>(null) }
    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val payload = pending
        pending = null
        if (uri == null || payload == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = payload.content.toByteArray(Charsets.UTF_8)
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    val stream = context.contentResolver.openOutputStream(uri)
                        ?: error("无法打开所选位置")
                    stream.use { it.write(bytes) }
                }.isSuccess
            }
            onNotify(if (written) "已导出 " + payload.entryCount + " 条标注" else "导出失败：无法写入所选位置")
            if (written) onDismiss()
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets(0) },
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        ImmersiveDialogWindow()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .heightIn(max = 560.dp)
                .padding(horizontal = Spacing.lg),
        ) {
            Text(
                text = "导出标注",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(Spacing.xxs))
            Text(
                // 书名可能很长：一行截断，别把弹层顶成两行再挤出按钮。
                text = "《" + document.bookTitle + "》 · 共 " + counts.total + " 条",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Spacing.md))
            if (counts.total == 0) {
                // 一条都没有时不摆类型/格式 chip：它们全是禁用态，还会写一句「至少选一种这本书里有的类型」，
                // 读起来像用户选错了，其实这本书压根没标过。
                Text(
                    text = "这本书还没有标注。阅读时选中文字，用工具条的「高亮」或「笔记」保存，再回来导出。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.md))
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = Spacing.minTouchTarget),
                ) { Text("知道了") }
            } else {
                Text(
                    text = "包含",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.xs))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    ExportKindChip(
                        label = "书签 " + counts.bookmarks,
                        available = counts.bookmarks > 0,
                        selected = includeBookmarks,
                        onToggle = { includeBookmarks = it },
                    )
                    ExportKindChip(
                        label = "高亮 " + counts.highlights,
                        available = counts.highlights > 0,
                        selected = includeHighlights,
                        onToggle = { includeHighlights = it },
                    )
                    ExportKindChip(
                        label = "笔记 " + counts.notes,
                        available = counts.notes > 0,
                        selected = includeNotes,
                        onToggle = { includeNotes = it },
                    )
                }
                Spacer(Modifier.height(Spacing.md))
                Text(
                    text = "格式",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    ExportFormatChip(
                        label = "Markdown（.md）",
                        selected = format == ExportFormat.MARKDOWN,
                        onSelect = { format = ExportFormat.MARKDOWN },
                    )
                    ExportFormatChip(
                        label = "CSV（.csv）",
                        selected = format == ExportFormat.CSV,
                        onSelect = { format = ExportFormat.CSV },
                    )
                }
                Spacer(Modifier.height(Spacing.md))
                Text(
                    text = if (exportedCount > 0) {
                        "将导出 " + exportedCount + " 条，" + if (format == ExportFormat.CSV) "CSV（.csv）" else "Markdown（.md）"
                    } else {
                        "没有勾选任何类型：至少选一种这本书里有的类型"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.md))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = {
                            pending = result
                            createDocument.launch(result.fileName)
                        },
                        enabled = exportedCount > 0,
                        modifier = Modifier.weight(1f),
                    ) { Text("另存为文件") }
                    TextButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                // 固定不带 charset：分享面板按 MIME 挑应用，charset 参数会让部分应用认不出来。
                                type = if (format == ExportFormat.CSV) "text/csv" else "text/markdown"
                                putExtra(Intent.EXTRA_SUBJECT, document.bookTitle + " — 标注导出")
                                putExtra(Intent.EXTRA_TEXT, result.content)
                            }
                            context.startActivity(Intent.createChooser(intent, "分享标注"))
                            onDismiss()
                        },
                        enabled = exportedCount > 0,
                        modifier = Modifier.heightIn(min = Spacing.minTouchTarget),
                    ) { Text("分享文本") }
                }
            }
            Spacer(Modifier.height(Spacing.lg))
        }
    }
}

@Composable
private fun ExportFormatChip(label: String, selected: Boolean, onSelect: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onSelect,
        label = { Text(text = label, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
        modifier = Modifier.heightIn(min = Spacing.xl),
    )
}

@Composable
private fun ExportKindChip(
    label: String,
    available: Boolean,
    selected: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = { onToggle(!selected) },
        enabled = available,
        label = { Text(text = label, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
        modifier = Modifier.heightIn(min = Spacing.xl),
    )
}
