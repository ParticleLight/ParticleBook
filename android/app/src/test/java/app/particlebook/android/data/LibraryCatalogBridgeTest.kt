package app.particlebook.android.data

import app.particlebook.android.ui.data.Book
import app.particlebook.android.ui.data.BookFormat
import app.particlebook.android.ui.data.LibraryCatalogBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** P1 旧书架 Book → SQLite BookRow 的字段映射（纯 JVM，不读写 books.json）。 */
class LibraryCatalogBridgeTest {

    @Test
    fun mapsBookFieldsAndUsesStableSystemTagAsIdentity() {
        val addedAt = Instant.parse("2024-01-02T03:04:05Z").toEpochMilli()
        val rows = LibraryCatalogBridge.bookRows(
            listOf(
                Book(
                    id = 7,
                    title = "三体",
                    author = "刘慈欣",
                    format = BookFormat.Epub,
                    filePath = "/storage/books/三体.epub",
                    fileSize = 123456,
                    addedAt = "2024-01-02T03:04:05Z",
                    tags = listOf("zlib:42", "科幻"),
                ),
            ),
            now = 777L,
        )

        val row = rows.single()
        assertEquals(7L, row.id)
        assertEquals("zlib:42", row.stableKey)
        assertEquals(7L, row.legacyId)
        assertEquals("三体", row.title)
        assertEquals("三体", row.sortTitle)
        assertEquals("刘慈欣", row.author)
        assertEquals("epub", row.format)
        assertEquals("/storage/books/三体.epub", row.filePath)
        assertEquals("三体.epub", row.fileName)
        assertEquals(123456L, row.fileSize)
        assertEquals("local", row.source)
        assertEquals(addedAt, row.addedAt)
        assertEquals(777L, row.updatedAt)
        assertEquals(0L, row.fileMtime)
    }

    @Test
    fun mapsPdfUnknownAuthorAndMissingAddedAt() {
        val row = LibraryCatalogBridge.bookRows(
            listOf(
                Book(
                    id = 9,
                    title = "PDF Book",
                    author = null,
                    format = BookFormat.Pdf,
                    filePath = "content://downloads/book.pdf",
                    fileSize = 0,
                    addedAt = null,
                ),
            ),
            now = 1L,
        ).single()

        assertEquals(9L, row.id)
        assertEquals("legacy:9", row.stableKey)
        assertEquals("", row.author)
        assertEquals("pdf", row.format)
        assertEquals("book.pdf", row.fileName)
        assertEquals(0L, row.addedAt)
    }

    @Test
    fun unsupportedFormatDoesNotBecomeChineseLabel() {
        val row = LibraryCatalogBridge.bookRows(
            listOf(
                Book(
                    id = 11,
                    title = "AZW3",
                    author = "",
                    format = BookFormat.Unsupported,
                    filePath = "/books/a.azw3",
                    fileSize = 1,
                ),
            ),
            now = 2L,
        ).single()

        assertEquals("unsupported", row.format)
        assertTrue(row.stableKey.startsWith(StableKey.LEGACY_PREFIX))
    }

    @Test
    fun emptyLibraryMapsToEmptyRows() {
        assertTrue(LibraryCatalogBridge.bookRows(emptyList(), now = 1L).isEmpty())
    }
}
