package app.particlebook.android.ui.data

import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P1c U1：LibraryStore 的 books.json 并发写（adopt / adopt+remove / 损坏重建 / 写失败）。
 *
 * 生产编解码是 org.json，本地 JVM 单测里不可用（android.jar 是 not mocked），因此注入
 * [TestIndexCodec]：它写/读与生产相同的 books.json 文本，断言直接检查磁盘上的真实 JSON。
 * 并发部分用真实协程（Dispatchers.IO + CyclicBarrier 同时起跑）驱动，不是串行模拟。
 */
class LibraryIndexStoreTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("pb-library-index-").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    // ---------------------------------------------------------------- U1 复现：两本并发 adopt

    @Test
    fun concurrentAdoptsOfTwoBooksBothSurvive() = runBlocking {
        repeat(8) { round ->
            val dir = newTempDir("two-" + round)
            val store = LibraryIndexStore(dir, TestIndexCodec())
            store.load { it }
            val first = bookFile(dir, "first.epub")
            val second = bookFile(dir, "second.pdf")
            val barrier = CyclicBarrier(2)
            val results = listOf(first, second).map { file ->
                async(Dispatchers.IO) {
                    barrier.await(30, TimeUnit.SECONDS)
                    store.registerFile(
                        file = file,
                        title = file.nameWithoutExtension,
                        author = null,
                        tags = listOf(DOWNLOAD_TAG),
                    )
                }
            }.awaitAll()

            assertTrue("round " + round + " 有 adopt 失败: " + results, results.all { it.isSuccess })
            assertEquals("round " + round + " 内存丢书", 2, store.books.size)
            assertEquals("round " + round + " 索引丢书", 2, indexEntries(dir).size)
            assertEquals("round " + round + " 索引不可回读", 2, decodeIndex(dir).size)
            assertNoTempFilesLeft(dir)
        }
    }

    @Test
    fun concurrentAdoptsStressKeepsEveryBookAndValidJson() = runBlocking {
        val dir = newTempDir("stress")
        val store = LibraryIndexStore(dir, TestIndexCodec())
        store.load { it }
        val files = (1..8).map { index -> bookFile(dir, "book-" + index + ".epub") }
        val barrier = CyclicBarrier(files.size)
        val results = files.map { file ->
            async(Dispatchers.IO) {
                barrier.await(30, TimeUnit.SECONDS)
                store.registerFile(file, "并发书", "作者", listOf(DOWNLOAD_TAG))
            }
        }.awaitAll()

        assertTrue("有 adopt 失败: " + results, results.all { it.isSuccess })
        assertEquals(8, store.books.size)
        assertEquals(8, store.books.map { it.id }.toSet().size)

        // 直接检查 books.json：条目数、路径唯一、JSON 可解析。
        val entries = indexEntries(dir)
        assertEquals(8, entries.size)
        assertEquals(8, entries.map { it["filePath"] }.toSet().size)
        assertEquals(8, decodeIndex(dir).size)

        // 等价冷启动：新实例重新加载仍是 8 本。
        val reopened = LibraryIndexStore(dir, TestIndexCodec())
        assertEquals(8, reopened.load { it }.size)
        assertNoTempFilesLeft(dir)
    }

    // ---------------------------------------------------------------- adopt + remove 交错

    @Test
    fun concurrentAdoptAndRemoveNeitherLoseNorResurrect() = runBlocking {
        repeat(6) { round ->
            val dir = newTempDir("adopt-remove-" + round)
            val store = LibraryIndexStore(dir, TestIndexCodec())
            store.load { it }
            val seeded = listOf("keep-a", "keep-b", "removed").map { name ->
                store.registerFile(bookFile(dir, name + ".epub"), name, null, emptyList()).getOrThrow()
            }
            val toRemove = seeded.last()
            val newFile = bookFile(dir, "new.epub")
            val barrier = CyclicBarrier(2)
            val adoptJob = async(Dispatchers.IO) {
                barrier.await(30, TimeUnit.SECONDS)
                store.registerFile(newFile, "new", null, emptyList())
            }
            val removeJob = async(Dispatchers.IO) {
                barrier.await(30, TimeUnit.SECONDS)
                store.remove(setOf(toRemove.id))
            }
            val adopted = adoptJob.await()
            val removed = removeJob.await()

            assertTrue("round " + round + " adopt 失败: " + adopted, adopted.isSuccess)
            assertEquals("round " + round + " 删除结果", listOf(toRemove), removed)
            val expected = setOf("keep-a.epub", "keep-b.epub", "new.epub")
            assertEquals(
                "round " + round + " 内存丢书/复活",
                expected,
                store.books.map { File(it.filePath).name }.toSet(),
            )
            assertEquals(
                "round " + round + " 索引丢书/复活",
                expected,
                indexEntries(dir).map { File(it["filePath"] as String).name }.toSet(),
            )
            assertNoTempFilesLeft(dir)
        }
    }

    @Test
    fun concurrentAdoptsOfSamePathStayIdempotent() = runBlocking {
        val dir = newTempDir("idempotent")
        val store = LibraryIndexStore(dir, TestIndexCodec())
        store.load { it }
        val file = bookFile(dir, "same.pdf")
        val barrier = CyclicBarrier(6)
        val results = (1..6).map {
            async(Dispatchers.IO) {
                barrier.await(30, TimeUnit.SECONDS)
                store.registerFile(file, "same", null, listOf(DOWNLOAD_TAG))
            }
        }.awaitAll()

        assertTrue(results.all { it.isSuccess })
        assertEquals("同一路径只应有一个 id", 1, results.map { it.getOrThrow().id }.toSet().size)
        assertEquals(1, store.books.size)
        assertEquals(1, indexEntries(dir).size)
        assertNoTempFilesLeft(dir)
    }

    @Test
    fun concurrentRemovesOfSameBookRemoveExactlyOnce() = runBlocking {
        val dir = newTempDir("remove")
        val store = LibraryIndexStore(dir, TestIndexCodec())
        store.load { it }
        val book = store.registerFile(bookFile(dir, "only.epub"), "only", null, emptyList()).getOrThrow()
        val barrier = CyclicBarrier(4)
        val results = (1..4).map {
            async(Dispatchers.IO) {
                barrier.await(30, TimeUnit.SECONDS)
                store.remove(setOf(book.id))
            }
        }.awaitAll()

        assertEquals("只有一次真正删除: " + results, 1, results.count { it.isNotEmpty() })
        assertTrue("删除后内存应为空", store.books.isEmpty())
        assertEquals(0, indexEntries(dir).size)
        assertNoTempFilesLeft(dir)
    }

    // ---------------------------------------------------------------- 损坏重建

    @Test
    fun corruptIndexKeepsBadBackupAndRebuildsValidEmptyIndex() = runBlocking {
        val dir = newTempDir("corrupt")
        val seed = LibraryIndexStore(dir, TestIndexCodec())
        seed.load { it }
        seed.registerFile(bookFile(dir, "seed.epub"), "seed", null, emptyList()).getOrThrow()
        assertEquals(1, indexEntries(dir).size)

        val corruptText = """{"version":1,"books":[{"id":1,"title":"未闭合"""
        File(dir, "books.json").writeText(corruptText)

        val logs = Collections.synchronizedList(mutableListOf<String>())
        val recovering = LibraryIndexStore(
            dir,
            TestIndexCodec(),
            log = { message, _ -> logs += message },
        )
        val loaded = recovering.load { it }

        assertTrue("损坏索引应重建为空书架", loaded.isEmpty())
        val bad = File(dir, "books.json.bad")
        assertTrue("应保留 .bad 备份", bad.isFile)
        assertEquals(" .bad 内容应为被替换前的原文", corruptText, bad.readText())
        assertEquals("重建后索引应可解析", 0, indexEntries(dir).size)
        assertEquals(0, decodeIndex(dir).size)
        assertTrue("应记录 parse failed 日志: " + logs, logs.any { it.contains("parse failed") })
        assertNoTempFilesLeft(dir)
    }

    @Test
    fun indexWithoutBooksArrayIsTreatedAsCorrupt() = runBlocking {
        val dir = newTempDir("corrupt-missing-books")
        val original = """{"version":1}"""
        File(dir, "books.json").writeText(original)

        val store = LibraryIndexStore(dir, TestIndexCodec())
        assertTrue(store.load { it }.isEmpty())

        assertEquals(original, File(dir, "books.json.bad").readText())
        assertEquals("重建后应是合法空索引", 0, indexEntries(dir).size)
    }

    @Test
    fun unreadableIndexIsQuarantinedInsteadOfBeingTreatedAsEmpty() = runBlocking {
        val dir = newTempDir("unreadable-index")
        // 让 books.json 无法被读成文本（同名目录）：readText 会抛，绝不能当成「空索引」直接覆盖。
        val broken = File(dir, "books.json").apply { mkdirs() }
        File(broken, "sentinel.txt").writeText("用户原始数据")

        val logs = Collections.synchronizedList(mutableListOf<String>())
        val store = LibraryIndexStore(dir, TestIndexCodec(), log = { message, _ -> logs += message })
        assertTrue("读不出索引时应重建为空书架", store.load { it }.isEmpty())

        val bad = File(dir, "books.json.bad")
        assertTrue("读失败的索引应原样留成 .bad", bad.isDirectory)
        assertEquals(
            " .bad 里应保留原数据",
            "用户原始数据",
            File(bad, "sentinel.txt").readText(),
        )
        assertTrue("重建后 books.json 应是合法索引文件", File(dir, "books.json").isFile)
        assertEquals("重建后应是空索引", 0, indexEntries(dir).size)
        assertTrue("应记录 read index failed 日志: " + logs, logs.any { it.contains("read index failed") })
        assertNoTempFilesLeft(dir)
    }

    // ---------------------------------------------------------------- 写失败不提交

    @Test
    fun failedIndexWriteDoesNotCommitOrChangeMemory() = runBlocking {
        val dir = newTempDir("write-failure")
        val delegate = TestIndexCodec()
        val failingCodec = object : LibraryIndexCodec {
            override fun encode(books: List<Book>): String {
                if (books.any { it.title == "BOOM" }) {
                    throw IllegalStateException("simulated index write failure")
                }
                return delegate.encode(books)
            }

            override fun decode(json: String): List<Book> = delegate.decode(json)
        }
        val store = LibraryIndexStore(dir, failingCodec)
        store.load { it }
        val good = store.registerFile(bookFile(dir, "good.epub"), "good", null, emptyList()).getOrThrow()
        val before = File(dir, "books.json").readText()

        val failed = store.registerFile(bookFile(dir, "boom.epub"), "BOOM", null, emptyList())

        assertTrue("写失败应返回 failure", failed.isFailure)
        assertEquals("写失败不得提交内存", listOf(good), store.books)
        assertEquals("写失败不得改动磁盘索引", before, File(dir, "books.json").readText())
        assertNoTempFilesLeft(dir)
    }

    // ---------------------------------------------------------------- helpers

    private fun newTempDir(name: String): File = File(root, name).apply { mkdirs() }

    private fun bookFile(dir: File, name: String): File = File(dir, name).apply {
        if (name.endsWith(".pdf", ignoreCase = true)) {
            writeBytes("%PDF-1.7 test content".toByteArray())
        } else {
            writeText("epub test content")
        }
    }

    private fun indexEntries(dir: File): List<Map<String, Any?>> {
        val parsed = TestJson.asObject(TestJson.parse(File(dir, "books.json").readText()), "books.json")
        return TestJson.asArray(parsed["books"], "books.json.books")
            .map { TestJson.asObject(it, "books[] 条目") }
    }

    private fun decodeIndex(dir: File): List<Book> =
        TestIndexCodec().decode(File(dir, "books.json").readText())

    private fun assertNoTempFilesLeft(dir: File) {
        val leftovers = dir.listFiles().orEmpty()
            .filter { it.name.startsWith("books.json.") && it.name.endsWith(".tmp") }
        assertTrue("不应残留临时文件: " + leftovers, leftovers.isEmpty())
    }

    private companion object {
        const val DOWNLOAD_TAG = "zlib-download"
    }
}
