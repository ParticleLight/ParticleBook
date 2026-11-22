package app.particlebook.android.reader.tts

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TtsLanguagesTest {

    private val zh = Locale.SIMPLIFIED_CHINESE

    @Test
    fun englishTextUsesUsVoice() {
        assertEquals(Locale.US, TtsLanguages.localeFor("Call me Ishmael. Some years ago—never mind how long.", zh))
    }

    @Test
    fun chineseTextUsesFallbackVoice() {
        assertEquals(zh, TtsLanguages.localeFor("白鲸记：叫我以实玛利吧。", zh))
    }

    @Test
    fun mostlyEnglishWithFewChineseCharsStaysLatin() {
        // 一句英文里夹一个中文引号或一个词，仍应按英文念。
        assertEquals(Locale.US, TtsLanguages.localeFor("He said “hello” and left the ship immediately.", zh))
    }

    @Test
    fun chinesePunctuationAloneDoesNotForceChinese() {
        assertEquals(Locale.US, TtsLanguages.localeFor("Moby Dick。", zh))
    }

    @Test
    fun blankTextKeepsFallback() {
        assertEquals(zh, TtsLanguages.localeFor("", zh))
        assertEquals(zh, TtsLanguages.localeFor("   ", zh))
        assertEquals(zh, TtsLanguages.localeFor("\n\t", zh))
        assertEquals(zh, TtsLanguages.localeFor("123 456", zh))
    }

    @Test
    fun nonDefaultFallbackIsRespected() {
        val fr = Locale.FRENCH
        assertNotEquals(Locale.US, TtsLanguages.localeFor("白鲸记", fr))
        assertEquals(fr, TtsLanguages.localeFor("白鲸记", fr))
        assertEquals(Locale.US, TtsLanguages.localeFor("Moby Dick", fr))
    }
}
