package app.particlebook.android.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 统计页的一根柱子：某个自然日的阅读时长与翻页数。 */
data class ReadingDay(
    /** 自 1970-01-01 起的自然日序号（[LocalDate.toEpochDay]）；与时区无关，UI 用它反查星期。 */
    val epochDay: Long,
    val millis: Long,
    val pages: Int,
)

/** 统计页「常读的书」的一行：统计窗口内某本书的时长、翻页与占比。 */
data class ReadingBookStat(
    /** books 表主键。 */
    val bookId: Long,
    /** 展示名：优先用调用方给的书名；查不到（书已删、换了库、标题为空）时是「未知书籍 #id」。 */
    val title: String,
    /** 统计窗口内的阅读时长：按天切分后只累加落在窗口内的份额。 */
    val millis: Long,
    /** 统计窗口内的正向翻页数；翻页按会话开始日归属，只有开始日落在窗口内才计入（与柱状图同口径）。 */
    val pages: Int,
    /** [millis] 占窗口总时长的比例，0.0..1.0；窗口总时长为 0（或窗口为空）时恒为 0.0，不会出现 NaN。 */
    val share: Double,
)

/** 阅读统计快照；完全由 [ReadingStatsCalculator] 从会话行聚合，不落库。 */
data class ReadingStats(
    /** 全部历史会话的时长之和。 */
    val totalMillis: Long,
    /** 今天（按调用方 zone 的自然日）的时长。 */
    val todayMillis: Long,
    /** 最近 dayCount 天，旧 → 新；没有阅读的日期补 0。 */
    val days: List<ReadingDay>,
    /** 从今天往前连续有阅读的天数；今天还没读但昨天读过时从昨天起算。 */
    val streakDays: Int,
    /** 出现过会话的不同书籍数。 */
    val booksRead: Int,
    /** 会话里正向页码变化之和（回翻不计负数）。 */
    val pagesTurned: Int,
    /**
     * 常读的书：窗口内时长降序、同长按 bookId 升序，最多 topBookLimit 条。
     *
     * 带默认值是为了让「只要汇总、不要排行」的既有构造调用（含测试夹具）不用改。
     */
    val topBooks: List<ReadingBookStat> = emptyList(),
)

/**
 * 纯聚合：不碰 Android、不碰数据库，输入 [ReadingSessionRow] 列表即可，JVM 单测直接调用。
 *
 * 口径（统计页 UI 依赖这些约定）：
 * - 自然日按调用方传入的 [ZoneId] 切分，不用系统默认时区，避免设备改时区把历史数据切乱；
 * - 跨午夜的会话按墙钟重叠比例分到两天，余数归到最后一天，保证每日之和恒等于该行的 durationMs；
 * - 页码变化记在会话开始那天——会话行没有逐页时间点，无法更细地还原；
 * - 负的 durationMs / pagesDelta 视为脏数据按 0 计，统计不出现负值；
 * - [ReadingStats.totalMillis] / [ReadingStats.booksRead] / [ReadingStats.pagesTurned]
 *   覆盖全部历史，不受 dayCount 窗口限制。
 * - 窗口只有一个来源：[windowRange]。柱状图的桶、「常读的书」的时长与翻页、以及占比的分母
 *   全部由它推导；切分只做一次、份额同时写进「按日」与「按书按日」两个桶，
 *   因此各书窗口时长之和恒等于柱状图各日之和。
 */
object ReadingStatsCalculator {

