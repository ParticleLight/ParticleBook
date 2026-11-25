package app.particlebook.android.reader.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TextSpeech] 的纯 JVM 单测。
 *
 * 断言口径以当前实现为准：
 * - sentences 直接委派 [app.particlebook.android.reader.tts.TtsText.sentences]，区间已去掉首尾空白；
 * - 可朗读判定只看 text 是否空白（isNotBlank），不看切句结果；
 * - nextSpeakable / previousSpeakable 是严格的 +1 / -1 转发。
 */
class TextSpeechTest {

    // ------------------------------------------------------------------
    // sentences
    // ------------------------------------------------------------------

    @Test
    fun blankParagraphProducesNoSentences() {
        assertTrue(TextSpeech.sentences("").isEmpty())
    }

    @Test
    fun whitespaceOnlyParagraphProducesNoSentences() {
        assertTrue(TextSpeech.sentences("   ").isEmpty())
        assertTrue(TextSpeech.sentences("\n\t ").isEmpty())
        assertTrue(TextSpeech.sentences("\u000C").isEmpty())
    }

    @Test
    fun singleSentenceWithPeriodProducesExactlyOne() {
        val sentences = TextSpeech.sentences("只有一句话。")
        assertEquals(1, sentences.size)
        assertEquals("只有一句话。", sentences[0].text)
        assertEquals(0, sentences[0].startChar)
        assertEquals("只有一句话。".length, sentences[0].endChar)
    }

    @Test
    fun multipleSentencesKeepOriginalOrderAndNonBlankText() {
        val paragraph = "第一句。第二句！第三句？"
        val sentences = TextSpeech.sentences(paragraph)
        assertTrue(sentences.size >= 2)
        for (sentence in sentences) {
            assertTrue(sentence.text.isNotBlank())
        }
        for (i in 1 until sentences.size) {
            assertTrue(
                "句子顺序应与原文一致：第 ${i} 条的 startChar 必须大于前一条",
                sentences[i].startChar >= sentences[i - 1].endChar,
            )
            assertTrue(sentences[i].endChar > sentences[i].startChar)
        }
        assertEquals("第一句。", sentences.first().text)
        assertEquals("第三句？", sentences.last().text)
    }

    @Test
    fun everySentenceRangeCutsBackToItsOwnText() {
        val paragraph = "  第一句。第二句！ 第三句？ "
        val sentences = TextSpeech.sentences(paragraph)
        assertTrue(sentences.isNotEmpty())
        for (sentence in sentences) {
            assertTrue(sentence.startChar in 0..paragraph.length)
            assertTrue(sentence.endChar in sentence.startChar..paragraph.length)
            assertEquals(sentence.text, paragraph.substring(sentence.startChar, sentence.endChar))
        }
    }

    @Test
    fun sentenceRangesStayInsideParagraphWithoutInnerWhitespace() {
        val paragraph = "甲。乙！丙？"
        val sentences = TextSpeech.sentences(paragraph)
        assertEquals(3, sentences.size)
        for (sentence in sentences) {
            assertEquals(sentence.text, paragraph.substring(sentence.startChar, sentence.endChar))
        }
    }

    @Test
    fun paragraphWithInnerLineBreakStillSplitsWithoutThrowing() {
        val paragraph = "第一行\n第二行。"
        val sentences = TextSpeech.sentences(paragraph)
        assertTrue(sentences.size >= 1)
        for (sentence in sentences) {
            assertEquals(sentence.text, paragraph.substring(sentence.startChar, sentence.endChar))
            assertTrue(sentence.text.isNotBlank())
        }
    }

    @Test
    fun punctuationOnlyParagraphProducesNoSentences() {
        assertTrue(TextSpeech.sentences("……").isEmpty())
        assertTrue(TextSpeech.sentences("？！").isEmpty())
    }

    @Test
    fun englishTerminatorsAreSplitAsWell() {
        val sentences = TextSpeech.sentences("Hi! Ok? Yes.")
        assertEquals(listOf("Hi!", "Ok?", "Yes."), sentences.map { it.text })
    }

    // ------------------------------------------------------------------
    // firstSpeakableAtOrAfter
    // ------------------------------------------------------------------

