package app.particlebook.android.reader.epub

import app.particlebook.android.reader.ReaderLocator
import app.particlebook.android.reader.epub.layout.EpubBlockMeasurer
import app.particlebook.android.reader.epub.layout.EpubLayoutConfig
import app.particlebook.android.reader.epub.layout.LineMetrics
import app.particlebook.android.reader.epub.layout.MeasuredBlock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.ceil
import kotlin.math.max

class EpubReaderControllerTest {

    private val tempFiles = ArrayList<File>()

    @After
    fun cleanup() {
        tempFiles.forEach { it.delete() }
    }

    @Test
    fun opensBookWithMetadataTocAndSpine() {
        EpubReaderController.open(fixture(), FakeMeasurer(), bookId = 42L).use { controller ->
            assertEquals(42L, controller.bookId)
            assertEquals("控制器样书", controller.book.metadata.title)
            assertEquals(2, controller.book.spine.size)
            assertEquals(2, controller.toc.size)
            assertEquals("第一章", controller.toc[0].title)
            assertEquals(0, controller.chapterIndexForHref("OEBPS/Text/ch1.xhtml#frag"))
            assertEquals(1, controller.chapterIndexForHref("OEBPS/Text/ch2.xhtml"))
            assertNull(controller.chapterIndexForHref("OEBPS/Text/missing.xhtml"))
            assertEquals(0, controller.currentChapterIndex)
        }
    }

    @Test
    fun layoutsPagesAndCachesSameConfig() {
        EpubReaderController.open(fixture(), FakeMeasurer()).use { controller ->
            val chapter = controller.openChapter(0)
            assertEquals(3, chapter.blocks.size)
            val config = EpubLayoutConfig(viewportWidthPx = 100, viewportHeightPx = 20)
            val layout = controller.layoutChapter(config)
            assertEquals(3, layout.pageCount)
            assertEquals(3, controller.pageCount)
            assertEquals("abcdefghij\nklmnopqrst", controller.pageText(0))
            val again = controller.layoutChapter(config)
            assertSame(layout, again)

            val wider = controller.layoutChapter(config.copy(viewportHeightPx = 100))
            assertEquals(1, wider.pageCount)
            assertEquals(1, controller.pageCount)
            val restored = controller.layoutChapter(config)
            assertSame(layout, restored)
            assertEquals(3, controller.pageCount)
        }
    }

    @Test
    fun locatorRoundTripsAcrossPagesAndProgressIsMonotonic() {
        EpubReaderController.open(fixture(), FakeMeasurer(), bookId = 42L).use { controller ->
            controller.openChapter(0)
            controller.layoutChapter(EpubLayoutConfig(viewportWidthPx = 100, viewportHeightPx = 20))

            assertEquals(0, controller.locatorForPage(0).blockIndex)
            assertEquals(0, controller.locatorForPage(0).charOffset)
            val middle = controller.locatorFor(1, blockIndex = 1, charOffset = 12)
            assertEquals(1, middle.blockIndex)
            assertEquals(12, middle.charOffset)
            assertTrue(middle.chapterHref.endsWith("ch1.xhtml"))
            assertEquals(1, controller.pageAt(middle))
            assertEquals(1, controller.pageForLocator(middle))

            assertEquals(10, controller.locatorForPage(1).charOffset)
            assertEquals(1, controller.pageAt(controller.locatorForPage(1)))
            val lastPage = controller.locatorForPage(2)
            assertEquals(2, controller.pageAt(lastPage))
            assertEquals(2, controller.pageForLocator(lastPage))
            assertEquals(0f, controller.percentAt(0), 0.0001f)
            assertEquals(1f / 6f, controller.percentAt(1), 0.0001f)
            assertEquals(1f / 3f, controller.percentAt(2), 0.0001f)
            controller.setPage(2)
            assertEquals(2, controller.currentPageIndex)

            val session = controller.session()
            assertEquals(42L, session.bookId)
            assertEquals(0, session.chapterIndex)
            assertEquals(2, session.pageIndex)
            assertEquals(3, session.pageCount)
            assertEquals(1f / 3f, session.percent, 0.0001f)
            assertTrue(session.locator is ReaderLocator.Epub)
        }
    }

