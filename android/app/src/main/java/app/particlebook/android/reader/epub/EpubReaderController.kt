package app.particlebook.android.reader.epub

import app.particlebook.android.reader.ReaderFormat
import app.particlebook.android.reader.ReaderLocator
import app.particlebook.android.reader.ReaderSession
import app.particlebook.android.reader.epub.layout.EpubBlockMeasurer
import app.particlebook.android.reader.epub.layout.EpubChapterLayout
import app.particlebook.android.reader.epub.layout.EpubLayoutConfig
import app.particlebook.android.reader.epub.layout.EpubLayoutEngine
import app.particlebook.android.reader.epub.layout.LineMetrics
import app.particlebook.android.reader.epub.layout.MeasuredBlock
import app.particlebook.android.reader.epub.layout.Page
import app.particlebook.android.reader.epub.parser.EpubParseException
import app.particlebook.android.reader.epub.parser.EpubParser
import app.particlebook.android.reader.epub.parser.EpubPaths
import java.io.Closeable
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * EPUB 阅读控制器：解析、章节生命周期、布局缓存、位置/进度和选区映射的唯一入口。
 *
 * 本类刻意保持纯 JVM 可测：不依赖 Android framework，不持有 View/Bitmap。
 * 生产渲染端通过 [open] 注入 StaticLayoutBlockMeasurer；纯 JVM 测试注入 fake measurer。
 * [open] 的默认 measurer 只是可用的近似实现，不用于最终排版验收。
 *
 * 线程约定：一个实例只由阅读会话的单一 worker 使用；不做跨线程同步。
 * 章节与布局缓存只在 [close] 时清空，避免换章/主题时重复解压和测量。
 */
