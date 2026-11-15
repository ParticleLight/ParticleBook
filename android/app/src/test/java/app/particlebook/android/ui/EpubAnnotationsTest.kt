package app.particlebook.android.ui

import app.particlebook.android.reader.ReaderAnnotation
import app.particlebook.android.reader.ReaderAnnotationType
import app.particlebook.android.reader.ReaderLocator
import app.particlebook.android.reader.epub.EpubSelection
import app.particlebook.android.reader.epub.layout.EpubChapterLayout
import app.particlebook.android.reader.epub.layout.EpubLayoutConfig
import app.particlebook.android.reader.epub.layout.Page
import app.particlebook.android.reader.epub.layout.PageSlice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 标注 UI 的纯 JVM 逻辑：选区落库、按页过滤、颜色映射、note 更新。 */
class EpubAnnotationsTest {

    private fun selection(
        chapterHref: String = "ch1.xhtml",
        blockIndex: Int = 0,
        startChar: Int = 2,
        endChar: Int = 6,
        text: String = "cdef",
        pageIndex: Int = 0,
    ): EpubSelection = EpubSelection(
        pageIndex = pageIndex,
        text = text,
        start = ReaderLocator.Epub(chapterHref, blockIndex, startChar),
        end = ReaderLocator.Epub(chapterHref, blockIndex, endChar),
        startOffsetInPage = startChar,
        endOffsetInPage = endChar,
    )

