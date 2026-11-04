package app.particlebook.android.ui

import app.particlebook.android.ui.data.Book
import app.particlebook.android.ui.data.BookFormat
import app.particlebook.android.zlib.ZBookDetail
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * P2-4/P2-5：下载目标完整性判定与「书架已有」反查的 JVM 单测。
 *
 * 真机报告里的两个回归都发生在没有任何网络请求的恢复路径上：
 * - 进程被杀后记录是 INTERRUPTED，但目标文件其实已经完整；旧逻辑会再下一次并在 SAF 场景把
 *   唯一完整副本 openOutputStream 截断。这里锁定 PDF/EPUB 的完整与截断判定。
 * - 删除下载记录后再点下载，旧逻辑只看历史，不认识书架里的 zlib:<bookId> 标签，会导入第二份。
 * 这些函数不依赖 Android API，可以直接在 JVM 上跑。
 */
class ZlibDownloadTargetCheckTest {

    private val tempDir: File = Files.createTempDirectory("pb-zlib-target").toFile()

    @After
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    @Test
    fun completePdfIsComplete() {
        val file = write("complete.pdf", pdfBytes(complete = true))
        assertEquals(
            ZlibDownloadTargetState.COMPLETE,
            zlibInspectBookFile(file),
        )
    }

    @Test
    fun truncatedPdfIsIncomplete() {
        val file = write("truncated.pdf", pdfBytes(complete = false))
        assertEquals(
            ZlibDownloadTargetState.INCOMPLETE,
            zlibInspectBookFile(file),
        )
    }

    @Test
    fun completeEpubIsComplete() {
        val file = write("complete.epub", epubBytes())
        assertEquals(
            ZlibDownloadTargetState.COMPLETE,
            zlibInspectBookFile(file),
        )
    }

    @Test
    fun epubWithoutEocdIsIncomplete() {
        val bytes = epubBytes()
        val truncated = bytes.copyOf(bytes.size - 10)
        val file = write("truncated.epub", truncated)
        assertEquals(
            ZlibDownloadTargetState.INCOMPLETE,
            zlibInspectBookFile(file),
        )
    }

    @Test
    fun htmlAndMissingTargetAreNotComplete() {
        val html = write("page.bin", ("<html>" + "x".repeat(1200) + "</html>").toByteArray())
        assertEquals(ZlibDownloadTargetState.INCOMPLETE, zlibInspectBookFile(html))
        assertEquals(
            ZlibDownloadTargetState.MISSING,
            zlibInspectBookFile(File(tempDir, "missing.pdf")),
        )
    }

    @Test
    fun expectedBytesMustMatchExactly() {
        val bytes = pdfBytes(complete = true)
        val file = write("sized.pdf", bytes)
        assertEquals(
            ZlibDownloadTargetState.COMPLETE,
            zlibInspectBookFile(file, bytes.size.toLong()),
        )
        assertEquals(
            ZlibDownloadTargetState.INCOMPLETE,
            zlibInspectBookFile(file, bytes.size + 1L),
        )
    }

    @Test
    fun smallFileIsNeverComplete() {
        val file = write("tiny.pdf", "%PDF-1.4\n%%EOF\n".toByteArray())
        assertEquals(
            ZlibDownloadTargetState.INCOMPLETE,
            zlibInspectBookFile(file),
        )
    }

    @Test
    fun libraryBookIsFoundByDownloadTagOnly() {
        val tagged = book(id = 7L, file = write("library.pdf", pdfBytes(true)), tags = listOf("zlib:42"))
        val other = book(id = 8L, file = write("other.pdf", pdfBytes(true)), tags = listOf("zlib:43"))
        val books = listOf(other, tagged)
        assertSame(tagged, zlibBookByDownloadTag("42", books))
        assertNull(zlibBookByDownloadTag("44", books))
    }

