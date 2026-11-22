package app.particlebook.android.reader.pdf.perf

import org.junit.Assert.*
import org.junit.Test

internal class TraceTestFixture(eventLimit: Long = 4096, sessionId: Long = 1) {
    var time = 1_000L
    val rows = mutableListOf<Map<String, Any?>>()
    val perf = PdfPerfSession("7af36f91-b296-4aad-9697-0e8059a73fb1", sessionId, 204,
        { time * 1_000_000 }, rows::add, eventLimit)

    fun named(event: String) = rows.filter { it["event"] == event }
    fun draw(request: PdfPerfSession.Request, at: Long, commit: Long) {
        val window = Any()
        val frame = PdfDrawFrame(window)
        frame.record(window, request, at * 1_000_000)
        frame.commit(window, commit * 1_000_000, perf)
    }
}

/** Drives the native state and frame pair using the independent runner synthetic timestamps. */
internal fun nativeGolden(): List<Map<String, Any?>> {
    val f = TraceTestFixture()
    val t = f.perf
    f.time = 1001
    val open = checkNotNull(t.queueOp())
    f.time = 1003
    t.beginOp(open)
    f.time = 1020
    t.workerEnded(open)
    f.time = 1021
    t.finishOp(open, PdfOutcome.OK, pageCount = 3)
    f.time = 1030
    val initial = checkNotNull(t.startRequest(0, PdfOrigin.DEMAND))
    f.time = 1031
    val first = checkNotNull(t.queueOp(initial))
    f.time = 1032
    t.beginOp(first)
    f.time = 1040
    t.workerEnded(first)
    f.time = 1041
    t.finishOp(first, PdfOutcome.OK)
    f.time = 1042
    t.finishRequest(initial, PdfOutcome.OK, PdfCacheResult.MISS, first.id)
    f.draw(initial, 1045, 1050)
    f.time = 2000
    val nav = checkNotNull(t.startNav(0, null, PdfNavSource.DRAG))
    f.time = 2001
    val request = checkNotNull(t.startRequest(1, PdfOrigin.DEMAND))
    f.time = 2002
    val render = checkNotNull(t.queueOp(request))
    f.time = 2122
    t.beginOp(render)
    f.time = 2127
    t.workerEnded(render)
    f.time = 2128
    t.finishOp(render, PdfOutcome.OK)
    f.time = 2129
    t.finishRequest(request, PdfOutcome.OK, PdfCacheResult.MISS, render.id)
    f.draw(request, 2130, 2132)
    f.time = 2140
    t.finishNav(nav, 1)
    f.time = 3000
    t.beginClose()
    check(t.seal(0, 0, PdfCloseResult.OK, PdfCloseResult.NOT_OPENED))
    return f.rows
}

class PdfPerfSessionTest {
    @Test fun opensAndRendersOnRealQueueAndReturnBoundaries() {
        val rows = nativeGolden()
        assertEquals(19, rows.size)
        assertEquals((1L..19L).toList(), rows.map { it["seq"] })
        fun time(seq: Int) = rows[seq - 1]["ts_ns"] as Long
        assertEquals(20_000_000L, time(4) - time(2))
        assertEquals(10_000_000L, time(8) - time(6))
        assertEquals(20_000_000L, time(10) - time(5))
        assertEquals(126_000_000L, time(15) - time(13))
        val workerEnd = rows[14]["worker_end_ns"] as Long
        assertEquals(5_000_000L, workerEnd - time(14))
        assertEquals(120_000_000L, time(14) - time(13))
        assertEquals(1_000_000L, time(15) - workerEnd)
        assertEquals(rows[18]["seq"], rows[18]["final_seq"])
        assertEquals("ok", rows[18]["source_close"])
        assertEquals("not_opened", rows[18]["text_close"])
    }

    @Test fun openingPrefetchThatFinishesDuringNavStaysOpeningWhenDemandJoins() {
        val f = TraceTestFixture(); val t = f.perf
        val warm = t.startRequest(2, PdfOrigin.PREFETCH)!!
        val op = t.queueOp(warm)!!
        t.beginOp(op)
        f.time++
        val nav = t.startNav(1, null, PdfNavSource.DRAG)!!
        val demand = t.startRequest(2, PdfOrigin.DEMAND)!!
        t.workerEnded(op)
        t.finishOp(op, PdfOutcome.OK)
        t.finishRequest(warm, PdfOutcome.OK, PdfCacheResult.MISS, op.id)
        t.finishRequest(demand, PdfOutcome.OK, PdfCacheResult.JOIN, op.id)
        f.draw(demand, f.time, ++f.time)
        t.finishNav(nav, 2)
        assertEquals(listOf("prefetch", "prefetch", "prefetch"),
            f.rows.filter { it["kind"] == "render" }.map { it["origin"] })
        assertTrue(f.named("op_end").single()["opening_member"] as Boolean)
        assertNull(f.named("op_end").single()["nav_id"])
        assertEquals("join", f.named("request_end").last()["cache"])
        assertEquals(op.id, f.named("page_present").single()["producer_op_id"])
    }

