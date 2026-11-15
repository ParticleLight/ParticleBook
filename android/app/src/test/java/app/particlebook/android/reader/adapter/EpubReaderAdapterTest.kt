package app.particlebook.android.reader.adapter

import app.particlebook.android.reader.AnnotationStore
import app.particlebook.android.reader.ReaderAnnotation
import app.particlebook.android.reader.ReaderAnnotationType
import app.particlebook.android.reader.ReaderFormat
import app.particlebook.android.reader.ReaderLocator
import app.particlebook.android.reader.epub.EpubBlock
import app.particlebook.android.reader.epub.EpubReaderController
import app.particlebook.android.reader.epub.layout.EpubBlockMeasurer
import app.particlebook.android.reader.epub.layout.EpubLayoutConfig
import app.particlebook.android.reader.epub.layout.MeasuredBlock
import app.particlebook.android.reader.epub.EpubCharLineMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Test
import java.io.File
import kotlin.math.ceil
import kotlin.math.max

class EpubReaderAdapterTest {

    private val config = EpubLayoutConfig(viewportWidthPx = 400, viewportHeightPx = 100)

    @Test
    fun locatorRoundTripsAndReportsFormat() {
        val controller = openSeed()
        try {
            val adapter = EpubReaderAdapter(controller, config)
            assertEquals(42L, adapter.bookId)
            assertEquals(ReaderFormat.EPUB, adapter.format)

            controller.openChapter(0)
            controller.layoutChapter(config)
            val locator = adapter.getLocator()
            assertTrue(locator is ReaderLocator.Epub)
            assertEquals(controller.book.spine[0].href, (locator as ReaderLocator.Epub).chapterHref)
            assertTrue(adapter.goTo(locator))
            assertEquals(0, controller.currentChapterIndex)
            assertEquals(0, controller.currentPageIndex)
        } finally {
            controller.close()
        }
    }

    @Test
    fun goToCrossesChaptersAndRejectsInvalidLocators() {
        val controller = openSeed()
        try {
            val adapter = EpubReaderAdapter(controller, config)
            val targetHref = controller.book.spine[1].href
            assertTrue(adapter.goTo(ReaderLocator.Epub(targetHref, 0, 0)))
            assertEquals(1, controller.currentChapterIndex)
            assertEquals(targetHref, (adapter.getLocator() as ReaderLocator.Epub).chapterHref)

            assertFalse(adapter.goTo(ReaderLocator.Pdf(page = 1)))
            assertFalse(adapter.goTo(ReaderLocator.Epub("OEBPS/Text/missing.xhtml", 0, 0)))
        } finally {
            controller.close()
        }
    }

    @Test
    fun searchFindsLocatorsWithoutMovingCurrentPosition() {
        val controller = openSeed()
        try {
            val adapter = EpubReaderAdapter(controller, config)
            controller.openChapter(0)
            controller.layoutChapter(config)
            val chapterBefore = controller.currentChapterIndex
            val pageBefore = controller.currentPageIndex

            val hits = adapter.search("河", limit = 5)
            assertTrue(hits.isNotEmpty())
            assertTrue(hits.size <= 5)
            val first = hits.first()
            assertTrue(first.locator is ReaderLocator.Epub)
            assertTrue(first.snippet.contains("河"))
            assertEquals(chapterBefore, controller.currentChapterIndex)
            assertEquals(pageBefore, controller.currentPageIndex)

            assertTrue(adapter.search("").isEmpty())
            assertTrue(adapter.search("不存在的检索词").isEmpty())
            assertTrue(adapter.search("河", limit = 0).isEmpty())
        } finally {
            controller.close()
        }
    }

    @Test
    fun getTextModelTruncatesAndOpensAnotherChapter() {
        val controller = openSeed()
        try {
            val adapter = EpubReaderAdapter(controller, config)
            controller.openChapter(0)
            controller.layoutChapter(config)
            val locator = adapter.getLocator()

            val short = adapter.getTextModel(locator, maxChars = 5)
            assertNotNull(short)
            assertEquals(5, short!!.text.length)
            assertTrue(short.truncated)

            val full = adapter.getTextModel(locator, maxChars = 100_000)
            assertNotNull(full)
            assertFalse(full!!.truncated)
            assertTrue(full.text.isNotEmpty())

            val otherHref = controller.book.spine[2].href
            val cross = adapter.getTextModel(ReaderLocator.Epub(otherHref, 0, 0), maxChars = 100_000)
            assertNotNull(cross)
            assertEquals(2, controller.currentChapterIndex)
            assertTrue(cross!!.text.isNotEmpty())

            assertNull(adapter.getTextModel(ReaderLocator.Pdf(page = 0), maxChars = 10))
            assertNull(adapter.getTextModel(ReaderLocator.Epub("OEBPS/Text/missing.xhtml", 0, 0), 10))
        } finally {
            controller.close()
        }
    }

