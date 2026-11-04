package app.particlebook.android.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * task-58：详情页登录态必须由 AppRoot 的账号 ViewModel（Observable）显式传入。
 * 组合期同步读 ZlibClient.isLoggedIn(prefs) 不订阅登录/退出变化，
 * 登录返回后按钮可能长期停留在旧状态（只有父层碰巧重组才刷新）。
 */
class ZlibDetailLoginStateTest {

    @Test
    fun detailScreenTakesLoggedInFromCallerInsteadOfReadingPrefs() {
        val source = uiSource("ZlibDetailScreen.kt")
        assertFalse(
            "详情页不得在组合期同步读 isLoggedIn(prefs)",
            source.contains("isLoggedIn("),
        )
        assertTrue(
            "ZlibDetailScreen 必须接收 loggedIn 参数",
            source.contains("loggedIn: Boolean"),
        )
        assertTrue(
            "下载/阅读按钮必须复用同一个 loggedIn",
            source.contains("loggedIn = loggedIn"),
        )
    }

    @Test
    fun readerStateResetsWhenAccountChanges() {
        val source = uiSource("ZlibDetailScreen.kt")
        assertTrue(
            "阅读入口状态必须在登录态/账号变化时重建，避免复用旧会话票据",
            source.contains("remember(bookHash, loggedIn, accountKey)"),
        )
        assertTrue(
            "打开失败也必须消费 Ready（不把旧 URL 留给下次点击）",
            source.contains("outcome.consumeReady"),
        )
    }

    @Test
    fun appRootPassesObservableAccountState() {
        val source = uiSource("AppRoot.kt")
        assertTrue(
            "AppRoot 必须把账号 ViewModel 的 Observable 状态传给详情页",
            source.contains("loggedIn = accountViewModel.account != null"),
        )
    }

    private fun uiSource(name: String): String {
        val relative = "src/main/java/app/particlebook/android/ui/" + name
        val candidates = ArrayList<File>()
        candidates += File(relative)
        candidates += File("app/" + relative)
        var dir: File? = File(System.getProperty("user.dir") ?: "").absoluteFile
        repeat(6) {
            val current = dir
            if (current != null) {
                candidates += File(current, "android/app/" + relative)
                candidates += File(current, "app/" + relative)
                candidates += File(current, relative)
                dir = current.parentFile
            }
        }
        val found = candidates.firstOrNull { it.isFile }
        assumeTrue(
            "找不到 " + name + "（工作目录=" + System.getProperty("user.dir") + "），跳过源码守卫",
            found != null,
        )
        return found!!.readText()
    }
}
