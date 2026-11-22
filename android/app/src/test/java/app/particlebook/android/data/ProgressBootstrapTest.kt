package app.particlebook.android.data

import app.particlebook.android.reader.ReaderLocator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressBootstrapTest {
    @Test
    fun backfillReadsPrefsOnceAndDeletedSQLiteProgressNeverReturns() {
        val repository = TestLibraryRepository()
        repository.saveBook(BookRow(id = 7, stableKey = "legacy:7", format = "pdf"))
        val prefs = mapOf("page_7" to 2, "pages_7" to 3, "last_read_7" to 50L)
        val first = ProgressBootstrap.load(repository, { prefs })
        val original = first.getValue(7)
        assertEquals(ReaderLocator.Pdf(2), original.locator())
        assertEquals("已读 100%", original.percentLabel())
        assertEquals(ProgressSnapshot.fromRow(repository.progressAll().single()), original)
        assertTrue(repository.progressBackfillComplete())
        assertEquals(1, repository.deleteProgress(setOf(7L)))
        assertEquals(0, repository.deleteProgress(setOf(7L)))
        val next = ProgressBootstrap.load(repository, { error("should not read pb_reading after marker") })
        assertTrue(next.isEmpty())
        assertNull(repository.progress(7))
    }

    @Test
    fun failedMarkerRollsBackRowsAndNextInitializeRetries() {
        val repository = TestLibraryRepository()
        repository.saveBook(BookRow(id = 1, stableKey = "legacy:1", format = "pdf"))
        val prefs = mapOf("page_1" to 1, "pages_1" to 3)
        val failed = object : LibraryRepository by repository {
            override fun <T> transaction(block: (LibraryRepository) -> T): T = repository.transaction { tx ->
                block(object : LibraryRepository by tx {
                    override fun markProgressBackfillComplete() {
                        tx.markProgressBackfillComplete()
                        error("marker write failed before commit")
                    }
                })
            }
        }
        assertThrows(IllegalStateException::class.java) { ProgressBootstrap.load(failed, { prefs }) }
        assertTrue(repository.progressAll().isEmpty())
        assertFalse(repository.progressBackfillComplete())
        assertEquals(1, ProgressBootstrap.load(repository, { prefs }).getValue(1).savedPage())
        assertTrue(repository.progressBackfillComplete())
    }

    @Test
    fun epubAndPdfRoundTripViaRepositoryAndHydrateOnceWithoutPrefs() = runBlocking {
        val repository = TestLibraryRepository()
        repository.saveBook(BookRow(id = 7, stableKey = "legacy:7", format = "epub"))
        repository.saveBook(BookRow(id = 8, stableKey = "legacy:8", format = "pdf"))
        val initial = ProgressBootstrap.load(repository, { emptyMap() })
        val store = SqliteProgressStore(repository, initial)
        val coordinator = ProgressCoordinator(store, initial, this, mapOf(1L to 7L, 2L to 8L))
        val chapter = ReaderLocator.Epub("text/ch03.xhtml", 5, 23)
        assertTrue(coordinator.enqueueLocation(1, chapter, 1, 10))
        assertTrue(coordinator.enqueueLocation(2, ReaderLocator.Pdf(1), 1, 3))
        assertTrue(coordinator.close())
        assertEquals(chapter.encode(), repository.progress(7)?.locatorJson)
        assertEquals(ReaderLocator.Pdf(1).encode(), repository.progress(8)?.locatorJson)
        assertNull(repository.progress(1))
        assertNull(repository.progress(2))

        var allReads = 0
        val counting = object : LibraryRepository by repository {
            override fun progressAll(): List<ProgressRow> = repository.progressAll().also { allReads++ }
        }
        val reopened = SqliteProgressStore(counting)
        val hydrated = reopened.hydrate()
        assertEquals(hydrated, reopened.hydrate())
        assertEquals(1, allReads)
        assertEquals(chapter, hydrated.getValue(7).locator())
        assertEquals(1, hydrated.getValue(7).savedPage())
        assertEquals(ReaderLocator.Pdf(1), hydrated.getValue(8).locator())
        assertEquals(1, hydrated.getValue(8).savedPage())
    }

    @Test
    fun preservesRawCorruptAndEmptyLocatorsWithPageFallback() {
        val repository = TestLibraryRepository()
        for (id in 1L..3L) repository.saveBook(BookRow(id = id, stableKey = "legacy:" + id))
        val raw = "  epub|***|1|2  "
        val prefs = mapOf("locator_1" to raw, "page_1" to 3, "pages_1" to 4, "locator_2" to "", "locator_3" to "pdf|2|NaN|0")
        val result = ProgressBootstrap.load(repository, { prefs })
        assertEquals(raw, repository.progress(1)?.locatorJson)
        assertEquals("", repository.progress(2)?.locatorJson)
        assertEquals("pdf|2|NaN|0", repository.progress(3)?.locatorJson)
        assertEquals(3, result.getValue(1).savedPage())
        result.values.forEach { assertNull(it.locator()) }
    }

    @Test
    fun newerLegacyValueWinsButEqualTimestampOnlyRepairsGeneratedShadows() {
        val chapter = ReaderLocator.Epub("chapter.xhtml", 2, 4).encode()
        val real = ProgressSnapshot(1, chapter, 2, 10, updatedAt = 25, finishedAt = 50)
        val synthetic = ProgressSnapshot(2, "{\"kind\":\"epub\",\"page\":2,\"total\":10}", 2, 10, updatedAt = 100)
        val other = synthetic.copy(bookId = 3)
        val db = mapOf(1L to real, 2L to synthetic, 3L to other)
        val result = ProgressBackfill.merge(db, listOf(
            LegacyProgress(1, 3, 10, 25, "older-invalid"),
            LegacyProgress(2, 2, 10, null, chapter),
            LegacyProgress(3, 4, 10, 101, "raw-invalid"),
            LegacyProgress(4, 1, 10, 101, chapter),
        )) { if (it in 1L..3L) "epub" else null }
        assertEquals(real, result.merged[1])
        assertEquals(synthetic.copy(locatorJson = chapter), result.merged[2])
        assertEquals("raw-invalid", result.merged.getValue(3).locatorJson)
        assertEquals(4, result.merged.getValue(3).savedPage())
        assertEquals(1, result.conflicts)
        assertEquals(1, result.missingBooks)
    }

    @Test
    fun preInitializeRemovalResolvesStableKeysAndKeepsRemainingAlias() {
        for (keepAlias in listOf(false, true)) {
            val repository = TestLibraryRepository()
            repository.saveBook(BookRow(id = 70, stableKey = "zlib:8", format = "epub"))
            repository.saveBook(BookRow(id = 42, stableKey = "legacy:unrelated", format = "pdf"))
            val raw = ReaderLocator.Epub("chapter6.xhtml", 5, 20).encode()
            repository.saveProgress(ProgressRow(70, raw, 2, 10, updatedAt = 100))
            repository.saveProgress(ProgressRow(42, "unrelated", 4, 6))
            val removed = listOf(BookRow(id = 42, stableKey = "zlib:8", format = "epub"))
            val remaining = if (keepAlias) listOf(BookRow(id = 43, stableKey = "zlib:8", format = "epub")) else emptyList()
            val active = CatalogSync.syncBookIds(repository, remaining)
            val resolved = CatalogSync.resolveBookIds(repository, removed)
            assertEquals(mapOf(42L to 70L), resolved)
            val hydrated = ProgressBootstrap.load(
                repository, { mapOf("page_42" to 9, "pages_42" to 20, "locator_42" to raw) },
                active::get, deletedBookIds = resolved.values.toSet() - active.values.toSet(),
            )
            assertEquals(keepAlias, 70L in hydrated)
            assertEquals("unrelated", repository.progress(42)?.locatorJson)
            assertTrue(repository.progressBackfillComplete())
            if (!keepAlias) {
                val reimported = CatalogSync.syncBookIds(repository, listOf(BookRow(id = 44, stableKey = "zlib:8")))
                val reopened = ProgressBootstrap.load(repository, { error("prefs already consumed") }, reimported::get)
                assertNull(reopened[70])
            }
        }
    }

    @Test
    fun oldDuplicateEpubSyntheticPdfOnlyRepairsExactTupleAndTime() {
        val raw = ReaderLocator.Epub("ch6.xhtml", 5, 20).encode()
        val exact = ProgressSnapshot(1, "{\"kind\":\"pdf\",\"page\":2,\"total\":10}", 2, 10, updatedAt = 100, finishedAt = 9)
        val changedPage = exact.copy(bookId = 2, page = 3)
        val changedRaw = exact.copy(bookId = 3, locatorJson = " {\"kind\":\"pdf\",\"page\":2,\"total\":10}")
        val changedTime = exact.copy(bookId = 4, updatedAt = 200)
        val real = exact.copy(bookId = 5, locatorJson = ReaderLocator.Epub("new.xhtml", 0, 0).encode())
        val db = listOf(exact, changedPage, changedRaw, changedTime, real).associateBy { it.bookId }
        val result = ProgressBackfill.merge(db, (1L..5L).map { LegacyProgress(it, 2, 10, 100, raw) }) { "epub" }
        assertEquals(exact.copy(locatorJson = raw), result.merged[1])
        assertEquals(changedPage, result.merged[2])
        assertEquals(changedRaw, result.merged[3])
        assertEquals(changedTime, result.merged[4])
        assertEquals(real, result.merged[5])
    }

    @Test
    fun duplicateAliasBackfillChoosesNewerCompleteSnapshotRegardlessOfOrder() {
        val older = LegacyProgress(1, 9, 20, 100, ReaderLocator.Epub("ch1.xhtml", 0, 0).encode())
        val newer = LegacyProgress(1, 2, 10, 200, ReaderLocator.Epub("ch6.xhtml", 5, 20).encode())
        for (legacy in listOf(listOf(older, newer), listOf(newer, older))) {
            val result = ProgressBackfill.merge(emptyMap(), legacy) { "epub" }.merged.getValue(1)
            assertEquals(newer.locatorRaw, result.locatorJson)
            assertEquals(newer.page, result.page)
            assertEquals(newer.totalPages, result.totalPages)
            assertEquals(newer.lastReadAt, result.updatedAt)
        }
    }

    @Test
    fun legacyShelfAliasesMapToCanonicalRowsAndIgnoreAbsentBookKeys() {
        val repository = TestLibraryRepository()
        repository.saveBook(BookRow(id = 90, stableKey = "zlib:42", format = "pdf"))
        val ids = repository.transaction { tx ->
            CatalogSync.syncBookIds(tx, listOf(BookRow(id = 1, stableKey = "zlib:42", format = "pdf")))
        }
        assertEquals(mapOf(1L to 90L), ids)
        val result = ProgressBootstrap.load(repository, { mapOf("page_1" to 1, "pages_1" to 3, "page_90" to 10) }, ids::get)
        assertEquals(setOf(90L), result.keys)
        assertEquals(ReaderLocator.Pdf(1), result.getValue(90).locator())
    }
}
