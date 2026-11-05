package app.particlebook.android.reader.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable

/** task-144：PDF 文本层窗口、LRU、命中边界与选区合并。 */
class PdfTextLayerControllerTest {

    @Test
    fun preloadVisibleKeepsOnlyNeighbourPagesAndReleasesNativePages() {
        val fixture = fixture(pageCount = 5)
        val window = fixture.controller.preloadVisible(2, radius = 1)

        assertEquals(1..3, window.window)
        assertEquals(listOf(1, 2, 3), window.pages.map { it.pageIndex })
        assertTrue(window.allLoaded)
        assertEquals(listOf(1, 2, 3), fixture.controller.cachedPageIndexes())
        assertEquals(3, fixture.source.cachedPageCount())
        assertEquals(listOf(1, 2, 3), fixture.doc.opened)

        val next = fixture.controller.preloadVisible(4, radius = 1)
        assertEquals(3..4, next.window)
        assertEquals(listOf(3, 4), next.pages.map { it.pageIndex })
        assertEquals(listOf(3, 4), fixture.controller.cachedPageIndexes())
        assertEquals(2, fixture.source.cachedPageCount())
        assertEquals(listOf(1, 2, 3, 4), fixture.doc.opened)

        assertEquals(listOf(3, 4), fixture.controller.releaseOutside(0..0))
        assertEquals(emptyList<Int>(), fixture.controller.cachedPageIndexes())
        assertEquals(0, fixture.source.cachedPageCount())
    }

    @Test
    fun preloadVisibleClampsWindowAtDocumentEdges() {
        val first = fixture(pageCount = 5).controller.preloadVisible(0, radius = 2)
        assertEquals(0..2, first.window)
        assertEquals(listOf(0, 1, 2), first.pages.map { it.pageIndex })

        val last = fixture(pageCount = 5).controller.preloadVisible(4, radius = 2)
        assertEquals(2..4, last.window)
        assertEquals(listOf(2, 3, 4), last.pages.map { it.pageIndex })
    }

    @Test
    fun loadPageHitsControllerCacheAndEvictsOutsideWindow() {
        val fixture = fixture(pageCount = 4, maxPages = 2)
        fixture.controller.preloadVisible(1, radius = 0)
        val first = fixture.controller.loadPage(0)
        val second = fixture.controller.loadPage(0)
        assertTrue(first === second)
        assertEquals(listOf(1, 0), fixture.controller.cachedPageIndexes())
        assertEquals(2, fixture.doc.opened.size)

        fixture.controller.loadPage(2)
        assertEquals(listOf(1, 2), fixture.controller.cachedPageIndexes())
        // PdfTextSource 自己只有 maxPages 个 native 槽位，读第 3 页时会先淘汰
        // 第 1 页；控制器仍持有第 1 页的纯文本快照，不影响命中。
        assertEquals(1, fixture.source.cachedPageCount())
    }

    @Test
    fun visibleWindowSurvivesTightByteBudgetAndCanBeReleasedExplicitly() {
        val fixture = fixture(pageCount = 3, maxPages = 5, maxBytes = 1L)
        val window = fixture.controller.preloadVisible(1, radius = 1)

        assertEquals(3, window.pages.size)
        assertEquals(listOf(0, 1, 2), fixture.controller.cachedPageIndexes())

        assertEquals(listOf(0, 2), fixture.controller.releaseOutside(1..1))
        assertEquals(listOf(1), fixture.controller.cachedPageIndexes())
    }

    @Test
    fun byteBudgetEvictsLruPageWhileNoWindowIsProtected() {
        val fixture = fixture(pageCount = 3, maxPages = 5, maxBytes = 300L)
        fixture.controller.loadPage(0)
        fixture.controller.loadPage(1)

        assertEquals(listOf(1), fixture.controller.cachedPageIndexes())
        assertTrue(fixture.controller.loadPage(1) === fixture.controller.loadPage(1))
        assertEquals(1, fixture.source.cachedPageCount())
    }

