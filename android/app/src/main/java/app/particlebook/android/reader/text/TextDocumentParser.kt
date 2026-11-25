package app.particlebook.android.reader.text

/**
 * 一段正文；headingLevel 0=普通段落，1..6 对应 Markdown 的 1..6 级标题。
 *
 * [image] 非空表示这一段是一张插图，此时 [text] 为空：图片占一个段落下标（滚动、进度、
 * 目录跳转都按它算），但搜索、朗读、标注、目录这些只看文字的功能都会跳过它。
 */
internal data class TextParagraph(
    val text: String,
    val headingLevel: Int = 0,
    val image: TextImage? = null,
)

/** 解析结果：段落 + 每段在原文里的起始字符偏移（用于按位置恢复阅读进度）。 */
internal data class TextDocument(
    val paragraphs: List<TextParagraph>,
    val charOffsets: IntArray,
) {
    private var sourceCharCount: Int = 0

    /**
     * 规范化（CRLF / CR → LF）后全文的字符数，口径是 UTF-16 码元，
     * 与 [charOffsets] 的下标同一坐标系。由 [TextDocumentParser.parse] 写入。
     */
    val charCount: Int
        get() = sourceCharCount

    /** 没有可显示的段落（空文件或全空白）。此时 [charCount] 仍可能是空白字符数。 */
    val isEmpty: Boolean
        get() = paragraphs.isEmpty()

    /** 仅供 [TextDocumentParser.parse] 在构造后写入原文长度；外部只读。 */
    internal fun bindCharCount(count: Int) {
        sourceCharCount = count.coerceAtLeast(0)
    }
}

/**
 * 纯文本 / Markdown → 段落模型的解析器。纯 JVM，不依赖 Android。
 *
 * 分段规则：连续非空行成段，空行或纯空白行是段分隔（Markdown 的代码围栏内除外，
 * 围栏里的空行属于代码本身，保留在同一段里，否则代码块会被空行劈碎）。
 * 围栏按出现顺序两两配对：末尾落单的开围栏当普通文本行，不会吞掉它后面的整本书。
 *
 * - TXT：段内换行折叠成一个空格（中文 txt 常见的 hard-wrap），段首尾 trim；
 * - Markdown：段内换行保留；段第一行形如「1..6 个 # + 空白」时识别为标题，
 *   标题文本去掉井号与前导空白，后续行原样留在段内；围栏内的 # 行不算标题。
 *
 * 超长段落（网页复制 / 机器导出的整篇无换行文本）超过 [MAX_PARAGRAPH_CHARS] 时按
 * 码元切成多段，切点避开代理对；后续块的 headingLevel 一律 0，charOffsets 仍然
 * 指向该块在规范化全文里的起点（段起点 + 前面各块长度）。
 *
 * charOffsets 是**近似**下标：段起点取原文行首，块内偏移却是在折叠后（TXT 折行、Markdown 去 #）
 * 的段落文本坐标上累加的，所以它只保证单调递增，不等于原文里的字符位置。当前唯一消费者是
 * 「字符偏移 → 段落」定位（[TextOutline] / 阅读进度），只需要单调；若将来要拿它跟原始文件对账
 * （标注导出、跨版本迁移），得先补一张「折叠下标 → 原文下标」的映射。
 * [TextDocument.charCount] 是规范化后全文长度，两者都按 UTF-16 码元计，因此比例可以直接算。
 */
internal object TextDocumentParser {

    /** 反引号围栏在 Kotlin 里用 \u0060 拼，避免和 markdown 文档里的反引号混淆。 */
    private const val FENCE_BACKTICKS = "\u0060\u0060\u0060"
    private const val FENCE_TILDES = "~~~"

    /** 没有落单围栏时的哨兵偏移。 */
    private const val NO_FENCE = -1

    private const val MAX_HEADING_LEVEL = 6

    /**
     * 单段最大字符数（UTF-16 码元）。2 万字符按一屏 40 字算约 500 行，排版
     * 成本可控；再大就会让一个不可分割的 Text 在 UI 层反复测量，几百 MB 的无换行
     * txt 更会直接 OOM。取 20_000 而不是更大：切分本身只是 substring，开销可以忽略，
     * 而块越小，滚动与按位置恢复进度的粒度越细（4M 上限下最多 200 块）。
     */
    private const val MAX_PARAGRAPH_CHARS = 20_000

    /**
     * Float 只有 24 位有效位：大文档里 fractionForParagraph 的返回值再乘回
     * charCount 会差几个字符。用全文百万分之一的容差吸收这个往返误差
     * （4M 字符的 txt 也只有 4 个字符），避免恢复进度时掉到上一段。
     */
    private const val ROUND_TRIP_SLACK = 1e-6f

