package app.particlebook.android.reader.epub.perf

/** PreDraw 注册的一帧；只接受该帧的内容，提交/取消后永不复用。 */
internal class EpubPerfFrame(
    private val tracker: EpubPerfTracker,
    private val chapter: Int,
    private val page: Int,
) {
    private var token: EpubPerfToken? = null
    private var finished = false

    fun matches(tracker: EpubPerfTracker, chapter: Int, page: Int): Boolean =
        !finished && this.tracker === tracker && this.chapter == chapter && this.page == page

    fun onContentDrawn(content: EpubPerfPageContent, drawEndNs: Long) {
        if (finished || token != null) return
        token = tracker.onContentDrawn(
            chapter = chapter,
            page = page,
            textOnly = content.textOnly,
            hasContent = content.hasContent,
            drawEndNs = drawEndNs,
        )
    }

    fun onFrameCommitted(endNs: Long) {
        if (finished) return
        finished = true
        token?.let { tracker.onFrameCommitted(it, endNs) }
        token = null
    }

    fun cancel() {
        if (finished) return
        finished = true
        token?.let { tracker.cancelDraw(it) }
        token = null
    }
}
