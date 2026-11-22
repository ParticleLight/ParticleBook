package app.particlebook.android.reader.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsSentenceSplitterTest {

    private fun texts(raw: String): List<String> =
        TtsSentenceSplitter.split(raw).map { raw.substring(it.startChar, it.endChar) }

    @Test
    fun splitsChineseTerminators() {
        assertEquals(
            listOf("第一句。", "第二句！", "第三句？"),
            texts("第一句。第二句！第三句？"),
        )
    }

    @Test
    fun keepsClosingQuotesWithSentence() {
        assertEquals(
            listOf("他说：“走吧。”", "她点头。"),
            texts("他说：“走吧。”她点头。"),
        )
    }

    @Test
    fun mergesRepeatedTerminators() {
        assertEquals(listOf("真的？！", "好吧……"), texts("真的？！好吧……"))
    }

    @Test
    fun splitsOnNewline() {
        assertEquals(listOf("第一行", "第二行"), texts("第一行\n第二行"))
    }

    @Test
    fun keepsDecimalPoint() {
        assertEquals(listOf("圆周率约等于 3.14159。"), texts("圆周率约等于 3.14159。"))
    }

    @Test
    fun keepsCommonAbbreviation() {
        assertEquals(listOf("Mr. Smith arrived.", "Then he left."), texts("Mr. Smith arrived. Then he left."))
    }

    @Test
    fun splitsEnglishSentences() {
        assertEquals(
            listOf("One thing.", "Another thing!", "Is it?"),
            texts("One thing. Another thing! Is it?"),
        )
    }

    @Test
    fun dropsBlankSpansAndTrims() {
        assertEquals(listOf("有内容。"), texts("  \n有内容。\n\n"))
    }

    @Test
    fun emptyTextHasNoSentences() {
        assertEquals(emptyList<String>(), texts(""))
        assertEquals(emptyList<String>(), texts("   "))
    }

    @Test
    fun offsetsAreWithinText() {
        val raw = "甲。乙。"
        val spans = TtsSentenceSplitter.split(raw)
        assertEquals(2, spans.size)
        assertEquals("甲。", raw.substring(spans[0].startChar, spans[0].endChar))
        assertEquals("乙。", raw.substring(spans[1].startChar, spans[1].endChar))
    }

    // --- 全大写缩写 / 首字母 ---

    @Test
    fun splitsAfterDottedAcronymAtSentenceEnd() {
        assertEquals(
            listOf("He lives in the U.S.A.", "She left."),
            texts("He lives in the U.S.A. She left."),
        )
    }

    @Test
    fun keepsAcronymInsideSentence() {
        assertEquals(
            listOf("U.S.A. is a country."),
            texts("U.S.A. is a country."),
        )
    }

    @Test
    fun keepsSingleLetterInitials() {
        // "J."、"K." 是名字缩写，不切；两字母大写不是豁免对象。
        assertEquals(listOf("J. K. Rowling wrote it."), texts("J. K. Rowling wrote it."))
    }

    @Test
    fun twoLetterCapitalizedWordIsNotAbbreviation() {
        assertEquals(listOf("Wa.", "Then he left."), texts("Wa. Then he left."))
    }

    // --- 纯标点段 ---

    @Test
    fun dropsPunctuationOnlySpans() {
        assertEquals(listOf("真的？！", "好吧。"), texts("真的？！\n\n？！\n\n好吧。"))
    }

    @Test
    fun standaloneEllipsisProducesNoSentence() {
        // 单独一段的"……"（U+2026 ×2）不该变成一句，也不该念成符号名。
        assertEquals(
            listOf("先是这样……", "后来那样。"),
            texts("先是这样……\n\n……\n\n后来那样。"),
        )
    }

    @Test
    fun punctuationOnlyTextHasNoSentences() {
        assertEquals(emptyList<String>(), texts("……？！"))
    }

    // --- 首尾裁剪：NBSP 系列 ---

    @Test
    fun trimsNoBreakSpaces() {
        // NBSP(U+00A0) / figure space(U+2007) / narrow no-break space(U+202F)
        assertEquals(listOf("甲。", "乙"), texts("甲。\u00A0乙\u00A0"))
        assertEquals(listOf("甲。", "乙"), texts("甲。乙\u2007"))
        assertEquals(listOf("甲。", "乙"), texts("甲。乙\u202F"))
    }

    @Test
    fun splitsSentenceEndingWithNoBreakSpace() {
        assertEquals(listOf("He left.", "She came."), texts("He left.\u00A0She came."))
    }

    // --- 小数 ---

    @Test
    fun keepsDecimalPointFollowedBySpace() {
        assertEquals(listOf("Pi 约等于 3.14 左右。"), texts("Pi 约等于 3.14 左右。"))
    }

    @Test
    fun plainWordPeriodWithLowercaseNextStillSplits() {
        // 回归：把「后面是小写就不切」放宽到所有词，会让 "ab. cd." 整段不切，
        // 连带 PDF 朗读的高亮按句定位全部落空（PdfTtsPageTest 抓到的正是这个）。
        assertEquals(listOf("ab.", "cd."), texts("ab. cd."))
    }

    @Test
    fun dottedInitialismFollowedByLowercaseStaysOneSentence() {
        assertEquals(listOf("the U.S.A. is a country."), texts("the U.S.A. is a country."))
    }
}