    @Test
    fun charAtUsesNormalizedTopLeftCoordinatesAndInclusiveEdges() {
        val fixture = fixture(
            pageCount = 1,
            pageFactory = { pageIndex ->
                fakePage(
                    pageIndex,
                    listOf(
                        pdfBox(0.1f, 0.1f, 0.2f, 0.2f),
                        pdfBox(0.3f, 0.1f, 0.15f, 0.2f),
                    ),
                )
            },
        )

        assertEquals(0, fixture.controller.charAt(0, 0.1f, 0.1f)?.index)
        assertEquals(1, fixture.controller.charAt(0, 0.3f, 0.1f)?.index)
        assertEquals(1, fixture.controller.charAt(0, 0.45f, 0.3f)?.index)
        assertNull(fixture.controller.charAt(0, 0.46f, 0.2f))
        assertNull(fixture.controller.charAt(0, -0.01f, 0.2f))
        assertNull(fixture.controller.charAt(0, Float.NaN, 0.2f))
    }

    @Test
    fun charAtUsesRotationNormalizedRectangle() {
        val fixture = fixture(
            pageCount = 1,
            pageFactory = { pageIndex ->
                fakePage(
                    pageIndex = pageIndex,
                    boxes = listOf(PdfBox(0f, 90f, 10f, 100f)),
                    rotationDegrees = 90,
                )
            },
        )

        val hit = fixture.controller.charAt(0, 0.95f, 0.05f)
        assertEquals(0, hit?.index)
        assertEquals(0.9f, hit!!.rect!!.left, 0.0001f)
        assertEquals(0f, hit.rect!!.top, 0.0001f)
        assertNull(fixture.controller.charAt(0, 0.5f, 0.05f))
    }

    @Test
    fun selectionRectsMergesSameLineAndSplitsWideGaps() {
        val fixture = fixture(
            pageCount = 1,
            pageFactory = { pageIndex ->
                fakePage(
                    pageIndex,
                    listOf(
                        pdfBox(0.1f, 0.1f, 0.03f, 0.1f),
                        pdfBox(0.15f, 0.1f, 0.03f, 0.1f),
                        pdfBox(0.6f, 0.1f, 0.03f, 0.1f),
                    ),
                )
            },
        )

        val rects = fixture.controller.selectionRects(0, 0, 3)
        assertEquals(2, rects.size)
        assertEquals(0.1f, rects[0].left, 0.0001f)
        assertEquals(0.18f, rects[0].right, 0.0001f)
        assertEquals(0.1f, rects[0].top, 0.0001f)
        assertEquals(0.1f, rects[0].height, 0.0001f)
        assertEquals(0.6f, rects[1].left, 0.0001f)

        val single = fixture.controller.selectionRects(0, 1, 2)
        assertEquals(1, single.size)
        assertEquals(0.15f, single[0].left, 0.0001f)
        assertTrue(fixture.controller.selectionRects(0, 2, 2).isEmpty())
    }

    @Test
    fun preloadCollectsFailedPageWithoutBlockingOthers() {
        val fixture = fixture(
            pageCount = 3,
            pageFactory = { pageIndex ->
                if (pageIndex == 1) throw IllegalStateException("boom")
                fakePage(pageIndex, listOf(pdfBox(0.1f, 0.1f, 0.1f, 0.1f)))
            },
        )

        val window = fixture.controller.preloadVisible(1, radius = 1)
        assertEquals(0..2, window.window)
        assertEquals(listOf(0, 2), window.pages.map { it.pageIndex })
        assertFalse(window.allLoaded)
        assertTrue(window.failures.getValue(1).contains("boom"))
        assertEquals(listOf(0, 2), fixture.controller.cachedPageIndexes())
    }

