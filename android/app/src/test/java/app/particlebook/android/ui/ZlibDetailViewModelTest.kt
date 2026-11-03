package app.particlebook.android.ui

import app.particlebook.android.zlib.ZBookDetail
import app.particlebook.android.zlib.ZlibException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U2（P1c 审计 §1）：详情页请求令牌。旧响应（书 A 的慢请求）不得覆盖新书 B 的页面状态。
 *
 * 用可注入的 loadDetail 假实现 + 真实 coroutine，不依赖设备/网络：
 * A 的假请求用 [suspendCoroutine] 挂起（故意不响应 Job 取消，模拟阻塞 IO 不可取消），
 * 以此证明「即使取消不生效，令牌校验也能挡住迟到响应」。
 */
class ZlibDetailViewModelTest {

    @Test
    fun slowPreviousBookResponseIsDropped() = runBlocking {
        val gateA = CompletableDeferred<ZBookDetail>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val vm = ZlibDetailViewModel(scope) { hash, _ ->
            if (hash == "hash-a") awaitIgnoringCancellation(gateA) else book("B", "hash-b")
        }

        vm.load("hash-a", null)
        assertEquals(ZlibDetailState.Loading, vm.state)

        // 用户返回后打开书 B：B 的响应先到，页面显示 B。
        vm.load("hash-b", null)
        assertEquals("B", (vm.state as ZlibDetailState.Ready).detail.title)

        // A 的慢响应迟到：令牌已过期，必须被丢弃（否则页面变 A、点下载下错书）。
        gateA.complete(book("A", "hash-a"))
        yield()
        assertEquals("B", (vm.state as ZlibDetailState.Ready).detail.title)
    }

    @Test
    fun staleFailureDoesNotReplaceNewBook() = runBlocking {
        val gateA = CompletableDeferred<ZBookDetail>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val vm = ZlibDetailViewModel(scope) { hash, _ ->
            if (hash == "hash-a") awaitIgnoringCancellation(gateA) else book("B", "hash-b")
        }

        vm.load("hash-a", null)
        vm.load("hash-b", null)
        gateA.completeExceptionally(ZlibException("boom", ZlibException.Kind.NETWORK))
        yield()

        assertTrue(vm.state is ZlibDetailState.Ready)
        assertEquals("B", (vm.state as ZlibDetailState.Ready).detail.title)
    }

    @Test
    fun httpCodeAppearsInErrorMessage() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val vm = ZlibDetailViewModel(scope) { _, _ ->
            throw ZlibException("HTTP 404 for /book/x", ZlibException.Kind.HTTP, httpCode = 404)
        }

        vm.load("hash-a", null)

        // D12：有状态码时文案精确到「服务器返回错误 N」。
        assertEquals(ZlibDetailState.Error("服务器返回错误 404"), vm.state)
    }

    @Test
    fun readyBookIsNotReloadedButRetryBypassesCache() = runBlocking {
        var calls = 0
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val vm = ZlibDetailViewModel(scope) { hash, _ ->
            calls += 1
            book("A", hash)
        }

        vm.load("hash-a", null)
        vm.load("hash-a", null)
        assertEquals(1, calls)

        vm.retry("hash-a", null)
        assertEquals(2, calls)
    }
}

private fun book(title: String, hash: String) = ZBookDetail(
    bookId = "id-" + hash,
    hash = hash,
    title = title,
    authors = emptyList(),
    language = null,
    year = null,
    format = "pdf",
    fileSize = null,
    isbn = null,
    coverUrl = null,
    detailPath = "/book/" + hash + "/x",
    downloadPath = "/dl/" + hash,
)

/**
 * 故意用 [suspendCoroutine]（非 cancellable）等 deferred：Job 被取消不会中止等待，
 * 用来验证「取消不生效时请求令牌仍能防止旧响应覆盖」。
 */
private suspend fun awaitIgnoringCancellation(deferred: CompletableDeferred<ZBookDetail>): ZBookDetail =
    suspendCoroutine { continuation ->
        deferred.invokeOnCompletion {
            continuation.resumeWith(runCatching { deferred.getCompleted() })
        }
    }
