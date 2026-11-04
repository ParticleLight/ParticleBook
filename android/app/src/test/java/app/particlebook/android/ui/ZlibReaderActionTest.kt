package app.particlebook.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A13：详情页「在线阅读」按钮的可用性/文案（纯函数，不依赖 Compose/设备）。
 * task-77：p15 真机实测匿名也可读，按钮不再按登录态禁用；页面无入口时才在错误文案里引导登录。
 */
class ZlibReaderActionTest {

    private val ready = ZlibReaderLaunchState.Ready(
        readerUrl = "https://reader.z-lib.by/read/a/book/hash/x.html?file_access_token=t",
    )

    @Test
    fun actionIsDisabledOnlyWhileResolving() {
        // 未登录也必须可点（匿名详情页自带 limited 票据）。
        assertTrue(zlibReaderActionEnabled(ZlibReaderLaunchState.Idle))
        assertTrue(zlibReaderActionEnabled(ready))
        assertTrue(zlibReaderActionEnabled(ZlibReaderLaunchState.Error("没有在线阅读链接")))
        assertFalse(zlibReaderActionEnabled(ZlibReaderLaunchState.Loading))
    }

    @Test
    fun hintPrioritizesLaunchFailureThenResolveErrorThenBrowserExplanation() {
        val missing = ZlibReaderLaunchState.Error(ZLIB_READER_MISSING_LINK_MESSAGE)

        assertEquals(
            ZLIB_READER_NO_BROWSER_MESSAGE,
            zlibReaderActionHint(ready, ZLIB_READER_NO_BROWSER_MESSAGE),
        )
        assertEquals(ZLIB_READER_MISSING_LINK_MESSAGE, zlibReaderActionHint(missing))
        assertEquals(ZLIB_READER_BROWSER_HINT, zlibReaderActionHint(ZlibReaderLaunchState.Idle))
        // task-77：点击前只说明「用浏览器打开 + 无权限时下载」，不再出现未登录/受限式劝退。
        assertTrue(ZLIB_READER_BROWSER_HINT, ZLIB_READER_BROWSER_HINT.contains("浏览器"))
        assertFalse(ZLIB_READER_BROWSER_HINT, ZLIB_READER_BROWSER_HINT.contains("未登录"))
        assertTrue(
            "只有页面确实没有入口的错误文案才提示登录",
            zlibReaderActionHint(missing).contains("未提供在线阅读入口"),
        )
    }

    @Test
    fun openedHintReportsSuccessInsteadOfLimitedWarning() {
        assertEquals(
            ZLIB_READER_OPENED_MESSAGE,
            zlibReaderActionHint(
                state = ZlibReaderLaunchState.Idle,
                opened = true,
            ),
        )
        // 打开失败仍优先显示失败原因，不能因为「刚成功过」就压掉无浏览器提示。
        assertEquals(
            ZLIB_READER_NO_BROWSER_MESSAGE,
            zlibReaderActionHint(
                state = ZlibReaderLaunchState.Idle,
                launchError = ZLIB_READER_NO_BROWSER_MESSAGE,
                opened = true,
            ),
        )
        assertFalse(ZLIB_READER_OPENED_MESSAGE.contains(ZLIB_READER_LIMITED_MESSAGE))
    }

    @Test
    fun missingReaderLinkHasOwnMessageButKeepsLimitedCaveat() {
        assertEquals(
            ZLIB_READER_MISSING_LINK_MESSAGE,
            zlibReaderUrlFailureMessage(ZlibReaderUrl.Failure.NOT_FOUND),
        )
        assertNotEquals(ZLIB_READER_LIMITED_MESSAGE, ZLIB_READER_MISSING_LINK_MESSAGE)
        assertTrue(ZLIB_READER_MISSING_LINK_MESSAGE.contains(ZLIB_READER_LIMITED_MESSAGE))
    }

    // task-43 P2-1：只有本层仍可见且拿到 Ready 才拉起浏览器（详情层被压住时不得抢焦点）。
    @Test
    fun opensBrowserOnlyWhenLayerVisibleAndStateReady() {
        assertTrue(zlibReaderShouldOpenBrowser(layerVisible = true, state = ready))
        assertFalse(zlibReaderShouldOpenBrowser(layerVisible = false, state = ready))
        assertFalse(zlibReaderShouldOpenBrowser(layerVisible = true, state = ZlibReaderLaunchState.Idle))
        assertFalse(
            zlibReaderShouldOpenBrowser(
                layerVisible = true,
                state = ZlibReaderLaunchState.Error("x"),
            ),
        )
    }

    // task-58 P2-1：打开失败（无浏览器/系统拒绝）也必须消费 Ready，下次点击重新解析；
    // 否则旧 URL 会一直留在 state 里被重复复用，而它可能属于已失效的登录会话。
    @Test
    fun openAttemptAlwaysConsumesReadyEvenWhenBrowserMissing() {
        val success = zlibReaderOpenOutcome(browserLaunched = true)
        assertTrue(success.opened)
        assertNull(success.launchError)
        assertTrue(success.consumeReady)

        val failure = zlibReaderOpenOutcome(browserLaunched = false)
        assertFalse(failure.opened)
        assertEquals(ZLIB_READER_NO_BROWSER_MESSAGE, failure.launchError)
        assertTrue(failure.consumeReady)
    }

    @Test
    fun labelReflectsIdleResolvingAndRetryStates() {
        assertEquals("在线阅读", zlibReaderActionLabel(ZlibReaderLaunchState.Idle))
        assertEquals("在线阅读", zlibReaderActionLabel(ready))
        assertEquals("正在获取链接…", zlibReaderActionLabel(ZlibReaderLaunchState.Loading))
        assertEquals(
            "重新尝试在线阅读",
            zlibReaderActionLabel(ZlibReaderLaunchState.Error("x")),
        )
    }
}
