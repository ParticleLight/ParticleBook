package app.particlebook.android.reader.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsTextTest {

    @Test
    fun sentencesCarryPageOffsets() {
        val page = "第一句。第二句。"
        val sentences = TtsText.sentences(page)
        assertEquals(listOf("第一句。", "第二句。"), sentences.map { it.text })
        assertEquals(0, sentences[0].startChar)
        assertEquals(4, sentences[0].endChar)
        assertEquals(4, sentences[1].startChar)
        assertEquals(8, sentences[1].endChar)
        assertTrue(sentences[1].text == page.substring(sentences[1].startChar, sentences[1].endChar))
    }

    @Test
    fun blankPageHasNoSentences() {
        assertEquals(emptyList<TtsSentence>(), TtsText.sentences(""))
        assertEquals(emptyList<TtsSentence>(), TtsText.sentences("   \n  "))
    }

    @Test
    fun paragraphBreaksBecomeSeparateSentences() {
        val page = "第一段没有句号\n第二段也没有句号"
        val sentences = TtsText.sentences(page)
        assertEquals(listOf("第一段没有句号", "第二段也没有句号"), sentences.map { it.text })
    }

    @Test
    fun offsetsStayInsideText() {
        val page = "甲。乙！丙？"
        for (sentence in TtsText.sentences(page)) {
            assertTrue(sentence.startChar in 0..page.length)
            assertTrue(sentence.endChar in sentence.startChar..page.length)
            assertEquals(sentence.text, page.substring(sentence.startChar, sentence.endChar))
        }
    }

    @Test
    fun englishPageKeepsAbbreviation() {
        val page = "Mr. Smith arrived. Then he left."
        assertEquals(listOf("Mr. Smith arrived.", "Then he left."), TtsText.sentences(page).map { it.text })
    }
}
