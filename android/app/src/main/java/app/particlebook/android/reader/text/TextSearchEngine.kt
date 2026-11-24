package app.particlebook.android.reader.text

/**
 * 一条文本命中。
 *
 * [start]/[end] 是命中在**段落原文**里的字符区间（UTF-16 码元，end 不含），点结果滚到该段后
 * 用它给原文上色；[snippet]/[matchStart]/[matchEnd] 是给列表显示用的折叠片段与命中区间
 * （片段内连续空白已压成一个空格，所以三个值不能直接互换）。
 */
internal data class TextSearchHit(
    val paragraphIndex: Int,
    val start: Int,
    val end: Int,
    val snippet: String,
    val matchStart: Int,
    val matchEnd: Int,
)

/**
 * 纯文本 / Markdown 的整本搜索。
 *
 * 文本阅读器打开时整本书已经在内存里（[TextDocument]），所以这里不需要 PDF 那套逐页扫描、
 * 进度回调与取消：同步跑完、结果一次返回。代价是必须自带上限——四百万字符的 txt 里搜一个
 * 单字可能命中几十万次，列表吃不下也没有意义，默认 [DEFAULT_LIMIT] 条就停。
 *
 * 匹配口径：
 * - 忽略大小写（中文无影响，英文/数字按 Unicode 简单折叠）；
 * - 段内非重叠匹配（同一段里 "aaa" 里搜 "aa" 只算一处，与 PDF 搜索一致）；
 * - 不跨段匹配：段是排版单位，跨段命中在界面上无处高亮；
 * - 空词、纯空白词、limit <= 0 一律返回空表。
 */
internal object TextSearchEngine {

    /** 默认命中上限：足够翻完一本书的词频统计，又不会让弹层一次渲染上千行。 */
    const val DEFAULT_LIMIT: Int = 200

    /** 片段在命中两侧各留的字符数（折叠空白后会略少一点）。 */
    private const val SNIPPET_RADIUS: Int = 24

    fun search(
        document: TextDocument,
        query: String,
        limit: Int = DEFAULT_LIMIT,
    ): List<TextSearchHit> {
        val needle = query.trim()
        if (needle.isEmpty() || limit <= 0) return emptyList()
        val paragraphs = document.paragraphs
        if (paragraphs.isEmpty()) return emptyList()
        val hits = ArrayList<TextSearchHit>()
        for (index in paragraphs.indices) {
            val text = paragraphs[index].text
            if (text.isEmpty()) continue
            var from = 0
            while (from <= text.length - needle.length) {
                val start = text.indexOf(needle, startIndex = from, ignoreCase = true)
                if (start < 0) break
                val end = start + needle.length
                val snippet = buildSnippet(text, start, end)
                hits.add(
                    TextSearchHit(
                        paragraphIndex = index,
                        start = start,
                        end = end,
                        snippet = snippet.text,
                        matchStart = snippet.matchStart,
                        matchEnd = snippet.matchEnd,
                    ),
                )
                if (hits.size >= limit) return hits
                // 非重叠：从命中末尾继续；长度为零不可能（needle 非空），但仍兜底 +1 防死循环。
                from = if (end > start) end else start + 1
            }
        }
        return hits
    }

    private class Snippet(val text: String, val matchStart: Int, val matchEnd: Int)

    /**
     * 截一段上下文并把连续空白压成一个空格，同时给出命中在压缩后片段里的区间。
     *
     * 必须一次扫描同时完成压缩与定位：先压缩再 indexOf 会在「命中本身含空白」或
     * 「命中紧邻被压缩的空白」时算偏一两个字符，高亮就会压到隔壁字上。
     */
    private fun buildSnippet(text: String, start: Int, end: Int): Snippet {
        val from = safeLowerBound(text, (start - SNIPPET_RADIUS).coerceAtLeast(0))
        val to = safeUpperBound(text, (end + SNIPPET_RADIUS).coerceAtMost(text.length))
        val builder = StringBuilder(to - from)
        var matchStart = -1
        var matchEnd = -1
        var pendingSpace = false
        var index = from
        while (index < to) {
            val char = text[index]
            if (char.isWhitespace()) {
                // 片段开头的空白直接丢；中间连续空白延后成一个空格，避免出现「命中前多一个空格」。
                if (builder.isNotEmpty()) pendingSpace = true
            } else {
                if (pendingSpace) {
                    builder.append(' ')
                    pendingSpace = false
                }
                if (index == start) matchStart = builder.length
                builder.append(char)
                if (index + 1 == end) matchEnd = builder.length
            }
            index++
        }
        if (matchStart < 0) matchStart = 0
        if (matchEnd < matchStart) matchEnd = builder.length
        return Snippet(builder.toString(), matchStart, matchEnd)
    }

    /** 片段左端别切在代理对中间（否则附近有 emoji 时片段会出现孤立代理字符）。 */
    private fun safeLowerBound(text: String, offset: Int): Int {
        if (offset <= 0 || offset >= text.length) return offset
        return if (Character.isLowSurrogate(text[offset]) && Character.isHighSurrogate(text[offset - 1])) {
            offset - 1
        } else {
            offset
        }
    }

    /** 片段右端同理：切点正好落在代理对中间时多收一个码元。 */
    private fun safeUpperBound(text: String, offset: Int): Int {
        if (offset <= 0 || offset >= text.length) return offset
        return if (Character.isHighSurrogate(text[offset - 1]) && Character.isLowSurrogate(text[offset])) {
            offset + 1
        } else {
            offset
        }
    }
}