    @Test
    fun selectionUsesPageOffsetsAndClearsOnInvalidRange() {
        val controller = openSeed()
        try {
            val adapter = EpubReaderAdapter(controller, config)
            controller.openChapter(0)
            controller.layoutChapter(config)
            val pageText = controller.pageText(0)
            Assume.assumeTrue(pageText.length > 10)

            val selection = adapter.select(0, 0, 10)
            assertNotNull(selection)
            assertEquals(pageText.substring(0, 10), selection!!.text)
            assertEquals(selection, adapter.getSelection())
            val locator = selection.locator as ReaderLocator.Epub
            assertEquals(10, locator.length)

            assertNull(adapter.select(0, 5, 5))
            assertNull(adapter.getSelection())
        } finally {
            controller.close()
        }
    }

    @Test
    fun annotationDelegatesToStoreAndWorksWithoutStore() {
        val controller = openSeed()
        try {
            val locator = ReaderLocator.Epub(controller.book.spine[0].href, 0, 0)
            val annotation = ReaderAnnotation(
                bookId = 42L,
                type = ReaderAnnotationType.HIGHLIGHT,
                locator = locator,
                quote = "河",
            )

            val noStore = EpubReaderAdapter(controller, config)
            val unsaved = noStore.applyAnnotation(annotation)
            assertEquals(0L, unsaved.id)
            assertFalse(noStore.removeAnnotation(99))

            val store = RecordingStore()
            val withStore = EpubReaderAdapter(controller, config, store)
            val saved = withStore.applyAnnotation(annotation)
            assertEquals(7L, saved.id)
            assertEquals(annotation, store.addedInput)
            assertTrue(withStore.removeAnnotation(7L))
            assertEquals(7L, store.removedId)
        } finally {
            controller.close()
        }
    }

    @Test
    fun closeOnlyClosesOwnedController() {
        val shared = openSeed()
        val adapter = EpubReaderAdapter(shared, config)
        adapter.close()
        // 共享 controller 仍然可用（只是适配器引用被置空）。
        shared.openChapter(1)
        assertEquals(1, shared.currentChapterIndex)
        shared.close()

        val owned = openSeed()
        val ownedAdapter = EpubReaderAdapter(owned, config, ownsController = true)
        ownedAdapter.close()
        try {
            owned.openChapter(0)
            fail("expected closed controller to reject operations")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("closed"))
        }
    }

    private fun openSeed(): EpubReaderController {
        val seed = listOf(
            File("src/main/assets/seed/seed-01.epub"),
            File("app/src/main/assets/seed/seed-01.epub"),
        ).firstOrNull { it.isFile }
        Assume.assumeNotNull(seed)
        return EpubReaderController.open(seed!!, FakeMeasurer(), bookId = 42L)
    }

    private class RecordingStore : AnnotationStore {
        var addedInput: ReaderAnnotation? = null
        var removedId: Long? = null

        override fun list(bookId: Long): List<ReaderAnnotation> = emptyList()

        override fun add(annotation: ReaderAnnotation): ReaderAnnotation {
            addedInput = annotation
            return annotation.copy(id = 7L, createdAt = 1L, updatedAt = 1L)
        }

        override fun update(annotation: ReaderAnnotation): ReaderAnnotation? = null

        override fun remove(annotationId: Long): Boolean {
            removedId = annotationId
            return true
        }
    }

    private class FakeMeasurer : EpubBlockMeasurer {
        override fun measure(block: EpubBlock, blockIndex: Int, config: EpubLayoutConfig): MeasuredBlock {
            val text = when (block) {
                is EpubBlock.Paragraph -> block.text
                is EpubBlock.Heading -> block.text
                is EpubBlock.ListItem -> block.text
                else -> ""
            }
            if (text.isEmpty()) return MeasuredBlock(blockIndex, block, heightPx = 0)
            val lines = FakeLines(text.length, charsPerLine = 10, lineHeight = 10)
            return MeasuredBlock(
                blockIndex = blockIndex,
                block = block,
                heightPx = lines.lineCount * 10,
                lines = lines,
            )
        }
    }

    private class FakeLines(
        private val textLength: Int,
        private val charsPerLine: Int,
        private val lineHeight: Int,
    ) : EpubCharLineMetrics {
        override val lineCount: Int = max(1, ceil(textLength.toFloat() / charsPerLine).toInt())
        override fun lineTop(line: Int): Int = line.coerceIn(0, lineCount) * lineHeight
        override fun lineBottom(line: Int): Int = (line.coerceIn(0, lineCount) + 1) * lineHeight
        override fun lineBaseline(line: Int): Int = lineBottom(line) - 2
        override fun lineStart(line: Int): Int = line.coerceIn(0, lineCount) * charsPerLine
        override fun lineEnd(line: Int): Int = minOf(textLength, lineStart(line) + charsPerLine)
    }
}
