package app.particlebook.android.reader.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TextProgressJump] 的跳转契约：比例 / 段落下标 / 上一章下一章 → [TextProgressTarget]。
 *
 * 文档一律经 [TextDocumentParser]（parse 或 fromParagraphs）构造、目录一律经 [TextOutline.build]
 * 推导，不手搓 charOffsets 与 TextChapter；断言只落在契约上（段落下标、1 基比例、文案、章节归属）。
 *
 * 比例口径：第 i 段 → (i + 1) / count，与顶栏 `((firstVisibleItemIndex + 1) * 100 / total)`、
 * 书架 `((page + 1) * 100 / totalPages)` 一致。旧口径（段首字符偏移 / 全文字符数）是 0 基，
 * 4 段等长的书面板显示 0% / 75% 而顶栏显示 25% / 100%，同屏自相矛盾，已弃用。
 */
internal class TextProgressJumpTest {

    private fun documentOf(vararg paragraphs: TextParagraph): TextDocument =
        TextDocumentParser.fromParagraphs(paragraphs.toList())

    private fun body(text: String): TextParagraph = TextParagraph(text)

    private fun heading(text: String): TextParagraph = TextParagraph(text, headingLevel = 1)

    /** 4 段等长：1 基口径下四段分别是 25% / 50% / 75% / 100%（旧偏移口径是 0% / 25% / 50% / 75%）。 */
    private fun evenDocument(): TextDocument = documentOf(
        body("甲甲甲甲"),
        body("乙乙乙乙"),
        body("丙丙丙丙"),
        body("丁丁丁丁"),
    )

    /** 前言 1 段 + 三章（每章「标题 + 一段正文」）：章节锚点 1 / 3 / 5，共 7 段。 */
    private fun outlinedDocument(): TextDocument = documentOf(
        body("前言甲"),
        heading("第一章"),
        body("正文一"),
        heading("第二章"),
        body("正文二"),
        heading("第三章"),
        body("正文三"),
    )

    /** 「必须有目标」的包装：chapterStep 返回 null 就直接让用例失败，免得后面满屏 ?. 断言。 */
    private fun step(
        document: TextDocument,
        chapters: List<TextChapter>,
        paragraphIndex: Int,
        direction: Int,
    ): TextProgressTarget =
        TextProgressJump.chapterStep(document, chapters, paragraphIndex, direction)
            ?: throw AssertionError("expected a chapter target for index " + paragraphIndex + ", direction " + direction)

    // ---- 空 / 单段 / 比例边界 ----

    @Test
    fun emptyDocumentReportsZeroPositionAndNoChapter() {
        val document = TextDocumentParser.parse("", markdown = false)
        val target = TextProgressJump.targetOf(document, emptyList(), 0.5f)
        assertEquals(0, target.paragraphIndex)
        assertEquals(0f, target.fraction, 0f)
        assertEquals("0%", target.percentLabel)
        // 空文档不是「第 1 段 / 共 0 段」。
        assertEquals("第 0 段 / 共 0 段", target.positionLabel)
        assertNull(target.chapterTitle)
        assertEquals(0, target.chapterIndex)
        assertEquals(0, target.chapterCount)
    }

    @Test
    fun singleParagraphDocumentPinsEveryFractionToIt() {
        val document = documentOf(body("只有一段"))
        // 只有一段 = 已读 1/1，任何比例都吸附到它：1 基口径下是 100%，旧口径会显示 0%。
        for (fraction in floatArrayOf(0f, 0.5f, 1f)) {
            val target = TextProgressJump.targetOf(document, emptyList(), fraction)
            assertEquals(0, target.paragraphIndex)
            assertEquals(1f, target.fraction, 0f)
            assertEquals("100%", target.percentLabel)
            assertEquals("第 1 段 / 共 1 段", target.positionLabel)
        }
    }

    @Test
    fun fractionZeroLandsOnFirstParagraph() {
        val target = TextProgressJump.targetOf(evenDocument(), emptyList(), 0f)
        assertEquals(0, target.paragraphIndex)
        // 首段（已读 1/4）是 25%，不再是 0%：第一段也是「读到的段」。
        assertEquals(0.25f, target.fraction, 0f)
        assertEquals("25%", target.percentLabel)
        assertEquals("第 1 段 / 共 4 段", target.positionLabel)
    }

