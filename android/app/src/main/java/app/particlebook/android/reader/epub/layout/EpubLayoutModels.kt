package app.particlebook.android.reader.epub.layout

import app.particlebook.android.reader.epub.EpubBlock
import app.particlebook.android.reader.epub.EpubChapter
import kotlin.math.max

/**
 * 布局配置。viewport 尺寸是 px；padding 也是 px；baseTextSizeSp 是主题基准字号。
 * M0 的 sp->px 换算放在 StaticLayout 适配器里，纯 JVM 分页只消费 [MeasuredBlock]。
 */
data class EpubLayoutConfig(
    val viewportWidthPx: Int,
    val viewportHeightPx: Int,
    val horizontalPaddingPx: Int = 0,
    val verticalPaddingPx: Int = 0,
    val baseTextSizeSp: Float = 17f,
    val lineHeightMultiplier: Float = 1.5f,
    val defaultTextColor: Int = 0xFF1A1A1A.toInt(),
) {
    val contentWidthPx: Int
        get() = max(1, viewportWidthPx - horizontalPaddingPx * 2)

    val contentHeightPx: Int
        get() = max(1, viewportHeightPx - verticalPaddingPx * 2)
}

/** 文本块的行几何；由 StaticLayout 或测试 fake 实现。line top/bottom 相对块内容顶部。 */
interface LineMetrics {
    val lineCount: Int
    fun lineTop(line: Int): Int
    fun lineBottom(line: Int): Int
    fun lineBaseline(line: Int): Int
}

data class MeasuredBlock(
    val blockIndex: Int,
    val block: EpubBlock,
    /** 块内容高度（不含 margin）。 */
    val heightPx: Int,
    val marginTopPx: Int = 0,
    val marginBottomPx: Int = 0,
    val lines: LineMetrics? = null,
    val pageBreakBefore: Boolean = false,
    /** PageBreak 块专用：放下后强制换页。 */
    val forcePageBreakAfter: Boolean = false,
) {
    val isSplittable: Boolean
        get() = lines != null && lines.lineCount > 0 && heightPx > 0
}

/** 一页上某块的一段行；text block 用 lineStart/lineEnd，图片/表格等 lineStart=lineEnd=0。 */
data class PageSlice(
    val blockIndex: Int,
    val lineStart: Int,
    val lineEnd: Int,
    /** 该段在页面中的内容顶部 y；渲染时把 block 的 lineTop(lineStart) 平移到这个位置。 */
    val topPx: Int,
    val heightPx: Int,
) {
    val isTextSlice: Boolean
        get() = lineEnd > lineStart
}

data class Page(
    val index: Int,
    val slices: List<PageSlice>,
    /** 本页实际使用高度；内容超高时会大于 pageHeight，渲染端可裁切/标记。 */
    val usedHeightPx: Int,
)

data class LayoutResult(
    val blockCount: Int,
    val lineCount: Int,
    val pageCount: Int,
    val layoutMs: Long,
)

data class EpubChapterLayout(
    val chapterHref: String,
    val chapterTitle: String?,
    val config: EpubLayoutConfig,
    val measuredBlocks: List<MeasuredBlock>,
    val pages: List<Page>,
) {
    val pageCount: Int
        get() = pages.size

    val totalLines: Int
        get() = measuredBlocks.sumOf { it.lines?.lineCount ?: 0 }
}

fun interface EpubBlockMeasurer {
    fun measure(block: EpubBlock, blockIndex: Int, config: EpubLayoutConfig): MeasuredBlock
}

class EpubLayoutEngine(
    private val measurer: EpubBlockMeasurer,
    private val paginator: EpubPaginator = EpubPaginator(),
) {
    fun layout(chapter: EpubChapter, config: EpubLayoutConfig): EpubChapterLayout {
        val measured = ArrayList<MeasuredBlock>(chapter.blocks.size)
        for ((index, block) in chapter.blocks.withIndex()) {
            if (block.style.hidden) continue
            val item = measurer.measure(block, index, config)
            if (item.heightPx > 0 || item.forcePageBreakAfter || item.pageBreakBefore) {
                measured += item
            }
        }
        val pages = paginator.paginate(measured, config.contentHeightPx)
        return EpubChapterLayout(
            chapterHref = chapter.href,
            chapterTitle = chapter.title,
            config = config,
            measuredBlocks = measured,
            pages = pages,
        )
    }
}