    @Test
    fun selectionMapsPageTextToPersistableLocators() {
        EpubReaderController.open(fixture(), FakeMeasurer()).use { controller ->
            controller.openChapter(0)
            controller.layoutChapter(EpubLayoutConfig(viewportWidthPx = 100, viewportHeightPx = 20))

            val selection = controller.selectionRange(0, 0, 10)
            assertNotNull(selection)
            assertEquals("abcdefghij", selection!!.text)
            assertEquals(0, selection.start.blockIndex)
            assertEquals(0, selection.start.charOffset)
            assertEquals(0, selection.end.blockIndex)
            assertEquals(10, selection.end.charOffset)
            assertEquals(0, selection.startOffsetInPage)
            assertEquals(10, selection.endOffsetInPage)

            val throughBreak = controller.selectionRange(0, 0, 11)
            assertNotNull(throughBreak)
            assertEquals("abcdefghij\n", throughBreak!!.text)
            assertEquals(1, throughBreak.end.blockIndex)
            assertEquals(0, throughBreak.end.charOffset)

            val crossed = controller.selectionRange(0, 8, 13)
            assertNotNull(crossed)
            assertEquals("ij\nkl", crossed!!.text)
            assertEquals(0, crossed.start.blockIndex)
            assertEquals(8, crossed.start.charOffset)
            assertEquals(1, crossed.end.blockIndex)
            assertEquals(2, crossed.end.charOffset)

            val inBlock = controller.selectionRangeInBlock(0, blockIndex = 1, startChar = 10, endChar = 16)
            assertNotNull(inBlock)
            assertEquals("uvwxyz", inBlock!!.text)
            assertEquals(1, inBlock.start.blockIndex)
            assertEquals(10, inBlock.start.charOffset)
            assertNull(controller.selectionRange(0, 5, 5))
            assertEquals("", controller.pageText(99))
        }
    }

    @Test
    fun blockRangesInPageSplitsByBlockAndDropsParagraphBreak() {
        EpubReaderController.open(fixture(), FakeMeasurer()).use { controller ->
            controller.openChapter(0)
            controller.layoutChapter(EpubLayoutConfig(viewportWidthPx = 100, viewportHeightPx = 20))

            val single = controller.blockRangesInPage(0, 0, 10)
            assertEquals(1, single.size)
            assertEquals(0, single[0].blockIndex)
            assertEquals(0, single[0].startChar)
            assertEquals(10, single[0].endChar)

            // 0..11 覆盖第一块末尾 + 换行 + 第二块首字符：换行不入区间，第二块只取 1 个字符。
            val crossed = controller.blockRangesInPage(0, 8, 13)
            assertEquals(2, crossed.size)
            assertEquals(0, crossed[0].blockIndex)
            assertEquals(8, crossed[0].startChar)
            assertEquals(10, crossed[0].endChar)
            assertEquals(1, crossed[1].blockIndex)
            assertEquals(0, crossed[1].startChar)
            assertEquals(2, crossed[1].endChar)

            assertTrue(controller.blockRangesInPage(0, 5, 5).isEmpty())
            assertTrue(controller.blockRangesInPage(99, 0, 5).isEmpty())
        }
    }

    @Test
    fun chapterSwitchingAndTocTargetsPreserveChapterState() {
        EpubReaderController.open(fixture(), FakeMeasurer()).use { controller ->
            controller.openChapter(0)
            controller.layoutChapter(EpubLayoutConfig(viewportWidthPx = 100, viewportHeightPx = 20))
            assertEquals(0, controller.pageForTocHref("OEBPS/Text/ch1.xhtml#sec"))
            assertNull(controller.pageForTocHref("OEBPS/Text/ch2.xhtml"))

            val targets = controller.tocTargets()
            assertEquals(2, targets.size)
            assertEquals(0, targets[0].chapterIndex)
            assertEquals(1, targets[1].chapterIndex)
            assertEquals("第二章", targets[1].title)

            val oldLocator = controller.locatorForPage(0)
            controller.openChapterByHref("OEBPS/Text/ch2.xhtml#frag")
            assertEquals(1, controller.currentChapterIndex)
            assertEquals(0, controller.currentPageIndex)
            assertNull(controller.pageAt(oldLocator))
            controller.layoutChapter(EpubLayoutConfig(viewportWidthPx = 100, viewportHeightPx = 20))
            assertEquals(1, controller.pageCount)
            assertEquals(0.5f, controller.percent, 0.0001f)
            assertEquals("end", controller.pageText(0))
        }
    }

