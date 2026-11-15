package app.particlebook.android.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSchemaTest {

    @Test
    fun globalSpecsHaveExactlyThirtySevenKeysIncludingFiveBookKeys() {
        assertEquals(37, SettingsSchema.GLOBAL_SPECS.size)
        assertEquals(37, SettingsSchema.DEFAULTS.size)
        assertEquals(5, SettingsSchema.BOOK_SPECS.size)
        assertEquals(
            linkedSetOf("fontSize", "fontFamily", "lineHeight", "margin", "textAlign"),
            SettingsSchema.BOOK_KEYS,
        )
        assertEquals(37, SettingsSchema.normalize(emptyMap()).asMap().size)
        assertFalse(SettingsSchema.normalize(emptyMap()).containsKey(SettingsSchema.VERSION_KEY))
    }

    @Test
    fun everySpecHasValidDefaultAndRequiredRangeOrEnum() {
        for (spec in SettingsSchema.GLOBAL_SPECS) {
            assertTrue(spec.key.isNotBlank())
            when (spec.type) {
                SettingType.BOOL -> assertTrue(spec.defaultValue is Boolean)
                SettingType.INT -> {
                    assertTrue(spec.defaultValue is Int)
                    assertNotNull(spec.minValue)
                    assertNotNull(spec.maxValue)
                    assertTrue(spec.minValue!! <= spec.maxValue!!)
                }
                SettingType.DOUBLE -> {
                    assertTrue(spec.defaultValue is Double)
                    assertNotNull(spec.minValue)
                    assertNotNull(spec.maxValue)
                    assertTrue(spec.minValue!! <= spec.maxValue!!)
                }
                SettingType.STRING -> assertTrue(spec.defaultValue is String)
                SettingType.ENUM -> {
                    assertTrue(spec.defaultValue is String)
                    assertTrue(spec.defaultValue in spec.enumValues)
                }
            }
        }
    }

    @Test
    fun allThirtySevenDefaultsAreExact() {
        val expected: Map<String, Any> = linkedMapOf(
            "theme" to "dark",
            "accentColor" to "blue",
            "fontSize" to 18,
            "fontFamily" to "Georgia, Noto Serif SC, serif",
            "lineHeight" to 1.8,
            "margin" to 40,
            "textAlign" to "justify",
            "autoSaveProgress" to true,
            "showReadingTime" to true,
            "defaultViewMode" to "grid",
            "defaultSortBy" to "last_opened",
            "language" to "zh",
            "library.coverCacheSize" to 300,
            "tts.enabled" to false,
            "tts.voice" to "",
            "tts.rate" to 0,
            "tts.volume" to 100,
            "tts.highlightSentence" to true,
            "tts.sleepTimerMinutes" to 0,
            "translation.enabled" to false,
            "translation.provider" to "openai",
            "translation.baseUrl" to "",
            "translation.model" to "",
            "translation.apiFormat" to "chat_completion",
            "translation.targetLanguage" to "zh",
            "translation.timeoutSeconds" to 60,
            "reading.dailyGoalMinutes" to 30,
            "reading.weeklyGoalMinutes" to 180,
            "dictionary.enabled" to true,
            "dictionary.provider" to "local",
            "dictionary.autoLookup" to false,
            "dictionary.maxResults" to 5,
            "backup.enabled" to true,
            "backup.path" to "",
            "backup.intervalDays" to 7,
            "backup.keepCount" to 5,
            "backup.lastBackupAt" to "",
        )
        assertEquals(37, expected.size)
        assertEquals(expected, SettingsSchema.defaults().asMap())
    }

    @Test
    fun desktopDefaultsAreMirrored() {
        val values = SettingsSchema.defaults()
        assertEquals("dark", values["theme"])
        assertEquals("blue", values["accentColor"])
        assertEquals(18, values["fontSize"])
        assertEquals("Georgia, Noto Serif SC, serif", values["fontFamily"])
        assertEquals(1.8, values["lineHeight"])
        assertEquals(40, values["margin"])
        assertEquals("justify", values["textAlign"])
        assertEquals("grid", values["defaultViewMode"])
        assertEquals("last_opened", values["defaultSortBy"])
        assertEquals(300, values["library.coverCacheSize"])
        assertEquals(60, values["translation.timeoutSeconds"])
        assertEquals(5, values["backup.keepCount"])
        assertEquals("", values["backup.lastBackupAt"])
    }

    @Test
    fun rangeValuesAreClampedWithWarnings() {
        val result = SettingsSchema.normalizeDetailed(
            mapOf(
                "fontSize" to 999,
                "tts.volume" to -20,
                "lineHeight" to 0.5,
                "library.coverCacheSize" to 10,
            ),
        )
        assertEquals(32, result.values["fontSize"])
        assertEquals(0, result.values["tts.volume"])
        assertEquals(1.2, result.values["lineHeight"])
        assertEquals(50, result.values["library.coverCacheSize"])
        assertEquals(4, result.issues.count { it.code == "range" })
        assertFalse(result.hasErrors)
    }

    @Test
    fun unknownEnumFallsBackToDefaultAndInvalidScalarFallsBackForNormalize() {
        val enums = SettingsSchema.normalize(
            mapOf("theme" to "neon", "defaultSortBy" to "rating", "defaultViewMode" to "carousel"),
        )
        assertEquals("dark", enums["theme"])
        assertEquals("last_opened", enums["defaultSortBy"])
        assertEquals("grid", enums["defaultViewMode"])

        val invalid = SettingsSchema.normalizeDetailed(mapOf("tts.volume" to "loud"))
        assertEquals(100, invalid.values["tts.volume"])
        assertTrue(invalid.issues.any { it.key == "tts.volume" && it.code == "type" })
        assertTrue(invalid.hasErrors)
    }

    @Test
    fun legacyTypesAreMigratedOnlyWhenEnabled() {
        val migrated = SettingsSchema.normalize(
            mapOf("autoSaveProgress" to "false", "tts.rate" to "-3", "lineHeight" to "2.0"),
        )
        assertEquals(false, migrated["autoSaveProgress"])
        assertEquals(-3, migrated["tts.rate"])
        assertEquals(2.0, migrated["lineHeight"])

        val strict = SettingsSchema.normalize(
            mapOf("autoSaveProgress" to "false"),
            options = SettingsOptions(migrateLegacyTypes = false),
        )
        assertEquals(true, strict["autoSaveProgress"])
    }

    @Test
    fun legacyKeysAndAndroidValuesAreMigrated() {
        val result = SettingsSchema.normalizeDetailed(
            mapOf(
                "theme_mode" to "System",
                "font_size" to "22",
                "line_height" to "2.2",
                "default_sort" to "Rating",
            ),
        )
        assertEquals(0, result.sourceVersion)
        assertTrue(result.migrated)
        assertEquals("dark", result.values["theme"])
        assertEquals(22, result.values["fontSize"])
        assertEquals(2.2, result.values["lineHeight"])
        assertEquals("last_opened", result.values["defaultSortBy"])
        assertTrue(result.issues.any { it.key == "theme_mode" && it.code == "migrated" })
        assertTrue(result.issues.any { it.key == "font_size" && it.code == "migrated" })
    }

    @Test
    fun canonicalKeyWinsOverLegacyKey() {
        val result = SettingsSchema.normalizeDetailed(
            mapOf("theme" to "sepia", "theme_mode" to "light"),
        )
        assertEquals("sepia", result.values["theme"])
        assertTrue(result.issues.any { it.key == "theme_mode" && it.code == "migrated" })
    }

    @Test
    fun newerSchemaVersionIsAnErrorAndKeepsVersion() {
        val result = SettingsSchema.normalizeDetailed(mapOf("schemaVersion" to 99, "theme" to "light"))
        assertEquals(99, result.sourceVersion)
        assertTrue(result.hasErrors)
        assertEquals("light", result.values["theme"])
    }

    @Test
    fun unknownKeysPolicyIsExplicit() {
        val preserved = SettingsSchema.normalizeDetailed(mapOf("future.flag" to 7))
        assertEquals(7, preserved.values["future.flag"])
        assertTrue(preserved.issues.any { it.key == "future.flag" && it.code == "unknown" })

        val dropped = SettingsSchema.normalize(
            mapOf("future.flag" to 7),
            options = SettingsOptions(unknownKeys = UnknownKeyPolicy.DROP),
        )
        assertFalse(dropped.containsKey("future.flag"))

        val rejected = SettingsSchema.normalizeDetailed(
            mapOf("future.flag" to 7),
            options = SettingsOptions(unknownKeys = UnknownKeyPolicy.REJECT),
        )
        assertFalse(rejected.values.containsKey("future.flag"))
        assertTrue(rejected.hasErrors)
    }

    @Test
    fun secretLikeKeysAndNestedSecretsNeverReachSettings() {
        val topLevel = SettingsSchema.normalizeDetailed(
            mapOf("translation.apiKey" to "pb-test-key-DO-NOT-USE", "theme" to "light"),
        )
        assertFalse(topLevel.values.containsKey("translation.apiKey"))
        assertTrue(topLevel.hasErrors)
        assertEquals("light", topLevel.values["theme"])

        val nested = SettingsSchema.normalizeDetailed(
            mapOf("futureConfig" to mapOf("token" to "pb-test-key-DO-NOT-USE", "mode" to "safe")),
        )
        assertFalse(nested.values.containsKey("futureConfig"))
        assertTrue(nested.issues.any { it.key == "futureConfig.token" && it.code == "secret" })
    }

    @Test
    fun secretLikeKeyDetectionMatchesDesktopBoundary() {
        assertTrue(SettingsSchema.isSecretLikeKey("translation.apiKey"))
        assertTrue(SettingsSchema.isSecretLikeKey("api_key"))
        assertTrue(SettingsSchema.isSecretLikeKey("openaiApiKey"))
        assertTrue(SettingsSchema.isSecretLikeKey("session.token"))
        assertTrue(SettingsSchema.isSecretLikeKey("clientSecret"))
        assertFalse(SettingsSchema.isSecretLikeKey("translation.provider"))
        assertFalse(SettingsSchema.isSecretLikeKey("backup.path"))
        assertFalse(SettingsSchema.isSecretLikeKey("monkey"))
        assertFalse(SettingsSchema.isSecretLikeKey(""))
    }

    @Test
    fun urlValidationRejectsNonHttpAndWarnsOnCredentials() {
        val bad = SettingsSchema.normalizeDetailed(mapOf("translation.baseUrl" to "ftp://example.com"))
        assertEquals("", bad.values["translation.baseUrl"])
        assertTrue(bad.hasErrors)

        val good = SettingsSchema.normalize(
            mapOf("translation.baseUrl" to "https://api.example.com/v1?token=abc"),
        )
        assertEquals("https://api.example.com/v1?token=abc", good["translation.baseUrl"])

        val credential = SettingsSchema.normalizeDetailed(
            mapOf("translation.baseUrl" to "https://user:pass@example.com/v1"),
        )
        assertEquals("https://user:pass@example.com/v1", credential.values["translation.baseUrl"])
        assertTrue(credential.issues.any { it.key == "translation.baseUrl" && it.code == "url" })
        assertFalse(credential.hasErrors)
    }

    @Test
    fun bookScopeIgnoresGlobalKeysAndDoesNotFillDefaults() {
        val result = SettingsSchema.normalizeDetailed(
            mapOf("fontSize" to 24, "theme" to "light", "unknown" to 1),
            SettingsScope.BOOK,
            SettingsOptions(unknownKeys = UnknownKeyPolicy.DROP),
        )
        assertEquals(24, result.values["fontSize"])
        assertFalse(result.values.containsKey("theme"))
        assertFalse(result.values.containsKey("defaultSortBy"))
        assertFalse(result.values.containsKey("unknown"))
        assertTrue(result.issues.any { it.key == "theme" && it.code == "scope" })
    }

    @Test
    fun bookInvalidValuesAreOmittedWithError() {
        val result = SettingsSchema.normalizeDetailed(
            mapOf("margin" to "not-a-number", "lineHeight" to 9.0),
            SettingsScope.BOOK,
        )
        assertFalse(result.values.containsKey("margin"))
        assertEquals(3.0, result.values["lineHeight"])
        assertTrue(result.hasErrors)
    }

    @Test
    fun resolveEffectiveMergesOnlyBookScopedOverrides() {
        val global = SettingsSchema.defaults()
        val book = SettingsValues(
            mapOf("fontSize" to 26, "theme" to "light", "unknown" to 1, "margin" to 8),
        )
        val effective = SettingsSchema.resolveEffective(global, book)
        assertEquals(26, effective["fontSize"])
        assertEquals(8, effective["margin"])
        assertEquals("dark", effective["theme"])
        assertFalse(effective.containsKey("unknown"))
    }

    @Test
    fun specLookupAndHelpersAreConsistent() {
        assertNotNull(SettingsSchema.find("translation.timeoutSeconds"))
        assertNull(SettingsSchema.find("translation.apiKey"))
        assertTrue(SettingsSchema.isBookKey("textAlign"))
        assertFalse(SettingsSchema.isBookKey("tts.enabled"))
        assertEquals(5, SettingsSchema.bookDefaults().asMap().size)
        assertTrue(SettingsSchema.containsSecretLikeKey(mapOf("x" to mapOf("password" to "p"))))
        assertThrows(IllegalArgumentException::class.java) {
            SettingSpec("bad", SettingType.INT, 0)
        }
    }
}