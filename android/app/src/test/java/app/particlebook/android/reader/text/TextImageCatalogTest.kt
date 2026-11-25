package app.particlebook.android.reader.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TextImageCatalog] 的纯 JVM 单测。
 *
 * 断言口径以当前实现为准：
 * - [TextImageCatalog.images] 按段落顺序收集，跳过纯文字段，**不去重**；
 * - [TextImageCatalog.indexOfParagraph] 是「该段之前有多少个插图段」，越界 / 无图给 null；
 * - [TextImageCatalog.indexOfKey] 只认 [TextImage.key]，给第一个匹配。
 *
 * [TextImage] 是带 [ByteArray] 的 data class，字节数组按引用比较，所以图片之间一律比 key 或
 * 用 assertSame 认实例，不用 assertEquals 比结构 —— 否则断言本身就在赌 equals 的语义。
 */
internal class TextImageCatalogTest {

    private fun text(body: String): TextParagraph = TextParagraph(body)

    private fun picture(key: String): TextParagraph =
        TextParagraph(text = "", image = TextImage(key = key, alt = key))

    /** 段落 + 按段落文本累加的 charOffsets，尽量贴近 [TextDocumentParser] 的产物形状。 */
    private fun documentOf(vararg paragraphs: TextParagraph): TextDocument {
        val offsets = IntArray(paragraphs.size)
        var cursor = 0
        paragraphs.forEachIndexed { index, paragraph ->
            offsets[index] = cursor
            cursor += paragraph.text.length
        }
        val document = TextDocument(paragraphs.toList(), offsets)
        document.bindCharCount(cursor)
        return document
    }

    private fun keys(document: TextDocument): List<String> =
        TextImageCatalog.images(document).map { it.key }

    // 1
    @Test
    fun emptyDocumentHasNoImages() {
        val document = TextDocumentParser.parse("", markdown = false)
        assertTrue(document.isEmpty)
        assertEquals(emptyList<String>(), keys(document))
        assertNull(TextImageCatalog.indexOfParagraph(document, 0))
        assertNull(TextImageCatalog.indexOfParagraph(document, -1))
        assertNull(TextImageCatalog.indexOfKey(document, "img:1"))
        assertNull(TextImageCatalog.indexOfKey(document, ""))
    }

    // 2
    @Test
    fun documentWithoutImageParagraphsYieldsEmptyCatalog() {
        val document = documentOf(text("甲"), text("乙"), text("丙"))
        assertEquals(0, TextImageCatalog.images(document).size)
        for (paragraphIndex in -1..document.paragraphs.size) {
            assertNull(
                "纯文字段不应有图片下标：paragraphIndex=$paragraphIndex",
                TextImageCatalog.indexOfParagraph(document, paragraphIndex),
            )
        }
        assertNull(TextImageCatalog.indexOfKey(document, "missing"))
    }

    // 3
    @Test
    fun singleImageParagraphMapsToIndexZero() {
        val document = documentOf(picture("img:a"))
        val images = TextImageCatalog.images(document)
        assertEquals(1, images.size)
        assertEquals("img:a", images[0].key)
        assertEquals(0, TextImageCatalog.indexOfParagraph(document, 0))
        assertEquals(0, TextImageCatalog.indexOfKey(document, "img:a"))
    }

    // 4
    @Test
    fun imagesFollowParagraphOrderAndSkipTextParagraphs() {
        val document = documentOf(
            text("标题"),
            picture("img:1"),
            text("说明一"),
            picture("img:2"),
            picture("img:3"),
            text("说明二"),
            picture("img:4"),
        )
        assertEquals(listOf("img:1", "img:2", "img:3", "img:4"), keys(document))
        // 下标 = 该段之前的插图段数量：段 1 → 0，段 3 → 1，段 4 → 2，段 6 → 3。
        assertEquals(0, TextImageCatalog.indexOfParagraph(document, 1))
        assertEquals(1, TextImageCatalog.indexOfParagraph(document, 3))
        assertEquals(2, TextImageCatalog.indexOfParagraph(document, 4))
        assertEquals(3, TextImageCatalog.indexOfParagraph(document, 6))
    }

    // 5
    @Test
    fun repeatedImageIsNotDeduplicated() {
        val repeated = TextImage(key = "img:same")
        val document = documentOf(
            TextParagraph(text = "", image = repeated),
            text("中间一段文字"),
            TextParagraph(text = "", image = repeated),
        )
        val images = TextImageCatalog.images(document)
        // 同一实例出现两次就是两张：查看器「第 n / 共 m 张」按出现次数算。
        assertEquals(2, images.size)
        assertSame(repeated, images[0])
        assertSame(repeated, images[1])
        assertEquals(0, TextImageCatalog.indexOfParagraph(document, 0))
        assertEquals(1, TextImageCatalog.indexOfParagraph(document, 2))
    }

