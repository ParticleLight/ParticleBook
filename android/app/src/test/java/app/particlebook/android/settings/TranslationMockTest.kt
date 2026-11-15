package app.particlebook.android.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationMockTest {

    @Test
    fun emptyKeyReturnsNotConfiguredWithoutNetwork() {
        val result = TranslationMock.translate(null, "Hello")
        assertFalse(result.ok)
        assertEquals(TranslationMock.NOT_CONFIGURED_MESSAGE, result.message)
        assertNull(result.translatedText)
    }

    @Test
    fun blankKeyIsTreatedAsNotConfigured() {
        val result = TranslationMock.translate("   ", "Hello")
        assertFalse(result.ok)
        assertEquals(TranslationMock.NOT_CONFIGURED_MESSAGE, result.message)
        assertNull(result.translatedText)
    }

    @Test
    fun configuredKeyReturnsMockTranslation() {
        val result = TranslationMock.translate("pb-test-key-DO-NOT-USE-7f3c", "Hello")
        assertTrue(result.ok)
        assertEquals(TranslationMock.SUCCESS_MESSAGE, result.message)
        assertEquals("[Mock 翻译] Hello", result.translatedText)
    }

    @Test
    fun blankTextIsRejectedAfterKeyCheck() {
        val result = TranslationMock.translate("pb-test-key-DO-NOT-USE-7f3c", "  ")
        assertFalse(result.ok)
        assertEquals("请输入要翻译的文本", result.message)
        assertNull(result.translatedText)
    }

    @Test
    fun mockResultNeverEchoesApiKey() {
        val key = "pb-test-key-DO-NOT-USE-7f3c"
        val result = TranslationMock.translate(key, "文本")
        assertFalse(result.toString().contains(key))
        assertEquals("[Mock 翻译] 文本", result.translatedText)
    }
}
