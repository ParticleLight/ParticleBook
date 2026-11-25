package app.particlebook.android.ui

import app.particlebook.android.ui.data.Book
import app.particlebook.android.ui.data.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ── 本轮新增：格式筛选（FormatFilter 新增 Text / Markdown 两个文本类筛选项） ──

    /** 格式化 fixture：filePath 扩展名跟着 format 走，避免「TXT 书挂 .epub 路径」这种自相矛盾的假数据。 */
    private fun formatBook(
        id: Long,
        format: BookFormat,
        title: String = "book-$id",
        author: String? = "作者$id",
        tags: List<String> = emptyList(),
        rating: Int? = null,
    ) = book(id, title = title, rating = rating, author = author, format = format, tags = tags)
        .copy(filePath = "/tmp/book-$id.${format.raw}")

    @Test
    fun allFormatFilterKeepsEveryFormatIncludingTextAndUnsupported() {
        // 「全部」= 不过滤：TXT / Markdown / Unsupported 都必须留在结果里（Unsupported 保留条目，只是打不开）。
        val books = BookFormat.values().mapIndexed { index, format ->
            formatBook((index + 1).toLong(), format)
        }

        val all = filterBooks(books, "", LibrarySort.RecentRead, FormatFilter.All)

        assertEquals(books.size, all.size)
        // RecentRead 按 id 倒序，顺序也一并锁住（格式增减时自动跟随，不写死 id 清单）。
        assertEquals((books.size downTo 1).map { it.toLong() }, ids(all))
        assertEquals(BookFormat.values().toSet(), all.map { it.format }.toSet())
        assertTrue(all.any { it.format == BookFormat.Text })
        assertTrue(all.any { it.format == BookFormat.Markdown })
        assertTrue(all.any { it.format == BookFormat.Fb2 })
        assertTrue(all.any { it.format == BookFormat.Unsupported })
    }

    @Test
    fun eachFormatFilterKeepsOnlyItsOwnFormat() {
        // 每种格式各一本，每个单项筛选各只留自己那一本 —— 文本类三种格式绝不能互相带出。
        val books = BookFormat.values().mapIndexed { index, format ->
            formatBook((index + 1).toLong(), format)
        }
        val cases = listOf(
            FormatFilter.Epub to BookFormat.Epub,
            FormatFilter.Pdf to BookFormat.Pdf,
            FormatFilter.Text to BookFormat.Text,
            FormatFilter.Markdown to BookFormat.Markdown,
            FormatFilter.Fb2 to BookFormat.Fb2,
        )

        cases.forEach { (filter, expected) ->
            val got = filterBooks(books, "", LibrarySort.RecentRead, filter)
            assertEquals("筛 $filter 后条目数", 1, got.size)
            assertEquals("筛 $filter 后格式", listOf(expected), got.map { it.format })
        }
    }

    @Test
    fun queryHitsTitleAndDropsNonMatchingBooks() {
        val books = listOf(
            formatBook(1, BookFormat.Text, title = "Kotlin 实战"),
            formatBook(2, BookFormat.Markdown, title = "统计之美"),
            formatBook(3, BookFormat.Epub, title = "Kotlin 协程"),
        )

        // 命中标题、大小写不敏感；2 标题不匹配被过滤掉。
        val hit = filterBooks(books, query = "kotlin", sort = LibrarySort.RecentRead, filter = FormatFilter.All)

        assertEquals(listOf(3L, 1L), ids(hit))
        assertTrue(hit.none { it.id == 2L })
    }

    @Test
    fun queryHitsAuthorAndDropsNonMatchingBooks() {
        val books = listOf(
            formatBook(1, BookFormat.Epub, author = "Kotlin 中文社区"),
            formatBook(2, BookFormat.Text, author = "张伟"),
            formatBook(3, BookFormat.Markdown, author = "kotlin 学院"),
        )

        val hit = filterBooks(books, query = "KOTLIN", sort = LibrarySort.RecentRead, filter = FormatFilter.All)

        assertEquals(listOf(3L, 1L), ids(hit))
        assertTrue(hit.none { it.id == 2L })
    }

    @Test
    fun queryHitsTagsAndDropsNonMatchingBooks() {
        val books = listOf(
            formatBook(1, BookFormat.Text, tags = listOf("安卓", "Kotlin")),
            formatBook(2, BookFormat.Markdown, tags = listOf("编译原理")),
            formatBook(3, BookFormat.Pdf, tags = emptyList()),
        )

        // tags 里任意一项包含关键字即算命中；2（标签不匹配）与 3（无标签）都被过滤。
        val hit = filterBooks(books, query = "kotlin", sort = LibrarySort.RecentRead, filter = FormatFilter.All)

        assertEquals(listOf(1L), ids(hit))
    }

    @Test
    fun queryHitsUnknownAuthorPlaceholderForNullAuthor() {
        // 查询匹配的是 authorLabel 而不是裸 author 字段：author 为 null 时占位文案是「未知作者」。
        val books = listOf(
            formatBook(1, BookFormat.Text, author = null),
            formatBook(2, BookFormat.Text, author = "张伟"),
        )

        val hit = filterBooks(books, query = "未知作者", sort = LibrarySort.RecentRead, filter = FormatFilter.All)

        assertEquals(listOf(1L), ids(hit))
        assertEquals("未知作者", hit.single().authorLabel)
    }

    @Test
    fun whitespaceOnlyQueryIsTreatedAsEmpty() {
        // 纯空格 trim 成空关键字：不该把任何书筛掉，但格式筛选依然生效。
        val books = listOf(
            formatBook(1, BookFormat.Text),
            formatBook(2, BookFormat.Markdown),
        )

        val hit = filterBooks(books, query = "   ", sort = LibrarySort.RecentRead, filter = FormatFilter.Text)

        assertEquals(listOf(1L), ids(hit))
    }

    @Test
    fun ratingSortStillAppliesAfterTextFilter() {
        val books = listOf(
            formatBook(1, BookFormat.Text, rating = null),
            formatBook(2, BookFormat.Text, rating = 2),
            formatBook(3, BookFormat.Markdown, rating = 5), // 格式不同，先被筛掉
            formatBook(4, BookFormat.Text, rating = 5),
            formatBook(5, BookFormat.Epub, rating = 4), // 格式不同，先被筛掉
        )

        val sorted = filterBooks(books, "", LibrarySort.Rating, FormatFilter.Text)

        // 5 星在前、未评分沉底；被筛掉的 3 / 5 不能混进来。
        assertEquals(listOf(4L, 2L, 1L), ids(sorted))
        assertEquals(listOf(5, 2, null), sorted.map { it.rating })
        assertTrue(sorted.all { it.format == BookFormat.Text })
    }

    @Test
    fun emptyListStaysEmptyUnderEveryFormatFilter() {
        FormatFilter.values().forEach { filter ->
            assertEquals(
                "筛 $filter + 空查询",
                emptyList<Long>(),
                ids(filterBooks(emptyList(), "", LibrarySort.Rating, filter)),
            )
            assertEquals(
                "筛 $filter + 非空查询",
                emptyList<Long>(),
                ids(filterBooks(emptyList(), "kotlin", LibrarySort.Title, filter)),
            )
        }
    }

    @Test
    fun unsupportedNeverLeaksIntoTextOrMarkdownFilter() {
        // 关键回归点：TXT / Markdown 都属于「文本类」，但 Unsupported 不是文本类，不能被文本筛选带出来。
        val books = listOf(
            formatBook(1, BookFormat.Text, title = "纯文本"),
            formatBook(2, BookFormat.Markdown, title = "Markdown 笔记"),
            formatBook(9, BookFormat.Unsupported, title = "AZW3 样章"),
        )

        val text = filterBooks(books, "", LibrarySort.RecentRead, FormatFilter.Text)
        val markdown = filterBooks(books, "", LibrarySort.RecentRead, FormatFilter.Markdown)

        assertEquals(listOf(1L), ids(text))
        assertEquals(listOf(2L), ids(markdown))
        assertTrue(text.none { it.format == BookFormat.Unsupported })
        assertTrue(markdown.none { it.format == BookFormat.Unsupported })
        // 语义锚点：Unsupported 既不是 Text 也不是 Markdown，isTextLike 必须为 false。
        assertFalse(BookFormat.Unsupported.isTextLike)
    }

    @Test
    fun formatFilterAndQueryBothMustMatch() {
        val books = listOf(
            formatBook(1, BookFormat.Text, title = "Kotlin 实战"),
            formatBook(2, BookFormat.Markdown, title = "Kotlin 实战"),
            formatBook(3, BookFormat.Markdown, title = "统计之美"),
        )

        // 1 被格式筛掉，3 被查询筛掉，只剩 MD 且标题命中。
        val hit = filterBooks(books, query = "kotlin", sort = LibrarySort.RecentRead, filter = FormatFilter.Markdown)

        assertEquals(listOf(2L), ids(hit))
    }
}

