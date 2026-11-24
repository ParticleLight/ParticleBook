package app.particlebook.android.ui.data

import app.particlebook.android.data.ProgressSnapshot
import app.particlebook.android.reader.ReaderLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TXT/Markdown「文本阅读进度快照」单测。
 *
 * 本轮口径：文本阅读器把「段落下标」写进 ProgressSnapshot.page，把「段落总数」写进 totalPages，
 * locator 用 ReaderLocator.Text(offset = 该段首字符偏移, length = 0)。
 * 这里验证这套复用成立 —— percentLabel/hasProgress/fraction/savedPage 沿用页口径的语义，
 * locator() 对 Text 做非负校验，并且既有 PDF 口径不回归。
 */
class ProgressSnapshotTextProgressTest {

    /** 纯文本快照：段落下标进 page，段落总数进 totalPages。 */
    private fun textSnapshot(
        page: Int?,
        totalPages: Int?,
        offset: Int = 0,
    ): ProgressSnapshot = ProgressSnapshot(
        bookId = 1L,
        locatorJson = ReaderLocator.Text(offset = offset, length = 0).encode(),
        page = page,
        totalPages = totalPages,
    )

    @Test
    fun textLocatorAtZeroOffsetIsKept() {
        // 第 0 段（offset=0）是最常见的位置，不能被「空值」误判成坏 locator。
        val snapshot = ProgressSnapshot(
            bookId = 1L,
            locatorJson = ReaderLocator.Text(offset = 0, length = 0).encode(),
        )
        assertEquals(ReaderLocator.Text(0, 0), snapshot.locator())
    }

    @Test
    fun textLocatorKeepsParagraphOffset() {
        // 段首字符偏移必须原样保留，进度恢复才能跳回同一段。
        val locator = ReaderLocator.Text(offset = 4321, length = 0)
        val snapshot = ProgressSnapshot(bookId = 1L, locatorJson = locator.encode())
        assertEquals(locator, snapshot.locator())
    }

    @Test
    fun negativeTextOffsetIsDropped() {
        // 负偏移会让阅读器滚到不存在的段落，按坏 locator 丢弃。
        val snapshot = ProgressSnapshot(
            bookId = 1L,
            locatorJson = ReaderLocator.Text(offset = -1, length = 0).encode(),
        )
        assertNull(snapshot.locator())
    }

    @Test
    fun negativeTextLengthIsDropped() {
        // 负长度是非法区间，同样丢弃。
        val snapshot = ProgressSnapshot(
            bookId = 1L,
            locatorJson = ReaderLocator.Text(offset = 0, length = -1).encode(),
        )
        assertNull(snapshot.locator())
    }

    @Test
    fun scrollLocatorIsKept() {
        // 滚动容器不是文本口径，不能被 Text 的校验误伤。
        val snapshot = ProgressSnapshot(bookId = 1L, locatorJson = ReaderLocator.Scroll(0.5f).encode())
        assertEquals(ReaderLocator.Scroll(0.5f), snapshot.locator())
    }

    @Test
    fun garbageLocatorJsonDecodesToNull() {
        // 乱码/无法解析的行只影响恢复，不抛异常。
        listOf("乱码", "not-a-locator", "v1|text|abc|0", "%%%|%%%").forEach { raw ->
            assertNull(raw, ProgressSnapshot(bookId = 1L, locatorJson = raw).locator())
        }
    }

    @Test
    fun firstParagraphOfHundredReadsOnePercent() {
        val snapshot = textSnapshot(page = 0, totalPages = 100)
        assertEquals("已读 1%", snapshot.percentLabel())
        assertTrue(snapshot.hasProgress())
        assertEquals(0.01, snapshot.fraction()!!.toDouble(), 1e-4)
        assertEquals(0, snapshot.savedPage())
        assertNotNull(snapshot.locator())
    }

    @Test
    fun lastParagraphOfHundredReadsFullPercent() {
        val snapshot = textSnapshot(page = 99, totalPages = 100, offset = 98765)
        assertEquals("已读 100%", snapshot.percentLabel())
        assertEquals(1.0, snapshot.fraction()!!.toDouble(), 1e-4)
        assertEquals(99, snapshot.savedPage())
    }

    @Test
    fun pagePastEndClampsToFullPercent() {
        // 越界段落下标（旧快照、文档变短）夹到末段，不显示 151%。
        val snapshot = textSnapshot(page = 150, totalPages = 100)
        assertEquals("已读 100%", snapshot.percentLabel())
        assertEquals(1.0, snapshot.fraction()!!.toDouble(), 1e-4)
        assertEquals(99, snapshot.savedPage())
    }

    @Test
    fun nullPageIsTreatedAsFirstParagraph() {
        val snapshot = textSnapshot(page = null, totalPages = 100)
        assertEquals("已读 1%", snapshot.percentLabel())
        assertTrue(snapshot.hasProgress())
        assertEquals(0.01, snapshot.fraction()!!.toDouble(), 1e-4)
        // 没有 page 就没有可恢复的段落下标：savedPage 为 null，而不是 0。
        assertNull(snapshot.savedPage())
    }