    @Test
    fun firstSpeakableAtOrAfterFromZeroReturnsZero() {
        val paragraphs = listOf(TextParagraph("第一段。"), TextParagraph("第二段。"))
        assertEquals(0, TextSpeech.firstSpeakableAtOrAfter(paragraphs, 0))
    }

    @Test
    fun firstSpeakableAtOrAfterSkipsBlankParagraphAtFrom() {
        val paragraphs = listOf(TextParagraph("甲。"), TextParagraph(" "), TextParagraph("乙。"))
        assertEquals(2, TextSpeech.firstSpeakableAtOrAfter(paragraphs, 1))
    }

    @Test
    fun firstSpeakableAtOrAfterReturnsFromWhenItselfSpeakable() {
        val paragraphs = listOf(TextParagraph("甲。"), TextParagraph("\n\t"), TextParagraph("乙。"))
        assertEquals(0, TextSpeech.firstSpeakableAtOrAfter(paragraphs, 0))
        assertEquals(2, TextSpeech.firstSpeakableAtOrAfter(paragraphs, 2))
    }

    @Test
    fun firstSpeakableAtOrAfterBeyondLastIndexReturnsNull() {
        val paragraphs = listOf(TextParagraph("甲。"), TextParagraph("乙。"), TextParagraph("丙。"))
        assertNull(TextSpeech.firstSpeakableAtOrAfter(paragraphs, 99))
    }

    @Test
    fun firstSpeakableAtOrAfterFromExactlySizeReturnsNull() {
        val paragraphs = listOf(TextParagraph("甲。"), TextParagraph("乙。"), TextParagraph("丙。"))
        assertNull(TextSpeech.firstSpeakableAtOrAfter(paragraphs, paragraphs.size))
    }

    @Test
    fun firstSpeakableAtOrAfterNegativeFromStartsAtZero() {
        val paragraphs = listOf(TextParagraph("   "), TextParagraph("乙。"))
        assertEquals(1, TextSpeech.firstSpeakableAtOrAfter(paragraphs, -5))
    }

    @Test
    fun firstSpeakableAtOrAfterAllBlankReturnsNull() {
        val paragraphs = listOf(TextParagraph(" "), TextParagraph("\n"), TextParagraph("\t "))
        assertNull(TextSpeech.firstSpeakableAtOrAfter(paragraphs, 0))
    }

    @Test
    fun firstSpeakableAtOrAfterEmptyListReturnsNull() {
        assertNull(TextSpeech.firstSpeakableAtOrAfter(emptyList(), 0))
        assertNull(TextSpeech.firstSpeakableAtOrAfter(emptyList(), -3))
    }

    @Test
    fun headingParagraphCountsAsSpeakable() {
        val paragraphs = listOf(TextParagraph("   "), TextParagraph("第一章", headingLevel = 2))
        assertEquals(1, TextSpeech.firstSpeakableAtOrAfter(paragraphs, 0))
    }

    // ------------------------------------------------------------------
    // lastSpeakableAtOrBefore
    // ------------------------------------------------------------------

    @Test
    fun lastSpeakableAtOrBeforeReturnsFromWhenItselfSpeakable() {
        val paragraphs = listOf(TextParagraph("甲。"), TextParagraph("乙。"), TextParagraph("丙。"))
        assertEquals(1, TextSpeech.lastSpeakableAtOrBefore(paragraphs, 1))
    }

    @Test
    fun lastSpeakableAtOrBeforeWalksBackOverBlankParagraph() {
        val paragraphs = listOf(TextParagraph("甲。"), TextParagraph(" "), TextParagraph("乙。"))
        assertEquals(0, TextSpeech.lastSpeakableAtOrBefore(paragraphs, 1))
    }

    @Test
    fun lastSpeakableAtOrBeforeBeyondLastIndexClampsToLast() {
        val paragraphs = listOf(TextParagraph("甲。"), TextParagraph(" "), TextParagraph("乙。"))
        assertEquals(2, TextSpeech.lastSpeakableAtOrBefore(paragraphs, 99))
    }

    @Test
    fun lastSpeakableAtOrBeforeBeyondLastIndexCanLandOnEarlierWhenTailBlank() {
        val paragraphs = listOf(TextParagraph("甲。"), TextParagraph("乙。"), TextParagraph(" "))
        assertEquals(1, TextSpeech.lastSpeakableAtOrBefore(paragraphs, 99))
    }

