package app.particlebook.android.reader.tts

/**
 * 朗读用的句内区间：字符偏移以块文本为基准，endChar 为 exclusive。
 * 只描述"在哪"，不带文本，便于与高亮/选区复用同一套坐标。
 */
internal data class TtsSentenceSpan(val startChar: Int, val endChar: Int) {
    val length: Int get() = (endChar - startChar).coerceAtLeast(0)
}

/**
 * 句子切分（纯 JVM，便于单测）。
 *
 * 规则：
 * 1. 句末标点（。！？；…!?;）后切开；连续标点（？！、……）合成一个边界。
 * 2. 英文句点只在"后面是空白或文本结尾、且下一段不是小写字母"时切开，避免拆掉 "3.14"、
 *    "Mr. Smith" 这类片段；"U.S.A."、"Ph.D." 这种带点缩写链收尾时按真句尾切开。
 * 3. 句末标点后紧跟的收尾引号/括号（”’」』）纳入本句。
 * 4. 换行本身是句边界（EPUB 的块内换行通常就是段落内的硬断行）。
 * 5. 只含标点/符号的片段（例如单独一段的"……"）直接丢弃：送去合成只会念出符号名或拖出长停顿。
 * 6. 返回区间已去掉首尾空白；NBSP(U+00A0)、figure space(U+2007)、narrow no-break space(U+202F)
 *    也算空白——Character.isWhitespace 不认它们，但书里常拿它们排"看不见的硬空格"。
 */
internal object TtsSentenceSplitter {

    private const val TERMINATORS = "。！？!?…"
    private const val SECONDARY = "；;"
    private const val CLOSERS = "”’\"'」』）)】》"

    /** 排版用的不换行空格：视觉上是空白，Character.isWhitespace 却判 false。 */
    private const val NO_BREAK_SPACES = "\u00A0\u2007\u202F"

    fun split(text: String): List<TtsSentenceSpan> {
        if (text.isEmpty()) return emptyList()
        val spans = ArrayList<TtsSentenceSpan>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            val boundary = when {
                ch == '\n' -> i + 1
                ch in TERMINATORS || ch in SECONDARY -> terminatorBoundary(text, i)
                ch == '.' -> periodBoundary(text, i)
                else -> -1
            }
            if (boundary > 0) {
                emit(text, start, boundary, spans)
                start = boundary
                i = boundary
                continue
            }
            i++
        }
        emit(text, start, text.length, spans)
        return spans
    }

    /** 句末标点：吞掉同类连写与收尾引号，得到下一句的起点。 */
    private fun terminatorBoundary(text: String, index: Int): Int {
        var i = index + 1
        while (i < text.length && (text[i] in TERMINATORS || text[i] in SECONDARY || text[i] == '.')) i++
        while (i < text.length && text[i] in CLOSERS) i++
        return i
    }

    /**
     * 英文句点：后面必须是空白或文本结尾，且该句点不属于缩写时才切开。
     * 小数（"3.14"）由"后继必须是空白"这一条覆盖：数字之间的句点后面永远紧跟数字，
     * 不是空白，所以不必再单独判小数。
     *
     * 「后面是小写字母就不切」**只对带点缩写链**（"the U.S.A. is big"）成立：
     * 普通词收尾的句点就是真句尾，"ab. cd." 必须切成两句。把这条规则放宽到所有词，
     * 会把整段英文吞成一句（PdfTtsPageTest.sentenceRectsCoverItsCharacters 抓到的回归）。
     */
    private fun periodBoundary(text: String, index: Int): Int {
        val next = text.getOrNull(index + 1)
        if (next != null && !isBlankish(next)) return -1
        if (wordRangeBefore(text, index) != null && isAbbreviation(text, index)) return -1
        if (isDottedInitialismTail(text, index) && continuesWithLowercase(text, index + 1)) return -1
        var i = index + 1
        while (i < text.length && text[i] in CLOSERS) i++
        return i
    }

    /**
     * 句点后面跳过空白若是小写字母，说明句子还没说完。
     * 例："the U.S.A. is big"：这个句点只是缩写链的收尾，切开反而打断朗读。
     */
    private fun continuesWithLowercase(text: String, from: Int): Boolean {
        var i = from
        while (i < text.length && isBlankish(text[i])) i++
        val ch = text.getOrNull(i) ?: return false
        return ch.isLowerCase()
    }

    /** 当前句点是否是 "U.S.A." 式带点缩写链的最后一节。 */
    private fun isDottedInitialismTail(text: String, periodIndex: Int): Boolean {
        val start = wordRangeBefore(text, periodIndex)?.first ?: return false
        return isDottedInitialism(text, start)
    }

    /**
     * 该句点是否属于缩写（属于就不切）。
     *
     * - 常见缩写词（Mr/Dr/etc…）与单个大写字母（"J. K. Rowling" 的首字母）算缩写。
     * - "U.S.A."、"Ph.D." 这种用句点连起来、且中间没有空格的缩写链，出现在句末时是真句尾，
     *   按非缩写处理：`He lives in the U.S.A. She left.` 切成两句。
     * - 两字母大写（"Wa."）不享受豁免：判断面保持窄，不靠"看起来像缩写"去猜。
     */
    private fun isAbbreviation(text: String, periodIndex: Int): Boolean {
        val (wordStart, word) = wordRangeBefore(text, periodIndex) ?: return false
        if (isDottedInitialism(text, wordStart)) return false
        if (word.length == 1 && word[0].isUpperCase()) return true
        return word.lowercase() in ABBREVIATIONS
    }

    /** 句点左边紧邻的连续字母段（start 与内容）；左边不是字母时返回 null（例如 "abc." 里 index=3 仍返回 "abc"）。 */
    private fun wordRangeBefore(text: String, periodIndex: Int): Pair<Int, String>? {
        var start = periodIndex - 1
        while (start >= 0 && text[start].isLetter()) start--
        start++
        if (start >= periodIndex) return null
        return start to text.substring(start, periodIndex)
    }

    /** 词首左边紧邻「字母.」时，这个词就是 "U.S.A." 式带点缩写链的最后一节。 */
    private fun isDottedInitialism(text: String, wordStart: Int): Boolean =
        wordStart >= 2 && text[wordStart - 1] == '.' && text[wordStart - 2].isLetter()

    /**
     * 收一段候选句子：去掉首尾空白后入表。
     * 不含任何字母/汉字/数字的片段（"……"、"？！"、"——"）直接丢弃，免得合成器念符号名。
     */
    private fun emit(text: String, from: Int, to: Int, out: MutableList<TtsSentenceSpan>) {
        var start = from
        var end = to
        while (start < end && isBlankish(text[start])) start++
        while (end > start && isBlankish(text[end - 1])) end--
        if (end <= start) return
        if (!hasSpeakableChar(text, start, end)) return
        out += TtsSentenceSpan(start, end)
    }

    /** 片段里至少有一个可发音字符（汉字按 letter 计）。 */
    private fun hasSpeakableChar(text: String, from: Int, to: Int): Boolean {
        for (i in from until to) {
            val ch = text[i]
            if (ch.isLetterOrDigit()) return true
        }
        return false
    }

    /** 空白判定：isWhitespace 之外还算上 NBSP 系列（见 NO_BREAK_SPACES）。 */
    private fun isBlankish(ch: Char): Boolean = ch.isWhitespace() || ch in NO_BREAK_SPACES

    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc", "eg", "ie", "fig", "no",
    )
}