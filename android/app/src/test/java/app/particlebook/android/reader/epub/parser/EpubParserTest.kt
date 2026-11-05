package app.particlebook.android.reader.epub.parser

import app.particlebook.android.reader.epub.EpubBlock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubParserTest {

    private val tempFiles = ArrayList<File>()

    @After
    fun cleanup() {
        tempFiles.forEach { it.delete() }
    }

    @Test
    fun parsesEpub3NavAndChapterBlocks() {
        val file = epub3File()
        EpubParser.open(file).use { parser ->
            assertEquals("测试书", parser.book.metadata.title)
            assertEquals("作者甲、作者乙", parser.book.metadata.author)
            assertEquals("zh-CN", parser.book.metadata.language)
            assertEquals("urn:test:epub3", parser.book.metadata.identifier)
            assertEquals(5, parser.book.manifest.size)
            assertEquals(2, parser.book.spine.size)
            assertEquals("OEBPS/Text/ch1.xhtml", parser.book.spine[0].href)
            assertFalse(parser.book.spine[1].linear)
            assertTrue(parser.manifestCssHrefs().contains("OEBPS/styles/book.css"))
            assertTrue(parser.hasEntry("OEBPS/Images/pic.png"))

            assertEquals(2, parser.book.toc.size)
            assertEquals("第一章", parser.book.toc[0].title)
            assertEquals("OEBPS/Text/ch1.xhtml", parser.book.toc[0].href)
            assertEquals(1, parser.book.toc[0].children.size)
            assertEquals("OEBPS/Text/ch1.xhtml#sec1", parser.book.toc[0].children[0].href)
            assertEquals("第二章", parser.book.toc[1].title)

            val chapter = parser.readChapter(0)
            assertEquals("OEBPS/Text/ch1.xhtml", chapter.href)
            assertEquals("第一章", chapter.title)

            val heading = chapter.blocks.filterIsInstance<EpubBlock.Heading>().first()
            assertEquals(1, heading.level)
            assertEquals("第一章", heading.text)
            assertEquals(24f, heading.style.fontSizeSp!!, 0.01f)
            assertTrue(heading.style.bold)

            val paragraphs = chapter.blocks.filterIsInstance<EpubBlock.Paragraph>()
            assertTrue(paragraphs.any { it.text.contains("第一段") && it.text.contains("结束") })
            assertFalse(paragraphs.any { it.text.contains("隐藏段") })

            val listItems = chapter.blocks.filterIsInstance<EpubBlock.ListItem>()
            assertEquals(listOf("项目一", "项目二"), listItems.map { it.text })
            assertEquals(listOf(1, 2), listItems.map { it.index })
            assertFalse(listItems[0].ordered)

            val table = chapter.blocks.filterIsInstance<EpubBlock.Table>().single()
            assertEquals(listOf(listOf("列1", "列2"), listOf("a", "b")), table.rows)
            assertEquals(1, chapter.blocks.count { it is EpubBlock.HorizontalRule })

            val image = chapter.blocks.filterIsInstance<EpubBlock.Image>().single()
            assertEquals("OEBPS/Images/pic.png", image.src)
            assertEquals("图", image.alt)
            assertEquals(120, image.width)
            assertEquals(80, image.height)

            val pageBreakIndex = chapter.blocks.indexOfFirst { it is EpubBlock.PageBreak }
            val newPageIndex = chapter.blocks.indexOfFirst {
                it is EpubBlock.Paragraph && it.text == "新页"
            }
            assertTrue(pageBreakIndex >= 0)
            assertTrue(newPageIndex > pageBreakIndex)
        }
    }

    @Test
    fun parsesEpub2NcxToc() {
        val file = epub2NcxFile()
        EpubParser.open(file).use { parser ->
            assertEquals("NCX 测试书", parser.book.metadata.title)
            assertEquals(2, parser.book.toc.size)
            assertEquals("第一章", parser.book.toc[0].title)
            assertEquals("OEBPS/Text/ch1.xhtml", parser.book.toc[0].href)
            assertEquals(1, parser.book.toc[0].children.size)
            assertEquals("第一节", parser.book.toc[0].children[0].title)
            assertEquals("OEBPS/Text/ch1.xhtml#sec1", parser.book.toc[0].children[0].href)
            assertEquals("第二章", parser.book.toc[1].title)
            val chapter = parser.readChapter(0)
            assertEquals("第一章标题", chapter.title)
            assertTrue(chapter.blocks.any { it is EpubBlock.Paragraph })
        }
    }

    @Test
    fun decodesGb18030ChapterAndNormalizesEntities() {
        val chapter = """
            <?xml version="1.0" encoding="gb18030"?>
            <html xmlns="http://www.w3.org/1999/xhtml"><head><title>编码测试</title></head>
            <body><p>中文内容 A&nbsp;B &amp; C</p></body></html>
        """.trimIndent()
        val file = singleChapterFile(chapter.toByteArray(Charset.forName("GB18030")))
        EpubParser.open(file).use { parser ->
            val parsed = parser.readChapter(0)
            val text = parsed.blocks.filterIsInstance<EpubBlock.Paragraph>().joinToString(" ") { it.text }
            assertTrue("实际文本：$text", text.contains("中文内容"))
            assertTrue("实际文本：$text", text.contains("A B"))
            assertTrue("实际文本：$text", text.contains("& C"))
        }
    }

    @Test
    fun rejectsBadZip() {
        val file = rawFile("not a zip".toByteArray())
        expectFailure("BAD_ZIP") { EpubParser.open(file) }
    }

    @Test
    fun rejectsMissingOpf() {
        val file = fileWith(
            entry("mimetype", "application/epub+zip"),
            entry("META-INF/container.xml", containerXml("OEBPS/missing.opf")),
        )
        expectFailure("MISSING_OPF") { EpubParser.open(file) }
    }

    @Test
    fun rejectsEmptySpine() {
        val opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="uid">urn:test:empty</dc:identifier>
                <dc:title>空 spine</dc:title>
              </metadata>
              <manifest>
                <item id="ch1" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine/>
            </package>
        """.trimIndent()
        val file = fileWith(
            entry("mimetype", "application/epub+zip"),
            entry("META-INF/container.xml", containerXml("OEBPS/content.opf")),
            entry("OEBPS/content.opf", opf),
            entry("OEBPS/Text/ch1.xhtml", simpleChapter("一")),
        )
        expectFailure("EMPTY_SPINE") { EpubParser.open(file) }
    }

    @Test
    fun reportsMissingChapterAndBadIndex() {
        val file = epub3File(includeChapterEntry = false)
        EpubParser.open(file).use { parser ->
            expectFailure("CHAPTER_MISSING") { parser.readChapter(0) }
            expectFailure("CHAPTER_INDEX") { parser.readChapter(99) }
        }
    }

    @Test
    fun parsesRealSeedEpub() {
        val seed = listOf(
            File("src/main/assets/seed/seed-01.epub"),
            File("app/src/main/assets/seed/seed-01.epub"),
        ).firstOrNull { it.isFile }
        Assume.assumeNotNull(seed)
        EpubParser.open(seed!!).use { parser ->
            assertEquals("故乡的河", parser.book.metadata.title)
            assertEquals("沈岸", parser.book.metadata.author)
            assertEquals(3, parser.book.spine.size)
            assertEquals(3, parser.book.toc.size)
            assertEquals("第一章 河从雾里来", parser.book.toc[0].title)
            val chapter = parser.readChapter(0)
            assertEquals("第一章 河从雾里来", chapter.title)
            assertTrue(chapter.blocks.filterIsInstance<EpubBlock.Paragraph>().size >= 3)
            assertTrue(parser.warnings.isEmpty())
        }
    }

    private fun expectFailure(prefix: String, block: () -> Unit) {
        try {
            block()
            fail("expected EpubParseException starting with $prefix")
        } catch (e: EpubParseException) {
            assertNotNull(e.message)
            assertTrue("expected $prefix, got ${e.message}", e.message!!.startsWith(prefix))
        }
    }

    private fun epub3File(includeChapterEntry: Boolean = true): File {
        val opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="uid">urn:test:epub3</dc:identifier>
                <dc:title>测试书</dc:title>
                <dc:creator>作者甲</dc:creator>
                <dc:creator>作者乙</dc:creator>
                <dc:language>zh-CN</dc:language>
              </metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="css" href="styles/book.css" media-type="text/css"/>
                <item id="img" href="Images/pic.png" media-type="image/png"/>
                <item id="ch1" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="ch2" href="Text/ch2.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine>
                <itemref idref="ch1"/>
                <itemref idref="ch2" linear="no"/>
              </spine>
            </package>
        """.trimIndent()
        val nav = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
            <body><nav epub:type="toc"><ol>
              <li><a href="Text/ch1.xhtml">第一章</a>
                <ol><li><a href="Text/ch1.xhtml#sec1">第一节</a></li></ol>
              </li>
              <li><a href="Text/ch2.xhtml">第二章</a></li>
            </ol></nav></body></html>
        """.trimIndent()
        val chapter1 = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml"><head>
            <title>第一章</title>
            <link rel="stylesheet" href="../styles/book.css"/>
            </head><body>
            <h1>第一章</h1>
            <p>第一段&nbsp;文本 &amp; 结束。</p>
            <p style="display:none">隐藏段</p>
            <ul><li>项目一</li><li>项目二</li></ul>
            <table><tr><th>列1</th><th>列2</th></tr><tr><td>a</td><td>b</td></tr></table>
            <hr/>
            <div style="page-break-before:always">新页</div>
            <img src="../Images/pic.png" alt="图" width="120" height="80"/>
            </body></html>
        """.trimIndent()
        val chapter2 = simpleChapter("第二章内容", title = "第二章")
        val css = """
            h1 { font-size: 24px; font-weight: bold; }
            p { text-indent: 2em; }
        """.trimIndent()
        val entries = ArrayList<Pair<String, ByteArray>>()
        entries += "mimetype" to "application/epub+zip".toByteArray()
        entries += "META-INF/container.xml" to containerXml("OEBPS/content.opf").toByteArray()
        entries += "OEBPS/content.opf" to opf.toByteArray()
        entries += "OEBPS/nav.xhtml" to nav.toByteArray()
        entries += "OEBPS/styles/book.css" to css.toByteArray()
        entries += "OEBPS/Text/ch2.xhtml" to chapter2.toByteArray()
        entries += "OEBPS/Images/pic.png" to byteArrayOf(1, 2, 3, 4)
        if (includeChapterEntry) entries += "OEBPS/Text/ch1.xhtml" to chapter1.toByteArray()
        return fileWith(entries)
    }

    private fun epub2NcxFile(): File {
        val opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="uid">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="uid">urn:test:epub2</dc:identifier>
                <dc:title>NCX 测试书</dc:title>
              </metadata>
              <manifest>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <item id="ch1" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="ch2" href="Text/ch2.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine toc="ncx"><itemref idref="ch1"/><itemref idref="ch2"/></spine>
            </package>
        """.trimIndent()
        val ncx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <navMap>
                <navPoint id="n1"><navLabel><text>第一章</text></navLabel><content src="Text/ch1.xhtml"/>
                  <navPoint id="n1-1"><navLabel><text>第一节</text></navLabel><content src="Text/ch1.xhtml#sec1"/></navPoint>
                </navPoint>
                <navPoint id="n2"><navLabel><text>第二章</text></navLabel><content src="Text/ch2.xhtml"/></navPoint>
              </navMap>
            </ncx>
        """.trimIndent()
        return fileWith(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to containerXml("OEBPS/content.opf").toByteArray(),
            "OEBPS/content.opf" to opf.toByteArray(),
            "OEBPS/toc.ncx" to ncx.toByteArray(),
            "OEBPS/Text/ch1.xhtml" to simpleChapter("第一章内容", title = "第一章标题").toByteArray(),
            "OEBPS/Text/ch2.xhtml" to simpleChapter("第二章内容", title = "第二章").toByteArray(),
        )
    }

    private fun singleChapterFile(chapterBytes: ByteArray): File {
        val opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>编码书</dc:title></metadata>
              <manifest><item id="ch1" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/></manifest>
              <spine><itemref idref="ch1"/></spine>
            </package>
        """.trimIndent()
        return fileWith(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to containerXml("OEBPS/content.opf").toByteArray(),
            "OEBPS/content.opf" to opf.toByteArray(),
            "OEBPS/Text/ch1.xhtml" to chapterBytes,
        )
    }

    private fun simpleChapter(text: String, title: String = "标题"): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml"><head><title>$title</title></head>
        <body><h2>$title</h2><p>$text</p></body></html>
    """.trimIndent()

    private fun containerXml(opfPath: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="$opfPath" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
    """.trimIndent()

    private fun entry(name: String, content: String): Pair<String, ByteArray> =
        name to content.toByteArray()

    private fun fileWith(vararg entries: Pair<String, ByteArray>): File = fileWith(entries.toList())

    private fun rawFile(bytes: ByteArray): File {
        val file = File.createTempFile("epub-bad-", ".epub")
        tempFiles += file
        file.writeBytes(bytes)
        return file
    }

    private fun fileWith(entries: List<Pair<String, ByteArray>>): File {
        val file = File.createTempFile("epub-test-", ".epub")
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
}
