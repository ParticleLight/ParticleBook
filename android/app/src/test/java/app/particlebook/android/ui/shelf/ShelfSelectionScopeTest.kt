package app.particlebook.android.ui.shelf

import app.particlebook.android.ui.FormatFilter
import app.particlebook.android.ui.LibrarySort
import app.particlebook.android.ui.data.Book
import app.particlebook.android.ui.data.BookFormat
import app.particlebook.android.ui.filterBooks
import org.junit.Assert.assertEquals
import org.junit.Test

class ShelfSelectionScopeTest {
    @Test
    fun idsOfEmptyResultIsEmpty() {
        assertEquals(emptySet<Long>(), ShelfSelectionScope.idsOf(emptyList()))
    }

    @Test
    fun idsOfSingleBookContainsItsId() {
        assertEquals(setOf(42L), ShelfSelectionScope.idsOf(listOf(book(42L))))
    }

    @Test
    fun idsOfDeduplicatesByIdInFirstAppearanceOrder() {
        val books = listOf(
            book(7L, title = "First"),
            book(2L),
            book(7L, title = "Different metadata", format = BookFormat.Pdf),
            book(9L),
            book(2L, title = "Another duplicate"),
        )

        assertEquals(listOf(7L, 2L, 9L), ShelfSelectionScope.idsOf(books).toList())
    }

    @Test
    fun idsOfKeepsEveryFormatIncludingUnsupported() {
        val books = listOf(
            book(1L, format = BookFormat.Epub),
            book(2L, format = BookFormat.Pdf),
            book(3L, format = BookFormat.Text),
            book(4L, format = BookFormat.Markdown),
            book(5L, format = BookFormat.Fb2),
            book(6L, format = BookFormat.Html),
            book(7L, format = BookFormat.Unsupported),
        )

        assertEquals(setOf(1L, 2L, 3L, 4L, 5L, 6L, 7L), ShelfSelectionScope.idsOf(books))
    }

    @Test
    fun idsOfAcceptsZeroNegativeAndExtremeLongIds() {
        val books = listOf(book(0L), book(-1L), book(Long.MIN_VALUE), book(Long.MAX_VALUE))

        assertEquals(
            listOf(0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE),
            ShelfSelectionScope.idsOf(books).toList(),
        )
    }

    @Test
    fun idsOfDoesNotChangeInputBookList() {
        val books = mutableListOf(book(8L), book(3L), book(8L, title = "Duplicate"))
        val before = books.toList()

        val ids = ShelfSelectionScope.idsOf(books)

        assertEquals(setOf(8L, 3L), ids)
        assertEquals(before, books)
    }

    @Test
    fun retainWithEmptyScopeClearsSelection() {
        assertEquals(emptySet<Long>(), ShelfSelectionScope.retain(setOf(1L, 2L), emptySet()))
    }

    @Test
    fun retainWithEmptySelectionDoesNotSelectScopeBooks() {
        assertEquals(emptySet<Long>(), ShelfSelectionScope.retain(emptySet(), setOf(1L, 2L)))
    }

    @Test
    fun retainKeepsOnlySelectedIdsInsideScope() {
        assertEquals(
            setOf(2L, 4L),
            ShelfSelectionScope.retain(setOf(1L, 2L, 4L), setOf(2L, 3L, 4L)),
        )
    }

    @Test
    fun retainKeepsAllSelectedIdsWithoutAddingOtherScopeIds() {
        assertEquals(
            setOf(1L, 3L),
            ShelfSelectionScope.retain(setOf(1L, 3L), setOf(1L, 2L, 3L)),
        )
    }

    @Test
    fun retainDropsSelectionWhenEveryIdIsOutsideScope() {
        assertEquals(emptySet<Long>(), ShelfSelectionScope.retain(setOf(1L, 2L), setOf(3L, 4L)))
    }

    @Test
    fun retainDoesNotChangeEitherInputSet() {
        val selected = linkedSetOf(3L, 1L, 2L)
        val scope = linkedSetOf(4L, 2L, 3L)
        val selectedBefore = selected.toList()
        val scopeBefore = scope.toList()

        val retained = ShelfSelectionScope.retain(selected, scope)

        assertEquals(setOf(2L, 3L), retained)
        assertEquals(selectedBefore, selected.toList())
        assertEquals(scopeBefore, scope.toList())
    }

