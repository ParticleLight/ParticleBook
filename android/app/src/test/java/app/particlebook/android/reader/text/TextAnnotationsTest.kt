package app.particlebook.android.reader.text

import app.particlebook.android.reader.ReaderAnnotation
import app.particlebook.android.reader.ReaderAnnotationType
import app.particlebook.android.reader.ReaderLocator
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TextAnnotations] 的段落级锚点契约。
 *
 * 用例对着契约写、不对着实现写：断言落在 paragraphIndex / offset / length / quote 这些可观察结果上。
 * 唯一带实现色彩的是「十万段与朴素线性实现逐点对照」—— 它验证的正是
 * [TextAnnotations.paragraphIndexOfOffset] 文档写明的「最后一个 charOffsets[i] <= offset」。
 *
 * 文档一律由 [TextDocumentParser] 造（parse 或 fromParagraphs），不手搓 charOffsets；
 * 唯一例外是「重复 offset 取最后一处」，理由见该用例注释。
 *
 * 底色按「高亮优先」写：paintColorOf 认的是**第一条 HIGHLIGHT**，没有高亮才回退到第一条 NOTE，
 * 两条在列表里的先后（createdAt）不决定选谁，只决定同类型内部取哪一条。
 */
internal class TextAnnotationsTest {

    private val bookId = 7L

    /** U+1F600，UTF-16 里占两个码元，专门用来卡截断边界。 */
    private val emoji = "\uD83D\uDE00"

    private fun docOf(vararg texts: String): TextDocument =
        TextDocumentParser.fromParagraphs(texts.map { TextParagraph(it) })

    private fun annotation(
        id: Long,
        locator: ReaderLocator,
        type: ReaderAnnotationType = ReaderAnnotationType.HIGHLIGHT,
        color: String? = null,
        note: String = "",
        locatorError: String? = null,
    ): ReaderAnnotation = ReaderAnnotation(
        id = id,
        bookId = bookId,
        type = type,
        locator = locator,
        note = note,
        color = color,
        locatorError = locatorError,
    )

    private fun textAt(offset: Int): ReaderLocator = ReaderLocator.Text(offset)

