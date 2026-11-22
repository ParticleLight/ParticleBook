package app.particlebook.android.reader.pdf

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** task-13：翻页即停的判定（PDF 连续滚动，页面会随滚动反复重算）。 */
class PdfTtsFollowTest {

    @Test
    fun noReadingNeverRequestsStop() {
        assertFalse(PdfTtsFollow.shouldStop(reading = null, visiblePage = 3))
    }

    @Test
    fun samePageKeepsReading() {
        assertFalse(PdfTtsFollow.shouldStop(reading = reading(5), visiblePage = 5))
    }

    @Test
    fun turningForwardStopsReading() {
        assertTrue(PdfTtsFollow.shouldStop(reading = reading(5), visiblePage = 6))
    }

    @Test
    fun turningBackwardStopsReading() {
        assertTrue(PdfTtsFollow.shouldStop(reading = reading(5), visiblePage = 4))
    }

    /** 只关心页码；句子内容取自页面自身文本，这里给一页够用的最小文本。 */
    private fun reading(pageIndex: Int): PdfTtsPage = PdfTtsPage.from(
        PdfTtsFixtures.page(
            text = "一页字数",
            chars = PdfTtsFixtures.chars("一页字数"),
            pageIndex = pageIndex,
        ),
    )
}
