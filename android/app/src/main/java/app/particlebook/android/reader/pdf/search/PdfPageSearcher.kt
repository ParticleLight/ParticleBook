package app.particlebook.android.reader.pdf.search

import app.particlebook.android.reader.pdf.PdfChar
import app.particlebook.android.reader.pdf.PdfPageText
import app.particlebook.android.reader.pdf.PdfSelectionGeometry

/**
 * 单页文本搜索。
 *
 * 为什么不在 [PdfPageText.text] 上直接搜：page.text 是 PDFium 自己抽取的整页字符串，
 * 它和 chars 列表并不逐字符对应。PdfChar.index 是 PDFium 的原始字符下标
 * （抽取时跳过 '\u0000'，所以 index 不等于 chars 的下标），而高亮矩形只能靠
 * PdfChar.index 才切得准；一旦在 page.text 上命中，命中位置就无法可靠地换算回
 * PdfChar.index。所以这里以 chars 为唯一文本源：按顺序拼出 joined，并用 IntArray
 * 记录 joined 中每个字符位置对应的 PdfChar.index，命中位置由此直接落回字符索引区间。
 *
 * 纯函数：无副作用、不依赖 Android、不做 Locale 相关的大小写转换（交给 indexOf(ignoreCase)）。
 */
object PdfPageSearcher {

    /** 片段在命中词两侧各保留的 joined 字符数。 */
    const val SNIPPET_CONTEXT: Int = 40

    /** 需要折叠掉的空白（与 Java 正则 "\s" 等价，不含 Unicode 空格）：空格、制表、换行等。 */
    private fun isWhitespace(char: Char): Boolean =
        char == ' ' || char == '\t' || char == '\n' || char == '\u000B' || char == '\u000C' || char == '\r'

    /**
     * 在 [page] 中大小写不敏感、不重叠地查找 [query]，最多返回 [maxHits] 条命中。
     * 命中按页内出现顺序排列；query 空白或 maxHits <= 0 时返回空列表。
     */
    fun find(page: PdfPageText, query: String, maxHits: Int): List<PdfSearchHit> {
        if (query.isBlank() || maxHits <= 0) return emptyList()

        val chars = page.chars
        if (chars.isEmpty()) return emptyList()
        val joined = joinChars(chars)
        val haystack = joined.text
        if (query.length > haystack.length) return emptyList()

        val hits = ArrayList<PdfSearchHit>()
        // 下一次从上一次命中之后继续，保证不重叠；步长至少为 1，避免死循环。
        val step = query.length.coerceAtLeast(1)
        var from = 0
        while (hits.size < maxHits && from <= haystack.length - step) {
            val start = haystack.indexOf(query, from, ignoreCase = true)
            if (start < 0) break
            val end = start + query.length
            hits.add(buildHit(page, joined, start, end))
            from = start + step
        }
        return hits
    }

    /** joined 文本 + 每个字符位置对应的 PdfChar.index（与 joined 等长）。 */
    private class JoinedChars(val text: String, val indexes: IntArray)

    /**
     * 按 chars 顺序拼接出可搜索文本。rect == null 的空白字符照样参与拼接，
     * 只是最后算矩形时会被 PdfSelectionGeometry 跳过——这样跨空白（跨行）的命中
     * 仍然能对上字符索引。
     */
    private fun joinChars(chars: List<PdfChar>): JoinedChars {
        var total = 0
        for (char in chars) total += char.text.length
        val builder = StringBuilder(total)
        val indexes = IntArray(total)
        var position = 0
        for (char in chars) {
            val text = char.text
            builder.append(text)
            for (offset in text.indices) {
                indexes[position++] = char.index
            }
        }
        return JoinedChars(builder.toString(), indexes)
    }

    /** 把 joined 的 [start, end) 命中换算成 PdfSearchHit：字符索引、矩形、片段。 */
    private fun buildHit(
        page: PdfPageText,
        joined: JoinedChars,
        start: Int,
        end: Int,
    ): PdfSearchHit {
        // 首字符取命中首位的 index，末字符取命中末位的 index + 1，构成半开区间。
        val charStart = joined.indexes[start]
        val charEnd = joined.indexes[end - 1] + 1
        val rects = PdfSelectionGeometry.rects(page.chars, charStart, charEnd)

        val windowStart = (start - SNIPPET_CONTEXT).coerceAtLeast(0)
        val windowEnd = (end + SNIPPET_CONTEXT).coerceAtMost(joined.text.length)
        val snippet = buildSnippet(joined.text, windowStart, windowEnd, start, end)
        return PdfSearchHit(
            page = page.pageIndex,
            charStart = charStart,
            charEnd = charEnd,
            snippet = snippet.text,
            matchStart = snippet.matchStart,
            matchEnd = snippet.matchEnd,
            rects = rects,
        )
    }

    /** 折叠后的片段 + 命中词在片段内的半开区间；算不出时为 -1/-1。 */
    private class Snippet(val text: String, val matchStart: Int, val matchEnd: Int)

    /**
     * 截取 joined 的 [windowStart, windowEnd) 作为原始片段，把连续空白折叠成单个空格，
     * 同时记录折叠后每个输出字符对应的原始下标，再用原始命中的起止位置反查回来。
     *
     * 命中词本身即使跨空白（例如 "b  c"），折叠后仍然连续；只要还剩下命中词的
     * 首字符和末字符，反查就能得到准确覆盖折叠后命中词的下标。query 空白在 [find] 入口就
     * 被挡掉，所以"命中词整段都是空白"实际不可达；这里仍保留 -1/-1 兜底，保证不抛异常。
     * 另外，折叠后的下标是"合并后占位符"语义：命中词跨多字符空白的极端情况下，
     * matchEnd 可能比折叠后命中词的实际长度少覆盖几个字符——宁可少加粗，也不越界。
     */
    private fun buildSnippet(
        joined: String,
        windowStart: Int,
        windowEnd: Int,
        hitStart: Int,
        hitEnd: Int,
    ): Snippet {
        val folded = StringBuilder(windowEnd - windowStart)
        val sourceIndexes = ArrayList<Int>(windowEnd - windowStart)
        var position = windowStart
        while (position < windowEnd) {
            val char = joined[position]
            if (!isWhitespace(char)) {
                folded.append(char)
                sourceIndexes.add(position)
                position++
                continue
            }
            // 连续空白整体折叠成一个空格，映射到这段空白的首个字符。
            var runEnd = position + 1
            while (runEnd < windowEnd && isWhitespace(joined[runEnd])) runEnd++
            if (runEnd - position > 1) {
                folded.append(' ')
                sourceIndexes.add(position)
            } else {
                folded.append(char)
                sourceIndexes.add(position)
            }
            position = runEnd
        }

        var firstHit = -1
        var lastHit = -1
        for (slot in sourceIndexes.indices) {
            val source = sourceIndexes[slot]
            if (source < hitStart || source >= hitEnd) continue
            if (firstHit < 0) firstHit = slot
            lastHit = slot
        }
        if (firstHit < 0) return Snippet(folded.toString(), -1, -1)
        return Snippet(folded.toString(), firstHit, lastHit + 1)
    }
}