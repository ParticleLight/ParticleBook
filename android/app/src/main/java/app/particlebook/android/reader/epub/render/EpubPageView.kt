package app.particlebook.android.reader.epub.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import app.particlebook.android.reader.epub.EpubResourceResolver
import app.particlebook.android.reader.epub.layout.EpubChapterLayout
import app.particlebook.android.reader.epub.layout.Page
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * EPUB 单页自定义 View：Canvas 绘制 + 横向 fling 翻页 + 左右边缘单击翻页 +
 * 长按/拖动选区。翻页只回调目标页，由上层校验 pageCount。
 *
 * 手势阈值按 48dp；选区命中/矩形由 [EpubPageRenderer] 提供。
 */
class EpubPageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val metrics = context.resources.displayMetrics
    private val renderer = EpubPageRenderer(metrics.density, metrics.scaledDensity)
    private var chapterLayout: EpubChapterLayout? = null
    private var pageIndex: Int = 0
    private var selection: EpubSelectionRange? = null
    private var selectionAnchor: EpubSelectionHit? = null
    private var selectingByLongPress = false

    var onPageRequested: ((Int) -> Unit)? = null
    var onTap: ((x: Float, y: Float) -> Unit)? = null
    var onSelectionChanged: ((EpubSelectionRange?) -> Unit)? = null

    private val minFlingDistance = 48f * metrics.density
    private val edgeWidth = 0.18f

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float,
        ): Boolean {
            if (e1 == null) return false
            val dx = e2.x - e1.x
            val dy = e2.y - e1.y
            if (abs(dx) <= minFlingDistance || abs(dx) <= abs(dy)) return false
            if (dx < 0) onPageRequested?.invoke(pageIndex + 1)
            else onPageRequested?.invoke(pageIndex - 1)
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (selection != null) {
                clearSelection()
                performClick()
                return true
            }
            val width = width.toFloat().coerceAtLeast(1f)
            when {
                e.x < width * edgeWidth -> onPageRequested?.invoke(pageIndex - 1)
                e.x > width * (1f - edgeWidth) -> onPageRequested?.invoke(pageIndex + 1)
                else -> onTap?.invoke(e.x, e.y)
            }
            performClick()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val page = currentPage() ?: return
            val hit = renderer.hitTest(page, e.x, e.y) ?: return
            selectionAnchor = hit
            selectingByLongPress = true
            applySelection(
                EpubSelectionRange(
                    blockIndex = hit.blockIndex,
                    startChar = hit.charOffset,
                    endChar = hit.charOffset + 1,
                ),
            )
        }
    })

    fun setChapter(layout: EpubChapterLayout, pageIndex: Int, resolver: EpubResourceResolver?) {
        val safePage = pageIndex.coerceIn(0, max(0, layout.pageCount - 1))
        val layoutChanged = chapterLayout !== layout
        val pageChanged = this.pageIndex != safePage
        if (layoutChanged) {
            chapterLayout = layout
            renderer.setChapter(layout, resolver)
        }
        this.pageIndex = safePage
        if (layoutChanged || pageChanged) {
            selection = null
            selectionAnchor = null
            selectingByLongPress = false
        }
        invalidate()
    }

    /** 由上层恢复/清除选区；不重建章节，拖动过程中调用不会丢锚点。 */
    fun setSelection(range: EpubSelectionRange?) {
        if (selection == range) return
        selection = range
        if (range == null) {
            selectionAnchor = null
            selectingByLongPress = false
        }
        invalidate()
    }

    fun setPage(index: Int) {
        pageIndex = index.coerceIn(0, max(0, (chapterLayout?.pageCount ?: 1) - 1))
        selection = null
        selectionAnchor = null
        selectingByLongPress = false
        invalidate()
    }

    fun currentPageIndex(): Int = pageIndex

    fun clearSelection() {
        setSelection(null)
        onSelectionChanged?.invoke(null)
    }

    fun selectedRange(): EpubSelectionRange? = selection

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val page = currentPage() ?: return
        renderer.draw(canvas, page)
        val active = selection ?: return
        val rects = renderer.selectionRects(page, active)
        if (rects.isNotEmpty()) renderer.drawSelection(canvas, rects)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            selectingByLongPress = false
        }
        val handled = gestureDetector.onTouchEvent(event)
        if (selectingByLongPress && event.actionMasked == MotionEvent.ACTION_MOVE) {
            updateSelection(event.x, event.y)
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            selectingByLongPress = false
        }
        return handled || super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        renderer.close()
        super.onDetachedFromWindow()
    }

    private fun updateSelection(x: Float, y: Float) {
        val page = currentPage() ?: return
        val anchor = selectionAnchor ?: return
        val hit = renderer.hitTest(page, x, y) ?: return
        if (hit.blockIndex != anchor.blockIndex) return
        val start = min(anchor.charOffset, hit.charOffset)
        val end = max(anchor.charOffset, hit.charOffset)
        applySelection(EpubSelectionRange(anchor.blockIndex, start, end))
    }

    private fun applySelection(range: EpubSelectionRange) {
        selection = range
        onSelectionChanged?.invoke(range)
        invalidate()
    }

    private fun currentPage(): Page? {
        val layout = chapterLayout ?: return null
        return layout.pages.getOrNull(pageIndex)
    }
}