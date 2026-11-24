package app.particlebook.android.reader.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TextSearchEngine] 的纯 JVM 单测：夹具一律走 [TextDocumentParser.parse]，
 * 不手拼 [TextDocument]（charCount 只有 bindCharCount 才有效）。
 *
 * 覆盖契约（见 TextSearchEngine 的类注释）：忽略大小写、段内非重叠、不跨段、
 * 空词 / limit <= 0 返回空表，以及两条易碎点——start/end 能回读原文，
 * snippet 压缩空白后 matchStart/matchEnd 仍然指向命中本身。
 */
internal class TextSearchEngineTest {

    /** UTF-16 里 emoji 是代理对，源码里一律用 \uD83D\uDE00 转义，避免依赖文件编码。 */
    private val emoji: String = "\uD83D\uDE00"

    private fun parse(raw: String, markdown: Boolean = false): TextDocument =
        TextDocumentParser.parse(raw, markdown = markdown)

    private fun paragraphText(document: TextDocument, hit: TextSearchHit): String =
        document.paragraphs[hit.paragraphIndex].text

    /** 母段夹具：四段，大小写混排，便于反复断言回读。 */
    private fun mixedCaseDocument(markdown: Boolean): TextDocument = parse(
        "Alpha beta GAMMA\n\nbeta alpha\n\nGamma BETA\n\n无命中的一段",
        markdown = markdown,
    )

    @Test
    fun emptyQueryReturnsEmptyList() {
        val document = parse("hello world\n\n第二段 hello")
        assertTrue(TextSearchEngine.search(document, "").isEmpty())
        assertTrue(TextSearchEngine.search(document, "", limit = 10).isEmpty())
    }

    @Test
    fun whitespaceOnlyQueryReturnsEmptyList() {
        val document = parse("hello world")
        assertTrue(TextSearchEngine.search(document, "   ").isEmpty())
        assertTrue(TextSearchEngine.search(document, " \t\n\r ").isEmpty())
    }

    @Test
    fun nonPositiveLimitReturnsEmptyList() {
        val document = parse("关键词 若干 关键词")
        assertTrue(TextSearchEngine.search(document, "关键词", limit = 0).isEmpty())
        assertTrue(TextSearchEngine.search(document, "关键词", limit = -1).isEmpty())
    }

    @Test
    fun twoHitsInOneParagraphKeepSameIndexAndAscendingStart() {
        val document = parse("关键词 中间 关键词")
        val hits = TextSearchEngine.search(document, "关键词")
        assertEquals(2, hits.size)
        assertEquals(0, hits[0].paragraphIndex)
        assertEquals(0, hits[0].paragraphIndex)
        assertEquals(0, hits[0].start)
        assertEquals(3, hits[0].end)
        assertEquals(7, hits[1].start)
        assertEquals(10, hits[1].end)
        assertTrue(hits[1].start > hits[0].start)
    }

    @Test
    fun englishMatchIgnoresCase() {
        val document = parse("Hello hello HELLO")
        val hits = TextSearchEngine.search(document, "hello")
        assertEquals(3, hits.size)
        assertEquals(listOf(0, 6, 12), hits.map { it.start })
        assertEquals(listOf(5, 11, 17), hits.map { it.end })
        assertTrue(hits.all { it.paragraphIndex == 0 })
    }

    @Test
    fun matchesInsideParagraphAreNonOverlapping() {
        val document = parse("aaaa")
        val hits = TextSearchEngine.search(document, "aa")
        assertEquals(2, hits.size)
        assertEquals(listOf(0, 2), hits.map { it.start })
        assertEquals(listOf(2, 4), hits.map { it.end })
    }

    @Test
    fun queryNeverSpansParagraphBoundary() {
        val document = parse("hello\n\nworld")
        assertEquals(listOf("hello", "world"), document.paragraphs.map { it.text })
        assertTrue(TextSearchEngine.search(document, "hello world").isEmpty())
    }

    @Test
    fun paragraphIndexIsReportedForEachHitParagraph() {
        val document = parse(
            "第一段 关键词\n\n第二段 无\n\n第三段 关键词\n\n第四段 无\n\n第五段 无\n\n第六段 关键词",
        )
        val hits = TextSearchEngine.search(document, "关键词")
        assertEquals(3, hits.size)
        assertEquals(listOf(0, 2, 5), hits.map { it.paragraphIndex })
    }

