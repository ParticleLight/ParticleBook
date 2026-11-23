package app.particlebook.android.data

import java.time.ZoneId

/**
 * 阅读会话记录器：把「打开一本书 → 翻页 → 退出」翻译成 reading_sessions 行。
 *
 * 设计要点：
 * - 可 JVM 单测：只依赖 [LibraryRepository] 接口、时钟和时区，不碰 Android API；
 * - 写库是同步的（[LibraryRepository.saveReadingSession] 自己也是同步语义），
 *   调用方负责把 [stop] / [flushIfDue] 放到 IO dispatcher，和 data 层其它 store 的约定一致；
 * - 用 [synchronized] 而不是 kotlinx Mutex：这些方法都是阻塞签名，会在生命周期回调里被调，
 *   Mutex 需要挂起上下文（或 runBlocking 把主线程卡在锁上），锁粒度反而是同步块更合适；
 * - 统计写入失败绝不打断阅读：持久化异常在这一层吞掉并返回 null，统计少一段而不是让阅读器崩。
 *
 * 语义：
 * - [start] 同书重复调用（重组/回前台）沿用已累计时长，只同步当前页；换书则先按 [stop] 语义
 *   落盘上一段，避免统计缺口；
 * - [flushIfDue] 用于进程随时可能被杀的场景：每 [flushIntervalMillis] 落一段并把起点前移，
 *   已落盘的部分不再受后续崩溃影响；flush 后的尾巴若短于 [minSessionMillis] 会被丢弃（代价 < 5s）；
 * - pages_delta 数「页码真正变化的次数」，不是页数差；首个已知页码视为起始页，不算翻一次；
 * - start_page / end_page 记首末**已知**页，未知（null）不参与计数。
 *
 * @param repositoryProvider 返回当前 [LibraryRepository]；数据库还没就绪时返回 null，本段会话不落库。
 * @param now 时钟，单测注入假时间。
 * @param zone 统计口径时区，与 [ReadingStatsCalculator.compute] 保持一致；记录器自身不按天切行
 *   （跨午夜的会话由聚合器按墙钟比例切开），这个字段留着避免上层各自取 systemDefault() 漂移。
 * @param minSessionMillis 短于该时长的一次会话不落库，避免「点开就退」刷出噪声行。
 * @param flushIntervalMillis 长会话分段落盘的间隔。
 */
class ReadingSessionRecorder(
    private val repositoryProvider: () -> LibraryRepository?,
    private val now: () -> Long = System::currentTimeMillis,
    val zone: ZoneId = ZoneId.systemDefault(),
    private val minSessionMillis: Long = 5_000L,
    private val flushIntervalMillis: Long = 60_000L,
) {
    private val lock = Any()

    /** 当前会话所属书籍；null 表示没有进行中的会话。 */
    private var bookId: Long? = null

    /** 当前未落盘片段的起点（flush 会前移它）。 */
    private var startedAt: Long = 0L

    /** 上次 flush（或 start）的时刻，用于判断 flush 是否到期。 */
    private var flushAnchorAt: Long = 0L
    private var startPage: Int? = null
    private var lastPage: Int? = null
    private var pagesDelta: Int = 0

    /** 开始（或继续）一本书的会话；见类注释里的同书/换书语义。 */
    fun start(bookId: Long, page: Int?) {
        synchronized(lock) {
            val active = this.bookId
            if (active == bookId) {
                // 同书重复 start：保住已累计时长，只把当前页同步进来。
                onPageChangedLocked(page)
                return
            }
            if (active != null) finishLocked()
            val at = now()
            this.bookId = bookId
            startedAt = at
            flushAnchorAt = at
            startPage = page
            lastPage = page
            pagesDelta = 0
        }
    }

    /** 翻页通知；页码没变、传 null 都不计数。没有进行中的会话时忽略。 */
    fun onPageChanged(page: Int?) {
        synchronized(lock) {
            if (bookId == null) return
            onPageChangedLocked(page)
        }
    }

    /**
     * 结束会话并落盘。
     *
     * @return 已写入的行（带自增 id）；会话过短、没有进行中的会话、或写库失败时返回 null。
     */
    fun stop(): ReadingSessionRow? = synchronized(lock) { finishLocked() }

    /**
     * 到期就落一段，用于降低进程被杀时的损失。
     *
     * 只有「距上次落盘 >= [flushIntervalMillis] 且本段 >= [minSessionMillis]」才写；
     * 写失败时不前移起点，下次调用会带着更长的一段重试。
     *
     * @return 已写入的行；未到期、无会话或写失败返回 null。
     */
    fun flushIfDue(): ReadingSessionRow? = synchronized(lock) {
        val currentBook = bookId ?: return null
        val at = now()
        if (at - flushAnchorAt < flushIntervalMillis) return null
        val duration = (at - startedAt).coerceAtLeast(0L)
        // 防御：flush 间隔被配到最小会话长度以下时，不写出碎片行。
        if (duration < minSessionMillis) return null
        val row = ReadingSessionRow(
            bookId = currentBook,
            startedAt = startedAt,
            endedAt = at,
            durationMs = duration,
            pagesDelta = pagesDelta,
            startPage = startPage,
            endPage = lastPage,
        )
        val persisted = persist(row) ?: return null
        startedAt = at
        flushAnchorAt = at
        startPage = lastPage
        pagesDelta = 0
        persisted
    }

    /** 放弃当前会话：不落盘、不留下任何状态（例如打开后立刻切走且不该计入统计的场景）。 */
    fun cancel() {
        synchronized(lock) { resetLocked() }
    }

    private fun finishLocked(): ReadingSessionRow? {
        val currentBook = bookId ?: return null
        val endedAt = now()
        val duration = (endedAt - startedAt).coerceAtLeast(0L)
        val row = ReadingSessionRow(
            bookId = currentBook,
            startedAt = startedAt,
            endedAt = endedAt,
            durationMs = duration,
            pagesDelta = pagesDelta,
            startPage = startPage,
            endPage = lastPage,
        )
        // 先复位再落盘：即便下面抛错，也不会留下半截状态。
        resetLocked()
        if (duration < minSessionMillis) return null
        return persist(row)
    }

    private fun onPageChangedLocked(page: Int?) {
        if (page == null) return
        val previous = lastPage
        if (previous == null) {
            // 首个已知页码：补上起始页，不算翻页。
            lastPage = page
            startPage = page
            return
        }
        if (page == previous) return
        lastPage = page
        pagesDelta += 1
    }

    private fun persist(row: ReadingSessionRow): ReadingSessionRow? {
        val repository = runCatching { repositoryProvider() }.getOrNull() ?: return null
        return try {
            row.copy(id = repository.saveReadingSession(row))
        } catch (error: Exception) {
            // 统计是旁路：写失败不抛出，丢这一段但不影响阅读。诊断交给调用方按 null 处理。
            null
        }
    }

    private fun resetLocked() {
        bookId = null
        startedAt = 0L
        flushAnchorAt = 0L
        startPage = null
        lastPage = null
        pagesDelta = 0
    }
}
