package app.particlebook.android.ui

import app.particlebook.android.data.BookRow
import app.particlebook.android.data.LibraryRepository
import app.particlebook.android.data.ReadingBookStat
import app.particlebook.android.data.ReadingSessionRow
import app.particlebook.android.data.TestLibraryRepository
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 阅读统计的 JVM 单测（无 Robolectric、无 kotlinx-coroutines-test，与 SearchViewModelTest 同款做法）。
 *
 * ## 时区策略（「时区无关」的含义）
 * 生产代码用 `ZoneId.systemDefault()` 切自然日，测试不去改写它，而是**用同一句时钟构造夹具**：
 * 会话时间一律写成「某个固定日历日的 09:00 / 20:00」再按系统时区转 epoch。
 * 于是「今天 45 分钟」「昨天 45 分钟」这类断言在任何时区下都成立——夹具与断言同时跟着时区走，
 * 断言的是聚合口径（切日、补齐、streak、求和），不是某个 epoch 常量。夹具里也钉死了
 * 2026-11-23 是周一这一前提，星期缩写因此不依赖运行环境。
 *
 * ## 计时策略
 * 稳定路径把 scope 与 ioDispatcher 都设成 `Dispatchers.Unconfined`：「加载 → 写 state」在
 * 构造函数 / refresh() 返回前同步跑完，断言不需要假睡。只有真的验证「等待中」与「旧请求不覆盖
 * 新结果」的用例才用 `Dispatchers.Default` + 闸门 + [waitUntil]（轮询，不固定时长）。
 */
class StatsViewModelTest {

    // ------------------------------------------------------------------
    // 夹具：固定日历日 + 系统时区
    // ------------------------------------------------------------------

    private val zone: ZoneId = ZoneId.systemDefault()

    /** 固定「今天」：2026-11-23（周一，见 weekday 用例）。 */
    private val today: LocalDate = LocalDate.of(2026, 11, 23)
    private val yesterday: LocalDate = today.minusDays(1)

    /** 观测时刻：今天 15:00。今天的两段会话都在它之前结束。 */
    private val now: Long = atLocal(today, 15)

    private val todaySessionA = session(bookId = 1L, start = atLocal(today, 9), minutes = 30, pages = 12)
    private val todaySessionB = session(bookId = 1L, start = atLocal(today, 14), minutes = 15, pages = 3)
    private val yesterdaySession = session(bookId = 2L, start = atLocal(yesterday, 20), minutes = 45, pages = 20)
    private val fiveDaysAgoSession = session(bookId = 2L, start = atLocal(today.minusDays(5), 10), minutes = 10, pages = 5)

    private val typicalSessions = listOf(todaySessionA, todaySessionB, yesterdaySession, fiveDaysAgoSession)

    // ------------------------------------------------------------------
    // 状态机
    // ------------------------------------------------------------------

    @Test
    fun `初次加载成功进入 Ready`() {
        val repository = StatsRepository(sessions = typicalSessions)
        val model = viewModel(repository, now)

        val ready = model.state as StatsUiState.Ready
        // 100 分钟合计：30 + 15 + 45 + 10。
        assertEquals(100L * 60_000L, ready.stats.totalMillis)
        assertEquals(45L * 60_000L, ready.stats.todayMillis)
        assertEquals(40, ready.stats.pagesTurned)
        assertEquals(2, ready.stats.booksRead)
    }

    @Test
    fun `按系统时区切日：7 天补齐且最后一格是今天`() {
        val repository = StatsRepository(sessions = typicalSessions)
        val model = viewModel(repository, now)

        val days = (model.state as StatsUiState.Ready).stats.days
        assertEquals(StatsViewModel.CHART_DAYS, days.size)
        // 旧→新，最后一格 = 今天，而不是「第一个非空日」。柱状图的「今天高亮」就靠这个顺序。
        assertEquals(today.toEpochDay(), days.last().epochDay)
        assertEquals(today.minusDays(6).toEpochDay(), days.first().epochDay)
        // 落在对应日上的分钟数；中间没读的那几天必须是 0（补齐，不是压缩）。
        assertEquals(45L * 60_000L, days[days.lastIndex].millis)
        assertEquals(45L * 60_000L, days[days.lastIndex - 1].millis)
        assertEquals(10L * 60_000L, days[days.lastIndex - 5].millis)
        assertEquals(0L, days[days.lastIndex - 2].millis)
        assertEquals(0L, days.first().millis)
    }