    // 6
    @Test
    fun sameKeyInDifferentParagraphsGetsDistinctIndexes() {
        // key 相同但不同实例（HTML 里同一文件被引用两次）：仍然各自占位，各自可点。
        val document = documentOf(
            picture("file:/book/pic/01.png"),
            text("正文"),
            picture("file:/book/pic/01.png"),
            picture("file:/book/pic/02.png"),
        )
        val images = TextImageCatalog.images(document)
        assertEquals(listOf("file:/book/pic/01.png", "file:/book/pic/01.png", "file:/book/pic/02.png"), keys(document))
        assertEquals(3, images.size)
        assertEquals(0, TextImageCatalog.indexOfParagraph(document, 0))
        assertEquals(1, TextImageCatalog.indexOfParagraph(document, 2))
        assertEquals(2, TextImageCatalog.indexOfParagraph(document, 3))
        // 按 key 查只给一个答案：最早出现的那个。
        assertEquals(0, TextImageCatalog.indexOfKey(document, "file:/book/pic/01.png"))
        assertEquals(2, TextImageCatalog.indexOfKey(document, "file:/book/pic/02.png"))
    }

    // 7
    @Test
    fun consecutiveImageParagraphsGetConsecutiveIndexes() {
        val document = documentOf(
            picture("img:1"),
            picture("img:2"),
            picture("img:3"),
        )
        assertEquals(0, TextImageCatalog.indexOfParagraph(document, 0))
        assertEquals(1, TextImageCatalog.indexOfParagraph(document, 1))
        assertEquals(2, TextImageCatalog.indexOfParagraph(document, 2))
        assertEquals(3, TextImageCatalog.images(document).size)
    }

    // 8
    @Test
    fun textParagraphInsideImageRunHasNoImageIndex() {
        val document = documentOf(picture("img:1"), text("图注"), picture("img:2"))
        assertNull(TextImageCatalog.indexOfParagraph(document, 1))
        assertEquals(0, TextImageCatalog.indexOfParagraph(document, 0))
        assertEquals(1, TextImageCatalog.indexOfParagraph(document, 2))
    }

    // 9
    @Test
    fun outOfRangeParagraphIndexIsNull() {
        val document = documentOf(text("甲"), picture("img:1"))
        assertNull(TextImageCatalog.indexOfParagraph(document, -1))
        assertNull(TextImageCatalog.indexOfParagraph(document, -100))
        assertNull(TextImageCatalog.indexOfParagraph(document, Int.MIN_VALUE))
        assertNull(TextImageCatalog.indexOfParagraph(document, document.paragraphs.size))
        assertNull(TextImageCatalog.indexOfParagraph(document, document.paragraphs.size + 50))
        assertNull(TextImageCatalog.indexOfParagraph(document, Int.MAX_VALUE))
    }

    // 10
    @Test
    fun paragraphIndexEqualsNumberOfEarlierImageParagraphs() {
        val document = documentOf(
            text("序"),
            picture("img:1"),
            text("甲"),
            text("乙"),
            picture("img:2"),
            picture("img:3"),
            text("丙"),
            picture("img:4"),
            text("后记"),
        )
        // 用独立计数验算，而不是信实现里的累加。
        for (paragraphIndex in document.paragraphs.indices) {
            val expected = (0 until paragraphIndex).count { document.paragraphs[it].image != null }
            val hasImage = document.paragraphs[paragraphIndex].image != null
            if (!hasImage) {
                assertNull(
                    "段 $paragraphIndex 不是插图段，应为 null",
                    TextImageCatalog.indexOfParagraph(document, paragraphIndex),
                )
            } else {
                assertEquals(
                    "段 $paragraphIndex 的图片下标应等于其前的插图段数",
                    expected,
                    TextImageCatalog.indexOfParagraph(document, paragraphIndex),
                )
            }
        }
        assertEquals(4, TextImageCatalog.images(document).size)
    }

    // 11
    @Test
    fun indexOfKeyFindsFirstOccurrenceOnly() {
        val document = documentOf(picture("img:1"), picture("img:2"), picture("img:1"), picture("img:3"))
        assertEquals(0, TextImageCatalog.indexOfKey(document, "img:1"))
        assertEquals(1, TextImageCatalog.indexOfKey(document, "img:2"))
        assertEquals(3, TextImageCatalog.indexOfKey(document, "img:3"))
        // 第二张 "img:1" 在 2 号位，但查 key 只给首次出现的 0。
        assertEquals(2, TextImageCatalog.indexOfParagraph(document, 2))
        assertEquals("img:1", TextImageCatalog.images(document)[2].key)
    }