    @Test
    fun libraryRecordMirrorsShelfBook() {
        val file = write("shelf.pdf", pdfBytes(true))
        val shelf = book(id = 7L, file = file, tags = listOf("zlib:42"))
        val record = zlibLibraryDownloadRecord(detail(), shelf, file.length(), now = 1_234L)
        assertEquals("zlib:42", record.id)
        assertEquals("42", record.bookId)
        assertEquals(ZlibDownloadStatus.COMPLETED, record.status)
        assertEquals(file.absolutePath, record.destPath)
        assertEquals(file.name, record.fileName)
        assertEquals(file.length(), record.received)
        assertEquals(file.length(), record.total)
        assertEquals("下载标题", record.title)
        assertEquals("作者", record.author)
        assertEquals("pdf", record.format)
        assertTrue(!record.saf)
        assertEquals(1_234L, record.finishedAt)
    }

    @Test
    fun legacyHistoryWithoutTotalFallsBackToReceived() {
        assertEquals(500L, zlibHistoryTotal(hasTotal = true, totalValue = 500L, received = 400L))
        assertEquals(400L, zlibHistoryTotal(hasTotal = false, totalValue = -1L, received = 400L))
    }

    @Test
    fun managerSourceKeepsRecoveryAndLibraryGuards() {
        val source = sourceFile("src/main/java/app/particlebook/android/ui/ZlibDownloadManager.kt")
        assertTrue("恢复时要先检查目标完整性", source.contains("zlibInspectBookFile"))
        assertTrue("File 完整时直接重新导入", source.contains("tryImportCompleteFileTarget"))
        assertTrue("SAF 完整时不能截断重写", source.contains("tryImportCompleteSafTarget"))
        assertTrue("中断记录要判定是否已完整", source.contains("interruptedComplete"))
        assertTrue("下载前先查书架标签", source.contains("zlibBookByDownloadTag"))
        assertTrue("冷启要等书架加载完再查", source.contains("store.ensureLoaded()"))
        assertTrue("命中书架要留日志", source.contains("zlib_download_already_in_library"))
        assertTrue("历史编码要写 total", source.contains(".put(\"total\", record.total)"))
        assertTrue("历史解码要兼容旧记录", source.contains("item.has(\"total\")"))
        assertTrue("导入态要落盘", source.contains("persistActive(importing)"))
    }

    private fun book(id: Long, file: File, tags: List<String>): Book = Book(
        id = id,
        title = "书架标题",
        author = "作者",
        format = BookFormat.Pdf,
        filePath = file.absolutePath,
        fileSize = file.length(),
        tags = tags,
    )

    private fun detail(): ZBookDetail = ZBookDetail(
        bookId = "42",
        hash = "hash42",
        title = "下载标题",
        authors = listOf(""),
        language = null,
        year = null,
        format = "pdf",
        fileSize = null,
        isbn = null,
        coverUrl = null,
        detailPath = "/book/42/hash42",
        downloadPath = "/dl/42",
    )

    private fun write(name: String, bytes: ByteArray): File =
        File(tempDir, name).apply { writeBytes(bytes) }

    /** 完整 PDF：%PDF 头 + 足够长度 + 末尾 %%EOF；截断版去掉 %%EOF。 */
    private fun pdfBytes(complete: Boolean): ByteArray {
        val body = "x".repeat(1_200)
        val tail = if (complete) "\n%%EOF\n" else "\ntruncated"
        return ("%PDF-1.4\n" + body + tail).toByteArray()
    }

    /** 最小的真实 ZIP 结构（含 EOCD 与中央目录），长度 > 1KB。 */
    private fun epubBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/epub+zip".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write("<container/>".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("payload.bin"))
            // 随机字节避免 deflate 把 1.4KB 压到 1KB 以下（那样会被 MIN_BOOK_BYTES 拦掉）。
            zip.write(ByteArray(1_400).also { Random(42L).nextBytes(it) })
            zip.closeEntry()
        }
        return out.toByteArray()
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
                candidates += File(current, relative)
                dir = current.parentFile
            }
        }
        val found = candidates.firstOrNull { it.isFile }
        assumeTrue(
            "找不到 " + relative + "（工作目录=" + System.getProperty("user.dir") + "），跳过源码契约检查",
            found != null,
        )
        return found!!.readText()
    }
}