class EpubReaderController private constructor(
    private val parser: EpubParser,
    private val measurer: EpubBlockMeasurer,
    val bookId: Long,
) : Closeable {

    val book: EpubBook
        get() = parser.book

    val toc: List<EpubTocEntry>
        get() = book.toc

    val warnings: List<String>
        get() = parser.warnings

    /** 图片/CSS 资源读取入口；直接复用 parser 的 archive，渲染器不要另开 ZIP。 */
    val resources: EpubResourceResolver
        get() = parser

    /** 当前章在 spine 中的下标；打开控制器后默认 0。 */
    var currentChapterIndex: Int = 0
        private set

    /** 当前章内的页下标；由 [layoutChapter]/[setPage] 维护，越界时自动夹取。 */
    var currentPageIndex: Int = 0
        private set

    private var currentChapter: EpubChapter? = null
    private var currentLayout: EpubChapterLayout? = null
    private var currentConfig: EpubLayoutConfig? = null
    private var closed = false

    private val engine = EpubLayoutEngine(measurer)

    private val chapterCache = object : LinkedHashMap<Int, EpubChapter>(CHAPTER_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, EpubChapter>?): Boolean =
            size > CHAPTER_CACHE_SIZE
    }

    private val layoutCache = object : LinkedHashMap<LayoutKey, EpubChapterLayout>(LAYOUT_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LayoutKey, EpubChapterLayout>?): Boolean =
            size > LAYOUT_CACHE_SIZE
    }

    val currentLayoutOrNull: EpubChapterLayout?
        get() = currentLayout

    val pageCount: Int
        get() = currentLayout?.pageCount ?: 0

    /** 当前章 + 页的全局阅读进度；没有布局时退化为按章进度。 */
    val percent: Float
        get() = percentAt(currentPageIndex)

    /**
     * 打开/切换章节。只解析，不自动布局；调用方随后用 [layoutChapter]。
     * 已解析章节会复用缓存，但每次切换都会把页下标重置为 0。
     */
    fun openChapter(spineIndex: Int): EpubChapter {
        requireOpen()
        val chapter = chapterAt(spineIndex)
        currentChapterIndex = spineIndex
        currentChapter = chapter
        currentLayout = null
        currentConfig = null
        currentPageIndex = 0
        return chapter
    }

    /** 按 TOC/链接 href 打开章节；fragment 会被忽略。 */
    fun openChapterByHref(href: String): EpubChapter {
        val index = chapterIndexForHref(href)
            ?: throw EpubParseException("CHAPTER_MISSING: " + href)
        return openChapter(index)
    }

    /** 只读取章节，不改变当前阅读位置。 */
    fun chapterAt(spineIndex: Int): EpubChapter {
        requireOpen()
        if (spineIndex !in book.spine.indices) {
            throw EpubParseException("CHAPTER_INDEX: " + spineIndex + " not in 0.." + book.spine.lastIndex)
        }
        chapterCache[spineIndex]?.let { return it }
        return parser.readChapter(spineIndex).also { chapterCache[spineIndex] = it }
    }

    /**
     * 对当前章布局；同一章同一 config 复用缓存。
     * 布局后 [pageCount] 更新，[currentPageIndex] 保持在合法范围。
     */
    fun layoutChapter(config: EpubLayoutConfig): EpubChapterLayout {
        requireOpen()
        require(config.viewportWidthPx > 0 && config.viewportHeightPx > 0) {
            "viewport must be positive: " + config.viewportWidthPx + "x" + config.viewportHeightPx
        }
        val chapter = currentChapter ?: chapterAt(currentChapterIndex).also { currentChapter = it }
        val key = LayoutKey(currentChapterIndex, config)
        val layout = layoutCache[key] ?: engine.layout(chapter, config).also { layoutCache[key] = it }
        currentLayout = layout
        currentConfig = config
        currentPageIndex = currentPageIndex.coerceIn(0, max(0, layout.pageCount - 1))
        return layout
    }

    /** 翻到章内某页；返回夹取后的页下标。 */
    fun setPage(pageIndex: Int): Int {
        requireOpen()
        currentPageIndex = pageIndex.coerceIn(0, max(0, pageCount - 1))
        return currentPageIndex
    }

    /**
     * locator -> 当前章页下标。
     *
     * 只处理当前章；跨章 locator 返回 null（调用方应先 openChapterByHref）。
     * 文本块优先用 [EpubCharLineMetrics] 把 charOffset 精确定位到行；没有字符行信息时退化为块所在页。
     */
    fun pageAt(locator: ReaderLocator): Int? = when (locator) {
        is ReaderLocator.Epub -> pageAt(locator)
        else -> null
    }

    fun pageAt(locator: ReaderLocator.Epub): Int? {
        requireOpen()
        val layout = currentLayout ?: return null
        val chapter = currentChapter ?: return null
        if (EpubPaths.withoutFragment(locator.chapterHref) != chapter.href) return null

        val block = layout.measuredBlocks.firstOrNull { it.blockIndex == locator.blockIndex }
        val line = block?.let { lineForOffset(it, locator.charOffset) }
        val exact = layout.pages.firstOrNull { page ->
            page.slices.any { slice ->
                slice.blockIndex == locator.blockIndex &&
                    (line == null || line in slice.lineStart until max(slice.lineStart + 1, slice.lineEnd))
            }
        }
        if (exact != null) return exact.index

        // 隐藏块/空块没有 slice：落到它之后最近的可见块，再没有就落到末页。
        return layout.pages.firstOrNull { page ->
            page.slices.any { it.blockIndex >= locator.blockIndex }
        }?.index ?: layout.pages.lastOrNull()?.index
    }

    fun pageForLocator(locator: ReaderLocator): Int? = pageAt(locator)

    /** 当前章内 page/block/char 的 locator；charOffset 夹取到块文本长度。 */
    fun locatorFor(pageIndex: Int, blockIndex: Int, charOffset: Int): ReaderLocator.Epub {
        requireOpen()
        val chapter = currentChapter ?: chapterAt(currentChapterIndex).also { currentChapter = it }
        val safePage = pageIndex.coerceIn(0, max(0, pageCount - 1))
        currentPageIndex = safePage
        val safeBlock = blockIndex.coerceIn(0, max(0, chapter.blocks.lastIndex))
        val text = textOf(chapter.blocks.getOrNull(safeBlock))
        return ReaderLocator.Epub(
            chapterHref = chapter.href,
            blockIndex = safeBlock,
            charOffset = charOffset.coerceIn(0, text.length),
        )
    }

    /** 某页起始位置的 locator。 */
    fun locatorForPage(pageIndex: Int): ReaderLocator.Epub {
        requireOpen()
        val chapter = currentChapter ?: chapterAt(currentChapterIndex).also { currentChapter = it }
        val page = pageAtPage(pageIndex)
        val slice = page?.slices?.firstOrNull()
        if (slice == null) return ReaderLocator.Epub(chapter.href, 0, 0)
        val block = chapter.blocks.getOrNull(slice.blockIndex)
        val offset = if (block != null) charOffsetForLine(block, slice.lineStart) else 0
        return ReaderLocator.Epub(chapter.href, slice.blockIndex, offset)
    }

    /** 生成当前阅读位置的统一会话快照；bookId 来自打开参数。 */
    fun session(pageIndex: Int = currentPageIndex): ReaderSession {
        requireOpen()
        val layout = currentLayout
        val safePage = pageIndex.coerceIn(0, max(0, (layout?.pageCount ?: 0) - 1))
        currentPageIndex = safePage
        return ReaderSession(
            bookId = bookId,
            format = ReaderFormat.EPUB,
            locator = locatorForPage(safePage),
            chapterIndex = currentChapterIndex,
            pageIndex = safePage,
            pageCount = layout?.pageCount ?: 0,
            percent = percentAt(safePage),
        )
    }

    /** 章内页所属的全局进度；页起点语义（第一页为章起点）。 */
    fun percentAt(pageIndex: Int): Float {
        val spineCount = book.spine.size
        if (spineCount <= 0) return 0f
        val pages = pageCount
        val fraction = if (pages <= 0) 0f else
            (pageIndex.coerceIn(0, pages - 1).toFloat() / pages.toFloat())
        return ((currentChapterIndex + fraction) / spineCount).coerceIn(0f, 1f)
    }

    /** 页内可选中文本；多个块之间用换行连接，便于选区偏移稳定。 */
    fun pageText(pageIndex: Int): String = pageTextMap(pageIndex)?.text.orEmpty()

    /**
     * 页内选区：startChar/endChar 是 [pageText] 的 UTF-16 偏移，end 为 exclusive。
     * 返回的 locator 可持久化；无法映射（空页/无效范围）时返回 null。
     */
    fun selectionRange(pageIndex: Int, startChar: Int, endChar: Int): EpubSelection? {
        val map = pageTextMap(pageIndex) ?: return null
        if (map.segments.isEmpty()) return null
        val from = startChar.coerceIn(0, map.text.length)
        val to = endChar.coerceIn(0, map.text.length)
        if (from >= to) return null
        val start = map.locate(from)
        val end = map.locateEnd(to)
        val chapter = currentChapter ?: return null
        return EpubSelection(
            pageIndex = pageIndex.coerceIn(0, max(0, pageCount - 1)),
            text = map.text.substring(from, to),
            start = ReaderLocator.Epub(chapter.href, start.blockIndex, start.charInBlock + (from - start.startInPage)),
            end = ReaderLocator.Epub(chapter.href, end.blockIndex, end.charInBlock + (to - end.startInPage)),
            startOffsetInPage = from,
            endOffsetInPage = to,
        )
    }

    /** 单块内选区；char 偏移相对块文本，适合渲染层已经知道块/字符的场景。 */
    fun selectionRangeInBlock(
        pageIndex: Int,
        blockIndex: Int,
        startChar: Int,
        endChar: Int,
    ): EpubSelection? {
        val page = pageAtPage(pageIndex) ?: return null
        if (page.slices.none { it.blockIndex == blockIndex }) return null
        val chapter = currentChapter ?: return null
        val text = textOf(chapter.blocks.getOrNull(blockIndex))
        val from = startChar.coerceIn(0, text.length)
        val to = endChar.coerceIn(0, text.length)
        if (from >= to) return null
        return EpubSelection(
            pageIndex = page.index,
            text = text.substring(from, to),
            start = ReaderLocator.Epub(chapter.href, blockIndex, from),
            end = ReaderLocator.Epub(chapter.href, blockIndex, to),
            startOffsetInPage = -1,
            endOffsetInPage = -1,
        )
    }

    /** href（可带 fragment）-> spine 下标；不在 spine 中返回 null。 */
    fun chapterIndexForHref(href: String): Int? {
        val path = EpubPaths.withoutFragment(href)
        return book.spine.indexOfFirst { EpubPaths.withoutFragment(it.href) == path }
            .takeIf { it >= 0 }
    }

    /**
     * 目录扁平化并补上 spine 下标，供 UI 跳转；href 不在 spine 时 chapterIndex=null。
     * 不展开为多个页面，保持原层级。
     */
    fun tocTargets(): List<EpubTocTarget> = book.toc.map { it.toTarget() }

    /** 当前章布局下，TOC href 对应页；不是当前章或未布局返回 null。 */
    fun pageForTocHref(href: String): Int? {
        val index = chapterIndexForHref(href) ?: return null
        if (index != currentChapterIndex) return null
        return pageAt(ReaderLocator.Epub(href, 0, 0))
    }

    /** 当前布局的第 pageIndex 页；越界返回 null。 */
    fun page(pageIndex: Int): Page? = pageAtPage(pageIndex)

    override fun close() {
        if (closed) return
        closed = true
        currentLayout = null
        currentChapter = null
        currentConfig = null
        layoutCache.clear()
        chapterCache.clear()
        parser.close()
    }

    private fun requireOpen() {
        check(!closed) { "EpubReaderController is closed" }
    }

    private fun pageAtPage(pageIndex: Int): Page? {
        val layout = currentLayout ?: return null
        return layout.pages.getOrNull(pageIndex)
    }

    private fun lineForOffset(block: MeasuredBlock, charOffset: Int): Int? {
        val metrics = block.lines as? EpubCharLineMetrics ?: return null
        if (metrics.lineCount <= 0) return null
        val length = textOf(block.block).length
        val offset = charOffset.coerceIn(0, length)
        // 用 lineStart 边界而不是 lineEnd：StaticLayout 的上一行 lineEnd 可能把
        // 行尾空白算进去（end > 下一行 start），用 end 会把“页首字符”误判到上一页。
        var result = 0
        for (line in 0 until metrics.lineCount) {
            val start = metrics.lineStart(line).coerceIn(0, length)
            if (start <= offset) result = line else break
        }
        return result
    }

    private fun charOffsetForLine(block: EpubBlock, line: Int): Int {
        val metrics = blockLines(block) as? EpubCharLineMetrics ?: return 0
        if (metrics.lineCount <= 0) return 0
        return metrics.lineStart(line.coerceIn(0, metrics.lineCount - 1))
    }

    private fun blockLines(block: EpubBlock): LineMetrics? {
        val chapter = currentChapter ?: return null
        val index = chapter.blocks.indexOfFirst { it === block }
        if (index < 0) return null
        return currentLayout?.measuredBlocks?.firstOrNull { it.blockIndex == index }?.lines
    }

    private fun pageTextMap(pageIndex: Int): PageTextMap? {
        val page = pageAtPage(pageIndex) ?: return null
        val layout = currentLayout ?: return null
        val chapter = currentChapter ?: return null
        val segments = ArrayList<PageTextSegment>()
        val builder = StringBuilder()

        for (slice in page.slices) {
            val measured = layout.measuredBlocks.firstOrNull { it.blockIndex == slice.blockIndex } ?: continue
            val fullText = selectableTextOf(measured.block)
            if (fullText.isEmpty()) continue
            val charRange = when {
                measured.lines is EpubCharLineMetrics && slice.lineEnd > slice.lineStart -> {
                    val metrics = measured.lines as EpubCharLineMetrics
                    val s = metrics.lineStart(slice.lineStart).coerceIn(0, fullText.length)
                    val e = metrics.lineEnd(slice.lineEnd - 1).coerceIn(s, fullText.length)
                    if (e > s) s until e else null
                }
                slice.lineStart == 0 -> 0 until fullText.length
                else -> null
            } ?: continue

            val text = fullText.substring(charRange.first, charRange.last + 1)
            if (text.isEmpty()) continue
            if (builder.isNotEmpty()) builder.append('\n')
            val startInPage = builder.length
            builder.append(text)
            segments += PageTextSegment(
                blockIndex = measured.blockIndex,
                charInBlock = charRange.first,
                startInPage = startInPage,
                endInPage = builder.length,
            )
        }
        if (segments.isEmpty()) return PageTextMap("", emptyList())
        return PageTextMap(builder.toString(), segments, chapter.href)
    }

    private fun selectableTextOf(block: EpubBlock?): String = when (block) {
        is EpubBlock.Paragraph -> block.text
        is EpubBlock.Heading -> block.text
        is EpubBlock.ListItem -> block.text
        is EpubBlock.Table -> block.rows.joinToString("\n") { row -> row.joinToString(" ") }
        else -> ""
    }

    private fun textOf(block: EpubBlock?): String = when (block) {
        is EpubBlock.Image -> block.alt.orEmpty()
        else -> selectableTextOf(block)
    }

    private data class LayoutKey(val chapterIndex: Int, val config: EpubLayoutConfig)

    private data class PageTextSegment(
        val blockIndex: Int,
        val charInBlock: Int,
        val startInPage: Int,
        val endInPage: Int,
    )

    private data class PageTextMap(
        val text: String,
        val segments: List<PageTextSegment>,
        val chapterHref: String? = null,
    ) {
        fun locate(offset: Int): PageTextSegment {
            val safe = offset.coerceIn(0, max(0, text.length - 1))
            segments.firstOrNull { safe in it.startInPage until it.endInPage }?.let { return it }
            // 段落分隔符/末尾偏移：优先落在前一段的末尾，否则落在下一段开头。
            segments.firstOrNull { safe == it.endInPage }?.let { return it }
            segments.firstOrNull { safe < it.startInPage }?.let { return it }
            return segments.last()
        }

        /** end-exclusive 边界；正好在段首时归下一段，正好在段尾时归前一段。 */
        fun locateEnd(offset: Int): PageTextSegment {
            val safe = offset.coerceIn(0, text.length)
            segments.firstOrNull { safe == it.startInPage }?.let { return it }
            segments.firstOrNull { safe in (it.startInPage + 1)..it.endInPage }?.let { return it }
            segments.firstOrNull { safe < it.startInPage }?.let { return it }
            return segments.last()
        }
    }

    private fun EpubTocEntry.toTarget(): EpubTocTarget = EpubTocTarget(
        title = title,
        href = href,
        chapterIndex = chapterIndexForHref(href),
        children = children.map { it.toTarget() },
    )

    companion object {
        const val CHAPTER_CACHE_SIZE = 3
        const val LAYOUT_CACHE_SIZE = 3

        /**
         * 打开 EPUB。
         *
         * @param measurer 生产传 StaticLayoutBlockMeasurer(density, scaledDensity)；
         * 纯 JVM 调用可省略（使用近似 measurer，仅用于测试/预检）。
         */
        fun open(
            file: File,
            measurer: EpubBlockMeasurer = HeuristicEpubBlockMeasurer(),
            bookId: Long = 0L,
        ): EpubReaderController {
            val parser = EpubParser.open(file)
            return EpubReaderController(parser, measurer, bookId)
        }
    }
}

