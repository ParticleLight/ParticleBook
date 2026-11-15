package app.particlebook.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
        return addAnnotation(type = ReaderAnnotationType.NOTE, note = note.trim(), color = null)
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
            .onSuccess { clear() }
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
 * 覆盖在单个 PDF 页面上：长按命中一个字符，拖动扩展选区，按行绘制半透明高亮。
 *
 * 调用方把它放进与页面位图同尺寸的 Box 即可；它不绘制页面，也不改变页面缩放。
 */
@Composable
fun PdfSelectionOverlay(
    pageIndex: Int,
    state: PdfSelectionState,
    modifier: Modifier = Modifier,
    highlightColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
) {
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
                color = highlightColor,
                topLeft = Offset(left, top),
                size = Size(width, height),
            )
        }
    }
}

/**
 * 选区工具条：复制/高亮/笔记。放在阅读屏底部 chrome 上方，由调用方决定对齐和 padding。
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
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.xxs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            TextButton(
                onClick = { state.copySelection() },
                enabled = state.canCopy,
            ) { Text("复制") }
            TextButton(
                onClick = { state.highlight() },
                enabled = state.canAnnotate,
            ) { Text("高亮") }
            TextButton(
                onClick = { noteOpen = true },
                enabled = state.canAnnotate,
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
