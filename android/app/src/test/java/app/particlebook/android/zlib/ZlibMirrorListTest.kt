package app.particlebook.android.zlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZlibMirrorListTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("zlib/$name")!!.use {
            it.readBytes().toString(Charsets.UTF_8)
        }

    @Test
    fun parsesFixedNavigationPage() {
        val mirrors = ZlibMirrorList.parse(fixture("mirror-nav.html"))

        // 只认 <a href> + 正文裸地址里的「站点根地址」；CDN/统计/推广页/黑名单全部剔除。
        // 注意 https://zh.dfj101.ru/?ref=nav：锚点本身因带 query 被拒，但正文裸地址规则会把它收成
        // https://zh.dfj101.ru/（与桌面端 ZLibraryMirror.cpp 的裸地址正则行为一致），故保留。
        assertEquals(
            listOf(
                "https://z-lib.by/",
                "https://zh.z-library.sk/",
                "https://zh.dfj101.ru/",
                "https://zh.zlib0.ru/",
                "https://zh.1lib.sk/",
            ),
            mirrors,
        )
    }

    @Test
    fun trustedHostRulesMatchDesktopWhitelist() {
        assertTrue(ZlibMirrorList.isTrustedHost("zh.z-lib.gd"))
        assertTrue(ZlibMirrorList.isTrustedHost("zlib.re"))
        assertTrue(ZlibMirrorList.isTrustedHost("zh.1lib.sk"))
        assertTrue(ZlibMirrorList.isTrustedHost("zh.singlelogin.rs"))
        assertFalse(ZlibMirrorList.isTrustedHost("olib.pages.dev"))
        assertFalse(ZlibMirrorList.isTrustedHost("docs.qq.com"))
        assertFalse(ZlibMirrorList.isTrustedHost("cdnjs.cloudflare.com"))
    }

    @Test
    fun blockedDomainIsNeverMerged() {
        assertTrue(ZlibMirrorList.isBlocked("https://singlelogin.re/"))
        val merged = ZlibMirrorList.merge(
            staticMirrors = listOf("https://z-lib.by/", "https://singlelogin.re/"),
            dynamic = listOf("https://zh.zlib0.ru/"),
        )
        assertEquals(listOf("https://z-lib.by/", "https://zh.zlib0.ru/"), merged)
    }

    @Test
    fun mergeKeepsStaticOrderAndDeduplicates() {
        val merged = ZlibMirrorList.merge(
            staticMirrors = listOf("https://z-lib.by", "https://zh.z-library.sk"),
            dynamic = listOf(
                "https://z-lib.by/",
                "https://new-mirror.zlibx.ru/",
                "https://zh.z-library.sk/",
            ),
        )
        assertEquals(
            listOf("https://z-lib.by/", "https://zh.z-library.sk/", "https://new-mirror.zlibx.ru/"),
            merged,
        )
    }

    @Test
    fun cacheRefreshesAfterOneHourAndOnFailureFallsBack() {
        ZlibMirrorList.resetForTest()
        try {
            val now = 1_000_000L
            assertTrue("never fetched -> refresh", ZlibMirrorList.shouldRefresh(now))

            ZlibMirrorList.markAttempt(emptyList(), now) // 失败：静默回退静态，1h 内不重试
            assertEquals(emptyList<String>(), ZlibMirrorList.cached())
            assertFalse(ZlibMirrorList.shouldRefresh(now + 60_000L))
            assertTrue(ZlibMirrorList.shouldRefresh(now + ZlibMirrorList.CACHE_TTL_MS))

            ZlibMirrorList.markAttempt(listOf("https://zh.zlib0.ru/"), now + 1_000L)
            assertEquals(listOf("https://zh.zlib0.ru/"), ZlibMirrorList.cached())
            assertFalse(ZlibMirrorList.shouldRefresh(now + 1_000L + ZlibMirrorList.CACHE_TTL_MS - 1))
        } finally {
            ZlibMirrorList.resetForTest()
        }
    }
}
