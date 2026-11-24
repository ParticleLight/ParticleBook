package app.particlebook.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.particlebook.android.data.LibraryRepository
import app.particlebook.android.data.ReadingStats
import app.particlebook.android.data.ReadingStatsCalculator
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 阅读统计页的状态机（消费 `LibraryRepository.readingSessionsSince` + `LibraryRepository.books` + `ReadingStatsCalculator`）。
 *
 * 与 [SearchViewModel] / [ZlibSearchViewModel] 同一套约定：
 * - 普通类，不是 androidx ViewModel——项目没有 androidx.lifecycle:viewmodel 依赖，页面用 remember 持有，
 *   协程跑在传入的 [scope] 上，离开页面随 scope 取消。
 * - [state] 用 Compose `mutableStateOf` 暴露，不引 StateFlow：页面只需要读快照。
 * - [state] 只在 [scope] 的调度器上写（先在 [ioDispatcher] 上读库，回到 scope 线程再赋值），
 *   主线程只读，避免后台线程写快照状态。
 *
 * ## 统计窗口
 * 只取最近 [LOOKBACK_DAYS] 天（`now - 60 * 24h`），因为统计页的汇总卡是「最近 60 天累计」，
 * 而图表只画 [CHART_DAYS] 天。窗口边界按毫秒算而不是按日历天算：夏令时切换会让 60 天
 * 实际是 59~61 个自然日，这个偏差对聚合结果没有意义，但把边界写成毫秒让查询条件可单测
 * （测试直接断言传给仓库的 `sinceMillis`）。
 *
 * ## 常读的书
 * 排行窗口与柱状图同一个 [CHART_DAYS]（都由 `compute(dayCount = CHART_DAYS)` 推导），所以「本周七根柱之和」
 * 与「常读的书各条之和」是同一笔数，页面上不会出现两种窗口口径。书名不在会话行里：加载时补一次
 * `books()` 组成 bookId → title 映射交给聚合器；这一步失败只退化成「未知书籍 #id」，
 * 不让整页落到 Failed（理由见 [bookTitles]）。
 *
 * ## Empty 的判定
 * 「没有任何阅读时长」就是 Empty（文案「还没有阅读记录」），而不是「查询返回空列表」：
 * 有会话但总时长为 0（例如记录器只落了 0ms 的脏行）时，界面显示 Ready 会是七根空柱，
 * 比空态更难解释。因此判定用 `totalMillis <= 0`，列表空只是它的最常见特例。
 *
 * ## 并发
 * 每次 [refresh] 取消上一个 Job 并把内部 generation 加一；结果写回前先比对 generation，
 * 于是「连点重试」时先发后至的旧请求不会覆盖新结果（与 [SearchViewModel] 的 requestId 同构）。
 * [Loading] 只在真正开始一次加载时出现——刷新期间回到 Loading 是本页的预期表现（重试要有反馈）。
 */
sealed interface StatsUiState {
    /** 首次加载或重试中：页面显示 LoadingIndicator。 */
    data object Loading : StatsUiState

    /** 有阅读记录：页面显示 hero 卡 + 7 天柱状图 + 三宫格汇总 + 常读的书（排行空则不渲染）。 */
    data class Ready(val stats: ReadingStats) : StatsUiState

    /** 统计窗口内没有任何阅读时长。 */
    data object Empty : StatsUiState

    /** 读库/聚合抛异常；message 已转成中文并保留原始异常文本便于排查。 */
    data class Failed(val message: String) : StatsUiState
}

/**
 * @param scope 页面生命周期（Composable 侧用 rememberCoroutineScope 传入）。
 * @param repositoryProvider 仓库取值器；写成函数而不是实例，是为了让页面在数据库尚未预热时
 *   也能构造 ViewModel（真正的开库成本留在 [ioDispatcher] 上）。
 * @param now 当前时刻；测试注入固定值，生产用系统时钟。
 * @param ioDispatcher 读库与聚合的执行器；仓库契约是同步的，必须离开主线程。
 */
