package app.particlebook.android.reader.epub

import app.particlebook.android.reader.epub.layout.EpubBlockMeasurer
import app.particlebook.android.reader.epub.layout.EpubLayoutConfig
import app.particlebook.android.reader.epub.layout.MeasuredBlock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.ceil
import kotlin.math.max

/**
 * 页文本映射缓存的行为契约：键是 (页下标, 布局代次)，只留最近两页。
 *
 * 断言走两层：实例同一性（命中必须返回同一个 PageTextMap，自然也就是同一个 text 字符串）
 * 与 [EpubReaderController.pageTextMapBuildsForTest] 计的重建次数（命中路径一次都不该重建）。
 * 不依赖 Android framework：控制器本来就是纯 JVM 可测的。
 */
class EpubPageTextMapCacheTest {

    private val tempFiles = ArrayList<File>()

    @After
    fun cleanup() {
        tempFiles.forEach { it.delete() }
    }

    @Test
    fun sameLayoutReusesOnePageTextMapAcrossReaders() {
        open().use { controller ->
            controller.openChapter(0)
            controller.layoutChapter(config())

            val first = controller.pageText(0)
            val second = controller.pageText(0)
            assertEquals("abcdefghij\nklmnopqrst", first)
            assertSame(first, second)
            assertEquals(1, controller.pageTextMapBuildsForTest)

            // 选区/朗读高亮都会反复打进来：同一个映射，不许重建、不许换实例。
            assertEquals(1, controller.blockRangesInPage(0, 0, 10).size)
            controller.selectionRange(0, 8, 13)
            controller.pageText(0)
            assertEquals(1, controller.pageTextMapBuildsForTest)
            assertSame(first, controller.pageText(0))
        }
    }

    @Test
    fun keepsOnlyPreviousAndCurrentPage() {
        open().use { controller ->
            controller.openChapter(0)
            controller.layoutChapter(config())
            assertEquals(3, controller.pageCount)

            val page0 = controller.pageText(0)
            val page1 = controller.pageText(1)
            assertEquals("uvwxyz\n0123456789", page1)
            assertNotEquals(page0, page1)
            assertEquals(2, controller.pageTextMapBuildsForTest)

            // 只有两槽：回到上一页仍命中。
            assertSame(page0, controller.pageText(0))
            assertEquals(2, controller.pageTextMapBuildsForTest)

            // 第三页挤掉最早的页，再回页 0 必须重建而不是拿到别人的映射。
            val page2 = controller.pageText(2)
            assertEquals("ABCDEFGHIJ", page2)
            assertEquals(3, controller.pageTextMapBuildsForTest)
            assertNotSame(page1, controller.pageText(0))
            assertEquals(4, controller.pageTextMapBuildsForTest)
            assertEquals("abcdefghij\nklmnopqrst", controller.pageText(0))
        }
    }

    @Test
    fun sameConfigLayoutKeepsCacheButNewViewportBumpsLayoutRevision() {
        open().use { controller ->
            controller.openChapter(0)
            controller.layoutChapter(config())
            val revision = controller.layoutRevision
            val narrowPage = controller.pageText(0)
            assertEquals(1, controller.pageTextMapBuildsForTest)

            // 同 config 命中布局 LRU：页界没变，代次不动，页文本缓存继续生效。
            controller.layoutChapter(config())
            assertEquals(revision, controller.layoutRevision)
            assertSame(narrowPage, controller.pageText(0))
            assertEquals(1, controller.pageTextMapBuildsForTest)

            // 换 viewport（分屏/折叠/旋转/字号）：代次 +1，旧映射作废。
            controller.layoutChapter(config(height = 100))
            assertEquals(1, controller.pageCount)
            assertTrue(controller.layoutRevision > revision)
            val widePage = controller.pageText(0)
            assertNotSame(narrowPage, widePage)
            assertEquals(2, controller.pageTextMapBuildsForTest)
            assertEquals("abcdefghij\nklmnopqrstuvwxyz\n0123456789ABCDEFGHIJ", widePage)

            // 回到原布局同样是新代次：旧实例不返回，内容重新按当前布局拼。
            controller.layoutChapter(config())
            assertEquals(3, controller.pageCount)
            assertTrue(controller.layoutRevision > revision)
            val restored = controller.pageText(0)
            assertNotSame(narrowPage, restored)
            assertEquals(narrowPage, restored)
            assertEquals(3, controller.pageTextMapBuildsForTest)
        }
    }

    @Test
    fun chapterAndPageSwitchNeverReturnStaleMap() {
        open().use { controller ->
            controller.openChapter(0)
            controller.layoutChapter(config())
            val revision = controller.layoutRevision
            val page0 = controller.pageText(0)
            assertSame(page0, controller.pageText(0))

            // 翻页只改页下标：布局代次不动，新页建一次，之后命中。
            controller.setPage(1)
            assertEquals(revision, controller.layoutRevision)
            val page1 = controller.pageText(1)
            assertNotSame(page0, page1)
            assertEquals(2, controller.pageTextMapBuildsForTest)
            assertSame(page1, controller.pageText(1))
            assertEquals(2, controller.pageTextMapBuildsForTest)

            // 换章推进代次；即使页号仍旧是 0，也不许拿上一章的映射。
            controller.openChapter(1)
            assertTrue(controller.layoutRevision > revision)
            controller.layoutChapter(config())
            val chapterTwoPage = controller.pageText(0)
            assertEquals("end", chapterTwoPage)
            assertNotSame(page0, chapterTwoPage)
            assertSame(chapterTwoPage, controller.pageText(0))
            assertEquals(3, controller.pageTextMapBuildsForTest)
        }
    }

    private fun open(): EpubReaderController =
        EpubReaderController.open(fixture(), FixedWidthMeasurer())

    private fun config(height: Int = 20): EpubLayoutConfig =
        EpubLayoutConfig(viewportWidthPx = 100, viewportHeightPx = height)

    private fun fixture(): File {
        val opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="uid">urn:test:page-text-cache</dc:identifier>
                <dc:title>页文本缓存样书</dc:title>
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
        val file = File.createTempFile("epub-page-text-cache-", ".epub")
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

    /** 定宽假 measurer：每行 10 个 UTF-16 code unit，行高 10px，行区间可精确映射（实现 EpubCharLineMetrics）。 */
    private class FixedWidthMeasurer : EpubBlockMeasurer {
        override fun measure(block: EpubBlock, blockIndex: Int, config: EpubLayoutConfig): MeasuredBlock {
            val text = when (block) {
                is EpubBlock.Paragraph -> block.text
                is EpubBlock.Heading -> block.text
                is EpubBlock.ListItem -> block.text
                else -> ""
            }
            if (text.isEmpty()) return MeasuredBlock(blockIndex, block, heightPx = 0)
            val lines = FixedWidthLines(text.length, charsPerLine = 10, lineHeight = 10)
            return MeasuredBlock(
                blockIndex = blockIndex,
                block = block,
                heightPx = lines.lineCount * 10,
                lines = lines,
            )
        }
    }

    private class FixedWidthLines(
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
