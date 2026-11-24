package app.particlebook.android.ui.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * task-52：评分在索引里的读写兼容性。
 *
 * 生产 [JsonLibraryIndexCodec] 依赖 org.json，纯 JVM 单测里是 not mocked，
 * 所以这里分三层覆盖：
 * 1. 解码判定 $[readBookRating] 的脏值矩阵（生产同一个纯函数，不经 org.json）；
 * 2. 写入侧 $[clampBookRating] 的夹取语义；
 * 3. 真实 JSON 文本往返（$[TestIndexCodec] 与生产同格式）——旧索引没有 rating 键、
 *    rating 写 null 必须落成 JSON null 而不是字符串 "null"。
 */
class LibraryRatingMigrationTest {

    // ---------------------------------------------------------------- 解码：脏值矩阵

    @Test
    fun missingRatingDecodesToNull() {
        // 旧索引根本没有 rating 键：解析出来是 Map 里没有该 key，调用方传 null。
        assertNull(readBookRating(null))
    }

    @Test
    fun validWholeRatingsSurvive() {
        assertEquals(1, readBookRating(1L))
        assertEquals(3, readBookRating(3L))
        assertEquals(5, readBookRating(5))
        assertEquals(4, readBookRating(4.0))
        assertEquals(2, readBookRating(2.toShort()))
        assertEquals(5, readBookRating(5.0))
    }

    @Test
    fun outOfRangeRatingsAreTreatedAsUnrated() {
        assertNull(readBookRating(0))
        assertNull(readBookRating(6))
        assertNull(readBookRating(-1))
        assertNull(readBookRating(99L))
        assertNull(readBookRating(-0.0))
    }

    @Test
    fun fractionalRatingsAreTreatedAsUnrated() {
        assertNull(readBookRating(2.5))
        assertNull(readBookRating(4.99))
        assertNull(readBookRating(1.5f))
    }

    @Test
    fun nonNumericRatingsAreTreatedAsUnrated() {
        assertNull(readBookRating("3"))
        assertNull(readBookRating("abc"))
        assertNull(readBookRating(true))
        assertNull(readBookRating(emptyList<Any>()))
    }

    // ---------------------------------------------------------------- 写入：夹取语义

    @Test
    fun clampKeepsLegalRatings() {
        assertEquals(1, clampBookRating(1))
        assertEquals(3, clampBookRating(3))
        assertEquals(5, clampBookRating(5))
    }

    @Test
    fun clampPullsOutliersIntoRange() {
        assertEquals(1, clampBookRating(0))
        assertEquals(1, clampBookRating(-7))
        assertEquals(5, clampBookRating(6))
        assertEquals(5, clampBookRating(99))
    }

    @Test
    fun clampKeepsNullAsClearedRating() {
        assertNull(clampBookRating(null))
    }

    @Test
    fun clampIsIdempotent() {
        listOf(-3, 0, 1, 3, 5, 6, 42).forEach { raw ->
            val once = clampBookRating(raw)
            assertEquals(once, clampBookRating(once))
        }
    }

    // ---------------------------------------------------------------- JSON 文本往返

    private fun encode(books: List<Book>): String = TestIndexCodec().encode(books)

    private fun decode(json: String): List<Book> = TestIndexCodec().decode(json)

    private fun book(id: Long, rating: Int?) = Book(
        id = id,
        title = "book-$id",
        author = "作者-$id",
        format = BookFormat.Epub,
        filePath = "/tmp/book-$id.epub",
        fileSize = 2048L,
        addedAt = "2026-11-24",
        rating = rating,
    )

    @Test
    fun legacyIndexWithoutRatingKeyDecodesAsUnrated() {
        val legacy = "{\"version\":1,\"books\":[{\"id\":7,\"title\":\"旧书\",\"author\":null," +
            "\"format\":\"epub\",\"filePath\":\"/tmp/old.epub\",\"fileSize\":1024," +
            "\"addedAt\":null,\"tags\":[]}]}"

        val books = decode(legacy)

        assertEquals(1, books.size)
        assertNull("旧索引缺少 rating 键必须读成未评分", books.single().rating)
    }

    @Test
    fun dirtyRatingsInIndexDoNotBreakParsing() {
        val item = fun(id: Int, title: String, rating: String) =
            "{\"id\":$id,\"title\":\"$title\",\"format\":\"epub\",\"filePath\":\"/tmp/$id.epub\"," +
                "\"fileSize\":1,\"rating\":$rating}"
        val dirty = "{\"version\":1,\"books\":[" +
            item(1, "零分", "0") + "," +
            item(2, "超界", "6") + "," +
            item(3, "小数", "2.5") + "," +
            item(4, "字符串", "\"3\"") + "," +
            item(5, "合法", "4") + "]}"

        val books = decode(dirty)

        assertEquals(5, books.size)
        assertEquals(listOf<Int?>(null, null, null, null, 4), books.map { it.rating })
    }

    @Test
    fun ratingRoundTripsThroughJson() {
        val books = listOf(book(1, 3), book(2, null), book(3, 5))

        val decoded = decode(encode(books))

        assertEquals(listOf<Int?>(3, null, 5), decoded.map { it.rating })
    }

    @Test
    fun clearedRatingIsJsonNullNotStringNull() {
        val json = encode(listOf(book(1, null)))

        assertTrue("未评分应写成 JSON null：$json", json.contains("\"rating\":null"))
        assertTrue("不能写成字符串 null：$json", !json.contains("\"rating\":\"null\""))
    }

    @Test
    fun ratingDoesNotDisturbOtherFields() {
        val original = book(11, 2)
        val restored = decode(encode(listOf(original))).single()

        assertEquals(original.id, restored.id)
        assertEquals(original.title, restored.title)
        assertEquals(original.author, restored.author)
        assertEquals(original.format, restored.format)
        assertEquals(original.filePath, restored.filePath)
        assertEquals(original.fileSize, restored.fileSize)
        assertEquals(original.addedAt, restored.addedAt)
        assertEquals(2, restored.rating)
    }

    @Test
    fun productionCodecWritesRatingSlot() {
        // 契约测试：生产 codec 的写侧必须把 rating 接上（org.json 跑不起来，只能查源码形状）。
        val candidates = listOf(
            java.io.File("src/main/java/app/particlebook/android/ui/data/LibraryStore.kt"),
            java.io.File("android/app/src/main/java/app/particlebook/android/ui/data/LibraryStore.kt"),
        )
        val source = candidates.firstOrNull { it.isFile }
        val cwd = java.io.File(".").absolutePath
        assertTrue("找不到 LibraryStore.kt，工作目录=$cwd", source != null)
        val text = source!!.readText()
        assertTrue("写侧应写入 rating 槽位", text.contains(".put(\"rating\", book.rating ?: JSONObject.NULL)"))
        assertTrue("读侧应经过 readIndexRating", text.contains("readIndexRating("))
        assertTrue("评分区间常量应存在", text.contains("MIN_BOOK_RATING") && text.contains("MAX_BOOK_RATING"))
    }
}