    @Test
    fun closeIsIdempotentAndClosesRendererAndDocument() {
        val fixture = fixture(pageCount = 1)
        fixture.controller.loadPage(0)
        fixture.controller.close()
        fixture.controller.close()

        assertEquals(1, fixture.renderer.closeCount)
        assertEquals(1, fixture.doc.closeCount)
        assertEquals(0, fixture.controller.cachedPageCount())
        assertThrows(IllegalStateException::class.java) { fixture.controller.loadPage(0) }
    }

    @Test
    fun emptyRectIsNotHitAndCannotBeSelected() {
        val fixture = fixture(
            pageCount = 1,
            pageFactory = { pageIndex -> fakePage(pageIndex, listOf(null)) },
        )
        assertNull(fixture.controller.charAt(0, 0.5f, 0.5f))
        assertTrue(fixture.controller.selectionRects(0, 0, 1).isEmpty())
    }

    private fun fixture(
        pageCount: Int,
        maxPages: Int = 5,
        maxBytes: Long = PdfTextLayerController.DEFAULT_MAX_BYTES,
        pageFactory: (Int) -> PdfTextPageHandle = { index ->
            fakePage(index, listOf(pdfBox(0.1f, 0.1f, 0.1f, 0.1f)))
        },
    ): Fixture {
        val doc = FakeDocument(pageCount, pageFactory)
        val open = PdfTextSource.open({ doc }, cacheLimit = maxPages)
        assertTrue("PdfTextSource 打开失败：" + open, open is PdfTextOpenResult.Success)
        val source = (open as PdfTextOpenResult.Success).source
        val renderer = FakeRenderer()
        val controller = PdfTextLayerController(
            source = source,
            rendererHandle = renderer,
            pageCount = pageCount,
            maxPages = maxPages,
            maxBytes = maxBytes,
        )
        return Fixture(doc, source, renderer, controller)
    }

    private fun pdfBox(left: Float, top: Float, width: Float, height: Float): PdfBox {
        val l = left * 100f
        val r = (left + width) * 100f
        val t = 100f - top * 100f
        val b = t - height * 100f
        return PdfBox(l, b, r, t)
    }

    private fun fakePage(
        pageIndex: Int,
        boxes: List<PdfBox?>,
        rotationDegrees: Int = 0,
    ): PdfTextPageHandle {
        return FakePage(pageIndex, boxes, rotationDegrees)
    }

    private class Fixture(
        val doc: FakeDocument,
        val source: PdfTextSource,
        val renderer: FakeRenderer,
        val controller: PdfTextLayerController,
    )

    private class FakeRenderer : Closeable {
        var closeCount = 0
        override fun close() {
            closeCount++
        }
    }

    private class FakeDocument(
        override val pageCount: Int,
        private val pageFactory: (Int) -> PdfTextPageHandle,
    ) : PdfTextDocumentHandle {
        val opened = ArrayList<Int>()
        var closeCount = 0

        override fun openPage(pageIndex: Int): PdfTextPageHandle {
            opened.add(pageIndex)
            return pageFactory(pageIndex)
        }

        override fun close() {
            closeCount++
        }
    }

    private class FakePage(
        private val pageIndex: Int,
        private val boxes: List<PdfBox?>,
        override val rotationDegrees: Int,
    ) : PdfTextPageHandle {
        override val widthPoints: Int = 100
        override val heightPoints: Int = 100
        override val cropBox: PdfBox? = null
        var closed = false

        override fun openTextPage(): PdfTextTextHandle = FakeText(boxes)

        override fun close() {
            closed = true
        }
    }

    private class FakeText(
        private val boxes: List<PdfBox?>,
    ) : PdfTextTextHandle {
        override val charCount: Int get() = boxes.size

        override fun charAt(index: Int): Char = 'a'

        override fun charBox(index: Int): PdfBox? = boxes[index]

        override fun text(): String = "a".repeat(boxes.size)

        override fun close() {
        }
    }
}
