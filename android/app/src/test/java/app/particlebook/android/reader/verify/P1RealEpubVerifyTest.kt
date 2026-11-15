package app.particlebook.android.reader.verify

import app.particlebook.android.reader.AnnotationStore
import app.particlebook.android.reader.ReaderAnnotation
import app.particlebook.android.reader.ReaderAnnotationType
import app.particlebook.android.reader.ReaderFormat
import app.particlebook.android.reader.ReaderLocator
import app.particlebook.android.reader.ReaderRect
import app.particlebook.android.reader.ReaderSelection
import app.particlebook.android.reader.adapter.EpubReaderAdapter
import app.particlebook.android.reader.epub.EpubBlock
import app.particlebook.android.reader.epub.EpubCharLineMetrics
import app.particlebook.android.reader.epub.EpubReaderController
import app.particlebook.android.reader.epub.layout.EpubChapterLayout
import app.particlebook.android.reader.epub.layout.EpubLayoutConfig
import app.particlebook.android.reader.epub.layout.Page
import app.particlebook.android.reader.epub.layout.PageSlice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * P1-A021/A022 独立验证：用 worktree 内 3 本真实 EPUB 资源做 oracle。
 *
 * 本文件只读生产代码，只新增测试；不依赖 Robolectric/设备。
 * 证据同时写 build/p1-verify 下的 txt，便于把真实数字抄进
 * docs/android-port/p1/verification/a021-a022-realnet.md。
 */
class P1RealEpubVerifyTest {

    private val config = EpubLayoutConfig(
        viewportWidthPx = 900,
        viewportHeightPx = 1600,
        horizontalPaddingPx = 48,
        verticalPaddingPx = 48,
    )

    private val books = listOf(
        BookCase("moby-dick", "moby-dick.epub", "Moby Dick"),
        BookCase("wizard-of-oz", "wizard-of-oz.epub", "Wonderful Wizard"),
        BookCase("multilevel-toc", "multilevel-toc.epub", ""),
    )

    @Test
    fun fiveLocatorKindsRoundTripOnEachRealBook() {
        val evidence = ArrayList<String>()
        var cases = 0
        for (book in books) {
            open(book).use { controller ->
                controller.openChapter(0)
                controller.layoutChapter(config)
                val pageLocator = controller.locatorForPage(0)
                val locators = listOf(
                    pageLocator.copy(length = 7),
                    ReaderLocator.Pdf(
                        page = 12,
                        x = 0.125f,
                        y = 0.25f,
                        rect = ReaderRect(0.5f, 0.25f, 0.75f, 0.5f),
                    ),
                    ReaderLocator.Text(offset = 1234, length = 56),
                    ReaderLocator.Html("chapter-1.xhtml", "footnote-1", offset = 9, length = 3),
                    ReaderLocator.Scroll(0.375f),
                )
                for (locator in locators) {
                    val encoded = locator.encode()
                    assertEquals("decode(encode) failed for " + book.key + ": " + encoded, locator, ReaderLocator.decode(encoded))
                    assertEquals("encode(decode(encode)) unstable for " + book.key, encoded, ReaderLocator.decode(encoded)!!.encode())
                    cases++
                }
                // 边界：滚动百分比被夹取；非法 PDF rect 仍应被拒绝。
                assertEquals(ReaderLocator.Scroll(1f), ReaderLocator.decode(ReaderLocator.Scroll(7f).encode()))
                assertNull(ReaderLocator.decode("v1|pdf|1|0.1|0.2|0.9|0.1|0.1|0.1"))
                assertEquals(0, controller.pageAt(pageLocator))
                evidence += "BOOK|" + book.key + "|title=" + controller.book.metadata.title +
                    "|spine=" + controller.book.spine.size + "|toc=" + controller.toc.size +
                    "|chapter0=" + controller.book.spine[0].href +
                    "|blocks=" + controller.chapterAt(0).blocks.size +
                    "|pages=" + controller.pageCount +
                    "|fiveLocators=OK"
                controller.toc.take(4).forEach { toc ->
                    evidence += "TOC|" + book.key + "|" + toc.title.replace("|", "/") + "|" + toc.href
                }
                for (chapterIndex in chooseChapters(controller)) {
                    val chapter = controller.chapterAt(chapterIndex)
                    controller.openChapter(chapterIndex)
                    val layout = controller.layoutChapter(config)
                    val sample = chapter.blocks.asSequence()
                        .filter { isTextBlock(it) }
                        .map { textOf(it) }
                        .firstOrNull()
                        .orEmpty()
                        .replace("\n", " ")
                        .take(72)
                    evidence += "CHAPTER|" + book.key + "|spine=" + chapterIndex +
                        "|href=" + chapter.href + "|title=" + (chapter.title ?: "") +
                        "|blocks=" + chapter.blocks.size + "|pages=" + layout.pageCount +
                        "|sample=" + sample.replace("|", "/")
                }
            }
        }
        assertEquals(15, cases)
        writeEvidence("locator-oracle.txt", evidence)
    }

