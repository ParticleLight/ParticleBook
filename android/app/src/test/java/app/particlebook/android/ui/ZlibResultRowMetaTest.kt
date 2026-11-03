package app.particlebook.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 搜索行副信息与封面 URL 选择（A2/A3 纯函数，不依赖 Android framework）。
 *
 * 封面原始值来自 [app.particlebook.android.zlib.ZBook.coverUrl]（HTML 里的 data-src / src），
 * 这里只验证「什么值会被拿去下载、什么值回退首字占位」。
 */
class ZlibResultRowMetaTest {

    @Test
    fun resolveCoverUrlPassesAbsoluteCdnThrough() {
        val raw = "https://s3proxy-alp2-covers.cdn-zlib.sk/covers100/abc/def.jpg"
        assertEquals(raw, resolveZlibCoverUrl(raw, "https://z-lib.example"))
    }

    @Test
    fun resolveCoverUrlAddsHttpsToProtocolRelative() {
        assertEquals(
            "https://cdn.example.com/covers100/a.jpg",
            resolveZlibCoverUrl("//cdn.example.com/covers100/a.jpg", null),
        )
    }

    @Test
    fun resolveCoverUrlJoinsSiteRelativePathWithMirrorBase() {
        assertEquals(
            "https://mirror.example.com/img/covers100/a.jpg",
            resolveZlibCoverUrl("/img/covers100/a.jpg", "https://mirror.example.com/"),
        )
        assertNull("没有可用线路时无法拼出绝对地址", resolveZlibCoverUrl("/img/covers100/a.jpg", null))
    }

    @Test
    fun resolveCoverUrlRejectsPlaceholderBlankAndDirtyValues() {
        assertNull(resolveZlibCoverUrl("", "https://mirror.example.com"))
        assertNull(resolveZlibCoverUrl("   ", "https://mirror.example.com"))
        assertNull(resolveZlibCoverUrl("/img/cover-not-exists.png", "https://mirror.example.com"))
        assertNull(resolveZlibCoverUrl("javascript:alert(1)", "https://mirror.example.com"))
        assertNull(resolveZlibCoverUrl("relative/without/slash.jpg", "https://mirror.example.com"))
    }

    @Test
    fun secondaryMetaLineShowsRatingAndPublisher() {
        assertEquals("★4.8 · 人民文学出版社", bookSecondaryMetaLine("4.8", "人民文学出版社"))
        assertEquals("★5.0", bookSecondaryMetaLine("5", ""))
        // 站点用 0 / 空表示未评分：不显示 ★0.0。
        assertEquals("", bookSecondaryMetaLine("0", ""))
        assertEquals("", bookSecondaryMetaLine("", "  "))
        assertEquals("商务印书馆", bookSecondaryMetaLine("", "商务印书馆"))
    }

    @Test
    fun metaLineKeepsExistingFormat() {
        assertEquals("未知作者 · English · PDF · 9.96 MB", bookMetaLine("", "English", "pdf", "9.96 MB"))
        assertEquals("鲁迅 · 中文", bookMetaLine("鲁迅", "中文", "", ""))
    }
}