    fun parse(raw: String, markdown: Boolean): TextDocument {
        val normalized = normalizeNewlines(raw)
        val paragraphs = ArrayList<TextParagraph>()
        val offsets = ArrayList<Int>()

        // 围栏要成对才算代码块：末尾落单的那个开围栏按普通文本处理。否则 inFence 一路为真、
        // 空行不再切段，后面整本书会被并成一段（目录、进度粒度、搜索片段全跟着坏）。
        val unclosedFenceStart = if (markdown) unclosedFenceLineStart(normalized) else NO_FENCE

        var blockStart = -1
        var blockLines = ArrayList<String>()
        var inFence = false

        fun flushBlock() {
            if (blockLines.isEmpty()) return
            val paragraph = buildParagraph(blockLines, markdown)
            if (paragraph != null) {
                appendParagraph(paragraph, blockStart, paragraphs, offsets)
            }
            blockLines.clear()
            blockStart = -1
        }

        var lineStart = 0
        while (lineStart <= normalized.length) {
            val lineFeed = normalized.indexOf('\n', lineStart)
            val lineEnd = if (lineFeed < 0) normalized.length else lineFeed
            val lineText = normalized.substring(lineStart, lineEnd)
            val fenceLine = markdown && lineStart != unclosedFenceStart && isFenceLine(lineText)
            val insideFence = inFence
            if (fenceLine) inFence = !inFence
            if (lineText.isBlank() && !insideFence) {
                flushBlock()
            } else {
                if (blockLines.isEmpty()) blockStart = lineStart
                blockLines.add(lineText)
            }
            if (lineFeed < 0) break
            lineStart = lineFeed + 1
        }
        flushBlock()

        val document = TextDocument(paragraphs, offsets.toIntArray())
        document.bindCharCount(normalized.length)
        return document
    }

    /**
     * 由「已经分好段的段落列表」重建文档（FB2 这类结构化格式用）。
     *
     * 与 [parse] 走同一套超长切分与代理对保护，只是偏移来源不同：
     * 结构化格式没有可复用的原文（标签之间还夹着属性与空白），所以
     * charOffsets[i] = 前 i 段文本长度之和、charCount = 全部段落文本长度之和 ——
     * 坐标系仍是 UTF-16 码元，[fractionForParagraph] / [paragraphIndexForFraction] 照常单调可用。
     * 空段落（空串）直接丢弃，不占一个可滚动的位置。
     */
    fun fromParagraphs(paragraphs: List<TextParagraph>): TextDocument {
        val out = ArrayList<TextParagraph>(paragraphs.size)
        val offsets = ArrayList<Int>(paragraphs.size)
        var cursor = 0
        for (paragraph in paragraphs) {
            // 图片段没有文字但必须留下：它是正文流里的一个位置，丢了图就整张消失。
            if (paragraph.text.isEmpty() && paragraph.image == null) continue
            appendParagraph(paragraph, cursor, out, offsets)
            cursor += paragraph.text.length
        }
        val document = TextDocument(out, offsets.toIntArray())
        document.bindCharCount(cursor)
        return document
    }

    /** 阅读进度：给定 0..1 的比例，返回应显示的第一段下标（越界一律落到合法范围）。 */
    fun paragraphIndexForFraction(document: TextDocument, fraction: Float): Int {
        val count = document.paragraphs.size
        if (count == 0) return 0
        val total = document.charCount
        if (total <= 0) return 0
        if (document.charOffsets.isEmpty()) return 0
        val safeFraction = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
        val target = safeFraction * total.toFloat() + ROUND_TRIP_SLACK * total.toFloat()
        var index = 0
        // 段落数一般 < 10 万，线性扫描足够；charOffsets 单调递增，遇到第一段越界即可停。
        for (candidate in 1 until count) {
            if (candidate >= document.charOffsets.size) break
            if (document.charOffsets[candidate].toFloat() <= target) index = candidate else break
        }
        return index
    }

