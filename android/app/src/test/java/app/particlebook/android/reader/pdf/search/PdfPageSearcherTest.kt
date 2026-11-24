package app.particlebook.android.reader.pdf.search

import app.particlebook.android.reader.pdf.PdfChar
import app.particlebook.android.reader.pdf.PdfNormalizedRect
import app.particlebook.android.reader.pdf.PdfPageText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [PdfPageSearcher] 的纯 JVM 行为：大小写、跨空白、字符索引区间、片段定位与 maxHits。 */
class PdfPageSearcherTest {

    // ---------------------------------------------------------------- 夹具

    /** 每个字符一个等宽矩形，横向排开；第 [blankIndexes] 个字符没有矩形（模拟空白）。 */
    private fun page(
        vararg texts: String,
        blankIndexes: Set<Int> = emptySet(),
        pageIndex: Int = 0,
    ): PdfPageText {
        val chars = texts.mapIndexed { position, text ->
            PdfChar(
                index = position,
                text = text,
                rect = if (position in blankIndexes) {
                    null
                } else {
                    PdfNormalizedRect(
                        left = 0.05f * position,
                        top = 0.1f,
                        width = 0.04f,
                        height = 0.02f,
                    )
                },
            )
        }
        // page.text 故意写乱：搜索必须只看 chars，不依赖它。
        return PdfPageText(
            pageIndex = pageIndex,
            pageWidthPoints = 600,
            pageHeightPoints = 800,
            rotationDegrees = 0,
            chars = chars,
            text = texts.joinToString(separator = ""),
        )
    }

    // ---------------------------------------------------------------- 基本命中

    @Test
    fun matchIsCaseInsensitive() {
        val hits = PdfPageSearcher.find(page("H", "e", "l", "l", "o"), "hello", 10)

        assertEquals(1, hits.size)
        assertEquals(0, hits[0].charStart)
        assertEquals(5, hits[0].charEnd)
        assertEquals("Hello", hits[0].snippet.substring(hits[0].matchStart, hits[0].matchEnd))
    }

    @Test
    fun multipleHitsKeepTextOrderAndCharRanges() {
        // "abAbab" 里 "ab" 出现在 0 和 2 处（非重叠），"Ab" 在位置 2 大小写不敏感命中。
        val pageText = page("a", "b", "A", "b", "a", "b")
        val hits = PdfPageSearcher.find(pageText, "ab", 10)

        assertEquals(listOf(0, 2, 4), hits.map { it.charStart })
        assertEquals(listOf(2, 4, 6), hits.map { it.charEnd })
        // 半开区间正好覆盖两个字符。
        assertEquals(listOf(2, 2, 2), hits.map { it.charEnd - it.charStart })
        assertTrue(hits.all { it.rects.isNotEmpty() })
    }

    @Test
    fun charIndexesFollowPdfCharIndexNotListPosition() {
        // PdfChar.index 由 PDFium 给定（跳过 '\u0000'），与 chars 下标不相等。
        val chars = listOf(
            PdfChar(index = 10, text = "x", rect = PdfNormalizedRect(0.1f, 0.1f, 0.04f, 0.02f)),
            PdfChar(index = 11, text = "y", rect = PdfNormalizedRect(0.2f, 0.1f, 0.04f, 0.02f)),
            PdfChar(index = 13, text = "z", rect = PdfNormalizedRect(0.3f, 0.1f, 0.04f, 0.02f)),
        )
        val pageText = PdfPageText(0, 600, 800, 0, chars, "xyz")

        val hit = PdfPageSearcher.find(pageText, "yz", 10).single()

        assertEquals(11, hit.charStart)
        assertEquals(14, hit.charEnd)
    }

    @Test
    fun hitAtPageEdgesCoversWholePage() {
        val hit = PdfPageSearcher.find(page("a", "b", "c"), "abc", 10).single()

        assertEquals(0, hit.charStart)
        assertEquals(3, hit.charEnd)
    }

    // ---------------------------------------------------------------- 跨空白

    @Test
    fun queryAcrossWhitespaceMatchesContiguousText() {
        // 页面字符是 "a b c"，用 "b c" 能命中；命中框覆盖 b、空格、c。
        val pageText = page("a", " ", "b", " ", "c", blankIndexes = setOf(1, 3))
        val hit = PdfPageSearcher.find(pageText, "b c", 10).single()

        assertEquals(2, hit.charStart)
        assertEquals(5, hit.charEnd)
        assertEquals("b c", hit.snippet.substring(hit.matchStart, hit.matchEnd))
        assertTrue(hit.hasSnippetMatch)
    }

    @Test
    fun queryWhoseWhitespaceIsWiderThanPageDoesNotMatch() {
        // 匹配是在原始字符上做的：joined 只有单个空白，"b  c" 找不到。
        val pageText = page("b", " ", "c", blankIndexes = setOf(1))

        assertTrue(PdfPageSearcher.find(pageText, "b  c", 10).isEmpty())
    }

