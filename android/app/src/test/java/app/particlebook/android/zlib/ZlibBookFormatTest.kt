package app.particlebook.android.zlib

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** task-51 D14：按文件头纠正扩展名（detail.format 不可信）。 */
class ZlibBookFormatTest {

    @Test
    fun detectsPdfAndEpubMagic() {
        assertEquals(ZlibBookFormat.PDF, ZlibBookFormat.detect("%PDF-1.7".toByteArray()))
        assertEquals(
            ZlibBookFormat.EPUB,
            ZlibBookFormat.detect(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)),
        )
        assertEquals(
            ZlibBookFormat.EPUB,
            ZlibBookFormat.detect(byteArrayOf(0x50, 0x4B, 0x05, 0x06, 0x00, 0x00)),
        )
        assertNull(ZlibBookFormat.detect("plain text".toByteArray()))
    }

    @Test
    fun correctedNameReplacesWrongExtension() {
        assertEquals("book.epub", ZlibBookFormat.correctedName("book.pdf", ZlibBookFormat.EPUB))
        assertEquals("book.pdf", ZlibBookFormat.correctedName("book", ZlibBookFormat.PDF))
        assertEquals("book.pdf", ZlibBookFormat.correctedName("book.PDF", ZlibBookFormat.PDF) ?: "book.pdf")
        assertNull(ZlibBookFormat.correctedName("book.pdf", ZlibBookFormat.PDF))
        assertNull(ZlibBookFormat.correctedName("book.pdf", null))
    }

    @Test
    fun sniffReadsOnlyFileHeader() {
        val dir = Files.createTempDirectory("zlib-format").toFile()
        try {
            val pdf = File(dir, "a.pdf")
            pdf.writeBytes("%PDF-1.7 hello".toByteArray())
            assertEquals(ZlibBookFormat.PDF, ZlibBookFormat.sniff(pdf))
            val epub = File(dir, "b.epub")
            epub.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x01, 0x02))
            assertEquals(ZlibBookFormat.EPUB, ZlibBookFormat.sniff(epub))
            assertNull(ZlibBookFormat.sniff(File(dir, "missing.pdf")))
        } finally {
            dir.deleteRecursively()
        }
    }
}
