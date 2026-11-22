package app.particlebook.android.reader.epub.perf

/** 记录终点：API 29+ 硬件渲染用帧提交，低版本/软件绘制回退为 Canvas 实绘完成。 */
enum class EpubPerfEndKind(val wireValue: String) {
    COMMIT("commit"),
    DRAW("draw"),
}

/**
 * 一条 PBPERF 事件。字段顺序和格式是外部性能脚本的契约，改动前先同步验证脚本。
 * ms 只表示 start_ns 到 end_ns，draw 回退不能冒充正式帧提交。
 */
data class EpubPerfEvent(
    val phase: String,
    val bookId: Long,
    val chapter: Int,
    val page: Int,
    val textOnly: Boolean,
    val startNs: Long,
    val drawEndNs: Long,
    val endNs: Long,
    val endKind: EpubPerfEndKind,
    val turn: Int? = null,
) {
    fun toLogLine(): String {
        // 浮点毫秒，不能用整数除法截断；Double.toString 不受 Locale 影响。
        val ms = (endNs - startNs).coerceAtLeast(0L) / 1_000_000.0
        return buildString {
            append("PBPERF|phase=").append(phase)
            append("|ms=").append(ms)
            append("|extra=book_id:").append(bookId)
            append(",chapter:").append(chapter)
            append(",page:").append(page)
            append(",text_only:").append(if (textOnly) 1 else 0)
            append(",start_ns:").append(startNs)
            append(",draw_end_ns:").append(drawEndNs)
            append(",end_ns:").append(endNs)
            append(",end_kind:").append(endKind.wireValue)
            if (turn != null) append(",turn:").append(turn)
        }
    }

    companion object {
        const val TAG = "PBPERF"
        const val PHASE_FIRST_CHAPTER = "reader_epub_first_chapter"
        const val PHASE_PAGE = "reader_epub_page"
    }
}
