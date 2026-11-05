package app.particlebook.android.reader.epub

import app.particlebook.android.reader.ReaderLocator
import app.particlebook.android.reader.epub.layout.EpubBlockMeasurer
import app.particlebook.android.reader.epub.layout.EpubLayoutConfig
import app.particlebook.android.reader.epub.layout.LineMetrics
import app.particlebook.android.reader.epub.layout.MeasuredBlock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
