package app.particlebook.android.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A023-6：5 个书级键（fontSize/fontFamily/lineHeight/margin/textAlign）的隔离。
 *
 * 三组值：全局默认 18 等；book A 覆盖 24 等；book B 不覆盖 = 默认。
 * 任何一本书的覆盖值都不能影响全局或其他书。
 */
class SettingsBookIsolationTest {

    private val defaultFontFamily = "Georgia, Noto Serif SC, serif"

    private val bookAOverrides = linkedMapOf<String, Any?>(
        "fontSize" to 24,
        "fontFamily" to "Book A Serif",
        "lineHeight" to 2.4,
        "margin" to 12,
        "textAlign" to "left",
    )

    private fun assertGlobalDefaults(store: SettingsStore) {
        val values = store.load()
        assertEquals(5, SettingsSchema.BOOK_KEYS.size)
        assertEquals(18, values.getInt("fontSize"))
        assertEquals(defaultFontFamily, values.getString("fontFamily"))
        assertEquals(1.8, values.getDouble("lineHeight"), 0.0)
        assertEquals(40, values.getInt("margin"))
        assertEquals("justify", values.getString("textAlign"))
    }

    private fun assertBookDefaults(store: SettingsStore, bookId: String) {
        val effective = store.effective(bookId)
        assertEquals(18, effective.getInt("fontSize"))
        assertEquals(defaultFontFamily, effective.getString("fontFamily"))
        assertEquals(1.8, effective.getDouble("lineHeight"), 0.0)
        assertEquals(40, effective.getInt("margin"))
        assertEquals("justify", effective.getString("textAlign"))
    }

    private fun assertBookAOverrides(store: SettingsStore) {
        val effective = store.effective("bookA")
        assertEquals(24, effective.getInt("fontSize"))
        assertEquals("Book A Serif", effective.getString("fontFamily"))
        assertEquals(2.4, effective.getDouble("lineHeight"), 0.0)
        assertEquals(12, effective.getInt("margin"))
        assertEquals("left", effective.getString("textAlign"))
    }

    @Test
    fun fiveBookScopedKeysAreIsolatedBetweenGlobalAndTwoBooks() {
        val storage = FakeSettingsStorage()
        val store = SettingsStore(storage)
        assertGlobalDefaults(store)

        store.setBook("bookA", bookAOverrides)

        assertGlobalDefaults(store)
        assertBookAOverrides(store)
        assertBookDefaults(store, "bookB")
        assertFalse(storage.snapshot().keys.any { it.startsWith("book.bookB.") })
        assertFalse(
            storage.snapshot().keys.any { key ->
                key.startsWith("book.bookA.") && key.removePrefix("book.bookA.") !in SettingsSchema.BOOK_KEYS
            },
        )
    }

    @Test
    fun removingBookOverridesFallsBackToGlobalOrDefaultWithoutLeaking() {
        val storage = FakeSettingsStorage()
        val store = SettingsStore(storage)
        store.setBook("bookA", bookAOverrides)
        store.setBook("bookB", "fontSize", 30)

        store.resetBook("bookA", "fontSize")
        assertEquals(18, store.effective("bookA").getInt("fontSize"))
        assertEquals("Book A Serif", store.effective("bookA").getString("fontFamily"))

        store.resetBook("bookA")
        assertBookAOverridesNotPresent(store)
        assertBookDefaults(store, "bookA")
        assertEquals(30, store.effective("bookB").getInt("fontSize"))

        store.resetBook("bookB")
        assertBookDefaults(store, "bookB")
        assertBookDefaults(store, "bookC")
        assertTrue(storage.snapshot().keys.none { it.startsWith("book.bookA.") })
        assertTrue(storage.snapshot().keys.none { it.startsWith("book.bookB.") })
    }

    @Test
    fun globalChangeOnlyAffectsBooksWithoutOwnOverrides() {
        val store = SettingsStore(FakeSettingsStorage())
        store.setBook("bookA", "fontSize", 24)

        store.set("fontSize", 20)

        assertEquals(20, store.load().getInt("fontSize"))
        assertEquals(24, store.effective("bookA").getInt("fontSize"))
        assertEquals(20, store.effective("bookB").getInt("fontSize"))
        assertEquals(18, store.getBook("bookB").getInt("fontSize"))
    }

    @Test
    fun onlyTheFiveBookScopedKeysCanBeOverridden() {
        val store = SettingsStore(FakeSettingsStorage())
        val error = assertThrows(SettingsException::class.java) {
            store.setBook("bookA", "theme", "light")
        }
        assertEquals(SettingsErrorCode.WRONG_SCOPE, error.code)
        val unknown = assertThrows(SettingsException::class.java) {
            store.setBook("bookA", "future.flag", true)
        }
        assertEquals(SettingsErrorCode.UNKNOWN_KEY, unknown.code)
        assertEquals("dark", store.getString("theme"))
    }

    private fun assertBookAOverridesNotPresent(store: SettingsStore) {
        val book = store.getBook("bookA")
        assertFalse(book.containsKey("fontSize"))
        assertFalse(book.containsKey("fontFamily"))
        assertFalse(book.containsKey("lineHeight"))
        assertFalse(book.containsKey("margin"))
        assertFalse(book.containsKey("textAlign"))
    }
}