/** 可选的字符行信息；StaticLayout 适配器实现它后，选区/字符定位才是精确的。 */
interface EpubCharLineMetrics : LineMetrics {
    fun lineStart(line: Int): Int
    fun lineEnd(line: Int): Int
}

/** 页内选区结果；start 含、end 不含，二者均为可直接持久化的 EPUB locator。 */
data class EpubSelection(
    val pageIndex: Int,
    val text: String,
    val start: ReaderLocator.Epub,
    val end: ReaderLocator.Epub,
    val startOffsetInPage: Int,
    val endOffsetInPage: Int,
)

/** 扁平化 TOC 目标；chapterIndex 为 null 表示 href 不在 spine（仍保留给 UI 诊断/降级）。 */
data class EpubTocTarget(
    val title: String,
    val href: String,
    val chapterIndex: Int?,
    val children: List<EpubTocTarget> = emptyList(),
)

/**
 * 默认近似 measurer：只保证纯 JVM 下 open/layout 可用，不参与视觉验收。
 * 字体宽度按 0.55em 估算，行高按 1.2 × baseTextSizeSp × lineHeight。
 */
private class HeuristicEpubBlockMeasurer : EpubBlockMeasurer {
    override fun measure(block: EpubBlock, blockIndex: Int, config: EpubLayoutConfig): MeasuredBlock {
        val style = block.style
        val marginTop = style.marginTopDp.roundToInt().coerceAtLeast(0)
        val marginBottom = style.marginBottomDp.roundToInt().coerceAtLeast(0)
        val pageBreakBefore = style.pageBreakBefore
        val lineHeight = max(1, (config.baseTextSizeSp * config.lineHeightMultiplier * 1.2f).roundToInt())

        return when (block) {
            is EpubBlock.Paragraph -> textBlock(block.text, blockIndex, block, config, lineHeight, marginTop, marginBottom, pageBreakBefore, 1f)
            is EpubBlock.Heading -> textBlock(block.text, blockIndex, block, config, lineHeight, marginTop, marginBottom, pageBreakBefore, 1.2f)
            is EpubBlock.ListItem -> textBlock(block.text, blockIndex, block, config, lineHeight, marginTop, marginBottom, pageBreakBefore, 1f)
            is EpubBlock.Table -> {
                val rows = max(1, block.rows.size)
                MeasuredBlock(blockIndex, block, heightPx = rows * lineHeight, marginTopPx = marginTop, marginBottomPx = marginBottom, pageBreakBefore = pageBreakBefore)
            }
            is EpubBlock.Image -> {
                val width = config.contentWidthPx
                MeasuredBlock(blockIndex, block, heightPx = max(1, width * 3 / 4), marginTopPx = marginTop, marginBottomPx = marginBottom, pageBreakBefore = pageBreakBefore)
            }
            EpubBlock.HorizontalRule -> MeasuredBlock(blockIndex, block, heightPx = 1, marginTopPx = marginTop, marginBottomPx = marginBottom, pageBreakBefore = pageBreakBefore)
            EpubBlock.PageBreak -> MeasuredBlock(blockIndex, block, heightPx = 0, forcePageBreakAfter = true)
        }
    }

