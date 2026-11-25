package app.particlebook.android.reader.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class TextDocumentParserTest {

    private fun texts(document: TextDocument): List<String> = document.paragraphs.map { it.text }

    private fun levels(document: TextDocument): List<Int> = document.paragraphs.map { it.headingLevel }

    @Test
    fun parseEmptyInputGivesEmptyDocument() {
        val document = TextDocumentParser.parse("", markdown = false)
        assertEquals(0, document.paragraphs.size)
        assertEquals(0, document.charOffsets.size)
        assertEquals(0, document.charCount)
        assertTrue(document.isEmpty)
    }

    @Test
    fun parseWhitespaceOnlyInputKeepsNormalizedLength() {
        val raw = "  \n\n\t\r\n"
        val document = TextDocumentParser.parse(raw, markdown = false)
        assertTrue(document.isEmpty)
        assertEquals(0, document.paragraphs.size)
        assertEquals(0, document.charOffsets.size)
        // CRLF 归一化后为 "  \n\n\t\n"，共 6 个字符。
        assertEquals(6, document.charCount)
    }

    @Test
    fun parseSeparatesParagraphsOnBlankLines() {
        val raw = "第一段\n\n第二段\n\n\n第三段"
        val document = TextDocumentParser.parse(raw, markdown = false)
        assertEquals(listOf("第一段", "第二段", "第三段"), texts(document))
        assertEquals(0, document.charOffsets[0])
        assertEquals(5, document.charOffsets[1])
        assertEquals(11, document.charOffsets[2])
        assertEquals(raw.length, document.charCount)
        assertFalse(document.isEmpty)
    }

    @Test
    fun parseTreatsWhitespaceOnlyLinesAsSeparators() {
        val raw = "甲\n   \n乙"
        val document = TextDocumentParser.parse(raw, markdown = false)
        assertEquals(listOf("甲", "乙"), texts(document))
        assertEquals(listOf(0, 6), document.charOffsets.toList())
        assertEquals(raw.length, document.charCount)
    }

    @Test
    fun parseCollapsesSoftWrapsInTxt() {
        val document = TextDocumentParser.parse("第一行\n第二行\n第三行", markdown = false)
        assertEquals(1, document.paragraphs.size)
        assertEquals("第一行 第二行 第三行", document.paragraphs[0].text)
        assertEquals(0, document.paragraphs[0].headingLevel)
    }

    @Test
    fun parseTxtKeepsInnerSpacesAndOnlyFoldsNewlines() {
        val document = TextDocumentParser.parse("第一行  第二行\n第三行", markdown = false)
        assertEquals("第一行  第二行 第三行", document.paragraphs[0].text)
    }

    @Test
    fun parseTxtDropsTrailingWhitespaceBeforeFoldingSoftWraps() {
        // 定长折行的 txt 常在行尾留空格 / 制表符：折叠前先去掉，否则句中断出宽间隙。
        val document = TextDocumentParser.parse("第一行   \n第二行\t\n第三行", markdown = false)
        assertEquals(1, document.paragraphs.size)
        assertEquals("第一行 第二行 第三行", document.paragraphs[0].text)
        // 段内空格仍然保留（不是全量压空白）。
        val inner = TextDocumentParser.parse("甲  乙\n\n丙", markdown = false)
        assertEquals("甲  乙", inner.paragraphs[0].text)
    }

    @Test
    fun parsePreservesNewlinesInMarkdown() {
        val document = TextDocumentParser.parse("第一行\n第二行", markdown = true)
        assertEquals(1, document.paragraphs.size)
        assertEquals("第一行\n第二行", document.paragraphs[0].text)
        assertEquals(0, document.paragraphs[0].headingLevel)
    }

    @Test
    fun parseDetectsHeadingLevelsOneToSix() {
        val raw = "# 一级\n\n###### 六级\n\n### 三级\n\n##\t二级制表符"
        val document = TextDocumentParser.parse(raw, markdown = true)
        assertEquals(listOf(1, 6, 3, 2), levels(document))
        assertEquals(listOf("一级", "六级", "三级", "二级制表符"), texts(document))
    }

    @Test
    fun parseDoesNotTreatHashWithoutSpaceAsHeading() {
        val raw = "#没有空格\n\n####### 七个井号"
        val document = TextDocumentParser.parse(raw, markdown = true)
        assertEquals(listOf(0, 0), levels(document))
        assertEquals(listOf("#没有空格", "####### 七个井号"), texts(document))
    }

    @Test
    fun parseHeadingKeepsContinuationLines() {
        val document = TextDocumentParser.parse("## 标题\n续行一\n续行二", markdown = true)
        assertEquals(1, document.paragraphs.size)
        assertEquals(2, document.paragraphs[0].headingLevel)
        assertEquals("标题\n续行一\n续行二", document.paragraphs[0].text)
    }

    @Test
    fun parseIgnoresHashLinesInsideBacktickFence() {
        val raw = "\u0060\u0060\u0060\n# 不是标题\n\u0060\u0060\u0060"
        val document = TextDocumentParser.parse(raw, markdown = true)
        assertEquals(1, document.paragraphs.size)
        assertEquals(0, document.paragraphs[0].headingLevel)
        assertEquals(raw, document.paragraphs[0].text)
    }

    @Test
    fun parseLeavesFenceAfterClosingMarker() {
        val raw = "\u0060\u0060\u0060\n# code\n\u0060\u0060\u0060\n\n# 真标题"
        val document = TextDocumentParser.parse(raw, markdown = true)
        assertEquals(2, document.paragraphs.size)
        assertEquals(0, document.paragraphs[0].headingLevel)
        assertEquals("\u0060\u0060\u0060\n# code\n\u0060\u0060\u0060", document.paragraphs[0].text)
        assertEquals(1, document.paragraphs[1].headingLevel)
        assertEquals("真标题", document.paragraphs[1].text)
        assertEquals(raw.indexOf("# 真标题"), document.charOffsets[1])
    }

    @Test
    fun parseIgnoresHashLinesInsideTildeFence() {
        val raw = "~~~\n# 注释\n~~~"
        val document = TextDocumentParser.parse(raw, markdown = true)
        assertEquals(1, document.paragraphs.size)
        assertEquals(0, document.paragraphs[0].headingLevel)
        assertEquals(raw, document.paragraphs[0].text)
    }

    @Test
    fun parseKeepsBlankLinesInsideFenceInSameParagraph() {
        val raw = "\u0060\u0060\u0060\na\n\nb\n\u0060\u0060\u0060"
        val document = TextDocumentParser.parse(raw, markdown = true)
        assertEquals(1, document.paragraphs.size)
        assertEquals(raw, document.paragraphs[0].text)
    }

    @Test
    fun parseTxtDoesNotHonourMarkdownFenceOrHeading() {
        val raw = "\u0060\u0060\u0060\n# 标题\n\u0060\u0060\u0060"
        val document = TextDocumentParser.parse(raw, markdown = false)
        assertEquals(1, document.paragraphs.size)
        assertEquals(0, document.paragraphs[0].headingLevel)
        assertEquals("\u0060\u0060\u0060 # 标题 \u0060\u0060\u0060", document.paragraphs[0].text)
    }

    @Test
    fun parseCharOffsetsAreMonotonicAndMatchFirstLine() {
        val raw = "第一段开头\n第二行\n\n第二段\n\n第三段行一\n第三段行二"
        val document = TextDocumentParser.parse(raw, markdown = false)
        assertEquals(3, document.paragraphs.size)
        var previous = -1
        for (index in document.charOffsets.indices) {
            val offset = document.charOffsets[index]
            assertTrue("offset $index should advance", offset > previous)
            assertTrue("offset $index inside text", offset < document.charCount)
            previous = offset
        }
        assertEquals(0, document.charOffsets[0])
        assertEquals(raw.indexOf("第二段"), document.charOffsets[1])
        assertEquals(raw.indexOf("第三段行一"), document.charOffsets[2])
        assertEquals(raw.length, document.charCount)
        assertEquals("第二段", raw.substring(document.charOffsets[1], document.charOffsets[1] + 3))
    }

    @Test
    fun parseNormalizesCrlfBeforeComputingOffsets() {
        val raw = "第一段\r\n\r\n第二段"
        val document = TextDocumentParser.parse(raw, markdown = false)
        assertEquals(listOf("第一段", "第二段"), texts(document))
        assertEquals(listOf(0, 5), document.charOffsets.toList())
        assertEquals(8, document.charCount)
    }

    @Test
    fun parseNormalizesLoneCrBeforeComputingOffsets() {
        // "a\r\rb" 归一后是 "a\n\nb"：孤立 CR 变成 LF，仍然是段分隔。
        val document = TextDocumentParser.parse("a\r\rb", markdown = false)
        assertEquals(listOf("a", "b"), texts(document))
        assertEquals(listOf(0, 3), document.charOffsets.toList())
        assertEquals(4, document.charCount)
    }

    @Test
    fun parseMarkdownKeepsLoneCrAsLineBreak() {
        val document = TextDocumentParser.parse("a\rb", markdown = true)
        assertEquals(1, document.paragraphs.size)
        assertEquals("a\nb", document.paragraphs[0].text)
        assertEquals(3, document.charCount)
    }

    @Test
    fun parseDropsTrailingBlankLines() {
        val raw = "正文\n\n\n"
        val document = TextDocumentParser.parse(raw, markdown = false)
        assertEquals(1, document.paragraphs.size)
        assertEquals("正文", document.paragraphs[0].text)
        assertEquals(raw.length, document.charCount)
    }

    @Test
    fun parseMarkdownTrimsParagraphButKeepsOffsetAtFirstLineStart() {
        val raw = "\n\n   缩进正文   \n\n"
        val document = TextDocumentParser.parse(raw, markdown = true)
        assertEquals(1, document.paragraphs.size)
        assertEquals("缩进正文", document.paragraphs[0].text)
        assertEquals(2, document.charOffsets[0])
    }

    @Test
    fun fractionRoundTripHoldsForEveryParagraph() {
        val raw = "甲\n\n乙\n\n丙丁\n\n戊"
        val document = TextDocumentParser.parse(raw, markdown = false)
        assertEquals(4, document.paragraphs.size)
        assertEquals(listOf("甲", "乙", "丙丁", "戊"), texts(document))
        assertEquals(listOf(0, 3, 6, 10), document.charOffsets.toList())
        assertEquals(11, document.charCount)
        for (index in document.paragraphs.indices) {
            val fraction = TextDocumentParser.fractionForParagraph(document, index)
            assertEquals("round trip at $index", index, TextDocumentParser.paragraphIndexForFraction(document, fraction))
        }
    }

    @Test
    fun fractionRoundTripHoldsOnLongDocument() {
        val builder = StringBuilder()
        for (index in 0 until 220) {
            if (index > 0) builder.append("\n\n")
            builder.append("第").append(index).append("段")
            builder.append("正文内容".repeat(index % 6 + 1))
        }
        val document = TextDocumentParser.parse(builder.toString(), markdown = false)
        assertEquals(220, document.paragraphs.size)
        for (index in document.paragraphs.indices) {
            val fraction = TextDocumentParser.fractionForParagraph(document, index)
            assertEquals("round trip at $index", index, TextDocumentParser.paragraphIndexForFraction(document, fraction))
        }
    }

    @Test
    fun fractionForParagraphClampsOutOfRangeIndex() {
        val document = TextDocumentParser.parse("段落一\n\n段落二\n\n段落三", markdown = false)
        assertEquals(0f, TextDocumentParser.fractionForParagraph(document, -7), 0f)
        assertEquals(10f / 13f, TextDocumentParser.fractionForParagraph(document, 99), 1e-5f)
        assertTrue(TextDocumentParser.fractionForParagraph(document, 2) < 1f)
    }

    @Test
    fun paragraphIndexForFractionRespectsBoundaries() {
        val raw = "aaaaaaaaaa\n\nbbbbbbbbbb"
        val document = TextDocumentParser.parse(raw, markdown = false)
        assertEquals(listOf(0, 12), document.charOffsets.toList())
        assertEquals(0, TextDocumentParser.paragraphIndexForFraction(document, 0f))
        assertEquals(0, TextDocumentParser.paragraphIndexForFraction(document, 0.5f))
        assertEquals(1, TextDocumentParser.paragraphIndexForFraction(document, TextDocumentParser.fractionForParagraph(document, 1)))
        assertEquals(1, TextDocumentParser.paragraphIndexForFraction(document, 1f))
    }

    @Test
    fun paragraphIndexForFractionClampsAndHandlesNonFinite() {
        val document = TextDocumentParser.parse("段落一\n\n段落二\n\n段落三", markdown = false)
        assertEquals(0, TextDocumentParser.paragraphIndexForFraction(document, -0.5f))
        assertEquals(2, TextDocumentParser.paragraphIndexForFraction(document, 1.5f))
        assertEquals(0, TextDocumentParser.paragraphIndexForFraction(document, Float.NaN))
        assertEquals(0, TextDocumentParser.paragraphIndexForFraction(document, Float.POSITIVE_INFINITY))
        assertEquals(0, TextDocumentParser.paragraphIndexForFraction(document, Float.NEGATIVE_INFINITY))
    }

    @Test
    fun fractionHelpersFallBackToZeroOnEmptyDocument() {
        val document = TextDocumentParser.parse("   \n\n", markdown = false)
        assertTrue(document.isEmpty)
        assertEquals(0, TextDocumentParser.paragraphIndexForFraction(document, 0.5f))
        assertEquals(0f, TextDocumentParser.fractionForParagraph(document, 3), 0f)
    }

    @Test
    fun parseTxtKeepsHeadingMarkupAsPlainText() {
        val document = TextDocumentParser.parse("# 不是标题", markdown = false)
        assertEquals(1, document.paragraphs.size)
        assertEquals(0, document.paragraphs[0].headingLevel)
        assertEquals("# 不是标题", document.paragraphs[0].text)
    }

    private fun hasLoneSurrogate(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (Character.isHighSurrogate(char)) {
                if (index + 1 >= text.length || !Character.isLowSurrogate(text[index + 1])) return true
                index += 2
                continue
            }
            if (Character.isLowSurrogate(char)) return true
            index++
        }
        return false
    }

    @Test
    fun parseSplitsOversizedParagraphIntoChunks() {
        val raw = "字".repeat(45_000)
        val document = TextDocumentParser.parse(raw, markdown = false)
        assertEquals(3, document.paragraphs.size)
        assertEquals(listOf(20_000, 20_000, 5_000), document.paragraphs.map { it.text.length })
        assertEquals(listOf(0, 20_000, 40_000), document.charOffsets.toList())
        assertEquals(45_000, document.charCount)
        assertEquals(listOf(0, 0, 0), levels(document))
        assertEquals(raw, document.paragraphs.joinToString("") { it.text })
        assertTrue(document.paragraphs.all { it.text.length <= 20_000 })
        for (index in document.paragraphs.indices) {
            val fraction = TextDocumentParser.fractionForParagraph(document, index)
            assertEquals("round trip at $index", index, TextDocumentParser.paragraphIndexForFraction(document, fraction))
        }
    }

    @Test
    fun parseKeepsHeadingLevelOnlyOnFirstChunk() {
        val raw = "# 大标题\n" + "内".repeat(25_000)
        val document = TextDocumentParser.parse(raw, markdown = true)
        assertEquals(2, document.paragraphs.size)
        assertEquals(1, document.paragraphs[0].headingLevel)
        assertEquals(0, document.paragraphs[1].headingLevel)
        assertEquals(20_000, document.paragraphs[0].text.length)
        // 标题行只剩 "大标题\n"（4 个码元），剩下 25_004 个"内"跨两块。
        assertEquals("大标题\n" + "内".repeat(20_000 - 4), document.paragraphs[0].text)
        assertEquals("内".repeat(25_004 - 20_000), document.paragraphs[1].text)
        assertEquals(25_004, document.paragraphs.sumOf { it.text.length })
        assertEquals(listOf(0, 20_000), document.charOffsets.toList())
    }

    @Test
    fun parseNeverSplitsSurrogatePairsWhenChunking() {
        // 第 20_000 个码元正好落在 emoji 的高位代理上，切点必须前移一位。
        val raw = "a".repeat(19_999) + "\uD83D\uDE00" + "b".repeat(1_000)
        val document = TextDocumentParser.parse(raw, markdown = false)
        assertEquals(2, document.paragraphs.size)
        assertEquals(19_999, document.paragraphs[0].text.length)
        assertEquals("a".repeat(19_999), document.paragraphs[0].text)
        assertEquals("\uD83D\uDE00" + "b".repeat(1_000), document.paragraphs[1].text)
        assertEquals(listOf(0, 19_999), document.charOffsets.toList())
        for (paragraph in document.paragraphs) {
            assertFalse("lone surrogate in chunk", hasLoneSurrogate(paragraph.text))
        }
        assertEquals(raw, document.paragraphs.joinToString("") { it.text })
    }

    @Test
    fun parseDoesNotSplitParagraphExactlyAtLimit() {
        val document = TextDocumentParser.parse("字".repeat(20_000), markdown = false)
        assertEquals(1, document.paragraphs.size)
        assertEquals(20_000, document.paragraphs[0].text.length)
        assertEquals(listOf(0), document.charOffsets.toList())
    }

    @Test
    fun parseSplitsOneCharacterOverLimit() {
        val document = TextDocumentParser.parse("字".repeat(20_001), markdown = false)
        assertEquals(2, document.paragraphs.size)
        assertEquals(listOf(20_000, 1), document.paragraphs.map { it.text.length })
        assertEquals(listOf(0, 20_000), document.charOffsets.toList())
        assertEquals(20_001, document.charCount)
    }

    // ---- fromParagraphs：结构化格式（FB2 等）的「已分好段」入口 ----

    @Test
    fun fromParagraphsOfEmptyListGivesEmptyDocument() {
        val document = TextDocumentParser.fromParagraphs(emptyList())
        assertTrue(document.isEmpty)
        assertEquals(0, document.paragraphs.size)
        assertEquals(0, document.charOffsets.size)
        assertEquals(0, document.charCount)
    }

    @Test
    fun fromParagraphsLaysOutOffsetsFromTextLengths() {
        val document = TextDocumentParser.fromParagraphs(
            listOf(
                TextParagraph("甲乙丙"),
                TextParagraph("丁戊"),
                TextParagraph("己庚辛"),
            ),
        )
        assertFalse(document.isEmpty)
        assertEquals(listOf("甲乙丙", "丁戊", "己庚辛"), texts(document))
        // 偏移 = 前面各段长度之和（3、3+2）。
        assertEquals(listOf(0, 3, 5), document.charOffsets.toList())
        assertEquals(8, document.charCount)
        assertEquals(listOf(0, 0, 0), levels(document))
    }

    @Test
    fun fromParagraphsDropsEmptyTextAndKeepsOffsetsPacked() {
        val document = TextDocumentParser.fromParagraphs(
            listOf(TextParagraph("甲"), TextParagraph(""), TextParagraph("乙丙")),
        )
        assertEquals(2, document.paragraphs.size)
        assertEquals(listOf("甲", "乙丙"), texts(document))
        assertEquals(listOf(0, 1), document.charOffsets.toList())
        assertEquals(3, document.charCount)
    }

    @Test
    fun fromParagraphsPassesHeadingLevelsThrough() {
        val document = TextDocumentParser.fromParagraphs(
            listOf(
                TextParagraph("一级", headingLevel = 1),
                TextParagraph("二级", headingLevel = 2),
                TextParagraph("六级", headingLevel = 6),
            ),
        )
        assertEquals(listOf(1, 2, 6), levels(document))
        assertEquals(listOf("一级", "二级", "六级"), texts(document))
        assertEquals(listOf(0, 2, 4), document.charOffsets.toList())
        assertEquals(6, document.charCount)
    }

    @Test
    fun fromParagraphsSplitsOversizedParagraphIntoChunks() {
        val raw = "字".repeat(45_000)
        val document = TextDocumentParser.fromParagraphs(listOf(TextParagraph(raw, headingLevel = 2)))
        assertEquals(3, document.paragraphs.size)
        assertEquals(listOf(20_000, 20_000, 5_000), document.paragraphs.map { it.text.length })
        assertEquals(listOf(0, 20_000, 40_000), document.charOffsets.toList())
        // 标题级别只属于首块，后续块一律 0。
        assertEquals(listOf(2, 0, 0), levels(document))
        assertEquals(45_000, document.charCount)
        assertEquals(raw, document.paragraphs.joinToString("") { it.text })
        assertTrue(document.paragraphs.all { it.text.length <= 20_000 })
    }

    @Test
    fun fromParagraphsNeverSplitsSurrogatePairsWhenChunking() {
        // 每个单元 19_999 个 'a' + 一个 emoji，第一个切点正好落在 emoji 的高位代理上。
        val raw = ("a".repeat(19_999) + "\uD83D\uDE00").repeat(3)
        val document = TextDocumentParser.fromParagraphs(listOf(TextParagraph(raw)))
        assertTrue(document.paragraphs.size >= 3)
        assertTrue(document.paragraphs.all { it.text.length <= 20_000 })
        assertEquals(raw, document.paragraphs.joinToString("") { it.text })
        assertEquals(raw.length, document.charCount)
        assertEquals(19_999, document.paragraphs[0].text.length)
        var expectedOffset = 0
        for ((index, paragraph) in document.paragraphs.withIndex()) {
            assertEquals("offset at chunk " + index, expectedOffset, document.charOffsets[index])
            expectedOffset += paragraph.text.length
            assertFalse("lone surrogate in chunk " + index, hasLoneSurrogate(paragraph.text))
            assertFalse(
                "chunk " + index + " starts with a low surrogate",
                Character.isLowSurrogate(paragraph.text.first()),
            )
            assertFalse(
                "chunk " + index + " ends with a high surrogate",
                Character.isHighSurrogate(paragraph.text.last()),
            )
        }
        assertEquals(raw.length, expectedOffset)
    }

    @Test
    fun fromParagraphsFractionRoundTripHoldsForEveryParagraph() {
        val document = TextDocumentParser.fromParagraphs(
            listOf(TextParagraph("甲", headingLevel = 1), TextParagraph("乙乙"), TextParagraph("丙丙丙")),
        )
        assertEquals(listOf(0, 1, 3), document.charOffsets.toList())
        assertEquals(6, document.charCount)
        for (index in document.paragraphs.indices) {
            val fraction = TextDocumentParser.fractionForParagraph(document, index)
            // ROUND_TRIP_SLACK（1e-6 * charCount）吸收 Float 24 位有效位带来的往返误差。
            assertEquals("round trip at " + index, index, TextDocumentParser.paragraphIndexForFraction(document, fraction))
        }
    }

    // ---- 未闭合围栏：末尾落单的围栏行按普通文本处理，不能吞掉后面的正文 ----

    /** 用例 A：落单的开围栏不能把后面整本书并成一段，也不能把其后的 ## 标题吃进代码块。 */
    @Test
    fun parseTreatsTrailingUnclosedBacktickFenceAsPlainTextLine() {
        val fence = "\u0060\u0060\u0060"
        val raw = "# 一 标题\n\n" + fence + "\n围栏里的文字\n\n第二段正文\n\n## 二\n\n第三段"
        val document = TextDocumentParser.parse(raw, markdown = true)
        assertTrue("unclosed fence must not swallow the rest of the book", document.paragraphs.size >= 4)
        assertEquals(
            listOf("一 标题", fence + "\n围栏里的文字", "第二段正文", "二", "第三段"),
            texts(document),
        )
        assertEquals(listOf(1, 0, 0, 2, 0), levels(document))
    }

    /** 用例 B：成对围栏行为不变 —— 围栏内空行不切段，围栏之后的正文是独立普通段。 */
    @Test
    fun parsePairedFenceKeepsInnerBlankLineAndSplitsParagraphAfterFence() {
        val fence = "\u0060\u0060\u0060"
        val raw = fence + "\ncode\n\ncode2\n" + fence + "\n\n正文"
        val document = TextDocumentParser.parse(raw, markdown = true)
        assertEquals(2, document.paragraphs.size)
        assertEquals(fence + "\ncode\n\ncode2\n" + fence, document.paragraphs[0].text)
        assertEquals(0, document.paragraphs[0].headingLevel)
        assertEquals("正文", document.paragraphs[1].text)
        assertEquals(0, document.paragraphs[1].headingLevel)
    }

    /** 用例 C：奇数个围栏行 —— 前两行配对，第三行落单当普通行，其后的 # 标题仍是标题。 */
    @Test
    fun parseOddFenceCountPairsFirstTwoAndKeepsFollowingHeading() {
        val fence = "\u0060\u0060\u0060"
        val raw = fence + "\ncode\n" + fence + "\n" + fence + "\n\n# 标题"
        val document = TextDocumentParser.parse(raw, markdown = true)
        assertEquals(2, document.paragraphs.size)
        assertEquals(fence + "\ncode\n" + fence + "\n" + fence, document.paragraphs[0].text)
        assertEquals(0, document.paragraphs[0].headingLevel)
        assertEquals("标题", document.paragraphs[1].text)
        assertEquals(1, document.paragraphs[1].headingLevel)
    }

    /** 用例 D：波浪线围栏同样适用 —— 单行 ~~~ 落单后当普通行，后面的 # 标题照常识别。 */
    @Test
    fun parseTreatsTrailingUnclosedTildeFenceAsPlainTextLine() {
        val raw = "~~~\n\n# 标题\n\n正文"
        val document = TextDocumentParser.parse(raw, markdown = true)
        assertEquals(3, document.paragraphs.size)
        assertEquals(listOf("~~~", "标题", "正文"), texts(document))
        assertEquals(listOf(0, 1, 0), levels(document))
    }

    /** 用例 E：TXT（markdown=false）里三反引号只是普通行，不参与分段判断。 */
    @Test
    fun parseTxtKeepsBacktickLinesAsOrdinaryParagraphContent() {
        val fence = "\u0060\u0060\u0060"
        val raw = "第一段\n" + fence + "\n\n" + fence + "\n第二段"
        val document = TextDocumentParser.parse(raw, markdown = false)
        assertEquals(2, document.paragraphs.size)
        assertEquals("第一段 " + fence, document.paragraphs[0].text)
        assertEquals(fence + " 第二段", document.paragraphs[1].text)
        assertEquals(listOf(0, 0), levels(document))
    }

    // ------------------------------------------------- 图片段（FB2 / HTML 插图）

    private fun image(bytes: ByteArray = byteArrayOf(1, 2, 3)): TextImage =
        TextImage.embedded(key = "img", bytes = bytes)!!

    /** 图片段没有文字，但它占一个段落下标；丢了图就整张消失，所以不能被当空段落过滤。 */
    @Test
    fun fromParagraphsKeepsImageParagraphsBetweenTextParagraphs() {
        val document = TextDocumentParser.fromParagraphs(
            listOf(
                TextParagraph("上"),
                TextParagraph("", image = image()),
                TextParagraph("下"),
            ),
        )
        assertEquals(3, document.paragraphs.size)
        assertEquals(listOf("上", "", "下"), texts(document))
        assertTrue(document.paragraphs[1].image != null)
        assertEquals(null, document.paragraphs[0].image)
        // 偏移仍单调：图片段长度 0，第三段从「上」之后开始。
        assertEquals(listOf(0, 1, 1), document.charOffsets.toList())
        assertEquals(2, document.charCount)
    }

    /** 没有图片的空串段落照旧丢弃——只有 image 非空才豁免。 */
    @Test
    fun fromParagraphsStillDropsBlankParagraphsWithoutImage() {
        val document = TextDocumentParser.fromParagraphs(
            listOf(TextParagraph("甲"), TextParagraph(""), TextParagraph("乙")),
        )
        assertEquals(listOf("甲", "乙"), texts(document))
    }

    /** 纯文本 / Markdown 解析不会凭空造出图片段（默认值必须保持 null）。 */
    @Test
    fun parseNeverProducesImageParagraphs() {
        val document = TextDocumentParser.parse("第一段\n\n第二段", markdown = false)
        assertTrue(document.paragraphs.all { it.image == null })
    }
}
