package app.particlebook.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRepositoryContractTest {

    @Test
    fun bookCrudKeepsNumericIdAndUpsertsByStableKey() {
        val repository = TestLibraryRepository()
        val id = repository.saveBook(BookRow(id = 42, stableKey = "zlib:42", title = "A", format = "epub"))
        assertEquals(42L, id)
        assertEquals("A", repository.bookById(42)?.title)
        repository.saveBook(BookRow(id = 42, stableKey = "zlib:42", title = "A2", format = "pdf"))
        assertEquals(1, repository.bookCount())
        assertEquals("A2", repository.bookById(42)?.title)
        val generated = repository.saveBook(BookRow(stableKey = "legacy:7", title = "B"))
        assertTrue(generated > 42)
        assertEquals(2, repository.bookCount())
        assertTrue(repository.deleteBook(42))
        assertFalse(repository.deleteBook(42))
        assertNull(repository.bookById(42))
    }

    @Test
    fun tagsProgressAnnotationsAndSearchCascadeOnBookDelete() {
        val repository = TestLibraryRepository()
        val id = repository.saveBook(BookRow(id = 1, stableKey = "zlib:1", title = "Book"))
        repository.addTag(id, "读书")
        repository.addTag(id, "读书")
        repository.addTag(id, "收藏")
        assertEquals(listOf("收藏", "读书"), repository.tagsForBook(id))
        repository.saveProgress(ProgressRow(bookId = id, page = 3, totalPages = 10, updatedAt = 1))
        repository.addAnnotation(AnnotationRow(bookId = id, kind = "highlight", locatorJson = "{}", quote = "q"))
        repository.addDownload(DownloadRow(bookId = id, sourceBookId = 1, destPath = "/a.epub"))
        repository.saveSearchDocument(id, "body", "1", "这本书非常好看")

        assertTrue(repository.deleteBook(id))
        assertEquals(0, repository.bookCount())
        assertEquals(emptyList<String>(), repository.tagsForBook(id))
        assertNull(repository.progress(id))
        assertEquals(0, repository.annotations(id).size)
        assertNull(repository.downloads().single().bookId)
        assertEquals(0, repository.search("好看").size)
    }

    @Test
    fun transactionRollsBackAllWritesOnException() {
        val repository = TestLibraryRepository()
        repository.saveBook(BookRow(id = 1, stableKey = "zlib:1", title = "keep"))
        assertThrows(IllegalStateException::class.java) {
            repository.transaction { tx ->
                tx.saveBook(BookRow(id = 2, stableKey = "zlib:2", title = "rolled back"))
                tx.addTag(2, "tag")
                throw IllegalStateException("boom")
            }
        }
        assertEquals(1, repository.bookCount())
        assertNull(repository.bookById(2))
        assertEquals(emptyList<String>(), repository.tagsForBook(2))
    }

    @Test
    fun transactionCommitsWhenBlockSucceeds() {
        val repository = TestLibraryRepository()
        val result = repository.transaction { tx ->
            val id = tx.saveBook(BookRow(id = 5, stableKey = "zlib:5", title = "committed"))
            tx.addTag(id, "ok")
            id
        }
        assertEquals(5L, result)
        assertEquals(listOf("ok"), repository.tagsForBook(5))
    }

    @Test
    fun foreignKeyMissingBookIsRejectedByRepository() {
        val repository = TestLibraryRepository()
        assertThrows(IllegalArgumentException::class.java) { repository.addTag(99, "x") }
        assertThrows(IllegalArgumentException::class.java) {
            repository.saveProgress(ProgressRow(bookId = 99, page = 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.addAnnotation(AnnotationRow(bookId = 99, kind = "bookmark", locatorJson = "{}"))
        }
    }

    @Test
    fun deletedAnnotationsAreNotListed() {
        val repository = TestLibraryRepository()
        val id = repository.saveBook(BookRow(id = 1, stableKey = "zlib:1", title = "Book"))
        repository.addAnnotation(AnnotationRow(bookId = id, kind = "note", locatorJson = "{}", note = "kept"))
        repository.addAnnotation(
            AnnotationRow(bookId = id, kind = "note", locatorJson = "{}", note = "deleted", deletedAt = 1),
        )
        assertEquals(1, repository.annotations(id).size)
        assertEquals("kept", repository.annotations(id).single().note)
    }
}
