package app.particlebook.android.reader.pdf.search

import app.particlebook.android.reader.pdf.PdfNormalizedRect

/**
 * 一条 PDF 书内命中。
 *
 * 坐标契约：`charStart`/`charEnd` 是 [app.particlebook.android.reader.pdf.PdfChar.index]
 * 的半开区间，不是 chars 列表下标（PDFium 会跳过 '\u0000'，两者并不相等）。`rects` 是扫描
 * 时随字符框一起算好的归一化矩形，跳页高亮不需要再打开一次文本页。
 *
 * `matchStart`/`matchEnd` 是 `snippet` 内命中词的半开区间（折叠空白后的下标），
 * 供 UI 在结果列表里给自己加粗；两者都为 -1 时表示片段里定位不到（不应发生，留作兜底）。
 */
data class PdfSearchHit(
    val page: Int,
    val charStart: Int,
    val charEnd: Int,
    val snippet: String,
    val matchStart: Int,
    val matchEnd: Int,
    val rects: List<PdfNormalizedRect>,
) {
    /** 片段是否带了可高亮的词内区间。 */
    val hasSnippetMatch: Boolean get() = matchStart in 0 until matchEnd && matchEnd <= snippet.length
}

/**
 * 一次扫描的完整结果。
 *
 * `scanning` 类的中间状态不在这里：扫描过程通过回调上报，最终只产出这一份快照，
 * 这样结果列表与进度条不会互相覆盖。
 */
data class PdfSearchReport(
    val query: String,
    val hits: List<PdfSearchHit>,
    val scannedPages: Int,
    val pageCount: Int,
    val limitReached: Boolean,
) {
    val hitCount: Int get() = hits.size

    /** 扫描完整跑完（未因上限提前结束）且一命中没有。 */
    val isEmpty: Boolean get() = hits.isEmpty() && !limitReached

    /** 每页命中数，供结果列表按页分组显示。 */
    fun hitsByPage(): Map<Int, List<PdfSearchHit>> =
        hits.groupBy { it.page }.toSortedMap()

    companion object {
        fun empty(query: String, pageCount: Int): PdfSearchReport =
            PdfSearchReport(query, emptyList(), 0, pageCount, false)
    }
}