    @Test
    fun resultIdsContainOnlyBooksMatchingBothQueryAndFormat() {
        val books = listOf(
            book(1L, title = "Kotlin notes", format = BookFormat.Markdown),
            book(2L, title = "Kotlin reference", format = BookFormat.Text),
            book(3L, title = "Statistics", format = BookFormat.Markdown),
            book(4L, title = "Kotlin archive", format = BookFormat.Unsupported),
        )

        val visible = filterBooks(books, "  KOTLIN  ", LibrarySort.Rating, FormatFilter.Markdown)

        assertEquals(setOf(1L), ShelfSelectionScope.idsOf(visible))
    }

    @Test
    fun disjointQueryAndFormatResultsDoNotFallBackToWholeLibrary() {
        val books = listOf(
            book(1L, title = "Kotlin reference", format = BookFormat.Text),
            book(2L, title = "Statistics", format = BookFormat.Markdown),
        )
        val visible = filterBooks(books, "kotlin", LibrarySort.Title, FormatFilter.Markdown)
        val scopeIds = ShelfSelectionScope.idsOf(visible)

        assertEquals(emptySet<Long>(), scopeIds)
        assertEquals(emptySet<Long>(), ShelfSelectionScope.retain(setOf(1L, 2L), scopeIds))
    }

    @Test
    fun allFormatResultKeepsMatchingUnsupportedBookSelectable() {
        val books = listOf(
            book(1L, title = "Kotlin archive", format = BookFormat.Unsupported),
            book(2L, title = "Kotlin book", format = BookFormat.Epub),
            book(3L, title = "Other archive", format = BookFormat.Unsupported),
        )

        val visible = filterBooks(books, "kotlin", LibrarySort.Title, FormatFilter.All)

        assertEquals(setOf(1L, 2L), ShelfSelectionScope.idsOf(visible))
    }

    @Test
    fun narrowingQueryThenFormatRetainsOnlyAlreadySelectedResultIds() {
        val books = listOf(
            book(1L, title = "Kotlin book", format = BookFormat.Epub),
            book(2L, title = "Kotlin notes", format = BookFormat.Markdown),
            book(3L, title = "Kotlin reference", format = BookFormat.Text),
            book(4L, title = "Statistics", format = BookFormat.Markdown),
        )
        val queryResults = filterBooks(books, "kotlin", LibrarySort.Title, FormatFilter.All)
        val afterQuery = ShelfSelectionScope.retain(setOf(1L, 2L, 4L), ShelfSelectionScope.idsOf(queryResults))
        val formatResults = filterBooks(books, "kotlin", LibrarySort.Title, FormatFilter.Markdown)
        val afterFormat = ShelfSelectionScope.retain(afterQuery, ShelfSelectionScope.idsOf(formatResults))

        assertEquals(setOf(1L, 2L), afterQuery)
        assertEquals(setOf(2L), afterFormat)
    }

    @Test
    fun resultIdsIncludeEveryMatchingBookBeyondASmallVisiblePage() {
        val books = (1L..24L).map { book(it, title = "Kotlin-$it") } + book(99L, title = "Other")
        val visible = filterBooks(books, "kotlin", LibrarySort.RecentRead, FormatFilter.All)

        val scopeIds = ShelfSelectionScope.idsOf(visible)

        assertEquals((24L downTo 1L).toList(), scopeIds.toList())
    }

    @Test
    fun refreshedLibraryDropsRemovedIdsWithoutSelectingNewBooks() {
        val refreshedBooks = listOf(book(1L), book(3L), book(4L))
        val visible = filterBooks(refreshedBooks, "", LibrarySort.RecentRead, FormatFilter.All)

        val retained = ShelfSelectionScope.retain(setOf(1L, 2L, 3L), ShelfSelectionScope.idsOf(visible))

        assertEquals(setOf(1L, 3L), retained)
    }

    private fun book(
        id: Long,
        title: String = "book-$id",
        format: BookFormat = BookFormat.Epub,
    ): Book = Book(
        id = id,
        title = title,
        author = null,
        format = format,
        filePath = "/fixtures/book-$id.${format.raw}",
        fileSize = 1024L,
    )
}