    @Test
    fun singleParagraphDocumentHasNoProgress() {
        // 单段文档没有「读到哪」这回事：不显示进度，但仍可保存第 0 段。
        val snapshot = textSnapshot(page = 0, totalPages = 1)
        assertNull(snapshot.percentLabel())
        assertFalse(snapshot.hasProgress())
        assertNull(snapshot.fraction())
        assertEquals(0, snapshot.savedPage())
    }

    @Test
    fun zeroTotalPagesHasNoProgress() {
        // 段落总数未知或为 0：不算进度，也不允许保存下标。
        val withPage = textSnapshot(page = 0, totalPages = 0)
        assertNull(withPage.percentLabel())
        assertFalse(withPage.hasProgress())
        assertNull(withPage.fraction())
        assertNull(withPage.savedPage())

        val withoutPage = textSnapshot(page = null, totalPages = 0)
        assertNull(withoutPage.percentLabel())
        assertFalse(withoutPage.hasProgress())
        assertNull(withoutPage.fraction())
        assertNull(withoutPage.savedPage())

        // totalPages 缺省同样按 0 处理。
        val missingTotal = textSnapshot(page = 3, totalPages = null)
        assertNull(missingTotal.percentLabel())
        assertFalse(missingTotal.hasProgress())
        assertNull(missingTotal.fraction())
        assertNull(missingTotal.savedPage())
    }

    @Test
    fun savedPageReturnsParagraphIndexAndClamps() {
        assertEquals(42, textSnapshot(page = 42, totalPages = 100).savedPage())
        assertEquals(0, textSnapshot(page = -5, totalPages = 100).savedPage())
        assertEquals(99, textSnapshot(page = 100, totalPages = 100).savedPage())
        // totalPages 为 1 时只有第 0 段合法。
        assertEquals(0, textSnapshot(page = 7, totalPages = 1).savedPage())
    }

    @Test
    fun pdfNegativePageLocatorIsDropped() {
        // 既有 PDF 口径不回归：页码为负的 locator 仍然丢弃。
        val snapshot = ProgressSnapshot(
            bookId = 1L,
            locatorJson = ReaderLocator.Pdf(page = -1, x = 0.5f, y = 0.5f).encode(),
        )
        assertNull(snapshot.locator())
    }

    @Test
    fun pdfOutOfRangeCoordinatesAreDropped() {
        val badX = ProgressSnapshot(
            bookId = 1L,
            locatorJson = ReaderLocator.Pdf(page = 3, x = 1.5f, y = 0.5f).encode(),
        )
        assertNull(badX.locator())

        val badY = ProgressSnapshot(
            bookId = 1L,
            locatorJson = ReaderLocator.Pdf(page = 3, x = 0.5f, y = -0.1f).encode(),
        )
        assertNull(badY.locator())

        // 对照组：合法 PDF locator 必须原样保留，防止校验被写宽成「全部丢弃」。
        val good = ProgressSnapshot(
            bookId = 1L,
            locatorJson = ReaderLocator.Pdf(page = 3, x = 0.25f, y = 0.75f).encode(),
        )
        assertEquals(ReaderLocator.Pdf(3, 0.25f, 0.75f), good.locator())
    }

    @Test
    fun textLocatorRoundTripsThroughEncodeIntoSnapshot() {
        // 用真实 encode 生成 locatorJson，再喂回 ProgressSnapshot.locator()，验证往返。
        val locators = listOf(
            ReaderLocator.Text(offset = 0, length = 0),
            ReaderLocator.Text(offset = 1, length = 0),
            ReaderLocator.Text(offset = 120_000, length = 0),
            ReaderLocator.Text(offset = 500, length = 12),
        )
        locators.forEach { locator ->
            val encoded = locator.encode()
            assertTrue(encoded, encoded.startsWith("v1|text|"))
            val snapshot = ProgressSnapshot(bookId = 1L, locatorJson = encoded, page = 7, totalPages = 100)
            assertEquals(locator, snapshot.locator())
            assertEquals(7, snapshot.savedPage())
        }
    }

    @Test
    fun twoParagraphsReachFiftyPercentAtFirstParagraph() {
        // 两段文档读到第 0 段就是一半，避免整数换算把 0.5 算成 0%。
        assertEquals("已读 50%", textSnapshot(page = 0, totalPages = 2).percentLabel())
        assertEquals(0.5, textSnapshot(page = 0, totalPages = 2).fraction()!!.toDouble(), 1e-4)
        assertEquals("已读 100%", textSnapshot(page = 1, totalPages = 2).percentLabel())
    }

    @Test
    fun percentLabelTruncatesInsteadOfRounding() {
        // 标签走整数除法截断：3 段读到第 2 段是 66%，不是 67%。
        assertEquals("已读 33%", textSnapshot(page = 0, totalPages = 3).percentLabel())
        assertEquals("已读 66%", textSnapshot(page = 1, totalPages = 3).percentLabel())
        assertEquals("已读 100%", textSnapshot(page = 2, totalPages = 3).percentLabel())
    }
}
