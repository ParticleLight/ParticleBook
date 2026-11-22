package app.particlebook.android.reader.epub.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubPerfFrameTest {
    private val text = EpubPerfPageContent(hasContent = true, textOnly = true)
    private val empty = EpubPerfPageContent(hasContent = false, textOnly = false)

    private fun tracker(logs: MutableList<String>, openStartNs: Long? = 1L) =
        EpubPerfTracker(201L, openStartNs, { logs += it })

    @Test
    fun emptyFirstDrawDoesNotConsumeFirstContentFrame() {
        val logs = mutableListOf<String>()
        val tracker = tracker(logs)
        val emptyFrame = EpubPerfFrame(tracker, 6, 0)
        emptyFrame.onContentDrawn(empty, 2L)
        emptyFrame.onFrameCommitted(3L)
        assertTrue(logs.isEmpty())
        assertTrue(tracker.hasPendingDrawCandidate())
        // 已提交的空帧不会被后续内容复用。
        emptyFrame.onContentDrawn(text, 4L)
        emptyFrame.onFrameCommitted(5L)
        assertTrue(logs.isEmpty())
        val contentFrame = EpubPerfFrame(tracker, 6, 0)
        contentFrame.onContentDrawn(text, 6L)
        contentFrame.onFrameCommitted(7L)
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains("chapter:6,page:0,text_only:1"))
        assertTrue(logs.single().contains("start_ns:1,draw_end_ns:6,end_ns:7,end_kind:commit"))
    }

    @Test
    fun callbackBeforeContentNeedsFreshFrame() {
        val logs = mutableListOf<String>()
        val tracker = tracker(logs)
        val stale = EpubPerfFrame(tracker, 6, 0)
        stale.onFrameCommitted(2L)
        stale.onContentDrawn(text, 3L)
        assertTrue(tracker.hasPendingDrawCandidate())
        val fresh = EpubPerfFrame(tracker, 6, 0)
        fresh.onContentDrawn(text, 4L)
        fresh.onFrameCommitted(5L)
        assertTrue(logs.single().contains("draw_end_ns:4,end_ns:5"))
    }

    @Test
    fun repeatedDrawAndCommitKeepOriginalDrawEnd() {
        val logs = mutableListOf<String>()
        val tracker = tracker(logs)
        val frame = EpubPerfFrame(tracker, 6, 0)
        frame.onContentDrawn(text, 2L)
        frame.onContentDrawn(text, 3L)
        frame.onFrameCommitted(4L)
        frame.onFrameCommitted(5L)
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains("draw_end_ns:2,end_ns:4"))
    }

    @Test
    fun detachCancelsFirstPendingAndAllowsReattach() {
        val logs = mutableListOf<String>()
        val tracker = tracker(logs)
        val detached = EpubPerfFrame(tracker, 6, 0)
        detached.onContentDrawn(text, 2L)
        assertFalse(tracker.hasPendingDrawCandidate())
        detached.cancel()
        detached.cancel()
        assertTrue(tracker.hasPendingDrawCandidate())
        val reattached = EpubPerfFrame(tracker, 6, 0)
        reattached.onContentDrawn(text, 3L)
        detached.onFrameCommitted(4L)
        assertTrue(logs.isEmpty())
        reattached.onFrameCommitted(5L)
        assertTrue(logs.single().contains("draw_end_ns:3,end_ns:5"))
    }

    @Test
    fun detachDropsDrawnTurnInsteadOfLeavingClaimedSample() {
        val logs = mutableListOf<String>()
        val tracker = tracker(logs, openStartNs = null)
        tracker.onContentDrawn(59, 0, true, true, 1L)
        tracker.beginUserTurn(59, 1, 2L)
        val detached = EpubPerfFrame(tracker, 59, 1)
        detached.onContentDrawn(text, 3L)
        detached.cancel()
        detached.onFrameCommitted(4L)
        assertFalse(tracker.hasPendingDrawCandidate())
        val reattached = EpubPerfFrame(tracker, 59, 1)
        reattached.onContentDrawn(text, 5L)
        reattached.onFrameCommitted(6L)
        assertTrue(logs.isEmpty())
        tracker.beginUserTurn(59, 2, 7L)
        val next = EpubPerfFrame(tracker, 59, 2)
        next.onContentDrawn(text, 8L)
        next.onFrameCommitted(9L)
        assertTrue(logs.single().contains("page:2"))
        assertTrue(logs.single().endsWith("turn:1"))
    }

    @Test
    fun cancellingOldViewKeepsNewCrossChapterTurn() {
        val logs = mutableListOf<String>()
        val tracker = tracker(logs, openStartNs = null)
        tracker.onContentDrawn(59, 0, true, true, 1L)
        tracker.beginUserTurn(59, 1, 2L)
        val old = EpubPerfFrame(tracker, 59, 1)
        old.onContentDrawn(text, 3L)
        tracker.beginUserTurn(60, 0, 4L)
        old.cancel()
        assertTrue(tracker.hasPendingDrawCandidate())
        val target = EpubPerfFrame(tracker, 60, 0)
        target.onContentDrawn(text, 5L)
        target.onFrameCommitted(6L)
        old.onFrameCommitted(7L)
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains("chapter:60,page:0"))
        assertTrue(logs.single().contains("start_ns:4"))
    }

    @Test
    fun cancellingUnboundOldViewKeepsCrossChapterTurn() {
        val logs = mutableListOf<String>()
        val tracker = tracker(logs, openStartNs = null)
        tracker.onContentDrawn(59, 0, true, true, 1L)
        val unbound = EpubPerfFrame(tracker, 59, 0)
        tracker.beginUserTurn(60, 0, 2L)
        unbound.cancel()
        val target = EpubPerfFrame(tracker, 60, 0)
        target.onContentDrawn(text, 3L)
        target.onFrameCommitted(4L)
        assertTrue(logs.single().contains("chapter:60,page:0"))
    }

    @Test
    fun frameMatchesOnlyItsOriginalTrackerChapterAndPage() {
        val logs = mutableListOf<String>()
        val tracker = tracker(logs)
        val another = tracker(mutableListOf())
        val frame = EpubPerfFrame(tracker, 6, 0)
        assertTrue(frame.matches(tracker, 6, 0))
        assertFalse(frame.matches(another, 6, 0))
        assertFalse(frame.matches(tracker, 7, 0))
        assertFalse(frame.matches(tracker, 6, 1))
        frame.cancel()
        assertFalse(frame.matches(tracker, 6, 0))
    }

    @Test
    fun imageFirstFrameIsContentButNotTextOnly() {
        val logs = mutableListOf<String>()
        val tracker = tracker(logs)
        val frame = EpubPerfFrame(tracker, 0, 0)
        frame.onContentDrawn(EpubPerfPageContent(true, false), 2L)
        frame.onFrameCommitted(3L)
        assertTrue(logs.single().contains("text_only:0"))
    }
}
