package app.particlebook.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** T13 P3：站点缺出版社时返回的占位值不能当出版社显示在搜索结果行。 */
class ZlibResultRowPublisherTest {

    @Test
    fun placeholderPublisherIsDropped() {
        assertNull(sanitizePublisher("UNKNOWN"))
        assertNull(sanitizePublisher("unknown"))
        assertNull(sanitizePublisher("  Unknown  "))
        assertNull(sanitizePublisher("未知"))
        assertNull(sanitizePublisher("未知出版社"))
        assertNull(sanitizePublisher("N/A"))
        assertNull(sanitizePublisher("-"))
        assertNull(sanitizePublisher("   "))
        assertEquals("★4.6", bookSecondaryMetaLine("4.6", "UNKNOWN"))
    }

    @Test
    fun realPublisherIsKept() {
        assertEquals("人民文学出版社", sanitizePublisher(" 人民文学出版社 "))
        assertEquals("★4.8 · 人民文学出版社", bookSecondaryMetaLine("4.8", "人民文学出版社"))
        assertEquals("商务印书馆", bookSecondaryMetaLine("", "商务印书馆"))
    }

    @Test
    fun placeholderPublisherWithRatingKeepsOnlyRating() {
        assertEquals("★4.2", bookSecondaryMetaLine("4.2", "未知"))
        assertEquals("", bookSecondaryMetaLine("", "UNKNOWN"))
    }
}
