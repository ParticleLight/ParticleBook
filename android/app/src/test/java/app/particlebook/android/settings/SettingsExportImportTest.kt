package app.particlebook.android.settings

import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A023-5：设置导出 / 恢复。
 *
 * 覆盖点：37 键完整导出、Key 不进入备份、导入不接受密钥字段、恢复后 37 键
 * 与书级覆盖回填、未知键策略、损坏 JSON / 重复键 / 更新版本拒绝、文件辅助类。
 */
class SettingsExportImportTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val testKey = "pb-test-key-DO-NOT-USE-7f3c"

    private fun store(storage: FakeSettingsStorage = FakeSettingsStorage()): SettingsStore =
        SettingsStore(storage)

    private fun rendered(error: Throwable): String {
        val parts = mutableListOf(error.message.orEmpty(), error.toString())
        error.stackTrace.forEach { parts += it.toString() }
        return parts.joinToString("\n")
    }

    @Test
    fun exportedJsonHasAllThirtySevenGlobalKeysAndNeverIncludesSecretValues() {
        val storage = FakeSettingsStorage()
        val store = store(storage)
        store.load()
        store.set("theme", "sepia")
        store.set("fontSize", 24)
        store.setBook("bookA", mapOf("fontSize" to 26, "margin" to 12))
        storage.put("global.translation.apiKey", testKey)
        storage.put("global.future.access_token", testKey)

        val json = store.exportToJson()

        assertFalse(json.contains(testKey))
        assertFalse(json.contains("apiKey", ignoreCase = true))
        assertFalse(json.contains("access_token", ignoreCase = true))
        val decoded = SettingsTransferJson.decodeObject(json)
        assertEquals(39, decoded.size)
        assertEquals("sepia", decoded["theme"])
        assertEquals(24, decoded["fontSize"])
        assertEquals(26, decoded["book.bookA.fontSize"])
        assertEquals(12, decoded["book.bookA.margin"])
        for (key in SettingsSchema.DEFAULTS.keys) {
            assertTrue("missing exported key: " + key, decoded.containsKey(key))
        }
        assertFalse(decoded.containsKey("schemaVersion"))
    }

    @Test
    fun importRestoresThirtySevenKeysAndBookOverridesWhileKeepingApiKeyOut() {
        val sourceStorage = FakeSettingsStorage()
        val source = store(sourceStorage)
        source.load()
        source.set("theme", "sepia")
        source.set("fontSize", 26)
        source.set("tts.voice", "zh-CN")
        source.setBook("bookA", mapOf("fontSize" to 24, "margin" to 12))
        sourceStorage.put("global.translation.apiKey", testKey)

        val exported = source.exportToJson()
        assertFalse(exported.contains(testKey))

        val targetStorage = FakeSettingsStorage()
        val target = store(targetStorage)
        target.load()
        target.set("theme", "light")
        target.setBook("bookB", "fontSize", 30)

        val result = target.importFromJson(exported)

        assertEquals(37, result.values.asMap().size)
        assertEquals(37, target.load().asMap().size)
        assertEquals("sepia", target.getString("theme"))
        assertEquals(26, target.getInt("fontSize"))
        assertEquals("zh-CN", target.getString("tts.voice"))
        assertEquals(24, target.effective("bookA").getInt("fontSize"))
        assertEquals(12, target.effective("bookA").getInt("margin"))
        // 备份里没有 bookB，导入不能清掉已有覆盖值。
        assertEquals(30, target.effective("bookB").getInt("fontSize"))
        assertEquals(26, target.effective("bookC").getInt("fontSize"))
        assertFalse(targetStorage.snapshot().keys.any { it.contains("apiKey", ignoreCase = true) })
        assertFalse(target.exportToJson().contains(testKey))
    }

    @Test
    fun importRejectsTopLevelSecretFieldsAtomicallyWithoutLeakingValue() {
        val storage = FakeSettingsStorage()
        val store = store(storage)
        store.load()
        store.set("theme", "dark")
        val before = LinkedHashMap(storage.snapshot())
        val payload = SettingsTransferJson.encode(
            linkedMapOf("theme" to "sepia", "translation.apiKey" to testKey),
        )

        val error = assertThrows(SettingsException::class.java) { store.importFromJson(payload) }

        assertEquals(SettingsErrorCode.SECRET_KEY, error.code)
        assertFalse(rendered(error).contains(testKey))
        assertEquals(before, storage.snapshot())
        assertEquals("dark", store.getString("theme"))
        assertFalse(store.exportToJson().contains(testKey))
    }

    @Test
    fun importRejectsNestedSecretFieldsWithoutChangingStore() {
        val storage = FakeSettingsStorage()
        val store = store(storage)
        store.load()
        store.set("theme", "dark")
        val before = LinkedHashMap(storage.snapshot())
        val payload = SettingsTransferJson.encode(
            linkedMapOf(
                "theme" to "sepia",
                "futureConfig" to linkedMapOf("mode" to "safe", "token" to testKey),
            ),
        )

        val error = assertThrows(SettingsException::class.java) { store.importFromJson(payload) }

        assertEquals(SettingsErrorCode.SECRET_KEY, error.code)
        assertFalse(rendered(error).contains(testKey))
        assertEquals(before, storage.snapshot())
        assertEquals("dark", store.getString("theme"))
    }

    @Test
    fun unknownKeysArePreservedByDefaultAndRejectedOnDemand() {
        val storage = FakeSettingsStorage()
        val store = store(storage)
        store.load()
        store.set("theme", "dark")

        val preserved = store.importFromJson(
            SettingsTransferJson.encode(linkedMapOf("theme" to "sepia", "future.flag" to 7)),
        )
        assertEquals("sepia", store.getString("theme"))
        assertEquals(7, preserved.values["future.flag"])
        assertEquals(7, store.get("future.flag"))
        assertEquals(7, storage.snapshot()["global.future.flag"])

        val before = LinkedHashMap(storage.snapshot())
        val error = assertThrows(SettingsException::class.java) {
            store.importFromJson(
                SettingsTransferJson.encode(linkedMapOf("theme" to "light", "future.flag" to 8)),
                SettingsOptions(unknownKeys = UnknownKeyPolicy.REJECT),
            )
        }
        assertEquals(SettingsErrorCode.UNKNOWN_KEY, error.code)
        assertEquals(before, storage.snapshot())
        assertEquals("sepia", store.getString("theme"))

        val dropped = store.importFromJson(
            SettingsTransferJson.encode(linkedMapOf("theme" to "light", "future.flag" to 9)),
            SettingsOptions(unknownKeys = UnknownKeyPolicy.DROP),
        )
        assertFalse(dropped.values.containsKey("future.flag"))
        assertFalse(storage.snapshot().containsKey("global.future.flag"))
        assertEquals("light", store.getString("theme"))
    }

    @Test
    fun unknownBookKeysArePreservedButGlobalKeysInBookRowsAreRejected() {
        val storage = FakeSettingsStorage()
        val store = store(storage)
        store.load()

        val result = store.importFromJson(
            SettingsTransferJson.encode(linkedMapOf("book.bookA.future.flag" to 7)),
        )
        assertEquals(7, result.bookValues["bookA"]?.get("future.flag"))
        assertEquals(7, store.getBook("bookA")["future.flag"])

        val before = LinkedHashMap(storage.snapshot())
        val error = assertThrows(SettingsException::class.java) {
            store.importFromJson(
                SettingsTransferJson.encode(linkedMapOf("book.bookA.theme" to "light")),
            )
        }
        assertEquals(SettingsErrorCode.WRONG_SCOPE, error.code)
        assertEquals(before, storage.snapshot())
    }

    @Test
    fun malformedDuplicateAndNewerVersionImportsFailWithoutChangingSettings() {
        val storage = FakeSettingsStorage()
        val store = store(storage)
        store.load()
        store.set("theme", "dark")

        val malformed = "{\"theme\":\"sepia\",\"translation.apiKey\":\"" + testKey
        var error = assertThrows(SettingsException::class.java) { store.importFromJson(malformed) }
        assertEquals(SettingsErrorCode.INVALID_VALUE, error.code)
        assertFalse(rendered(error).contains(testKey))

        error = assertThrows(SettingsException::class.java) {
            store.importFromJson("{\"theme\":\"light\",\"theme\":\"sepia\"}")
        }
        assertEquals(SettingsErrorCode.INVALID_VALUE, error.code)

        error = assertThrows(SettingsException::class.java) {
            store.importFromJson("{\"schemaVersion\":99,\"theme\":\"light\"}")
        }
        assertEquals(SettingsErrorCode.VERSION_UNSUPPORTED, error.code)

        error = assertThrows(SettingsException::class.java) { store.importFromJson("[1,2]") }
        assertEquals(SettingsErrorCode.INVALID_VALUE, error.code)
        assertEquals("dark", store.getString("theme"))

        store.importFromJson("{\"schemaVersion\":1,\"theme\":\"sepia\"}")
        assertEquals("sepia", store.getString("theme"))
    }

    @Test
    fun exportFileWritesSettingsExportJsonToDirectoryAndRestoresIt() {
        val directory = File(temporaryFolder.root, "files")
        val exportFile = SettingsExportFile(directory)
        val sourceStorage = FakeSettingsStorage()
        val source = store(sourceStorage)
        source.load()
        source.set("theme", "sepia")
        source.set("accentColor", "green")
        sourceStorage.put("global.translation.apiKey", testKey)

        val written = exportFile.write(source)

        assertEquals(SettingsExportFile.FILE_NAME, written.name)
        assertEquals(File(directory, SettingsExportFile.FILE_NAME).absolutePath, written.absolutePath)
        assertTrue(written.isFile)
        assertFalse(written.readText(Charsets.UTF_8).contains(testKey))
        assertEquals(
            0,
            directory.listFiles().orEmpty().count { it.name.endsWith(".tmp") },
        )

        val targetStorage = FakeSettingsStorage()
        val target = store(targetStorage)
        val result = exportFile.restore(target)

        assertEquals(37, result.values.asMap().size)
        assertEquals("sepia", target.getString("theme"))
        assertEquals("green", target.getString("accentColor"))
        assertFalse(targetStorage.snapshot().keys.any { it.contains("apiKey", ignoreCase = true) })
    }

    @Test
    fun settingsBackupNeverCarriesSecretStoreApiKeyAndRestoreDoesNotRecreateIt() {
        val secretStore = FakeSecretStore()
        val bridge = SettingsSecretBridge(secretStore)
        bridge.putApiKey(testKey)
        assertEquals(testKey, bridge.getApiKey())

        val source = store()
        source.load()
        source.set("translation.provider", "openai")
        val exported = source.exportToJson()
        assertFalse(exported.contains(testKey))

        val restoredSecretStore = FakeSecretStore()
        val restoredBridge = SettingsSecretBridge(restoredSecretStore)
        val target = store()
        target.importFromJson(exported)

        assertEquals(37, target.load().asMap().size)
        assertEquals("openai", target.getString("translation.provider"))
        assertNull(restoredBridge.getApiKey())
        assertFalse(exported.contains(testKey))
    }

    @Test
    fun exportFileRestoreRejectsMissingFile() {
        val exportFile = SettingsExportFile(File(temporaryFolder.root, "missing"))
        val error = assertThrows(FileNotFoundException::class.java) {
            exportFile.restore(store())
        }
        assertNotNull(error.message)
    }
}