    @Test
    fun `streak 连续天数与阅读天数一致`() {
        val repository = StatsRepository(sessions = typicalSessions)
        val model = viewModel(repository, now)

        // 今天 + 昨天连续，5 天前断开 → 2；不是「有阅读的总天数」。
        assertEquals(2, (model.state as StatsUiState.Ready).stats.streakDays)
    }

    @Test
    fun `会话为空进入 Empty`() {
        val repository = StatsRepository(sessions = emptyList())
        val model = viewModel(repository, now)
        assertEquals(StatsUiState.Empty, model.state)
    }

    @Test
    fun `会话全为零时长也进入 Empty`() {
        val zero = ReadingSessionRow(
            bookId = 3L,
            startedAt = atLocal(today, 9),
            endedAt = atLocal(today, 9),
            durationMs = 0L,
            pagesDelta = 0,
        )
        val repository = StatsRepository(sessions = listOf(zero))
        val model = viewModel(repository, now)
        // 有行但没有时长：七根空柱比空态更难解释，判定按 totalMillis 归零。
        assertEquals(StatsUiState.Empty, model.state)
    }

    @Test
    fun `仓库抛异常进入 Failed 并保留原始原因`() {
        val repository = StatsRepository(failure = IllegalStateException("database is closed"))
        val model = viewModel(repository, now)

        val failed = model.state as StatsUiState.Failed
        assertTrue("文案要说明是统计失败：" + failed.message, failed.message.contains("统计失败"))
        assertTrue("原始异常文本必须保留：" + failed.message, failed.message.contains("database is closed"))
    }

    @Test
    fun `失败后重试成功回到 Ready`() {
        val repository = StatsRepository(failure = IllegalStateException("boom"))
        val model = viewModel(repository, now)
        assertTrue(model.state is StatsUiState.Failed)

        repository.failure = null
        repository.sessions = typicalSessions
        model.refresh()

        val ready = model.state as StatsUiState.Ready
        assertEquals(100L * 60_000L, ready.stats.totalMillis)
    }

    @Test
    fun `刷新期间 state 回到 Loading`() {
        val gate = CountDownLatch(1)
        val repository = StatsRepository(sessions = typicalSessions).apply {
            // 闸门超时就直接判失败，而不是让「Loading 断言」偶然通过。
            onRead = { assertTrue("闸门超时", gate.await(2, TimeUnit.SECONDS)) }
        }
        val scope = CoroutineScope(Dispatchers.Default)
        val model = StatsViewModel(
            scope = scope,
            repositoryProvider = { repository },
            now = { now },
            ioDispatcher = Dispatchers.Default,
        )
        try {
            // 读库被闸门挡住：界面必须是「正在统计」，不能还是上一帧或空态。
            assertEquals(StatsUiState.Loading, model.state)
        } finally {
            gate.countDown()
        }
        waitUntil("闸门放开后应进入 Ready") { model.state is StatsUiState.Ready }
    }

    @Test
    fun `只查最近 60 天：下界是 now 减 60 天`() {
        val repository = StatsRepository(sessions = typicalSessions)
        viewModel(repository, now)

        assertEquals(
            listOf(now - StatsViewModel.LOOKBACK_MILLIS),
            repository.sinceRequests(),
        )
        assertEquals(60L * 24L * 60L * 60L * 1000L, StatsViewModel.LOOKBACK_MILLIS)
    }

    @Test
    fun `超出 60 天窗口的会话不进入统计`() {
        // 61 天前读的：窗口外。仓库按 ended_at >= since 过滤（与 SQL 同语义），结果落成 Empty。
        val old = session(bookId = 9L, start = atLocal(today.minusDays(61), 10), minutes = 120, pages = 50)
        val repository = StatsRepository(sessions = listOf(old))
        val model = viewModel(repository, now)
        assertEquals(StatsUiState.Empty, model.state)
    }

