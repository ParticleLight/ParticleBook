package app.particlebook.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
 * 标注侧栏的类型筛选档位；[label] 是 chip 上的中文名，条数由 UI 另外拼接
 * （同一条筛选在不同列表里数量不同，不放进枚举）。
 */
internal enum class AnnotationFilter(val label: String) {
    ALL("全部"),
    BOOKMARK("书签"),
    HIGHLIGHT("高亮"),
    NOTE("笔记"),
}

/**
 * 类型 -> 筛选档位。PDF 归到「高亮」：PDF 的几何标注在 store 里本来就落成 highlight
 * （SQLite kind 只有 bookmark/highlight/note，见 [ReaderAnnotationType]），
 * 独立 PDF 值只出现在迁移/旧数据里；给它单开一个 chip 只会把同一批几何标注拆成两半。
 */
private fun filterOf(type: ReaderAnnotationType): AnnotationFilter = when (type) {
    ReaderAnnotationType.BOOKMARK -> AnnotationFilter.BOOKMARK
    ReaderAnnotationType.HIGHLIGHT, ReaderAnnotationType.PDF -> AnnotationFilter.HIGHLIGHT
    ReaderAnnotationType.NOTE -> AnnotationFilter.NOTE
}

/**
 * 按 [filter] 过滤标注，保持入参顺序。
 *
 * - [AnnotationFilter.ALL] 原样返回同一个列表（同一实例、同一顺序），不复制也不重排；
 * - 其余档位按 [filterOf] 分桶，PDF 与 HIGHLIGHT 一起出现在「高亮」里
 *   （PDF 的几何标注在 store 里本来就落成 highlight，两者是同一批数据的两种编码）。
 *
 * 纯函数，无 Compose/Android 依赖，JVM 单测直接覆盖。
 */
internal fun filterAnnotations(
    annotations: List<ReaderAnnotation>,
    filter: AnnotationFilter,
): List<ReaderAnnotation> = when (filter) {
    AnnotationFilter.ALL -> annotations
    else -> annotations.filter { filterOf(it.type) == filter }
}

/**
 * 把可能已经失效的筛选档位拉回 [AnnotationFilter.ALL]。
 *
 * 场景：筛到「笔记」后把该类最后一条删掉 —— 计数归零让「笔记」chip 消失，但 `filter` 仍停在
 * 已消失的档位。此后列表永远为空，屏幕上又没有任何 chip 能点回去，用户只能关掉侧栏重开
 * （而且重开还会带着同一个错档位，如果它是被 remember 保存的话）。
 *
 * 纯函数，无 Compose 依赖，JVM 单测直接覆盖；UI 侧只在结果与当前值不同时才写状态，
 * 避免每次重组都赋值造成多余的失效。
 */
internal fun resolveFilter(
    annotations: List<ReaderAnnotation>,
    filter: AnnotationFilter,
): AnnotationFilter {
    if (filter == AnnotationFilter.ALL) return AnnotationFilter.ALL
    return if (filterAnnotations(annotations, filter).isEmpty()) AnnotationFilter.ALL else filter
}

/**
 * 当前列表里「可筛类型」的条数，按 ALL 之外的固定顺序（书签 / 高亮 / 笔记）返回，
 * 只保留计数 > 0 的档位 —— 0 条的类型不出 chip，1 种类型时调用方据此隐藏整行筛选。
 *
 * 注意 PDF 与 HIGHLIGHT 在这里合并成同一个「高亮」档位，所以两者同时出现也只算 1 种可筛类型。
 */
internal fun annotationFilterCounts(
    annotations: List<ReaderAnnotation>,
): List<Pair<AnnotationFilter, Int>> {
    val counts = annotations.groupingBy { filterOf(it.type) }.eachCount()
    return buildList {
        for (filter in listOf(AnnotationFilter.BOOKMARK, AnnotationFilter.HIGHLIGHT, AnnotationFilter.NOTE)) {
            val count = counts[filter] ?: 0
            if (count > 0) add(filter to count)
        }
    }
}

