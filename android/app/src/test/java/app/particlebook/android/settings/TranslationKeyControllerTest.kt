package app.particlebook.android.settings

import app.particlebook.android.platform.SecretStoreError
import app.particlebook.android.platform.SecretStoreException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationKeyControllerTest {

    private val testKey = "pb-test-key-DO-NOT-USE-7f3c"

    private fun controller(secret: FakeSecretStore = FakeSecretStore()): Pair<TranslationKeyController, FakeSecretStore> {
        val bridge = SettingsSecretBridge(secret)
        return TranslationKeyController(bridge) to secret
    }

    @Test
    fun unconfiguredStatusAndTestCall() {
        val (controller, _) = controller()
        val status = controller.status()
        assertEquals(TranslationKeyState.NOT_CONFIGURED, status.state)
        assertEquals("未配置", status.shortLabel)
        assertNull(controller.read())

        val result = controller.test()
        assertFalse(result.ok)
        assertEquals(TranslationMock.NOT_CONFIGURED_MESSAGE, result.message)
        assertNull(result.translatedText)
    }

    @Test
    fun saveThenStatusReadAndTestSucceed() {
        val (controller, secret) = controller()
        val saved = controller.save("  " + testKey + "  ")
        assertTrue(saved.ok)
        assertEquals("已保存 API Key", saved.message)
        assertEquals(testKey, secret.storedFor(SettingsSecretBridge.API_KEY_ALIAS))
        assertEquals(testKey, controller.read())

        val status = controller.status()
        assertEquals(TranslationKeyState.CONFIGURED, status.state)
        assertEquals("已配置", status.shortLabel)
        assertNull(status.message)

        val result = controller.test()
        assertTrue(result.ok)
        assertEquals(TranslationMock.SUCCESS_MESSAGE, result.message)
        assertEquals("[Mock 翻译] " + TranslationMock.DEFAULT_SAMPLE_TEXT, result.translatedText)
        assertFalse(result.toString().contains(testKey))
    }

    @Test
    fun blankSaveIsRejectedAndDoesNotWrite() {
        val (controller, secret) = controller()
        val result = controller.save("   ")
        assertFalse(result.ok)
        assertEquals("请输入 API Key", result.message)
        assertNull(secret.storedFor(SettingsSecretBridge.API_KEY_ALIAS))
        assertEquals(TranslationKeyState.NOT_CONFIGURED, controller.status().state)
    }

    @Test
    fun clearReturnsToNotConfigured() {
        val (controller, _) = controller()
        controller.save(testKey)
        val result = controller.clear()
        assertTrue(result.ok)
        assertEquals("已清除 API Key", result.message)
        assertEquals(TranslationKeyState.NOT_CONFIGURED, controller.status().state)
        assertNull(controller.read())
        assertFalse(controller.test().ok)
    }

    @Test
    fun corruptedCiphertextMapsToChineseCorruptedStatus() {
        val (controller, secret) = controller()
        controller.save(testKey)
        secret.failGet = SecretStoreException(
            SecretStoreError.CIPHERTEXT_CORRUPT,
            "bad ciphertext",
        )

        val status = controller.status()
        assertEquals(TranslationKeyState.CORRUPTED, status.state)
        assertEquals("损坏", status.shortLabel)
        assertTrue(status.message!!.contains("无法解密"))
        assertNull(controller.read())

        val result = controller.test()
        assertFalse(result.ok)
        assertEquals(status.message, result.message)
        assertNull(result.translatedText)
    }

    @Test
    fun keystoreUnavailableMapsToChineseRecoveryMessage() {
        val (controller, secret) = controller()
        secret.failGet = SecretStoreException(
            SecretStoreError.KEY_UNAVAILABLE,
            "keystore key missing",
        )
        val status = controller.status()
        assertEquals(TranslationKeyState.CORRUPTED, status.state)
        assertTrue(status.message!!.contains("密钥存储不可用"))
    }

    @Test
    fun saveAndClearErrorsAreReportedInChinese() {
        val (controller, secret) = controller()
        secret.failPut = SecretStoreException(SecretStoreError.STORAGE_FAILED, "disk full")
        val save = controller.save(testKey)
        assertFalse(save.ok)
        assertEquals("密钥保存失败，请重试", save.message)

        secret.failPut = null
        controller.save(testKey)
        secret.failDelete = SecretStoreException(SecretStoreError.STORAGE_FAILED, "disk full")
        val clear = controller.clear()
        assertFalse(clear.ok)
        assertEquals("密钥清除失败，请重试", clear.message)
        assertEquals(testKey, controller.read())
    }
}
