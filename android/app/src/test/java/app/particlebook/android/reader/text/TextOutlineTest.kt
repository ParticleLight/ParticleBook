package app.particlebook.android.reader.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TextOutline] 的目录推导与「当前章节」查询。
 *
 * 所有文档都经 [TextDocumentParser.fromParagraphs] 构造（真实路径，不手写 charOffsets）。
 * 注意：fromParagraphs 会丢弃**空串**段落、保留纯空白段落，所以「空白标题」用 " " 造。
 */
internal class TextOutlineTest {

    private fun doc(vararg paragraphs: TextParagraph): TextDocument =
        TextDocumentParser.fromParagraphs(paragraphs.toList())

    private fun titles(chapters: List<TextChapter>): List<String> = chapters.map { it.title }

    private fun levels(chapters: List<TextChapter>): List<Int> = chapters.map { it.level }

    private fun anchors(chapters: List<TextChapter>): List<Int> = chapters.map { it.paragraphIndex }

    // 1
    @Test
    fun buildEmptyDocumentGivesEmptyTable() {
        val document = doc()
        assertTrue(document.isEmpty)
        val chapters = TextOutline.build(document)
        assertTrue(chapters.isEmpty())
        assertEquals(0, chapters.size)
    }

    // 2
    @Test
    fun buildDocumentWithoutHeadingsGivesEmptyTable() {
        val document = doc(
            TextParagraph("书名页"),
            TextParagraph("正文第一段"),
            TextParagraph("正文第二段"),
        )
        val chapters = TextOutline.build(document)
        assertTrue(chapters.isEmpty())
    }

    // 3
    @Test
    fun buildSingleHeadingGivesOneLevelOneEntry() {
        val document = doc(
            TextParagraph("书名页"),
            TextParagraph("第一章 起", headingLevel = 1),
            TextParagraph("正文"),
        )
        val chapters = TextOutline.build(document)
        assertEquals(1, chapters.size)
        assertEquals("第一章 起", chapters[0].title)
        assertEquals(1, chapters[0].level)
        assertEquals(1, chapters[0].paragraphIndex)
    }

    // 4
    @Test
    fun buildNestedThreeLevelsKeepsDocumentOrder() {
        val document = doc(
            TextParagraph("正文 0"),
            TextParagraph("卷一", headingLevel = 1),
            TextParagraph("正文 2"),
            TextParagraph("第一章", headingLevel = 2),
            TextParagraph("正文 4"),
            TextParagraph("第一节", headingLevel = 3),
        )
        val chapters = TextOutline.build(document)
        assertEquals(3, chapters.size)
        assertEquals(listOf("卷一", "第一章", "第一节"), titles(chapters))
        assertEquals(listOf(1, 2, 3), levels(chapters))
        assertEquals(listOf(1, 3, 5), anchors(chapters))
    }

    // 5
    @Test
    fun buildStartingAtHeadingLevelThreeNormalizesAllToOne() {
        val document = doc(
            TextParagraph("甲", headingLevel = 3),
            TextParagraph("正文"),
            TextParagraph("乙", headingLevel = 3),
            TextParagraph("丙", headingLevel = 3),
        )
        val chapters = TextOutline.build(document)
        assertEquals(3, chapters.size)
        assertEquals(listOf("甲", "乙", "丙"), titles(chapters))
        // 最小层级是 3，所以每个都是 3-3+1 = 1。
        assertEquals(listOf(1, 1, 1), levels(chapters))
        assertEquals(listOf(0, 2, 3), anchors(chapters))
    }

    // 6
    @Test
    fun buildH3ThenH5NormalizesRelatively() {
        val document = doc(
            TextParagraph("卷", headingLevel = 3),
            TextParagraph("章", headingLevel = 5),
        )
        val chapters = TextOutline.build(document)
        assertEquals(2, chapters.size)
        // 实现按 level = (headingLevel - minLevel + 1) 逐级递增，不做「压缩空档」：
        // 最小层级 3 → 3-3+1 = 1，5-3+1 = 3（中间的 h4 仍占一级，即使文档里没有 h4）。
        assertEquals(listOf(1, 3), levels(chapters))
        assertEquals(listOf("卷", "章"), titles(chapters))
    }

