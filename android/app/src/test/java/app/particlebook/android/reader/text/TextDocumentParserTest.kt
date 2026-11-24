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
}