    /** 是否存在孤立代理（半个 emoji）：截断保护要保证的是这个，而不是「整个 quote 里没有代理」。 */
    private fun hasLoneSurrogate(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            if (Character.isHighSurrogate(text[index])) {
                if (index + 1 >= text.length || !Character.isLowSurrogate(text[index + 1])) return true
                index += 2
            } else if (Character.isLowSurrogate(text[index])) {
                return true
            } else {
                index++
            }
        }
        return false
    }

    // ---------------------------------------------------------------- paragraphIndexOfOffset

    // 1. 空文档
    @Test
    fun emptyDocumentHasNoParagraph() {
        val document = TextDocumentParser.parse("", markdown = false)
        assertTrue(document.isEmpty)
        assertEquals(0, TextAnnotations.paragraphIndexOfOffset(document, 0))
        assertEquals(0, TextAnnotations.paragraphIndexOfOffset(document, 4096))
        assertNull(TextAnnotations.anchorOf(document, 0))
        assertNull(TextAnnotations.anchorOf(document, -1))
        assertTrue(TextAnnotations.byParagraph(document, listOf(annotation(1, textAt(0)))).isEmpty())
    }

    // 2. 单段
    @Test
    fun singleParagraphAnswersEveryOffsetInsideIt() {
        val document = docOf("hello world")
        assertEquals(1, document.paragraphs.size)
        assertEquals(0, document.charOffsets[0])
        for (offset in 0 until document.paragraphs[0].text.length) {
            assertEquals("offset=$offset", 0, TextAnnotations.paragraphIndexOfOffset(document, offset))
        }
        assertEquals(0, TextAnnotations.paragraphIndexOfOffset(document, 5_000))
    }

    // 3. 段首
    @Test
    fun paragraphStartsMapToTheirOwnParagraph() {
        val document = docOf("abc", "def", "ghi")
        assertEquals(0, document.charOffsets[0])
        assertEquals(3, document.charOffsets[1])
        assertEquals(6, document.charOffsets[2])
        assertEquals(0, TextAnnotations.paragraphIndexOfOffset(document, 0))
        assertEquals(1, TextAnnotations.paragraphIndexOfOffset(document, 3))
        assertEquals(2, TextAnnotations.paragraphIndexOfOffset(document, 6))
    }

    // 4. 段中
    @Test
    fun paragraphInteriorMapsToThatParagraph() {
        val document = docOf("abc", "def", "ghi")
        assertEquals(0, TextAnnotations.paragraphIndexOfOffset(document, 1))
        assertEquals(1, TextAnnotations.paragraphIndexOfOffset(document, 4))
        assertEquals(2, TextAnnotations.paragraphIndexOfOffset(document, 8))
    }

    // 5. 段尾（最后一个字符仍属于本段）
    @Test
    fun lastCharOfParagraphStaysInThatParagraph() {
        val document = docOf("abc", "def", "ghi")
        assertEquals(0, TextAnnotations.paragraphIndexOfOffset(document, 2))
        assertEquals(1, TextAnnotations.paragraphIndexOfOffset(document, 5))
        assertEquals(2, TextAnnotations.paragraphIndexOfOffset(document, 8))
    }

    // 6. 超出末尾
    @Test
    fun offsetPastDocumentEndStaysOnLastParagraph() {
        val document = docOf("abc", "def", "ghi")
        assertEquals(9, document.charCount)
        assertEquals(2, TextAnnotations.paragraphIndexOfOffset(document, 9))
        assertEquals(2, TextAnnotations.paragraphIndexOfOffset(document, 10_000))
        assertEquals(2, TextAnnotations.paragraphIndexOfOffset(document, Int.MAX_VALUE))
    }

    // 7. 负 offset
    @Test
    fun negativeOffsetIsTreatedAsZero() {
        val document = docOf("abc", "def")
        assertEquals(0, TextAnnotations.paragraphIndexOfOffset(document, -1))
        assertEquals(0, TextAnnotations.paragraphIndexOfOffset(document, -10_000))
        assertEquals(0, TextAnnotations.paragraphIndexOfOffset(document, Int.MIN_VALUE))
    }

    // 8. 小于首段起点的偏移
    @Test
    fun offsetBeforeFirstParagraphStartReturnsFirstParagraph() {
        // 前导空行不占段，但会推后首段起点：这里首段从 2 开始，0 和 1 都必须归到 0 号段。
        val document = TextDocumentParser.parse("\n\na\n\nb\n", markdown = false)
        assertEquals(2, document.paragraphs.size)
        assertEquals(2, document.charOffsets[0])
        assertEquals(0, TextAnnotations.paragraphIndexOfOffset(document, 0))
        assertEquals(0, TextAnnotations.paragraphIndexOfOffset(document, 1))
        assertEquals(0, TextAnnotations.paragraphIndexOfOffset(document, 2))
        assertEquals(1, TextAnnotations.paragraphIndexOfOffset(document, document.charOffsets[1]))
    }

    // 9. 重复 offset 取最后一处
    @Test
    fun duplicateOffsetsResolveToLastParagraph() {
        // 现有四种格式的解析器都不会产出重复 offset（空段直接丢弃、游标只前进），
        // 但 charOffsets 的契约只承诺「单调不减」，重复 offset 仍是合法输入。
        // 这里在已解析的真实文档上覆写 charOffsets 造这个退化情形，段落本身不伪造。
        val document = docOf("aa", "bb").copy(charOffsets = intArrayOf(0, 0))
        assertEquals(2, document.paragraphs.size)
        assertEquals(1, TextAnnotations.paragraphIndexOfOffset(document, 0))
        assertEquals(1, TextAnnotations.paragraphIndexOfOffset(document, 1))
        assertEquals(1, TextAnnotations.paragraphIndexOfOffset(document, 999))
        // 负 offset 先归 0，再按「最后一个 <= 0」落位，结果同上。
        assertEquals(1, TextAnnotations.paragraphIndexOfOffset(document, -1))
    }

    // 10. 十万段的二分与朴素线性实现逐点一致
    @Test
    fun binarySearchMatchesLinearReferenceOnLargeDocument() {
        val count = 100_000
        val paragraphs = ArrayList<TextParagraph>(count)
        repeat(count) { paragraphs.add(TextParagraph("x")) }
        val document = TextDocumentParser.fromParagraphs(paragraphs)
        assertEquals(count, document.paragraphs.size)
        assertEquals(count, document.charCount)

        val targets = ArrayList<Int>()
        targets.add(0)
        targets.add(1)
        targets.add(count - 1)
        targets.add(count)
        targets.add(count + 50)
        val random = Random(2026L)
        repeat(200) { targets.add(random.nextInt(count + 96)) }

        for (target in targets) {
            var expected = 0
            for (index in document.charOffsets.indices) {
                if (document.charOffsets[index] <= target) expected = index else break
            }
            assertEquals("offset=$target", expected, TextAnnotations.paragraphIndexOfOffset(document, target))
        }
    }

    // ---------------------------------------------------------------- anchorOf

    // 11. 段首偏移与长度
    @Test
    fun anchorOfReportsParagraphStartAndLength() {
        val document = docOf("abc", "defg")
        assertEquals(TextAnchor(0, 0, 3), TextAnnotations.anchorOf(document, 0))
        assertEquals(TextAnchor(1, 3, 4), TextAnnotations.anchorOf(document, 1))
    }

    // 12. 纯空白段保留自己的长度（不是 0）
    @Test
    fun anchorOfKeepsWhitespaceParagraphLength() {
        val document = docOf("a", "   ", "b")
        assertEquals(3, document.paragraphs.size)
        val anchor = TextAnnotations.anchorOf(document, 1)
        assertNotNull(anchor)
        assertEquals(1, anchor!!.offset)
        assertEquals(3, anchor.length)
        assertEquals(TextAnchor(1, 1, 3), anchor)
    }

    // 13. 越界下标与空文档
    @Test
    fun anchorOfRejectsOutOfRangeIndexAndEmptyDocument() {
        val document = docOf("abc")
        assertNull(TextAnnotations.anchorOf(document, -1))
        assertNull(TextAnnotations.anchorOf(document, 1))
        assertNull(TextAnnotations.anchorOf(document, Int.MAX_VALUE))
        assertNull(TextAnnotations.anchorOf(TextDocumentParser.parse("", markdown = false), 0))
    }

    // ---------------------------------------------------------------- paragraphOf

    // 14. Text locator 正常定位
    @Test
    fun paragraphOfResolvesTextLocator() {
        val document = docOf("abc", "def", "ghi")
        assertEquals(0, TextAnnotations.paragraphOf(document, annotation(1, ReaderLocator.Text(0, 3))))
        assertEquals(1, TextAnnotations.paragraphOf(document, annotation(2, ReaderLocator.Text(4))))
        assertEquals(2, TextAnnotations.paragraphOf(document, annotation(3, ReaderLocator.Text(6, 0))))
    }

    // 15. 非 Text locator（Scroll / Epub）
    @Test
    fun paragraphOfRejectsNonTextLocator() {
        val document = docOf("abc", "def")
        assertNull(TextAnnotations.paragraphOf(document, annotation(1, ReaderLocator.Scroll(0.5f))))
        assertNull(TextAnnotations.paragraphOf(document, annotation(2, ReaderLocator.Epub("chapter1.xhtml", 0))))
    }

    // 16. locatorError 非空（locator 本身是合法 Text 也不认）
    @Test
    fun paragraphOfRejectsBrokenLocator() {
        val document = docOf("abc", "def")
        val broken = annotation(1, ReaderLocator.Text(3), locatorError = "locator_json 解析失败")
        assertNull(TextAnnotations.paragraphOf(document, broken))
    }

    // 17. 负 offset
    @Test
    fun paragraphOfRejectsNegativeOffset() {
        val document = docOf("abc", "def")
        assertNull(TextAnnotations.paragraphOf(document, annotation(1, ReaderLocator.Text(-1))))
        assertNull(TextAnnotations.paragraphOf(document, annotation(2, ReaderLocator.Text(Int.MIN_VALUE))))
    }

    // ---------------------------------------------------------------- byParagraph

    // 18. 分组、组内顺序、键的首次出现顺序
    @Test
    fun byParagraphGroupsByParagraphAndKeepsInputOrder() {
        val document = docOf("第一段", "第二段", "第三段")
        assertEquals(listOf(0, 3, 6), document.charOffsets.toList())
        val annotations = listOf(
            annotation(1, textAt(0), color = "#111111"),
            annotation(2, textAt(6), type = ReaderAnnotationType.BOOKMARK),
            annotation(3, textAt(3), type = ReaderAnnotationType.NOTE, note = "注解"),
            annotation(4, textAt(1), color = "#222222"),
        )
        val grouped = TextAnnotations.byParagraph(document, annotations)
        // LinkedHashMap 语义：2 号段先出现所以排在 1 号段前面。
        assertEquals(listOf(0, 2, 1), grouped.keys.toList())
        assertEquals(listOf(1L, 4L), grouped[0]!!.map { it.id })
        assertEquals(listOf(3L), grouped[1]!!.map { it.id })
        assertEquals(listOf(2L), grouped[2]!!.map { it.id })
    }

    // 19. 丢弃 locatorError / 非 Text / 负 offset
    @Test
    fun byParagraphSkipsUnusableAnnotations() {
        val document = docOf("abc", "def")
        val annotations = listOf(
            annotation(1, textAt(0)),
            annotation(2, textAt(3), locatorError = "locator_json 解析失败"),
            annotation(3, ReaderLocator.Scroll(0.3f)),
            annotation(4, ReaderLocator.Epub("c1.xhtml", 2)),
            annotation(5, ReaderLocator.Text(-3)),
            annotation(6, textAt(4)),
        )
        val grouped = TextAnnotations.byParagraph(document, annotations)
        assertEquals(listOf(0, 1), grouped.keys.toList())
        assertEquals(listOf(1L), grouped[0]!!.map { it.id })
        assertEquals(listOf(6L), grouped[1]!!.map { it.id })
    }

    // 20. 空文档：paragraphOf 归 0，但 0 越界，全部丢弃
    @Test
    fun byParagraphDropsEverythingWhenDocumentHasNoParagraph() {
        val document = TextDocumentParser.parse("   ", markdown = false)
        assertTrue(document.isEmpty)
        assertEquals(3, document.charCount)
        val grouped = TextAnnotations.byParagraph(
            document,
            listOf(
                annotation(1, textAt(0)),
                annotation(2, textAt(9_999), type = ReaderAnnotationType.BOOKMARK),
            ),
        )
        assertTrue(grouped.isEmpty())
    }

    // 21. 有段落时，远超出末尾的偏移被夹到末段而不是丢弃
    @Test
    fun byParagraphClampsOffsetPastEndIntoLastParagraph() {
        val document = docOf("abc", "def")
        val grouped = TextAnnotations.byParagraph(document, listOf(annotation(1, textAt(10_000))))
        assertEquals(listOf(1), grouped.keys.toList())
        assertEquals(listOf(1L), grouped[1]!!.map { it.id })
    }

    // ---------------------------------------------------------------- hasPaint

    // 22. 空列表不涂
    @Test
    fun hasPaintIsFalseForEmptyList() {
        assertFalse(TextAnnotations.hasPaint(emptyList()))
    }

    // 23. 只有书签不涂（书签带颜色也一样：颜色是高亮的属性，书签只是位置标记）
    @Test
    fun hasPaintIsFalseForBookmarksOnly() {
        val bookmarks = listOf(
            annotation(1, textAt(0), type = ReaderAnnotationType.BOOKMARK),
            annotation(2, textAt(5), type = ReaderAnnotationType.BOOKMARK, color = "#FF0000"),
        )
        assertFalse(TextAnnotations.hasPaint(bookmarks))
    }

    // 24. 只有高亮要涂；与有没有设颜色无关（没颜色时调用方回退默认色）
    @Test
    fun hasPaintIsTrueForHighlightOnly() {
        assertTrue(TextAnnotations.hasPaint(listOf(annotation(1, textAt(0), color = "#FFCC00"))))
        assertTrue(TextAnnotations.hasPaint(listOf(annotation(2, textAt(1), color = null))))
    }

    // 25. 只有笔记要涂
    @Test
    fun hasPaintIsTrueForNoteOnly() {
        val notes = listOf(annotation(1, textAt(0), type = ReaderAnnotationType.NOTE))
        assertTrue(TextAnnotations.hasPaint(notes))
    }

    // 26. 混合列表只要有高亮或笔记就是 true，与顺序无关
    @Test
    fun hasPaintIsTrueForMixedList() {
        val mixed = listOf(
            annotation(1, textAt(0), type = ReaderAnnotationType.BOOKMARK),
            annotation(2, textAt(1), type = ReaderAnnotationType.NOTE, color = "#0000FF"),
            annotation(3, textAt(2), color = "#FFCC00"),
        )
        assertTrue(TextAnnotations.hasPaint(mixed))
        // 反转后书签落到最后，结论不变。
        assertTrue(TextAnnotations.hasPaint(mixed.reversed()))
        // 书签在前、笔记在后：不能因为「第一条是书签」就判成不涂。
        val bookmarkThenNote = listOf(
            annotation(1, textAt(0), type = ReaderAnnotationType.BOOKMARK),
            annotation(2, textAt(1), type = ReaderAnnotationType.NOTE),
        )
        assertTrue(TextAnnotations.hasPaint(bookmarkThenNote))
    }

    // ---------------------------------------------------------------- paintColorOf

    // 27. 高亮优先：笔记排在列表前面也不落到笔记色（本次修复的核心）
    @Test
    fun paintColorOfPrefersHighlightOverEarlierNote() {
        // 先写笔记（列表里靠前）、后点黄色高亮：底色必须是高亮的黄，不是笔记的蓝。
        val noteThenHighlight = listOf(
            annotation(1, textAt(0), type = ReaderAnnotationType.NOTE, color = "#0000FF"),
            annotation(2, textAt(1), color = "#FFCC00"),
        )
        assertEquals("#FFCC00", TextAnnotations.paintColorOf(noteThenHighlight))

        // 同一段经 byParagraph 归组后（书签排最前、笔记在中间）结论一致。
        val document = docOf("abc", "def")
        val grouped = TextAnnotations.byParagraph(
            document,
            listOf(
                annotation(1, textAt(0), type = ReaderAnnotationType.BOOKMARK),
                annotation(2, textAt(1), color = "#FF0000"),
                annotation(3, textAt(2), type = ReaderAnnotationType.NOTE, color = "#00FF00"),
            ),
        )
        assertEquals("#FF0000", TextAnnotations.paintColorOf(grouped[0]!!))
    }

    // 28. 多条高亮取第一条的颜色
    @Test
    fun paintColorOfTakesFirstHighlightAmongHighlights() {
        val twoHighlights = listOf(
            annotation(1, textAt(0), color = "#111111"),
            annotation(2, textAt(1), color = "#222222"),
        )
        assertEquals("#111111", TextAnnotations.paintColorOf(twoHighlights))

        // 笔记夹在两条高亮中间不影响选择：仍取第一条高亮。
        val withNotesAround = listOf(
            annotation(1, textAt(0), type = ReaderAnnotationType.NOTE, color = "#0000FF"),
            annotation(2, textAt(1), color = "#AA0000"),
            annotation(3, textAt(2), type = ReaderAnnotationType.NOTE, color = "#00FF00"),
            annotation(4, textAt(3), color = "#BB0000"),
        )
        assertEquals("#AA0000", TextAnnotations.paintColorOf(withNotesAround))
    }

    // 29. 没有高亮时取第一条笔记的颜色
    @Test
    fun paintColorOfFallsBackToFirstNoteColor() {
        val notes = listOf(
            annotation(1, textAt(0), type = ReaderAnnotationType.BOOKMARK),
            annotation(2, textAt(1), type = ReaderAnnotationType.NOTE, color = "#123456"),
            annotation(3, textAt(2), type = ReaderAnnotationType.NOTE, color = "#654321"),
        )
        assertEquals("#123456", TextAnnotations.paintColorOf(notes))

        // 第一条笔记没设颜色就是 null：不继续往下找一条有颜色的。
        val colorlessFirstNote = listOf(
            annotation(1, textAt(0), type = ReaderAnnotationType.NOTE, color = null),
            annotation(2, textAt(1), type = ReaderAnnotationType.NOTE, color = "#654321"),
        )
        assertNull(TextAnnotations.paintColorOf(colorlessFirstNote))
    }

    // 30. 只有书签时没有底色（书签自带颜色也不参与）
    @Test
    fun paintColorOfIsNullForBookmarksOnly() {
        val onlyBookmarks = listOf(
            annotation(1, textAt(0), type = ReaderAnnotationType.BOOKMARK),
            annotation(2, textAt(3), type = ReaderAnnotationType.BOOKMARK, color = "#FF0000"),
        )
        assertNull(TextAnnotations.paintColorOf(onlyBookmarks))
    }

    // 31. 胜出的高亮没设颜色就是 null：不落到后面（或前面）笔记的颜色
    @Test
    fun paintColorOfReturnsNullWhenWinningHighlightHasNoColor() {
        val highlightFirst = listOf(
            annotation(1, textAt(0), color = null),
            annotation(2, textAt(1), type = ReaderAnnotationType.NOTE, color = "#0000FF"),
        )
        assertNull(TextAnnotations.paintColorOf(highlightFirst))

        // 顺序反过来也一样：高亮优先意味着「高亮的 null」压过笔记的蓝。
        val noteFirst = listOf(
            annotation(1, textAt(0), type = ReaderAnnotationType.NOTE, color = "#0000FF"),
            annotation(2, textAt(1), color = null),
        )
        assertNull(TextAnnotations.paintColorOf(noteFirst))
    }

    // 32. 空列表没有底色
    @Test
    fun paintColorOfIsNullForEmptyList() {
        assertNull(TextAnnotations.paintColorOf(emptyList()))
    }

    // ---------------------------------------------------------------- create

    // 33. 拒绝分支：PDF 类型
    @Test
    fun createRejectsPdfType() {
        val document = docOf("abc")
        assertNull(TextAnnotations.create(bookId, document, 0, ReaderAnnotationType.PDF))
    }

    // 34. 拒绝分支：越界下标
    @Test
    fun createRejectsParagraphIndexOutOfRange() {
        val document = docOf("abc", "def")
        assertNull(TextAnnotations.create(bookId, document, 2, ReaderAnnotationType.HIGHLIGHT))
        assertNull(TextAnnotations.create(bookId, document, 99, ReaderAnnotationType.HIGHLIGHT))
    }

    // 35. 拒绝分支：负下标
    @Test
    fun createRejectsNegativeParagraphIndex() {
        val document = docOf("abc")
        assertNull(TextAnnotations.create(bookId, document, -1, ReaderAnnotationType.HIGHLIGHT))
        assertNull(TextAnnotations.create(bookId, document, Int.MIN_VALUE, ReaderAnnotationType.BOOKMARK))
    }

    // 36. 拒绝分支：空白段
    @Test
    fun createRejectsBlankParagraph() {
        val document = docOf("abc", "   ", "def")
        assertEquals(3, document.paragraphs.size)
        assertTrue(document.paragraphs[1].text.isBlank())
        assertNull(TextAnnotations.create(bookId, document, 1, ReaderAnnotationType.HIGHLIGHT))
    }

    // 37. 正常构造：locator / quote / note / color / 时间戳
    @Test
    fun createBuildsAnnotationOnParagraph() {
        val document = docOf("abc", "defg")
        val created = TextAnnotations.create(
            bookId = bookId,
            document = document,
            paragraphIndex = 1,
            type = ReaderAnnotationType.NOTE,
            color = "#FFCC00",
            note = "这里要改",
            now = 1_700_000_000_000L,
        )
        assertNotNull(created)
        created!!
        assertEquals(0L, created.id) // id 由 store 落库时补
        assertEquals(bookId, created.bookId)
        assertEquals(ReaderAnnotationType.NOTE, created.type)
        assertEquals(ReaderLocator.Text(3, 4), created.locator)
        assertEquals("defg", created.quote)
        assertEquals("这里要改", created.note)
        assertEquals("#FFCC00", created.color)
        assertTrue(created.tags.isEmpty())
        assertNull(created.relatedId)
    }

    // 38. quote 截断
    @Test
    fun createTruncatesLongQuote() {
        assertEquals(200, TextAnnotations.QUOTE_MAX_CHARS)
        val text = "字".repeat(TextAnnotations.QUOTE_MAX_CHARS + 50)
        val created = TextAnnotations.create(bookId, docOf(text), 0, ReaderAnnotationType.HIGHLIGHT)
        assertNotNull(created)
        created!!
        assertEquals(TextAnnotations.QUOTE_MAX_CHARS + 1, created.quote.length)
        assertEquals(text.substring(0, TextAnnotations.QUOTE_MAX_CHARS) + "\u2026", created.quote)
        // quote 截断了，locator 仍指向整段全文。
        assertEquals(ReaderLocator.Text(0, text.length), created.locator)
    }

    // 39. 正好等于上限时不截断
    @Test
    fun createKeepsQuoteWithinLimitUntouched() {
        val text = "a".repeat(TextAnnotations.QUOTE_MAX_CHARS)
        val created = TextAnnotations.create(bookId, docOf(text), 0, ReaderAnnotationType.HIGHLIGHT)
        assertNotNull(created)
        assertEquals(text, created!!.quote)
    }

    // 40. 截断点劈在代理对中间：回退一位，不产出半个 emoji
    @Test
    fun createDoesNotSplitSurrogatePairAtTruncationBoundary() {
        // 199 个 'a' 之后是 emoji：第 200 个码元（下标 199）是高位代理，截断点正好劈开这一对。
        val text = "a".repeat(199) + emoji + "b".repeat(10)
        assertEquals(211, text.length)
        val created = TextAnnotations.create(bookId, docOf(text), 0, ReaderAnnotationType.HIGHLIGHT)
        assertNotNull(created)
        created!!
        assertEquals("a".repeat(199) + "\u2026", created.quote)
        assertEquals(200, created.quote.length)
        assertFalse(hasLoneSurrogate(created.quote))
        assertEquals(ReaderLocator.Text(0, 211), created.locator)
    }

    // 41. 代理对恰好结束在边界上：不需要回退
    @Test
    fun createKeepsSurrogatePairThatEndsExactlyAtLimit() {
        // 198 个 'a' + emoji = 正好 200 个码元，边界落在 emoji 之后。
        val text = "a".repeat(198) + emoji + "b".repeat(10)
        assertEquals(210, text.length)
        val created = TextAnnotations.create(bookId, docOf(text), 0, ReaderAnnotationType.HIGHLIGHT)
        assertNotNull(created)
        created!!
        assertEquals("a".repeat(198) + emoji + "\u2026", created.quote)
        assertEquals(TextAnnotations.QUOTE_MAX_CHARS + 1, created.quote.length)
        assertFalse(hasLoneSurrogate(created.quote))
    }

    // 42. 书签的 length 为 0，quote 照填
    @Test
    fun createTreatsBookmarkAsZeroLengthPoint() {
        val document = docOf("abc", "defg")
        val created = TextAnnotations.create(bookId, document, 1, ReaderAnnotationType.BOOKMARK)
        assertNotNull(created)
        created!!
        assertEquals(ReaderLocator.Text(3, 0), created.locator)
        assertEquals("defg", created.quote)
    }

    // 43. now 透传；传 0 就留 0 给 store 补
    @Test
    fun createPassesNowThroughAndDefaultsToZero() {
        val document = docOf("abc")
        val stamped = TextAnnotations.create(bookId, document, 0, ReaderAnnotationType.HIGHLIGHT, now = 42L)
        assertNotNull(stamped)
        assertEquals(42L, stamped!!.createdAt)
        assertEquals(42L, stamped.updatedAt)

        val unstamped = TextAnnotations.create(bookId, document, 0, ReaderAnnotationType.HIGHLIGHT)
        assertNotNull(unstamped)
        assertEquals(0L, unstamped!!.createdAt)
        assertEquals(0L, unstamped.updatedAt)
    }

    // 44. 普通段落的 offset / length 来自 anchorOf：offset 是原文行首、length 是段文本的码元数
    @Test
    fun createTakesOffsetAndLengthFromAnchorOf() {
        // 前导空行把 0 号段推到原文偏移 2：offset 若取「前面各段文本长度之和」会得到 0。
        val document = TextDocumentParser.parse("\n\nabc\n\n" + emoji + "beta\n", markdown = false)
        assertEquals(2, document.paragraphs.size)
        assertEquals(2, document.charOffsets[0])
        assertEquals(7, document.charOffsets[1])

        val created = TextAnnotations.create(bookId, document, 1, ReaderAnnotationType.HIGHLIGHT)
        assertNotNull(created)
        created!!
        val locator = created.locator as ReaderLocator.Text
        assertEquals(7, locator.offset)
        // 段文本「emoji + beta」在 UTF-16 里是 6 个码元（码点只有 5 个）。
        assertEquals(6, locator.length)
        assertEquals(ReaderLocator.Text(7, 6), created.locator)
        assertEquals(emoji + "beta", created.quote)

        // 与 anchorOf 同一口径：create 不能再有另一套位置算法。
        val anchor = TextAnnotations.anchorOf(document, 1)
        assertNotNull(anchor)
        assertEquals(anchor!!.offset, locator.offset)
        assertEquals(anchor.length, locator.length)
    }

    // 45. 书签取 anchorOf 的偏移但 length 固定 0，quote 仍按上限截断照填
    @Test
    fun createBookmarkUsesAnchorOffsetWithZeroLengthAndTruncatedQuote() {
        val longText = "字".repeat(TextAnnotations.QUOTE_MAX_CHARS + 30)
        val document = docOf("abc", longText)
        assertEquals(3, document.charOffsets[1])

        val created = TextAnnotations.create(bookId, document, 1, ReaderAnnotationType.BOOKMARK)
        assertNotNull(created)
        created!!
        assertEquals(ReaderLocator.Text(3, 0), created.locator)
        assertEquals(TextAnnotations.QUOTE_MAX_CHARS + 1, created.quote.length)
        assertEquals("字".repeat(TextAnnotations.QUOTE_MAX_CHARS) + "\u2026", created.quote)
    }
}