    @Test
    fun pageAtUsesNextLineStartWhenPreviousLineEndOverlaps() {
        EpubReaderController.open(fixture(), OverlapMeasurer(), bookId = 11L).use { controller ->
            controller.openChapter(0)
            controller.layoutChapter(EpubLayoutConfig(viewportWidthPx = 100, viewportHeightPx = 20))

            val secondPage = controller.locatorForPage(1)
            assertEquals(10, secondPage.charOffset)
            assertEquals(1, controller.pageAt(secondPage))
            assertEquals(1, controller.pageForLocator(secondPage))
        }
    }

    @Test
    fun defaultMeasurerStillAllowsOpenAndLayoutForPreflight() {
        EpubReaderController.open(fixture(), bookId = 7L).use { controller ->
            assertEquals(0, controller.pageCount)
            controller.openChapter(0)
            val layout = controller.layoutChapter(EpubLayoutConfig(viewportWidthPx = 100, viewportHeightPx = 100))
            assertTrue(layout.pageCount >= 1)
            assertTrue(controller.pageText(0).startsWith("abcdefghij"))
            assertEquals(7L, controller.session().bookId)
        }
    }

    @Test
    fun opensRealSeedBookEndToEnd() {
        val seed = listOf(
            File("src/main/assets/seed/seed-01.epub"),
            File("app/src/main/assets/seed/seed-01.epub"),
        ).firstOrNull { it.isFile }
        Assume.assumeNotNull(seed)

        EpubReaderController.open(seed!!, FakeMeasurer(), bookId = 9L).use { controller ->
            assertEquals("故乡的河", controller.book.metadata.title)
            assertEquals(3, controller.book.spine.size)
            assertEquals(3, controller.toc.size)
            controller.openChapter(0)
            val layout = controller.layoutChapter(EpubLayoutConfig(viewportWidthPx = 400, viewportHeightPx = 1000))
            assertTrue(layout.pageCount >= 1)
            assertTrue(controller.pageText(0).isNotBlank())
            assertEquals(9L, controller.session().bookId)
        }
    }