    private fun annotation(
        type: ReaderAnnotationType = ReaderAnnotationType.HIGHLIGHT,
        chapterHref: String = "ch1.xhtml",
        blockIndex: Int = 0,
        charOffset: Int = 0,
        length: Int = 2,
        color: String? = null,
        quote: String = "ab",
        note: String = "",
        id: Long = 0,
        createdAt: Long = 0,
    ): ReaderAnnotation = ReaderAnnotation(
        id = id,
        bookId = 7L,
        type = type,
        locator = ReaderLocator.Epub(chapterHref, blockIndex, charOffset, length),
        quote = quote,
        note = note,
        color = color,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun layout(href: String = "ch1.xhtml#top", vararg blockIndices: Int): EpubChapterLayout =
        EpubChapterLayout(
            chapterHref = href,
            chapterTitle = null,
            config = EpubLayoutConfig(viewportWidthPx = 100, viewportHeightPx = 100),
            measuredBlocks = emptyList(),
            pages = listOf(
                Page(
                    index = 0,
                    slices = blockIndices.map { PageSlice(it, 0, 1, 0, 10) },
                    usedHeightPx = 10,
                ),
            ),
        )

    @Test
    fun annotationFromSelectionStoresRangeAsLocator() {
        val now = 1234L
        val result = annotationFromSelection(
            bookId = 42L,
            type = ReaderAnnotationType.HIGHLIGHT,
            selection = selection(startChar = 2, endChar = 6, text = "cdef"),
            color = "green",
            note = "",
            now = now,
        )

        requireNotNull(result)
        assertEquals(42L, result.bookId)
        assertEquals(ReaderAnnotationType.HIGHLIGHT, result.type)
        assertEquals("cdef", result.quote)
        assertEquals("green", result.color)
        assertEquals(now, result.createdAt)
        assertEquals(now, result.updatedAt)
        val locator = result.locator as ReaderLocator.Epub
        assertEquals("ch1.xhtml", locator.chapterHref)
        assertEquals(0, locator.blockIndex)
        assertEquals(2, locator.charOffset)
        assertEquals(4, locator.length)
    }

    @Test
    fun annotationFromSelectionRejectsCrossBlockOrEmptyRange() {
        assertNull(
            annotationFromSelection(
                1L,
                ReaderAnnotationType.HIGHLIGHT,
                selection(blockIndex = 0).copy(end = ReaderLocator.Epub("ch1.xhtml", 1, 6)),
                null,
                "",
                1L,
            ),
        )
        assertNull(
            annotationFromSelection(
                1L,
                ReaderAnnotationType.HIGHLIGHT,
                selection(blockIndex = 0).copy(end = ReaderLocator.Epub("ch2.xhtml", 0, 6)),
                null,
                "",
                1L,
            ),
        )
        assertNull(
            annotationFromSelection(
                1L,
                ReaderAnnotationType.HIGHLIGHT,
                selection(startChar = 4, endChar = 4, text = ""),
                null,
                "",
                1L,
            ),
        )
        assertNull(
            annotationFromSelection(
                1L,
                ReaderAnnotationType.NOTE,
                selection(startChar = 4, text = ""),
                null,
                "note",
                1L,
            ),
        )
    }

    @Test
    fun annotationWithNoteKeepsIdentityAndCreatedAt() {
        val original = annotation(
            type = ReaderAnnotationType.NOTE,
            id = 7L,
            color = "pink",
            quote = "quote",
            note = "old",
            createdAt = 100L,
        ).copy(bookId = 9L)

        val updated = annotationWithNote(original, "新的想法", 200L)

        assertEquals(7L, updated.id)
        assertEquals(9L, updated.bookId)
        assertEquals(ReaderAnnotationType.NOTE, updated.type)
        assertEquals(original.locator, updated.locator)
        assertEquals("quote", updated.quote)
        assertEquals("pink", updated.color)
        assertEquals("新的想法", updated.note)
        assertEquals(100L, updated.createdAt)
        assertEquals(200L, updated.updatedAt)
    }

    @Test
    fun annotationWithNoteFillsMissingCreatedAt() {
        val updated = annotationWithNote(annotation(note = "old"), "new", 300L)
        assertEquals(300L, updated.createdAt)
        assertEquals(300L, updated.updatedAt)
    }

    @Test
    fun annotationsOnPageFiltersByChapterPageAndType() {
        val page = layout("ch1.xhtml#top", 0, 2)
        val annotations = listOf(
            annotation(
                type = ReaderAnnotationType.HIGHLIGHT,
                chapterHref = "ch1.xhtml",
                blockIndex = 0,
                charOffset = 2,
                length = 3,
                color = "green",
            ),
            annotation(
                type = ReaderAnnotationType.NOTE,
                chapterHref = "ch1.xhtml#frag",
                blockIndex = 2,
                charOffset = 0,
                length = 2,
                color = null,
            ),
            // 其它块不在当前页
            annotation(blockIndex = 1, charOffset = 0, length = 2),
            // 其它章节
            annotation(chapterHref = "ch2.xhtml", blockIndex = 0, charOffset = 0, length = 2),
            // 书签不是高亮
            annotation(type = ReaderAnnotationType.BOOKMARK, blockIndex = 0, length = 2),
            // 零长度不画
            annotation(blockIndex = 0, charOffset = 1, length = 0),
            // locator_json 损坏的占位行不画，也不能跳转
            annotation(blockIndex = 0, charOffset = 1, length = 2)
                .copy(locatorError = "bad locator"),
        )

        val highlights = annotationsOnPage(annotations, page, 0)

        assertEquals(2, highlights.size)
        assertEquals(0, highlights[0].blockIndex)
        assertEquals(2, highlights[0].startChar)
        assertEquals(5, highlights[0].endChar)
        assertEquals(EpubAnnotationPalette.byKey("green").argb, highlights[0].colorArgb)
        assertEquals(2, highlights[1].blockIndex)
        assertEquals(EpubAnnotationPalette.default.argb, highlights[1].colorArgb)
    }

    @Test
    fun annotationsOnPageReturnsEmptyForMissingPage() {
        val page = layout("ch1.xhtml", 0)
        assertTrue(annotationsOnPage(listOf(annotation()), page, 3).isEmpty())
        assertTrue(annotationsOnPage(emptyList(), page, 0).isEmpty())
    }

    @Test
    fun paletteHasFourDistinctTranslucentColorsAndYellowFallback() {
        val colors = EpubAnnotationPalette.colors
        assertEquals(4, colors.size)
        assertEquals(colors.size, colors.map { it.key }.toSet().size)
        assertEquals(listOf("yellow", "green", "blue", "pink"), colors.map { it.key })
        assertTrue(colors.all { (it.argb ushr 24) == 0x55 })
        assertEquals(EpubAnnotationPalette.default, EpubAnnotationPalette.byKey(null))
        assertEquals(EpubAnnotationPalette.default, EpubAnnotationPalette.byKey("unknown"))
        assertEquals("blue", EpubAnnotationPalette.note.key)
        assertEquals(EpubAnnotationPalette.note.argb, EpubAnnotationPalette.argbFor("blue"))
        assertEquals(EpubAnnotationPalette.default.argb, EpubAnnotationPalette.argbFor(null))
    }

    @Test
    fun annotationTypeLabelsCoverFrozenTypes() {
        assertEquals("高亮", annotationTypeLabel(ReaderAnnotationType.HIGHLIGHT))
        assertEquals("笔记", annotationTypeLabel(ReaderAnnotationType.NOTE))
        assertEquals("书签", annotationTypeLabel(ReaderAnnotationType.BOOKMARK))
        assertEquals("PDF 标注", annotationTypeLabel(ReaderAnnotationType.PDF))
    }
}