    @Test
    fun `旧请求晚返回不会覆盖新结果`() {
        val gate = CountDownLatch(1)
        val calls = AtomicInteger(0)
        val repository = StatsRepository(sessions = emptyList()).apply {
            onRead = {
                // 只卡住第一次查询：让「先发后至」的窗口真实存在。
                if (calls.incrementAndGet() == 1) gate.await(2, TimeUnit.SECONDS)
            }
        }
        val scope = CoroutineScope(Dispatchers.Default)
        val model = StatsViewModel(
            scope = scope,
            repositoryProvider = { repository },
            now = { now },
            ioDispatcher = Dispatchers.Default,
        )
        try {
            // 第二次刷新换成有今天阅读的数据；第一次（空结果）随后才返回。
            repository.sessions = typicalSessions
            model.refresh()
            waitUntil("第二次刷新应先落到 Ready") {
                val state = model.state
                state is StatsUiState.Ready && state.stats.todayMillis == 45L * 60_000L
            }

            gate.countDown()
            // 观察 1 秒：空结果若被写进来，todayMillis 会掉到 0 / 状态回到 Empty。
            val deadline = System.currentTimeMillis() + 1_000L
            while (System.currentTimeMillis() < deadline) {
                val state = model.state
                if (state !is StatsUiState.Ready || state.stats.todayMillis != 45L * 60_000L) {
                    fail("旧请求覆盖了新结果：" + state)
                }
                Thread.sleep(10L)
            }
        } finally {
            gate.countDown()
        }
    }

    // ------------------------------------------------------------------
    // 纯函数：时长文案
    // ------------------------------------------------------------------

    @Test
    fun `formatDuration 分钟档边界`() {
        assertEquals("不到 1 分钟", formatDuration(0L))
        assertEquals("不到 1 分钟", formatDuration(59_999L))
        assertEquals("1 分钟", formatDuration(60_000L))
        // 59 分 59 秒不掉到小时档：按分钟向下取整。
        assertEquals("59 分钟", formatDuration(59L * 60_000L + 59_000L))
        assertEquals("不到 1 分钟", formatDuration(-1L))
    }

    @Test
    fun `formatDuration 小时与天档边界`() {
        assertEquals("1 小时", formatDuration(60L * 60_000L))
        assertEquals("1 小时 30 分", formatDuration(90L * 60_000L))
        // 整小时不带「0 分」尾巴。
        assertEquals("2 小时", formatDuration(120L * 60_000L))
        assertEquals("1 天", formatDuration(24L * 60L * 60_000L))
        assertEquals("1 天 2 小时", formatDuration(26L * 60L * 60_000L))
        assertEquals("2 天 2 小时", formatDuration(50L * 60L * 60_000L))
    }

    @Test
    fun `formatDurationOrZero 零值显示 0 分钟`() {
        // hero 与副行不能用「不到 1 分钟」：没读过的时候那是错误信息。
        assertEquals("0 分钟", formatDurationOrZero(0L))
        assertEquals("0 分钟", formatDurationOrZero(-5L))
        assertEquals("1 分钟", formatDurationOrZero(60_000L))
        assertEquals("1 小时", formatDurationOrZero(60L * 60_000L))
    }

    @Test
    fun `chartMinuteLabel 零值显示破折号`() {
        assertEquals("—", chartMinuteLabel(0L))
        assertEquals("<1", chartMinuteLabel(30_000L))
        assertEquals("1", chartMinuteLabel(60_000L))
        assertEquals("90", chartMinuteLabel(90L * 60_000L))
    }

    // ------------------------------------------------------------------
    // 纯函数：星期与读屏描述
    // ------------------------------------------------------------------

    @Test
    fun `weekdayShortLabel 与日历对齐`() {
        // 前提钉死：2026-11-23 是周一，2026-11-29 是周日。
        assertEquals(DayOfWeek.MONDAY, today.dayOfWeek)
        assertEquals("一", weekdayShortLabel(today.toEpochDay()))
        assertEquals("日", weekdayShortLabel(today.plusDays(6).toEpochDay()))
        assertEquals("六", weekdayShortLabel(today.plusDays(5).toEpochDay()))
    }