    @Test fun fiftyConfirmedWarmMovesCannotCreateAnyRenderOperation() {
        repeat(5) { round ->
            val f = TraceTestFixture(sessionId = round + 1L); val t = f.perf
            val pages = (0..2).associateWith { page ->
                val warm = t.startRequest(page, PdfOrigin.PREFETCH)!!
                val op = t.queueOp(warm)!!
                t.beginOp(op)
                t.workerEnded(op)
                t.finishOp(op, PdfOutcome.OK)
                t.finishRequest(warm, PdfOutcome.OK, PdfCacheResult.MISS, op.id)
                op.id
            }
            var page = 0
            repeat(10) { move ->
                val target = listOf(1, 2, 1, 0)[move % 4]
                val nav = t.startNav(page, null, PdfNavSource.DRAG)!!
                val request = t.startRequest(target, PdfOrigin.DEMAND)!!
                t.finishRequest(request, PdfOutcome.OK, PdfCacheResult.HIT, pages[target])
                f.draw(request, f.time, ++f.time)
                t.finishNav(nav, target)
                page = target
            }
            t.beginClose()
            assertTrue(t.seal(0, 0, PdfCloseResult.OK, PdfCloseResult.OK))
            assertEquals(10, f.named("nav_end").count { it["outcome"] == "ok" })
            assertEquals(0, f.named("op_end").count { it["nav_id"] != null })
            assertEquals(10, f.named("request_end").count { it["cache"] == "hit" })
        }
    }

    @Test fun cancelDoesNotTurnIntoSuccessAndSealWaitsForTheProducer() {
        val f = TraceTestFixture(); val t = f.perf
        val request = t.startRequest(0, PdfOrigin.DEMAND)!!
        val op = t.queueOp(request)!!
        t.beginOp(op)
        t.releaseRequest(request)
        t.beginClose()
        assertFalse(t.seal(0, 0, PdfCloseResult.OK, PdfCloseResult.NOT_OPENED))
        t.workerEnded(op)
        t.finishOp(op, PdfOutcome.OK)
        t.finishRequest(request, PdfOutcome.OK, PdfCacheResult.MISS, op.id)
        f.draw(request, f.time, ++f.time)
        assertTrue(t.seal(0, 0, PdfCloseResult.OK, PdfCloseResult.NOT_OPENED))
        assertEquals("cancelled", f.named("request_end").single()["outcome"])
        assertEquals("cancelled", f.named("page_present").single()["outcome"])
        assertEquals("ok", f.named("op_end").single()["outcome"])
        assertNull(t.startRequest(1, PdfOrigin.PREFETCH))
    }

    @Test fun queuedCancellationHasNoExecutionAndOldSessionCannotBindToNewRequest() {
        val f = TraceTestFixture(); val t = f.perf
        val request = t.startRequest(0, PdfOrigin.DEMAND)!!
        val op = t.queueOp(request)!!
        t.finishOp(op, PdfOutcome.CANCELLED)
        t.beginOp(op)
        t.finishRequest(request, PdfOutcome.CANCELLED, null, op.id)
        assertTrue(f.named("op_begin").isEmpty())
        assertNull(f.named("op_end").single()["worker_end_ns"])
        val second = TraceTestFixture(sessionId = 2)
        val new = second.perf.startRequest(0, PdfOrigin.DEMAND)!!
        assertEquals(request.id, new.id)
        second.perf.finishRequest(new, PdfOutcome.OK, PdfCacheResult.REUSED, null)
        second.draw(request, second.time, ++second.time)
        assertTrue(second.named("page_present").isEmpty())
    }

    @Test fun frameRequiresRealDrawSameWindowAndDoesNotReuseCancelledTraversal() {
        val f = TraceTestFixture(); val t = f.perf
        val request = t.startRequest(0, PdfOrigin.DEMAND)!!
        t.finishRequest(request, PdfOutcome.OK, PdfCacheResult.HIT, 3)
        val window = Any(); val other = Any()
        PdfDrawFrame(window).commit(window, t.now(), t)
        PdfDrawFrame(window).apply { record(other, request, t.now()); commit(window, t.now(), t) }
        PdfDrawFrame(window).apply { record(window, request, t.now()); commit(other, t.now(), t) }
        PdfDrawFrame(window).apply { record(window, request, t.now()); cancel(); commit(window, t.now(), t) }
        assertTrue(f.named("page_present").isEmpty())
        val frame = PdfDrawFrame(window)
        f.time++
        frame.record(window, request, t.now())
        f.time++
        frame.commit(window, t.now(), t)
        frame.commit(window, t.now(), t)
        assertEquals("ok", f.named("page_present").single()["outcome"])
    }

    @Test fun openingVisibilityIsObservedBeforeSealAndUsesOriginalRequestClock() {
        val f = TraceTestFixture(); val t = f.perf
        val initial = t.startRequest(1, PdfOrigin.LAZY)!!
        val offscreen = t.startRequest(2, PdfOrigin.LAZY)!!
        f.time++
        assertTrue(t.observeVisible(initial))
        f.time++
        t.startNav(0, 1, PdfNavSource.TOC)
        assertFalse(t.observeVisible(offscreen))
        t.finishRequest(initial, PdfOutcome.OK, PdfCacheResult.HIT, 2)
        f.draw(initial, f.time, ++f.time)
        assertEquals(initial.startedNs + 1_000_000L, f.named("page_present").single()["visible_ns"])
        assertEquals(null, initial.navId)
        assertEquals(null, offscreen.navId)
    }

    @Test fun traceOverflowIsExplicitFailureAndCannotSealTwice() {
        val f = TraceTestFixture(eventLimit = 3); val t = f.perf
        val request = t.startRequest(1, PdfOrigin.PREFETCH)!!
        t.finishRequest(request, PdfOutcome.OK, PdfCacheResult.HIT, 2)
        t.beginClose()
        assertTrue(t.seal(0, 0, PdfCloseResult.OK, PdfCloseResult.OK))
        assertFalse(t.seal(0, 0, PdfCloseResult.OK, PdfCloseResult.OK))
        assertEquals("error", f.rows.last()["outcome"])
        assertEquals("overflow", f.rows.last()["reason"])
        assertEquals(3L, f.rows.last()["final_seq"])
    }
}
