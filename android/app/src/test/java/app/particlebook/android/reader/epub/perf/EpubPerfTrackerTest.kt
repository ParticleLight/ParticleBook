package app.particlebook.android.reader.epub.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubPerfTrackerTest {

    private fun tracker(
        bookId: Long = 42L,
        openStartNs: Long? = null,
        logs: MutableList<String>,
    ): EpubPerfTracker = EpubPerfTracker(
        bookId = bookId,
        initialOpenStartNs = openStartNs,
        log = { logs += it },
    )

    @Test
    fun firstChapterUsesOpenTokenAndCommitFormat() {
        val logs = mutableListOf<String>()
        val subject = tracker(openStartNs = 1_000_000_000L, logs = logs)
        assertTrue(subject.hasPendingDrawCandidate())

        val token = subject.onContentDrawn(
            chapter = 0,
            page = 0,
            textOnly = true,
            hasContent = true,
            drawEndNs = 1_500_000_000L,
        )
        assertNotNull(token)
        assertNull(
            "同一页重复重绘不能产生第二个首章候选",
            subject.onContentDrawn(0, 0, true, true, 1_600_000_000L),
        )

        subject.onFrameCommitted(token!!, 1_550_000_000L)
        assertEquals(
            "PBPERF|phase=reader_epub_first_chapter|ms=550.0|" +
                "extra=book_id:42,chapter:0,page:0,text_only:1," +
                "start_ns:1000000000,draw_end_ns:1500000000,end_ns:1550000000,end_kind:commit",
            logs.single(),
        )
        assertFalse(subject.hasPendingDrawCandidate())
        subject.close()
    }

    @Test
    fun millisecondWireFormatKeepsFraction() {
        val line = EpubPerfEvent(
            phase = EpubPerfEvent.PHASE_PAGE,
            bookId = 7L,
            chapter = 59,
            page = 3,
            textOnly = true,
            startNs = 1_000_000L,
            drawEndNs = 17_800_000L,
            endNs = 17_900_000L,
            endKind = EpubPerfEndKind.COMMIT,
            turn = 1,
        ).toLogLine()
        assertTrue(line, line.contains("|ms=16.9|"))
        assertTrue(line, line.contains("turn:1"))
    }

    @Test
    fun pageTurnNeedsUserTurnAndActualPageChange() {
        val logs = mutableListOf<String>()
        val subject = tracker(logs = logs)

        // 初始绘制没有 beginUserTurn：不算翻页。
        assertNull(subject.onContentDrawn(0, 0, true, true, 10_000_000L))
        assertFalse(subject.hasPendingDrawCandidate())

        subject.beginUserTurn(targetChapter = 0, targetPage = 1, startNs = 1_000_000L)
        assertTrue(subject.hasPendingDrawCandidate())
        val token = subject.onContentDrawn(0, 1, true, true, 2_000_000L)
        assertNotNull(token)
        subject.onFrameCommitted(token!!, 3_000_000L)
        assertTrue(logs.single().contains("phase=reader_epub_page"))
        assertTrue(logs.single().contains("turn:1"))
        assertTrue(logs.single().contains("ms=2"))

        // 没有新用户动作的同页重绘不再是翻页。
        assertNull(subject.onContentDrawn(0, 1, true, true, 4_000_000L))
        assertEquals(1, logs.size)
        subject.close()
    }

    @Test
    fun unmeasuredTocDrawUpdatesPageForFollowingUserTurn() {
        val logs = mutableListOf<String>()
        val subject = tracker(logs = logs)
        subject.onUnmeasuredDraw(59, 0)
        subject.beginUserTurn(59, 1, startNs = 10L)
        val first = subject.onContentDrawn(59, 1, true, true, 20L)!!
        subject.onFrameCommitted(first, 30L)
        subject.onUnmeasuredDraw(59, 0)
        subject.beginUserTurn(59, 1, startNs = 40L)
        val second = subject.onContentDrawn(59, 1, true, true, 50L)!!
        subject.onFrameCommitted(second, 60L)
        assertEquals(2, logs.size)
        assertTrue(logs.last().endsWith("turn:2"))
    }

    @Test
    fun samePageUserTurnIsDiscarded() {
        val logs = mutableListOf<String>()
        val subject = tracker(logs = logs)
        subject.onContentDrawn(0, 0, true, true, 10_000_000L)
        subject.beginUserTurn(0, 0, startNs = 20_000_000L)
        assertNull(subject.onContentDrawn(0, 0, true, true, 30_000_000L))
        assertFalse(subject.hasPendingDrawCandidate())
        assertTrue(logs.isEmpty())
        subject.close()
    }

    @Test
    fun oldGenerationCommitCannotStealCurrentSample() {
        val logs = mutableListOf<String>()
        val subject = tracker(logs = logs)
        subject.onContentDrawn(0, 0, true, true, 10_000_000L)

        subject.beginUserTurn(0, 1, startNs = 20_000_000L)
        val oldToken = subject.onContentDrawn(0, 1, true, true, 30_000_000L)!!
        subject.beginUserTurn(0, 2, startNs = 40_000_000L)
        val newToken = subject.onContentDrawn(0, 2, true, true, 50_000_000L)!!

        subject.onFrameCommitted(oldToken, 60_000_000L)
        assertTrue(logs.isEmpty())

        subject.onFrameCommitted(newToken, 70_000_000L)
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains("page:2"))
        assertTrue(logs.single().contains("turn:1"))
        subject.close()
    }

    @Test
    fun clearingOpenTokenCancelsPendingFirstChapter() {
        val logs = mutableListOf<String>()
        val subject = tracker(openStartNs = 100L, logs = logs)
        val token = subject.onContentDrawn(0, 0, true, true, 200L)!!
        subject.updateOpenStartNs(null)
        subject.onFrameCommitted(token, 300L)
        assertTrue(logs.isEmpty())
        assertFalse(subject.hasPendingDrawCandidate())

        subject.updateOpenStartNs(400L)
        val retry = subject.onContentDrawn(0, 0, true, true, 500L)
        assertNotNull(retry)
        subject.onFrameCommitted(retry!!, 600L)
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains("start_ns:400"))
        subject.close()
    }

    @Test
    fun drawFallbackUsesDrawEndKind() {
        val logs = mutableListOf<String>()
        val subject = tracker(logs = logs)
        subject.onContentDrawn(0, 0, true, true, 10_000_000L)
        subject.beginUserTurn(0, 1, startNs = 20_000_000L)
        val token = subject.onContentDrawn(0, 1, true, true, 30_000_000L)!!
        subject.onDrawFallback(token, 31_000_000L)
        assertTrue(logs.single().contains("end_kind:draw"))
        assertTrue(logs.single().contains("end_ns:31000000"))
        subject.close()
    }

    @Test
    fun tokenFromAnotherTrackerCannotCommitCurrentPending() {
        val oldLogs = mutableListOf<String>()
        val oldTracker = tracker(openStartNs = 1L, logs = oldLogs)
        val oldToken = oldTracker.onContentDrawn(0, 0, true, true, 2L)!!

        val newLogs = mutableListOf<String>()
        val newTracker = tracker(openStartNs = 3L, logs = newLogs)
        val newToken = newTracker.onContentDrawn(0, 0, true, true, 4L)!!
        // 两个 tracker 的首个 token id 都是 1；session 必须把旧 token 挡掉。
        newTracker.onFrameCommitted(oldToken, 5L)
        assertTrue(newLogs.isEmpty())
        newTracker.onFrameCommitted(newToken, 6L)
        assertEquals(1, newLogs.size)
        oldTracker.close()
        newTracker.close()
    }

    @Test
    fun closeDropsPendingAndDisablesFutureEvents() {
        val logs = mutableListOf<String>()
        val subject = tracker(openStartNs = 1L, logs = logs)
        val token = subject.onContentDrawn(0, 0, true, true, 2L)!!
        subject.close()
        subject.onFrameCommitted(token, 3L)
        assertTrue(logs.isEmpty())
        assertFalse(subject.hasPendingDrawCandidate())
        assertNull(subject.onContentDrawn(0, 1, true, true, 4L))
    }
}