    @Test
    fun lastSpeakableAtOrBeforeNegativeFromReturnsNull() {
        val paragraphs = listOf(TextParagraph("甲。"), TextParagraph("乙。"))
        assertNull(TextSpeech.lastSpeakableAtOrBefore(paragraphs, -1))
        assertNull(TextSpeech.lastSpeakableAtOrBefore(paragraphs, -99))
    }

    @Test
    fun lastSpeakableAtOrBeforeBeforeAnySpeakableReturnsNull() {
        val paragraphs = listOf(TextParagraph(" "), TextParagraph("甲。"))
        assertNull(TextSpeech.lastSpeakableAtOrBefore(paragraphs, 0))
    }

    @Test
    fun lastSpeakableAtOrBeforeAllBlankReturnsNull() {
        val paragraphs = listOf(TextParagraph(" "), TextParagraph("\n\t"))
        assertNull(TextSpeech.lastSpeakableAtOrBefore(paragraphs, 1))
    }

    @Test
    fun lastSpeakableAtOrBeforeEmptyListReturnsNull() {
        assertNull(TextSpeech.lastSpeakableAtOrBefore(emptyList(), 0))
    }

    // ------------------------------------------------------------------
    // nextSpeakable / previousSpeakable
    // ------------------------------------------------------------------

    @Test
    fun nextSpeakableIsStrictlyForward() {
        val paragraphs = listOf(TextParagraph("甲。"), TextParagraph("乙。"))
        assertEquals(1, TextSpeech.nextSpeakable(paragraphs, 0))
    }

    @Test
    fun nextSpeakableFromMinusOneStartsAtZero() {
        val paragraphs = listOf(TextParagraph("甲。"), TextParagraph("乙。"))
        assertEquals(0, TextSpeech.nextSpeakable(paragraphs, -1))
    }

    @Test
    fun nextSpeakableSkipsConsecutiveBlankParagraphs() {
        val paragraphs = listOf(
            TextParagraph("甲。"),
            TextParagraph(" "),
            TextParagraph("\n\t"),
            TextParagraph("乙。"),
        )
        assertEquals(3, TextSpeech.nextSpeakable(paragraphs, 0))
    }

    @Test
    fun nextSpeakableAtLastParagraphReturnsNull() {
        val paragraphs = listOf(TextParagraph("甲。"), TextParagraph("乙。"))
        assertNull(TextSpeech.nextSpeakable(paragraphs, 1))
        assertNull(TextSpeech.nextSpeakable(paragraphs, 1 + 10))
    }

    @Test
    fun nextSpeakableOnEmptyListReturnsNull() {
        assertNull(TextSpeech.nextSpeakable(emptyList(), -1))
    }

    @Test
    fun previousSpeakableIsStrictlyBackward() {
        val paragraphs = listOf(TextParagraph("甲。"), TextParagraph("乙。"))
        assertEquals(0, TextSpeech.previousSpeakable(paragraphs, 1))
    }

    @Test
    fun previousSpeakableAtFirstParagraphReturnsNull() {
        val paragraphs = listOf(TextParagraph("甲。"), TextParagraph("乙。"))
        assertNull(TextSpeech.previousSpeakable(paragraphs, 0))
    }

    @Test
    fun previousSpeakableFromMinusOneReturnsNull() {
        val paragraphs = listOf(TextParagraph("甲。"), TextParagraph("乙。"))
        assertNull(TextSpeech.previousSpeakable(paragraphs, -1))
    }

    @Test
    fun previousSpeakableSkipsConsecutiveBlankParagraphs() {
        val paragraphs = listOf(
            TextParagraph("甲。"),
            TextParagraph(" "),
            TextParagraph("\n"),
            TextParagraph("乙。"),
        )
        assertEquals(0, TextSpeech.previousSpeakable(paragraphs, 3))
    }

    // ------------------------------------------------------------------
    // 纯函数性
    // ------------------------------------------------------------------

