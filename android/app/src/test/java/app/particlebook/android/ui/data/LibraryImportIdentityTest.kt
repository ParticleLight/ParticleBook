package app.particlebook.android.ui.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * task-83：导入去重的身份识别必须建立在「文件确实完整」之上。
 *
 * 覆盖真实现象：旧书架条目指向 0 字节/截断 EPUB/PDF，新文件导入时只看 isFile 会把坏文件当幂等命中，
 * 新文件成孤儿；反过来 repoint 后旧坏文件若留在 _pb_files，冷启扫描会把它重新登记成第二条。
 * 这里同时验证纯函数（可 JVM 跑）与 LibraryIndexStore 的真实索引写盘。
 */
class LibraryImportIdentityTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("pb-import-identity-").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    // ---------------------------------------------------------------- 文件结构可用性

    @Test
    fun isUsableImportedFileAcceptsCompletePdfEpubAndRejectsBrokenCopies() {
        val dir = newDir("usable")
        val epub = validEpubFile(File(dir, "good.epub"))
        val pdf = pdfFile(File(dir, "good.pdf"))
        val truncatedEpub = File(dir, "truncated.epub").apply {
            writeBytes(validEpubBytes().copyOf(24))
        }
        val truncatedPdf = File(dir, "truncated.pdf").apply {
            writeText("%PDF-1.7 only header")
        }
        val zero = File(dir, "zero.epub").apply { writeBytes(ByteArray(0)) }
        val html = File(dir, "error.epub").apply { writeText("<html>not a book</html>") }

        assertTrue("完整 EPUB（含中央目录）应可用", isUsableImportedFile(epub))
        assertTrue("完整 PDF（%PDF + %%EOF）应可用", isUsableImportedFile(pdf))
        assertFalse("截断 EPUB 不能当可用", isUsableImportedFile(truncatedEpub))
        assertFalse("没有 %%EOF 的 PDF 不能当可用", isUsableImportedFile(truncatedPdf))
        assertFalse("0 字节不能当可用", isUsableImportedFile(zero))
        assertFalse("HTML 错误页不能当可用", isUsableImportedFile(html))
        assertFalse("不存在的文件不能当可用", isUsableImportedFile(File(dir, "missing.epub")))
    }

    @Test
    fun isRecoverableOrphanLibraryFileRejectsBrokenOrWrongExtension() {
        val dir = newDir("orphan-filter")
        val good = validEpubFile(File(dir, "import-1-abcd.epub"))
        val truncated = File(dir, "import-2-abcd.epub").apply {
            writeBytes(validEpubBytes().copyOf(24))
        }
        val wrongExtension = File(dir, "import-3-abcd.txt").apply {
            writeBytes(validEpubBytes())
        }
        val zeroPdf = File(dir, "zlib-1-abcd.pdf").apply { writeBytes(ByteArray(0)) }

        assertTrue("完整 EPUB 可被冷启扫描恢复", isRecoverableOrphanLibraryFile(good))
        assertFalse("截断 EPUB 不能被冷启扫描复活", isRecoverableOrphanLibraryFile(truncated))
        assertFalse("非 epub/pdf 扩展名不能被恢复", isRecoverableOrphanLibraryFile(wrongExtension))
        assertFalse("0 字节 PDF 不能被冷启扫描复活", isRecoverableOrphanLibraryFile(zeroPdf))
    }

    // ---------------------------------------------------------------- 多命中选最新可用

    @Test
    fun findNewestUsableBookByTagPrefersUsableEntriesAndFallsBackToNewest() {
        val books = listOf(
            book(id = 1L, addedAt = "2026-01-01T00:00:00.000Z", tags = listOf("same")),
            book(id = 2L, addedAt = "2026-02-01T00:00:00.000Z", tags = listOf("same")),
            book(id = 3L, addedAt = "2026-03-01T00:00:00.000Z", tags = listOf("same")),
        )

        assertEquals(
            "有可用条目时选最新的可用条目，不能被后面更新的坏条目挡住",
            2L,
            findNewestUsableBookByTag(books, "same") { it.id == 1L || it.id == 2L }?.id,
        )
        assertEquals(
            "全都不可用时返回最新条目，交给 registerFile repoint",
            3L,
            findNewestUsableBookByTag(books, "same") { false }?.id,
        )
        assertNull(findNewestUsableBookByTag(books, "absent") { true })
        assertNull("空 tag 不命中任何条目", findNewestUsableBookByTag(books, " ") { true })
    }

    @Test
    fun findDuplicateImportIgnoresBrokenUriHitAndUsesUsableContentHit() {
        val dir = newDir("duplicate")
        val broken = File(dir, "broken.epub").apply { writeBytes(validEpubBytes().copyOf(24)) }
        val good = validEpubFile(File(dir, "good.epub"))
        val uriTag = safUriTag("content://doc/broken")
        val fingerprintTag = "fp:100-abc"
        val byBrokenUri = book(id = 1L, path = broken.absolutePath, tags = listOf(uriTag))
        val byGoodContent = book(id = 2L, path = good.absolutePath, tags = listOf(fingerprintTag))

        val hit = findDuplicateImport(listOf(byBrokenUri, byGoodContent), uriTag, fingerprintTag)

        assertNotNull("坏文件不能挡住可用的内容命中", hit)
        assertEquals(DuplicateImportKind.CONTENT, hit?.kind)
        assertEquals(2L, hit?.book?.id)
    }

    // ---------------------------------------------------------------- 真实索引写盘

    @Test
    fun registerFileRepointsBrokenIdentityEntryCleansTagsAndDeletesOldFile() = runBlocking {
        val dir = newDir("repoint")
        val library = File(dir, "_pb_files").apply { mkdirs() }
        val oldFile = File(library, "old.epub").apply { writeBytes(validEpubBytes().copyOf(24)) }
        val newFile = validEpubFile(File(library, "new.epub"))
        val zlibTag = zlibBookTag("book-83")
        val oldUri = safUriTag("content://doc/old")
        val oldFp = contentFingerprintTag(oldFile)!!
        val store = LibraryIndexStore(dir, TestIndexCodec())
        store.load { it }

        val first = store.registerFile(
            file = oldFile,
            title = "旧条目",
            author = null,
            tags = listOf(zlibTag, oldUri, oldFp),
        ).getOrThrow()
        val newUri = safUriTag("content://doc/new")
        val newFp = contentFingerprintTag(newFile)!!
        val second = store.registerFile(
            file = newFile,
            title = "新文件",
            author = null,
            tags = listOf(zlibTag, newUri, newFp),
        ).getOrThrow()

        assertEquals("identity tag 命中应复用 Book.id（进度/封面不丢）", first.id, second.id)
        assertEquals("路径要修到新文件", newFile.absolutePath, second.filePath)
        assertFalse("旧坏文件应从 _pb_files 清理", oldFile.exists())
        assertTrue("新文件必须保留", newFile.exists())
        assertEquals(
            "旧 SAF URI/指纹 tag 已不描述新文件，必须清掉；zlib 书目身份保留",
            setOf(zlibTag, newUri, newFp),
            second.tags.toSet(),
        )
        assertEquals(1, store.books.size)
        assertEquals(1, decodeIndex(dir).size)
        assertEquals(newFile.absolutePath, decodeIndex(dir).single().filePath)
    }

    @Test
    fun registerFileCleansOldCopyWhenValidatorRejectsEvenIfHeaderLooksComplete() = runBlocking {
        val dir = newDir("validator-reject")
        val library = File(dir, "_pb_files").apply { mkdirs() }
        val oldFile = validEpubFile(File(library, "old.epub"))
        val newFile = validEpubFile(File(library, "new.epub"))
        val zlibTag = zlibBookTag("book-keep")
        val store = LibraryIndexStore(dir, TestIndexCodec())
        store.load { it }

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
        assertFalse(
            "validator 判定不可用的旧副本要清理，否则冷启扫描可能把它登记成第二条",
            oldFile.exists(),
        )
        assertTrue(newFile.exists())
    }

    @Test
    fun findByTagPicksNewestUsableEntryAndFallsBackToNewestBroken() = runBlocking {
        val dir = newDir("find-by-tag")
        val goodOld = validEpubFile(File(dir, "good-old.epub"))
        val goodNew = validEpubFile(File(dir, "good-new.epub"))
        val broken = File(dir, "broken.epub").apply { writeBytes(validEpubBytes().copyOf(24)) }
        val tag = zlibBookTag("book-tag")

        fun storeFor(books: List<Book>): LibraryIndexStore {
            File(dir, "books.json").writeText(TestIndexCodec().encode(books))
            // findByTag 自己会 ensureLoaded（写前 load 保护），这里不需要额外 suspend load。
            return LibraryIndexStore(dir, TestIndexCodec())
        }

        val newestGood = storeFor(
            listOf(
                book(id = 11L, path = goodOld.absolutePath, addedAt = "2026-01-01T00:00:00.000Z", tags = listOf(tag)),
                book(id = 12L, path = goodNew.absolutePath, addedAt = "2026-02-01T00:00:00.000Z", tags = listOf(tag)),
                book(id = 13L, path = broken.absolutePath, addedAt = "2026-03-01T00:00:00.000Z", tags = listOf(tag)),
            ),
        )
        assertEquals("多命中选最新可用，跳过更新的坏文件", 12L, newestGood.findByTag(tag)?.id)

        val brokenOld = File(dir, "broken-old.epub").apply { writeBytes(validEpubBytes().copyOf(24)) }
        val fallback = storeFor(
            listOf(
                book(id = 21L, path = brokenOld.absolutePath, addedAt = "2026-01-01T00:00:00.000Z", tags = listOf(tag)),
                book(id = 22L, path = broken.absolutePath, addedAt = "2026-02-01T00:00:00.000Z", tags = listOf(tag)),
            ),
        )
        assertEquals("全坏时返回最新条目供调用方 repoint", 22L, fallback.findByTag(tag)?.id)
    }

    // ---------------------------------------------------------------- 源码契约（JVM 无 Context 的部分）

    @Test
    fun adoptDocumentChecksUsableFileInsideIoAndOrphanScanIsStrict() {
        val source = sourceFile("src/main/java/app/particlebook/android/ui/data/LibraryStore.kt")
        assertTrue(
            "adoptDocument 的 tag 查询与文件校验要整体放 IO（Manager main scope 调用）",
            source.contains("val earlyExisting = withContext(Dispatchers.IO)"),
        )
        assertTrue("早退前必须过结构完整性", source.contains("isUsableImportedFile(existingFile)"))
        assertTrue(
            "孤儿扫描改用严格版本，坏文件不能被冷启复活",
            source.contains("isRecoverableOrphanLibraryFile(file)"),
        )
        assertTrue("repoint 后清理旧坏文件", source.contains("oldRepointedBrokenFile(existingFile"))
        assertTrue("mergeImportTags 不能给坏文件补 tag", source.contains("if (!isUsableImportedFile(file)) return"))
    }

    // ---------------------------------------------------------------- helpers

    private fun newDir(name: String): File = File(root, name).apply { mkdirs() }

    private fun book(
        id: Long,
        path: String = "/lib/_pb_files/book-$id.epub",
        addedAt: String? = null,
        tags: List<String> = emptyList(),
    ): Book = Book(
        id = id,
        title = File(path).nameWithoutExtension.ifBlank { "书$id" },
        author = null,
        format = BookFormat.Epub,
        filePath = path,
        fileSize = 0L,
        addedAt = addedAt,
        tags = tags,
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

    private fun decodeIndex(dir: File): List<Book> =
        TestIndexCodec().decode(File(dir, "books.json").readText())

    /** 与其它数据层测试相同：从测试工作目录向上找 android/app 根。 */
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
