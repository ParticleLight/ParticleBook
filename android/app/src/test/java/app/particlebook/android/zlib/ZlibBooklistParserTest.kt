package app.particlebook.android.zlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** P1.5 /booklists 书单列表解析（真实夹具：spikes/zz-probe/p15/booklists.html）。 */
class ZlibBooklistParserTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("zlib/$name")!!.use {
            it.readBytes().toString(Charsets.UTF_8)
        }

    @Test
    fun parsesFirstPageAndSkipsHiddenTemplateRow() {
        // 真实页 14 个 <z-booklist>，其中 1 个 id="${booklist.id}" 的 JS 模板行 → 13 条真实书单。
        val page = ZlibBooklistParser.parsePage(fixture("booklists.html"))

        assertEquals(13, page.booklists.size)
        assertFalse("第 1 页 page-load-more 是 display:none（当前确实没有更多）", page.hasLoadMore)

        val first = page.booklists.first()
        assertEquals("305827", first.id)
        assertTrue(first.href.startsWith("/booklist/305827/"))
        assertEquals("투자 (주식&부동산&경매&부업등)", first.topic)
        assertEquals("Casian", first.author)
        assertEquals("/profile/15046099/2c1e17", first.authorProfile)
        assertEquals(81, first.quantity)
        assertEquals("10K", first.views)
        assertEquals(2, first.comments)
        assertTrue("notlogged 裸属性", first.notLogged)
        assertTrue("short_description 裸属性", first.shortDescription)
        assertTrue("editorschoice 裸属性", first.editorsChoice)
        assertEquals("列表只取前 3 张封面", 3, first.coverUrls.size)
        assertTrue(first.coverUrls.all { it.startsWith("https://") })
    }

    @Test
    fun parsesMultiLineDescription() {
        val third = ZlibBooklistParser.parsePage(fixture("booklists.html")).booklists.first { it.id == "1317965" }
        assertTrue("多行描述应保留换行占位，实际=" + third.description, third.description.contains("LONGLISTED"))
        assertTrue(third.description.contains("WINNER"))
    }

    @Test
    fun parsesSecondPage() {
        // 真实页 11 个 <z-booklist>，最后 1 个是 id="${booklist.id}" 的模板行 → 10 条。
        val page = ZlibBooklistParser.parsePage(fixture("booklists-page2.html"))
        assertEquals(10, page.booklists.size)
        assertEquals("1122976", page.booklists.first().id)
        assertEquals("2023", page.booklists.first().topic)
    }

    @Test
    fun softNotFoundThrowsParse() {
        try {
            ZlibBooklistParser.parsePage(fixture("page-not-found.html"))
            fail("expected ZlibException(PARSE)")
        } catch (e: ZlibException) {
            assertEquals(ZlibException.Kind.PARSE, e.kind)
        }
    }
}
