package app.particlebook.android.zlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** P1.5 /category/<id>/<Slug> 分类书籍列表解析（真实夹具：spikes/zz-probe/p1c/cat_10.html）。 */
class ZlibCategoryPageParserTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("zlib/$name")!!.use {
            it.readBytes().toString(Charsets.UTF_8)
        }

    @Test
    fun parsesComputersCategoryFirstPage() {
        val page = ZlibCategoryPageParser.parse(fixture("category-10.html"), 1)

        assertEquals(1, page.page)
        assertEquals(50, page.books.size)
        assertEquals(3, page.totalPages)
        val first = page.books.first()
        assertEquals("116429854", first.id)
        assertEquals("/dl/yOW4bYJb2r", first.downloadPath)
        assertEquals("pdf", first.extension)
        assertTrue(first.href.startsWith("/book/"))
    }

    @Test
    fun softNotFoundThrowsParse() {
        try {
            ZlibCategoryPageParser.parse(fixture("page-not-found.html"), 1)
            fail("expected ZlibException(PARSE)")
        } catch (e: ZlibException) {
            assertEquals(ZlibException.Kind.PARSE, e.kind)
        }
    }
}
