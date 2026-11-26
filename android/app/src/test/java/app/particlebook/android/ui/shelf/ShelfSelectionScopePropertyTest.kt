package app.particlebook.android.ui.shelf

import app.particlebook.android.ui.FormatFilter
import app.particlebook.android.ui.LibrarySort
import app.particlebook.android.ui.data.Book
import app.particlebook.android.ui.data.BookFormat
import app.particlebook.android.ui.filterBooks
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfSelectionScopePropertyTest {
    @Test
    fun retainIsASubsetIsIdempotentAndOnlyShrinksWithItsScope() {
        val random = Random(0x5E1EC7)
        repeat(128) { sample ->
            val selectedIds = (
                listOf(0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE) +
                    List(40) { random.nextLong(-20L, 21L) }
                ).toSet()
            val scopeIds = (
                listOf(0L, -1L, Long.MIN_VALUE) +
                    List(40) { random.nextLong(-20L, 21L) }
                ).toSet()
            val retained = ShelfSelectionScope.retain(selectedIds, scopeIds)

            assertTrue("sample $sample: result stays within the selection", selectedIds.containsAll(retained))
            assertTrue("sample $sample: result stays within scope", scopeIds.containsAll(retained))
            selectedIds.forEach { id ->
                if (id in scopeIds) {
                    assertTrue("sample $sample: shared ID $id survives", id in retained)
                }
            }
            assertEquals(
                "sample $sample: retaining twice is idempotent",
                retained,
                ShelfSelectionScope.retain(retained, scopeIds),
            )

            val smallerScope = scopeIds.filter { random.nextBoolean() }.toSet()
            val afterShrink = ShelfSelectionScope.retain(selectedIds, smallerScope)
            assertTrue("sample $sample: a smaller scope only removes IDs", retained.containsAll(afterShrink))
            assertEquals(afterShrink, ShelfSelectionScope.retain(retained, smallerScope))
        }
    }

    @Test
    fun duplicateBooksAndInputShufflesPreserveScopeMembership() {
        val random = Random(0xD0B1E)
        repeat(64) { sample ->
            val books = List(40) { index ->
                book(random.nextLong(-12L, 13L), "Book $index", BookFormat.values()[index % BookFormat.values().size])
            }
            val scopeIds = ShelfSelectionScope.idsOf(books)
            books.forEach { book ->
                assertTrue("sample $sample: every book ID survives", book.id in scopeIds)
            }
            assertTrue("sample $sample: scope never invents an ID", scopeIds.all { id -> books.any { it.id == id } })

            val duplicatedAndShuffled = (books + books + books).shuffled(random)
            assertEquals(
                "sample $sample: duplicates do not change membership",
                scopeIds,
                ShelfSelectionScope.idsOf(duplicatedAndShuffled),
            )
        }
    }

    @Test
    fun everySortPreservesFilteredIdMembership() {
        val random = Random(0x50A7)
        val books = List(70) { index ->
            val format = BookFormat.values()[index % BookFormat.values().size]
            book(
                id = random.nextLong(-15L, 16L),
                title = if (index % 2 == 0) "Match $index" else "Other $index",
                format = format,
                tags = if (index % 3 == 0) listOf("topic", "match") else listOf("topic"),
            ).copy(rating = if (index % 4 == 0) null else random.nextInt(1, 6))
        }

        listOf("", "match", "topic", "absent-token").forEach { query ->
            FormatFilter.values().forEach { filter ->
                val expectedIds = ShelfSelectionScope.idsOf(
                    filterBooks(books, query, LibrarySort.RecentRead, filter),
                )
                LibrarySort.values().forEach { sort ->
                    val actualIds = ShelfSelectionScope.idsOf(filterBooks(books, query, sort, filter))
                    assertEquals("query=$query filter=$filter sort=$sort", expectedIds, actualIds)
                }
            }
        }
    }

    @Test
    fun queryAndFormatScopesKeepExactlyTheMatchingFormatsIncludingUnsupported() {
        val matchingByFormat = BookFormat.values().associateWith { format ->
            book(format.ordinal.toLong() + 1L, "Book " + format.raw, format, tags = listOf("scope-token"))
        }
        val outsider = book(100L, "Outside", BookFormat.Pdf, tags = listOf("unrelated"))
        val books = matchingByFormat.values.toList() + outsider
        val allMatchingIds = matchingByFormat.values.map { it.id }.toSet()
        val previouslySelected = ShelfSelectionScope.idsOf(books) + 999L

        FormatFilter.values().forEach { filter ->
            val visible = filterBooks(books, "scope-token", LibrarySort.Title, filter)
            val visibleIds = ShelfSelectionScope.idsOf(visible)
            val expectedIds = filter.bookFormat?.let { setOf(matchingByFormat.getValue(it).id) } ?: allMatchingIds

            assertEquals("filter $filter keeps its matching result", expectedIds, visibleIds)
            assertEquals(
                "filter $filter drops stale and result-external selections",
                expectedIds,
                ShelfSelectionScope.retain(previouslySelected, visibleIds),
            )
            assertFalse("filter $filter excludes the unmatched PDF", outsider.id in visibleIds)
            if (filter != FormatFilter.All) {
                assertTrue("filter $filter excludes Unsupported", visible.none { it.format == BookFormat.Unsupported })
            }
        }

        val allIds = ShelfSelectionScope.idsOf(filterBooks(books, "scope-token", LibrarySort.Title, FormatFilter.All))
        assertTrue("All keeps matching Unsupported books", matchingByFormat.getValue(BookFormat.Unsupported).id in allIds)
    }

    private fun book(
        id: Long,
        title: String,
        format: BookFormat,
        tags: List<String> = emptyList(),
    ) = Book(
        id = id,
        title = title,
        author = "Author $id",
        format = format,
        filePath = "/tmp/book-" + id + "." + format.raw,
        fileSize = 1024L,
        tags = tags,
    )
}
