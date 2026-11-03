package app.particlebook.android.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.particlebook.android.zlib.ZAccount
import app.particlebook.android.zlib.ZLoginResult
import app.particlebook.android.zlib.ZlibClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * U5：登录成功回调是否应完成「返程」。
 *
 * [openSession] 是登录页入栈时捕获的会话号，[currentSession] 是当前会话号，[stack] 是当前导航栈，
 * [returnTo] 是入栈时记录在 login 下一层的来源路由（空串 = 主 tab）。
 * 只有「会话未换代（没有被新的登录页取代） + login 仍在栈顶 + 下层仍是原来源」时才允许弹栈；
 * 否则说明用户已经手动返回或跳到了别的页面，迟到成功只更新账号状态，绝不能把用户顶回书架/详情。
 */
internal fun canCompleteLoginReturn(
    openSession: Long,
    currentSession: Long,
    stack: List<String>,
    returnTo: String,
): Boolean = openSession == currentSession &&
    stack.lastOrNull() == "login" &&
    stack.dropLast(1).lastOrNull().orEmpty() == returnTo

/**
 * Z-Library 账号状态（P1b §4.1/§4.2）：进程内唯一实例由 ParticleBookApp 持有，
 * FindScreen 账号区 / 登录页 / 设置页共享；cookie 与账号持久化都在 zlib 网络层。
 */
class ZlibAccountViewModel(
    private val scope: CoroutineScope,
    context: Context,
) {
    private val appContext = context.applicationContext
    private val client: ZlibClient get() = ZlibClient.get(appContext)

    var account by mutableStateOf<ZAccount?>(null)
        private set

    var busy by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    init {
        refresh()
    }

    /** 从网络层缓存/prefs 重新读账号（登录、退出、回前台时调用）。 */
    fun refresh() {
        account = runCatching { client.cachedAccount(appContext) }.getOrNull()
    }

    fun clearError() {
        error = null
    }

    /** 登录：失败不关闭页面（错误文案留在 [error]），成功回调。 */
    fun login(email: String, password: String, onSuccess: () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { client.login(email, password) }
                if (result is ZLoginResult.Ok) {
                    account = result.account
                    error = null
                    onSuccess()
                } else if (result is ZLoginResult.Failed) {
                    error = ZlibUiError.loginFailed(result.kind.name, result.message)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                error = ZlibUiError.message(throwable)
            } finally {
                busy = false
            }
        }
    }

    /** 退出登录：清 cookie + prefs，完成后回调（调用方负责 Snackbar）。 */
    fun logout(onDone: () -> Unit = {}) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { client.logout() }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                error = ZlibUiError.message(throwable)
            } finally {
                account = null
                busy = false
                onDone()
            }
        }
    }
}
