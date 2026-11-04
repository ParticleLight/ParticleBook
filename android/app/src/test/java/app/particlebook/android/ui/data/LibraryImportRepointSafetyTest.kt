package app.particlebook.android.ui.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * task-102：repoint 旧副本清理的三态安全边界，以及 SAF 复制取消后的临时/最终文件清理。
 *
 * P1-1 的真实现象：旧书架条目文件可能只是调用方 validator 不认（例如下载长度字段缺失），
 * 但结构上仍是唯一完整的书。此时直接删旧、再把条目指向一个读失败/无法判定的新文件，会造成数据丢失。
 * P1-2：阻塞的复制不响应取消时，取消点落在 rename 之后会在 _pb_files 留下未登记最终文件，冷启扫描复活。
 */
class LibraryImportRepointSafetyTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("pb-repoint-safety-").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    // ---------------------------------------------------------------- 三态判定

    @Test
    fun classifyImportedFileSeparatesCorruptFromUnknown() {
        val dir = newDir("classify")
        val epub = validEpubFile(File(dir, "good.epub"))
        val pdf = pdfFile(File(dir, "good.pdf"))
        val truncatedEpub = File(dir, "truncated.epub").apply {
            writeBytes(validEpubBytes().copyOf(24))
        }
        val pdfWithoutEof = File(dir, "no-eof.pdf").apply { writeText("%PDF-1.7 only header") }
        val zero = File(dir, "zero.epub").apply { writeBytes(ByteArray(0)) }
        val html = File(dir, "error.epub").apply { writeText("<html>not a book</html>") }

        assertEquals("完整 EPUB 应判 COMPLETE", ImportedFileIntegrity.COMPLETE, classifyImportedFile(epub))
        assertEquals("完整 PDF 应判 COMPLETE", ImportedFileIntegrity.COMPLETE, classifyImportedFile(pdf))
        assertEquals(
            "有 ZIP 头但缺中央目录的截断包应判 CORRUPT",
            ImportedFileIntegrity.CORRUPT,
            classifyImportedFile(truncatedEpub),
        )
        assertEquals(
            "缺 %%EOF 的 PDF 应判 CORRUPT",
            ImportedFileIntegrity.CORRUPT,
            classifyImportedFile(pdfWithoutEof),
        )
        assertEquals("0 字节应判 CORRUPT", ImportedFileIntegrity.CORRUPT, classifyImportedFile(zero))
        assertEquals(
            "HTML 错误页/格式无法识别应判 UNKNOWN，不能当损坏删掉",
            ImportedFileIntegrity.UNKNOWN,
            classifyImportedFile(html),
        )
        assertEquals(
            "文件不存在/读失败应判 UNKNOWN",
            ImportedFileIntegrity.UNKNOWN,
            classifyImportedFile(File(dir, "missing.epub")),
        )
        assertTrue(isUsableImportedFile(epub))
        assertFalse(isUsableImportedFile(html))
    }

    // ---------------------------------------------------------------- repoint 不丢旧文件

    @Test
    fun repointKeepsCorruptOldFileWhenNewFileCannotBeVerified() = runBlocking {
        val dir = newDir("old-corrupt-new-unknown")
        val library = File(dir, "_pb_files").apply { mkdirs() }
        val oldFile = File(library, "old.epub").apply { writeBytes(validEpubBytes().copyOf(24)) }
        val newFile = File(library, "new.epub").apply { writeText("<html>download error page</html>") }
        val zlibTag = zlibBookTag("task-102-old-corrupt")
        val store = newStore(dir)

        val first = store.registerFile(oldFile, "旧", null, listOf(zlibTag)).getOrThrow()
        val second = store.registerFile(
            file = newFile,
            title = "新",
            author = null,
            tags = listOf(zlibTag),
            existingFileValidator = { false },
        ).getOrThrow()

        assertEquals(first.id, second.id)
        assertEquals(newFile.absolutePath, second.filePath)
        assertTrue("新文件无法验证为完整时，绝不能删旧副本", oldFile.isFile)
        assertTrue(newFile.isFile)
        assertEquals(1, store.books.size)
    }

    @Test
    fun repointKeepsUnknownIntegrityOldFile() = runBlocking {
        val dir = newDir("old-unknown")
        val library = File(dir, "_pb_files").apply { mkdirs() }
        val oldFile = File(library, "old.azw3").apply { writeText("unrecognized but possibly valid book") }
        val newFile = validEpubFile(File(library, "new.epub"))
        val zlibTag = zlibBookTag("task-102-old-unknown")
        val store = newStore(dir)

        val first = store.registerFile(oldFile, "旧", null, listOf(zlibTag)).getOrThrow()
        val second = store.registerFile(
            file = newFile,
            title = "新",
            author = null,
            tags = listOf(zlibTag),
            existingFileValidator = { false },
        ).getOrThrow()

        assertEquals(first.id, second.id)
        assertEquals(newFile.absolutePath, second.filePath)
        assertTrue("旧文件无法判定完整性时只能保留，不能删", oldFile.isFile)
        assertTrue(newFile.isFile)
    }

    @Test
    fun repointQuarantinesCompleteOldFileInsteadOfDeletingIt() = runBlocking {
        val dir = newDir("old-complete")
        val library = File(dir, "_pb_files").apply { mkdirs() }
        val oldFile = validEpubFile(File(library, "old.epub"))
        val newFile = validEpubFile(File(library, "new.epub"))
        val zlibTag = zlibBookTag("task-102-old-complete")
        val oldUri = safUriTag("content://doc/old")
        val oldFp = contentFingerprintTag(oldFile)!!
        val newUri = safUriTag("content://doc/new")
        val store = newStore(dir)

        val first = store.registerFile(oldFile, "旧", null, listOf(zlibTag, oldUri, oldFp)).getOrThrow()
        val second = store.registerFile(
            file = newFile,
            title = "新",
            author = null,
            tags = listOf(zlibTag, newUri),
            existingFileValidator = { false },
        ).getOrThrow()

        assertEquals(first.id, second.id)
        assertEquals(newFile.absolutePath, second.filePath)
        assertFalse("完整旧副本不能从 _pb_files 直接删除", oldFile.exists())
        assertTrue(
            "完整旧副本应改名 .deleted 隔离，既保留数据又不会被孤儿扫描复活",
            File(library, "old.epub.deleted").isFile,
        )
        val newFp = contentFingerprintTag(newFile)!!
        assertEquals(
            "repoint 后要清掉旧 SAF URI/指纹，补上新文件指纹，zlib 身份保留",
            setOf(zlibTag, newUri, newFp),
            second.tags.toSet(),
        )
        assertEquals(1, store.books.size)
    }

    // ---------------------------------------------------------------- 排序

    @Test
    fun newestBookParsesInstantInsteadOfComparingStrings() {
        val withOffset = book(1L, addedAt = "2026-01-01T00:00:00+08:00") // 2025-12-31T16:00Z
        val laterUtc = book(2L, addedAt = "2025-12-31T20:00:00Z")
        val empty = book(3L, addedAt = null)
        val blank = book(4L, addedAt = "")
        val invalid = book(5L, addedAt = "not-a-timestamp")

        assertEquals(
            "最新条目按真实时间比较，不能按 ISO 字符串字典序",
            2L,
            newestBook(listOf(withOffset, laterUtc, empty, blank, invalid))?.id,
        )
        assertEquals(
            "空/非法 addedAt 视为最旧，合法时间戳优先",
            1L,
            newestBook(listOf(empty, blank, invalid, withOffset))?.id,
        )
        assertEquals("都无有效时间时 id 大的兜底", 4L, newestBook(listOf(empty, blank))?.id)
        assertNotNull(addedAtEpochMillis(laterUtc.addedAt))
        assertEquals(null, addedAtEpochMillis(null))
        assertEquals(null, addedAtEpochMillis(""))
        assertEquals(null, addedAtEpochMillis("not-a-timestamp"))
    }

    // ---------------------------------------------------------------- 复制取消清理

    @Test
    fun copySourceToFileWritesAtomicallyAndCleansTempOnFailure() = runBlocking {
        val dir = newDir("copy-success")
        val temp = File(dir, "part.tmp")
        val destination = File(dir, "final.epub")
        val payload = ByteArray(200_000) { index -> (index % 251).toByte() }

        copySourceToFile(ByteArrayInputStream(payload), temp, destination)

        assertTrue("成功后最终文件必须存在", destination.isFile)
        assertArrayEquals(payload, destination.readBytes())
        assertFalse("成功后临时文件必须消失", temp.exists())

        val failureTemp = File(dir, "failure.tmp")
        val failureDestination = File(dir, "failure.epub")
        val failing = object : InputStream() {
            override fun read(): Int = throw IOException("boom")
        }
        try {
            copySourceToFile(failing, failureTemp, failureDestination)
            fail("读失败应抛出异常")
        } catch (expected: IOException) {
            assertEquals("boom", expected.message)
        }
        assertFalse("读失败要清掉临时文件", failureTemp.exists())
        assertFalse("读失败不能留下最终文件", failureDestination.exists())
    }

    @Test
    fun copySourceToFileCleansTempAndDestinationWhenCancelled() = runBlocking {
        val dir = newDir("copy-cancel")
        val temp = File(dir, "part.tmp")
        val destination = File(dir, "final.epub")
        val started = CompletableDeferred<Unit>()
        val slow = object : InputStream() {
            private var sent = false

            override fun read(): Int = 0

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (sent) return -1
                sent = true
                started.complete(Unit)
                // 模拟大文件复制时阻塞在 read：取消发生在 read 期间，下一轮 ensureActive 才能观察到。
                Thread.sleep(300)
                buffer[offset] = 1
                return 1
            }
        }

        val job = launch(Dispatchers.Default) {
            copySourceToFile(slow, temp, destination)
        }
        started.await()
        job.cancel()
        job.join()

        assertFalse("取消后不能留下 temp", temp.exists())
        assertFalse("取消后不能留下已 rename 的最终文件（否则冷启扫描会当孤儿复活）", destination.exists())
    }

    // ---------------------------------------------------------------- 源码契约

    @Test
    fun adoptDocumentAndRegisterFileWireCancellationAndThreeStateCleanup() {
        val source = sourceFile("src/main/java/app/particlebook/android/ui/data/LibraryStore.kt")
        val import = functionBody(source, "suspend fun importDocument(")
        assertTrue("手工 SAF 导入复制后取消也要回收副本", import.contains("discardCopiedFileOnCancellation(copiedRef)"))
        assertTrue("登记成功后必须置空，避免取消误删已入库文件", import.contains("copiedRef = null"))
        val adopt = functionBody(source, "suspend fun adoptDocument(")
        assertTrue("adopt 复制要改用可取消的分块复制", adopt.contains("copyUriToLibrary(uri, prefix = \"zlib\")"))
        assertTrue("取消时清理已复制的文件", adopt.contains("discardCopiedFileOnCancellation(copiedRef)"))
        assertTrue("登记成功但复制副本多余时也要清理", adopt.contains("discardCopiedFileOnCancellation(copied)"))
        assertTrue("adopt 登记必须在 NonCancellable 临界区", adopt.contains("withContext(NonCancellable + Dispatchers.IO)"))
        assertTrue("adopt 必须显式捕获 CancellationException 并继续抛", adopt.contains("catch (cancellation: CancellationException)"))

        assertTrue("复制函数要分块检查协程 active", source.contains("coroutineContext.ensureActive()"))
        assertTrue("复制函数取消时 NonCancellable 清理 temp/destination", source.contains("runCatching { destination.delete() }"))
        assertTrue(
            "copyUriToLibrary 必须调用可取消的 copySourceToFile",
            source.contains("copySourceToFile(source, temp, destination)"),
        )

        val register = functionBody(source, "suspend fun registerFile(")
        assertTrue("repoint 前做三态判定", register.contains("classifyImportedFile(existingFile)"))
        assertTrue("旧条目三态必须带入清理动作", register.contains("oldIntegrity ?: ImportedFileIntegrity.UNKNOWN"))
        assertTrue("清理必须在索引提交后执行", register.contains("repointCleanup?.let(::performRepointCleanup)"))
        assertFalse("不能在索引提交前直接删旧文件", register.contains("runCatching { old.delete() }"))
        assertTrue(
            "新文件非 COMPLETE 时跳过清理",
            source.contains("if (newIntegrity != ImportedFileIntegrity.COMPLETE)"),
        )
        assertTrue(
            "完整旧文件只隔离不删除",
            source.contains("quarantineRepointedCompleteFile(cleanup.oldFile)"),
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun newDir(name: String): File = File(root, name).apply { mkdirs() }

    private suspend fun newStore(dir: File): LibraryIndexStore =
        LibraryIndexStore(dir, TestIndexCodec()).also { store -> store.load { it } }

    private fun book(id: Long, addedAt: String?): Book = Book(
        id = id,
        title = "书" + id,
        author = null,
        format = BookFormat.Epub,
        filePath = "/lib/_pb_files/book-" + id + ".epub",
        fileSize = 0L,
        addedAt = addedAt,
    )

    private fun validEpubFile(file: File): File = file.apply { writeBytes(validEpubBytes()) }

    private fun validEpubBytes(): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/epub+zip".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write("<container/>".toByteArray())
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private fun pdfFile(file: File): File = file.apply {
        writeText("%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF\n")
    }

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assumeTrue("找不到函数 " + signature, start >= 0)
        val tail = source.substring(start + signature.length)
        val next = tail.indexOf("\n    suspend fun ")
            .takeIf { it >= 0 }
            ?: tail.indexOf("\n    private suspend fun ")
                .takeIf { it >= 0 }
                ?: tail.length
        return tail.substring(0, next)
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