    @Test
    fun selectionRangeInBlockLocatorsStayOnPageAndRoundTrip() {
        val evidence = ArrayList<String>()
        var selections = 0
        for (book in books) {
            open(book).use { controller ->
                val chapters = chooseChapters(controller)
                assertTrue(book.key + " should have content chapters", chapters.isNotEmpty())
                for (chapterIndex in chapters) {
                    controller.openChapter(chapterIndex)
                    val layout = controller.layoutChapter(config)
                    val pages = textPages(layout)
                    if (pages.isEmpty()) continue
                    for (pageIndex in choosePages(pages)) {
                        val page = layout.pages[pageIndex]
                        val picked = pickTextSlice(layout, page)
                        assertNotNull(book.key + " chapter " + chapterIndex + " page " + pageIndex + " no slice", picked)
                        val (slice, range) = picked!!
                        assertTrue(range.last - range.first + 1 >= 4)
                        val from = range.first
                        val to = min(range.first + 6, range.last)
                        val selection = controller.selectionRangeInBlock(pageIndex, slice.blockIndex, from, to)
                        assertNotNull("selection null for " + book.key + " ch" + chapterIndex + " p" + pageIndex, selection)
                        assertEquals(rangeText(layout, slice)!!.substring(from, to), selection!!.text)
                        assertEquals(ReaderLocator.Epub(controller.currentLayoutOrNull!!.chapterHref, slice.blockIndex, from), selection.start)
                        assertEquals(ReaderLocator.Epub(controller.currentLayoutOrNull!!.chapterHref, slice.blockIndex, to), selection.end)
                        assertEquals(pageIndex, controller.pageAt(selection.start))
                        assertEquals(pageIndex, controller.pageAt(selection.end))
                        for (locator in listOf(selection.start, selection.end)) {
                            assertEquals(locator, ReaderLocator.decode(locator.encode()))
                        }
                        selections++
                        if (selections <= 3) {
                            evidence += "SELECT|" + book.key + "|ch=" + chapterIndex +
                                "|page=" + pageIndex + "|block=" + slice.blockIndex +
                                "|from=" + from + "|to=" + to + "|text=" + selection.text.take(24)
                        }
                    }
                }
                evidence += "BOOK|" + book.key + "|chapters=" + chapters.joinToString(",") +
                    "|selectionChecks=" + selections + "|totalSoFar"
            }
        }
        assertTrue("expected selections from all books, got " + selections, selections >= 9)
        writeEvidence("selection-oracle.txt", evidence)
    }

    @Test
    fun pageAtIsStableAcrossFreshControllerInstances() {
        val evidence = ArrayList<String>()
        var checks = 0
        for (book in books) {
            val probe = open(book)
            val targets = ArrayList<Pair<ReaderLocator.Epub, Int>>()
            try {
                for (chapterIndex in chooseChapters(probe)) {
                    probe.openChapter(chapterIndex)
                    val layout = probe.layoutChapter(config)
                    val pages = textPages(layout)
                    for (pageIndex in choosePages(pages)) {
                        targets += probe.locatorForPage(pageIndex) to pageIndex
                    }
                }
            } finally {
                probe.close()
            }
            assertTrue(book.key + " has no targets", targets.isNotEmpty())
            for ((rawLocator, expectedPage) in targets) {
                val persisted = ReaderLocator.decode(rawLocator.encode()) as ReaderLocator.Epub
                // 模拟杀进程重开：全新 controller 实例，从持久化 locator 恢复。
                open(book).use { reopened ->
                    reopened.openChapterByHref(persisted.chapterHref)
                    reopened.layoutChapter(config)
                    assertEquals(book.key + " reopened pageAt " + persisted, expectedPage, reopened.pageAt(persisted))
                }
                // 另一条路径：先打开别的章，再跳回目标章（缓存/状态不得污染）。
                open(book).use { other ->
                    val otherChapter = (0 until other.book.spine.size).firstOrNull {
                        it != other.chapterIndexForHref(persisted.chapterHref)
                    }
                    if (otherChapter != null) {
                        other.openChapter(otherChapter)
                        other.layoutChapter(config)
                    }
                    other.openChapterByHref(persisted.chapterHref)
                    other.layoutChapter(config)
                    assertEquals(book.key + " after other chapter " + persisted, expectedPage, other.pageAt(persisted))
                }
                checks += 2
            }
            evidence += "BOOK|" + book.key + "|locators=" + targets.size + "|checks=" + targets.size * 2 +
                "|spine=" + probe.book.spine.size
        }
        assertTrue("expected >= 12 cross-instance checks, got " + checks, checks >= 12)
        writeEvidence("pageat-stability.txt", evidence)
    }

