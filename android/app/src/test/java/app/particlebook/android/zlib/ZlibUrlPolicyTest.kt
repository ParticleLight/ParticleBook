package app.particlebook.android.zlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** task-51：URL 安全策略纯函数（D5/D6/D8/D9/D10/D22）。 */
class ZlibUrlPolicyTest {

    @Test
    fun relativeLocationResolvesAgainstCurrentPath() {
        // D8：/dl/ABC 上的 download/x 必须解析成 /dl/download/x，不能字符串拼成 /dl/ABC/download/x。
        assertEquals(
            "https://z-lib.by/dl/download/x",
            ZlibUrlPolicy.resolveLocation("https://z-lib.by/dl/ABC", "download/x"),
        )
        assertEquals(
            "https://z-lib.by/dl/ABC?x=1",
            ZlibUrlPolicy.resolveLocation("https://z-lib.by/dl/ABC", "?x=1"),
        )
        assertEquals(
            "https://cdn.example.org/book.pdf",
            ZlibUrlPolicy.resolveLocation("https://z-lib.by/dl/ABC", "//cdn.example.org/book.pdf"),
        )
        assertEquals(
            "https://z-lib.by/a/book.pdf",
            ZlibUrlPolicy.resolveLocation("https://z-lib.by/dl/ABC", "../a/book.pdf"),
        )
    }

    @Test
    fun sitePathDropsForeignHost() {
        // D10：//evil.example/x 不能整串当 URL，只保留 path+query。
        assertEquals("/x?y=1", ZlibUrlPolicy.sitePathOf("https://evil.example/x?y=1"))
        assertEquals("/x?y=1", ZlibUrlPolicy.sitePathOf("//evil.example/x?y=1"))
        assertEquals("/popular", ZlibUrlPolicy.sitePathOf("/popular"))
        assertEquals("/", ZlibUrlPolicy.sitePathOf("https://evil.example"))
    }

    @Test
    fun downloadUrlWhitelistRejectsForeignAndPrivateHosts() {
        val mirror = "https://z-lib.by"
        assertTrue(ZlibUrlPolicy.isAllowedDownloadUrl("https://z-lib.by/dl/x", mirror))
        assertTrue(ZlibUrlPolicy.isAllowedDownloadUrl("https://dln1.ncdn.ec/books/x", mirror))
        assertTrue(ZlibUrlPolicy.isAllowedDownloadUrl("https://zh.z-library.sk/dl/x", mirror))
        // D5：外部主机 / 伪装域名 / userinfo 绕过 / 非标准端口一律拒绝。
        assertFalse(ZlibUrlPolicy.isAllowedDownloadUrl("https://evil.example/dl/x", mirror))
        assertFalse(ZlibUrlPolicy.isAllowedDownloadUrl("https://zlib.evil.com/dl/x", mirror))
        assertFalse(ZlibUrlPolicy.isAllowedDownloadUrl("https://z-lib.by@evil.example/dl/x", mirror))
        assertFalse(ZlibUrlPolicy.isAllowedDownloadUrl("https://z-lib.by:8443/dl/x", mirror))
        assertFalse(ZlibUrlPolicy.isAllowedDownloadUrl("ftp://z-lib.by/dl/x", mirror))
        // 私网/loopback：只有「当前镜像本身就是它」（debug mock）才放行。
        assertFalse(ZlibUrlPolicy.isAllowedDownloadUrl("http://127.0.0.1:8899/dl/x", mirror))
        assertTrue(
            ZlibUrlPolicy.isAllowedDownloadUrl(
                "http://127.0.0.1:8899/dl/x",
                "http://127.0.0.1:8899",
            ),
        )
        assertFalse(ZlibUrlPolicy.isAllowedDownloadUrl("http://10.0.0.5/x", "https://z-lib.by"))
    }

    @Test
    fun signedUrlDetectionUsesQueryKeys() {
        // D9：token/sig/X-Amz-* 等强签名参数，filename+md5 组合才算签名。
        assertTrue(ZlibUrlPolicy.isSignedDownloadUrl("https://cdn.example/f?token=abc"))
        assertTrue(ZlibUrlPolicy.isSignedDownloadUrl("https://cdn.example/f?sig=abc"))
        assertTrue(ZlibUrlPolicy.isSignedDownloadUrl("https://cdn.example/f?X-Amz-Signature=abc"))
        assertTrue(ZlibUrlPolicy.isSignedDownloadUrl("https://cdn.example/f?expires=123"))
        assertTrue(ZlibUrlPolicy.isSignedDownloadUrl("https://cdn.example/f?filename=a.epub&md5=abc"))
        assertFalse(ZlibUrlPolicy.isSignedDownloadUrl("https://cdn.example/f?filename=a.epub"))
        assertFalse(ZlibUrlPolicy.isSignedDownloadUrl("https://cdn.example/f?page=2"))
        assertFalse(ZlibUrlPolicy.isSignedDownloadUrl(null))
    }

    @Test
    fun percentDecodeKeepsPlusAndDecodesUtf8() {
        assertEquals("a+b c", ZlibUrlPolicy.percentDecode("a+b%20c"))
        assertEquals("中文", ZlibUrlPolicy.percentDecode("%E4%B8%AD%E6%96%87"))
        assertEquals("cafe", ZlibUrlPolicy.percentDecode("cafe"))
        assertEquals("%zz", ZlibUrlPolicy.percentDecode("%zz"))
        assertEquals("caf\u00e9.pdf", ZlibUrlPolicy.percentDecode("caf%E9.pdf", "ISO-8859-1"))
    }

    @Test
    fun ipLiteralAndPrivateDetection() {
        assertTrue(ZlibUrlPolicy.isIpLiteral("127.0.0.1"))
        assertTrue(ZlibUrlPolicy.isIpLiteral("::1"))
        assertFalse(ZlibUrlPolicy.isIpLiteral("z-lib.by"))
        assertTrue(ZlibUrlPolicy.isLoopbackOrPrivate("10.1.2.3"))
        assertTrue(ZlibUrlPolicy.isLoopbackOrPrivate("192.168.1.1"))
        assertTrue(ZlibUrlPolicy.isLoopbackOrPrivate("localhost"))
        assertFalse(ZlibUrlPolicy.isLoopbackOrPrivate("8.8.8.8"))
    }

    @Test
    fun queryParamIsReadRaw() {
        assertEquals("Love%20(Stendhal).epub", ZlibUrlPolicy.rawQueryParam("https://cdn/f?a=1&filename=Love%20(Stendhal).epub", "filename"))
        assertEquals("", ZlibUrlPolicy.rawQueryParam("https://cdn/f?filename", "filename"))
        assertNull(ZlibUrlPolicy.rawQueryParam("https://cdn/f?a=1", "filename"))
    }
}
