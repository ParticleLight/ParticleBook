package app.particlebook.android.zlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** P1c ④：分类/榜单/作者页解析（真实夹具来自 spikes/zz-probe/p1c/，见 zlibrary-p1c-research.md §3）。 */
class ZlibCategoryParserTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("zlib/$name")!!.use {
            it.readBytes().toString(Charsets.UTF_8)
        }

    /** /popular：z-masonry 内 100 个 a[href=/book/] + z-cover，无 download 属性。 */
    @Test
    fun parsesPopularMasonryCards() {
        val books = ZlibCategoryParser.parse(fixture("popular.html"), "/popular")

        assertEquals(100, books.size)
        val first = books.first()
        assertEquals("The Love Hypothesis", first.title)
        assertEquals("Ali Hazelwood", first.author)
        assertEquals("/book/7oYX6Rbz9k/the-love-hypothesis.html", first.href)
        assertEquals("28704955", first.id)
        assertEquals("", first.downloadPath)
        assertEquals("", first.language)
        assertTrue(books.all { it.href.startsWith("/book/") && it.title.isNotEmpty() })
    }

    /** 搜索页/作者页（分类列表同构）：z-bookcard 属性带 download，与搜索解析结果一致。 */
    @Test
    fun parsesSearchLikeCategoryPageWithDownloadAttr() {
        val html = fixture("search-android.html")

        val parsedByCategory = ZlibCategoryParser.parse(html, "/s/android")
        val parsedBySearch = ZlibSearchParser.parseCards(html)

        assertEquals(parsedBySearch.size, parsedByCategory.size)
        assertEquals(50, parsedByCategory.size)
        assertTrue(parsedByCategory.all { it.downloadPath.startsWith("/dl/") })
        assertEquals(parsedBySearch.first().href, parsedByCategory.first().href)
    }

    /** 分类页搭错 URL（实测 /collections/foreignfiction）= 站点 "Page not found" → PARSE。 */
    @Test
    fun notFoundPageThrowsParse() {
        try {
            ZlibCategoryParser.parse(fixture("page-not-found.html"), "/collections/foreignfiction")
            fail("expected ZlibException")
        } catch (e: ZlibException) {
            assertEquals(ZlibException.Kind.PARSE, e.kind)
        }
    }
}
