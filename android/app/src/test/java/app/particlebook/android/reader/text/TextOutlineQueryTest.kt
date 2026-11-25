package app.particlebook.android.reader.text

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TextOutline.chapterAt] 的目录定位契约。
 *
 * 只回答一个问题：给定段落下标，返回最后一条「锚点不晚于它」的目录项。
 * 用例全部对着契约写，不对着当前二分的实现写：断言落在 title / paragraphIndex 上，
 * 不关心内部是二分还是线性；唯一带实现色彩的一条是「重复锚点返回靠后那条」，
 * 而它本身就是 [TextOutline.chapterAt] 文档写明的契约。
 */
internal class TextOutlineQueryTest {

    /** 按锚点顺序造一条目录，标题各不相同便于定位。 */
    private fun outlineOf(vararg anchors: Int): List<TextChapter> =
        anchors.mapIndexed { position, anchor -> TextChapter("第 ${position + 1} 章", anchor, 1) }

    /** 契约的朴素实现：线性扫一遍，取最后一个锚点 <= [paragraphIndex] 的条目。 */
    private fun referenceAt(chapters: List<TextChapter>, paragraphIndex: Int): TextChapter? {
        if (paragraphIndex < 0) return null
        var found: TextChapter? = null
        for (chapter in chapters) {
            if (chapter.paragraphIndex <= paragraphIndex) found = chapter
        }
        return found
    }

    /** 固定种子的递增锚点目录：相邻锚点间距 1..9，level 在 1..[TextOutline.MAX_LEVEL] 之间轮转。 */
    private fun seededOutline(seed: Long, firstAnchor: Int, count: Int): List<TextChapter> {
        val random = Random(seed)
        val result = ArrayList<TextChapter>(count)
        var anchor = firstAnchor
        for (position in 0 until count) {
            if (position > 0) anchor += 1 + random.nextInt(9)
            result += TextChapter("章 $position", anchor, 1 + position % TextOutline.MAX_LEVEL)
        }
        return result
    }

    // 1. 空表
    @Test
    fun emptyOutlineReturnsNullForEveryIndex() {
        val empty = emptyList<TextChapter>()
        assertNull(TextOutline.chapterAt(empty, 0))
        assertNull(TextOutline.chapterAt(empty, 1))
        assertNull(TextOutline.chapterAt(empty, 10_000))
        assertNull(TextOutline.chapterAt(empty, Int.MAX_VALUE))
    }

    // 2. 负下标
    @Test
    fun negativeIndexReturnsNull() {
        val outline = outlineOf(0, 4, 9)
        assertNull(TextOutline.chapterAt(outline, -1))
        assertNull(TextOutline.chapterAt(outline, -9))
        assertNull(TextOutline.chapterAt(outline, Int.MIN_VALUE))
        // 首条锚点是 0 时也不能把负下标兜成首条：负号不是「早于第一条」。
        assertNull(TextOutline.chapterAt(outlineOf(0, 3), -1))
    }

    // 3. 下标小于第一条 anchor（第一条 anchor > 0）
    @Test
    fun indexBeforeFirstAnchorReturnsNull() {
        val outline = outlineOf(5, 9, 20)
        assertNull(TextOutline.chapterAt(outline, 0))
        assertNull(TextOutline.chapterAt(outline, 1))
        assertNull(TextOutline.chapterAt(outline, 4))
        // 长前言 / 卷首：第一条标题很远时同样不兜底。
        val late = outlineOf(1_000)
        assertNull(TextOutline.chapterAt(late, 0))
        assertNull(TextOutline.chapterAt(late, 999))
    }

    // 4. 下标正好等于第一条 anchor
    @Test
    fun indexAtFirstAnchorReturnsFirstChapter() {
        val outline = outlineOf(5, 9, 20)
        assertNotNull(TextOutline.chapterAt(outline, 5))
        assertEquals(outline[0], TextOutline.chapterAt(outline, 5))
    }

    // 5. 下标位于第 1、2 条之间
    @Test
    fun indexBetweenFirstAndSecondAnchorReturnsFirstChapter() {
        val outline = outlineOf(5, 9, 20)
        for (index in 5 until 9) {
            assertEquals("index=$index", outline[0], TextOutline.chapterAt(outline, index))
        }
    }

    // 6. 下标正好等于某条 anchor（逐条扫一遍，含锚点前一个下标归上一条）
    @Test
    fun indexExactlyOnAnchorReturnsThatChapter() {
        val outline = outlineOf(0, 3, 7, 12, 40)
        for (position in outline.indices) {
            val anchor = outline[position].paragraphIndex
            assertEquals("anchor=$anchor", outline[position], TextOutline.chapterAt(outline, anchor))
            val expectedBefore = if (position == 0) null else outline[position - 1]
            assertEquals("anchor-1=$anchor", expectedBefore, TextOutline.chapterAt(outline, anchor - 1))
        }
    }

    // 7. 下标大于最后一条 anchor
    @Test
    fun indexAfterLastAnchorReturnsLastChapter() {
        val outline = outlineOf(2, 8, 15)
        assertEquals(outline.last(), TextOutline.chapterAt(outline, 16))
        assertEquals(outline.last(), TextOutline.chapterAt(outline, 10_000))
    }

    // 8. Int.MAX_VALUE
    @Test
    fun maxIntReturnsLastChapter() {
        val outline = outlineOf(0, 1, 2)
        assertEquals(outline.last(), TextOutline.chapterAt(outline, Int.MAX_VALUE))
        assertEquals(outline.last(), TextOutline.chapterAt(outline, Int.MAX_VALUE - 1))
    }

