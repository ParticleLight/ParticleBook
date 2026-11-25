package app.particlebook.android.reader.text

/**
 * 文本类阅读器（TXT / Markdown / FB2 / HTML）的一条目录项。
 *
 * 四种格式经 [TextDocument] 归一之后，标题只剩 [TextParagraph.headingLevel] 这一个特征，
 * 所以目录直接从文档本身推导，不必每种格式各写一套目录解析。
 *
 * [level] 是**归一化后的相对层级**：文档里出现过的最小标题层级算 1，往后依次递增，
 * 封顶 [TextOutline.MAX_LEVEL]。归一化的理由：FB2 从 1 起（section 嵌套），
 * HTML 常见从 2 起（拿 h2 当章），Markdown 也可能从 3 起；若按原始值缩进，
 * 三本同样结构的书会缩出三种样子。
 */
internal data class TextChapter(
    val title: String,
    val paragraphIndex: Int,
    val level: Int,
)

/**
 * 从 [TextDocument] 推导目录，并回答「当前读到第几章」。
 *
 * 纯函数、无状态：文档在内存里整本可扫，目录只在文档变化时算一次。
 */
internal object TextOutline {
    /** 目录最多收多少条：长篇动辄上千个标题，弹层一次铺全表既慢又没人翻。 */
    const val DEFAULT_MAX_CHAPTERS: Int = 400

    /** 层级上限，与 Markdown / HTML 的 h1..h6 对齐。 */
    const val MAX_LEVEL: Int = 6

    /**
     * 扫出所有标题段落，按文档顺序返回。
     *
     * - 只认 [TextParagraph.headingLevel] > 0 且文本非空白（isBlank）的段落 —— 纯空白标题没有导航价值；
     * - [TextChapter.paragraphIndex] 是 [TextDocument.paragraphs] 的下标：结构化格式经
     *   [TextDocumentParser.fromParagraphs] 时会丢掉空串段落，所以拿原始入参列表的下标去对会错位；
     * - [maxChapters] <= 0 或文档为空 → 空表（调用方据此隐藏目录入口）；
     * - 超过 [maxChapters] 只取前 N 条，不报错也不截断正文。
     */
    fun build(document: TextDocument, maxChapters: Int = DEFAULT_MAX_CHAPTERS): List<TextChapter> {
        if (maxChapters <= 0 || document.isEmpty) return emptyList()
        val indices = ArrayList<Int>()
        var minLevel = Int.MAX_VALUE
        val paragraphs = document.paragraphs
        for (index in paragraphs.indices) {
            val paragraph = paragraphs[index]
            if (paragraph.headingLevel <= 0) continue
            if (paragraph.text.isBlank()) continue
            indices += index
            if (paragraph.headingLevel < minLevel) minLevel = paragraph.headingLevel
        }
        if (indices.isEmpty()) return emptyList()
        val chapters = ArrayList<TextChapter>(minOf(indices.size, maxChapters))
        for (index in indices) {
            if (chapters.size >= maxChapters) break
            val paragraph = paragraphs[index]
            chapters += TextChapter(
                title = paragraph.text,
                paragraphIndex = index,
                level = (paragraph.headingLevel - minLevel + 1).coerceIn(1, MAX_LEVEL),
            )
        }
        return chapters
    }

    /**
     * [paragraphIndex] 属于哪一章：返回最后一个「锚点不晚于它」的条目。
     *
     * 第一条标题之前（前言、书名页、无标题开头）返回 null —— 那里确实没有所属章节，
     * 硬塞第一条会让「当前章节」显示成还没读到的东西。
     *
     * 二分查找：弹层滚动时每滚一段就要问一次，目录可能几百条。
     * 前提是 [chapters] 按 [TextChapter.paragraphIndex] 递增，[build] 保证这一点。
     */
    fun chapterAt(chapters: List<TextChapter>, paragraphIndex: Int): TextChapter? {
        if (chapters.isEmpty() || paragraphIndex < 0) return null
        var low = 0
        var high = chapters.size - 1
        var found: TextChapter? = null
        while (low <= high) {
            val middle = (low + high) ushr 1
            val candidate = chapters[middle]
            if (candidate.paragraphIndex <= paragraphIndex) {
                found = candidate
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return found
    }
}
