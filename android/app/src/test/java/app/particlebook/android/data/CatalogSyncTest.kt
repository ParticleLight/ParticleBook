package app.particlebook.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1 书架 SQLite 同步：stableKey upsert、UI id 对齐、已有扩展字段不被清空。 */
class CatalogSyncTest {

    @Test
    fun syncUpsertsByStableKeyWithoutDuplicatesAndKeepsExtendedFields() {
        val repository = TestLibraryRepository()
        repository.saveBook(
            BookRow(
                id = 1,
                stableKey = "zlib:1",
                title = "Old",
                filePath = "/old.epub",
                description = "keep me",
                coverPath = "/cover.png",
                rating = 5,
                addedAt = 50,
            ),
        )

        val synced = CatalogSync.syncBooks(
            repository,
            listOf(
                BookRow(
                    id = 1,
                    stableKey = "zlib:1",
                    title = "New title",
                    author = "Author",
                    format = "pdf",
                    filePath = "/new.pdf",
                    fileName = "new.pdf",
                    fileSize = 10,
                    addedAt = 100,
                    updatedAt = 100,
                ),
                BookRow(
                    id = 2,
                    stableKey = "legacy:2",
                    title = "Second",
                    format = "epub",
                    addedAt = 200,
                    updatedAt = 200,
                ),
            ),
        )

        assertEquals(2, synced)
        assertEquals(2, repository.bookCount())
        val first = repository.books().first { it.stableKey == "zlib:1" }
        assertEquals(1L, first.id)
        assertEquals("New title", first.title)
        assertEquals("pdf", first.format)
        assertEquals("/new.pdf", first.filePath)
        assertEquals(100L, first.addedAt)
        assertEquals("keep me", first.description)
        assertEquals("/cover.png", first.coverPath)
        assertEquals(5, first.rating)
        assertEquals(2L, repository.books().first { it.stableKey == "legacy:2" }.id)

        val secondSync = CatalogSync.syncBooks(
            repository,
            listOf(BookRow(id = 1, stableKey = "zlib:1", title = "Newer", updatedAt = 300)),
        )
        assertEquals(1, secondSync)
        assertEquals(2, repository.bookCount())
        assertEquals("Newer", repository.bookById(1)?.title)
        assertEquals("keep me", repository.bookById(1)?.description)
    }

    @Test
    fun firstInsertUsesUiIdAndResyncKeepsAnnotationForeignKey() {
        val repository = TestLibraryRepository()
        assertEquals(1, CatalogSync.syncBooks(repository, listOf(BookRow(id = 7, stableKey = "legacy:7", title = "Book"))))
        assertEquals(7L, repository.books().single().id)
        repository.addAnnotation(AnnotationRow(bookId = 7, kind = "note", locatorJson = "v1|text|0|0"))

        assertEquals(1, CatalogSync.syncBooks(repository, listOf(BookRow(id = 7, stableKey = "legacy:7", title = "Renamed"))))
        assertEquals(7L, repository.books().single().id)
        assertEquals(1, repository.annotations(7).size)
        assertEquals("Renamed", repository.bookById(7)?.title)
    }

    @Test
    fun duplicateStableKeysInOneBatchCollapseToOneBook() {
        val repository = TestLibraryRepository()
        val synced = CatalogSync.syncBooks(
            repository,
            listOf(
                BookRow(id = 0, stableKey = "zlib:9", title = "First"),
                BookRow(id = 0, stableKey = "zlib:9", title = "Last"),
            ),
        )
        assertEquals(2, synced)
        assertEquals(1, repository.bookCount())
        assertEquals("Last", repository.books().single().title)
    }

    @Test
    fun blankStableKeyRowsAreSkipped() {
        val repository = TestLibraryRepository()
        assertEquals(0, CatalogSync.syncBooks(repository, listOf(BookRow(stableKey = "", title = "dirty"))))
        assertEquals(0, repository.bookCount())
        assertEquals(0, CatalogSync.syncBooks(repository, emptyList()))
    }
}