    // 9. 单条目录
    @Test
    fun singleChapterOutlineMatchesOnlyFromItsAnchor() {
        val single = outlineOf(12)
        assertNull(TextOutline.chapterAt(single, 0))
        assertNull(TextOutline.chapterAt(single, 11))
        assertEquals(single[0], TextOutline.chapterAt(single, 12))
        assertEquals(single[0], TextOutline.chapterAt(single, 13))
        assertEquals(single[0], TextOutline.chapterAt(single, Int.MAX_VALUE))

        val fromZero = outlineOf(0)
        assertEquals(fromZero[0], TextOutline.chapterAt(fromZero, 0))
        assertNull(TextOutline.chapterAt(fromZero, -1))
    }

    // 10. 重复 anchor → 返回靠后的那条
    @Test
    fun duplicateAnchorPrefersTheLaterEntry() {
        val duplicated = listOf(
            TextChapter("A", 5, 1),
            TextChapter("B", 5, 1),
        )
        val found = TextOutline.chapterAt(duplicated, 5)
        assertNotNull(found)
        assertEquals("B", found?.title)
        assertEquals(5, found?.paragraphIndex)
        assertEquals(duplicated[1], found)
        // 锚点之后的同一对重复项同样报靠后那条。
        assertEquals(duplicated[1], TextOutline.chapterAt(duplicated, 6))

        // 三条同锚点：取列表最后一条。
        val tripled = listOf(
            TextChapter("A", 5, 1),
            TextChapter("B", 5, 1),
            TextChapter("C", 5, 1),
        )
        assertEquals("C", TextOutline.chapterAt(tripled, 5)?.title)
        assertEquals("C", TextOutline.chapterAt(tripled, 99)?.title)
    }

    // 11. anchor 全为 0（第一条从 0 起）→ 任意非负下标都命中
    @Test
    fun allAnchorsZeroMatchesEveryNonNegativeIndex() {
        val outline = listOf(
            TextChapter("A", 0, 1),
            TextChapter("B", 0, 1),
            TextChapter("C", 0, 1),
        )
        for (index in listOf(0, 1, 2, 7, 999, Int.MAX_VALUE)) {
            assertEquals("index=$index", outline.last(), TextOutline.chapterAt(outline, index))
        }
        assertNull(TextOutline.chapterAt(outline, -1))
    }

    // 12. 属性式对照：固定种子 60 条递增锚点，全下标与线性扫描逐项一致
    @Test
    fun matchesLinearReferenceOnSeededOutline() {
        val cases = listOf(
            Triple(20_261_125L, 0, 60),
            Triple(7L, 7, 60),
            Triple(4_004L, 1, 400),
        )
        for ((seed, firstAnchor, count) in cases) {
            val outline = seededOutline(seed, firstAnchor, count)
            assertEquals(count, outline.size)
            for (position in 1 until outline.size) {
                assertTrue(
                    "锚点必须严格递增：seed=$seed position=$position",
                    outline[position].paragraphIndex > outline[position - 1].paragraphIndex,
                )
            }
            val lastAnchor = outline.last().paragraphIndex
            for (index in 0..(lastAnchor + 20)) {
                val expected = referenceAt(outline, index)
                val found = TextOutline.chapterAt(outline, index)
                val message = "seed=$seed firstAnchor=$firstAnchor index=$index"
                assertEquals(message, expected, found)
                if (expected != null && found != null) {
                    assertEquals(message, expected.title, found.title)
                    assertEquals(message, expected.paragraphIndex, found.paragraphIndex)
                    assertEquals(message, expected.level, found.level)
                }
            }
            assertNull(TextOutline.chapterAt(outline, firstAnchor - 1))
        }
    }

    // 13. 真实文档：TextOutline.build 产出的目录再做全下标线性对照
    @Test
    fun matchesLinearReferenceOnBuiltOutline() {
        val source = ArrayList<TextParagraph>()
        source += TextParagraph("书名页：静水集")
        source += TextParagraph("序言正文：开篇没有标题，所以这一段不属于任何章节。")
        for (section in 0 until 30) {
            source += TextParagraph("第 ${section + 1} 节", headingLevel = 1 + section % 3)
            repeat(3) { line ->
                source += TextParagraph("正文 ${section + 1}-${line + 1}：" + "文".repeat(12))
            }
        }
        // 纯空白标题必须被 build 丢弃：fromParagraphs 只丢空串，所以这里用空格串。
        source += TextParagraph("   ", headingLevel = 2)
        source += TextParagraph("尾声正文：末尾同样没有标题。")

        val document = TextDocumentParser.fromParagraphs(source)
        val outline = TextOutline.build(document)
        assertTrue("目录不该为空", outline.isNotEmpty())
        assertTrue("空白标题不该进目录", outline.none { it.title.isBlank() })
        for (position in 1 until outline.size) {
            assertTrue(
                "build 产出的锚点必须递增：position=$position",
                outline[position].paragraphIndex > outline[position - 1].paragraphIndex,
            )
        }
        // 书名页与序言在首条标题之前。
        assertNull(TextOutline.chapterAt(outline, 0))
        assertNull(TextOutline.chapterAt(outline, 1))
        // 每条标题段落自己精确命中自己。
        for (chapter in outline) {
            assertEquals(chapter, TextOutline.chapterAt(outline, chapter.paragraphIndex))
        }

        // 0..charCount/2 全下标对照，步长保证采样点不少于 100 个。
        val upper = document.charCount / 2
        val stride = maxOf(1, upper / 200)
        var samples = 0
        var index = 0
        while (index <= upper) {
            assertEquals(
                "index=$index charCount=${document.charCount} stride=$stride",
                referenceAt(outline, index),
                TextOutline.chapterAt(outline, index),
            )
            samples++
            index += stride
        }
        assertTrue("采样点不足：samples=$samples upper=$upper", samples >= 100)
    }
}