    @Test
    fun fractionOneLandsOnLastParagraph() {
        val target = TextProgressJump.targetOf(evenDocument(), emptyList(), 1f)
        assertEquals(3, target.paragraphIndex)
        // 末段（已读 4/4）是 100%，与顶栏「拖到底 = 100%」一致。
        assertEquals(1f, target.fraction, 0f)
        assertEquals("100%", target.percentLabel)
        assertEquals("第 4 段 / 共 4 段", target.positionLabel)
    }

    @Test
    fun nanFractionFallsBackToTheStart() {
        val target = TextProgressJump.targetOf(evenDocument(), emptyList(), Float.NaN)
        assertEquals(0, target.paragraphIndex)
        assertEquals(0.25f, target.fraction, 0f)
        assertEquals("25%", target.percentLabel)
    }

    @Test
    fun negativeFractionIsClampedToTheStart() {
        val document = evenDocument()
        assertEquals(
            TextProgressJump.targetOf(document, emptyList(), 0f),
            TextProgressJump.targetOf(document, emptyList(), -3.5f),
        )
    }

    @Test
    fun fractionAboveOneIsClampedToTheEnd() {
        val document = evenDocument()
        assertEquals(
            TextProgressJump.targetOf(document, emptyList(), 1f),
            TextProgressJump.targetOf(document, emptyList(), 4f),
        )
    }

    // ---- 段落吸附 ----

    @Test
    fun midParagraphFractionSnapsToTheNearestParagraph() {
        val document = evenDocument()
        val target = TextProgressJump.targetOf(document, emptyList(), 0.9f)
        // 0.9 * 4 = 3.6 → 四舍五入 4 → 第 4 段；1 基比例 (3 + 1) / 4 = 100%，标签必须与落点一致。
        assertEquals(3, target.paragraphIndex)
        assertEquals(1f, target.fraction, 0f)
        assertEquals("100%", target.percentLabel)
        assertEquals((target.paragraphIndex + 1).toFloat() / document.paragraphs.size, target.fraction, 0.0001f)
    }

    @Test
    fun fractionAndParagraphEntryPointsAgreeForTheSameParagraph() {
        val document = evenDocument()
        val chapters = emptyList<TextChapter>()
        val fromFraction = TextProgressJump.targetOf(document, chapters, 0.5f)
        // 0.5 * 4 = 2 → 减 1 得第 1 段（0 基下标 1），1 基比例 2/4 = 50%。
        assertEquals(1, fromFraction.paragraphIndex)
        assertEquals(0.5f, fromFraction.fraction, 0f)
        // 拖动预览走 targetOf、松手落地走 targetOfParagraph：同一个段落必须给出同一个 target。
        assertEquals(fromFraction, TextProgressJump.targetOfParagraph(document, chapters, 1))
    }

    @Test
    fun imageParagraphKeepsItsSlotAndIsCounted() {
        val document = documentOf(
            body("甲甲甲甲"),
            TextParagraph(text = "", image = TextImage(key = "img:1")),
            body("乙乙乙乙"),
        )
        // 图片段 text 为空但占一个下标：共 3 段，图片是第 2 段。
        assertEquals(3, document.paragraphs.size)
        assertTrue(document.paragraphs[1].text.isEmpty())
        val target = TextProgressJump.targetOfParagraph(document, emptyList(), 1)
        assertEquals(1, target.paragraphIndex)
        assertEquals("第 2 段 / 共 3 段", target.positionLabel)
        assertEquals(2f / 3f, target.fraction, 0.0001f)
        assertEquals("67%", target.percentLabel)
    }

    @Test
    fun percentLabelRoundsToTheNearestPercent() {
        val document = TextDocumentParser.fromParagraphs(List(7) { TextParagraph("第 " + it + " 段") })
        // 第 3 段（下标 2）：3 / 7 = 42.857…%，四舍五入到 43%。
        val target = TextProgressJump.targetOfParagraph(document, emptyList(), 2)
        assertEquals("43%", target.percentLabel)
    }

