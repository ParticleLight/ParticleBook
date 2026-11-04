package app.particlebook.android.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * B12 修正（realnet T26）：站点已无找回密码页（全部线路软 404），登录页只保留注册外链。
 * 链接必须落在当前线路，无线路时不渲染；源码级守卫防止「忘记密码」死链被重新加回来。
 */
class ZlibLoginLinksTest {

    @Test
    fun buildsRegistrationLinkOnCurrentMirror() {
        assertEquals(
            "https://z-lib.by/registration",
            zlibAccountHelpUrl("https://z-lib.by", "registration"),
        )
    }

    @Test
    fun trailingSlashOnMirrorAndPathDoNotDoubleSlash() {
        assertEquals(
            "https://z-lib.by/registration",
            zlibAccountHelpUrl("https://z-lib.by/", "/registration"),
        )
        assertEquals(
            "https://z-lib.by/registration",
            zlibAccountHelpUrl("  https://z-lib.by//  ", "registration/"),
        )
    }

    @Test
    fun queryAndFragmentOnMirrorAreDroppedBeforeJoiningPath() {
        assertEquals(
            "https://z-lib.by/registration",
            zlibAccountHelpUrl("https://z-lib.by/?utm=1", "registration"),
        )
        assertEquals(
            "https://z-lib.by/registration",
            zlibAccountHelpUrl("https://z-lib.by#top", "registration"),
        )
        assertEquals(
            "https://z-lib.by/path/registration",
            zlibAccountHelpUrl("https://z-lib.by/path/?a=b#frag", "registration"),
        )
        // 只剩查询串的“线路”视作无效，入口隐藏而不是拼出相对链接。
        assertNull(zlibAccountHelpUrl("?x=1", "registration"))
    }

    @Test
    fun noCurrentMirrorHidesEntry() {
        assertNull(zlibAccountHelpUrl(null, "registration"))
        assertNull(zlibAccountHelpUrl("", "registration"))
        assertNull(zlibAccountHelpUrl("   ", "registration"))
    }

    @Test
    fun loginScreenDoesNotLinkToPasswordRecovery() {
        val source = loginScreenSource()
        assertFalse(
            "站点 /password-recovery 全线软 404，登录页不得再拼该路径",
            source.contains("password-recovery"),
        )
        assertFalse(
            "不得再渲染「忘记密码」按钮（死链）",
            source.contains("Text(\"忘记密码\")"),
        )
        assertTrue(
            "注册外链必须保留且落在当前线路",
            source.contains("zlibAccountHelpUrl(currentMirrorBase, \"registration\")"),
        )
    }

    private fun loginScreenSource(): String {
        val relative = "src/main/java/app/particlebook/android/ui/ZlibLoginScreen.kt"
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
            "找不到 ZlibLoginScreen.kt（工作目录=" + System.getProperty("user.dir") + "），跳过源码守卫",
            found != null,
        )
        return found!!.readText()
    }
}
