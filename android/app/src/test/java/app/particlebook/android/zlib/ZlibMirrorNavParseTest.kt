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
        // T24④/T31：entries 是硬前提；标题可选。软 404/广告页/SPA 壳不能当成功缓存。
        assertTrue(ZlibMirrorList.isNavPage(fixture("wwwnav.html")))
        assertFalse(ZlibMirrorList.isNavPage(fixture("mirror-nav.html")))
        assertFalse(ZlibMirrorList.isNavPage(fixture("page-not-found.html")))
        assertFalse(ZlibMirrorList.isNavPage(""))
        assertFalse(ZlibMirrorList.isNavPage("<html><body>404 not found</body></html>"))
        assertFalse(
            "entries 里没有白名单内的有效镜像（广告/第三方链接）不算导航页",
            ZlibMirrorList.isNavPage("<html><div id=\"entries\"><a href=\"https://evil.example/\">x</a></div></html>"),
        )
    }

    @Test
    fun entriesWithoutPanelTitlesStillCountsAsNavPage() {
        // T31（realnet task-26）：真实 wwwnav 面板标题解析为空，但 entries 里链接可解析 → 必须判活。
        assertTrue(
            ZlibMirrorList.isNavPage(
                "<html><body><section id=\"entries\"><a class=\"btn\" href=\"https://z-lib.by/\">z-lib.by</a>" +
                    "</section></body></html>",
            ),
        )
        assertTrue(
            "单引号 id 也应识别",
            ZlibMirrorList.isNavPage(
                "<html><section id='entries'><a href=\"https://zlib.re/\">zlib.re</a></section></html>",
            ),
        )
    }

    @Test
    fun entriesWithoutValidMirrorOrWithoutEntriesIsNotNavPage() {
        assertFalse(
            "entries 存在但没有有效镜像（只有广告/云盘链接）→ 不判活",
            ZlibMirrorList.isNavPage(
                "<html><section id=\"entries\"><a href=\"https://pan.quark.cn/s/x\">quark</a></section></html>",
            ),
        )
        assertFalse(
            "镜像链接在 entries 区块之外不算",
            ZlibMirrorList.isNavPage(
                "<html><body><section id=\"entries\"><p>empty</p></section>" +
                    "<a href=\"https://z-lib.by/\">footer</a></body></html>",
            ),
        )
        assertFalse(
            "没有 entries id → 不判活（即使页面有镜像链接）",
            ZlibMirrorList.isNavPage("<html><body><a href=\"https://z-lib.by/\">z-lib.by</a></body></html>"),
        )
    }


    @Test
    fun navCacheTtlStaysInSixToTwelveHours() {
        val sixHours = 6L * 60L * 60L * 1_000L
        val twelveHours = 12L * 60L * 60L * 1_000L
        assertTrue("NAV_TTL_MS 应落在 6–12h 区间，实际 " + ZlibMirrorRegistry.NAV_TTL_MS, ZlibMirrorRegistry.NAV_TTL_MS in sixHours..twelveHours)
    }
}