    fun compute(
        sessions: List<ReadingSessionRow>,
        now: Long,
        zone: ZoneId,
        dayCount: Int = 7,
        bookTitles: Map<Long, String> = emptyMap(),
        topBookLimit: Int = 5,
    ): ReadingStats {
        val today = epochDay(now, zone)
        val window = windowRange(today, dayCount)
        val millisByDay = HashMap<Long, Long>()
        val pagesByDay = HashMap<Long, Int>()
        // bookId → (自然日 → 时长)：与 millisByDay 吃同一份切分，只是多带一层书的维度。
        val millisByBookAndDay = HashMap<Long, HashMap<Long, Long>>()
        val pagesByBook = HashMap<Long, Int>()
        val books = HashSet<Long>()
        var totalMillis = 0L
        var pagesTurned = 0

        sessions.forEach { session ->
            books += session.bookId
            val duration = session.durationMs.coerceAtLeast(0L)
            totalMillis += duration
            val pages = session.pagesDelta.coerceAtLeast(0)
            pagesTurned += pages
            if (duration > 0L) {
                val byBook = millisByBookAndDay.getOrPut(session.bookId) { HashMap() }
                splitAcrossDays(session, duration, zone, millisByDay, byBook)
            }
            if (pages > 0) {
                val day = epochDay(session.startedAt, zone)
                pagesByDay[day] = (pagesByDay[day] ?: 0) + pages
                if (day in window) pagesByBook[session.bookId] = (pagesByBook[session.bookId] ?: 0) + pages
            }
        }

        // 空窗口（dayCount 非正数）时 map 出来就是空表：补桶规则与「常读的书」共用 window，不写两遍。
        val days = window.map { day ->
            ReadingDay(epochDay = day, millis = millisByDay[day] ?: 0L, pages = pagesByDay[day] ?: 0)
        }
        val windowMillis = days.sumOf { it.millis }

        return ReadingStats(
            totalMillis = totalMillis,
            todayMillis = millisByDay[today] ?: 0L,
            days = days,
            streakDays = streakDays(today, millisByDay),
            booksRead = books.size,
            pagesTurned = pagesTurned,
            topBooks = buildTopBooks(
                window = window,
                windowMillis = windowMillis,
                millisByBookAndDay = millisByBookAndDay,
                pagesByBook = pagesByBook,
                bookTitles = bookTitles,
                limit = topBookLimit,
            ),
        )
    }

    /**
     * 今天有阅读就从今天数；今天还没有阅读允许从昨天起算一次，
     * 再往前遇到空白天立刻停——不把「歇了一天」当连续。
     */
    private fun streakDays(today: Long, millisByDay: Map<Long, Long>): Int {
        var day = today
        if ((millisByDay[day] ?: 0L) <= 0L) day -= 1
        var count = 0
        while ((millisByDay[day] ?: 0L) > 0L) {
            count += 1
            day -= 1
        }
        return count
    }

    /**
     * 「常读的书」排行。
     *
     * 排序：时长降序，时长相同按 bookId 升序——书名可能缺省或重名，bookId 稳定，
     * 同一份数据两次渲染顺序不变（列表顺序就是名次，UI 不再另加序号）。
     * 窗口内既没时长也没翻页的书不进榜：它在窗口里不可见，占位只会挤掉真正读过的书。
     * [limit] 非正数直接给空表，而不是「不限」——调用方写错数值时宁可少一块 UI，也不要一屏全量。
     */
    private fun buildTopBooks(
        window: LongRange,
        windowMillis: Long,
        millisByBookAndDay: Map<Long, Map<Long, Long>>,
        pagesByBook: Map<Long, Int>,
        bookTitles: Map<Long, String>,
        limit: Int,
    ): List<ReadingBookStat> {
        if (limit <= 0 || window.isEmpty()) return emptyList()
        // LinkedHashSet：先写有时长贡献的书，再补只有翻页贡献的书；排序前顺序无所谓，只为去重不漏。
        val bookIds = LinkedHashSet<Long>()
        bookIds += millisByBookAndDay.keys
        bookIds += pagesByBook.keys
        val rows = bookIds.mapNotNull { bookId ->
            val byDay = millisByBookAndDay[bookId]
            var windowedMillis = 0L
            if (byDay != null) {
                for (day in window) windowedMillis += byDay[day] ?: 0L
            }
            val pages = pagesByBook[bookId] ?: 0
            if (windowedMillis <= 0L && pages <= 0) return@mapNotNull null
            ReadingBookStat(
                bookId = bookId,
                title = bookTitle(bookId, bookTitles),
                millis = windowedMillis,
                pages = pages,
                // 分母是窗口总时长；窗口内没有任何时长时 share 只能是 0.0，不做 0/0。
                share = if (windowMillis <= 0L) 0.0 else windowedMillis.toDouble() / windowMillis.toDouble(),
            )
        }
        return rows
            .sortedWith(compareByDescending<ReadingBookStat> { it.millis }.thenBy { it.bookId })
            .take(limit)
    }

