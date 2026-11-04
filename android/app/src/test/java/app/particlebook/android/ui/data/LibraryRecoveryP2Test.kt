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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * task-45 P2-1 / P2-3：书架数据层收口。
 *
 * - 索引里文件暂时不存在的条目不能丢（打 missing 标记，文件回来再清）；
 * - 下载记录导入按 zlib:<bookId> 幂等（SAF 复制路径不同也认得出），并发下也只能有一条；
 * - 损坏/丢失索引优先从 bak1/bak2 恢复，只有真的恢复不出条目才允许扫下载目录；
 * - 下载历史线索能回填书名/作者/tag/时间。
 *
 * JVM 单测没有 org.json，因此索引编解码注入 [TestIndexCodec]（与生产同格式），
 * 断言直接看磁盘上的 books.json。
 */
class LibraryRecoveryP2Test {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("pb-library-recovery-").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    // ---------------------------------------------------------------- mergePersistedBooks

    @Test
    fun missingFileBookIsKeptWithTagAndHealedWhenFileReturns() {
        val dir = newTempDir("merge-missing")
        val missing = File(dir, "gone.epub")
        val stored = book(missing.absolutePath, tags = listOf("zlib-download"))

        val first = mergePersistedBooks(emptyList(), listOf(stored), dir)
        assertEquals("文件不在也不能丢条目（否则回写索引就永久丢书）", 1, first.size)
        assertTrue("文件不在时应打 missing 标记", "missing" in first.single().tags)

        missing.writeBytes(epubHeader())
        val second = mergePersistedBooks(emptyList(), first, dir)
        assertEquals(1, second.size)
        assertFalse("文件回来后应清掉 missing 标记", "missing" in second.single().tags)
        assertTrue("原有 tag 不能弄丢", "zlib-download" in second.single().tags)
    }

    @Test
    fun fixtureWinsAndDuplicatePersistedPathKeepsOneEntry() {
        val dir = newTempDir("merge-dup")
        val fixturePath = File(dir, "fixture.epub").absolutePath
        val fixture = book(fixturePath, id = 7L, title = "fixture")
        val duplicate = book(fixturePath, id = 8L, title = "persisted-dup")

        val merged = mergePersistedBooks(listOf(fixture), listOf(duplicate), dir)
        assertEquals("同路径的持久化条目不能覆盖 fixtures 已有条目", 1, merged.size)
        assertEquals("fixture", merged.single().title)
        assertEquals(7L, merged.single().id)
    }

    // ---------------------------------------------------------------- registerFile 幂等

    @Test
    fun registerFileIsIdempotentByZlibTagAcrossDifferentPaths() = runBlocking {
        val dir = newTempDir("zlib-idempotent")
        val store = LibraryIndexStore(dir, TestIndexCodec())
        store.load { it }

        val original = sniffableBookFile(dir, "original.epub")
        val copied = sniffableBookFile(dir, "copied-by-saf.epub")
        val first = store.registerFile(
            file = original,
            title = "同一本书",
            author = null,
            tags = listOf(zlibBookTag("book-1")),
        ).getOrThrow()
        val second = store.registerFile(
            file = copied,
            title = "同一本书",
            author = null,
            tags = listOf(zlibBookTag("book-1")),
        ).getOrThrow()

        assertEquals("同 zlib tag 重复登记应返回同一条", first.id, second.id)
        assertEquals("不能复制出第二条书架记录", 1, store.books.size)
        assertEquals(original.absolutePath, second.filePath)
        assertEquals(1, decodeIndex(dir).size)
    }

    @Test
    fun concurrentRegistrationsWithSameZlibTagKeepSingleBook() = runBlocking {
        val dir = newTempDir("zlib-concurrent")
        val store = LibraryIndexStore(dir, TestIndexCodec())
        store.load { it }
        val files = listOf(
            sniffableBookFile(dir, "a.epub"),
            sniffableBookFile(dir, "b.epub"),
        )
        val barrier = CyclicBarrier(files.size)

        val results = files.map { file ->
            async(Dispatchers.IO) {
                barrier.await(30, TimeUnit.SECONDS)
                store.registerFile(
                    file = file,
                    title = "并发同一本",
                    author = null,
                    tags = listOf(zlibBookTag("same-book")),
                )
            }
        }.awaitAll()

        assertTrue("并发登记都应成功返回已有/新建条目: " + results, results.all { it.isSuccess })
        assertEquals("同 zlib tag 并发登记后仍只能有一条", 1, store.books.size)
        assertEquals(1, decodeIndex(dir).size)
    }