    // 12
    @Test
    fun indexOfKeyMatchesKeyNotPathNorAlt() {
        val fromFile = TextImage.file("/book/pic/01.png", alt = "封面")
        assertNotNull(fromFile)
        val document = documentOf(TextParagraph(text = "", image = fromFile))
        // TextImage.file 的 key 是 "file:" + 绝对路径。
        assertEquals("file:/book/pic/01.png", fromFile!!.key)
        assertEquals(0, TextImageCatalog.indexOfKey(document, "file:/book/pic/01.png"))
        assertNull("path 不是 key", TextImageCatalog.indexOfKey(document, "/book/pic/01.png"))
        assertNull("alt 不是 key", TextImageCatalog.indexOfKey(document, "封面"))
    }

    // 13
    @Test
    fun indexOfKeyIsCaseSensitiveAndExact() {
        val document = documentOf(picture("img:Cover"), picture("img:cover"))
        assertEquals(0, TextImageCatalog.indexOfKey(document, "img:Cover"))
        assertEquals(1, TextImageCatalog.indexOfKey(document, "img:cover"))
        assertNull(TextImageCatalog.indexOfKey(document, "img:COVER"))
        assertNull(TextImageCatalog.indexOfKey(document, "img:Cover "))
        assertNull(TextImageCatalog.indexOfKey(document, "img:Cove"))
    }

    // 14
    @Test
    fun emptyKeyStillMatchesAnEmptyKeyedImage() {
        val document = documentOf(text("甲"), picture(""), picture("img:1"))
        // 段落 1 是空 key 图，它前面没有插图段，所以在目录里是第 0 张。
        assertEquals(0, TextImageCatalog.indexOfKey(document, ""))
        assertEquals(0, TextImageCatalog.indexOfParagraph(document, 1))
        // 空 key 只匹配第一张空 key 图；没有空 key 图时返回 null。
        assertNull(TextImageCatalog.indexOfKey(documentOf(picture("img:1")), ""))
    }

    // 15
    @Test
    fun catalogIndexesAgreeWithImagesList() {
        val document = documentOf(
            text("序"),
            picture("img:1"),
            picture("img:1"),
            text("甲"),
            picture("img:2"),
            picture("img:3"),
            picture("img:2"),
            text("后记"),
        )
        val images = TextImageCatalog.images(document)
        assertEquals(5, images.size)

        // 每条查询都拿 images() 里的同一实例核对：段落里的图和目录里的是同一份。
        val firstIndexByKey = LinkedHashMap<String, Int>()
        images.forEachIndexed { imageIndex, image ->
            val previous = firstIndexByKey.putIfAbsent(image.key, imageIndex)
            assertEquals(
                "key=${image.key} 的首次出现下标",
                previous ?: imageIndex,
                TextImageCatalog.indexOfKey(document, image.key),
            )
        }
        for (paragraphIndex in document.paragraphs.indices) {
            val image = document.paragraphs[paragraphIndex].image ?: continue
            val at = TextImageCatalog.indexOfParagraph(document, paragraphIndex)
            assertNotNull("段 $paragraphIndex 有图却查不到下标", at)
            assertSame(image, images[at!!])
        }
    }

    // 16
    @Test
    fun imagesReturnedListIsAFreshSnapshot() {
        val document = documentOf(picture("img:1"), text("甲"))
        val first = TextImageCatalog.images(document)
        assertEquals(1, first.size)
        // 实现每次新建列表：调用方把返回值当可变列表改坏，也污染不到下一次查询。
        @Suppress("UNCHECKED_CAST")
        (first as MutableList<TextImage>).add(TextImage(key = "img:hacked"))
        assertEquals(1, TextImageCatalog.images(document).size)
        assertEquals("img:1", TextImageCatalog.images(document)[0].key)
    }

    // 17
    @Test
    fun catalogWorksOnDocumentRebuiltFromParagraphs() {
        // FB2 / HTML 走 fromParagraphs：图片段文字为空但必须留下，空文字段被丢弃。
        val document = TextDocumentParser.fromParagraphs(
            listOf(
                TextParagraph("第一章"),
                TextParagraph(text = ""),
                TextParagraph(text = "", image = TextImage(key = "img:1")),
                TextParagraph("正文"),
                TextParagraph(text = "", image = TextImage(key = "img:2")),
            ),
        )
        // 空文字段被丢弃后：0=第一章、1=img:1、2=正文、3=img:2。
        assertEquals(4, document.paragraphs.size)
        assertEquals(listOf("第一章", "", "正文", ""), document.paragraphs.map { it.text })
        assertEquals(listOf("img:1", "img:2"), keys(document))
        assertEquals(0, TextImageCatalog.indexOfParagraph(document, 1))
        assertEquals(1, TextImageCatalog.indexOfParagraph(document, 3))
        assertEquals(0, TextImageCatalog.indexOfKey(document, "img:1"))
        assertNull(TextImageCatalog.indexOfParagraph(document, 0))
        assertNull(TextImageCatalog.indexOfParagraph(document, 2))
    }
}
