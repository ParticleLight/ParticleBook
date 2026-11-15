package app.particlebook.android.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsStoreTest {

    private fun store(
        storage: FakeSettingsStorage,
        legacy: SettingsLegacySource? = null,
        options: SettingsOptions = SettingsOptions(),
    ) = SettingsStore(storage, legacy, options)

    @Test
    fun firstLoadPersistsThirtySevenDefaultsAndSchemaVersion() {
        val storage = FakeSettingsStorage()
        val loaded = store(storage).load()

        assertEquals(37, loaded.asMap().size)
        assertEquals("dark", loaded["theme"])
        assertEquals(18, loaded["fontSize"])
        assertEquals(1, storage.snapshot()["global.schemaVersion"])
        assertEquals("dark", storage.snapshot()["global.theme"])
        assertEquals(37, storage.snapshot().keys.count { it.startsWith("global.") } - 1)
    }

    @Test
    fun setPersistsAndSurvivesReopen() {
        val storage = FakeSettingsStorage()
        val first = store(storage)
        first.set("theme", "sepia")
        first.set("fontSize", 24)
        first.set("tts.voice", "zh-CN")

        val second = store(storage)
        assertEquals("sepia", second.getString("theme"))
        assertEquals(24, second.getInt("fontSize"))
        assertEquals("zh-CN", second.getString("tts.voice"))
    }

    @Test
    fun numericRangeIsClampedWhenSet() {
        val storage = FakeSettingsStorage()
        val s = store(storage)
        assertEquals(32, s.set("fontSize", 99).getInt("fontSize"))
        assertEquals(0, s.set("tts.volume", -4).getInt("tts.volume"))
        assertEquals(10, s.set("tts.rate", 99).getInt("tts.rate"))
        assertEquals(32, storage.snapshot()["global.fontSize"])
    }

    @Test
    fun unknownEnumFallsBackAndIsPersisted() {
        val storage = FakeSettingsStorage()
        val s = store(storage)
        assertEquals("dark", s.set("theme", "neon").getString("theme"))
        assertEquals("grid", s.set("defaultViewMode", "carousel").getString("defaultViewMode"))
        assertEquals("dark", storage.snapshot()["global.theme"])
    }

    @Test
    fun unknownAndSecretKeysAreRejected() {
        val storage = FakeSettingsStorage()
        val s = store(storage)
        var error = assertThrows(SettingsException::class.java) { s.set("future.flag", true) }
        assertEquals(SettingsErrorCode.UNKNOWN_KEY, error.code)

        error = assertThrows(SettingsException::class.java) {
            s.set("translation.apiKey", "pb-test-key-DO-NOT-USE")
        }
        assertEquals(SettingsErrorCode.SECRET_KEY, error.code)
        assertFalse(storage.snapshot().keys.any { it.contains("apiKey") })
    }

    @Test
    fun invalidTypeAndUrlAreRejectedWithoutOverwriting() {
        val storage = FakeSettingsStorage()
        val s = store(storage)
        var error = assertThrows(SettingsException::class.java) { s.set("tts.volume", "loud") }
        assertEquals(SettingsErrorCode.INVALID_VALUE, error.code)
        assertEquals(100, storage.snapshot()["global.tts.volume"])

        error = assertThrows(SettingsException::class.java) {
            s.set("translation.baseUrl", "ftp://example.com")
        }
        assertEquals(SettingsErrorCode.INVALID_VALUE, error.code)
        assertEquals("", storage.snapshot()["global.translation.baseUrl"])
    }

    @Test
    fun booleanAndStringGettersUseDefaultsAndCanonicalTypes() {
        val s = store(FakeSettingsStorage())
        assertTrue(s.getBool("autoSaveProgress"))
        assertFalse(s.getBool("tts.enabled"))
        assertEquals(30, s.getInt("reading.dailyGoalMinutes"))
        assertEquals(1.8, s.getDouble("lineHeight"), 0.0)
        assertEquals("zh", s.getString("language"))
        assertNull(s.get("not.a.real.key"))
    }

    @Test
    fun legacyKeysMigrateAndKeepBackupBeforeOverwrite() {
        val storage = FakeSettingsStorage(
            mapOf(
                "global.theme_mode" to "Light",
                "global.font_size" to "22",
                "global.line_height" to "2.2",
                "global.default_sort" to "Author",
            ),
        )
        val s = store(storage)
        val detailed = s.loadDetailed()
        assertEquals(0, detailed.sourceVersion)
        assertTrue(detailed.migrated)
        assertEquals("light", detailed.values["theme"])
        assertEquals(22, detailed.values["fontSize"])
        assertEquals(2.2, detailed.values["lineHeight"])
        assertEquals("author", detailed.values["defaultSortBy"])
        assertEquals(1, storage.snapshot()["global.schemaVersion"])
        assertFalse(storage.contains("global.theme_mode"))

        val backup = s.legacyBackup()
        assertEquals("Light", backup["global.theme_mode"])
        assertEquals("22", backup["global.font_size"])
        assertEquals("Author", backup["global.default_sort"])
    }

    @Test
    fun legacySourceIsReadOnceAndBackedUp() {
        val storage = FakeSettingsStorage()
        val legacy = FakeLegacySettingsSource(
            mapOf("theme_mode" to "Sepia", "font_size" to 20, "default_sort" to "Title"),
        )
        val s = store(storage, legacy)
        val detailed = s.loadDetailed()
        assertEquals(0, detailed.sourceVersion)
        assertEquals("sepia", detailed.values["theme"])
        assertEquals(20, detailed.values["fontSize"])
        assertEquals("title", detailed.values["defaultSortBy"])
        val backup = s.legacyBackup()
        assertEquals("Sepia", backup["legacy.theme_mode"])
        assertEquals(20, backup["legacy.font_size"])

        // 二次 load 不再读取 legacy，已有备份也不会被覆盖。
        val again = store(storage, FakeLegacySettingsSource(mapOf("theme_mode" to "Dark"))).load()
        assertEquals("sepia", again["theme"])
        assertEquals("Sepia", store(storage).legacyBackup()["legacy.theme_mode"])
    }

    @Test
    fun canonicalKeyWinsOverLegacySource() {
        val storage = FakeSettingsStorage(mapOf("global.schemaVersion" to 1, "global.theme" to "sepia"))
        val s = store(storage, FakeLegacySettingsSource(mapOf("theme_mode" to "light")))
        assertEquals("sepia", s.getString("theme"))
        assertEquals(0, s.loadDetailed().issues.count { it.key == "theme_mode" })
    }

    @Test
    fun unknownKeysArePreservedAcrossWritesAndExport() {
        val storage = FakeSettingsStorage(
            mapOf("global.schemaVersion" to 1, "global.customFlag" to true),
        )
        val s = store(storage)
        assertTrue(s.get("customFlag") as Boolean)
        s.set("theme", "light")
        assertEquals(true, storage.snapshot()["global.customFlag"])
        assertEquals(true, s.exportRedacted()["customFlag"])
    }

    @Test
    fun secretLikeKeysAlreadyInStorageAreDroppedAndNeverExported() {
        val testKey = "pb-test-key-DO-NOT-USE-7f3c"
        val storage = FakeSettingsStorage(
            mapOf(
                "global.schemaVersion" to 1,
                "global.theme" to "sepia",
                "global.translation.apiKey" to testKey,
                "global.future.token" to testKey,
            ),
        )
        val s = store(storage)
        val loaded = s.load()
        assertFalse(loaded.containsKey("translation.apiKey"))
        assertFalse(loaded.containsKey("future.token"))
        s.set("theme", "light")
        assertFalse(storage.snapshot().containsKey("global.translation.apiKey"))
        assertFalse(storage.snapshot().containsKey("global.future.token"))
        assertFalse(s.exportRedacted().toString().contains(testKey))
    }

    @Test
    fun exportRedactsUrlTokensAndSecretFields() {
        val storage = FakeSettingsStorage(
            mapOf(
                "global.schemaVersion" to 1,
                "global.translation.baseUrl" to "https://api.example.com/v1?token=PB-URL-SECRET",
                "global.future.mode" to "safe",
                "global.future.access_token" to "PB-NESTED-SECRET",
            ),
        )
        val exported = store(storage).exportRedacted()
        val baseUrl = exported["translation.baseUrl"] as String
        assertTrue(baseUrl.contains("token=<redacted>"))
        assertFalse(baseUrl.contains("PB-URL-SECRET"))
        assertEquals("safe", exported["future.mode"])
        assertFalse(exported.containsKey("future.access_token"))
        assertFalse(exported.toString().contains("PB-NESTED-SECRET"))
    }

    @Test
    fun bookOverridesDoNotLeakAcrossBooks() {
        val s = store(FakeSettingsStorage())
        s.setBook("bookA", "fontSize", 24)
        s.setBook("bookB", "fontSize", 28)
        s.setBook("bookA", "margin", 12)

        assertEquals(24, s.getBook("bookA").getInt("fontSize"))
        assertEquals(12, s.getBook("bookA").getInt("margin"))
        assertFalse(s.getBook("bookA").containsKey("fontFamily"))
        assertEquals(28, s.getBook("bookB").getInt("fontSize"))
        assertEquals(40, s.getBook("bookB").getInt("margin"))

        assertEquals(24, s.effective("bookA").getInt("fontSize"))
        assertEquals(12, s.effective("bookA").getInt("margin"))
        assertEquals("Georgia, Noto Serif SC, serif", s.effective("bookA").getString("fontFamily"))
        assertEquals(28, s.effective("bookB").getInt("fontSize"))
        assertEquals(40, s.effective("bookB").getInt("margin"))
        assertEquals(18, s.effective("bookC").getInt("fontSize"))
    }

    @Test
    fun bookScopedWritesRejectGlobalAndSecretKeys() {
        val s = store(FakeSettingsStorage())
        var error = assertThrows(SettingsException::class.java) {
            s.setBook("bookA", "tts.enabled", true)
        }
        assertEquals(SettingsErrorCode.WRONG_SCOPE, error.code)

        error = assertThrows(SettingsException::class.java) {
            s.setBook("bookA", "translation.apiKey", "pb-test-key")
        }
        assertEquals(SettingsErrorCode.SECRET_KEY, error.code)

        error = assertThrows(SettingsException::class.java) {
            s.setBook("bookA", "unknown.book.key", 1)
        }
        assertEquals(SettingsErrorCode.UNKNOWN_KEY, error.code)
    }

    @Test
    fun batchBookWriteIsAtomicWhenOneValueIsInvalid() {
        val storage = FakeSettingsStorage()
        val s = store(storage)
        val error = assertThrows(SettingsException::class.java) {
            s.setBook("bookA", mapOf("fontSize" to 24, "margin" to "bad"))
        }
        assertEquals(SettingsErrorCode.INVALID_VALUE, error.code)
        assertFalse(storage.snapshot().keys.any { it.startsWith("book.bookA.") })
        assertTrue(s.getBook("bookA").asMap().isEmpty())
    }

    @Test
    fun resetBookKeyAndWholeBookWork() {
        val storage = FakeSettingsStorage()
        val s = store(storage)
        s.setBook("bookA", "fontSize", 24)
        s.setBook("bookA", "margin", 12)
        s.setBook("bookB", "fontSize", 30)

        s.resetBook("bookA", "fontSize")
        assertEquals(12, s.getBook("bookA").getInt("margin"))
        assertFalse(s.getBook("bookA").containsKey("fontSize"))

        s.resetBook("bookA")
        assertTrue(s.getBook("bookA").asMap().isEmpty())
        assertFalse(storage.snapshot().keys.any { it.startsWith("book.bookA.") })
        assertEquals(30, s.getBook("bookB").getInt("fontSize"))
    }

    @Test
    fun resetGlobalAndResetAllKeepUnknownAndBooks() {
        val storage = FakeSettingsStorage()
        val s = store(storage)
        s.set("theme", "sepia")
        s.set("accentColor", "green")
        s.setBook("bookA", "fontSize", 24)
        storage.put("global.customFlag", "keep")

        assertEquals("dark", s.reset("theme").getString("theme"))
        assertEquals("blue", s.reset("accentColor").getString("accentColor"))

        s.resetAll()
        assertEquals("dark", s.getString("theme"))
        assertEquals("keep", s.getString("customFlag"))
        assertEquals(24, s.getBook("bookA").getInt("fontSize"))
    }

    @Test
    fun newerStorageVersionIsRejectedWithoutChange() {
        val storage = FakeSettingsStorage(
            mapOf("global.schemaVersion" to 99, "global.theme" to "light"),
        )
        val s = store(storage)
        val error = assertThrows(SettingsVersionException::class.java) { s.load() }
        assertEquals(99, error.foundVersion)
        assertEquals(99, storage.snapshot()["global.schemaVersion"])
        assertEquals("light", storage.snapshot()["global.theme"])
    }

    @Test
    fun invalidBookIdsAreRejected() {
        val s = store(FakeSettingsStorage())
        var error = assertThrows(SettingsException::class.java) { s.getBook(" ") }
        assertEquals(SettingsErrorCode.INVALID_BOOK_ID, error.code)

        error = assertThrows(SettingsException::class.java) { s.setBook("x".repeat(513), "fontSize", 20) }
        assertEquals(SettingsErrorCode.INVALID_BOOK_ID, error.code)
    }

    @Test
    fun clearAllWipesStorageAndReloadsDefaults() {
        val storage = FakeSettingsStorage()
        val s = store(storage)
        s.set("theme", "sepia")
        s.setBook("bookA", "fontSize", 24)
        s.clearAll()
        assertEquals(0, storage.size())

        val reloaded = s.load()
        assertEquals("dark", reloaded["theme"])
        assertEquals(37, reloaded.asMap().size)
        assertEquals(1, storage.snapshot()["global.schemaVersion"])
    }

    @Test
    fun concurrentWritersAreSerializedByStoreLock() {
        val storage = FakeSettingsStorage()
        val s = store(storage)
        val failures = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val threads = (0 until 4).map { worker ->
            Thread {
                try {
                    repeat(20) { index ->
                        s.set("tts.voice", "voice-" + worker + "-" + index)
                        s.set("tts.rate", (index % 21) - 10)
                    }
                } catch (t: Throwable) {
                    failures.compareAndSet(null, t)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertNull(failures.get())
        assertEquals(37, s.load().asMap().size)
        assertTrue(storage.snapshot()["global.tts.voice"] is String)
    }
}