    @Test
    fun targetOfParagraphClampsOutOfRangeIndex() {
        val document = evenDocument()
        assertEquals(0, TextProgressJump.targetOfParagraph(document, emptyList(), -5).paragraphIndex)
        assertEquals(3, TextProgressJump.targetOfParagraph(document, emptyList(), 99).paragraphIndex)
        assertEquals(
            TextProgressJump.targetOfParagraph(document, emptyList(), 0),
            TextProgressJump.targetOfParagraph(document, emptyList(), -5),
        )
        assertEquals(
            TextProgressJump.targetOfParagraph(document, emptyList(), 3),
            TextProgressJump.targetOfParagraph(document, emptyList(), 99),
        )
    }

    // ---- 章节归属 ----

    @Test
    fun noOutlineLeavesChapterFieldsEmpty() {
        val target = TextProgressJump.targetOfParagraph(evenDocument(), emptyList(), 2)
        assertNull(target.chapterTitle)
        assertEquals(0, target.chapterIndex)
        assertEquals(0, target.chapterCount)
    }

    @Test
    fun paragraphBeforeFirstChapterHasNoChapter() {
        val document = outlinedDocument()
        val chapters = TextOutline.build(document)
        assertEquals(3, chapters.size)
        val target = TextProgressJump.targetOfParagraph(document, chapters, 0)
        assertNull(target.chapterTitle)
        assertEquals(0, target.chapterIndex)
        assertEquals(3, target.chapterCount)
        assertEquals("第 1 段 / 共 7 段", target.positionLabel)
    }

    @Test
    fun headingParagraphBelongsToItsOwnChapter() {
        val document = outlinedDocument()
        val chapters = TextOutline.build(document)
        val target = TextProgressJump.targetOfParagraph(document, chapters, 3)
        assertEquals("第二章", target.chapterTitle)
        // 章序号从 1 起：第二章在 chapters 里的下标是 1。
        assertEquals(2, target.chapterIndex)
        assertEquals(3, target.chapterCount)
    }

    @Test
    fun paragraphRightBeforeNextHeadingStaysInPreviousChapter() {
        val document = outlinedDocument()
        val chapters = TextOutline.build(document)
        val target = TextProgressJump.targetOfParagraph(document, chapters, 4)
        assertEquals("第二章", target.chapterTitle)
        assertEquals(2, target.chapterIndex)
    }

    @Test
    fun crossChapterFractionPicksTheChapterOfTheSnappedParagraph() {
        val document = outlinedDocument()
        val chapters = TextOutline.build(document)
        // 7 段：0.55 * 7 = 3.85 → 四舍五入 4 → 第 4 段（下标 3 = 第二章标题）。
        val target = TextProgressJump.targetOf(document, chapters, 0.55f)
        assertEquals(3, target.paragraphIndex)
        assertEquals("第二章", target.chapterTitle)
        assertEquals(2, target.chapterIndex)
        assertEquals(3, target.chapterCount)
        assertEquals(4f / 7f, target.fraction, 0.0001f)
    }

    // ---- 上一章 / 下一章 ----

    @Test
    fun chapterStepForwardJumpsToTheNextChapterStart() {
        val document = outlinedDocument()
        val chapters = TextOutline.build(document)
        val target = step(document, chapters, 2, 1)
        assertEquals(3, target.paragraphIndex)
        assertEquals("第二章", target.chapterTitle)
        assertEquals(2, target.chapterIndex)
        assertEquals("第 4 段 / 共 7 段", target.positionLabel)
    }

    @Test
    fun chapterStepForwardFromAChapterStartLeavesThatChapter() {
        val document = outlinedDocument()
        val chapters = TextOutline.build(document)
        // 站在第二章标题上再按「下一章」必须走到第三章；原地返回第二章等于按钮失灵。
        val target = step(document, chapters, 3, 1)
        assertEquals(5, target.paragraphIndex)
        assertEquals("第三章", target.chapterTitle)
        assertEquals(3, target.chapterIndex)
    }

    @Test
    fun chapterStepForwardAtLastChapterReturnsNull() {
        val document = outlinedDocument()
        val chapters = TextOutline.build(document)
        assertNull(TextProgressJump.chapterStep(document, chapters, 5, 1))
        assertNull(TextProgressJump.chapterStep(document, chapters, 6, 1))
    }

