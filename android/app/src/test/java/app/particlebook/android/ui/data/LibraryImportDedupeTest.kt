package app.particlebook.android.ui.data

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * task-70：SAF 本地文件重复导入去重（P1 数据正确性）。
 *
 * 覆盖两条真实路径：
 * - 同一 SAF URI 再导入：靠 Book.tags 里持久化的 saf-uri:<sha256> 认领，不再复制；
 * - 不同 URI 但内容相同：靠 fp:<size>-<sha256(size+首尾 64KiB)> 认领，复制出来的副本会被删掉。
 * 并发同一 URI 两路同时导入由 LibraryIndexStore.registerFile 的 identity tag 幂等兜底。
 * 索引写入用 [LibraryIndexStore] + [TestIndexCodec] 真写盘验证（JVM 单测没有 android org.json）。
 */
class LibraryImportDedupeTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("pb-import-dedupe-").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    // ---------------------------------------------------------------- 身份 tag 纯函数

    @Test
    fun safUriTagIsStablePerUriAndDistinctAcrossUris() {
        val uri = "content://com.android.providers.downloads.documents/document/42"
        val first = safUriTag(uri)
        val again = safUriTag(uri)
        val other = safUriTag(
            "content://com.android.providers.downloads.documents/document/43",
        )

        assertEquals("同一 URI 必须稳定命中", first, again)
        assertNotEquals("不同 URI 不能互相认领", first, other)
        assertTrue("URI tag 前缀", first.startsWith(SAF_URI_TAG_PREFIX))
        assertEquals("tag = 前缀 + SHA-256 hex", SAF_URI_TAG_PREFIX.length + 64, first.length)
    }

    @Test
    fun contentFingerprintMatchesIdenticalBytesAndDetectsEdits() {
        val dir = newTempDir("fingerprint")
        val bytes = ByteArray(200_000) { index -> ((index * 31 + 7) and 0xFF).toByte() }
        val original = bookFile(dir, "original.pdf", bytes)
        val copy = bookFile(dir, "copy.pdf", bytes.copyOf())
        val changedHead = bookFile(dir, "head.pdf", bytes.copyOf().also { it[0] = (it[0] + 1).toByte() })
        val changedTail = bookFile(
            dir,
            "tail.pdf",
            bytes.copyOf().also { it[it.lastIndex] = (it[it.lastIndex] + 1).toByte() },
        )
        val changedMiddle = bookFile(
            dir,
            "middle.pdf",
            bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() },
        )
        val shorter = bookFile(dir, "short.pdf", bytes.copyOf(199_999))

        val tag = contentFingerprintTag(original)
        assertEquals("同内容不同文件名必须同指纹", tag, contentFingerprintTag(copy))
        assertNotEquals("头部改动要换指纹", tag, contentFingerprintTag(changedHead))
        assertNotEquals("尾部改动要换指纹", tag, contentFingerprintTag(changedTail))
        assertNotEquals("中间改动也要换指纹（<=8MiB 全量哈希）", tag, contentFingerprintTag(changedMiddle))
        assertNotEquals("大小变化要换指纹", tag, contentFingerprintTag(shorter))
        assertTrue("指纹带大小前缀", tag!!.startsWith(CONTENT_FINGERPRINT_TAG_PREFIX + 200_000 + "-"))
        assertNull("不存在的文件没有指纹", contentFingerprintTag(File(dir, "missing.pdf")))
    }

    // ---------------------------------------------------------------- 重复判定

    @Test
    fun findDuplicateImportPrefersUriHitOverContentHit() {
        val uriTag = safUriTag("content://doc/same")
        val fingerprintTag = "fp:100-abc"
        val byContent = book("/lib/b.epub", id = 2L, tags = listOf(fingerprintTag))
        val byUri = book("/lib/a.epub", id = 1L, tags = listOf(uriTag))

        val hit = findDuplicateImport(listOf(byContent, byUri), uriTag, fingerprintTag) { true }

        assertEquals("同一 URI 优先，避免多读文件", DuplicateImportKind.SAF_URI, hit?.kind)
        assertEquals(1L, hit?.book?.id)
    }

    @Test
    fun findDuplicateImportFallsBackToContentFingerprint() {
        val uriTag = safUriTag("content://doc/new-uri")
        val fingerprintTag = "fp:100-abc"
        val byContent = book("/lib/b.epub", id = 2L, tags = listOf(fingerprintTag))

        val hit = findDuplicateImport(listOf(byContent), uriTag, fingerprintTag) { true }

        assertEquals(DuplicateImportKind.CONTENT, hit?.kind)
        assertEquals(2L, hit?.book?.id)
    }

    @Test
    fun findDuplicateImportIgnoresEntriesWhoseFileIsGone() {
        val uriTag = safUriTag("content://doc/deleted")
        val fingerprintTag = "fp:100-abc"
        val gone = book("/lib/gone.epub", id = 1L, tags = listOf(uriTag))
        val present = book("/lib/present.epub", id = 2L, tags = listOf(fingerprintTag))

        val onlyGone = findDuplicateImport(listOf(gone), uriTag, null) { book -> book.id != 1L }
        assertNull("条目文件已被删时必须允许重新导入", onlyGone)

        val fallback = findDuplicateImport(listOf(gone, present), uriTag, fingerprintTag) { book ->
            book.id != 1L
        }
        assertEquals("缺失的 URI 条目不能挡住内容命中", DuplicateImportKind.CONTENT, fallback?.kind)
        assertEquals(2L, fallback?.book?.id)
    }

    @Test
    fun findIdempotentBookMatchesUriAndFingerprintTags() {
        val uriTag = safUriTag("content://doc/1")
        val fingerprintTag = "fp:100-abc"
        val uriBook = book("/lib/uri.epub", id = 1L, tags = listOf(uriTag))
        val contentBook = book("/lib/content.epub", id = 2L, tags = listOf(fingerprintTag))
        val zlibBook = book("/lib/zlib.epub", id = 3L, tags = listOf(zlibBookTag("z1")))
        val books = listOf(uriBook, contentBook, zlibBook)

        assertEquals(1L, findIdempotentBook(books, "/lib/new.epub", listOf(uriTag))?.id)
        assertEquals(2L, findIdempotentBook(books, "/lib/new.epub", listOf(fingerprintTag))?.id)
        assertEquals(
            "原有 zlib 判重不能回归",
            3L,
            findIdempotentBook(books, "/lib/new.epub", listOf(zlibBookTag("z1")))?.id,
        )
        assertNull(findIdempotentBook(books, "/lib/new.epub", listOf("zlib-download")))
    }

    // ---------------------------------------------------------------- 真实索引写盘

    @Test
    fun registerFileMergesNewImportTagsOnDuplicateHit() = runBlocking {
        val dir = newTempDir("merge-tags")
        val store = LibraryIndexStore(dir, TestIndexCodec())
        store.load { it }
        val file = bookFile(dir, "same.epub")
        val uriTag1 = safUriTag("content://doc/1")
        val uriTag2 = safUriTag("content://doc/2")
        val fingerprintTag = contentFingerprintTag(file)!!

        val first = store.registerFile(file, "same", null, listOf(uriTag1, fingerprintTag)).getOrThrow()
        val second = store.registerFile(file, "same", null, listOf(uriTag2, fingerprintTag)).getOrThrow()

        assertEquals("同一文件重复登记不能出第二条", first.id, second.id)
        assertEquals("新 URI tag 要合并进条目", setOf(uriTag1, uriTag2, fingerprintTag), second.tags.toSet())
        assertEquals(1, store.books.size)
        val persisted = decodeIndex(dir)
        assertEquals(1, persisted.size)
        assertEquals(setOf(uriTag1, uriTag2, fingerprintTag), persisted.single().tags.toSet())
    }

    @Test
    fun registerFileRepointsUriTaggedEntryWhenOldFileIsDeleted() = runBlocking {
        val dir = newTempDir("repoint-uri")
        val store = LibraryIndexStore(dir, TestIndexCodec())
        store.load { it }
        val oldFile = bookFile(dir, "old.epub")
        val uriTag = safUriTag("content://doc/reimport")
        val fingerprintTag = contentFingerprintTag(oldFile)!!
        val first = store.registerFile(oldFile, "old", null, listOf(uriTag, fingerprintTag)).getOrThrow()
        assertTrue(oldFile.delete())

        val newFile = bookFile(dir, "new.epub")
        val second = store.registerFile(newFile, "new", null, listOf(uriTag, fingerprintTag)).getOrThrow()

        assertEquals("同 URI 再次导入应复用条目 id", first.id, second.id)
        assertEquals("书架路径要改到新文件", newFile.absolutePath, second.filePath)
        assertFalse("文件回来后不能留 missing", second.tags.contains("missing"))
        assertEquals(1, store.books.size)
        assertEquals(newFile.absolutePath, decodeIndex(dir).single().filePath)
    }

    @Test
    fun concurrentRegistersWithSameUriTagKeepSingleEntry() = runBlocking {
        val dir = newTempDir("concurrent-same-uri")
        val store = LibraryIndexStore(dir, TestIndexCodec())
        store.load { it }
        val uriTag = safUriTag("content://doc/concurrent")
        val first = bookFile(dir, "copy-a.pdf")
        val second = bookFile(dir, "copy-b.pdf")
        val barrier = CyclicBarrier(2)

        val results = listOf(first, second).map { file ->
            async(Dispatchers.IO) {
                barrier.await(30, TimeUnit.SECONDS)
                store.registerFile(file, "same", null, listOf(uriTag, contentFingerprintTag(file)!!))
            }
        }.awaitAll()

        assertTrue("并发两路都要成功（幂等返回）: " + results, results.all { it.isSuccess })
        assertEquals("同一 URI 并发导入只能留下一条", 1, store.books.size)
        assertEquals(1, decodeIndex(dir).size)
        assertEquals("两路返回的应是同一条目", 1, results.map { it.getOrThrow().id }.toSet().size)
    }

    // ---------------------------------------------------------------- 源码契约（UI 接线）

    @Test
    fun importDocumentDedupesByPersistedUriThenFingerprint() {
        val source = sourceFile("src/main/java/app/particlebook/android/ui/data/LibraryStore.kt")

        assertTrue("先算 SAF URI tag", source.contains("val uriTag = safUriTag(uri.toString())"))
        assertTrue("复制前先按 URI findById", source.contains("indexStore.findByTag(uriTag)"))
        assertTrue("复制后算内容指纹", source.contains("val fingerprintTag = contentFingerprintTag(copied.file)"))
        assertTrue(
            "URI 优先、内容兜底的复用判定",
            source.contains("findDuplicateImport(indexStore.books, uriTag, fingerprintTag)"),
        )
        assertTrue(
            "命中已有条目要删掉复制出来的副本",
            source.contains("runCatching { copied.file.delete() }"),
        )
        assertTrue("命中后补记新的 URI/指纹 tag", source.contains("mergeImportTags(duplicate.book"))
        assertTrue("新导入同时写 URI 与指纹 tag", source.contains("add(uriTag)") && source.contains("fingerprintTag?.let(::add)"))
        assertTrue("返回 alreadyInLibrary 供 UI 区分", source.contains("ImportOutcome(duplicate.book, alreadyInLibrary = true)"))
        assertTrue(
            "并发兜底：register 返回已有路径时删副本",
            source.contains("val deduped = pathKey(book.filePath) != pathKey(copied.file.absolutePath)"),
        )
    }

    @Test
    fun registerFileMergesTagsAndUiShowsAlreadyInLibrary() {
        val storeSource = sourceFile("src/main/java/app/particlebook/android/ui/data/LibraryStore.kt")
        assertTrue(
            "幂等命中要合并新 tag（不同 URI 同内容第二次导入）",
            storeSource.contains("tags.filterNot { it in repointed.tags }"),
        )
        assertTrue("identity tag 统一判定", storeSource.contains("isImportIdentityTag"))
        assertTrue("URI 命中优先于内容", storeSource.contains("DuplicateImportKind.SAF_URI"))
        assertTrue("内容命中作为兜底", storeSource.contains("DuplicateImportKind.CONTENT"))

        val appRoot = sourceFile("src/main/java/app/particlebook/android/ui/AppRoot.kt")
        assertTrue("UI 要按 alreadyInLibrary 分文案", appRoot.contains("outcome.alreadyInLibrary"))
        assertTrue("重复导入提示「已在书架」", appRoot.contains("\"已在书架：《\""))
        assertTrue("重复导入直接打开已有条目", appRoot.contains("openBookId = book.id"))
    }

    // ---------------------------------------------------------------- helpers

    private fun newTempDir(name: String): File = File(root, name).apply { mkdirs() }

    private fun bookFile(dir: File, name: String, bytes: ByteArray = "epub test content".toByteArray()): File =
        File(dir, name).apply { writeBytes(bytes) }

    private fun book(path: String, id: Long, tags: List<String> = emptyList()): Book = Book(
        id = id,
        title = File(path).nameWithoutExtension,
        author = null,
        format = if (path.endsWith(".pdf")) BookFormat.Pdf else BookFormat.Epub,
        filePath = path,
        fileSize = 0L,
        tags = tags,
    )

    private fun decodeIndex(dir: File): List<Book> =
        TestIndexCodec().decode(File(dir, "books.json").readText())

    /** 与 LibraryDeletionCascadeTest 相同的源码定位：从测试工作目录向上找 android/app 根。 */
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