    private fun textBlock(
        text: String,
        blockIndex: Int,
        block: EpubBlock,
        config: EpubLayoutConfig,
        lineHeight: Int,
        marginTop: Int,
        marginBottom: Int,
        pageBreakBefore: Boolean,
        scale: Float,
    ): MeasuredBlock {
        if (text.isBlank()) return MeasuredBlock(blockIndex, block, heightPx = 0, marginTopPx = marginTop, marginBottomPx = marginBottom, pageBreakBefore = pageBreakBefore)
        val charWidth = max(1f, config.baseTextSizeSp * scale * 0.55f)
        val charsPerLine = max(1, (config.contentWidthPx / charWidth).toInt())
        val lineCount = max(1, ceil(text.length.toFloat() / charsPerLine).toInt())
        val lines = HeuristicLines(text.length, charsPerLine, lineHeight)
        return MeasuredBlock(blockIndex, block, heightPx = lineCount * lineHeight, marginTopPx = marginTop, marginBottomPx = marginBottom, lines = lines, pageBreakBefore = pageBreakBefore)
    }
}

private class HeuristicLines(
    private val textLength: Int,
    private val charsPerLine: Int,
    private val lineHeight: Int,
) : EpubCharLineMetrics {
    override val lineCount: Int = max(1, ceil(textLength.toFloat() / charsPerLine).toInt())
    override fun lineTop(line: Int): Int = line.coerceIn(0, lineCount) * lineHeight
    override fun lineBottom(line: Int): Int = (line.coerceIn(0, lineCount) + 1) * lineHeight
    override fun lineBaseline(line: Int): Int = lineBottom(line) - 2
    override fun lineStart(line: Int): Int = line.coerceIn(0, lineCount) * charsPerLine
    override fun lineEnd(line: Int): Int = minOf(textLength, lineStart(line) + charsPerLine)
}
