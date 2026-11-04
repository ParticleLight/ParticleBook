package app.particlebook.android.ui.data

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** task-81：P1-2 补扫先于索引写回；P2-1 mtime 同值/未来/0；P2-4 空快照证据。 */
class LibraryIndexStoreLoadRecoveryTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("pb-index-load-recovery-").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun recoveryCandidatesAreAdoptedBeforeIndexIsWritten() = runBlocking {
        val dir = newTempDir("recover-before-write")
        val file = epubFile(dir, "downloaded.epub")
        var observed: LibraryIndexLoadInfo? = null
        val store = LibraryIndexStore(dir, TestIndexCodec())

        val loaded = store.loadWithRecovery(
            merge = { it },
            recover = { info, _ ->
                observed = info
                listOf(LibraryFileCandidate(file = file, title = "补扫书", tags = listOf(DOWNLOAD_TAG)))
            },
        )

        assertTrue("无索引时 load 应标记 rebuilt", observed!!.rebuilt)
        assertEquals(1, loaded.size)
        assertEquals("补扫书", loaded.single().title)
        assertEquals("最终 books.json 必须包含补扫条目", "补扫书", decodeIndex(dir).single().title)
        assertTrue(store.lastLoadWasRebuilt)
    }

    @Test
    fun recoveryFailureDoesNotWriteIndexAndNextLoadRetries() = runBlocking {
        val dir = newTempDir("recover-failure")
        val file = epubFile(dir, "retry.epub")
        val store = LibraryIndexStore(dir, TestIndexCodec())
        var fail = true

        val first = runCatching {
            store.loadWithRecovery(
                merge = { it },
                recover = { _, _ ->
                    if (fail) error("simulated download recovery failure")
                    listOf(LibraryFileCandidate(file = file, title = "重试书"))
                },
            )
        }
        assertTrue("恢复回调失败应向上抛", first.isFailure)
        assertFalse("写回前失败不能留下 books.json", File(dir, "books.json").exists())

        fail = false
        val loaded = store.loadWithRecovery(
            merge = { it },
            recover = { _, _ -> listOf(LibraryFileCandidate(file = file, title = "重试书")) },
        )
        assertEquals("下一次 load 应重新补扫成功", listOf("重试书"), loaded.map { it.title })
        assertEquals("重试后索引应落盘", "重试书", decodeIndex(dir).single().title)
    }

    @Test
    fun equalMtimePrefersBak1() = runBlocking {
        val dir = newTempDir("equal-mtime")
        val now = 2_000_000L
        File(dir, "books.json.bak1").writeText(TestIndexCodec().encode(listOf(book("bak1"))))
        File(dir, "books.json.bak2").writeText(TestIndexCodec().encode(listOf(book("bak2"))))
        assertTrue(File(dir, "books.json.bak1").setLastModified(now))
        assertTrue(File(dir, "books.json.bak2").setLastModified(now))

        val store = LibraryIndexStore(dir, TestIndexCodec(), now = { now })
        val loaded = store.load { it }

        assertEquals("同 mtime 优先最近一次提交 bak1", listOf("bak1"), loaded.map { it.title })
        assertEquals(now, store.lastRecoveredSnapshotModified)
    }

    @Test
    fun futureMtimeIsDeprioritized() = runBlocking {
        val dir = newTempDir("future-mtime")
        val now = 2_000_000L
        File(dir, "books.json.bak1").writeText(TestIndexCodec().encode(listOf(book("bak1"))))
        File(dir, "books.json.bad").writeText(TestIndexCodec().encode(listOf(book("future-bad"))))
        assertTrue(File(dir, "books.json.bak1").setLastModified(now - 1_000L))
        assertTrue(File(dir, "books.json.bad").setLastModified(now + 10 * 60_000L))

        val store = LibraryIndexStore(dir, TestIndexCodec(), now = { now })
        val loaded = store.load { it }

        assertEquals("未来 mtime 的 .bad 不能压过正常 bak1", listOf("bak1"), loaded.map { it.title })
        assertEquals("采用正常快照的 effective mtime", now - 1_000L, store.lastRecoveredSnapshotModified)
    }

    @Test
    fun zeroMtimeSnapshotIsTreatedAsOldest() = runBlocking {
        val dir = newTempDir("zero-mtime")
        val now = 2_000_000L
        File(dir, "books.json.bak1").writeText(TestIndexCodec().encode(listOf(book("zero"))))
        File(dir, "books.json.bak2").writeText(TestIndexCodec().encode(listOf(book("normal"))))
        assertTrue(File(dir, "books.json.bak1").setLastModified(0L))
        assertTrue(File(dir, "books.json.bak2").setLastModified(now - 1_000L))

        val store = LibraryIndexStore(dir, TestIndexCodec(), now = { now })
        val loaded = store.load { it }

        assertEquals("0 mtime 应排最后，选正常 bak2", listOf("normal"), loaded.map { it.title })
    }

    @Test
    fun emptySnapshotWithoutMarkerFallsBackToNonEmpty() = runBlocking {
        val dir = newTempDir("empty-no-marker")
        val now = 2_000_000L
        File(dir, "books.json.bak1").writeText(TestIndexCodec().encode(emptyList()))
        File(dir, "books.json.bak2").writeText(TestIndexCodec().encode(listOf(book("old"))))
        assertTrue(File(dir, "books.json.bak1").setLastModified(now))
        assertTrue(File(dir, "books.json.bak2").setLastModified(now - 1_000L))

        val store = LibraryIndexStore(dir, TestIndexCodec(), now = { now })
        val loaded = store.load { it }

        assertEquals("无空书架提交证据时不能信任伪空快照", listOf("old"), loaded.map { it.title })
    }

    @Test
    fun emptyCommitWritesMarkerAndNonEmptyCommitClearsIt() = runBlocking {
        val dir = newTempDir("empty-marker")
        val store = LibraryIndexStore(dir, TestIndexCodec())
        store.load { it }
        val file = epubFile(dir, "only.epub")
        val added = store.registerFile(file, "only", null, emptyList()).getOrThrow()
        assertFalse("非空提交不应留下空标记", File(dir, "books.json.empty").exists())

        store.remove(setOf(added.id))
        assertTrue("空书架提交应留下 books.json.empty 证据", File(dir, "books.json.empty").isFile)

        store.registerFile(epubFile(dir, "new.epub"), "new", null, emptyList()).getOrThrow()
        assertFalse("非空提交应删除空标记", File(dir, "books.json.empty").exists())
    }

    private fun newTempDir(name: String): File = File(root, name).apply { mkdirs() }

    private fun epubFile(dir: File, name: String): File = File(dir, name).apply {
        writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ("epub index test " + name).toByteArray())
    }

    private fun book(title: String): Book = Book(
        id = title.hashCode().toLong() and 0x7FFFFFFF,
        title = title,
        author = null,
        format = BookFormat.Epub,
        filePath = File(root, title + ".epub").absolutePath,
        fileSize = 0L,
    )

    private fun decodeIndex(dir: File): List<Book> =
        TestIndexCodec().decode(File(dir, "books.json").readText())

    private companion object {
        const val DOWNLOAD_TAG = "zlib-download"
    }
}
