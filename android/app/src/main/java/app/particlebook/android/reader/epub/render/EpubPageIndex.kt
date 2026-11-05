package app.particlebook.android.reader.epub.render

import app.particlebook.android.reader.ReaderLocator
import app.particlebook.android.reader.epub.EpubCharLineMetrics
import app.particlebook.android.reader.epub.layout.EpubChapterLayout

/**
 * 分页索引换算。纯 JVM，不依赖 Android；供 UI 的目录/恢复位置和测试使用。
 *
 * 块内行几何实现 [EpubCharLineMetrics] 时，字符偏移可精确定位到行；
 * 否则退化为块所在页。
 */
object EpubPageIndex {

    fun clampPage(pageIndex: Int, pageCount: Int): Int =
        if (pageCount <= 0) 0 else pageIndex.coerceIn(0, pageCount - 1)

    /** 找到包含 block/line 的第一页；块不存在返回 null。非文本块只认 line=0。 */
    fun pageForBlockLine(
        layout: EpubChapterLayout,
        blockIndex: Int,
        line: Int = 0,
    ): Int? {
        for (page in layout.pages) {
            for (slice in page.slices) {
                if (slice.blockIndex != blockIndex) continue
                if (!slice.isTextSlice) {
                    if (line == 0) return page.index
                } else if (line >= slice.lineStart && line < slice.lineEnd) {
                    return page.index
                }
            }
        }
        return null
    }

    /** 当前章内 locator 的页；跨章或块不存在返回 null。字符偏移有 [EpubCharLineMetrics] 时精确定位行。 */
    fun pageForLocator(layout: EpubChapterLayout, locator: ReaderLocator.Epub): Int? {
        val path = locator.chapterHref.substringBefore('#')
        if (path != layout.chapterHref.substringBefore('#')) return null
        val line = lineForOffset(layout, locator.blockIndex, locator.charOffset)
        return pageForBlockLine(layout, locator.blockIndex, line)
    }

    /** 某页起始 locator；空页返回 null。 */
    fun locatorForPage(layout: EpubChapterLayout, pageIndex: Int): ReaderLocator.Epub? {
        if (pageIndex !in 0 until layout.pageCount) return null
        val page = layout.pages[pageIndex]
        val slice = page.slices.firstOrNull() ?: return null
        return ReaderLocator.Epub(
            chapterHref = layout.chapterHref,
            blockIndex = slice.blockIndex,
            charOffset = 0,
        )
    }

    /** 跨章进度；chapterCount 为 spine 数。第一页为章起点，与控制器 percent 口径一致。 */
    private fun lineForOffset(
        layout: EpubChapterLayout,
        blockIndex: Int,
        charOffset: Int,
    ): Int {
        val block = layout.measuredBlocks.firstOrNull { it.blockIndex == blockIndex } ?: return 0
        val metrics = block.lines as? EpubCharLineMetrics ?: return 0
        if (metrics.lineCount <= 0) return 0
        val offset = charOffset.coerceAtLeast(0)
        // 与 EpubReaderController 保持一致：按 lineStart 边界定位，避免行尾空白
        // 让“下一页页首字符”被算到上一行/上一页。
        var result = 0
        for (line in 0 until metrics.lineCount) {
            if (metrics.lineStart(line) <= offset) result = line else break
        }
        return result
    }

    fun percent(
        chapterIndex: Int,
        chapterCount: Int,
        pageIndex: Int,
        pageCount: Int,
    ): Float {
        if (chapterCount <= 0) return 0f
        val pages = pageCount.coerceAtLeast(0)
        val fraction = if (pages <= 0) 0f
        else clampPage(pageIndex, pages).toFloat() / pages.toFloat()
        return ((chapterIndex.coerceAtLeast(0) + fraction) / chapterCount).coerceIn(0f, 1f)
    }
}