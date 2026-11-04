package app.particlebook.android.ui

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
 * A13：在线阅读页请求令牌（与详情页 U2 同款）。
 * 慢请求/迟到失败不得覆盖当前书的阅读状态（否则会打开上一本书的 reader URL）。
 */
class ZlibReaderViewModelTest {

    @Test
    fun slowPreviousBookResponseIsDropped() = runBlocking {
        val gateA = CompletableDeferred<ZlibReaderLaunchState>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val vm = ZlibReaderViewModel(scope) { hash, _ ->
            if (hash == "hash-a") awaitIgnoringCancellation(gateA) else ready("B")
        }

        vm.load("hash-a", null)
        assertEquals(ZlibReaderLaunchState.Loading, vm.state)

        vm.load("hash-b", null)
        assertEquals("B", (vm.state as ZlibReaderLaunchState.Ready).title)

        gateA.complete(ready("A"))
        yield()
        assertEquals("B", (vm.state as ZlibReaderLaunchState.Ready).title)
    }

    @Test
    fun staleFailureDoesNotReplaceNewBook() = runBlocking {
        val gateA = CompletableDeferred<ZlibReaderLaunchState>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val vm = ZlibReaderViewModel(scope) { hash, _ ->
            if (hash == "hash-a") awaitIgnoringCancellation(gateA) else ready("B")
        }

        vm.load("hash-a", null)
        vm.load("hash-b", null)
        gateA.completeExceptionally(ZlibException("boom", ZlibException.Kind.NETWORK))
        yield()

        assertTrue(vm.state is ZlibReaderLaunchState.Ready)
        assertEquals("B", (vm.state as ZlibReaderLaunchState.Ready).title)
    }

    @Test
    fun missingReaderLinkShowsErrorState() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val vm = ZlibReaderViewModel(scope) { _, _ ->
            ZlibReaderLaunchState.Error(ZLIB_READER_MISSING_LINK_MESSAGE)
        }

        vm.load("hash-a", null)

        assertEquals(ZlibReaderLaunchState.Error(ZLIB_READER_MISSING_LINK_MESSAGE), vm.state)
    }

    @Test
    fun unexpectedNetworkErrorUsesSharedChineseMessage() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val vm = ZlibReaderViewModel(scope) { _, _ ->
            throw ZlibException("HTTP 404 for /book/x", ZlibException.Kind.HTTP, httpCode = 404)
        }

        vm.load("hash-a", null)

        assertEquals(ZlibReaderLaunchState.Error("服务器返回错误 404"), vm.state)
    }

    @Test
    fun readyBookIsNotReloadedButRetryBypassesCache() = runBlocking {
        var calls = 0
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val vm = ZlibReaderViewModel(scope) { _, _ ->
            calls += 1
            ready("A")
        }

        vm.load("hash-a", null)
        vm.load("hash-a", null)
        assertEquals(1, calls)

        vm.retry("hash-a", null)
        assertEquals(2, calls)
    }

    private fun ready(title: String): ZlibReaderLaunchState.Ready =
        ZlibReaderLaunchState.Ready(
            readerUrl = "https://reader.z-lib.by/read/a/book/hash/x.html?file_access_token=t",
            title = title,
        )

    /** 故意用不可取消的挂起，模拟阻塞 IO 无法被 Job.cancel 中断。 */
    private suspend fun awaitIgnoringCancellation(
        deferred: CompletableDeferred<ZlibReaderLaunchState>,
    ): ZlibReaderLaunchState = suspendCoroutine { continuation ->
        deferred.invokeOnCompletion {
            continuation.resumeWith(runCatching { deferred.getCompleted() })
        }
    }
}
