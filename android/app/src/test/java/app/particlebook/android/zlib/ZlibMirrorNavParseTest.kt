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
    @Test
    fun detectsRealNavPageButRejectsSoft404() {
        // T24④：只有「entries + 镜像入口/官网地址」才算导航页；软 404/广告页/SPA 壳不能当成功缓存。
        assertTrue(ZlibMirrorList.isNavPage(fixture("wwwnav.html")))
        assertFalse(ZlibMirrorList.isNavPage(fixture("mirror-nav.html")))
        assertFalse(ZlibMirrorList.isNavPage(fixture("page-not-found.html")))
        assertFalse(ZlibMirrorList.isNavPage(""))
        assertFalse(ZlibMirrorList.isNavPage("<html><body>404 not found</body></html>"))
        assertFalse(
            "只有 entries 没有面板标题（裸 JSON/广告注入）不算导航页",
            ZlibMirrorList.isNavPage("<html><div id=\"entries\"><a href=\"https://evil.example/\">x</a></div></html>"),
        )
    }

    @Test
    fun navCacheTtlStaysInSixToTwelveHours() {
        val sixHours = 6L * 60L * 60L * 1_000L
        val twelveHours = 12L * 60L * 60L * 1_000L
        assertTrue("NAV_TTL_MS 应落在 6–12h 区间，实际 " + ZlibMirrorRegistry.NAV_TTL_MS, ZlibMirrorRegistry.NAV_TTL_MS in sixHours..twelveHours)
    }
}