    // 7
    @Test
    fun buildCapsNormalizedLevelAtMaxLevel() {
        val document = doc(
            TextParagraph("一级", headingLevel = 1),
            TextParagraph("八级", headingLevel = 8),
        )
        val chapters = TextOutline.build(document)
        assertEquals(2, chapters.size)
        // 1-1+1 = 1；8-1+1 = 8 被 coerceIn 到 MAX_LEVEL(6)。
        assertEquals(listOf(1, 6), levels(chapters))
        assertEquals(6, TextOutline.MAX_LEVEL)
    }

    // 8
    @Test
    fun buildOverLimitKeepsFirstChaptersNotLast() {
        val document = doc(
            TextParagraph("一", headingLevel = 1),
            TextParagraph("二", headingLevel = 2),
            TextParagraph("三", headingLevel = 2),
        )
        val chapters = TextOutline.build(document, maxChapters = 2)
        assertEquals(2, chapters.size)
        assertEquals(listOf("一", "二"), titles(chapters))
        assertEquals(listOf(0, 1), anchors(chapters))
    }

    // 9
    @Test
    fun buildZeroMaxChaptersGivesEmptyTable() {
        val document = doc(
            TextParagraph("一", headingLevel = 1),
            TextParagraph("二", headingLevel = 2),
        )
        assertTrue(TextOutline.build(document, maxChapters = 0).isEmpty())
    }

    // 10
    @Test
    fun buildNegativeMaxChaptersGivesEmptyTable() {
        val document = doc(
            TextParagraph("一", headingLevel = 1),
            TextParagraph("二", headingLevel = 2),
        )
        assertTrue(TextOutline.build(document, maxChapters = -5).isEmpty())
    }

    // 11
    @Test
    fun buildSkipsBlankHeadingAndIgnoresItWhenComputingMinLevel() {
        val document = doc(
            TextParagraph("根标题", headingLevel = 2),
            TextParagraph(" ", headingLevel = 2),
            TextParagraph("实标题", headingLevel = 3),
        )
        // fromParagraphs 保留纯空白段落，这里确认空白标题确实进了文档。
        assertEquals(3, document.paragraphs.size)
        assertEquals(" ", document.paragraphs[1].text)
        assertEquals(2, document.paragraphs[1].headingLevel)

        val chapters = TextOutline.build(document)
        assertEquals(2, chapters.size)
        assertEquals(listOf("根标题", "实标题"), titles(chapters))
        // 空白标题已跳过，不参与最小层级计算：最小层级是 2，实标题 3 归一化成 2。
        assertEquals(listOf(1, 2), levels(chapters))
        assertEquals(listOf(0, 2), anchors(chapters))
    }

    // 11b：空白标题是最小层级时，它若参与计算会拉低别人的相对层级
    @Test
    fun buildBlankHeadingDoesNotBecomeTheMinLevelBaseline() {
        val document = doc(
            TextParagraph(" ", headingLevel = 1),
            TextParagraph("实标题", headingLevel = 3),
            TextParagraph("次标题", headingLevel = 4),
        )
        val chapters = TextOutline.build(document)
        assertEquals(2, chapters.size)
        assertEquals(listOf("实标题", "次标题"), titles(chapters))
        // 最小层级取实标题的 3（不是空白标题的 1）：3-3+1 = 1，4-3+1 = 2。
        assertEquals(listOf(1, 2), levels(chapters))
        assertEquals(listOf(1, 2), anchors(chapters))
    }

    // 12
    @Test
    fun buildParagraphIndexIsRealDocumentIndex() {
        val document = doc(
            TextParagraph("正文甲"),
            TextParagraph("正文乙"),
            TextParagraph("标题", headingLevel = 1),
        )
        val chapters = TextOutline.build(document)
        assertEquals(1, chapters.size)
        assertEquals(2, chapters[0].paragraphIndex)
    }

    // 13
    @Test
    fun buildHeadingsOnlyDocumentKeepsEveryHeading() {
        val document = doc(
            TextParagraph("一", headingLevel = 1),
            TextParagraph("二", headingLevel = 2),
            TextParagraph("三", headingLevel = 2),
        )
        val chapters = TextOutline.build(document)
        assertEquals(3, chapters.size)
        assertEquals(listOf("一", "二", "三"), titles(chapters))
        assertEquals(listOf(1, 2, 2), levels(chapters))
        assertEquals(listOf(0, 1, 2), anchors(chapters))
    }

