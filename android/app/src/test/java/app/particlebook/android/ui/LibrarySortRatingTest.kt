package app.particlebook.android.ui

import app.particlebook.android.ui.data.Book
import app.particlebook.android.ui.data.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * task-49：书架评分排序。
 *
 * 直接打生产实现 [filterBooks]（同 module 的 test 源集可见 internal），
 * 不再在测试里复制排序契约——过滤/排序规则改动会立刻反映到这里。
 *
 * 契约（源码 LibraryScreen.kt:1082-1084）：
 * `compareByDescending<Book> { it.rating ?: 0 }.thenByDescending { it.id }`
 * 即 5 星在前、未评分（null 记 0）沉底、同分按 id 倒序保证列表稳定。
 */
class LibrarySortRatingTest {

    private fun book(
        id: Long,
        title: String = "book-$id",
        rating: Int? = null,
        author: String? = "作者$id",
        format: BookFormat = BookFormat.Epub,
        tags: List<String> = emptyList(),
    ) = Book(
        id = id,
        title = title,
        author = author,
        format = format,
        filePath = "/tmp/book-$id.epub",
        fileSize = 1024L * id,
        addedAt = "2026-11-2$id",
        tags = tags,
        rating = rating,
    )

    private fun ids(books: List<Book>) = books.map { it.id }

    @Test
    fun ratingSortPutsFiveStarFirstAndUnratedLast() {
        val books = listOf(
            book(1, rating = null),
            book(2, rating = 3),
            book(3, rating = 5),
            book(4, rating = 1),
            book(5, rating = 4),
        )

        val sorted = filterBooks(books, query = "", sort = LibrarySort.Rating, filter = FormatFilter.All)

        assertEquals(listOf(3L, 5L, 2L, 4L, 1L), ids(sorted))
        assertEquals(listOf(5, 4, 3, 1, null), sorted.map { it.rating })
    }

    @Test
    fun sameRatingBreaksTieByIdDescending() {
        val books = listOf(
            book(10, rating = 4),
            book(30, rating = 4),
            book(20, rating = 4),
        )

        val sorted = filterBooks(books, "", LibrarySort.Rating, FormatFilter.All)

        assertEquals(listOf(30L, 20L, 10L), ids(sorted))
    }

    @Test
    fun unratedBooksKeepIdOrderWhenEverythingIsNull() {
        val books = listOf(book(7), book(2), book(5))

        val sorted = filterBooks(books, "", LibrarySort.Rating, FormatFilter.All)

        assertEquals(listOf(7L, 5L, 2L), ids(sorted))
        assertTrue(sorted.all { it.rating == null })
    }

    @Test
    fun zeroAndNullBothSinkButDoNotCrash() {
        // 0 不是合法评分（写入侧 clamp 到 1..5），但仍要保证比较逻辑不崩、不与 null 混淆成高分。
        val books = listOf(
            book(1, rating = null),
            book(2, rating = 0),
            book(3, rating = 2),
        )

        val sorted = filterBooks(books, "", LibrarySort.Rating, FormatFilter.All)

        assertEquals(listOf(3L, 2L, 1L), ids(sorted))
    }

    @Test
    fun emptyListStaysEmpty() {
        assertEquals(emptyList<Long>(), ids(filterBooks(emptyList(), "", LibrarySort.Rating, FormatFilter.All)))
    }

    @Test
    fun singleBookIsUnaffected() {
        val one = filterBooks(listOf(book(42, rating = 3)), "", LibrarySort.Rating, FormatFilter.All)
        assertEquals(listOf(42L), ids(one))
    }

    @Test
    fun ratingSortComposesWithQueryFilter() {
        val books = listOf(
            book(1, title = "Alpha", rating = 5),
            book(2, title = "Beta", rating = null),
            book(3, title = "Alphabet", rating = 2),
        )

        val sorted = filterBooks(books, query = "alpha", sort = LibrarySort.Rating, filter = FormatFilter.All)

        assertEquals(listOf(1L, 3L), ids(sorted))
    }

    @Test
    fun ratingSortComposesWithFormatFilter() {
        val books = listOf(
            book(1, format = BookFormat.Pdf, rating = 1),
            book(2, format = BookFormat.Epub, rating = 5),
            book(3, format = BookFormat.Pdf, rating = 4),
        )

        val sorted = filterBooks(books, "", LibrarySort.Rating, FormatFilter.Pdf)

        assertEquals(listOf(3L, 1L), ids(sorted))
    }

    @Test
    fun otherSortsIgnoreRating() {
        val books = listOf(
            book(1, title = "B", rating = 5),
            book(2, title = "A", rating = null),
            book(3, title = "C", rating = 3),
        )

        val byTitle = filterBooks(books, "", LibrarySort.Title, FormatFilter.All)
        assertEquals(listOf(2L, 1L, 3L), ids(byTitle))

        // 添加时间 / 最近阅读都按 id 倒序，与评分无关。
        assertEquals(listOf(3L, 2L, 1L), ids(filterBooks(books, "", LibrarySort.AddedAt, FormatFilter.All)))
        assertEquals(listOf(3L, 2L, 1L), ids(filterBooks(books, "", LibrarySort.RecentRead, FormatFilter.All)))
    }

    @Test
    fun allRatingsAreDescendingInOutput() {
        val books = (1..20).map { id -> book(id.toLong(), rating = (id % 5) + 1) }

        val sorted = filterBooks(books, "", LibrarySort.Rating, FormatFilter.All)

        val ratings = sorted.map { it.rating!! }
        assertEquals(ratings.sortedDescending(), ratings)
        // 同一批书不会丢条目
        assertEquals(books.size, sorted.size)
        assertEquals(books.map { it.id }.toSet(), sorted.map { it.id }.toSet())
    }
}
