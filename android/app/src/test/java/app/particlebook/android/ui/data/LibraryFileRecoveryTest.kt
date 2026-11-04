package app.particlebook.android.ui.data

import java.io.File
import java.nio.file.Files
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

    private fun write(name: String, bytes: ByteArray): File = File(root, name).apply { writeBytes(bytes) }

    private companion object {
        val EPUB_HEADER = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)
    }
}
