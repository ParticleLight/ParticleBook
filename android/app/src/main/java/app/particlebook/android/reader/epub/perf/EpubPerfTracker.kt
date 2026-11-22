package app.particlebook.android.reader.epub.perf

/** 一次绘制候选；session 防止切书后旧 tracker 的 token 串到新 tracker。 */
class EpubPerfToken internal constructor(
    val id: Long,
    internal val session: Long,
)

/**
 * 纯 JVM 的 PBPERF 状态机，不做 Android 调用，方便单测覆盖防重/过期逻辑。
 *
 * - 首章事件只在 openStartNs != null 时产生；置空会撤销还没提交的 pending init。
 * - 翻页事件由 beginUserTurn 单独开启，不依赖首章 token，初始/恢复/目录跳转不产生 turn。
 * - 同一页重复重绘不产生事件；旧世代/关闭后的 commit 到达时直接丢弃。
 */
class EpubPerfTracker(
    private val bookId: Long,
    initialOpenStartNs: Long?,
    private val log: (String) -> Unit,
    private val clock: () -> Long = { System.nanoTime() },
) {
    private enum class Kind { FIRST, PAGE }

    private data class Turn(
        val generation: Int,
        val chapter: Int,
        val page: Int?,
        val startNs: Long,
    )

    private data class Pending(
        val tokenId: Long,
        val kind: Kind,
        val chapter: Int,
        val page: Int,
        val textOnly: Boolean,
        val startNs: Long,
        val drawEndNs: Long,
        val generation: Int,
    )

    private var openStartNs: Long? = initialOpenStartNs
    private var firstLogged = false
    private var firstPending = false
    private var lastDrawnChapter: Int? = null
    private var lastDrawnPage: Int? = null
    private var generation = 0
    private var activeTurn: Turn? = null
    private var activeTurnClaimed = false
    private var turnNumber = 0
    private var nextTokenId = 1L
    private var closed = false
    private val session = NEXT_SESSION.getAndIncrement()
    private val pending = LinkedHashMap<Long, Pending>()

    /** 关闭/切书时传 null，撤销尚未提交的首章事件；已提交的首章不会被改写。 */
    fun updateOpenStartNs(value: Long?) {
        if (closed) return
        openStartNs = value
        if (value == null && !firstLogged) {
            pending.entries.removeAll { it.value.kind == Kind.FIRST }
            firstPending = false
        }
    }

    /** 用户确实发起翻页前调用；targetPage=null 表示上一章最后一页（目标页尚未定）。 */
    fun beginUserTurn(targetChapter: Int, targetPage: Int?, startNs: Long = clock()): Int {
        if (closed) return generation
        generation += 1
        pending.entries.removeAll { it.value.kind == Kind.PAGE }
        activeTurn = Turn(generation, targetChapter, targetPage, startNs)
        activeTurnClaimed = false
        return generation
    }

    /**
     * View 在 PreDraw 阶段用它决定是否提前注册帧提交回调。
     * firstPending 时不再申请候选：若 API29 回调缺失，本轮就没有首章事件，
     * 验收按 MISSING_FIRST_EVENT 报不 PASS，不能改用 draw 冒充正式提交。
     */
    fun hasPendingDrawCandidate(): Boolean {
        if (closed) return false
        if (!firstLogged && !firstPending && openStartNs != null) return true
        val turn = activeTurn ?: return false
        return !activeTurnClaimed && turn.generation == generation
    }

    /** 无测量候选的普通重绘也要更新实际页，供下一次翻页判断是否换页。 */
    fun onUnmeasuredDraw(chapter: Int, page: Int) {
        if (closed) return
        lastDrawnChapter = chapter
        lastDrawnPage = page
    }

    /**
     * Canvas 实际绘制结束调用。返回 null 表示本次绘制不需要记录（空白/重复/不在目标页）。
     * 返回 token 后，API 29+ 应等帧提交回调再调用 [onFrameCommitted]；否则用
     * [onDrawFallback] 立即记 draw。
     */
    fun onContentDrawn(
        chapter: Int,
        page: Int,
        textOnly: Boolean,
        hasContent: Boolean,
        drawEndNs: Long,
    ): EpubPerfToken? {
        if (closed || !hasContent) return null
        val previousChapter = lastDrawnChapter
        val previousPage = lastDrawnPage
        onUnmeasuredDraw(chapter, page)

        if (!firstLogged && !firstPending && openStartNs != null) {
            val startNs = openStartNs ?: return null
            val token = addPending(
                kind = Kind.FIRST,
                chapter = chapter,
                page = page,
                textOnly = textOnly,
                startNs = startNs,
                drawEndNs = drawEndNs,
                generation = generation,
            )
            firstPending = true
            return token
        }

        val turn = activeTurn ?: return null
        if (activeTurnClaimed || turn.generation != generation) return null
        if (turn.chapter != chapter || (turn.page != null && turn.page != page)) return null
        val changed = previousChapter != chapter || previousPage != page
        if (!changed) {
            // 用户点了同页/目标页没有变化，不产生翻页事件，也不能留给后续随机重绘。
            activeTurn = null
            activeTurnClaimed = false
            return null
        }
        val token = addPending(
            kind = Kind.PAGE,
            chapter = chapter,
            page = page,
            textOnly = textOnly,
            startNs = turn.startNs,
            drawEndNs = drawEndNs,
            generation = turn.generation,
        )
        activeTurnClaimed = true
        return token
    }

    /** API 29+ 帧提交回调；endNs 必须是帧提交后的 System.nanoTime()。 */
    fun onFrameCommitted(token: EpubPerfToken, endNs: Long) {
        val item = takePending(token) ?: return
        finish(item, endNs.coerceAtLeast(item.drawEndNs), EpubPerfEndKind.COMMIT)
    }

    /** 软件绘制/低版本没有可靠帧回调时，用 Canvas 实绘完成时间记 draw。 */
    fun onDrawFallback(token: EpubPerfToken, drawEndNs: Long) {
        val item = takePending(token) ?: return
        finish(item, drawEndNs.coerceAtLeast(item.drawEndNs), EpubPerfEndKind.DRAW)
    }

    /** View detach/替换 tracker 时只撤销本帧，避免取消尚未绘制的跨章目标。 */
    fun cancelDraw(token: EpubPerfToken) {
        val item = takePending(token) ?: return
        when (item.kind) {
            Kind.FIRST -> firstPending = false
            Kind.PAGE -> if (activeTurn?.generation == item.generation) {
                activeTurn = null
                activeTurnClaimed = false
            }
        }
    }

    fun close() {
        closed = true
        openStartNs = null
        firstPending = false
        pending.clear()
        activeTurn = null
        activeTurnClaimed = false
    }

    private fun addPending(
        kind: Kind,
        chapter: Int,
        page: Int,
        textOnly: Boolean,
        startNs: Long,
        drawEndNs: Long,
        generation: Int,
    ): EpubPerfToken {
        val id = nextTokenId++
        pending[id] = Pending(
            tokenId = id,
            kind = kind,
            chapter = chapter,
            page = page,
            textOnly = textOnly,
            startNs = startNs,
            drawEndNs = drawEndNs,
            generation = generation,
        )
        return EpubPerfToken(id, session)
    }

    private fun takePending(token: EpubPerfToken): Pending? {
        if (closed || token.session != session) return null
        return pending.remove(token.id)
    }

    private fun finish(item: Pending, endNs: Long, endKind: EpubPerfEndKind) {
        when (item.kind) {
            Kind.FIRST -> {
                if (firstLogged || !firstPending) return
                log(
                    EpubPerfEvent(
                        phase = EpubPerfEvent.PHASE_FIRST_CHAPTER,
                        bookId = bookId,
                        chapter = item.chapter,
                        page = item.page,
                        textOnly = item.textOnly,
                        startNs = item.startNs,
                        drawEndNs = item.drawEndNs,
                        endNs = endNs,
                        endKind = endKind,
                    ).toLogLine(),
                )
                firstLogged = true
                firstPending = false
            }
            Kind.PAGE -> {
                val turn = activeTurn
                if (turn == null ||
                    turn.generation != item.generation ||
                    generation != item.generation
                ) {
                    return
                }
                turnNumber += 1
                log(
                    EpubPerfEvent(
                        phase = EpubPerfEvent.PHASE_PAGE,
                        bookId = bookId,
                        chapter = item.chapter,
                        page = item.page,
                        textOnly = item.textOnly,
                        startNs = item.startNs,
                        drawEndNs = item.drawEndNs,
                        endNs = endNs,
                        endKind = endKind,
                        turn = turnNumber,
                    ).toLogLine(),
                )
                activeTurn = null
                activeTurnClaimed = false
            }
        }
    }

    private companion object {
        val NEXT_SESSION = java.util.concurrent.atomic.AtomicLong(1L)
    }
}
