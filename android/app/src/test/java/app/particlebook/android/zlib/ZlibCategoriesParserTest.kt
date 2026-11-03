package app.particlebook.android.zlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** P1.5 /categories 分类总览解析（真实夹具：spikes/zz-probe/p15/categories.html，39 组 / 802 子类）。 */
class ZlibCategoriesParserTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("zlib/$name")!!.use {
            it.readBytes().toString(Charsets.UTF_8)
        }

    @Test
    fun parsesAllGroupsAndSubcategories() {
        val groups = ZlibCategoriesParser.parse(fixture("categories.html"))

        assertEquals(39, groups.size)
        assertEquals(802, groups.sumOf { it.categories.size })

        val arts = groups.first()
        assertEquals("1", arts.id)
        assertEquals("Arts", arts.name)
        assertEquals("/category/1", arts.path)
        assertTrue(arts.categories.isNotEmpty())

        val architecture = arts.categories.first { it.id == "40" }
        assertEquals("Architecture", architecture.slug)
        assertEquals("/category/40/Architecture", architecture.path)
        assertEquals("non-fiction", architecture.type)
        assertTrue("booksCount 应解析出真实值，实际=" + architecture.booksCount, architecture.booksCount > 1_000)
    }

    @Test
    fun findsComputersGroup() {
        val groups = ZlibCategoriesParser.parse(fixture("categories.html"))
        val computers = groups.first { it.id == "10" }

        assertEquals("Computers", computers.name)
        assertEquals("/category/10", computers.path)
        assertTrue(computers.categories.isNotEmpty())
        val algorithms = computers.categories.first { it.slug == "Algorithms-and-Data-Structures" }
        assertEquals("168", algorithms.id)
        assertEquals("Algorithms and Data Structures", algorithms.name)
        assertTrue(algorithms.booksCount > 0)
    }

    @Test
    fun softNotFoundThrowsParse() {
        try {
            ZlibCategoriesParser.parse(fixture("page-not-found.html"))
            fail("expected ZlibException(PARSE)")
        } catch (e: ZlibException) {
            assertEquals(ZlibException.Kind.PARSE, e.kind)
        }
    }
}
