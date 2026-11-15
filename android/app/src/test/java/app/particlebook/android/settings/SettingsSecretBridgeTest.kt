package app.particlebook.android.settings

import app.particlebook.android.platform.SecretStoreException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SettingsSecretBridgeTest {

    @Test
    fun bridgeInitializesTranslationAliasAndRoundTripsValue() {
        val secret = FakeSecretStore()
        val bridge = SettingsSecretBridge(secret)
        assertEquals(listOf(SettingsSecretBridge.API_KEY_ALIAS), secret.aliasCalls)
        assertEquals(SettingsSecretBridge.API_KEY_ALIAS, bridge.currentAlias())
        assertNull(bridge.getApiKey())

        bridge.putApiKey("pb-test-key-DO-NOT-USE-7f3c")
        assertEquals("pb-test-key-DO-NOT-USE-7f3c", bridge.getApiKey())
        assertEquals(
            "pb-test-key-DO-NOT-USE-7f3c",
            secret.storedFor(SettingsSecretBridge.API_KEY_ALIAS),
        )

        bridge.deleteApiKey()
        assertNull(bridge.getApiKey())
    }

    @Test
    fun aliasSwitchIsolatesSecrets() {
        val secret = FakeSecretStore()
        val bridge = SettingsSecretBridge(secret)
        bridge.putApiKey("key-a")
        bridge.setAlias("translation.backupKey")
        assertNull(bridge.getApiKey())
        bridge.putApiKey("key-b")
        assertEquals("key-b", bridge.getApiKey())
        assertEquals("translation.backupKey", bridge.currentAlias())

        bridge.setAlias(SettingsSecretBridge.API_KEY_ALIAS)
        assertEquals("key-a", bridge.getApiKey())
        assertEquals("key-a", secret.storedFor(SettingsSecretBridge.API_KEY_ALIAS))
        assertEquals("key-b", secret.storedFor("translation.backupKey"))
    }

    @Test
    fun secretStoreErrorsAreNotSwallowed() {
        val secret = FakeSecretStore()
        val bridge = SettingsSecretBridge(secret)
        secret.failGet = FakeSecretStore.unavailable()
        assertThrows(SecretStoreException::class.java) { bridge.getApiKey() }

        secret.failGet = null
        secret.failPut = FakeSecretStore.unavailable()
        assertThrows(SecretStoreException::class.java) { bridge.putApiKey("x") }
    }

    @Test
    fun apiKeyNeverAppearsInSettingsStorageOrExport() {
        val testKey = "pb-test-key-DO-NOT-USE-7f3c"
        val storage = FakeSettingsStorage()
        val settings = SettingsStore(storage)
        val bridge = SettingsSecretBridge(FakeSecretStore())

        settings.set("theme", "sepia")
        settings.set("translation.baseUrl", "https://api.example.com/v1")
        bridge.putApiKey(testKey)

        assertFalse(storage.snapshot().keys.any { it.contains(testKey) })
        assertFalse(storage.snapshot().values.toString().contains(testKey))
        assertFalse(settings.exportRedacted().toString().contains(testKey))
        assertEquals(testKey, bridge.getApiKey())
    }
}