    @Test
    fun callsLeaveInputParagraphsUntouched() {
        val paragraphs = listOf(
            TextParagraph("甲。"),
            TextParagraph(" "),
            TextParagraph("乙。", headingLevel = 2),
        )
        val snapshot = paragraphs.map { it.copy() }

        TextSpeech.sentences(paragraphs[0].text)
        TextSpeech.firstSpeakableAtOrAfter(paragraphs, 0)
        TextSpeech.lastSpeakableAtOrBefore(paragraphs, paragraphs.lastIndex)
        TextSpeech.nextSpeakable(paragraphs, -1)
        TextSpeech.previousSpeakable(paragraphs, paragraphs.size)

        assertEquals(snapshot, paragraphs)
        assertEquals(3, paragraphs.size)
        assertEquals(0, paragraphs[1].headingLevel)
        assertEquals(2, paragraphs[2].headingLevel)
    }

    // ------------------------------------------------------------------
    // 可读判定收紧（只含标点 / 破折号 / NBSP 的段不算可读）
    // ------------------------------------------------------------------

    @Test
    fun punctuationOnlyParagraphIsSkipped() {
        val paragraphs = listOf(TextParagraph("……"), TextParagraph("正文句子。"))
        assertEquals(1, TextSpeech.firstSpeakableAtOrAfter(paragraphs, 0))
    }

    @Test
    fun dashOnlyParagraphIsSkippedByNextSpeakable() {
        val paragraphs = listOf(TextParagraph("——"), TextParagraph("正文句子。"))
        assertEquals(1, TextSpeech.nextSpeakable(paragraphs, -1))
    }

    @Test
    fun noBreakSpaceOnlyParagraphIsSkipped() {
        val paragraphs = listOf(TextParagraph("\u00A0\u00A0\u00A0"), TextParagraph("正文句子。"))
        assertEquals(1, TextSpeech.firstSpeakableAtOrAfter(paragraphs, 0))
    }

    @Test
    fun blankAndPunctuationParagraphsAreBothSkipped() {
        val paragraphs = listOf(
            TextParagraph(" "),
            TextParagraph("……"),
            TextParagraph("正文句子。"),
        )
        assertEquals(2, TextSpeech.firstSpeakableAtOrAfter(paragraphs, 0))
    }

    @Test
    fun backwardScanSkipsPunctuationOnlyParagraph() {
        val paragraphs = listOf(
            TextParagraph("正文句子。"),
            TextParagraph("……"),
            TextParagraph("另一段。"),
        )
        // from 落在可读段：含 from，返回它自己（"另一段。" 是真能念的，不能被当成"跳过"的对象）。
        assertEquals(2, TextSpeech.lastSpeakableAtOrBefore(paragraphs, 2))
        // from 落在纯标点段：跳过它，回到前一个真正可读的段。
        assertEquals(0, TextSpeech.lastSpeakableAtOrBefore(paragraphs, 1))
    }

    @Test
    fun allPunctuationAndBlankDocumentHasNoSpeakableParagraph() {
        val paragraphs = listOf(
            TextParagraph("   "),
            TextParagraph("……"),
            TextParagraph("——"),
            TextParagraph("\u00A0"),
        )
        assertNull(TextSpeech.firstSpeakableAtOrAfter(paragraphs, 0))
        assertNull(TextSpeech.lastSpeakableAtOrBefore(paragraphs, 2))
    }

    @Test
    fun headingParagraphStaysSpeakableAfterTightening() {
        val paragraphs = listOf(TextParagraph("第一章", headingLevel = 2))
        assertEquals(0, TextSpeech.firstSpeakableAtOrAfter(paragraphs, 0))
    }

    // ------------------------------------------------------------------
    // next/previous 的 Int 溢出边界
    // ------------------------------------------------------------------

    @Test
    fun nextSpeakableAtIntMaxValueDoesNotWrapToFirstParagraph() {
        val paragraphs = listOf(TextParagraph("甲。"), TextParagraph("乙。"))
        assertNull(TextSpeech.nextSpeakable(paragraphs, Int.MAX_VALUE))
    }

    @Test
    fun previousSpeakableAtIntMinValueDoesNotWrapToLastParagraph() {
        val paragraphs = listOf(TextParagraph("甲。"), TextParagraph("乙。"))
        assertNull(TextSpeech.previousSpeakable(paragraphs, Int.MIN_VALUE))
    }
}
