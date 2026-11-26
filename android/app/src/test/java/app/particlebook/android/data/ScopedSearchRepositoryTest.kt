package app.particlebook.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM 内存行为契约：验证 TestLibraryRepository 的范围搜索和 CatalogSync 的稳定身份解析。
 * 历史书仍在 repository，但不在当前书架；不制造违反外键的孤立文档，也不做物理删除。
 * 本类不执行 Android SQLite / FTS，真实 SQL 的过滤先于 LIMIT 由设备上 205 章历史 A + 有效 B 另验。
 */
class ScopedSearchRepositoryTest {

    @Test
    fun earlierHistoricalDocumentsDoNotConsumeTheScopedLimit() {
        for (historicalCount in listOf(201, 205)) {
            val fixture = historicalFixture(historicalCount, currentCount = 3)
            val mapping = CatalogSync.resolveBookIds(fixture.repository, fixture.shelf)
            assertEquals(mapOf(42L to CURRENT_ID), mapping)

            for (limit in listOf(1, 200)) {
                val hits = fixture.repository.searchInBooks(QUERY, mapping.values.toSet(), limit)
                val expectedCount = if (limit == 1) 1 else 3
                val label = "historical=" + historicalCount + ", limit=" + limit
                assertEquals(label, expectedCount, hits.size)
                assertEquals(label, List(expectedCount) { CURRENT_ID }, hits.map { it.bookId })
                assertEquals(label, (1..expectedCount).map { "current-" + it }, hits.map { it.locator })
            }
        }
    }

    @Test
    fun emptyScopeReturnsNoHitsWithoutChangingTheCatalog() {
        val fixture = historicalFixture(205, currentCount = 1)
        val booksBefore = fixture.repository.books()
        val allHitsBefore = fixture.repository.search(QUERY, 300)
        assertEquals(206, allHitsBefore.size)

        assertTrue(fixture.repository.searchInBooks(QUERY, emptySet(), 200).isEmpty())

        assertEquals(booksBefore, fixture.repository.books())
        assertEquals(allHitsBefore, fixture.repository.search(QUERY, 300))
    }

    @Test
    fun nonPositiveLimitsReturnNoHitsForANonEmptyScope() {
        val fixture = historicalFixture(1, currentCount = 1)
        assertEquals(1, fixture.repository.searchInBooks(QUERY, setOf(CURRENT_ID), 1).size)

        for (limit in listOf(0, -1, Int.MIN_VALUE)) {
            assertTrue("limit=" + limit, fixture.repository.searchInBooks(QUERY, setOf(CURRENT_ID), limit).isEmpty())
        }
    }

    @Test
    fun scopeExcludesMatchingDocumentsOfEveryOtherBook() {
        val repository = TestLibraryRepository()
        val allowed = saveBook(repository, 10L)
        val excluded = saveBook(repository, 20L)
        repository.saveSearchDocument(excluded, "body", "excluded-first", QUERY)
        repository.saveSearchDocument(allowed, "body", "allowed-one", QUERY + " first")
        repository.saveSearchDocument(allowed, "body", "unmatched", "different vocabulary")
        repository.saveSearchDocument(excluded, "body", "excluded-last", QUERY)
        repository.saveSearchDocument(allowed, "body", "allowed-two", QUERY + " second")

        val hits = repository.searchInBooks(QUERY, setOf(allowed), 200)

        assertEquals(listOf(allowed, allowed), hits.map { it.bookId })
        assertEquals(listOf("allowed-one", "allowed-two"), hits.map { it.locator })
        assertEquals(listOf("body", "body"), hits.map { it.docType })
        assertEquals(listOf(QUERY + " first", QUERY + " second"), hits.map { it.snippet })
        assertEquals(4, repository.search(QUERY, 200).size)
    }

    @Test
    fun omittedLimitReturnsTheFirstFiftyAllowedDocuments() {
        val fixture = historicalFixture(205, currentCount = 60)

        val hits = fixture.repository.searchInBooks(QUERY, setOf(CURRENT_ID))

        assertEquals(50, hits.size)
        assertEquals(List(50) { CURRENT_ID }, hits.map { it.bookId })
        assertEquals((1..50).map { "current-" + it }, hits.map { it.locator })
    }

    @Test
    fun explicitLimitCapsOnlyAllowedDocuments() {
        val fixture = historicalFixture(205, currentCount = 205)

        val hits = fixture.repository.searchInBooks(QUERY, setOf(CURRENT_ID), 200)

        assertEquals(200, hits.size)
        assertEquals(List(200) { CURRENT_ID }, hits.map { it.bookId })
        assertEquals((1..200).map { "current-" + it }, hits.map { it.locator })
    }

    @Test
    fun multipleAllowedBooksKeepGlobalDocumentOrderInsteadOfScopeOrder() {
        val repository = TestLibraryRepository()
        val first = saveBook(repository, 10L)
        val second = saveBook(repository, 20L)
        val excluded = saveBook(repository, 30L)
        repository.saveSearchDocument(excluded, "body", "excluded", QUERY)
        repository.saveSearchDocument(second, "body", "second-1", QUERY)
        repository.saveSearchDocument(first, "body", "first-1", QUERY)
        repository.saveSearchDocument(second, "body", "second-2", QUERY)
        repository.saveSearchDocument(first, "body", "first-2", QUERY)

        for (scope in listOf(linkedSetOf(first, second), linkedSetOf(second, first))) {
            val hits = repository.searchInBooks(QUERY, scope, 200)
            assertEquals(listOf(second, first, second, first), hits.map { it.bookId })
            assertEquals(listOf("second-1", "first-1", "second-2", "first-2"), hits.map { it.locator })
            assertEquals(
                listOf("second-1", "first-1"),
                repository.searchInBooks(QUERY, scope, 2).map { it.locator },
            )
        }
    }