/**
 * 标注侧栏：EPUB 与 PDF 共用的列表。
 *
 * 两侧的差异只有空态提示（选 EPUB 正文 vs 在 PDF 页面上长按拖动），
 * 因此由调用方传 [emptyHint]，其余（标题计数、导出入口、跳转/编辑/删除）完全一致。
 *
 * @param onExport 非空且确实有标注时才显示「导出」；null 表示这个入口不需要导出。
 * @param loadFailed 上次读取失败。此时空表不能讲成「暂无标注」——那是在说这本书没有标注，
 *   而事实是没读到；两者用户该做的事完全不同（前者去标注，后者重试）。
 * @param onRetry 读取失败时的重试动作；null 表示这个入口没有重试能力（只显示失败提示）。
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
    loadFailed: Boolean = false,
    onRetry: (() -> Unit)? = null,
) {
    // 只影响列表显示；计数、导出入口仍然按全量 annotations，切筛选不会让导出消失。
    var filter by remember { mutableStateOf(AnnotationFilter.ALL) }
    // 筛到某一类后把该类最后一条删掉，chip 会随计数归零消失；此时 filter 仍停在已消失的档位，
    // 列表永远空、又没有任何 chip 可以点回去 —— 用户只能关掉侧栏重开。计数一变就把越界的筛选拉回全部。
    LaunchedEffect(annotations, filter) {
        val resolved = resolveFilter(annotations, filter)
        if (resolved != filter) filter = resolved
    }
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
                // 只有当前一条都没有时才让 loading 顶掉列表：改一条颜色也会重读全量，
                // 若每次都换成"正在读取标注…"，长列表里删一条就会整块闪一下并弹回顶部。
                loading && annotations.isEmpty() -> {
                    Text(
                        text = "正在读取标注…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Spacing.lg),
                    )
                }
                loadFailed && annotations.isEmpty() -> {
                    Column(modifier = Modifier.padding(Spacing.lg)) {
                        Text(
                            text = "读取标注失败",
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (onRetry != null) {
                            TextButton(
                                onClick = onRetry,
                                modifier = Modifier.heightIn(min = Spacing.minTouchTarget),
                            ) { Text("重试") }
                        }
                    }
                }
                annotations.isEmpty() -> {
                    Text(
                        text = emptyHint,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Spacing.lg),
                    )
                }
                else -> {
                    // 只有一种可筛类型时筛选没有信息量，整行不出现（避免「全部 N / 高亮 N」两个等价 chip）。
                    val typeCounts = remember(annotations) { annotationFilterCounts(annotations) }
                    if (typeCounts.size >= 2) {
                        AnnotationFilterRow(
                            options = listOf(AnnotationFilter.ALL to annotations.size) + typeCounts,
                            selected = filter,
                            onSelect = { filter = it },
                        )
                    }
                    val visible = filterAnnotations(annotations, filter)
                    if (visible.isEmpty()) {
                        // 不能复用 emptyHint：那句讲的是「怎么新建标注」，这里只是当前筛选没有命中。
                        Text(
                            text = "这个筛选下没有标注",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(Spacing.lg),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false),
                        ) {
                            items(visible) { annotation ->
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
            }
            Spacer(Modifier.padding(bottom = Spacing.lg))
        }
    }
}

/**
 * 顶部筛选行：「全部 N」+ 当前列表里出现过的类型。
 *
 * 用 [FlowRow] 而不是 [Row]：4 个带数字的 chip 在 320dp 宽屏上会溢出被裁掉，
 * 换行比横向滚动更容易发现（同 ui/FindScreen.kt:1261 的 chip 组写法）。
 * chip 高度走 [Spacing.xl] 锚点，和 m3-audit 的触摸目标规范一致。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnnotationFilterRow(
    options: List<Pair<AnnotationFilter, Int>>,
    selected: AnnotationFilter,
    onSelect: (AnnotationFilter) -> Unit,
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        options.forEach { (filter, count) ->
            FilterChip(
                selected = selected == filter,
                onClick = { onSelect(filter) },
                label = {
                    Text(
                        text = filter.label + " " + count,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                },
                modifier = Modifier.heightIn(min = Spacing.xl),
            )
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
            // 书签不画点：它没有颜色，回退成默认黄会让人以为这一段被高亮过（同 paintColorOf 的口径）。
            if (annotation.type == ReaderAnnotationType.BOOKMARK) {
                Spacer(Modifier.size(Spacing.xs))
            } else {
                Box(
                    modifier = Modifier
                        .size(Spacing.xs)
                        .background(annotationColor(annotation.color), CircleShape),
                )
            }
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

/**
 * 列表时间格式器按线程复用。
 *
 * `SimpleDateFormat` 不是线程安全的，构造一次也不便宜（要建 Calendar）。列表每次重组都
 * new 一个纯属浪费；ThreadLocal 既避免同步开销，又不会跨线程共享可变状态。
 */
private val AnnotationTimeFormat = ThreadLocal.withInitial {
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
}

private fun formatAnnotationTime(millis: Long): String {
    if (millis <= 0L) return ""
    val formatter = AnnotationTimeFormat.get() ?: return ""
    return formatter.format(Date(millis))
}
