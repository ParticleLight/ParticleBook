package app.particlebook.android.zlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZlibCookieHeaderTest {

    @Test
    fun android12SingleConcatenatedCookieStringIsNormalized() {
        // 真实形态（Android 12 / AOSP InMemoryCookieStore.sortByPath）：Version=1 cookie 输出为
        // 单条拼接串，值带引号并混入 $Version/$Path/$Domain 伪属性。
        val raw = "\$Version=\"1\"; remix_userkey=\"7920bd1a1073\";\$Path=\"/\";\$Domain=\".z-lib.by\"; " +
            "remix_userid=35980588; selectedSiteMode=books"
        val merged = ZlibCookieHeader.merge(raw, null)
        assertEquals(
            "remix_userkey=7920bd1a1073; remix_userid=35980588; selectedSiteMode=books",
            merged,
        )
        assertTrue(ZlibCookieHeader.parse(raw).containsKey("remix_userkey"))
    }

    @Test
    fun jvmMultiValueHeaderListIsAlsoNormalized() {
        // 防御：JDK 的 CookieManager.get() 可能是多个 header 值（拼接后以 ", " 分隔）。
        val raw = "\$Version=\"1\", remix_userkey=\"7920bd1a1073\";\$Path=\"/\";\$Domain=\".z-lib.by\", " +
            "remix_userid=35980588, selectedSiteMode=books"
        val merged = ZlibCookieHeader.merge(raw, null)
        assertEquals(
            "remix_userkey=7920bd1a1073; remix_userid=35980588; selectedSiteMode=books",
            merged,
        )
    }

    @Test
    fun manualChallengeCookiesOverrideJarEntries() {
        val merged = ZlibCookieHeader.merge(
            "remix_userkey=abc; bsrv=1",
            linkedMapOf("c_token" to "tok", "bsrv" to "2"),
        )
        assertEquals("remix_userkey=abc; bsrv=2; c_token=tok", merged)
    }

    @Test
    fun parseDropsPseudoAttributesAndQuotes() {
        val parsed = ZlibCookieHeader.parse("a=\"1\"; \$Path=/; b=2; \$Version=\"1\"; c=3")
        assertEquals(linkedMapOf("a" to "1", "b" to "2", "c" to "3"), parsed)
    }

    @Test
    fun redactMasksLongValues() {
        val redacted = ZlibCookieHeader.redact("remix_userkey=7920bd1a1073; bsrv=abc")
        assertEquals("remix_userkey=7920bd…; bsrv=abc", redacted)
        assertEquals("none", ZlibCookieHeader.redact(null))
    }

    @Test
    fun cookieManagerVersion1CookieIsRescued() {
        // 端到端复现根因：真 java.net.CookieManager 存 Version=1 cookie 后 get() 的输出，
        // 经 ZlibCookieHeader 规范化后必须仍含 remix_userkey 的 name=value（修复前只剩 $Version）。
        val manager = java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL)
        val uri = java.net.URI("https://z-lib.by/dl/AX1jDr5bOm")
        manager.put(
            uri,
            mapOf("Set-Cookie" to listOf("remix_userkey=7920bd1a1073; Version=1; Path=/; Domain=z-lib.by")),
        )
        val raw = manager.get(uri, emptyMap<String, List<String>>())["Cookie"].orEmpty().joinToString("; ")
        val merged = ZlibCookieHeader.merge(raw, mapOf("c_token" to "tok"))
        assertTrue("raw=$raw merged=$merged", merged != null && merged.contains("remix_userkey=7920bd1a1073"))
        assertTrue("raw=$raw merged=$merged", merged!!.contains("c_token=tok"))
        assertTrue("raw=$raw merged=$merged", !merged.contains("\$Path"))
        assertTrue("raw=$raw merged=$merged", !merged.contains("\$Version"))
    }

    @Test
    fun mergeWithoutCookiesReturnsNull() {
        assertNull(ZlibCookieHeader.merge(null, null))
        assertNull(ZlibCookieHeader.merge("", emptyMap()))
    }
}
