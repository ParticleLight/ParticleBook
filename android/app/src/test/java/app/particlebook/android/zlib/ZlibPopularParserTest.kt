package app.particlebook.android.zlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** P1.5 /popular 榜单解析（真实夹具：spikes/zz-probe/p15/popular.html）。 */
class ZlibPopularParserTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("zlib/$name")!!.use {
            it.readBytes().toString(Charsets.UTF_8)
        }

    @Test
    fun parsesHundredMasonryCards() {
        val items = ZlibPopularParser.parsePage(fixture("popular-p15.html"))

        assertEquals(100, items.size)
        val first = items.first()
        assertEquals("24126585", first.book.id)
        assertEquals("毛泽东选集(1-7卷,包括后续两卷静火版)", first.book.title)
        assertEquals("毛泽东", first.book.author)
        assertTrue("href 应剥掉 ?dsource=mostpopular，实际=" + first.book.href, !first.book.href.contains('?'))
        assertTrue(first.book.href.startsWith("/book/"))
        assertTrue(first.coverUrl.startsWith("https://s3proxy-alp2-covers.cdn-zlib.sk/covers150/"))
        assertTrue(items.all { it.book.href.startsWith("/book/") && it.book.title.isNotEmpty() })
        assertEquals("按 href 去重", items.size, items.map { it.book.href }.distinct().size)
    }

    @Test
    fun emptyPageReturnsEmptyList() {
        assertTrue(ZlibPopularParser.parsePage("<html><body>Nothing found</body></html>").isEmpty())
    }
}