    @Test
    fun chapterStepBackwardJumpsToThePreviousChapterStart() {
        val document = outlinedDocument()
        val chapters = TextOutline.build(document)
        val target = step(document, chapters, 4, -1)
        assertEquals(1, target.paragraphIndex)
        assertEquals("第一章", target.chapterTitle)
        assertEquals(1, target.chapterIndex)
        assertEquals(3, target.chapterCount)
    }

    @Test
    fun chapterStepBackwardFromFirstChapterReturnsNull() {
        val document = outlinedDocument()
        val chapters = TextOutline.build(document)
        assertNull(TextProgressJump.chapterStep(document, chapters, 1, -1))
        assertNull(TextProgressJump.chapterStep(document, chapters, 2, -1))
    }

    @Test
    fun chapterStepWithZeroDirectionReturnsNull() {
        val document = outlinedDocument()
        val chapters = TextOutline.build(document)
        assertNull(TextProgressJump.chapterStep(document, chapters, 3, 0))
        assertNull(TextProgressJump.chapterStep(document, chapters, 0, 0))
    }

    @Test
    fun chapterStepWithoutOutlineReturnsNull() {
        val document = outlinedDocument()
        assertNull(TextProgressJump.chapterStep(document, emptyList(), 0, 1))
        assertNull(TextProgressJump.chapterStep(document, emptyList(), 3, -1))
    }

    @Test
    fun chapterStepInSingleChapterDocumentReturnsNull() {
        val document = documentOf(heading("唯一一章"), body("正文"))
        val chapters = TextOutline.build(document)
        assertEquals(1, chapters.size)
        assertNull(TextProgressJump.chapterStep(document, chapters, 0, -1))
        assertNull(TextProgressJump.chapterStep(document, chapters, 1, 1))
    }

    @Test
    fun chapterStepFromBeforeFirstChapterOnlyGoesForward() {
        val document = outlinedDocument()
        val chapters = TextOutline.build(document)
        // 前言没有所属章：前进落到第一章，后退没有可去的地方。
        assertNull(TextProgressJump.chapterStep(document, chapters, 0, -1))
        val forward = step(document, chapters, 0, 1)
        assertEquals(1, forward.paragraphIndex)
        assertEquals("第一章", forward.chapterTitle)
        assertEquals(1, forward.chapterIndex)
    }

    // ---- 与顶栏 / 书架的百分比口径对照 ----

    @Test
    fun percentLabelMatchesTheTopBarReadParagraphFormula() {
        val document = evenDocument()
        val target = TextProgressJump.targetOf(document, emptyList(), 0.5f)
        val count = document.paragraphs.size
        // 顶栏写法：((firstVisibleItemIndex + 1) * 100 / total)，整数除法。
        val topBarPercent = (target.paragraphIndex + 1) * 100 / count
        assertEquals(topBarPercent.toString() + "%", target.percentLabel)
        assertEquals("50%", target.percentLabel)
    }

    @Test
    fun hundredParagraphsReverseMapToTheFiftyFirst() {
        val document = TextDocumentParser.fromParagraphs(List(100) { TextParagraph("第 " + it + " 段") })
        assertEquals(100, document.paragraphs.size)
        // 0.505 * 100 = 50.5 → 四舍五入 51 → 减 1 得下标 50，即第 51 段 / 51%。
        val target = TextProgressJump.targetOf(document, emptyList(), 0.505f)
        assertEquals(50, target.paragraphIndex)
        assertEquals(0.51f, target.fraction, 0.0001f)
        assertEquals("51%", target.percentLabel)
    }

    @Test
    fun firstParagraphShowsOneOverCountInsteadOfZero() {
        // 短首段 + 长次段：旧口径按段首字符偏移算，首段起点恒为 0，任何书的第一段都显示 0%。
        val document = documentOf(body("一"), body("第二段写得长一些，用来对照旧口径的 0% 缺陷"))
        val first = TextProgressJump.targetOfParagraph(document, emptyList(), 0)
        assertEquals(0.5f, first.fraction, 0f)
        assertEquals("50%", first.percentLabel)
        // 4 段等长的书，首段是 25%，与顶栏一致。
        assertEquals("25%", TextProgressJump.targetOf(evenDocument(), emptyList(), 0f).percentLabel)
        assertTrue(first.percentLabel != "0%")
    }
}
