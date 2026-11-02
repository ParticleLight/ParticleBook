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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 详情页状态（P1b §4.3）：Loading / Ready(detail) / Error(message)。 */
sealed interface ZlibDetailState {
    data object Loading : ZlibDetailState
    data class Ready(val detail: ZBookDetail) : ZlibDetailState
    data class Error(val message: String) : ZlibDetailState
}

class ZlibDetailViewModel(
    private val scope: CoroutineScope,
    context: Context,
) {
    private val appContext = context.applicationContext
    private val client: ZlibClient get() = ZlibClient.get(appContext)

    var state by mutableStateOf<ZlibDetailState>(ZlibDetailState.Loading)
        private set

    private var loadedHash: String? = null

    fun load(hash: String, slug: String?) {
        if (loadedHash == hash && state is ZlibDetailState.Ready) return
        loadInternal(hash, slug)
    }

    fun retry(hash: String, slug: String?) {
        loadInternal(hash, slug)
    }

    private fun loadInternal(hash: String, slug: String?) {
        loadedHash = hash
        state = ZlibDetailState.Loading
        scope.launch {
            try {
                val detail = withContext(Dispatchers.IO) { client.fetchDetail(hash, slug) }
                state = ZlibDetailState.Ready(detail)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                state = ZlibDetailState.Error(ZlibUiError.message(throwable))
            }
        }
    }
}
