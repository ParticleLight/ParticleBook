package app.particlebook.android.data

import app.particlebook.android.reader.ReaderAnnotation
import app.particlebook.android.reader.ReaderAnnotationType
import app.particlebook.android.reader.ReaderLocator
import app.particlebook.android.reader.ReaderRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1-A021：AnnotationStore 契约的纯 JVM 测试（内存 repository 模拟 SQLite 行语义）。 */
class SqliteAnnotationStoreTest {

    private fun repositoryWithBook(): Pair<TestLibraryRepository, Long> {
        val repository = TestLibraryRepository()
        val bookId = repository.saveBook(BookRow(stableKey = "zlib:1", title = "Book"))
        return repository to bookId
    }

    @Test
    fun allFiveLocatorKindsRoundTripAndListIsSorted() {
        val (repository, bookId) = repositoryWithBook()
        val store = SqliteAnnotationStore(repository) { 1_000L }
        val entries: List<Triple<ReaderLocator, ReaderAnnotationType, Long>> = listOf(
            Triple(ReaderLocator.Epub("ch1.xhtml", 3, 7, 2), ReaderAnnotationType.BOOKMARK, 300L),
            Triple(
                ReaderLocator.Pdf(4, 0.25f, 0.5f, ReaderRect(0.1f, 0.2f, 0.3f, 0.4f)),
                ReaderAnnotationType.HIGHLIGHT,
                100L,
            ),
            Triple(ReaderLocator.Text(123, 4), ReaderAnnotationType.NOTE, 500L),
            Triple(ReaderLocator.Html("第二章.xhtml", "sec-1", 9, 3), ReaderAnnotationType.BOOKMARK, 200L),
            Triple(ReaderLocator.Scroll(0.42f), ReaderAnnotationType.HIGHLIGHT, 400L),
        )
        entries.forEachIndexed { index, (locator, type, createdAt) ->
            val added = store.add(
                ReaderAnnotation(
                    bookId = bookId,
                    type = type,
                    locator = locator,
                    quote = "q" + index,
                    note = "n" + index,
                    createdAt = createdAt,
                    updatedAt = createdAt,
                ),
            )
            assertTrue("add 必须分配 id", added.id > 0)
            assertEquals(locator, added.locator)
            assertEquals(type, added.type)
            assertEquals(createdAt, added.createdAt)
        }

        val listed = store.list(bookId)
        assertEquals(5, listed.size)
        assertEquals(listOf(100L, 200L, 300L, 400L, 500L), listed.map { it.createdAt })
        assertEquals(entries.sortedBy { it.third }.map { it.first }, listed.map { it.locator })
        assertEquals(entries.sortedBy { it.third }.map { it.second }, listed.map { it.type })
        assertEquals(listed.sortedWith(compareBy({ it.createdAt }, { it.id })), listed)
    }

    @Test
    fun addAllocatesIdAndTimestampsWhenInputHasZeros() {
        val (repository, bookId) = repositoryWithBook()
        val store = SqliteAnnotationStore(repository) { 7_654L }
        val added = store.add(
            ReaderAnnotation(
                bookId = bookId,
                type = ReaderAnnotationType.NOTE,
                locator = ReaderLocator.Text(1),
                note = "hello",
            ),
        )
        assertTrue(added.id > 0)
        assertEquals(7_654L, added.createdAt)
        assertEquals(7_654L, added.updatedAt)
        assertEquals("hello", repository.annotationById(added.id)?.note)
    }

    @Test
    fun updateOnlyEditsQuoteNoteColorAndKeepsLocatorAndType() {
        val (repository, bookId) = repositoryWithBook()
        var now = 10L
        val store = SqliteAnnotationStore(repository) { now }
        val added = store.add(
            ReaderAnnotation(
                bookId = bookId,
                type = ReaderAnnotationType.HIGHLIGHT,
                locator = ReaderLocator.Epub("ch1.xhtml", 1, 2, 3),
                quote = "old",
                createdAt = 10L,
                updatedAt = 10L,
            ),
        )
        val originalLocatorJson = repository.annotationById(added.id)?.locatorJson

        now = 20L
        val updated = requireNotNull(
            store.update(
                added.copy(
                    type = ReaderAnnotationType.NOTE,
                    locator = ReaderLocator.Pdf(9),
                    quote = "new quote",
                    note = "new note",
                    color = "#ffcc00",
                    createdAt = 999L,
                ),
            ),
        )
        assertEquals(added.id, updated.id)
        assertEquals(ReaderAnnotationType.HIGHLIGHT, updated.type)
        assertEquals(added.locator, updated.locator)
        assertEquals("new quote", updated.quote)
        assertEquals("new note", updated.note)
        assertEquals("#ffcc00", updated.color)
        assertEquals(10L, updated.createdAt)
        assertEquals(20L, updated.updatedAt)

        val row = requireNotNull(repository.annotationById(added.id))
        assertEquals("highlight", row.kind)
        assertEquals(originalLocatorJson, row.locatorJson)
        assertEquals("new note", row.note)
        assertEquals(20L, row.updatedAt)
    }

