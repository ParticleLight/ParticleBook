package app.particlebook.android.ui

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.particlebook.android.reader.tts.TtsSleepTimer
import kotlinx.coroutines.delay

/** 当前阅读会话的定时状态；不落盘，进程结束后不会单独恢复一个没有朗读的定时器。 */
@Stable
internal class ReaderSleepTimerState(nowMillis: () -> Long) {
    private val timer = TtsSleepTimer(nowMillis)

    var selectedMinutes: Int? by mutableStateOf(null)
        private set
    var remainingMillis: Long? by mutableStateOf(null)
        private set
    var sheetOpen by mutableStateOf(false)
        private set
    internal var revision by mutableIntStateOf(0)
        private set

    val label: String?
        get() = remainingMillis?.let { TtsSleepTimer.formatRemaining(it) }

    fun open() { sheetOpen = true }
    fun dismiss() { sheetOpen = false }

    fun select(minutes: Int?) {
        // 先校验/重置模型，再发布 UI，非法输入不会破坏仍在进行的计划。
        if (minutes == null) timer.cancel() else timer.start(minutes)
        selectedMinutes = timer.durationMinutes
        remainingMillis = timer.remainingMillis()
        sheetOpen = false
        revision += 1
    }

    fun cancel() {
        if (selectedMinutes == null && !sheetOpen) return
        select(null)
    }

    /** 先消费到期事件再通知宿主，避免停止朗读引发重组后再次触发。 */
    fun refresh(): Boolean {
        val expired = timer.pollExpired()
        selectedMinutes = timer.durationMinutes
        remainingMillis = timer.remainingMillis()
        if (expired) sheetOpen = false
        return expired
    }
}

@Composable
internal fun rememberReaderSleepTimer(readerKey: String): ReaderSleepTimerState =
    remember(readerKey) { ReaderSleepTimerState(SystemClock::elapsedRealtime) }

/** 用截止时刻重算，不把每次 delay 当作一秒；回到前台时也立即复核。 */
@Composable
internal fun ReaderSleepTimerEffect(state: ReaderSleepTimerState, onExpired: () -> Unit) {
    val latestOnExpired by rememberUpdatedState(onExpired)
    val checkExpired = {
        val expired = state.refresh()
        if (expired) latestOnExpired()
        expired
    }
    LaunchedEffect(state, state.revision) {
        while (state.remainingMillis != null) {
            if (checkExpired()) break
            val remaining = state.remainingMillis ?: break
            delay(remaining.coerceIn(1L, TickMillis))
        }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, state) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) checkExpired()
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            state.cancel()
        }
    }
}

@Composable
internal fun ReaderSleepTimerSheet(state: ReaderSleepTimerState) {
    if (state.sheetOpen) {
        TtsSleepTimerSheet(
            selectedMinutes = state.selectedMinutes,
            remainingMillis = state.remainingMillis,
            onSelect = state::select,
            onDismiss = state::dismiss,
        )
    }
}

private const val TickMillis = 1_000L