    /** 书名缺省：书被删掉、换库或标题为空时给可辨认的占位，UI 不会出现空白行头。 */
    private fun bookTitle(bookId: Long, bookTitles: Map<Long, String>): String =
        bookTitles[bookId]?.trim()?.takeIf { it.isNotEmpty() } ?: (FALLBACK_TITLE_PREFIX + bookId)

    /**
     * 把一段会话按时区自然日切开。
     *
     * 区间按半开 [start, end) 处理：正好结束在午夜的会话全部算前一天。
     * 每日份额按墙钟重叠比例向下取整，残差统一给最后一天，因此 sum(days) == duration 恒成立。
     *
     * 同一份份额按 [out] 逐个写入：调用方既要知道「每天读了多少」（柱状图），也要知道
     * 「某本书每天读了多少」（常读的书）。两处共用这一次切分，窗口口径因此不会分叉。
     */
    private fun splitAcrossDays(
        session: ReadingSessionRow,
        duration: Long,
        zone: ZoneId,
        vararg out: MutableMap<Long, Long>,
    ) {
        val start = minOf(session.startedAt, session.endedAt)
        val end = maxOf(session.startedAt, session.endedAt)
        val span = end - start
        if (span <= 0L) {
            addToAll(out, epochDay(start, zone), duration)
            return
        }
        val lastDay = epochDay(end - 1, zone)
        var day = epochDay(start, zone)
        var remaining = duration
        while (day <= lastDay) {
            val dayStart = dayStart(day, zone)
            val dayEnd = dayStart(day + 1, zone)
            val overlap = (minOf(dayEnd, end) - maxOf(dayStart, start)).coerceAtLeast(0L)
            val share = if (day == lastDay) {
                remaining
            } else {
                proportional(duration, overlap, span).coerceAtMost(remaining)
            }
            if (share > 0L) addToAll(out, day, share)
            remaining -= share
            day += 1
        }
        // 防御：理论上前面的取整不会多分，若有残差补到最后一天，保证总时长不丢。
        if (remaining > 0L) addToAll(out, lastDay, remaining)
    }

    /** 先除后乘，避免 duration * overlap 在超长会话上溢出 Long。 */
    private fun proportional(duration: Long, overlap: Long, span: Long): Long =
        (duration / span) * overlap + (duration % span) * overlap / span

    /** 一份份额写进所有桶（按日总计 + 按书按日）；顺序与数值都不影响结果。 */
    private fun addToAll(out: Array<out MutableMap<Long, Long>>, day: Long, millis: Long) {
        out.forEach { add(it, day, millis) }
    }

    private fun add(out: MutableMap<Long, Long>, day: Long, millis: Long) {
        out[day] = (out[day] ?: 0L) + millis
    }

    private fun epochDay(millis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()

    /**
     * 统计窗口：最近 [dayCount] 个自然日（含今天，旧 → 新）。
     *
     * dayCount 非正数时给空区间（[EMPTY_WINDOW]）：柱状图不给桶、「常读的书」不给行，
     * 但汇总数字照旧——与改动前的行为一致。
     */
    private fun windowRange(today: Long, dayCount: Int): LongRange =
        if (dayCount <= 0) EMPTY_WINDOW else (today - dayCount + 1)..today

    /** 空窗口：1..0 的 first 大于 last，迭代为空、in 恒 false。 */
    private val EMPTY_WINDOW: LongRange = 1L..0L

    /** 该自然日在该时区的第一毫秒；夏令时切换日由 [ZoneId] 自己纠正。 */
    private fun dayStart(day: Long, zone: ZoneId): Long =
        LocalDate.ofEpochDay(day).atStartOfDay(zone).toInstant().toEpochMilli()

    /** 书名缺省前缀；拼上 bookId 后是「未知书籍 #7」这种可辨认占位。 */
    private const val FALLBACK_TITLE_PREFIX = "未知书籍 #"
}
