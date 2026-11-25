package app.particlebook.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.particlebook.android.reader.AnnotationStore
import app.particlebook.android.reader.ReaderAnnotation
import app.particlebook.android.reader.ReaderAnnotationType
import app.particlebook.android.reader.pdf.PdfSelection
import app.particlebook.android.reader.pdf.PdfSelectionController

/**
 * PDF 选区的 Compose 状态 holder：命中/扩展/复制/高亮/笔记。
 *
 * 选区本身由 PdfSelectionController 计算，本类只负责 UI 状态和 Android 剪贴板/标注落库。
 * 所有动作都在主线程调用；controller 的文本层读取应已在 IO 线程预载。
 */
class PdfSelectionState(
    private val controller: PdfSelectionController,
    private val bookId: Long,
    private val annotationStore: AnnotationStore?,
    private val clipboard: ClipboardManager?,
) {
    var selection: PdfSelection? by mutableStateOf(null)
        private set

    var lastActionError: String? by mutableStateOf(null)
        private set

    /**
     * 成功落库的次数。宿主拿它当"标注变了"的信号：PDF 侧没有 EPUB 那种 adapter 通知通道，
     * 不重读的话页面上的既有高亮要等下次进阅读器才出现。
     */
    var savedVersion by mutableIntStateOf(0)
        private set

    val canCopy: Boolean
        get() = clipboard != null && selection?.hasText == true

    val canAnnotate: Boolean
        get() = annotationStore != null && selection?.hasText == true

    fun begin(page: Int, xNorm: Float, yNorm: Float) {
        lastActionError = null
        selection = runCatching { controller.begin(page, xNorm, yNorm) }.getOrNull()
    }

    fun extend(page: Int, xNorm: Float, yNorm: Float) {
        val active = selection ?: return
        selection = runCatching { controller.extend(page, xNorm, yNorm) }.getOrNull() ?: active
    }

    fun clear() {
        controller.clear()
        selection = null
    }

    /** 提示过一次就清掉：lastActionError 是状态而不是事件，不清会一直挂在那里。 */
    fun clearActionError() {
        lastActionError = null
    }

    fun copySelection(): Boolean {
        val active = selection?.takeIf { it.hasText } ?: return false
        val manager = clipboard ?: return false
        return runCatching {
            manager.setPrimaryClip(ClipData.newPlainText("PDF 摘录", active.text))
            clear()
            true
        }.onFailure { lastActionError = it.message ?: "复制失败" }.getOrDefault(false)
    }

    fun highlight(): ReaderAnnotation? =
        addAnnotation(type = ReaderAnnotationType.HIGHLIGHT, note = "", color = HIGHLIGHT_COLOR)

    fun addNote(note: String): ReaderAnnotation? {
        if (note.isBlank()) return null
        // 笔记用调色板的蓝色，和默认黄色高亮分开：侧栏色点、页面色块两边都跟着这个 key 走，
        // 不然 PDF 的笔记在列表里会和普通高亮长得一模一样（kind 都落成 highlight）。
        return addAnnotation(type = ReaderAnnotationType.NOTE, note = note.trim(), color = EpubAnnotationPalette.note.key)
    }

    private fun addAnnotation(
        type: ReaderAnnotationType,
        note: String,
        color: String?,
    ): ReaderAnnotation? {
        val active = selection?.takeIf { it.hasText } ?: return null
        val store = annotationStore ?: return null
        val annotation = ReaderAnnotation(
            bookId = bookId,
            type = type,
            locator = controller.toLocator(active.page, active.rects),
            quote = active.text,
            note = note,
            color = color,
        )
        return runCatching { store.add(annotation) }
            .onSuccess {
                clear()
                savedVersion += 1
            }
            .onFailure { lastActionError = it.message ?: "保存标注失败" }
            .getOrNull()
    }

    private companion object {
        const val HIGHLIGHT_COLOR = "#FFF59D"
    }
}

@Composable
fun rememberPdfSelectionState(
    controller: PdfSelectionController,
    bookId: Long,
    annotationStore: AnnotationStore? = null,
): PdfSelectionState {
    val context = LocalContext.current
    val clipboard = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }
    return remember(controller, bookId, annotationStore, clipboard) {
        PdfSelectionState(controller, bookId, annotationStore, clipboard)
    }
}

/**
 * PDF 选区高亮色：PDF 纸面是渲染出来的白底位图（深色主题下也不反色），所以不能只挑一个浅色主题能看的角色。
 *
 * - 浅色主题：primary 是品牌蓝，低透明度叠在白纸上就是可读的浅蓝选区；
 * - 深色主题：primary 变成浅蓝（#B0C6FF），压在白纸上几乎不可见，改用 primaryContainer（深蓝）做底。
 *
 * 两者都来自 colorScheme，不写 hex，也不用「固定 alpha 黑」那种把纸面压灰的叠色。
 * 主题明暗按 surface 的亮度判断（深色方案 surface 亮度 < 0.5），不额外读配置。
 */
