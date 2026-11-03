package app.particlebook.android.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.particlebook.android.zlib.ZBookDetail
import app.particlebook.android.zlib.ZlibClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 详情页状态（P1b §4.3）：Loading / Ready(detail) / Error(message)。 */
sealed interface ZlibDetailState {
    data object Loading : ZlibDetailState
    data class Ready(val detail: ZBookDetail) : ZlibDetailState
    data class Error(val message: String) : ZlibDetailState
}

/**
 * 详情页 ViewModel。
 *
 * U2（P1c 审计 §1）：详情页是**单实例** ViewModel，route 只换 hash；网络响应可能乱序
 * （书 A 慢、用户返回后打开书 B，B 先回、A 后回）。旧实现成功回调不校验归属，A 的慢响应
 * 会把 state 覆盖成 A → 页面显示 A / 点下载实际下载 A（用户以为在 B 的页面）。
 *
 * 现在每次 load/retry：自增 requestSeq 请求令牌 + 取消上一个 Job；响应回来时校验
 * 「令牌仍是当前 + loadedHash 仍是当前书」才写 state，否则丢弃（取消不生效时也有令牌兜底）。
 *
 * [loadDetail] 可注入，JVM 单测用它构造慢/快响应验证令牌；生产环境走 secondary constructor，
 * 在 Dispatchers.IO 上调用 [ZlibClient.fetchDetail]。
 */
class ZlibDetailViewModel(
    private val scope: CoroutineScope,
    private val loadDetail: suspend (hash: String, slug: String?) -> ZBookDetail,
) {
    constructor(scope: CoroutineScope, context: Context) : this(
        scope = scope,
        loadDetail = { hash, slug ->
            withContext(Dispatchers.IO) {
                ZlibClient.get(context.applicationContext).fetchDetail(hash, slug)
            }
        },
    )

    var state by mutableStateOf<ZlibDetailState>(ZlibDetailState.Loading)
        private set

    /** 当前响应应归属的 hash；[load] 早退判断用。 */
    private var loadedHash: String? = null
    private var requestSeq: Long = 0L
    private var loadJob: Job? = null

    fun load(hash: String, slug: String?) {
        if (loadedHash == hash && state is ZlibDetailState.Ready) return
        loadInternal(hash, slug)
    }

    fun retry(hash: String, slug: String?) {
        loadInternal(hash, slug)
    }

    private fun loadInternal(hash: String, slug: String?) {
        val token = ++requestSeq
        loadedHash = hash
        // 旧请求取消不掉时（阻塞 IO / 非协作取消）下面的令牌校验仍能挡住迟到响应。
        loadJob?.cancel()
        state = ZlibDetailState.Loading
        loadJob = scope.launch {
            try {
                val detail = loadDetail(hash, slug)
                if (!isCurrent(token, hash)) return@launch
                state = ZlibDetailState.Ready(detail)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                if (!isCurrent(token, hash)) return@launch
                state = ZlibDetailState.Error(ZlibUiError.message(throwable))
            }
        }
    }

    /** 请求令牌 + hash 双校验：只有仍属于当前请求的响应才允许写 state。 */
    private fun isCurrent(token: Long, hash: String): Boolean =
        token == requestSeq && loadedHash == hash
}
