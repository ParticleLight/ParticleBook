package app.particlebook.android.ui

import app.particlebook.android.ui.data.Book
import app.particlebook.android.ui.data.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 书籍详情弹层（task-43）的 4 个纯函数：不碰 Compose 运行时、不碰 Android framework，
 * 因此是纯 JVM 单测。覆盖边界：评分 0 / 6（越界）、addedAt 为 null / 空 / 脏日期、作者为空。
 */
class BookDetailSheetTest {

    // ---------------------------------------------------------------- ratingSummary

    @Test
    fun ratingSummaryIsUnratedWhenNull() {
        assertEquals("未评分", ratingSummary(null))
    }

    @Test
    fun ratingSummaryCountsStarsForEveryLegalValue() {
        for (stars in 1..5) {
            assertEquals(stars.toString() + " 星", ratingSummary(stars))
        }
    }

    @Test
    fun ratingSummaryTreatsOutOfRangeAsUnrated() {
        // 写侧（LibraryStore.setRating）会 clamp，这里防的是历史脏值把「0 星 / 6 星」这种档位画出来。
        assertEquals("未评分", ratingSummary(0))
        assertEquals("未评分", ratingSummary(6))
        assertEquals("未评分", ratingSummary(-3))
        assertEquals("未评分", ratingSummary(99))
    }

    // ---------------------------------------------------------------- nextRatingFor

    @Test
    fun nextRatingForTappingSameStarClearsIt() {
        assertNull(nextRatingFor(1, 1))
        assertNull(nextRatingFor(3, 3))
        assertNull(nextRatingFor(5, 5))
    }

    @Test
    fun nextRatingForSetsTappedStar() {
        assertEquals(3, nextRatingFor(null, 3))
        assertEquals(5, nextRatingFor(3, 5))
        assertEquals(2, nextRatingFor(5, 2))
        assertEquals(4, nextRatingFor(1, 4))
    }

    @Test
    fun nextRatingForClampsDirtyTap() {
        assertEquals(1, nextRatingFor(null, 0))
        assertEquals(1, nextRatingFor(3, -4))
        assertEquals(5, nextRatingFor(null, 6))
        assertEquals(5, nextRatingFor(3, 99))
    }

    @Test
    fun nextRatingForIgnoresOutOfRangeCurrentValue() {
        // 当前值越界时不能等于任何一颗星：第一次点击必须落成一个合法评分，而不是被当成「反选」。
        assertEquals(3, nextRatingFor(0, 3))
        assertEquals(3, nextRatingFor(6, 3))
        assertEquals(5, nextRatingFor(6, 5))
        assertEquals(1, nextRatingFor(6, 1))
    }

    @Test
    fun tappingTwiceRoundTripsThroughSummary() {
        val afterTap = nextRatingFor(null, 4)
        assertEquals("4 星", ratingSummary(afterTap))
        val afterSecondTap = nextRatingFor(afterTap, 4)
        assertNull(afterSecondTap)
        assertEquals("未评分", ratingSummary(afterSecondTap))
    }

    // ---------------------------------------------------------------- detailMetaLine

    @Test
    fun detailMetaLineJoinsFormatSizeAndDate() {
        assertEquals("EPUB · 1.2 MB · 添加于 2026-11-20", detailMetaLine(book()))
    }

    @Test
    fun detailMetaLineFormatsIsoTimestampToDateOnly() {
        assertEquals(
            "EPUB · 1.2 MB · 添加于 2026-11-20",
            detailMetaLine(book(addedAt = "2026-11-20T09:30:00Z")),
        )
        assertEquals(
            "EPUB · 1.2 MB · 添加于 2026-11-20",
            detailMetaLine(book(addedAt = "2026-11-20T09:30:00+08:00")),
        )
    }

    @Test
    fun detailMetaLineOmitsMissingDate() {
        assertEquals("EPUB · 1.2 MB", detailMetaLine(book(addedAt = null)))
        assertEquals("EPUB · 1.2 MB", detailMetaLine(book(addedAt = "")))
        assertEquals("EPUB · 1.2 MB", detailMetaLine(book(addedAt = "   ")))
    }

    @Test
    fun detailMetaLineOmitsDirtyDateWithoutThrowing() {
        assertEquals("EPUB · 1.2 MB", detailMetaLine(book(addedAt = "2026-02-30")))
        assertEquals("EPUB · 1.2 MB", detailMetaLine(book(addedAt = "2026-13-01")))
        assertEquals("EPUB · 1.2 MB", detailMetaLine(book(addedAt = "not-a-date")))
        assertEquals("EPUB · 1.2 MB", detailMetaLine(book(addedAt = "2026/11/20")))
        assertEquals("EPUB · 1.2 MB", detailMetaLine(book(addedAt = "null")))
    }

    @Test
    fun detailMetaLineUsesFormatLabelAndSizePlaceholder() {
        assertEquals(
            "PDF · — · 添加于 2026-11-20",
            detailMetaLine(book(format = BookFormat.Pdf, sizeBytes = 0L)),
        )
        assertEquals(
            "不支持 · 512 KB · 添加于 2026-11-20",
            detailMetaLine(book(format = BookFormat.Unsupported, sizeBytes = 524_288L)),
        )
    }

    @Test
    fun detailMetaLineIsStableWhenAuthorIsBlank() {
        // 作者不参与元信息行；空作者只在 authorLabel 上体现为「未知作者」，不应让元信息行空掉或变短。
        val blankAuthor = book(author = "   ")
        assertEquals("未知作者", blankAuthor.authorLabel)
        assertEquals("EPUB · 1.2 MB · 添加于 2026-11-20", detailMetaLine(blankAuthor))
        assertEquals("未知作者", book(author = null).authorLabel)
        assertEquals("EPUB · 1.2 MB · 添加于 2026-11-20", detailMetaLine(book(author = null)))
    }

    // ---------------------------------------------------------------- starContentDescription

    @Test
    fun starContentDescriptionMarksSelectionInText() {
        assertEquals("第 3 星，已选", starContentDescription(3, true))
        assertEquals("第 3 星", starContentDescription(3, false))
    }

    @Test
    fun starContentDescriptionCoversAllFivePositions() {
        for (star in 1..5) {
            assertEquals("第 " + star + " 星", starContentDescription(star, false))
            assertEquals("第 " + star + " 星，已选", starContentDescription(star, true))
        }
    }

    // ---------------------------------------------------------------- fixtures

    /** 1.2 MB = 1_258_291 字节（%.1f 四舍五入后仍是 1.2）。 */
    private fun book(
        format: BookFormat = BookFormat.Epub,
        sizeBytes: Long = 1_258_291L,
        addedAt: String? = "2026-11-20",
        author: String? = "刘慈欣",
    ): Book = Book(
        id = 1L,
        title = "三体",
        author = author,
        format = format,
        filePath = "/books/three-body.epub",
        fileSize = sizeBytes,
        addedAt = addedAt,
    )
}
