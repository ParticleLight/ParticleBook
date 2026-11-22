package app.particlebook.android.reader.epub.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import app.particlebook.android.reader.epub.EpubResourceResolver
import app.particlebook.android.reader.epub.layout.EpubChapterLayout
import app.particlebook.android.reader.epub.layout.Page
import app.particlebook.android.reader.epub.perf.EpubPerfFrame
import app.particlebook.android.reader.epub.perf.EpubPerfPageContent
import app.particlebook.android.reader.epub.perf.EpubPerfPageRuns
import app.particlebook.android.reader.epub.perf.EpubPerfTracker
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
    private var chapterResolver: EpubResourceResolver? = null
    private var perfPageRuns: EpubPerfPageRuns? = null
    private var perfPageContent: EpubPerfPageContent? = null
    private var pageIndex: Int = 0
    private var selection: EpubSelectionRange? = null
    private var highlights: List<EpubHighlight> = emptyList()
    private var selectionAnchor: EpubSelectionHit? = null
    private var selectingByLongPress = false


    var onPageRequested: ((Int) -> Unit)? = null
    var onTap: ((x: Float, y: Float) -> Unit)? = null
    var onSelectionChanged: ((EpubSelectionRange?) -> Unit)? = null

    /** PBPERF 状态机由阅读屏注入；为空时不做任何性能记录。 */
    var perfTracker: EpubPerfTracker? = null
        set(value) {
            if (field === value) return
            cancelFrameProbes()
            field = value
            invalidate()
        }
    var perfChapterIndex: Int = 0

    private val frameProbes = LinkedHashSet<FrameProbe>()
    private var currentFrameProbe: FrameProbe? = null

    /** 见 [PERF_RETRY_BUDGET]：探针重试的剩余帧数，配对成功即恢复。 */
    private var perfRetryBudget = PERF_RETRY_BUDGET
    private var preDrawObserver: ViewTreeObserver? = null
    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        // 上次 traversal 若未绘制就结束，只清理未绑定的 probe，不碰等待提交的帧。
        currentFrameProbe?.let { cancelFrameProbe(it) }
        val tracker = perfTracker
        if (tracker != null && perfPageContent?.hasContent == true &&
            tracker.hasPendingDrawCandidate() &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isHardwareAccelerated
        ) {
            prepareFrameProbe(tracker)
        }
        true
    }

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
            // 长按选区后拖动扩展时，抬手不能把这次拖动当成翻页 fling。
            if (selectingByLongPress || selection != null) return true
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
            perfPageRuns = EpubPerfPageRuns(layout)
            renderer.setChapter(layout, resolver)
        }
        chapterResolver = resolver
        this.pageIndex = safePage
        if (layoutChanged || pageChanged) {
            updatePerfPageContent()
            selection = null
            selectionAnchor = null
            selectingByLongPress = false
        }
        invalidate()
    }

    /**
     * 当前页已持久化标注的高亮范围。与 [selection] 独立：选区绘制在最上层，
     * 高亮只按颜色画半透明底，不参与命中/拖动。
     */
    fun setHighlights(ranges: List<EpubHighlight>) {
        if (highlights == ranges) return
        highlights = ranges
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
        val safePage = index.coerceIn(0, max(0, (chapterLayout?.pageCount ?: 1) - 1))
        if (pageIndex != safePage) {
            pageIndex = safePage
            updatePerfPageContent()
        }
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
        for (highlight in highlights) {
            if (highlight.length <= 0) continue
            val rects = renderer.selectionRects(
                page,
                EpubSelectionRange(
                    blockIndex = highlight.blockIndex,
                    startChar = highlight.startChar,
                    endChar = highlight.endChar,
                ),
            )
            if (rects.isNotEmpty()) renderer.drawSelection(canvas, rects, highlight.colorArgb)
        }
        val active = selection
        if (active != null) {
            val rects = renderer.selectionRects(page, active)
            if (rects.isNotEmpty()) renderer.drawSelection(canvas, rects)
        }
        // 先取实绘终点，后续分类/状态机/回调管理开销不计入 draw_end_ns。
        val drawEndNs = System.nanoTime()
        finishPerfDraw(drawEndNs)
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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        chapterLayout?.let { renderer.setChapter(it, chapterResolver) }
        val observer = viewTreeObserver
        preDrawObserver = observer
        observer.addOnPreDrawListener(preDrawListener)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        preDrawObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDrawListener)
        preDrawObserver = null
        cancelFrameProbes()
        renderer.close()
        super.onDetachedFromWindow()
    }

    /** PreDraw 注册本帧回调；空首绘之后仍须等待匹配帧，不降级为 draw。 */
    private fun prepareFrameProbe(tracker: EpubPerfTracker) {
        val layout = chapterLayout ?: return
        val observer = viewTreeObserver
        if (!observer.isAlive) return
        val probe = FrameProbe(observer, layout, EpubPerfFrame(tracker, perfChapterIndex, pageIndex))
        val callback = object : Runnable {
            override fun run() {
                val endNs = System.nanoTime()
                frameProbes.remove(probe)
                if (currentFrameProbe === probe) currentFrameProbe = null
                // 捕获原 tracker 的帧对象，切书后迟到回调不会投递给新的 tracker。
                probe.frame.onFrameCommitted(endNs)
                // 本帧可能复用了空/旧 display list，未触发 onDraw；强制下一帧再配对。
                // 同样受 [PERF_RETRY_BUDGET] 约束，避免候选不成立时整页无休止重绘。
                if (isAttachedToWindow && perfTracker === tracker &&
                    tracker.hasPendingDrawCandidate() && perfRetryBudget > 0
                ) {
                    perfRetryBudget -= 1
                    postInvalidateOnAnimation()
                }
            }
        }
        probe.callback = callback
        observer.registerFrameCommitCallback(callback)
        frameProbes += probe
        currentFrameProbe = probe
    }

    private fun cancelFrameProbe(probe: FrameProbe) {
        probe.frame.cancel()
        val callback = probe.callback
        if (callback != null && probe.observer.isAlive) {
            probe.observer.unregisterFrameCommitCallback(callback)
        }
        frameProbes.remove(probe)
        if (currentFrameProbe === probe) currentFrameProbe = null
    }

    private fun cancelFrameProbes() {
        for (probe in frameProbes.toList()) cancelFrameProbe(probe)
    }

    private fun updatePerfPageContent() {
        // 仅在页面/排版变化时分类，普通重绘只读缓存。
        perfPageContent = currentPage()?.let { perfPageRuns?.of(it) }
    }

    private fun finishPerfDraw(drawEndNs: Long) {
        val tracker = perfTracker ?: return
        val content = perfPageContent ?: return
        if (!content.hasContent) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isHardwareAccelerated) {
            val probe = currentFrameProbe
            currentFrameProbe = null
            if (probe == null || probe.layout !== chapterLayout ||
                !probe.frame.matches(tracker, perfChapterIndex, pageIndex)
            ) {
                // 内容可能在首次 PreDraw 后才绑定；下一帧重新注册，不消耗首章 token。
                // 但重试必须有预算：候选若不成立，无限 postInvalidateOnAnimation 会让整页 60fps 空转。
                if (tracker.hasPendingDrawCandidate() && perfRetryBudget > 0) {
                    perfRetryBudget -= 1
                    postInvalidateOnAnimation()
                } else {
                    tracker.onUnmeasuredDraw(perfChapterIndex, pageIndex)
                }
                return
            }
            // 帧配对成功说明绘制路径正常，下一轮候选重新给足预算。
            perfRetryBudget = PERF_RETRY_BUDGET
            probe.frame.onContentDrawn(content, drawEndNs)
        } else {
            val token = tracker.onContentDrawn(
                chapter = perfChapterIndex,
                page = pageIndex,
                textOnly = content.textOnly,
                hasContent = content.hasContent,
                drawEndNs = drawEndNs,
            ) ?: return
            tracker.onDrawFallback(token, drawEndNs)
        }
    }

    private class FrameProbe(
        val observer: ViewTreeObserver,
        val layout: EpubChapterLayout,
        val frame: EpubPerfFrame,
    ) {
        var callback: Runnable? = null
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

    private companion object {
        /**
         * 帧探针的重试预算。tracker 有候选却没等到匹配帧时，旧实现在 onDraw 里无限
         * postInvalidateOnAnimation：只要候选一直不成立（例如首屏事件在直开章节里产不出来），
         * 整页就会以 60fps 无休止重绘，把 GPU 管线占满（真机证据：epub-04c/04e 窗口外仍有
         * 3064 帧、draw 4.20ms，帧间 p50 16.65ms）。给 90 帧（约 1.5s）上限：够冷启事件落地，
         * 之后退回普通重绘，等下一次真实翻页再重来。
         */
        const val PERF_RETRY_BUDGET = 90
    }
}