    /** 阅读进度：给定段落下标，返回 0..1 的比例（按该段起始字符偏移 / 全文字符数）。 */
    fun fractionForParagraph(document: TextDocument, index: Int): Float {
        if (document.paragraphs.isEmpty()) return 0f
        val safeIndex = index.coerceIn(0, document.paragraphs.size - 1)
        val total = document.charCount
        if (total <= 0) return 0f
        if (safeIndex >= document.charOffsets.size) return 0f
        return (document.charOffsets[safeIndex].toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }

    /**
     * 把一段正文按 [MAX_PARAGRAPH_CHARS] 切开后写入结果；charOffsets 逐块累加，
     * 起点仍落在规范化全文里（第二块 = 段起点 + 第一块长度）。标题级别只属于首块。
     */
    private fun appendParagraph(
        paragraph: TextParagraph,
        startOffset: Int,
        paragraphs: MutableList<TextParagraph>,
        offsets: MutableList<Int>,
    ) {
        val text = paragraph.text
        if (text.length <= MAX_PARAGRAPH_CHARS) {
            paragraphs.add(paragraph)
            offsets.add(startOffset)
            return
        }
        var chunkStart = 0
        var headingLevel = paragraph.headingLevel
        while (chunkStart < text.length) {
            var chunkEnd = minOf(chunkStart + MAX_PARAGRAPH_CHARS, text.length)
            // 切点落在代理对中间时前移一位，把整对留到下一块。
            if (chunkEnd < text.length && chunkEnd - 1 > chunkStart &&
                Character.isHighSurrogate(text[chunkEnd - 1]) &&
                Character.isLowSurrogate(text[chunkEnd])
            ) {
                chunkEnd--
            }
            paragraphs.add(TextParagraph(text.substring(chunkStart, chunkEnd), headingLevel))
            offsets.add(startOffset + chunkStart)
            chunkStart = chunkEnd
            headingLevel = 0
        }
    }

    private fun buildParagraph(lines: List<String>, markdown: Boolean): TextParagraph? {
        if (!markdown) {
            val text = foldTxtLines(lines)
            return if (text.isEmpty()) null else TextParagraph(text, headingLevel = 0)
        }
        val trimmed = lines.joinToString("\n").trim()
        if (trimmed.isEmpty()) return null
        val lineBreak = trimmed.indexOf('\n')
        val firstLine = if (lineBreak < 0) trimmed else trimmed.substring(0, lineBreak)
        val level = headingLevel(firstLine)
        if (level == 0) return TextParagraph(trimmed, headingLevel = 0)
        val headingText = firstLine.substring(level).trimStart()
        val rest = if (lineBreak < 0) "" else trimmed.substring(lineBreak)
        // 多行标题只把第一行当标题；后续行原样留在段内。
        val text = if (headingText.isEmpty()) rest.trim() else headingText + rest
        return if (text.isEmpty()) null else TextParagraph(text, headingLevel = level)
    }

    /**
     * TXT 段内软换行折叠：逐行去掉行尾空白，再用一个空格连接。
     *
     * 行尾空白必须先去掉再折叠：定长折行的 txt（网页复制、PDF/EPUB 转文本的产物）常在每行末尾
     * 留空格，直接 join 会把「两个空格 + 一个空格」叠成一段宽间隙，中文正文里看着像错排。
     */
    private fun foldTxtLines(lines: List<String>): String {
        val builder = StringBuilder()
        for (line in lines) {
            val trimmed = line.trimEnd()
            if (trimmed.isEmpty()) continue
            if (builder.isNotEmpty()) builder.append(' ')
            builder.append(trimmed)
        }
        return builder.toString().trim()
    }

    /** 「1..6 个 # + 至少一个空格 / 制表符」返回 # 个数，否则 0。 */
    private fun headingLevel(line: String): Int {
        var hashes = 0
        while (hashes < line.length && line[hashes] == '#') hashes++
        if (hashes == 0 || hashes > MAX_HEADING_LEVEL) return 0
        if (hashes >= line.length) return 0
        val separator = line[hashes]
        return if (separator == ' ' || separator == '\t') hashes else 0
    }

    private fun isFenceLine(line: String): Boolean = isFenceLine(line, 0, line.length)

    /**
     * 围栏行判定：行首空白之后连续三个反引号或波浪线（后面允许带语言标注）。
     *
     * 用 [CharSequence] + 区间而不是 [String]：预扫描要给全文每行判一次，逐行 substring 在大文件上纯属浪费。
     */
    private fun isFenceLine(source: CharSequence, start: Int, end: Int): Boolean {
        var index = start
        while (index < end && source[index].isWhitespace()) index++
        if (end - index < 3) return false
        val marker = source[index]
        if (marker != '`' && marker != '~') return false
        return source[index + 1] == marker && source[index + 2] == marker
    }

    /**
     * 末尾落单的开围栏所在行的行首偏移；围栏全部成对时返回 [NO_FENCE]。
     *
     * 围栏按出现顺序两两配对（与主循环同一个宽松判定，不区分反引号/波浪线），
     * 配不上的最后一个就是「开了没收」，主循环会把它当普通文本行。
     */
    private fun unclosedFenceLineStart(normalized: String): Int {
        var pending = NO_FENCE
        var lineStart = 0
        while (lineStart <= normalized.length) {
            val lineFeed = normalized.indexOf('\n', lineStart)
            val lineEnd = if (lineFeed < 0) normalized.length else lineFeed
            if (isFenceLine(normalized, lineStart, lineEnd)) {
                pending = if (pending == NO_FENCE) lineStart else NO_FENCE
            }
            if (lineFeed < 0) break
            lineStart = lineFeed + 1
        }
        return pending
    }

    private fun normalizeNewlines(raw: String): String {
        if (raw.indexOf('\r') < 0) return raw
        val out = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            val char = raw[index]
            if (char == '\r') {
                out.append('\n')
                if (index + 1 < raw.length && raw[index + 1] == '\n') index++
            } else {
                out.append(char)
            }
            index++
        }
        return out.toString()
    }
}
