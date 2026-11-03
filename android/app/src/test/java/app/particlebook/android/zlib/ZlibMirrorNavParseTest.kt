package app.particlebook.android.zlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R7-H：wwwnav 导航页新版式解析（镜像入口 -> T1 dynamic；官网地址 -> T0 official）。 */
class ZlibMirrorNavParseTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("zlib/" + name)!!.use {
            it.readBytes().toString(Charsets.UTF_8)
        }

    @Test
    fun parsesMirrorAndOfficialPanelsSeparately() {
        val result = ZlibMirrorList.parseNav(fixture("wwwnav.html"))

        assertEquals(listOf("https://zh.z-library.sk/", "https://zh.z-lib.gd/", "https://z-lib.fm/"), result.official)
        assertEquals(listOf("https://z-lib.by/", "https://zlib.ch/", "https://zlib.re/"), result.dynamic)
        assertEquals("网盘/推广页（quark x2、pages.dev）+ 镜像面板的 znew.pages.dev", 4, result.skipped)
    }

    @Test
    fun mirrorPanelNeverLeaksPromotionPages() {
        val result = ZlibMirrorList.parseNav(fixture("wwwnav.html"))
        assertFalse(result.dynamic.any { it.contains("pages.dev") || it.contains("quark") })
        assertFalse(result.official.any { it.contains("pages.dev") })
        assertTrue(result.dynamic.none { it.contains("znew") })
    }

    @Test
    fun fallsBackToLegacyParserWhenLayoutChanges() {
        val legacy = "<html><body><a href=\"https://z-lib.by/\">mirror</a></body></html>"
        val result = ZlibMirrorList.parseNav(legacy)
        assertEquals(emptyList<String>(), result.official)
        assertEquals(listOf("https://z-lib.by/"), result.dynamic)
        assertEquals(0, result.skipped)
    }
}