    // ---------------------------------------------------------------- 上限与退化输入

    @Test
    fun maxHitsCapsResultCount() {
        val pageText = page("a", "b", "a", "b", "a", "b")

        assertEquals(3, PdfPageSearcher.find(pageText, "ab", 10).size)
        assertEquals(2, PdfPageSearcher.find(pageText, "ab", 2).size)
        assertEquals(listOf(0, 2), PdfPageSearcher.find(pageText, "ab", 2).map { it.charStart })
    }

    @Test
    fun pageWithoutMatchReturnsEmpty() {
        assertTrue(PdfPageSearcher.find(page("a", "b", "c"), "zz", 10).isEmpty())
        assertTrue(PdfPageSearcher.find(page("a", "b", "c"), "abcdef", 10).isEmpty())
    }

    @Test
    fun blankQueryReturnsEmpty() {
        val pageText = page("a", "b", "c")

        assertTrue(PdfPageSearcher.find(pageText, "", 10).isEmpty())
        assertTrue(PdfPageSearcher.find(pageText, "   ", 10).isEmpty())
        assertTrue(PdfPageSearcher.find(pageText, "\n\t", 10).isEmpty())
    }

    @Test
    fun nonPositiveMaxHitsReturnsEmpty() {
        val pageText = page("a", "b", "c")

        assertTrue(PdfPageSearcher.find(pageText, "abc", 0).isEmpty())
        assertTrue(PdfPageSearcher.find(pageText, "abc", -3).isEmpty())
    }

    @Test
    fun pageWithoutCharsReturnsEmpty() {
        val empty = PdfPageText(0, 600, 800, 0, emptyList(), "")

        assertTrue(PdfPageSearcher.find(empty, "a", 10).isEmpty())
    }

    // ---------------------------------------------------------------- 矩形

    @Test
    fun hitWithRectsProducesMergedRects() {
        val hit = PdfPageSearcher.find(page("a", "b", "c"), "abc", 10).single()

        // 同一行、间隙很小，PdfSelectionGeometry 会合并成一块。
        assertEquals(1, hit.rects.size)
        assertTrue(hit.rects[0].isValid)
    }

    @Test
    fun charsWithoutRectDoNotThrow() {
        val pageText = page("a", " ", " ", "b", blankIndexes = setOf(1, 2))

        // 命中整段（含无矩形空白）不抛异常，矩形只来自有 rect 的 a、b。
        val whole = PdfPageSearcher.find(pageText, "a  b", 10).single()
        assertTrue(whole.rects.isNotEmpty())
        assertEquals(0, whole.charStart)
        assertEquals(4, whole.charEnd)
    }

    // ---------------------------------------------------------------- 片段

    @Test
    fun snippetCollapsesWhitespaceIntoASingleSpace() {
        // 命中词前面夹着两个换行和一个空格（无 rect 空白），片段里折叠成单个空格；
        // 命中词在折叠后片段里的下标仍要准确。
        val pageText = page(
            "S", "e", "e", "\n", "\n", "t", "h", "e", " ", "c", "a", "t",
            blankIndexes = setOf(3, 4, 8),
        )
        val hit = PdfPageSearcher.find(pageText, "cat", 10).single()

        assertEquals("See the cat", hit.snippet)
        assertEquals("cat", hit.snippet.substring(hit.matchStart, hit.matchEnd))
        assertTrue(hit.hasSnippetMatch)
        assertEquals(9, hit.charStart)
        assertEquals(12, hit.charEnd)
    }

    @Test
    fun snippetStopsAtPageBoundaries() {
        val pageText = page("a", "b", "c")
        val hit = PdfPageSearcher.find(pageText, "bc", 10).single()

        assertEquals("abc", hit.snippet)
        assertEquals(1, hit.matchStart)
        assertEquals(3, hit.matchEnd)
    }

    @Test
    fun snippetIsCenteredAroundHitOnLongPage() {
        val body = "x".repeat(120)
        val chars = (body + "needle" + body).map { character ->
            PdfChar(0, character.toString(), PdfNormalizedRect(0.01f, 0.01f, 0.01f, 0.01f))
        }
        // index 手动递增，模拟 PDFium 顺序下标。
        val indexed = chars.mapIndexed { position, char -> char.copy(index = position) }
        val pageText = PdfPageText(0, 600, 800, 0, indexed, "")

        val hit = PdfPageSearcher.find(pageText, "NEEDLE", 10).single()

        assertEquals(PdfPageSearcher.SNIPPET_CONTEXT * 2 + "needle".length, hit.snippet.length)
        assertEquals("needle", hit.snippet.substring(hit.matchStart, hit.matchEnd))
        assertEquals(120, hit.charStart)
        assertEquals(126, hit.charEnd)
    }
}