    @Test
    fun registerFileClearsMissingTagWhenFileIsBack() = runBlocking {
        val dir = newTempDir("heal-missing")
        val file = sniffableBookFile(dir, "back.epub")
        val seed = LibraryIndexStore(dir, TestIndexCodec())
        seed.load { it }
        seed.registerFile(file, "回来了", null, listOf("missing")).getOrThrow()
        assertTrue("missing" in decodeIndex(dir).single().tags)

        val store = LibraryIndexStore(dir, TestIndexCodec())
        val healed = store.registerFile(file, "回来了", null, emptyList()).getOrThrow()

        assertFalse("命中条目应清掉 missing 标记", "missing" in healed.tags)
        assertFalse("内存态也应清掉", "missing" in store.books.single().tags)
        assertFalse("索引回写后也应清掉", "missing" in decodeIndex(dir).single().tags)
    }

    // ---------------------------------------------------------------- 备份恢复 / 下载目录补登记

    @Test
    fun missingIndexRecoversFromBak1WithoutTriggeringDownloadScan() = runBlocking {
        val dir = newTempDir("bak-recover")
        val seed = LibraryIndexStore(dir, TestIndexCodec())
        seed.load { it }
        seed.registerFile(sniffableBookFile(dir, "seed.epub"), "seed", null, emptyList()).getOrThrow()
        assertTrue("成功提交后应有 bak1", File(dir, "books.json.bak1").isFile)
        assertTrue("模拟索引文件被删", File(dir, "books.json").delete())

        val store = LibraryIndexStore(dir, TestIndexCodec())
        val loaded = store.load { it }

        assertEquals("索引丢失时应从 bak1 恢复，而不是空书架", 1, loaded.size)
        assertEquals("seed", loaded.single().title)
        assertTrue("应标记为重建", store.lastLoadWasRebuilt)
        assertFalse("恢复出条目时不能扫下载目录（避免删过的书复活）", store.lastLoadLostIndex)
    }

    @Test
    fun lostIndexWithoutRecoverableBackupIsFlaggedForDownloadScan() = runBlocking {
        val dir = newTempDir("lost-index")
        File(dir, "books.json.bad").writeText("not a valid index")

        val store = LibraryIndexStore(dir, TestIndexCodec())
        assertTrue(store.load { it }.isEmpty())

        assertTrue("索引确实没了应标记重建", store.lastLoadWasRebuilt)
        assertTrue("没有可恢复条目时才允许扫下载目录", store.lastLoadLostIndex)
    }

    @Test
    fun recoverCandidatesBackfillsMetadataThenIsIdempotent() = runBlocking {
        val dir = newTempDir("recover-candidates")
        val downloadDir = newTempDir("downloads")
        val target = sniffableBookFile(downloadDir, "downloaded-book.epub")
        val known = sniffableBookFile(downloadDir, "known.epub")
        val store = LibraryIndexStore(dir, TestIndexCodec())
        val loaded = store.load { it }
        val knownBook = book(known.absolutePath, tags = listOf("zlib-download"))
        val hints = listOf(
            LibraryDownloadHint(
                destPath = target.absolutePath,
                title = "下载书名",
                author = "下载作者",
                bookId = "z9",
                finishedAt = 1_700_000_000_000L,
            ),
            LibraryDownloadHint(
                destPath = known.absolutePath,
                title = "已知书",
                author = null,
                bookId = "z8",
                finishedAt = 0L,
            ),
        )

        val candidates = buildDownloadRecoveryCandidates(downloadDir, loaded + knownBook, hints)
        assertEquals("已收录文件不能重复登记，非书籍文件也要过滤", 1, candidates.size)

        val result = store.recoverCandidates(candidates)
        assertEquals(1, result.adopted.size)
        val adopted = result.adopted.single()
        assertEquals("下载书名", adopted.title)
        assertEquals("下载作者", adopted.author)
        assertTrue("应带下载 tag", "zlib-download" in adopted.tags)
        assertTrue("应带 zlib bookId tag", zlibBookTag("z9") in adopted.tags)
        assertNotNull("下载完成时间应回填 addedAt", adopted.addedAt)
        assertEquals(target.absolutePath, adopted.filePath)

        val again = store.recoverCandidates(candidates)
        assertTrue("再跑一次应幂等", again.adopted.isEmpty())
        assertEquals(1, store.books.size)
    }

    // ---------------------------------------------------------------- helpers

    private fun newTempDir(name: String): File = File(root, name).apply { mkdirs() }

    private fun book(
        path: String,
        id: Long = 1L,
        title: String = File(path).nameWithoutExtension,
        tags: List<String> = emptyList(),
    ): Book = Book(
        id = id,
        title = title,
        author = null,
        format = BookFormat.Epub,
        filePath = path,
        fileSize = 0L,
        tags = tags,
    )

    private fun sniffableBookFile(dir: File, name: String): File = File(dir, name).apply {
        writeBytes(epubHeader())
    }

    private fun epubHeader(): ByteArray =
        byteArrayOf(0x50, 0x4B, 0x03, 0x04) + "epub test content".toByteArray()

    private fun decodeIndex(dir: File): List<Book> =
        TestIndexCodec().decode(File(dir, "books.json").readText())
}