    // 14
    @Test
    fun buildFb2StyleMixedHeadingsKeepOrderAndLevels() {
        val document = doc(
            TextParagraph("第一卷", headingLevel = 1),
            TextParagraph("第一章", headingLevel = 2),
            TextParagraph("正文"),
            TextParagraph("第二章", headingLevel = 2),
            TextParagraph("第一节", headingLevel = 3),
            TextParagraph("正文"),
        )
        val chapters = TextOutline.build(document)
        assertEquals(4, chapters.size)
        assertEquals(listOf("第一卷", "第一章", "第二章", "第一节"), titles(chapters))
        assertEquals(listOf(1, 2, 2, 3), levels(chapters))
        assertEquals(listOf(0, 1, 3, 4), anchors(chapters))
    }

    // 15
    @Test
    fun buildHtmlStyleStartingAtH2NormalizesTo121() {
        val document = doc(
            TextParagraph("章一", headingLevel = 2),
            TextParagraph("节一", headingLevel = 3),
            TextParagraph("章二", headingLevel = 2),
        )
        val chapters = TextOutline.build(document)
        assertEquals(3, chapters.size)
        assertEquals(listOf(1, 2, 1), levels(chapters))
        assertEquals(listOf("章一", "节一", "章二"), titles(chapters))
    }

    // 16
    @Test
    fun buildAnchorsAreStrictlyIncreasing() {
        val document = doc(
            TextParagraph("正文 0"),
            TextParagraph("卷一", headingLevel = 1),
            TextParagraph("正文 2"),
            TextParagraph("第一章", headingLevel = 2),
            TextParagraph("第一节", headingLevel = 3),
            TextParagraph("正文 5"),
            TextParagraph("第二章", headingLevel = 2),
        )
        val chapters = TextOutline.build(document)
        assertEquals(4, chapters.size)
        assertEquals(listOf(1, 3, 4, 6), anchors(chapters))
        // 严格递增：已排序序列等于自身，且两两之间严格小于。
        assertEquals(chapters.sortedBy { it.paragraphIndex }, chapters)
        chapters.zipWithNext { previous, next ->
            assertTrue(
                "anchor 必须严格递增：${previous.paragraphIndex} -> ${next.paragraphIndex}",
                previous.paragraphIndex < next.paragraphIndex,
            )
        }
    }

    // 17：默认上限走常量（造 401 个标题太慢，直接钉住常量值）
    @Test
    fun defaultMaxChaptersConstantIsFourHundred() {
        assertEquals(400, TextOutline.DEFAULT_MAX_CHAPTERS)
    }

    // 18
    @Test
    fun buildKeepsHeadingTextVerbatim() {
        val raw = "  带空格的标题  "
        val document = doc(TextParagraph(raw, headingLevel = 1))
        val chapters = TextOutline.build(document)
        assertEquals(1, chapters.size)
        // fromParagraphs 与 build 都不做 trim，标题原样保留。
        assertEquals(raw, chapters[0].title)
        assertEquals(raw.length, chapters[0].title.length)
        assertTrue(chapters[0].title.startsWith("  "))
        assertTrue(chapters[0].title.endsWith("  "))
    }

    // 19（chapterAt 契约）：第一条标题之前返回 null
    @Test
    fun chapterAtBeforeFirstHeadingReturnsNull() {
        val document = doc(
            TextParagraph("书名页"),
            TextParagraph("第一章", headingLevel = 1),
        )
        val chapters = TextOutline.build(document)
        assertEquals(1, chapters[0].paragraphIndex)
        assertNull(TextOutline.chapterAt(chapters, 0))
        assertNull(TextOutline.chapterAt(chapters, -1))
        assertNull(TextOutline.chapterAt(emptyList(), 0))
        assertNotNull(TextOutline.chapterAt(chapters, 1))
    }

    // 20（chapterAt 契约）：重复 anchor 返回列表里靠后的那条
    @Test
    fun chapterAtDuplicateAnchorReturnsLaterEntry() {
        val chapters = listOf(
            TextChapter(title = "前", paragraphIndex = 0, level = 1),
            TextChapter(title = "后", paragraphIndex = 0, level = 1),
            TextChapter(title = "尾", paragraphIndex = 5, level = 1),
        )
        assertEquals("后", TextOutline.chapterAt(chapters, 0)!!.title)
        assertEquals("后", TextOutline.chapterAt(chapters, 4)!!.title)
        assertEquals("尾", TextOutline.chapterAt(chapters, 5)!!.title)
        assertEquals("尾", TextOutline.chapterAt(chapters, 99)!!.title)
    }
}
