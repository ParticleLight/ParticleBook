package app.particlebook.android.ui.data

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * task-25：冷启恢复用到的纯函数（不依赖 Android Context，可直接 JVM 单测）。
 * 覆盖 SAF 复制文件名 uuid/扩展名清洗、文件头嗅探、冷启候选过滤、恢复标题。
 */
class LibraryFileRecoveryTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("pb-library-recovery-").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun copyFileNameEmbedsTimestampAndUuidPrefix() {
        val name = libraryCopyFileName("zlib", "epub", 1_700_000_000_000L, "abcdef12-3456-7890-abcd-ef1234567890")
        assertEquals("zlib-1700000000000-abcdef12.epub", name)

        // 同一毫秒两次导入：uuid 不同 → 文件名不同，不会互相覆盖。
        val other = libraryCopyFileName("zlib", "epub", 1_700_000_000_000L, "ffffffff-0000")
        assertNotEquals(name, other)

        // 大写 uuid/前缀按小写落盘，避免大小写不敏感文件系统上的歧义。
        assertTrue(
            libraryCopyFileName("import", "pdf", 5L, "ABCDEF12-XXXX")
                .startsWith("import-5-abcdef12."),
        )
    }

    @Test
    fun sanitizeCopyExtensionKeepsOnlyAlphanumerics() {
        assertEquals("pdf", sanitizeCopyExtension("PDF", BookFormat.Pdf))
        assertEquals("epub", sanitizeCopyExtension("EPUB", BookFormat.Pdf))

        // 异常 DISPLAY_NAME（含路径分隔符）不得把扩展名带出目录。
        val pathLike = sanitizeCopyExtension("pdf/../../evil", BookFormat.Epub)
        assertTrue(pathLike.all { it.isLetterOrDigit() })
        assertTrue(pathLike.isNotEmpty())

        // 空扩展名按格式兜底。
        assertEquals("epub", sanitizeCopyExtension("", BookFormat.Epub))
        assertEquals("pdf", sanitizeCopyExtension("   ", BookFormat.Pdf))
    }

    @Test
    fun sniffBookFormatUsesFirstBytes() {
        assertEquals(BookFormat.Pdf, sniffBookFormat(write("a.bin", "%PDF-1.7".toByteArray())))
        assertEquals(
            BookFormat.Epub,
            sniffBookFormat(write("b.bin", byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00))),
        )
        assertNull(sniffBookFormat(write("c.bin", "plain text".toByteArray())))
        assertNull("读不到文件应返回 null", sniffBookFormat(File(root, "missing.epub")))
    }

    @Test
    fun recoverableFileFilterRejectsPartialAndNonBookFiles() {
        assertTrue(isRecoverableLibraryFile(write("import-1-abcd.epub", EPUB_HEADER)))
        assertTrue(isRecoverableLibraryFile(write("zlib-1-abcd.pdf", "%PDF-1.7".toByteArray())))

        assertFalse("复制中间态不得登记", isRecoverableLibraryFile(write("import-1-abcd.epub.tmp", EPUB_HEADER)))
        assertFalse("非 epub/pdf 扩展名不得登记", isRecoverableLibraryFile(write("notes.txt", EPUB_HEADER)))
        assertFalse("半个复制文件（无书籍头）不得登记", isRecoverableLibraryFile(write("partial.epub", "half".toByteArray())))
        assertFalse("目录不得登记", isRecoverableLibraryFile(File(root, "folder.epub").apply { mkdirs() }))
    }

    @Test
    fun recoveredTitleHidesGeneratedCopyNames() {
        assertEquals("恢复的书籍", recoveredBookTitle(File(root, "import-1700000000000-ab12cd34.epub")))
        assertEquals("恢复的书籍", recoveredBookTitle(File(root, "zlib-1700000000000.epub")))
        assertEquals("我的书", recoveredBookTitle(File(root, "我的书.epub")))
    }

    @Test
    fun isoUtcTimestampMatchesFixtureShape() {
        assertEquals("1970-01-01T00:00:00.000Z", isoUtcTimestamp(0L))
        assertTrue(
            isoUtcTimestamp(1_700_000_000_000L)
                .matches(Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$")),
        )
    }

    // ------------------------------------------------------------------ 纯文本格式识别

    @Test
    fun textFormatByExtensionIsCaseInsensitiveAndRejectsUnknownTypes() {
        // 扩展名大小写不敏感（SAF DISPLAY_NAME 大小写不可控）。
        assertEquals(BookFormat.Text, textFormatByExtension("TXT"))
        assertEquals(BookFormat.Text, textFormatByExtension("text"))
        assertEquals(BookFormat.Markdown, textFormatByExtension("MD"))
        assertEquals(BookFormat.Markdown, textFormatByExtension("Markdown"))

        // 无扩展名 / 非文本格式不能猜成文本，否则 AZW3 会被送进文本阅读器。
        assertNull(textFormatByExtension(""))
        assertNull(textFormatByExtension("azw3"))
    }

    @Test
    fun looksLikePlainTextAcceptsUtf8ChineseWithoutBom() {
        // UTF-8 中文（无 BOM）编码里没有 0x00，必须认作文本。
        val file = write("chinese.txt", "第一章 纯文本阅读器\n中文标点：测试。".toByteArray(Charsets.UTF_8))
        assertTrue(looksLikePlainText(file))
    }

    @Test
    fun looksLikePlainTextRejectsNulBytes() {
        assertFalse(
            "前 8 KiB 出现 0x00 视为二进制",
            looksLikePlainText(write("binary.txt", byteArrayOf(0x48, 0x65, 0x00, 0x6C, 0x6C, 0x6F))),
        )

        // 只扫前 8 KiB：窗口之后才出现的 0x00 不影响判定（大文件不做全量扫描）。
        val lateNul = ByteArray(8 * 1024 + 16) { 'a'.code.toByte() }.also {
            it[8 * 1024 + 1] = 0x00
        }
        assertTrue(looksLikePlainText(write("late-nul.txt", lateNul)))
    }

    @Test
    fun looksLikePlainTextAcceptsUtf16Bom() {
        // UTF-16 正文必然含大量 0x00，靠 FF FE / FE FF BOM 放行。
        assertTrue(looksLikePlainText(write("le.txt", utf16WithBom("纯文本内容", littleEndian = true))))
        assertTrue(looksLikePlainText(write("be.txt", utf16WithBom("纯文本内容", littleEndian = false))))
    }

    @Test
    fun looksLikePlainTextRejectsEmptyAndMissingFiles() {
        assertFalse("0 字节文件不算文本", looksLikePlainText(write("empty.txt", ByteArray(0))))
        assertFalse("不存在的文件不算文本", looksLikePlainText(File(root, "missing.txt")))
    }

    @Test
    fun sniffBookFormatPrefersPdfAndZipMagic() {
        assertEquals(
            BookFormat.Pdf,
            sniffBookFormat(write("book.bin", "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF\n".toByteArray())),
        )
        assertEquals(
            BookFormat.Epub,
            sniffBookFormat(write("book.zip", byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00))),
        )
    }

    @Test
    fun sniffBookFormatFallsBackToTextExtension() {
        assertEquals(BookFormat.Text, sniffBookFormat(write("novel.txt", "山河故人".toByteArray(Charsets.UTF_8))))
        assertEquals(BookFormat.Markdown, sniffBookFormat(write("notes.md", "# 标题\n\n- 列表".toByteArray(Charsets.UTF_8))))

        // 文本扩展名但内容是二进制：不能登记成文本。
        assertNull(sniffBookFormat(write("fake.txt", byteArrayOf(0x00, 0x01, 0x02))))
        assertNull(sniffBookFormat(write("blob.bin", byteArrayOf(0x00, 0x01, 0x02))))
    }

    @Test
    fun detectBookFormatUsesHeaderWhenExtensionMissing() {
        // 无扩展名（下载器没给后缀）只能靠文件头。
        assertEquals(BookFormat.Pdf, detectBookFormat(write("no-ext", "%PDF-1.7\n%âãÏÓ\n".toByteArray(Charsets.ISO_8859_1))))
        assertEquals(BookFormat.Markdown, detectBookFormat(write("readme.md", "# 标题".toByteArray())))
        // 扩展名优先于内容：.pdf 即使文件头不合法也按 PDF 处理（扩展名来自下载器，可信）。
        assertEquals(BookFormat.Pdf, detectBookFormat(write("broken.pdf", "not a pdf".toByteArray())))
        assertEquals(BookFormat.Unsupported, detectBookFormat(write("random.dat", byteArrayOf(0x13, 0x37, 0x00, 0x42))))
    }

    @Test
    fun isRecoverableLibraryFileAcceptsOnlyRealBooks() {
        assertTrue(isRecoverableLibraryFile(write("novel.txt", "正文".toByteArray(Charsets.UTF_8))))
        assertTrue(isRecoverableLibraryFile(write("notes.md", "# 正文".toByteArray(Charsets.UTF_8))))

        assertFalse("扩展名不认识不得登记", isRecoverableLibraryFile(write("novel.bin", "正文".toByteArray())))
        assertFalse(
            "含 NUL 的 .txt 内容不像文本，不得登记",
            isRecoverableLibraryFile(write("fake.txt", byteArrayOf(0x41, 0x00, 0x42))),
        )
    }

    @Test
    fun classifyImportedFileTreatsPlainTextAsComplete() {
        assertEquals(
            ImportedFileIntegrity.COMPLETE,
            classifyImportedFile(write("novel.txt", "纯文本正文".toByteArray(Charsets.UTF_8))),
        )
        assertEquals(
            ImportedFileIntegrity.COMPLETE,
            classifyImportedFile(write("notes.md", "# 标题".toByteArray(Charsets.UTF_8))),
        )
    }

    @Test
    fun classifyImportedFileMarksUnknownForBinaryTextAndMissing() {
        // 文本扩展名但含 NUL：既不是完整文本，也无法断言是坏书 → UNKNOWN（不能据此删旧副本）。
        assertEquals(
            ImportedFileIntegrity.UNKNOWN,
            classifyImportedFile(write("fake.txt", byteArrayOf(0x41, 0x00, 0x42))),
        )
        assertEquals(ImportedFileIntegrity.UNKNOWN, classifyImportedFile(write("blob.bin", byteArrayOf(0x41, 0x42))))
        assertEquals(ImportedFileIntegrity.UNKNOWN, classifyImportedFile(File(root, "missing.txt")))
        // 0 字节是唯一能「确认损坏」的无争议形态。
        assertEquals(ImportedFileIntegrity.CORRUPT, classifyImportedFile(write("empty.txt", ByteArray(0))))
    }

    @Test
    fun classifyImportedFileChecksPdfTailAndZipCentralDirectory() {
        assertEquals(
            ImportedFileIntegrity.COMPLETE,
            classifyImportedFile(write("ok.pdf", "%PDF-1.7\n1 0 obj\n<<>>\nendobj\ntrailer\n%%EOF\n".toByteArray())),
        )
        assertEquals(
            ImportedFileIntegrity.CORRUPT,
            classifyImportedFile(write("truncated.pdf", "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n".toByteArray())),
        )
        assertEquals(ImportedFileIntegrity.COMPLETE, classifyImportedFile(writeZip("ok.epub")))
        // 只有 ZIP 头、中央目录被截断 —— 下载中断的典型形态。
        assertEquals(
            ImportedFileIntegrity.CORRUPT,
            classifyImportedFile(
                write("truncated.epub", byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00, 0x00, 0x00)),
            ),
        )
    }

    private fun write(name: String, bytes: ByteArray): File = File(root, name).apply { writeBytes(bytes) }

    /** UTF-16 文本（带 FF FE / FE FF BOM），用于验证 BOM 放行分支。 */
    private fun utf16WithBom(text: String, littleEndian: Boolean): ByteArray {
        val bom = if (littleEndian) {
            byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        } else {
            byteArrayOf(0xFE.toByte(), 0xFF.toByte())
        }
        val body = if (littleEndian) {
            text.toByteArray(Charsets.UTF_16LE)
        } else {
            text.toByteArray(Charsets.UTF_16BE)
        }
        return bom + body
    }

    /** 结构完整的 ZIP（含 EOCD + 中央目录），用于验证 EPUB 完整性判定。 */
    private fun writeZip(name: String): File {
        val file = File(root, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/epub+zip".toByteArray())
            zip.closeEntry()
        }
        return file
    }

    private companion object {
        val EPUB_HEADER = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)
    }
}