    @Test
    fun `dayDescription 区分今天与没读的日子`() {
        assertEquals("今天，阅读 45 分钟", dayDescription(today.toEpochDay(), 45L * 60_000L, isToday = true))
        assertEquals("周三，没有阅读", dayDescription(today.plusDays(2).toEpochDay(), 0L, isToday = false))
    }

    @Test
    fun `页面窗口常量与汇总说明同源`() {
        assertEquals(7, StatsViewModel.CHART_DAYS)
        assertEquals(60, StatsViewModel.LOOKBACK_DAYS)
        assertEquals(
            StatsViewModel.LOOKBACK_DAYS.toLong() * 24L * 60L * 60L * 1000L,
            StatsViewModel.LOOKBACK_MILLIS,
        )
    }

    // ------------------------------------------------------------------
    // 常读的书：书名映射与窗口
    // ------------------------------------------------------------------

    @Test
    fun `常读的书书名来自书架`() {
        val repository = StatsRepository(sessions = typicalSessions).apply {
            bookRows = listOf(
                BookRow(id = 1L, stableKey = "b1", title = "三体"),
                BookRow(id = 2L, stableKey = "b2", title = "   "),
            )
        }
        val model = viewModel(repository, now)

        // book 2：昨天 45 + 5 天前 10 = 55 分钟；book 1：今天 30 + 15 = 45 分钟。
        val top = (model.state as StatsUiState.Ready).stats.topBooks
        assertEquals(listOf(2L, 1L), top.map { it.bookId })
        // 只有空白算没书名：降级成占位，不显示一行空白。
        assertEquals("未知书籍 #2", top[0].title)
        assertEquals("三体", top[1].title)
        assertEquals(55L * 60_000L, top[0].millis)
        assertEquals(45L * 60_000L, top[1].millis)
        // 分母是 7 天窗口总时长（100 分钟），两天前没读的那天是 0，不影响。
        assertEquals(0.55, top[0].share, 1e-9)
        assertEquals(0.45, top[1].share, 1e-9)
    }

    @Test
    fun `取书名抛异常时统计仍然 Ready 并退回占位书名`() {
        val repository = StatsRepository(sessions = typicalSessions).apply {
            booksFailure = IllegalStateException("books table is gone")
        }
        val model = viewModel(repository, now)

        // 书架读失败只是让书名缺失：统计本身已经算完，不该整页 Failed。
        val ready = model.state as StatsUiState.Ready
        assertEquals(100L * 60_000L, ready.stats.totalMillis)
        assertTrue("排行不应该因为书名失败而消失：" + ready.stats.topBooks, ready.stats.topBooks.isNotEmpty())
        assertTrue(
            "书名应退回占位：" + ready.stats.topBooks.map { it.title },
            ready.stats.topBooks.all { it.title.startsWith("未知书籍 #") },
        )
    }

    @Test
    fun `常读的书只算 7 天窗口内的会话`() {
        // 10 天前读的 120 分钟：在 60 天查询窗口内，但在 7 天排行窗口外。
        val old = session(bookId = 9L, start = atLocal(today.minusDays(10), 10), minutes = 120, pages = 50)
        val repository = StatsRepository(sessions = typicalSessions + old)
        val model = viewModel(repository, now)

        val ready = model.state as StatsUiState.Ready
        assertTrue("窗口外的书不应进榜：" + ready.stats.topBooks, ready.stats.topBooks.none { it.bookId == 9L })
        // 汇总仍覆盖 60 天查询窗口。
        assertEquals(220L * 60_000L, ready.stats.totalMillis)
    }