    @Test
    fun startAndEndReadBackOriginalParagraphText() {
        val document = mixedCaseDocument(markdown = false)
        val hits = TextSearchEngine.search(document, "beta")
        assertEquals(3, hits.size)
        for (hit in hits) {
            val original = paragraphText(document, hit).substring(hit.start, hit.end)
            assertTrue("回读结果应为命中原文：$original", original.equals("beta", ignoreCase = true))
        }
    }

    @Test
    fun startAndEndReadBackInMarkdownMode() {
        val document = mixedCaseDocument(markdown = true)
        val hits = TextSearchEngine.search(document, "GAMMA")
        assertEquals(2, hits.size)
        for (hit in hits) {
            val original = paragraphText(document, hit).substring(hit.start, hit.end)
            assertTrue("回读结果应为命中原文：$original", original.equals("GAMMA", ignoreCase = true))
        }
    }

    @Test
    fun snippetMatchRangePointsAtQuery() {
        val document = mixedCaseDocument(markdown = false)
        val hits = TextSearchEngine.search(document, "beta")
        assertEquals(3, hits.size)
        for (hit in hits) {
            val matched = hit.snippet.substring(hit.matchStart, hit.matchEnd)
            assertTrue("片段命中区间应为查询词：$matched", matched.equals("beta", ignoreCase = true))
        }
    }

    @Test
    fun snippetContextIsBoundedInsideLongParagraph() {
        val body = "中".repeat(60)
        val text = "头" + body + "关键词" + body + "尾"
        val document = parse(text)
        val hits = TextSearchEngine.search(document, "关键词")
        assertEquals(1, hits.size)
        val hit = hits[0]
        assertEquals(61, hit.start)
        assertTrue("片段应短于整段：${hit.snippet.length} vs ${text.length}", hit.snippet.length < text.length)
        assertFalse("片段不该带上段首字符", hit.snippet.contains("头"))
        assertFalse("片段不该带上段尾字符", hit.snippet.contains("尾"))
        assertTrue(hit.snippet.contains("关键词"))
    }

    @Test
    fun snippetCompressesConsecutiveWhitespace() {
        // 围栏内的空行不算段分隔，才能得到「甲  \n\n  乙」这种段内连续空白。
        val fence = "\u0060\u0060\u0060"
        val raw = fence + "\n甲  \n\n  乙\n" + fence
        val document = parse(raw, markdown = true)
        assertEquals(1, document.paragraphs.size)
        assertTrue(document.paragraphs[0].text.contains("甲"))
        assertTrue(document.paragraphs[0].text.contains("乙"))

        val hits = TextSearchEngine.search(document, "乙")
        assertEquals(1, hits.size)
        val hit = hits[0]
        assertFalse("片段里不该残留连续空白：${hit.snippet}", hit.snippet.contains("  "))
        assertFalse("片段里不该残留换行：${hit.snippet}", hit.snippet.contains("\n"))
        assertTrue("压缩后应只剩一个空格：${hit.snippet}", hit.snippet.contains("甲 乙"))
        assertEquals("乙", hit.snippet.substring(hit.matchStart, hit.matchEnd))
    }

    @Test
    fun limitTruncatesHitsInDocumentOrder() {
        val document = parse("目标甲 目标乙\n\n目标丙 目标丁\n\n目标戊 目标己")
        val all = TextSearchEngine.search(document, "目标")
        assertEquals(6, all.size)

        val limited = TextSearchEngine.search(document, "目标", limit = 2)
        assertEquals(2, limited.size)
        assertEquals(listOf(0, 0), limited.map { it.paragraphIndex })
        assertEquals(listOf(all[0].start, all[1].start), limited.map { it.start })
    }

    @Test
    fun snippetKeepsSurrogatePairsAroundEmoji() {
        val document = parse("$emoji$emoji 关键词 $emoji$emoji")
        val hits = TextSearchEngine.search(document, "关键词")
        assertEquals(1, hits.size)
        val hit = hits[0]
        assertEquals(5, hit.start)
        assertEquals(8, hit.end)
        assertEquals("关键词", hit.snippet.substring(hit.matchStart, hit.matchEnd))
        assertNoLoneSurrogate(hit.snippet)
    }