@Composable
private fun pdfSelectionHighlight(): Color {
    val scheme = MaterialTheme.colorScheme
    return if (scheme.surface.luminance() < 0.5f) {
        scheme.primaryContainer.copy(alpha = 0.45f)
    } else {
        scheme.primary.copy(alpha = 0.28f)
    }
}

/**
 * 覆盖在单个 PDF 页面上：长按命中一个字符，拖动扩展选区，按行绘制半透明高亮。
 *
 * 调用方把它放进与页面位图同尺寸的 Box 即可；它不绘制页面，也不改变页面缩放。
 *
 * @param highlightColor 显式覆盖高亮色（测试/特殊页用）；默认 null 表示按主题取 [pdfSelectionHighlight]。
 */
@Composable
fun PdfSelectionOverlay(
    pageIndex: Int,
    state: PdfSelectionState,
    modifier: Modifier = Modifier,
    highlightColor: Color? = null,
) {
    val selectionColor = highlightColor ?: pdfSelectionHighlight()
    var pageSize by remember { mutableStateOf(IntSize.Zero) }
    val active = state.selection
    Canvas(
        modifier = modifier
            .onSizeChanged { pageSize = it }
            .pointerInput(pageIndex, pageSize) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        if (pageSize.width > 0 && pageSize.height > 0) {
                            state.begin(
                                page = pageIndex,
                                xNorm = (offset.x / pageSize.width).coerceIn(0f, 1f),
                                yNorm = (offset.y / pageSize.height).coerceIn(0f, 1f),
                            )
                        }
                    },
                    onDrag = { change, _ ->
                        if (pageSize.width > 0 && pageSize.height > 0) {
                            state.extend(
                                page = pageIndex,
                                xNorm = (change.position.x / pageSize.width).coerceIn(0f, 1f),
                                yNorm = (change.position.y / pageSize.height).coerceIn(0f, 1f),
                            )
                        }
                        change.consume()
                    },
                )
            },
    ) {
        if (active == null || active.page != pageIndex) return@Canvas
        for (rect in active.rects) {
            if (!rect.isValid) continue
            val left = rect.left.coerceIn(0f, 1f) * size.width
            val top = rect.top.coerceIn(0f, 1f) * size.height
            val width = rect.width.coerceIn(0f, 1f - rect.left.coerceIn(0f, 1f)) * size.width
            val height = rect.height.coerceIn(0f, 1f - rect.top.coerceIn(0f, 1f)) * size.height
            if (width <= 0f || height <= 0f) continue
            drawRect(
                color = selectionColor,
                topLeft = Offset(left, top),
                size = Size(width, height),
            )
        }
    }
}

/**
 * 选区工具条：复制/高亮/笔记。放在阅读屏底部 chrome 上方，由调用方决定对齐和 padding。
 *
 * 浮层条按 M3 用 shapes.extraLarge + surfaceContainerHigh + Elevation 档位；
 * 动作是 M3 TextButton，禁用态（无剪贴板 / 无标注库）走规范值 onSurface.copy(alpha = 0.38f)。
 */
@Composable
fun PdfSelectionToolbar(
    state: PdfSelectionState,
    modifier: Modifier = Modifier,
) {
    val active = state.selection
    if (active == null || !active.hasText) return
    var noteOpen by remember { mutableStateOf(false) }
    var noteText by remember { mutableStateOf("") }
    val scheme = MaterialTheme.colorScheme
    val actionColors = ButtonDefaults.textButtonColors(
        contentColor = scheme.primary,
        disabledContentColor = scheme.onSurface.copy(alpha = 0.38f),
    )
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = scheme.surfaceContainerHigh,
        contentColor = scheme.onSurface,
        tonalElevation = Elevation.level2,
        shadowElevation = Elevation.level3,
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.xxs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            TextButton(
                onClick = { state.copySelection() },
                enabled = state.canCopy,
                colors = actionColors,
            ) { Text("复制") }
            TextButton(
                onClick = { state.highlight() },
                enabled = state.canAnnotate,
                colors = actionColors,
            ) { Text("高亮") }
            TextButton(
                onClick = { noteOpen = true },
                enabled = state.canAnnotate,
                colors = actionColors,
            ) { Text("笔记") }
        }
    }

    if (noteOpen) {
        AlertDialog(
            onDismissRequest = { noteOpen = false },
            title = { Text("添加笔记") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ImmersiveDialogWindow()
                    OutlinedTextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        label = { Text("笔记内容") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.addNote(noteText)
                        noteText = ""
                        noteOpen = false
                    },
                    enabled = noteText.isNotBlank(),
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { noteOpen = false }) { Text("取消") }
            },
        )
    }
}
