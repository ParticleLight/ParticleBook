package app.particlebook.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A13：在线阅读 WebView 状态机（纯函数，不依赖设备/WebView）。 */
class ZlibReaderStateTest {

    @Test
    fun launchFailureMessagesAreActionable() {
        assertTrue(
            zlibReaderUrlFailureMessage(ZlibReaderUrl.Failure.NOT_FOUND)
                .contains("没有在线阅读链接"),
        )
        assertTrue(
            zlibReaderUrlFailureMessage(ZlibReaderUrl.Failure.MISSING_TICKET)
                .contains("缺少访问票据"),
        )
        assertTrue(
            zlibReaderUrlFailureMessage(ZlibReaderUrl.Failure.UNTRUSTED_HOST)
                .contains("域名不受信任"),
        )
        assertTrue(
            zlibReaderUrlFailureMessage(ZlibReaderUrl.Failure.INSECURE_SCHEME)
                .contains("HTTPS"),
        )
    }

    @Test
    fun initialWebStateIsLoading() {
        val state = ZlibReaderWebState()
        assertTrue(state.loading)
        assertEquals(0, state.progress)
        assertNull(state.failedMessage)
        assertFalse(state.canGoBack)
    }

    @Test
    fun progressAndFinishDriveLoadingState() {
        var state = zlibReaderOnPageStarted(ZlibReaderWebState())
        state = zlibReaderOnProgress(state, 40)
        assertTrue(state.loading)
        assertEquals(40, state.progress)

        state = zlibReaderOnPageFinished(state, canGoBack = true)
        assertFalse(state.loading)
        assertEquals(100, state.progress)
        assertTrue(state.canGoBack)
        assertNull(state.failedMessage)
    }

    @Test
    fun mainFrameErrorSurvivesPageFinishedButNotReload() {
        var state = zlibReaderOnPageStarted(ZlibReaderWebState())
        state = zlibReaderOnMainFrameError(state, ZlibReaderWebErrorCodes.HOST_LOOKUP)
        assertFalse(state.loading)
        assertTrue(state.failedMessage!!.contains("无法解析在线阅读服务器地址"))

        // WebView 在 onReceivedError 之后通常还会回调 onPageFinished：不能把错误遮罩清掉变白屏。
        state = zlibReaderOnPageFinished(state, canGoBack = false)
        assertEquals("无法解析在线阅读服务器地址，请检查网络后重试。", state.failedMessage)

        // 重试 / 重新开始导航才清错误。
        state = zlibReaderOnRetry(state)
        assertTrue(state.loading)
        assertNull(state.failedMessage)
        state = zlibReaderOnPageStarted(state)
        assertNull(state.failedMessage)
    }

    @Test
    fun errorCodesMapToChineseMessagesWithoutEnglishDescription() {
        assertEquals("连接在线阅读服务器失败，请检查网络后重试。", zlibReaderWebErrorMessage(ZlibReaderWebErrorCodes.CONNECT))
        assertEquals("连接在线阅读服务器失败，请检查网络后重试。", zlibReaderWebErrorMessage(ZlibReaderWebErrorCodes.IO))
        assertEquals("在线阅读页面加载超时，请稍后重试。", zlibReaderWebErrorMessage(ZlibReaderWebErrorCodes.TIMEOUT))
        assertEquals("在线阅读安全连接失败，请检查网络后重试。", zlibReaderWebErrorMessage(ZlibReaderWebErrorCodes.FAILED_SSL_HANDSHAKE))
        assertEquals("在线阅读页面加载失败，请稍后重试。", zlibReaderWebErrorMessage(ZlibReaderWebErrorCodes.UNKNOWN))
        assertEquals("在线阅读页面加载失败，请稍后重试。", zlibReaderWebErrorMessage(-999))
    }

    @Test
    fun httpErrorsMapToActionableMessages() {
        assertEquals(
            "在线阅读凭证已失效，请返回详情页重新打开。",
            zlibReaderHttpErrorMessage(401),
        )
        assertEquals(
            "在线阅读凭证已失效，请返回详情页重新打开。",
            zlibReaderHttpErrorMessage(403),
        )
        assertEquals(
            "在线阅读页面不存在（可能已下架）。",
            zlibReaderHttpErrorMessage(404),
        )
        assertEquals(
            "在线阅读页面加载失败（服务器返回 500），请稍后重试。",
            zlibReaderHttpErrorMessage(500),
        )
        assertNull(zlibReaderHttpErrorMessage(200))
        assertNull(zlibReaderHttpErrorMessage(302))
    }

    @Test
    fun httpErrorUpdatesStateOnlyForErrorStatuses() {
        var state = zlibReaderOnPageStarted(ZlibReaderWebState())
        val unchanged = zlibReaderOnHttpError(state, 200)
        assertEquals(state, unchanged)

        state = zlibReaderOnHttpError(state, 403)
        assertFalse(state.loading)
        assertTrue(state.failedMessage!!.contains("凭证已失效"))
    }

    @Test
    fun unsafeSchemesAreBlockedButHttpAndHttpsAllowed() {
        assertFalse(zlibReaderShouldBlockUrl("https://reader.z-lib.by/read/x"))
        assertFalse(zlibReaderShouldBlockUrl("http://reader.z-lib.by/read/x"))
        assertFalse(zlibReaderShouldBlockUrl("/relative/path"))
        assertTrue(zlibReaderShouldBlockUrl("javascript:alert(1)"))
        assertTrue(zlibReaderShouldBlockUrl("intent://scan/#Intent;scheme=zxing;end"))
        assertTrue(zlibReaderShouldBlockUrl("file:///sdcard/secret.txt"))
        assertTrue(zlibReaderShouldBlockUrl("content://com.example/secret"))
    }

    @Test
    fun credentialExpiryNeedsFreshLinkButOtherErrorsDoNot() {
        assertTrue(zlibReaderNeedsFreshLink("在线阅读凭证已失效，请返回详情页重新打开。"))
        assertFalse(zlibReaderNeedsFreshLink("在线阅读页面加载失败，请稍后重试。"))
        assertFalse(zlibReaderNeedsFreshLink("连接在线阅读服务器失败，请检查网络后重试。"))
        assertFalse(zlibReaderNeedsFreshLink(null))
    }

    @Test
    fun historyChangedUpdatesCanGoBack() {
        val state = zlibReaderOnHistoryChanged(ZlibReaderWebState(canGoBack = false), true)
        assertTrue(state.canGoBack)
    }
}