    @Test
    fun readerAdapterSevenMethodContractOnRealEpub() {
        val book = books[1] // Wizard of Oz: 30 spine, 有正文图片，覆盖 image block。
        val evidence = ArrayList<String>()
        val store = RecordingStore()
        val controller = open(book, bookId = 4242L)
        val adapter = EpubReaderAdapter(controller, config, store, ownsController = false)
        try {
            assertEquals(4242L, adapter.bookId)
            assertEquals(ReaderFormat.EPUB, adapter.format)
            controller.openChapter(0)
            controller.layoutChapter(config)
            val firstPage = controller.currentPageIndex
            val locator = adapter.getLocator()
            assertTrue(locator is ReaderLocator.Epub)
            assertEquals(controller.locatorForPage(firstPage), locator)
            assertEquals(firstPage, controller.pageAt(locator))
            assertEquals(locator, ReaderLocator.decode(locator.encode()))
            evidence += "ADAPTER|getLocator|page=" + firstPage + "|" + locator

            // goTo 跨章 + 非法 locator。
            val targetChapter = chooseChapters(controller).first { it != 0 }
            controller.openChapter(targetChapter)
            controller.layoutChapter(config)
            val target = controller.locatorForPage(0)
            controller.openChapter(0)
            controller.layoutChapter(config)
            assertTrue(adapter.goTo(target))
            assertEquals(targetChapter, controller.currentChapterIndex)
            assertEquals(0, controller.currentPageIndex)
            assertFalse(adapter.goTo(ReaderLocator.Pdf(page = 2)))
            assertFalse(adapter.goTo(ReaderLocator.Epub("missing/chapter.xhtml", 0, 0)))
            evidence += "ADAPTER|goTo|crossChapter=" + targetChapter + "|invalidRejected=true"

            // getSelection：合法选区 -> 文本/locator；非法范围清空。
            val contentChapter = chooseChapters(controller).first()
            controller.openChapter(contentChapter)
            val contentLayout = controller.layoutChapter(config)
            val basePage = textPages(contentLayout).first { controller.pageText(it).length > 20 }
            controller.setPage(basePage)
            val baseLocator = adapter.getLocator()
            val pageText = controller.pageText(basePage)
            assertTrue(pageText.length > 10)
            assertNull(adapter.getSelection())
            val selection = adapter.select(basePage, 0, min(10, pageText.length))
            assertNotNull(selection)
            assertEquals(pageText.substring(0, min(10, pageText.length)), selection!!.text)
            assertEquals(selection, adapter.getSelection())
            assertEquals(selection.text.length, (selection.locator as ReaderLocator.Epub).length)
            assertNull(adapter.select(0, 5, 5))
            assertNull(adapter.getSelection())
            evidence += "ADAPTER|getSelection|len=" + selection.text.length + "|clearOnInvalid=true"

            // getTextModel：截断、整页、跨章、非法类型。
            controller.setPage(basePage)
            val short = adapter.getTextModel(baseLocator, maxChars = 5)
            assertNotNull(short)
            assertTrue(short!!.truncated)
            assertEquals(5, short.text.length)
            val full = adapter.getTextModel(baseLocator, maxChars = 1_000_000)
            assertNotNull(full)
            assertFalse(full!!.truncated)
            assertEquals(controller.pageText(basePage), full.text)
            val zero = adapter.getTextModel(baseLocator, maxChars = 0)
            assertNotNull(zero)
            assertEquals("", zero!!.text)
            assertTrue(zero.truncated)
            val negative = adapter.getTextModel(baseLocator, maxChars = -7)
            assertNotNull(negative)
            assertEquals("", negative!!.text)
            assertTrue(negative.truncated)
            controller.openChapter(targetChapter)
            controller.layoutChapter(config)
            val crossTarget = controller.locatorForPage(0)
            controller.openChapter(0)
            controller.layoutChapter(config)
            val cross = adapter.getTextModel(crossTarget, maxChars = 1_000_000)
            assertNotNull(cross)
            assertEquals(targetChapter, controller.currentChapterIndex)
            assertTrue(cross!!.text.isNotEmpty())
            assertNull(adapter.getTextModel(ReaderLocator.Pdf(page = 1), 10))
            assertNull(adapter.getTextModel(ReaderLocator.Epub("missing/chapter.xhtml", 0, 0), 10))
            evidence += "ADAPTER|getTextModel|truncated5=true|full=" + full.text.length + "|cross=" + cross.text.length

            // search：命中 locator 可跳转，且不改变当前位置；空 query/limit 边界。
            controller.openChapter(contentChapter)
            controller.layoutChapter(config)
            controller.setPage(basePage)
            val beforeChapter = controller.currentChapterIndex
            val beforePage = controller.currentPageIndex
            val hits = adapter.search("the", limit = 5)
            assertTrue(hits.isNotEmpty())
            assertTrue(hits.size <= 5)
            assertTrue(hits.all { it.locator is ReaderLocator.Epub })
            assertTrue(hits.first().snippet.lowercase().contains("the"))
            assertEquals(beforeChapter, controller.currentChapterIndex)
            assertEquals(beforePage, controller.currentPageIndex)
            assertTrue(adapter.search("THE", limit = 3).isNotEmpty())
            assertTrue(adapter.goTo(hits.first().locator))
            assertTrue(adapter.search("").isEmpty())
            assertTrue(adapter.search("   ").isEmpty())
            assertTrue(adapter.search("the", limit = 0).isEmpty())
            assertTrue(adapter.search("the", limit = -1).isEmpty())
            assertTrue(adapter.search("zzzz-no-such-token-zzzz").isEmpty())
            evidence += "ADAPTER|search|hits=" + hits.size + "|locatorClass=" + hits.first().locator.javaClass.simpleName +
                "|positionUnchanged=true"

            // applyAnnotation/removeAnnotation：有 store 委托；无 store 返回 id=0/false。
            val annotation = ReaderAnnotation(
                bookId = 4242L,
                type = ReaderAnnotationType.HIGHLIGHT,
                locator = baseLocator,
                quote = "the",
                color = "yellow",
            )
            val saved = adapter.applyAnnotation(annotation)
            assertEquals(77L, saved.id)
            assertEquals(annotation, store.addedInput)
            assertTrue(adapter.removeAnnotation(77L))
            assertEquals(77L, store.removedId)
            val noStore = EpubReaderAdapter(controller, config)
            assertEquals(0L, noStore.applyAnnotation(annotation).id)
            assertFalse(noStore.removeAnnotation(77L))
            evidence += "ADAPTER|annotation|savedId=77|noStoreId=0|remove=true"

            // close 语义：共享 controller 不被关闭；关闭后 adapter 拒绝 getLocator。
            noStore.close()
            noStore.close()
            assertEquals(contentChapter, controller.currentChapterIndex)
            assertEquals(basePage, controller.currentPageIndex)
            assertFalse(noStore.goTo(baseLocator))
            assertNull(noStore.getTextModel(baseLocator, 10))
            assertTrue(noStore.search("the").isEmpty())
            try {
                noStore.getLocator()
                fail("closed adapter should reject getLocator")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message!!.contains("closed"))
            }
            evidence += "ADAPTER|close|sharedControllerAlive=true|closedRejects=true"
        } finally {
            adapter.close()
            controller.close()
        }
        // ownsController：adapter.close() 必须关闭底层 controller。
        val ownedController = open(book, bookId = 4243L)
        val owned = EpubReaderAdapter(ownedController, config, ownsController = true)
        ownedController.openChapter(0)
        ownedController.layoutChapter(config)
        owned.close()
        try {
            ownedController.openChapter(0)
            fail("owned controller should be closed")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("closed"))
        }
        evidence += "ADAPTER|close|ownsControllerClosed=true"
        writeEvidence("adapter-contract.txt", evidence)
    }

    // ---- helpers ---------------------------------------------------------

    private data class BookCase(val key: String, val resource: String, val titlePart: String)

    private fun open(book: BookCase, bookId: Long = 7L): EpubReaderController {
        val file = bookFile(book)
        return EpubReaderController.open(file, bookId = bookId)
    }

    private fun bookFile(book: BookCase): File {
        val url = javaClass.getResource("/p1/books/" + book.resource)
            ?: error("missing test resource /p1/books/" + book.resource)
        return File(url.toURI())
    }

    private fun chooseChapters(controller: EpubReaderController): List<Int> {
        val content = controller.book.spine.indices.filter { index ->
            runCatching { controller.chapterAt(index).blocks.any { isTextBlock(it) } }.getOrDefault(false)
        }
        if (content.isEmpty()) return emptyList()
        return listOf(content.first(), content[content.size / 2], content.last()).distinct()
    }

    private fun textPages(layout: EpubChapterLayout): List<Int> =
        layout.pages.indices.filter { pageIndex ->
            layout.pages[pageIndex].slices.any { it.isTextSlice }
        }

    private fun choosePages(pages: List<Int>): List<Int> {
        if (pages.isEmpty()) return emptyList()
        return listOf(pages.first(), pages[pages.size / 2], pages.last()).distinct()
    }

    private fun pickTextSlice(layout: EpubChapterLayout, page: Page): Pair<PageSlice, IntRange>? {
        for (slice in page.slices) {
            val range = sliceRange(layout, slice) ?: continue
            if (range.last - range.first + 1 >= 4) return slice to range
        }
        return null
    }

    private fun rangeText(layout: EpubChapterLayout, slice: PageSlice): String? {
        val measured = layout.measuredBlocks.firstOrNull { it.blockIndex == slice.blockIndex } ?: return null
        return textOf(measured.block).takeIf { it.isNotEmpty() }
    }

    private fun sliceRange(layout: EpubChapterLayout, slice: PageSlice): IntRange? {
        val text = rangeText(layout, slice) ?: return null
        val measured = layout.measuredBlocks.firstOrNull { it.blockIndex == slice.blockIndex } ?: return null
        val lines = measured.lines as? EpubCharLineMetrics
        if (lines != null && slice.lineEnd > slice.lineStart) {
            val s = lines.lineStart(slice.lineStart).coerceIn(0, text.length)
            val e = lines.lineEnd(slice.lineEnd - 1).coerceIn(s, text.length)
            return if (e > s) s..(e - 1) else null
        }
        return if (slice.lineStart == 0) 0..(text.length - 1) else null
    }

    private fun isTextBlock(block: EpubBlock): Boolean =
        (block is EpubBlock.Paragraph || block is EpubBlock.Heading ||
            block is EpubBlock.ListItem || block is EpubBlock.Table) && textOf(block).isNotEmpty()

    private fun textOf(block: EpubBlock?): String = when (block) {
        is EpubBlock.Paragraph -> block.text
        is EpubBlock.Heading -> block.text
        is EpubBlock.ListItem -> block.text
        is EpubBlock.Table -> block.rows.joinToString("\n") { row -> row.joinToString(" ") }
        is EpubBlock.Image -> block.alt.orEmpty()
        else -> ""
    }

    private fun writeEvidence(name: String, lines: List<String>) {
        val dir = File("build/p1-verify").also { it.mkdirs() }
        val file = File(dir, name)
        file.writeText(lines.joinToString("\n") + "\n")
        println("P1EVIDENCE|" + file.absolutePath)
        lines.forEach { println("P1|" + it) }
    }

    private class RecordingStore : AnnotationStore {
        var addedInput: ReaderAnnotation? = null
        var removedId: Long? = null
        private var nextId = 77L

        override fun list(bookId: Long): List<ReaderAnnotation> = emptyList()
        override fun add(annotation: ReaderAnnotation): ReaderAnnotation =
            annotation.copy(id = nextId++, createdAt = 1L, updatedAt = 1L).also { addedInput = annotation }
        override fun update(annotation: ReaderAnnotation): ReaderAnnotation? = null
        override fun remove(annotationId: Long): Boolean {
            removedId = annotationId
            return annotationId == 77L
        }
    }
}