    @Test
    fun largeScopeIncludesTargetAfterMoreThanOneThousandIds() {
        val repository = TestLibraryRepository()
        val target = saveBook(repository, 1_005L)
        val excluded = saveBook(repository, 2_000L)
        repository.saveSearchDocument(excluded, "body", "outside-large-scope", QUERY)
        repository.saveSearchDocument(target, "body", "large-scope-target", QUERY)
        val scope = (1L..1_005L).toSet()
        assertEquals(1_005, scope.size)

        val hits = repository.searchInBooks(QUERY, scope, 1)

        assertEquals(listOf(target), hits.map { it.bookId })
        assertEquals(listOf("large-scope-target"), hits.map { it.locator })
    }

    @Test
    fun stableKeyResolutionUsesCanonicalIdDespiteNumericCollision() {
        val repository = TestLibraryRepository()
        val canonical = saveBook(repository, 70L, stableKey = "zlib:8")
        val unrelated = saveBook(repository, 42L, stableKey = "legacy:unrelated")
        repository.saveSearchDocument(unrelated, "body", "numeric-collision", QUERY)
        repository.saveSearchDocument(canonical, "body", "canonical", QUERY)
        val shelf = listOf(BookRow(id = 42L, stableKey = " zlib:8 ", title = "current shelf"))

        val mapping = CatalogSync.resolveBookIds(repository, shelf)
        val hits = repository.searchInBooks(QUERY, mapping.values.toSet(), 1)

        assertEquals(mapOf(42L to canonical), mapping)
        assertEquals(listOf(canonical), hits.map { it.bookId })
        assertEquals(listOf("canonical"), hits.map { it.locator })
        assertEquals(listOf(unrelated, canonical), repository.search(QUERY, 200).map { it.bookId })
    }

    @Test
    fun unresolvedOrBlankIdentityDoesNotGrantANumericIdMatch() {
        val repository = TestLibraryRepository()
        val occupied = saveBook(repository, 42L, stableKey = "legacy:unrelated")
        repository.saveSearchDocument(occupied, "body", "occupied-id", QUERY)
        val shelf = listOf(
            BookRow(id = occupied, stableKey = "zlib:missing", title = "not indexed"),
            BookRow(id = 43L, stableKey = "   ", title = "blank identity"),
        )
        val booksBefore = repository.books()

        val mapping = CatalogSync.resolveBookIds(repository, shelf)

        assertTrue(mapping.isEmpty())
        assertTrue(repository.searchInBooks(QUERY, mapping.values.toSet(), 200).isEmpty())
        assertEquals(listOf(occupied), repository.search(QUERY, 200).map { it.bookId })
        assertEquals(booksBefore, repository.books())
    }

    @Test
    fun legacySearchKeepsEarlierHistoricalDocumentsAndItsOriginalLimit() {
        val fixture = historicalFixture(205, currentCount = 1)
        val firstTwoHundred = fixture.repository.search(QUERY, 200)

        assertEquals(200, firstTwoHundred.size)
        assertEquals(List(200) { HISTORICAL_ID }, firstTwoHundred.map { it.bookId })
        assertEquals((1..200).map { "historical-" + it }, firstTwoHundred.map { it.locator })
        assertEquals(50, fixture.repository.search(QUERY).size)
        val allBefore = fixture.repository.search(QUERY, 300)
        assertEquals(206, allBefore.size)
        assertEquals(CURRENT_ID, allBefore.last().bookId)

        assertEquals(
            listOf(CURRENT_ID),
            fixture.repository.searchInBooks(QUERY, setOf(CURRENT_ID), 1).map { it.bookId },
        )

        assertEquals(allBefore, fixture.repository.search(QUERY, 300))
    }

    private data class HistoricalFixture(
        val repository: TestLibraryRepository,
        val shelf: List<BookRow>,
    )

    private fun historicalFixture(historicalCount: Int, currentCount: Int): HistoricalFixture {
        val repository = TestLibraryRepository()
        saveBook(repository, HISTORICAL_ID)
        saveBook(repository, CURRENT_ID, stableKey = "zlib:current")
        for (index in 1..historicalCount) {
            repository.saveSearchDocument(HISTORICAL_ID, "body", "historical-" + index, QUERY)
        }
        for (index in 1..currentCount) {
            repository.saveSearchDocument(CURRENT_ID, "body", "current-" + index, QUERY)
        }
        val shelf = listOf(BookRow(id = 42L, stableKey = "zlib:current", title = "current shelf"))
        return HistoricalFixture(repository, shelf)
    }

    private fun saveBook(
        repository: TestLibraryRepository,
        id: Long,
        stableKey: String = "legacy:" + id,
    ): Long = repository.saveBook(BookRow(id = id, stableKey = stableKey, title = "Book " + id))

    private companion object {
        const val QUERY = "orchidscoperepository"
        const val HISTORICAL_ID = 1L
        const val CURRENT_ID = 70L
    }
}