    @Test
    fun snippetBoundariesDoNotSplitSurrogatePairs() {
        // 让片段左右切点正好落在代理对中间，验证 safeLowerBound / safeUpperBound。
        val raw = emoji.repeat(20) + " 关键词 " + emoji.repeat(20)
        val document = parse(raw)
        val hits = TextSearchEngine.search(document, "关键词")
        assertEquals(1, hits.size)
        val hit = hits[0]
        assertEquals(41, hit.start)
        assertTrue("片段应被裁剪但包含命中词", hit.snippet.contains("关键词"))
        assertEquals("关键词", hit.snippet.substring(hit.matchStart, hit.matchEnd))
        assertNoLoneSurrogate(hit.snippet)
    }

    @Test
    fun emptyDocumentReturnsEmptyList() {
        val document = parse("")
        assertTrue(document.isEmpty)
        assertTrue(TextSearchEngine.search(document, "任意").isEmpty())
        assertTrue(TextSearchEngine.search(document, "任意", limit = 5).isEmpty())
    }

    @Test
    fun whitespaceOnlyDocumentReturnsEmptyList() {
        val document = parse("  \n\n\t\n   ")
        assertTrue(document.isEmpty)
        assertEquals(0, document.paragraphs.size)
        assertTrue(TextSearchEngine.search(document, "任意").isEmpty())
    }

    @Test
    fun hitAtParagraphStartKeepsFullSnippet() {
        val document = parse("关键词后面还有很长的一串上下文用来把片段撑到上限之后再说")
        val hits = TextSearchEngine.search(document, "关键词")
        assertEquals(1, hits.size)
        val hit = hits[0]
        assertEquals(0, hit.start)
        assertEquals(0, hit.matchStart)
        assertEquals("关键词", hit.snippet.substring(hit.matchStart, hit.matchEnd))
        assertTrue(hit.snippet.startsWith("关键词"))
    }

    @Test
    fun hitAtParagraphEndTrimsSnippetButKeepsMatch() {
        val text = "这是一段很长的铺垫文字用来把片段的左端撑开然后才是关键词"
        val document = parse(text)
        val hits = TextSearchEngine.search(document, "关键词")
        assertEquals(1, hits.size)
        val hit = hits[0]
        assertEquals(text.length, hit.end)
        // 命中贴段尾：右侧没得补，matchEnd 正好落在片段末尾。
        assertEquals(hit.snippet.length, hit.matchEnd)
        assertEquals("关键词", hit.snippet.substring(hit.matchStart, hit.matchEnd))
        assertTrue(hit.snippet.endsWith("关键词"))
        assertTrue("片段应短于整段：${hit.snippet.length} vs ${text.length}", hit.snippet.length < text.length)
        assertFalse("片段左端应被裁掉段首字符", hit.snippet.startsWith("这"))
    }

    @Test
    fun querySurroundingSpacesAreTrimmed() {
        val document = parse("hello world")
        val dirty = TextSearchEngine.search(document, "  hello  ")
        val clean = TextSearchEngine.search(document, "hello")
        assertEquals(1, dirty.size)
        assertEquals(clean.map { it.start to it.end }, dirty.map { it.start to it.end })
        assertEquals("hello", dirty[0].snippet.substring(dirty[0].matchStart, dirty[0].matchEnd))
    }

    /** 逐码元断言片段里没有孤立代理字符：高位后面必须跟低位，低位前面必须有高位。 */
    private fun assertNoLoneSurrogate(snippet: String) {
        var index = 0
        while (index < snippet.length) {
            val char = snippet[index]
            if (Character.isHighSurrogate(char)) {
                val paired = index + 1 < snippet.length && Character.isLowSurrogate(snippet[index + 1])
                assertTrue("片段第 $index 位是孤立高位代理：$snippet", paired)
                index += 2
                continue
            }
            assertFalse("片段第 $index 位是孤立低位代理：$snippet", Character.isLowSurrogate(char))
            index++
        }
    }
}