class StatsViewModel(
    private val scope: CoroutineScope,
    private val repositoryProvider: () -> LibraryRepository,
    private val now: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    var state: StatsUiState by mutableStateOf(StatsUiState.Loading)
        private set

    private var loadJob: Job? = null
    private var generation = 0

    init {
        // 构造即加载：页面进入就是「正在统计」而不是空白，避免首帧闪一下空态。
        refresh()
    }

    /** 重新统计；失败后的「重试」入口也走这里。 */
    fun refresh() {
        loadJob?.cancel()
        generation += 1
        val token = generation
        state = StatsUiState.Loading
        loadJob = scope.launch {
            val outcome = load()
            // 迟到结果直接丢弃：state 只能由最新一次刷新写。
            if (token != generation) return@launch
            state = when (outcome) {
                is StatsOutcome.Loaded ->
                    if (outcome.stats.totalMillis <= 0L) {
                        StatsUiState.Empty
                    } else {
                        StatsUiState.Ready(outcome.stats)
                    }

                is StatsOutcome.Failed -> StatsUiState.Failed(statsErrorMessage(outcome.error))
            }
        }
    }

    private suspend fun load(): StatsOutcome = try {
        withContext(ioDispatcher) {
            // now 与 zone 在同一处取样：跨零点的瞬间不会出现「用今天的时间、按昨天的时区切日」。
            val at = now()
            val since = at - LOOKBACK_MILLIS
            val repository = repositoryProvider()
            val sessions = repository.readingSessionsSince(since)
            StatsOutcome.Loaded(
                ReadingStatsCalculator.compute(
                    sessions = sessions,
                    now = at,
                    zone = ZoneId.systemDefault(),
                    dayCount = CHART_DAYS,
                    bookTitles = bookTitles(repository),
                    topBookLimit = TOP_BOOK_LIMIT,
                ),
            )
        }
    } catch (cancellation: CancellationException) {
        // 取消不是失败：必须原样抛给协程，不能落成 Failed（否则重试会看到假错误）。
        throw cancellation
    } catch (throwable: Throwable) {
        StatsOutcome.Failed(throwable)
    }

    /**
     * bookId → 书名。统计行只带 bookId，书名要回查书架（[LibraryRepository.books]）。
     *
     * 取书名失败时退回空 map，而不是让整页 Failed：时长、柱状图、排行都已经算出来了，
     * 少一批书名只该退化成「未知书籍 #id」——一个缺书名的排行榜比一张错误页有用得多。
     * 标题为空的书按「没有书名」处理（不塞空串，聚合器会补占位）。
     * [CancellationException] 原样抛出：取消不是「书架读失败」，吞掉会让重试后的旧协程继续跑。
     */
    private fun bookTitles(repository: LibraryRepository): Map<Long, String> = try {
        repository.books().mapNotNull { book ->
            val title = book.title.trim()
            if (title.isEmpty()) null else book.id to title
        }.toMap()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        // 故意吞掉并降级：见函数注释。这里不留日志——统计页没有日志通道，异常文本也无处展示。
        emptyMap()
    }

    private sealed interface StatsOutcome {
        data class Loaded(val stats: ReadingStats) : StatsOutcome
        data class Failed(val error: Throwable) : StatsOutcome
    }

    companion object {
        /** 柱状图天数：最近 7 天（含今天，旧→新）。与 [ReadingStatsCalculator.compute] 的 dayCount 同一来源。 */
        const val CHART_DAYS: Int = 7

        /** 汇总卡的统计窗口天数；页面底部会把它写进「统计范围为最近 N 天」的说明里。 */
        const val LOOKBACK_DAYS: Int = 60

        /**
         * 「常读的书」最多几行。
         *
         * 与 [ReadingStatsCalculator.compute] 的 topBookLimit 默认值同值，这里显式传：
         * 页面文案（Top N）与数据口径都要能从同一个常量读出来。
         */
        const val TOP_BOOK_LIMIT: Int = 5

        private const val DAY_MILLIS: Long = 24L * 60L * 60L * 1000L

        /** 查询下界：`now - LOOKBACK_DAYS`（毫秒窗口，见类注释「统计窗口」）。 */
        const val LOOKBACK_MILLIS: Long = LOOKBACK_DAYS * DAY_MILLIS
    }
}

/** 异常 → 中文失败文案；保留原始 message（本机 SQLite 失败时是唯一线索）。 */
internal fun statsErrorMessage(error: Throwable): String {
    val detail = error.message?.trim().orEmpty()
    return if (detail.isEmpty()) "统计失败，请稍后重试。" else "统计失败：" + detail
}
