package app.particlebook.android.ui

import app.particlebook.android.ui.data.Book
import app.particlebook.android.ui.data.BookFormat
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T62(P0/P1-2)：SAF 完整性误判与书架命中完整性。
 *
 * 回归路径：
 * - provider 返回 pipe/云文档时 channel.size()==0，旧逻辑直接判 INCOMPLETE，随后下载复用同一
 *   content:// 并用 openOutputStream("w") 截断唯一完整副本。这里锁定「0 长度不可信」与
 *   「只有 CANCELLED/本次新建才允许截断复用」两条纯逻辑。
 * - 书架命中旧逻辑只判 File.isFile，0 字节/HTML/截断 PDF 会被补成 COMPLETED。这里锁定只选完整文件。
 */
class ZlibDownloadSafReuseTest {

    private val tempDir: File = Files.createTempDirectory("pb-zlib-saf-reuse").toFile()

    @After
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    @Test
    fun descriptorSizeZeroOrNegativeIsUnknown() {
        assertFalse(zlibSafDescriptorSizeReliable(0L))
        assertFalse(zlibSafDescriptorSizeReliable(-1L))
        assertTrue(zlibSafDescriptorSizeReliable(1L))
    }

    @Test
    fun onlyCancelledRecordMayTruncateExistingSafDocument() {
        assertEquals(
            ZlibSafTruncateDecision.ALLOW,
            zlibSafTruncateDecision(ZlibDownloadStatus.CANCELLED),
        )
        listOf(
            ZlibDownloadStatus.FAILED,
            ZlibDownloadStatus.COMPLETED,
            ZlibDownloadStatus.DOWNLOADING,
            ZlibDownloadStatus.PREPARING,
            null,
        ).forEach { status ->
            assertEquals(
                "status=" + status,
                ZlibSafTruncateDecision.CREATE_NEW,
                zlibSafTruncateDecision(status),
            )
        }
    }

    @Test
    fun targetCreatedThisRunMayBeTruncated() {
        assertEquals(
            ZlibSafTruncateDecision.ALLOW,
            zlibSafTruncateDecision(null, createdThisRun = true),
        )
        assertEquals(
            ZlibSafTruncateDecision.ALLOW,
            zlibSafTruncateDecision(ZlibDownloadStatus.FAILED, createdThisRun = true),
        )
    }

    @Test
    fun unknownStateIsNotIncomplete() {
        assertNotEquals(ZlibDownloadTargetState.INCOMPLETE, ZlibDownloadTargetState.UNKNOWN)
        assertNotEquals(ZlibDownloadTargetState.COMPLETE, ZlibDownloadTargetState.UNKNOWN)
        assertNotEquals(ZlibDownloadTargetState.MISSING, ZlibDownloadTargetState.UNKNOWN)
    }

    @Test
    fun completeLibraryBookIsSelected() {
        val book = book(write("complete.pdf", pdfBytes(complete = true)))
        assertSame(book, zlibCompleteLibraryBook(listOf(book)))
    }

    @Test
    fun emptyLibraryBookIsRejected() {
        val book = book(write("empty.pdf", ByteArray(0)))
        assertNull(zlibCompleteLibraryBook(listOf(book)))
    }

    @Test
    fun htmlErrorPageInLibraryIsRejected() {
        val html = ("<html>" + "x".repeat(1_200) + "</html>").toByteArray()
        val book = book(write("error.html", html))
        assertNull(zlibCompleteLibraryBook(listOf(book)))
    }

    @Test
    fun truncatedPdfInLibraryIsRejected() {
        val book = book(write("truncated.pdf", pdfBytes(complete = false)))
        assertNull(zlibCompleteLibraryBook(listOf(book)))
    }

    @Test
    fun managerSourceKeepsSafReuseAndLibraryGuards() {
        val source = sourceFile("src/main/java/app/particlebook/android/ui/ZlibDownloadManager.kt")
        assertTrue("fd 长度 0 必须视为未知", source.contains("zlibSafDescriptorSizeReliable"))
        assertTrue("未知长度要复制到 cacheDir 复核", source.contains("zlib-inspect-"))
        assertTrue("未知状态不能被当成 INCOMPLETE", source.contains("ZlibDownloadTargetState.UNKNOWN"))
        assertTrue("截断复用只能由显式策略放行", source.contains("zlibSafTruncateDecision"))
        assertTrue("下载入口要传复用许可", source.contains("allowSafTargetReuse"))
        assertTrue("保留旧文档要留日志", source.contains("zlib_download_saf_target_preserved"))
        assertTrue("书架命中要过滤不完整文件", source.contains("zlibCompleteLibraryBook"))
        assertFalse("旧的 INTERRUPTED 目标复用逻辑必须移除", source.contains("reusedSafDoc"))
        assertFalse("完整性检查失败不能直接返回 INCOMPLETE", source.contains("getOrNull()\n        if (viaDescriptor != null) return@withContext viaDescriptor"))
        val importIndex = source.indexOf("tryImportCompleteSafTarget(context, task, store, run, began)")
        val createIndex = source.indexOf("val target = reused ?: DownloadLocation.createDocument")
        assertTrue("先尝试导入完整目标，再决定是否新建/复用文档", importIndex >= 0 && importIndex < createIndex)
        val sizeGuardIndex = source.indexOf("zlibSafDescriptorSizeReliable(size)")
        val channelIndex = source.indexOf("zlibInspectBookChannel(channel, size, expected)")
        assertTrue("必须先校验 fd 长度，再做随机读完整性检查", sizeGuardIndex >= 0 && sizeGuardIndex < channelIndex)
    }

    private fun book(file: File): Book = Book(
        id = 7L,
        title = "书架标题",
        author = "作者",
        format = BookFormat.Pdf,
        filePath = file.absolutePath,
        fileSize = file.length(),
        tags = listOf("zlib:42"),
    )

    private fun write(name: String, bytes: ByteArray): File =
        File(tempDir, name).apply { writeBytes(bytes) }

    /** 完整 PDF：%PDF 头 + 足够长度 + 末尾 %%EOF；截断版去掉 %%EOF。 */
    private fun pdfBytes(complete: Boolean): ByteArray {
        val body = "x".repeat(1_200)
        val tail = if (complete) "\n%%EOF\n" else "\ntruncated"
        return ("%PDF-1.4\n" + body + tail).toByteArray()
    }

    private fun sourceFile(relative: String): String {
        val candidates = ArrayList<File>()
        candidates += File(relative)
        candidates += File("app/" + relative)
        var dir: File? = File(System.getProperty("user.dir") ?: "").absoluteFile
        repeat(6) {
            val current = dir
            if (current != null) {
                candidates += File(current, "android/app/" + relative)
                candidates += File(current, "app/" + relative)
            }
            dir = current?.parentFile
        }
        val file = candidates.firstOrNull { it.isFile }
        assertTrue("source not found: " + relative + " cwd=" + System.getProperty("user.dir"), file != null)
        return file!!.readText()
    }
}
