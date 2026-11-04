package app.particlebook.android.ui

import app.particlebook.android.zlib.ZlibException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A13：在线阅读按钮的请求令牌（与详情页 U2 同款）。
 * 慢请求/迟到失败不得覆盖当前书的阅读状态（否则会打开上一本书的 reader URL）。
 */
class ZlibReaderViewModelTest {

    @Test
    fun initialStateIsIdleUntilRequested() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val vm = ZlibReaderViewModel(scope) { _, _ -> ready("A") }

        assertEquals(ZlibReaderLaunchState.Idle, vm.state)
    }

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
        assertEquals(readyUrl("B"), (vm.state as ZlibReaderLaunchState.Ready).readerUrl)

        gateA.complete(ready("A"))
        yield()
        assertEquals(readyUrl("B"), (vm.state as ZlibReaderLaunchState.Ready).readerUrl)
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
        assertEquals(readyUrl("B"), (vm.state as ZlibReaderLaunchState.Ready).readerUrl)
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

        assertEquals(
            ZlibReaderLaunchState.Error(
                message = "服务器返回错误 404",
                failure = ZlibReaderFailure.OTHER,
            ),
            vm.state,
        )
    }

    @Test
    fun readyBookIsNotReloadedWhileReady() = runBlocking {
        var calls = 0
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val vm = ZlibReaderViewModel(scope) { _, _ ->
            calls += 1
            ready("A")
        }

        vm.load("hash-a", null)
        vm.load("hash-a", null)

        assertEquals(1, calls)
        assertTrue(vm.state is ZlibReaderLaunchState.Ready)
    }

    // task-43 P2-1：点击路径在调用方协程里直接拿 URL（不再靠 LaunchedEffect 自动打开）。
    @Test
    fun loadNowReturnsUrlForClickPathAndWritesState() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val vm = ZlibReaderViewModel(scope) { _, _ -> ready("A") }

        val result = vm.loadNow("hash-a", null)

        assertEquals(readyUrl("A"), (result as ZlibReaderLaunchState.Ready).readerUrl)
        assertEquals(result, vm.state)
    }

    @Test
    fun loadNowFailureBecomesErrorState() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val vm = ZlibReaderViewModel(scope) { _, _ ->
            throw ZlibException("HTTP 404 for /book/x", ZlibException.Kind.HTTP, httpCode = 404)
        }

        val result = vm.loadNow("hash-a", null)

        assertEquals(
            ZlibReaderLaunchState.Error(
                message = "服务器返回错误 404",
                failure = ZlibReaderFailure.OTHER,
            ),
            result,
        )
        assertEquals(result, vm.state)
    }

    @Test
    fun loadNowDropsResultIfAnotherRequestStarted() = runBlocking {
        val gateA = CompletableDeferred<ZlibReaderLaunchState>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val vm = ZlibReaderViewModel(scope) { hash, _ ->
            if (hash == "hash-a") awaitIgnoringCancellation(gateA) else ready("B")
        }
        var resultA: ZlibReaderLaunchState? = ZlibReaderLaunchState.Idle

        val jobA = launch(Dispatchers.Unconfined) { resultA = vm.loadNow("hash-a", null) }
        vm.load("hash-b", null)
        assertEquals(readyUrl("B"), (vm.state as ZlibReaderLaunchState.Ready).readerUrl)

        gateA.complete(ready("A"))
        jobA.join()

        assertNull(resultA)
        assertEquals(readyUrl("B"), (vm.state as ZlibReaderLaunchState.Ready).readerUrl)
    }

    // task-43 P2-2：URL 交给浏览器后必须消费，下次点击重新解析，不复用过期/旧账号票据。
    @Test
    fun consumeReadyForcesFreshResolveOnNextClick() = runBlocking {
        var calls = 0
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val vm = ZlibReaderViewModel(scope) { _, _ ->
            calls += 1
            ready("A")
        }

        vm.load("hash-a", null)
        vm.load("hash-a", null)
        assertEquals(1, calls)

        vm.consumeReady()
        assertEquals(ZlibReaderLaunchState.Idle, vm.state)

        vm.load("hash-a", null)
        assertEquals(2, calls)
    }

    @Test
    fun consumeReadyDoesNotResetErrorState() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val vm = ZlibReaderViewModel(scope) { _, _ ->
            ZlibReaderLaunchState.Error(ZLIB_READER_MISSING_LINK_MESSAGE)
        }

        vm.load("hash-a", null)
        val error = vm.state
        vm.consumeReady()

        assertEquals(error, vm.state)
    }

    private fun readyUrl(tag: String): String =
        "https://reader.z-lib.by/read/" + tag + "/book/hash/x.html?file_access_token=t"

    private fun ready(tag: String): ZlibReaderLaunchState.Ready =
        ZlibReaderLaunchState.Ready(readerUrl = readyUrl(tag))

    /** 故意用不可取消的挂起，模拟阻塞 IO 无法被 Job.cancel 中断。 */
    private suspend fun awaitIgnoringCancellation(
        deferred: CompletableDeferred<ZlibReaderLaunchState>,
    ): ZlibReaderLaunchState = suspendCoroutine { continuation ->
        deferred.invokeOnCompletion {
            continuation.resumeWith(runCatching { deferred.getCompleted() })
        }
    }
}
