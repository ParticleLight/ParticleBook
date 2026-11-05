package app.particlebook.android.reader.epub.parser

import org.junit.Assert.assertTrue
import org.junit.Test

class M0ProbeJsonTest {

    @Test
    fun serializesAllProbeFieldsAsJson() {
        val result = M0Probe.M0Result(
            file = "C:/books/seed.epub",
            bookTitle = "故乡的河",
            spineCount = 3,
            tocCount = 3,
            manifestCount = 5,
            archiveBytes = 93564,
            chapterIndex = 0,
            chapterHref = "OEBPS/Text/ch1.xhtml",
            chapterTitle = "第一章 河从雾里来",
            blockCount = 4,
            lineCount = 12,
            pageCount = 3,
            openMs = 11,
            chapterReadMs = 7,
            layoutMs = 23,
            totalMs = 41,
            memoryBeforeKb = 1000,
            memoryAfterLayoutKb = 1200,
            memoryAfterCloseKb = 1005,
            peakMemoryKb = 1200,
            warnings = emptyList(),
        )
        val json = result.toJson()
        assertTrue(json.startsWith("{\"file\":\"C:/books/seed.epub\""))
        assertTrue(json.contains("\"memoryAfterLayoutKb\":1200"))
        assertTrue(json.contains("\"memoryAfterCloseKb\":1005"))
        assertTrue(json.contains("\"peakMemoryKb\":1200"))
        assertTrue(json.contains("\"error\":null"))
    }

    @Test
    fun escapesQuotesAndNewlinesInErrorAndWarnings() {
        val result = M0Probe.M0Result(
            file = "a\"b.epub",
            bookTitle = "t",
            spineCount = 1,
            tocCount = 0,
            manifestCount = 0,
            archiveBytes = 0,
            chapterIndex = 0,
            chapterHref = "",
            chapterTitle = null,
            blockCount = 0,
            lineCount = 0,
            pageCount = 0,
            openMs = 0,
            chapterReadMs = 0,
            layoutMs = 0,
            totalMs = 0,
            memoryBeforeKb = -1,
            memoryAfterLayoutKb = -1,
            memoryAfterCloseKb = -1,
            peakMemoryKb = -1,
            warnings = listOf("line1\nline2"),
            error = "IllegalStateException: bad \"opf\"\nnext",
        )
        val json = result.toJson()
        assertTrue(json.contains("\"file\":\"a\\\"b.epub\""))
        assertTrue(json.contains("line1\\nline2"))
        assertTrue(json.contains("bad \\\"opf\\\"\\nnext"))
    }
}