    @Test
    fun updateMissingOrRemovedAnnotationReturnsNull() {
        val (repository, bookId) = repositoryWithBook()
        val store = SqliteAnnotationStore(repository) { 50L }
        assertNull(store.update(ReaderAnnotation(bookId = bookId, type = ReaderAnnotationType.NOTE, locator = ReaderLocator.Text(0)).copy(id = 9999L)))
        val added = store.add(ReaderAnnotation(bookId = bookId, type = ReaderAnnotationType.BOOKMARK, locator = ReaderLocator.Text(0)))
        assertTrue(store.remove(added.id))
        assertNull(store.update(added.copy(note = "after delete")))
    }

    @Test
    fun removeIsSoftDeleteAndIdempotent() {
        val (repository, bookId) = repositoryWithBook()
        val store = SqliteAnnotationStore(repository) { 100L }
        val added = store.add(ReaderAnnotation(bookId = bookId, type = ReaderAnnotationType.BOOKMARK, locator = ReaderLocator.Scroll(0.5f)))
        assertTrue(store.remove(added.id))
        assertFalse(store.remove(added.id))
        assertFalse(store.remove(9999L))
        assertTrue(store.list(bookId).isEmpty())
        assertNull(repository.annotationById(added.id))
        // 软删而非物理删：上层按 deleted_at 过滤，重复删除仍是幂等 false。
    }

    @Test
    fun listKeepsCorruptLocatorRowsAsPlaceholdersWithRawJson() {
        val (repository, bookId) = repositoryWithBook()
        val store = SqliteAnnotationStore(repository) { 1L }
        val valid = store.add(
            ReaderAnnotation(
                bookId = bookId,
                type = ReaderAnnotationType.NOTE,
                locator = ReaderLocator.Text(5, 1),
                quote = "good",
            ),
        )
        repository.addAnnotation(
            AnnotationRow(bookId = bookId, kind = "note", locatorJson = "not-a-locator", quote = "bad-json"),
        )
        repository.addAnnotation(
            AnnotationRow(bookId = bookId, kind = "highlight", locatorJson = "v1|epub|broken", quote = "bad-v1"),
        )
        repository.addAnnotation(
            AnnotationRow(bookId = bookId, kind = "mystery", locatorJson = valid.locator.encode(), quote = "unknown-kind"),
        )
        repository.addAnnotation(
            AnnotationRow(bookId = bookId, kind = "note", locatorJson = "", quote = "empty-json"),
        )

        val listed = store.list(bookId)
        assertEquals(5, listed.size)

        val good = listed.first { it.id == valid.id }
        assertNull(good.locatorError)
        assertNull(good.rawLocatorJson)
        assertEquals(valid.locator, good.locator)

        val badJson = listed.first { it.quote == "bad-json" }
        assertEquals(ReaderLocator.Text(0), badJson.locator)
        assertNotNull(badJson.locatorError)
        assertEquals("not-a-locator", badJson.rawLocatorJson)

        val badV1 = listed.first { it.quote == "bad-v1" }
        assertNotNull(badV1.locatorError)
        assertEquals("v1|epub|broken", badV1.rawLocatorJson)

        val emptyJson = listed.first { it.quote == "empty-json" }
        assertNotNull(emptyJson.locatorError)
        assertEquals("", emptyJson.rawLocatorJson)

        // 未知 kind 不丢弃，按 highlight 兜底；它的 locator 本身是合法的，不应误报位置错误。
        val unknownKind = listed.first { it.quote == "unknown-kind" }
        assertEquals(ReaderAnnotationType.HIGHLIGHT, unknownKind.type)
        assertNull(unknownKind.locatorError)
    }

    @Test
    fun newStoreInstanceReadsPersistedAnnotationsBack() {
        val (repository, bookId) = repositoryWithBook()
        val first = SqliteAnnotationStore(repository) { 100L }
        val added = first.add(
            ReaderAnnotation(
                bookId = bookId,
                type = ReaderAnnotationType.NOTE,
                locator = ReaderLocator.Html("ch2.xhtml", "p3", 10, 2),
                quote = "quote",
                note = "note",
            ),
        )
        // 模拟杀进程后重新构造 store：状态必须只来自 repository（SQLite）。
        val second = SqliteAnnotationStore(repository) { 200L }
        val restored = second.list(bookId).single()
        assertEquals(added.id, restored.id)
        assertEquals(added.locator, restored.locator)
        assertEquals("note", restored.note)
        assertEquals(100L, restored.createdAt)
        assertTrue(second.remove(added.id))
        assertTrue(second.list(bookId).isEmpty())
    }

    @Test
    fun pdfAnnotationIsStoredAsHighlightKindUntilP2() {
        val (repository, bookId) = repositoryWithBook()
        val store = SqliteAnnotationStore(repository) { 1L }
        val added = store.add(
            ReaderAnnotation(
                bookId = bookId,
                type = ReaderAnnotationType.PDF,
                locator = ReaderLocator.Pdf(3, 0.5f, 0.5f),
                quote = "pdf",
            ),
        )
        val row = requireNotNull(repository.annotationById(added.id))
        assertEquals("highlight", row.kind)
        assertEquals(ReaderAnnotationType.HIGHLIGHT, store.list(bookId).single().type)
        assertEquals(ReaderLocator.Pdf(3, 0.5f, 0.5f), store.list(bookId).single().locator)
    }
}