    @Test
    fun closeRejectsFurtherOperations() {
        val controller = EpubReaderController.open(fixture(), FakeMeasurer())
        controller.close()
        controller.close()
        try {
            controller.openChapter(0)
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("closed"))
        }
    }

    // ------------------------------------------------------------------
    // layoutRevision / openChapter / layoutChapter 三条核心不变量
    // 页数由 FakeMeasurer(charsPerLine=10, lineHeight=10) + 分页高度唯一决定：
    //   ch1 = [10 字符/1 行, 16 字符/2 行, 20 字符/2 行] -> 页高 10 -> 5 页，20 -> 3 页，100 -> 1 页。
    //   ch2 = [3 字符/1 行] -> 页高 10 与 100 都是 1 页。
    // ------------------------------------------------------------------

    @Test
    fun layoutRevisionStartsAtZeroAndLayoutIsAbsentBeforeAnyLayout() {
        EpubReaderController.open(fixture(), FakeMeasurer()).use { controller ->
            assertEquals(0, controller.layoutRevision)
            assertEquals(0, controller.currentPageIndex)
            assertEquals(0, controller.pageCount)
            assertNull(controller.currentLayoutOrNull)
        }
    }

    @Test
    fun openChapterAdvancesRevisionAndResetsPageAndLayout() {
        EpubReaderController.open(fixture(), FakeMeasurer()).use { controller ->
            val config = EpubLayoutConfig(viewportWidthPx = 100, viewportHeightPx = 20)

            controller.openChapter(0)
            assertEquals(1, controller.layoutRevision)
            controller.layoutChapter(config)
            assertEquals(2, controller.layoutRevision)
            assertEquals(3, controller.pageCount)
            assertEquals(2, controller.setPage(2))
            assertNotNull(controller.currentLayoutOrNull)

            controller.openChapter(1)
            assertEquals(3, controller.layoutRevision)
            assertEquals(1, controller.currentChapterIndex)
            assertEquals(0, controller.currentPageIndex)
            assertNull(controller.currentLayoutOrNull)
            assertEquals(0, controller.pageCount)

            controller.openChapter(0)
            assertEquals(4, controller.layoutRevision)
            assertEquals(0, controller.currentChapterIndex)
            assertEquals(0, controller.currentPageIndex)
            assertNull(controller.currentLayoutOrNull)
        }
    }

    @Test
    fun layoutChapterSameConfigHitsLayoutCacheWithoutAdvancingRevision() {
        EpubReaderController.open(fixture(), FakeMeasurer()).use { controller ->
            controller.openChapter(0)
            val config = EpubLayoutConfig(viewportWidthPx = 100, viewportHeightPx = 20)

            val first = controller.layoutChapter(config)
            assertEquals(2, controller.layoutRevision)
            assertEquals(3, first.pageCount)

            val second = controller.layoutChapter(config)
            assertEquals(2, controller.layoutRevision)
            assertSame(first, second)

            val third = controller.layoutChapter(config)
            assertEquals(2, controller.layoutRevision)
            assertSame(first, third)
            assertSame(first, controller.currentLayoutOrNull)
            assertEquals(3, controller.pageCount)
        }
    }

    @Test
    fun layoutChapterWithChangedConfigAdvancesRevisionAndRebuildsLayout() {
        EpubReaderController.open(fixture(), FakeMeasurer()).use { controller ->
            controller.openChapter(0)
            val base = EpubLayoutConfig(viewportWidthPx = 100, viewportHeightPx = 20)
            val first = controller.layoutChapter(base)
            assertEquals(2, controller.layoutRevision)

            // 只改宽度：FakeMeasurer 不消耗宽度，页数不变，代次推进只能来自「布局实例换了」。
            val wider = base.copy(viewportWidthPx = 200)
            val second = controller.layoutChapter(wider)
            assertEquals(3, controller.layoutRevision)
            assertNotSame(first, second)
            assertEquals(wider, second.config)
            assertEquals(first.pageCount, second.pageCount)

            // 只改字号：同上，仍换实例、仍推进。
            val largerText = wider.copy(baseTextSizeSp = 24f)
            val third = controller.layoutChapter(largerText)
            assertEquals(4, controller.layoutRevision)
            assertNotSame(second, third)
            assertEquals(largerText, third.config)

            // 改页高：页数真的变了，实例与页数一起变。
            val taller = largerText.copy(viewportHeightPx = 100)
            val fourth = controller.layoutChapter(taller)
            assertEquals(5, controller.layoutRevision)
            assertNotSame(third, fourth)
            assertEquals(1, fourth.pageCount)
            assertEquals(1, controller.pageCount)
        }
    }

    @Test
    fun layoutChapterClampsPageIndexIntoShrunkLayout() {
        EpubReaderController.open(fixture(), FakeMeasurer()).use { controller ->
            controller.openChapter(0)
            val fivePages = EpubLayoutConfig(viewportWidthPx = 100, viewportHeightPx = 10)
            val threePages = EpubLayoutConfig(viewportWidthPx = 100, viewportHeightPx = 20)
            val onePage = EpubLayoutConfig(viewportWidthPx = 100, viewportHeightPx = 100)

            assertEquals(5, controller.layoutChapter(fivePages).pageCount)
            assertEquals(4, controller.setPage(4))
            assertEquals(4, controller.currentPageIndex)
            assertEquals(4, controller.setPage(99))

            // 页数 5 -> 3：页下标被夹到最后可用页。
            assertEquals(3, controller.layoutChapter(threePages).pageCount)
            assertEquals(2, controller.currentPageIndex)
            assertTrue(controller.currentPageIndex in 0 until controller.pageCount)

            // 页数 3 -> 5：页下标留在原地，不回到 0 也不越界。
            assertEquals(5, controller.layoutChapter(fivePages).pageCount)
            assertEquals(2, controller.currentPageIndex)

            // 页数 5 -> 1：夹到 0。
            assertEquals(1, controller.layoutChapter(onePage).pageCount)
            assertEquals(0, controller.currentPageIndex)
            assertTrue(controller.currentPageIndex in 0 until controller.pageCount)
        }
    }

    @Test
    fun openChapterWithoutLayoutHasNoPages() {
        EpubReaderController.open(fixture(), FakeMeasurer()).use { controller ->
            controller.openChapter(0)
            assertEquals(0, controller.pageCount)
            assertNull(controller.currentLayoutOrNull)
            assertEquals(0, controller.currentPageIndex)
            assertNull(controller.page(0))
            assertEquals(0f, controller.percent, 0.0001f)

            // 换到有内容的章但不布局：仍然没有页。
            controller.openChapter(1)
            assertEquals(0, controller.pageCount)
            assertEquals(0, controller.currentPageIndex)
            assertNull(controller.currentLayoutOrNull)
            assertNull(controller.page(0))
        }
    }

    @Test
    fun layoutChapterAfterChapterSwitchBindsLayoutToCurrentChapter() {
        EpubReaderController.open(fixture(), FakeMeasurer()).use { controller ->
            val config = EpubLayoutConfig(viewportWidthPx = 100, viewportHeightPx = 20)

            controller.openChapter(0)
            val first = controller.layoutChapter(config)
            assertEquals(2, controller.layoutRevision)
            assertEquals(controller.book.spine[0].href, first.chapterHref)
            assertTrue(first.chapterHref.endsWith("ch1.xhtml"))

            controller.openChapter(1)
            assertEquals(3, controller.layoutRevision)
            val second = controller.layoutChapter(config)
            assertEquals(4, controller.layoutRevision)
            assertEquals(1, controller.currentChapterIndex)

            // 同 config 不同章 => 不同缓存键 => 不同实例，且布局绑在当前章上。
            assertNotSame(first, second)
            assertEquals(config, second.config)
            assertEquals(controller.book.spine[1].href, second.chapterHref)
            assertEquals(controller.chapterAt(controller.currentChapterIndex).href, second.chapterHref)
            assertTrue(second.chapterHref.endsWith("ch2.xhtml"))
            assertEquals(1, second.pageCount)
            assertEquals(1, controller.pageCount)
        }
    }

    @Test
    fun reopeningSameChapterInvalidatesLayoutEvenWhenCacheHits() {
        EpubReaderController.open(fixture(), FakeMeasurer()).use { controller ->
            val config = EpubLayoutConfig(viewportWidthPx = 100, viewportHeightPx = 20)

            controller.openChapter(0)
            assertEquals(1, controller.layoutRevision)
            val first = controller.layoutChapter(config)
            assertEquals(2, controller.layoutRevision)
            controller.setPage(2)
            assertEquals(2, controller.currentPageIndex)

            controller.openChapter(0)
            assertEquals(3, controller.layoutRevision)
            assertNull(controller.currentLayoutOrNull)
            assertEquals(0, controller.currentPageIndex)

            val reopened = controller.layoutChapter(config)
            assertEquals(4, controller.layoutRevision)
            // LRU 命中，拿回的是同一个布局实例；推进代次的是「currentLayout 已被 openChapter 作废」。
            assertSame(first, reopened)
            assertEquals(3, reopened.pageCount)
            assertEquals(3, controller.pageCount)
        }
    }

    @Test
    fun setPageDoesNotAdvanceLayoutRevision() {
        EpubReaderController.open(fixture(), FakeMeasurer()).use { controller ->
            controller.openChapter(0)
            val layout = controller.layoutChapter(EpubLayoutConfig(viewportWidthPx = 100, viewportHeightPx = 20))
            val revisionAfterLayout = controller.layoutRevision
            assertEquals(2, revisionAfterLayout)

            controller.setPage(1)
            assertEquals(1, controller.currentPageIndex)
            controller.setPage(2)
            assertEquals(2, controller.currentPageIndex)
            controller.setPage(-5)
            assertEquals(0, controller.currentPageIndex)
            controller.setPage(99)
            assertEquals(2, controller.currentPageIndex)

            // 翻页不改变页界，代次与布局实例都必须原地不动。
            assertEquals(revisionAfterLayout, controller.layoutRevision)
            assertSame(layout, controller.currentLayoutOrNull)
            assertEquals(3, controller.pageCount)
        }
    }

    @Test
    fun layoutChapterWithoutOpenChapterResolvesCurrentChapterOnce() {
        EpubReaderController.open(fixture(), FakeMeasurer()).use { controller ->
            val config = EpubLayoutConfig(viewportWidthPx = 100, viewportHeightPx = 20)
            val layout = controller.layoutChapter(config)

            // 没有 openChapter：隐式解析第 0 章，只 +1 次（没有额外的换章代次）。
            assertEquals(1, controller.layoutRevision)
            assertEquals(0, controller.currentChapterIndex)
            assertEquals(controller.book.spine[0].href, layout.chapterHref)
            assertEquals(3, controller.pageCount)
            assertEquals(3, layout.pageCount)

            controller.layoutChapter(EpubLayoutConfig(viewportWidthPx = 100, viewportHeightPx = 20))
            assertEquals(1, controller.layoutRevision)
        }
    }

    private fun fixture(): File {
        val opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="uid">urn:test:controller</dc:identifier>
                <dc:title>控制器样书</dc:title>
                <dc:creator>ParticleBook</dc:creator>
                <dc:language>zh-CN</dc:language>
              </metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="ch1" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="ch2" href="Text/ch2.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine>
                <itemref idref="ch1"/>
                <itemref idref="ch2"/>
              </spine>
            </package>
        """.trimIndent()
        val nav = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
            <body><nav epub:type="toc"><ol>
              <li><a href="Text/ch1.xhtml">第一章</a></li>
              <li><a href="Text/ch2.xhtml">第二章</a></li>
            </ol></nav></body></html>
        """.trimIndent()
        val ch1 = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml"><head><title>第一章</title></head>
            <body>
              <p>abcdefghij</p>
              <p>klmnopqrstuvwxyz</p>
              <p>0123456789ABCDEFGHIJ</p>
            </body></html>
        """.trimIndent()
        val ch2 = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml"><head><title>第二章</title></head>
            <body><p>end</p></body></html>
        """.trimIndent()
        return zip(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to container().toByteArray(),
            "OEBPS/content.opf" to opf.toByteArray(),
            "OEBPS/nav.xhtml" to nav.toByteArray(),
            "OEBPS/Text/ch1.xhtml" to ch1.toByteArray(),
            "OEBPS/Text/ch2.xhtml" to ch2.toByteArray(),
        )
    }

    private fun container(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
        </container>
    """.trimIndent()

    private fun zip(vararg entries: Pair<String, ByteArray>): File {
        val file = File.createTempFile("epub-controller-", ".epub")
        tempFiles += file
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    /**
     * 模拟 StaticLayout 的行尾空白：lineEnd 比下一行 lineStart 多 1。
     * 页首字符正好等于下一行 lineStart，必须归到下一页而不是上一页。
     */
    private class OverlapMeasurer : EpubBlockMeasurer {
        override fun measure(block: EpubBlock, blockIndex: Int, config: EpubLayoutConfig): MeasuredBlock {
            val text = when (block) {
                is EpubBlock.Paragraph -> block.text
                is EpubBlock.Heading -> block.text
                is EpubBlock.ListItem -> block.text
                else -> ""
            }
            if (text.isEmpty()) return MeasuredBlock(blockIndex, block, heightPx = 0)
            val lineCount = max(1, ceil(text.length.toFloat() / 10).toInt())
            val lines = object : EpubCharLineMetrics {
                override val lineCount: Int = lineCount
                override fun lineTop(line: Int): Int = line.coerceIn(0, lineCount) * 10
                override fun lineBottom(line: Int): Int = (line.coerceIn(0, lineCount) + 1) * 10
                override fun lineBaseline(line: Int): Int = lineBottom(line) - 2
                override fun lineStart(line: Int): Int = line.coerceIn(0, lineCount) * 10
                override fun lineEnd(line: Int): Int =
                    minOf(text.length, lineStart(line) + 11)
            }
            return MeasuredBlock(
                blockIndex = blockIndex,
                block = block,
                heightPx = lines.lineCount * 10,
                lines = lines,
            )
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
