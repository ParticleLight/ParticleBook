package app.particlebook.android.reader.pdf.perf

internal enum class PdfOutcome(val wire: String) {
    OK("ok"), ERROR("error"), CANCELLED("cancelled"), CLOSED("closed"),
}

internal enum class PdfOrigin(val wire: String) {
    DEMAND("demand"), PREFETCH("prefetch"), LAZY("lazy"), REUSED("reused"),
}

internal enum class PdfCacheResult(val wire: String) {
    MISS("miss"), JOIN("join"), HIT("hit"), REUSED("reused"),
}

internal enum class PdfNavSource(val wire: String) {
    DRAG("drag"), TOC("toc"), SLIDER("slider"), SEARCH("search"),
}

internal enum class PdfCloseResult(val wire: String) {
    OK("ok"), ERROR("error"), NOT_OPENED("not_opened"),
}

/** One attempt, including its failed/cancelled operations. Only the sink touches logcat. */
internal class PdfPerfSession(
    private val processId: String,
    private val sessionId: Long,
    bookId: Long,
    private val clock: () -> Long,
    private val sink: (Map<String, Any?>) -> Unit,
    private val eventLimit: Long = 4096L,
) {
    internal class Request(
        val id: Long,
        val page: Int,
        val origin: PdfOrigin,
        val opening: Boolean,
        val navId: Long?,
        val startedNs: Long,
        var visibleNs: Long?,
    ) {
        var delivered = false
        var producer: Long? = null
    }

    internal class Op(val id: Long, val request: Request?, val queuedNs: Long) {
        var began = false
        var workerEndNs: Long? = null
        var workerOutcome: PdfOutcome? = null
        var pageCount: Int? = null
    }

    internal class Nav(val id: Long, val from: Int, val target: Int?)

    private var seq = 0L
    private var requestId = 0L
    private var opId = 0L
    private var navId = 0L
    private var opening = true
    private var closing = false
    private var sealed = false
    private var overflow = false
    private var nav: Nav? = null
    private val requests = mutableMapOf<Long, Request>()
    private val operations = mutableMapOf<Long, Op>()

    init {
        emit("session_start", clock(), "book_id" to bookId)
    }

    fun now(): Long = clock()

    @Synchronized
    fun startNav(from: Int, target: Int?, source: PdfNavSource): Nav? {
        if (closing) return null
        val ns = clock()
        nav?.let { finishNav(it, null, "superseded") }
        opening = false
        return Nav(++navId, from, target).also {
            nav = it
            emit("nav_begin", ns, "nav_id" to it.id, "from_page" to from,
                "target_page" to target, "source" to source.wire)
        }
    }

    @Synchronized
    fun finishNav(token: Nav, to: Int?, reason: String? = null) {
        if (nav !== token) return
        nav = null
        val failure = reason ?: when {
            to == null -> "unsettled"
            to == token.from -> "no_change"
            token.target != null && to != token.target -> "target_missed"
            else -> null
        }
        emit("nav_end", clock(), "nav_id" to token.id, "to_page" to to,
            "outcome" to if (failure == null) PdfOutcome.OK.wire else PdfOutcome.CANCELLED.wire,
            "reason" to failure)
    }

    @Synchronized
    fun startRequest(page: Int, origin: PdfOrigin): Request? {
        if (closing) return null
        val ns = clock()
        val initialVisible = origin == PdfOrigin.DEMAND || origin == PdfOrigin.REUSED
        val request = Request(++requestId, page, origin, opening && origin != PdfOrigin.REUSED,
            nav?.id?.takeIf { origin == PdfOrigin.DEMAND }, ns, ns.takeIf { initialVisible })
        requests[request.id] = request
        emit("request_start", ns, "request_id" to request.id, "page" to page,
            "origin" to origin.wire, "opening_member" to request.opening, "nav_id" to request.navId)
        return request
    }

    /** Lazy requests qualify as initial only if the viewport actually intersects them before seal. */
    @Synchronized
    fun observeVisible(request: Request): Boolean {
        if (closing || requests[request.id] !== request || request.origin == PdfOrigin.PREFETCH) return false
        if (request.visibleNs != null) return true
        if (!opening || !request.opening) return false
        request.visibleNs = clock()
        return true
    }

    @Synchronized
    fun finishRequest(request: Request, outcome: PdfOutcome, cache: PdfCacheResult?, producer: Long?) {
        if (requests[request.id] !== request || request.delivered) return
        request.producer = producer
        request.delivered = true
        emit("request_end", clock(), "request_id" to request.id, "page" to request.page,
            "outcome" to outcome.wire, "cache" to cache?.wire, "producer_op_id" to producer)
        if (outcome != PdfOutcome.OK || request.origin == PdfOrigin.PREFETCH) {
            if (request.visibleNs != null) present(request, outcome)
            else requests.remove(request.id)
        }
    }

    @Synchronized
    fun releaseRequest(request: Request, outcome: PdfOutcome = PdfOutcome.CANCELLED) {
        if (requests[request.id] !== request) return
        if (!request.delivered) finishRequest(request, outcome, null, request.producer)
        if (requests[request.id] === request) {
            if (request.visibleNs != null) present(request, outcome)
            else requests.remove(request.id)
        }
    }

    @Synchronized
    fun awaitingPresent(request: Request): Boolean = !closing &&
        requests[request.id] === request && request.delivered && request.visibleNs != null

    @Synchronized
    fun confirmPresent(request: Request, drawNs: Long, commitNs: Long) {
        val visible = request.visibleNs ?: return
        if (!awaitingPresent(request) || drawNs < visible || commitNs < drawNs) return
        present(request, PdfOutcome.OK, drawNs, commitNs)
    }

    private fun present(request: Request, outcome: PdfOutcome, drawNs: Long? = null, commitNs: Long? = null) {
        requests.remove(request.id)
        emit("page_present", commitNs ?: clock(), "request_id" to request.id, "page" to request.page,
            "producer_op_id" to request.producer, "visible_ns" to request.visibleNs,
            "draw_ns" to drawNs, "commit_ts" to commitNs, "outcome" to outcome.wire)
    }

    @Synchronized
    fun queueOp(request: Request? = null, queuedNs: Long = clock()): Op? {
        if (closing || (request != null && requests[request.id] !== request)) return null
        return Op(++opId, request, queuedNs).also {
            operations[it.id] = it
            if (request != null) request.producer = it.id
            emitOp(it, "op_queued", queuedNs)
        }
    }

    @Synchronized
    fun beginOp(op: Op) {
        if (operations[op.id] !== op || op.began) return
        op.began = true
        emitOp(op, "op_begin", clock())
    }

    @Synchronized
    fun workerEnded(op: Op, outcome: PdfOutcome = PdfOutcome.OK, pageCount: Int? = null) {
        if (operations[op.id] !== op || !op.began || op.workerEndNs != null) return
        op.workerEndNs = clock()
        op.workerOutcome = outcome
        op.pageCount = pageCount
    }

    @Synchronized
    fun finishOp(op: Op, outcome: PdfOutcome, pageCount: Int? = null, endNs: Long = clock()) {
        if (operations[op.id] !== op) return
        operations.remove(op.id)
        // Prompt cancellation can discard delivery after the worker really succeeded.
        // The producer keeps that result; request_end independently retains cancellation.
        val actual = op.workerOutcome ?: outcome
        val count = if (op.request == null && actual == PdfOutcome.OK) op.pageCount ?: pageCount else null
        emitOp(op, "op_end", endNs, "outcome" to actual.wire,
            "page_count" to count, "worker_end_ns" to op.workerEndNs)
    }

    private fun emitOp(op: Op, event: String, ns: Long, vararg extra: Pair<String, Any?>) {
        val r = op.request
        emit(event, ns, "op_id" to op.id, "kind" to if (r == null) "open" else "render",
            "page" to r?.page, "request_id" to r?.id, "origin" to (r?.origin ?: PdfOrigin.DEMAND).wire,
            "opening_member" to (r?.opening ?: true), "nav_id" to r?.navId, *extra)
    }

    @Synchronized
    fun beginClose() {
        if (closing) return
        closing = true
        nav?.let { finishNav(it, null, "reader_close") }
        // A delivered request can still be waiting for a frame. In-flight delivery finishes itself.
        requests.values.filter { it.delivered }.forEach { releaseRequest(it, PdfOutcome.CLOSED) }
    }

    @Synchronized
    fun seal(count: Int, bytes: Long, source: PdfCloseResult, text: PdfCloseResult): Boolean {
        if (!closing || sealed || operations.isNotEmpty() || requests.isNotEmpty() || nav != null) return false
        val failed = overflow || count != 0 || bytes != 0L ||
            source == PdfCloseResult.ERROR || text == PdfCloseResult.ERROR
        emit("session_close", clock(), "final_seq" to seq + 1,
            "outcome" to if (failed) PdfOutcome.ERROR.wire else PdfOutcome.OK.wire,
            "cache_count" to count, "cache_bytes" to bytes, "source_close" to source.wire,
            "text_close" to text.wire, "reason" to "overflow".takeIf { overflow })
        sealed = true
        return true
    }

    private fun emit(event: String, ns: Long, vararg fields: Pair<String, Any?>) {
        if (event != "session_close" && seq >= eventLimit - 1) {
            overflow = true
            return
        }
        val row = linkedMapOf<String, Any?>("v" to 1, "process_id" to processId,
            "session_id" to sessionId, "seq" to ++seq, "event" to event, "ts_ns" to ns)
        for ((name, value) in fields) {
            if (name != "reason" || value != null) row[name] = value
        }
        sink(row)
    }
}
