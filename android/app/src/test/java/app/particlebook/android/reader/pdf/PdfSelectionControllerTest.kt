package app.particlebook.android.reader.pdf

import app.particlebook.android.reader.ReaderLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** task-153：PDF 选区的命中、同页扩展、反向拖动、文本/矩形/locator 转换。 */
class PdfSelectionControllerTest {

    @Test
    fun beginSelectsHitCharacterAndBuildsSelection() {
        val source = sourceWithTwoLines()
        val controller = PdfSelectionController(source)

        val selection = controller.begin(0, 0.1f, 0.1f)

        assertNotNull(selection)
        val active = requireNotNull(selection)
        assertEquals(0, active.startChar)
        assertEquals(1, active.endChar)
        assertEquals("H", active.text)
        assertEquals(1, active.rects.size)
        assertTrue(active.hasText)
        assertEquals(1, active.length)
    }

    @Test
    fun extendForwardAcrossLinesKeepsAnchorAndMergesRects() {
        val controller = PdfSelectionController(sourceWithTwoLines())
        controller.begin(0, 0.1f, 0.1f)

        val line1 = requireNotNull(controller.extend(0, xFor(4), 0.1f))
        assertEquals(0, line1.startChar)
        assertEquals(5, line1.endChar)
        assertEquals("Hello", line1.text)
        assertEquals(1, line1.rects.size)

        val line2 = requireNotNull(controller.extend(0, xFor(7), 0.25f))
        assertEquals(0, line2.startChar)
        assertEquals(8, line2.endChar)
        assertEquals("HelloWor", line2.text)
        assertEquals(2, line2.rects.size)
    }

    @Test
    fun reverseDragNormalizesStartAndEnd() {
        val controller = PdfSelectionController(sourceWithTwoLines())
        controller.begin(0, xFor(6), 0.25f)

        val selection = requireNotNull(controller.extend(0, xFor(2), 0.1f))

        assertEquals(2, selection.startChar)
        assertEquals(7, selection.endChar)
        assertEquals("lloWo", selection.text)
        assertTrue(selection.endChar > selection.startChar)
    }

    @Test
    fun extendOnAnotherPageIsIgnored() {
        val controller = PdfSelectionController(sourceWithTwoLines())
        controller.begin(0, 0.1f, 0.1f)

        val selection = requireNotNull(controller.extend(1, 0.1f, 0.1f))

        assertEquals(0, selection.page)
        assertEquals(0, selection.startChar)
        assertEquals(1, selection.endChar)
    }

    @Test
    fun extendClampsToLastCharacterAndKeepsSamePage() {
        val controller = PdfSelectionController(sourceWithTwoLines())
        controller.begin(0, xFor(9), 0.25f)

        val selection = requireNotNull(controller.extend(0, 0.99f, 0.99f))

        assertEquals(0, selection.page)
        assertEquals(9, selection.startChar)
        assertEquals(10, selection.endChar)
        assertEquals("d", selection.text)
    }

    @Test
    fun missClearsSelectionAndWhitespaceHasNoSelection() {
        val controller = PdfSelectionController(sourceWithTwoLines())
        assertNull(controller.begin(0, -0.2f, 0.5f))
        assertNull(controller.selection)
        assertNull(controller.getSelection())
    }

    @Test
    fun textUsesHalfOpenCharacterRange() {
        val controller = PdfSelectionController(sourceWithTwoLines())

        assertEquals("Hello", controller.text(0, 0, 5))
        assertEquals("World", controller.text(0, 5, 10))
        assertEquals("", controller.text(0, 5, 5))
        assertEquals("", controller.text(0, 10, 12))
    }

    @Test
    fun locatorUsesBoundingBoxAndFallsBackToSinglePoint() {
        val controller = PdfSelectionController(sourceWithTwoLines())
        controller.begin(0, 0.1f, 0.1f)
        val selection = requireNotNull(controller.extend(0, xFor(4), 0.1f))

        val locator = controller.toLocator(selection.page, selection.rects)
        assertEquals(0, locator.page)
        assertEquals(0.1f, locator.x, 0.0001f)
        assertEquals(0.1f, locator.y, 0.0001f)
        assertNotNull(locator.rect)
        assertEquals(0.1f, locator.rect!!.left, 0.0001f)
        assertEquals(0.4f, locator.rect!!.right, 0.0001f)
        assertEquals(0.2f, locator.rect!!.bottom, 0.0001f)

        val empty = controller.toLocator(0, emptyList())
        assertEquals(ReaderLocator.Pdf(0), empty)
    }

    @Test
    fun getSelectionReturnsReaderSelectionAndClearResets() {
        val controller = PdfSelectionController(sourceWithTwoLines())
        controller.begin(0, 0.1f, 0.1f)

        val readerSelection = controller.getSelection()
        assertNotNull(readerSelection)
        val active = requireNotNull(readerSelection)
        assertEquals("H", active.text)
        assertEquals(1, active.rects.size)
        assertTrue(active.locator is ReaderLocator.Pdf)

        controller.clear()
        assertNull(controller.selection)
        assertNull(controller.getSelection())
    }

    private fun sourceWithTwoLines(): FakeDocumentTextSource {
        val chars = ArrayList<PdfChar>()
        val first = "Hello"
        val second = "World"
        first.forEachIndexed { index, char ->
            chars += PdfChar(index, char.toString(), rect(0.1f + index * 0.06f, 0.1f, 0.06f, 0.1f))
        }
        second.forEachIndexed { index, char ->
            chars += PdfChar(5 + index, char.toString(), rect(0.1f + index * 0.06f, 0.25f, 0.06f, 0.1f))
        }
        val page = PdfPageText(
            pageIndex = 0,
            pageWidthPoints = 612,
            pageHeightPoints = 792,
            rotationDegrees = 0,
            chars = chars,
            text = "Hello\nWorld",
        )
        return FakeDocumentTextSource(listOf(page))
    }

    private fun xFor(index: Int): Float = 0.1f + (index % 5) * 0.06f + 0.01f

    private fun rect(left: Float, top: Float, width: Float, height: Float): PdfNormalizedRect =
        PdfNormalizedRect(left, top, width, height)
}

/** 纯 JVM 文本源，按 P0 的命中/合并规则模拟控制器；不依赖 PDFium。 */
class FakeDocumentTextSource(
    private val pages: List<PdfPageText>,
) : PdfDocumentTextSource {
    var closeCount = 0

    override val pageCount: Int get() = pages.size

    override fun loadPage(page: Int): PdfPageText {
        if (page !in pages.indices) throw IllegalArgumentException("page " + page)
        return pages[page]
    }

    override fun charAt(page: Int, xNorm: Float, yNorm: Float): PdfChar? =
        PdfTextHitTest.hit(loadPage(page).chars, xNorm, yNorm)

    override fun selectionRects(
        page: Int,
        startChar: Int,
        endChar: Int,
    ): List<PdfNormalizedRect> = PdfSelectionGeometry.rects(loadPage(page).chars, startChar, endChar)

    override fun close() {
        closeCount++
    }
}