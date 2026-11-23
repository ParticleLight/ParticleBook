package app.particlebook.android.reader.search

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

class EpubTextExtractorTest {

    private val tempFiles = ArrayList<File>()

    @After
    fun cleanup() {
        tempFiles.forEach { it.delete() }
    }

    @Test
    fun flattensBlocksInDocumentOrder() {
        val file = epub(
            TestEpubFactory.Chapter(
                fileName = "ch1.xhtml",
                title = "第一章",
                body = """
                    <h1>第一章</h1>
                    <p>河从雾里来。</p>
                    <ul><li>项目一</li><li>项目二</li></ul>
                    <table><tr><th>列1</th><th>列2</th></tr><tr><td>a</td><td>b</td></tr></table>
                    <hr/>
                    <img src="../Images/pic.png" alt="插图"/>
                """.trimIndent(),
            ),
        )
        val chapter = EpubTextExtractor().extract(file).chapters.single()
        assertEquals("OEBPS/Text/ch1.xhtml", chapter.chapterHref)
        assertEquals("第一章", chapter.chapterTitle)
        assertEquals(0, chapter.firstTextBlockIndex)
        assertEquals(
            listOf("第一章", "河从雾里来。", "项目一", "项目二", "列1 列2", "a b", "插图").joinToString("\n"),
            chapter.text,
        )
    }

    @Test
    fun pointsLocatorAtFirstBlockThatCarriesText() {
        val file = epub(
            TestEpubFactory.Chapter(
                fileName = "ch1.xhtml",
                body = """<img src="../Images/blank.png" alt=""/><p>正文</p>""",
            ),
        )
        val chapter = EpubTextExtractor().extract(file).chapters.single()
        assertEquals(1, chapter.firstTextBlockIndex)
        assertEquals("正文", chapter.text)
    }

    @Test
    fun skipsImageOnlyAndBlankChapters() {
        val file = epub(
            TestEpubFactory.Chapter(fileName = "text.xhtml", body = "<p>有字</p>"),
            TestEpubFactory.Chapter(
                fileName = "image.xhtml",
                body = """<img src="../Images/pic.png" alt=""/>""",
            ),
            TestEpubFactory.Chapter(fileName = "blank.xhtml", body = "   "),
        )
        val extracted = EpubTextExtractor().extract(file)
        assertEquals(1, extracted.chapters.size)
        assertEquals(2, extracted.emptyChapterCount)
        assertEquals(0, extracted.brokenChapterCount)
        assertEquals(3, extracted.spineEntryCount)
    }

    @Test
    fun keepsGoingWhenChapterEntryIsMissingFromArchive() {
        val file = epub(
            TestEpubFactory.Chapter(fileName = "a.xhtml", body = "<p>A</p>"),
            TestEpubFactory.Chapter(fileName = "b.xhtml", body = "<p>B</p>"),
            missingEntries = setOf("b.xhtml"),
        )
        val extracted = EpubTextExtractor().extract(file)
        assertEquals(1, extracted.chapters.size)
        assertEquals(1, extracted.brokenChapterCount)
        assertTrue(extracted.warnings.any { it.contains("CHAPTER_MISSING") })
    }

    @Test
    fun deduplicatesRepeatedSpineEntries() {
        val file = epub(
            TestEpubFactory.Chapter(fileName = "a.xhtml", body = "<p>A</p>"),
            TestEpubFactory.Chapter(fileName = "b.xhtml", body = "<p>B</p>"),
            spine = listOf("a.xhtml", "a.xhtml", "b.xhtml"),
        )
        val extracted = EpubTextExtractor().extract(file)
        assertEquals(listOf("a.xhtml", "b.xhtml"), extracted.chapters.map { it.chapterHref.substringAfterLast('/') })
        assertEquals(3, extracted.spineEntryCount)
        assertEquals(1, extracted.duplicateSpineEntryCount)
    }

    @Test
    fun readsBundledSampleBook() {
        val seed = File("src/main/assets/seed/seed-01.epub")
        Assume.assumeTrue("seed book missing: " + seed.absolutePath, seed.isFile)
        val extracted = EpubTextExtractor().extract(seed)
        assertEquals(3, extracted.chapters.size)
        assertEquals(0, extracted.brokenChapterCount)
        assertTrue(extracted.chapters.all { it.text.isNotBlank() })
        assertEquals(listOf("ch1.xhtml", "ch2.xhtml", "ch3.xhtml"), extracted.chapters.map { it.chapterHref.substringAfterLast('/') })
    }

    private fun epub(
        vararg chapters: TestEpubFactory.Chapter,
        spine: List<String> = chapters.map { it.fileName },
        missingEntries: Set<String> = emptySet(),
    ): File {
        val file = File.createTempFile("pb-extract-", ".epub")
        tempFiles += file
        return TestEpubFactory.write(file, chapters.toList(), spine, missingEntries)
    }
}