    @Test
    fun `常读的书最多 5 行`() {
        val sessions = (1L..6L).map { id ->
            session(bookId = id, start = atLocal(today, 9), minutes = 10, pages = 1)
        }
        val repository = StatsRepository(sessions = sessions)
        val model = viewModel(repository, now)

        val top = (model.state as StatsUiState.Ready).stats.topBooks
        // 同时长按 bookId 升序，第 6 本被 limit 截掉。
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), top.map { it.bookId })
        assertEquals(StatsViewModel.TOP_BOOK_LIMIT, top.size)
    }

    // ------------------------------------------------------------------
    // 纯函数：占比文案与读屏描述
    // ------------------------------------------------------------------

    @Test
    fun `sharePercentLabel 四舍五入并挡住脏数据`() {
        assertEquals("占 0%", sharePercentLabel(0.0))
        assertEquals("占 45%", sharePercentLabel(0.45))
        assertEquals("占 33%", sharePercentLabel(0.3333))
        // 非有限值与越界值都不该把 roundToInt 打崩（NaN 会抛异常）。
        assertEquals("占 0%", sharePercentLabel(Double.NaN))
        assertEquals("占 0%", sharePercentLabel(-0.2))
        assertEquals("占 100%", sharePercentLabel(1.4))
    }

    @Test
    fun `bookStatDescription 一句念完书名时长页数占比`() {
        val stat = ReadingBookStat(bookId = 7L, title = "三体", millis = 45L * 60_000L, pages = 12, share = 0.45)
        assertEquals("三体，45 分钟，12 页，占 45%", bookStatDescription(stat))

        // 只有翻页没有时长：0 读作「0 分钟」，不是「不到 1 分钟」。
        val pagesOnly = ReadingBookStat(bookId = 8L, title = "未知书籍 #8", millis = 0L, pages = 3, share = 0.0)
        assertEquals("未知书籍 #8，0 分钟，3 页，占 0%", bookStatDescription(pagesOnly))
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun atLocal(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun session(bookId: Long, start: Long, minutes: Long, pages: Int): ReadingSessionRow {
        val duration = minutes * 60_000L
        return ReadingSessionRow(
            bookId = bookId,
            startedAt = start,
            endedAt = start + duration,
            durationMs = duration,
            pagesDelta = pages,
        )
    }

    private fun viewModel(
        repository: LibraryRepository,
        now: Long,
        ioDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ): StatsViewModel = StatsViewModel(
        scope = CoroutineScope(Dispatchers.Unconfined),
        repositoryProvider = { repository },
        now = { now },
        ioDispatcher = ioDispatcher,
    )

    /** 轮询等待，不假睡固定时长；超时后由 assertTrue 报出带上下文的失败信息。 */
    private fun waitUntil(message: String, timeoutMs: Long = 5_000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(10L)
        }
        assertTrue(message, predicate())
    }
}

/**
 * 记录查询下界并允许注入结果/异常的假仓库。
 *
 * 覆盖 [LibraryRepository.readingSessionsSince]（记录下界、注入结果/异常）与
 * [LibraryRepository.books]（注入书名或让它抛异常），其余方法（标注、下载、事务）
 * 全部委托给 [TestLibraryRepository]，不重抄一遍接口。
 */
private class StatsRepository(
    private val delegate: TestLibraryRepository = TestLibraryRepository(),
    var sessions: List<ReadingSessionRow> = emptyList(),
    var failure: Throwable? = null,
    /** 书架：统计页只用来把 bookId 翻成书名；null 表示沿用 delegate 的书架。 */
    var bookRows: List<BookRow>? = null,
    /** 让 books() 抛异常，验证「书名失败不影响统计」。 */
    var booksFailure: Throwable? = null,
) : LibraryRepository by delegate {

    private val lock = Any()
    private val sinceRequests = ArrayList<Long>()

    /** 在真正返回结果前执行（用来制造阻塞 / 并发窗口）。 */
    var onRead: (() -> Unit)? = null

    fun sinceRequests(): List<Long> = synchronized(lock) { sinceRequests.toList() }

    override fun books(): List<BookRow> {
        booksFailure?.let { throw it }
        return bookRows ?: delegate.books()
    }

    override fun readingSessionsSince(sinceMillis: Long): List<ReadingSessionRow> {
        // 先拍快照再进钩子：钩子里改 sessions 不能影响本次查询（模拟「SQL 已经查完」）。
        val snapshot = synchronized(lock) {
            sinceRequests.add(sinceMillis)
            sessions
        }
        onRead?.invoke()
        failure?.let { throw it }
        // 与 SQL 的 `WHERE ended_at >= ?` 同语义。
        return snapshot.filter { it.endedAt >= sinceMillis }
    }
}
