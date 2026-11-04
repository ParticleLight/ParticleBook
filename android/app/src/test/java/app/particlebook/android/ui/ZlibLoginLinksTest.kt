package app.particlebook.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** B12：登录页「忘记密码 / 注册」链接必须落在当前线路，无线路时不渲染。 */
class ZlibLoginLinksTest {

    @Test
    fun buildsLinksOnCurrentMirror() {
        assertEquals(
            "https://z-lib.by/password-recovery",
            zlibAccountHelpUrl("https://z-lib.by", "password-recovery"),
        )
        assertEquals(
            "https://z-lib.by/registration",
            zlibAccountHelpUrl("https://z-lib.by", "registration"),
        )
    }

    @Test
    fun trailingSlashOnMirrorAndPathDoNotDoubleSlash() {
        assertEquals(
            "https://z-lib.by/password-recovery",
            zlibAccountHelpUrl("https://z-lib.by/", "/password-recovery"),
        )
        assertEquals(
            "https://z-lib.by/registration",
            zlibAccountHelpUrl("  https://z-lib.by//  ", "registration/"),
        )
    }

    @Test
    fun noCurrentMirrorHidesEntry() {
        assertNull(zlibAccountHelpUrl(null, "password-recovery"))
        assertNull(zlibAccountHelpUrl("", "password-recovery"))
        assertNull(zlibAccountHelpUrl("   ", "registration"))
    }
}
