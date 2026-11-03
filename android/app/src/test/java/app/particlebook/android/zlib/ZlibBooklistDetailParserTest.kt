package app.particlebook.android.zlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** P1.5 /booklist/<id>/… 书单详情解析（真实夹具：spikes/zz-probe/p15/booklist-detail.html）。 */
class ZlibBooklistDetailParserTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("zlib/$name")!!.use {
            it.readBytes().toString(Charsets.UTF_8)
        }

    @Test
    fun parsesHeaderAndFirstBatchOfBooks() {
        val detail = ZlibBooklistDetailParser.parse(fixture("booklist-detail.html"))

        assertEquals("305827", detail.id)
        assertEquals("투자 (주식&부동산&경매&부업등)", detail.title)
        assertEquals("Casian", detail.author)
        assertEquals("/profile/15046099/2c1e17", detail.authorProfile)
        assertEquals(81, detail.booksCount)
        assertEquals(2, detail.commentsCount)
        assertEquals("date_saved", detail.sort)
        // 真实页 21 个 <z-bookcard>，其中 1 个是属性含 ${book[…]} 的隐藏模板卡 → 20 条。
        assertEquals("首屏 20 张真实 z-bookcard（模板卡已剔除）", 20, detail.books.size)
        assertTrue("21/81 → 还有更多", detail.hasMore)
        assertTrue(detail.books.all { it.href.startsWith("/book/") })
        assertTrue(detail.books.all { it.downloadPath.startsWith("/dl/") })
    }

    @Test
    fun softNotFoundThrowsParse() {
        try {
            ZlibBooklistDetailParser.parse(fixture("page-not-found.html"))
            fail("expected ZlibException(PARSE)")
        } catch (e: ZlibException) {
            assertEquals(ZlibException.Kind.PARSE, e.kind)
        }
    }
}
