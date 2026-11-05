package app.particlebook.android.reader.epub.model

import app.particlebook.android.reader.epub.EpubAlign
import app.particlebook.android.reader.epub.EpubBlock
import app.particlebook.android.reader.epub.EpubChapter
import app.particlebook.android.reader.epub.EpubResourceResolver
import app.particlebook.android.reader.epub.css.CssParser
import app.particlebook.android.reader.epub.css.CssStylesheet
import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XhtmlBlockParserTest {

    private fun assertEquals(expected: Float, actual: Float, delta: Float = 0.01f) {
        org.junit.Assert.assertEquals(expected.toDouble(), actual.toDouble(), delta.toDouble())
    }

    private fun parse(
        xhtml: String,
        css: String = "",
        chapterHref: String = "OEBPS/Text/ch1.xhtml",
        resolver: EpubResourceResolver? = null,
        options: XhtmlParseOptions = XhtmlParseOptions(),
    ): EpubChapter = XhtmlBlockParser.parse(
        xhtml = xhtml,
        chapterHref = chapterHref,
        stylesheet = CssParser.parse(css),
        resolver = resolver,
        options = options,
    )

    private fun bookEntries(name: String): Map<String, ByteArray> {
        val bytes = javaClass.classLoader.getResourceAsStream("epub/" + name)?.readBytes()
            ?: error("fixture not found: " + name)
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return entries
    }

    private fun resolver(entries: Map<String, ByteArray>): EpubResourceResolver =
        EpubResourceResolver { href -> entries[href] }

    private fun chapterOf(book: String, href: String): EpubChapter {
        val entries = bookEntries(book)
        return XhtmlBlockParser.parse(
            xhtml = String(entries.getValue(href), Charsets.UTF_8),
            chapterHref = href,
            stylesheet = CssStylesheet.EMPTY,
            resolver = resolver(entries),
        )
    }

    @Test
    fun parsesBlockSequenceEntitiesListsTableHrPageBreakAndHidden() {
        val chapter = parse(
            """
            <html><body>
            <h1>第一章</h1>
            <p>第一段 &amp; &#x4E2D;</p>
            <ul><li>甲</li><li>乙</li></ul>
            <ol><li>一</li></ol>
            <table><tr><th>A</th><th>B</th></tr><tr><td>1</td><td>2</td></tr></table>
            <hr/>
            <div class="pb"></div>
            <p class="hidden">隐藏</p>
            </body></html>
            """.trimIndent(),
            ".pb { page-break-before: always; } .hidden { display: none; }",
        )
        assertEquals("第一章", chapter.title)
        val blocks = chapter.blocks
        assertEquals(8, blocks.size)
        assertEquals("第一章", (blocks[0] as EpubBlock.Heading).text)
        assertEquals(1, (blocks[0] as EpubBlock.Heading).level)
        assertEquals("第一段 & 中", (blocks[1] as EpubBlock.Paragraph).text)
        assertEquals(
            listOf("甲", "乙"),
            blocks.filterIsInstance<EpubBlock.ListItem>().filter { !it.ordered }.map { it.text },
        )
        val ordered = blocks.filterIsInstance<EpubBlock.ListItem>().filter { it.ordered }
        assertEquals(1, ordered.size)
        assertEquals(1, ordered[0].index)
        assertEquals("一", ordered[0].text)
        val table = blocks.filterIsInstance<EpubBlock.Table>().single()
        assertEquals(listOf(listOf("A", "B"), listOf("1", "2")), table.rows)
        assertTrue(blocks[5] is EpubBlock.Table)
        assertTrue(blocks[6] is EpubBlock.HorizontalRule)
        assertTrue(blocks[7] is EpubBlock.PageBreak)
        assertEquals(7, blocks.indexOfLast { it is EpubBlock.PageBreak })
    }

    @Test
    fun toleratesMalformedHtmlAndUnknownEntities() {
        val chapter = parse(
            "<html><body><p>坏<b>加粗<i>斜体</p><p>后续 &unknown; &amp; &#x4E2D;</p></body></html>",
        )
        val paragraphs = chapter.blocks.filterIsInstance<EpubBlock.Paragraph>()
        assertEquals(2, paragraphs.size)
        assertEquals("坏加粗斜体", paragraphs[0].text)
        assertEquals("后续 &unknown; & 中", paragraphs[1].text)
    }

    @Test
    fun resolvesImagePathsRelativeToChapterAndSplitsInlineImages() {
        val chapter = parse(
            """
            <html><body>
            <p>前<img src="../Images/a.png" alt="图" width="120" height="80"/>后</p>
            <img src="https://example.com/b.png" alt="远程"/>
            </body></html>
            """.trimIndent(),
        )
        val blocks = chapter.blocks
        assertEquals("前", (blocks[0] as EpubBlock.Paragraph).text)
        val first = blocks[1] as EpubBlock.Image
        assertEquals("OEBPS/Images/a.png", first.src)
        assertEquals("图", first.alt)
        assertEquals(120, first.width ?: -1)
        assertEquals(80, first.height ?: -1)
        assertEquals("后", (blocks[2] as EpubBlock.Paragraph).text)
        val remote = blocks[3] as EpubBlock.Image
        assertEquals("https://example.com/b.png", remote.src)
        assertEquals("远程", remote.alt)
    }

    @Test
    fun canDisablePathResolutionAndAltText() {
        val chapter = parse(
            "<html><body><p><img src='../Images/a.png' alt='图'/></p></body></html>",
            options = XhtmlParseOptions(resolveImagePaths = false, includeAltText = false),
        )
        val image = chapter.blocks.filterIsInstance<EpubBlock.Image>().single()
        assertEquals("../Images/a.png", image.src)
        assertNull(image.alt)
    }

    @Test
    fun usesHeadingThenFallbackThenTitleAsChapterTitle() {
        val withHeading = parse("<html><body><h2>标题二</h2><p>x</p></body></html>")
        assertEquals("标题二", withHeading.title)

        val withTitle = parse("<html><head><title>书页标题</title></head><body><p>x</p></body></html>")
        assertEquals("书页标题", withTitle.title)

        val fallback = parse(
            "<html><body><p>x</p></body></html>",
            options = XhtmlParseOptions(fallbackTitle = "回退标题"),
        )
        assertEquals("回退标题", fallback.title)
    }

    @Test
    fun hiddenBodyProducesNoBlocks() {
        val chapter = parse(
            "<html><body><p>应被整章隐藏</p></body></html>",
            "body { display: none; }",
        )
        assertTrue(chapter.blocks.isEmpty())
    }

    @Test
    fun styleElementAndInlineStyleAreApplied() {
        val chapter = parse(
            """
            <html><head><style>
            p { color: #ff0000; font-size: 20px; }
            p.note { font-weight: bold; }
            </style></head><body><p class="note" style="font-size: 24px">文字</p></body></html>
            """.trimIndent(),
        )
        val paragraph = chapter.blocks.filterIsInstance<EpubBlock.Paragraph>().single()
        assertEquals("文字", paragraph.text)
        assertEquals(0xFFFF0000.toInt(), paragraph.style.color)
        assertEquals(24f, paragraph.style.fontSizeSp ?: 0f, 0.01f)
        assertTrue(paragraph.style.bold)
    }

    @Test
    fun explicitPageBreakRoleAndClassAreRecognized() {
        val chapter = parse(
            """
            <html><body>
            <p>一</p>
            <div class="page-break"></div>
            <p>二</p>
            <hr role="doc-pagebreak"/>
            <p>三</p>
            </body></html>
            """.trimIndent(),
        )
        assertEquals(5, chapter.blocks.size)
        assertTrue(chapter.blocks[0] is EpubBlock.Paragraph)
        assertTrue(chapter.blocks[1] is EpubBlock.PageBreak)
        assertTrue(chapter.blocks[2] is EpubBlock.Paragraph)
        assertTrue(chapter.blocks[3] is EpubBlock.PageBreak)
        assertTrue(chapter.blocks[4] is EpubBlock.Paragraph)
    }

    @Test
    fun simpleBookLoadsExternalCssAndParsesAllRequiredBlocks() {
        val entries = bookEntries("simple.epub")
        val chapter = XhtmlBlockParser.parse(
            xhtml = String(entries.getValue("OEBPS/Text/ch1.xhtml"), Charsets.UTF_8),
            chapterHref = "OEBPS/Text/ch1.xhtml",
            stylesheet = CssStylesheet.EMPTY,
            resolver = resolver(entries),
        )
        assertEquals("第一章 纯文字", chapter.title)
        val heading = chapter.blocks.filterIsInstance<EpubBlock.Heading>().single()
        assertEquals("第一章 纯文字", heading.text)
        assertEquals(36f, heading.style.fontSizeSp ?: 0f, 0.01f)
        assertTrue(heading.style.bold)
        assertEquals(EpubAlign.CENTER, heading.style.align)

        val paragraphs = chapter.blocks.filterIsInstance<EpubBlock.Paragraph>()
        assertTrue(paragraphs.any { it.text == "第一段 & 实体 <测试>。" })
        assertTrue(paragraphs.any { it.text.startsWith("第二段带行内颜色") })
        assertFalse(paragraphs.any { it.text.contains("隐藏") })
        val lead = paragraphs.first { it.text.startsWith("第一段") }
        assertEquals(EpubAlign.JUSTIFY, lead.style.align)
        assertEquals(0f, lead.style.indentEm, 0.01f)
        assertEquals(18f, lead.style.fontSizeSp ?: 0f, 0.01f)
        val last = paragraphs.first { it.text == "最后一段。" }
        assertEquals(2f, last.style.indentEm, 0.01f)

        val items = chapter.blocks.filterIsInstance<EpubBlock.ListItem>()
        assertEquals(4, items.size)
        assertEquals("无序一", items[0].text)
        assertEquals("无序二", items[1].text)
        assertFalse(items[0].ordered)
        assertEquals("有序一", items[2].text)
        assertEquals("有序二", items[3].text)
        assertTrue(items[2].ordered)
        assertEquals(2, items[3].index)

        val table = chapter.blocks.filterIsInstance<EpubBlock.Table>().single()
        assertEquals(listOf(listOf("列A", "列B"), listOf("值1", "值2")), table.rows)
        assertTrue(chapter.blocks.any { it is EpubBlock.HorizontalRule })
        assertTrue(chapter.blocks.any { it is EpubBlock.PageBreak })
    }

    @Test
    fun imageBookResolvesCoverAndInlineImage() {
        val entries = bookEntries("image.epub")
        val chapter = XhtmlBlockParser.parse(
            xhtml = String(entries.getValue("OEBPS/Text/ch1.xhtml"), Charsets.UTF_8),
            chapterHref = "OEBPS/Text/ch1.xhtml",
            stylesheet = CssStylesheet.EMPTY,
            resolver = resolver(entries),
        )
        val images = chapter.blocks.filterIsInstance<EpubBlock.Image>()
        assertEquals(2, images.size)
        assertEquals("OEBPS/Images/cover.png", images[0].src)
        assertEquals("封面", images[0].alt)
        assertEquals(120, images[0].width ?: -1)
        assertEquals(80, images[0].height ?: -1)
        assertEquals("OEBPS/Images/inline.png", images[1].src)

        val paragraphs = chapter.blocks.filterIsInstance<EpubBlock.Paragraph>().map { it.text }
        assertTrue(paragraphs.contains("图片段落前。"))
        assertTrue(paragraphs.contains("图片段落后。"))
        assertTrue(paragraphs.contains("行内"))
        assertTrue(paragraphs.contains("图片。"))
    }

    @Test
    fun footnoteBookKeepsDocumentOrderAndNestedToc() {
        val chapter = chapterOf("toc-footnotes.epub", "OEBPS/Text/ch1.xhtml")
        assertEquals("第一章 脚注", chapter.title)
        assertEquals(
            listOf("正文一[1]。", "脚注一内容。", "正文二[2]。", "脚注二内容。"),
            chapter.blocks.filterIsInstance<EpubBlock.Paragraph>().map { it.text },
        )
        val nav = String(
            bookEntries("toc-footnotes.epub").getValue("OEBPS/nav.xhtml"),
            Charsets.UTF_8,
        )
        assertEquals(2, Regex("<ol>").findAll(nav).count())
        assertTrue(nav.contains("Text/ch1.xhtml#s1"))
        assertTrue(nav.contains("Text/ch2.xhtml"))
    }

    @Test
    fun threeFixturesAreValidZipAndUnderTwoMegabytes() {
        for (name in listOf("simple.epub", "image.epub", "toc-footnotes.epub")) {
            val bytes = javaClass.classLoader.getResourceAsStream("epub/" + name)?.readBytes()
                ?: error("fixture not found: " + name)
            assertTrue(name + " size=" + bytes.size, bytes.size < 2 * 1024 * 1024)
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                val first = zip.nextEntry ?: error("empty zip: " + name)
                assertEquals("mimetype", first.name)
                assertEquals(ZipEntry.STORED, first.method)
                assertEquals("application/epub+zip", String(zip.readBytes(), Charsets.UTF_8))
                var count = 1
                while (zip.nextEntry != null) {
                    count++
                    zip.closeEntry()
                }
                assertTrue(name + " entries=" + count, count >= 5)
            }
        }
    }
}
