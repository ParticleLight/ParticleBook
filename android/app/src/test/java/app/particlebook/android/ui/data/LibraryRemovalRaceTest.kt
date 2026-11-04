package app.particlebook.android.ui.data

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * task-98：a24be15 复核两个 P1 的回归测试。
 *
 * P1-1：空书架 marker 必须能否决比它更旧的非空快照（删光书后 main 损坏不能从旧 bak 复活）；
 *      写序改为 marker 先落盘，再写空 main/备份。
 * P1-2：删书时 bus 必须先于指纹哈希派发，并在其后按 zlib tag 回收竞态窗口里新 adopt 的条目。
 *
 * LibraryStore 本体依赖 Android Context，JVM 侧用 LibraryIndexStore + 源码契约锁定承重顺序/纯函数。
 */
class LibraryRemovalRaceTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("pb-removal-race-").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    // ---------------------------------------------------------------- P1-1 marker vs 旧非空快照

    @Test
    fun staleNonEmptySnapshotOlderThanEmptyMarkerIsRejected() = runBlocking {
        val dir = newTempDir("stale-bak-vs-marker")
        val now = 2_000_000L
        File(dir, "books.json.bak1").writeText(codec().encode(listOf(book("deleted"))))
        File(dir, "books.json.bak2").writeText(codec().encode(listOf(book("deleted-too"))))
        assertTrue(File(dir, "books.json.bak1").setLastModified(now - 60_000L))
        assertTrue(File(dir, "books.json.bak2").setLastModified(now - 90_000L))
        // 模拟崩溃窗口：空提交已写 marker，main 丢失/损坏，但 bak1/bak2 还是删前旧快照。
        File(dir, "books.json.empty").writeText(now.toString())

        val store = LibraryIndexStore(dir, codec(), now = { now })
        val loaded = store.load { it }

        assertTrue("比空书架 marker 更旧的非空快照不能复活: " + loaded.map { it.title }, loaded.isEmpty())
        assertEquals("应把 marker 时间当作恢复边界", now, store.lastRecoveredSnapshotModified)
        assertFalse("这是有证据的空快照恢复，不应再扫下载目录", store.lastLoadLostIndex)
        assertTrue("重建后的索引应是空书架", decodeIndex(dir).isEmpty())
        assertTrue("marker 不能被恢复过程删掉", File(dir, "books.json.empty").isFile)
    }

    @Test
    fun nonEmptySnapshotNewerThanEmptyMarkerIsRecovered() = runBlocking {
        val dir = newTempDir("newer-bak-than-marker")
        val now = 2_000_000L
        File(dir, "books.json.bak1").writeText(codec().encode(listOf(book("after-delete"))))
        assertTrue(File(dir, "books.json.bak1").setLastModified(now))
        // 旧 marker：用户删光后又有新书提交，但非空提交删 marker 恰好失败/崩溃，留下这个旧证据。
        File(dir, "books.json.empty").writeText((now - 120_000L).toString())

        val loaded = LibraryIndexStore(dir, codec(), now = { now }).load { it }

        assertEquals("marker 之后的非空快照是合法新数据，必须恢复", listOf("after-delete"), loaded.map { it.title })
    }

    @Test
    fun emptyCommitWritesMarkerBeforeEmptyIndexSnapshot() {
        val source = sourceFile("src/main/java/app/particlebook/android/ui/data/LibraryStore.kt")
        val body = functionBody(source, "private fun writeIndexLocked(")
        val markerWrite = body.indexOf("writePayloadLocked(File(dir, EMPTY_MARKER_NAME)")
        val mainWrite = body.indexOf("writePayloadLocked(indexFile()")
        assertTrue("空提交必须存在 marker 写入", markerWrite >= 0)
        assertTrue("marker 必须先于空 main 落盘（崩溃窗口里 marker 已持久化）", markerWrite < mainWrite)
        assertTrue("marker 写失败要抛出，不能静默留下无法证明的空索引", !body.contains("syncEmptyMarkerLocked(true)"))
    }

    // ---------------------------------------------------------------- P1-2 删书 vs 在途下载完成

    @Test
    fun zlibRemovalTagsOnlyCollectsZlibIdentityTags() {
        val removed = listOf(
            book("a", id = 1L, tags = listOf("zlib-download", zlibBookTag("z1"), safUriTag("content://doc/a"))),
            book("b", id = 2L, tags = listOf(zlibBookTag("z2"), "recovered")),
            book("c", id = 3L, tags = listOf("imported")),
        )

        assertEquals("只回收本次删书带 zlib 身份 tag 的书", setOf(zlibBookTag("z1"), zlibBookTag("z2")), zlibRemovalTags(removed))
        assertTrue("没有 zlib tag 的本地书不参与回收", zlibRemovalTags(listOf(removed[2])).isEmpty())
    }

    @Test
    fun removeBooksByTagsReclaimsNewAdoptedEntryAndKeepsExcludedOldOne() = runBlocking {
        val dir = newTempDir("reclaim-adopted")
        val tag = zlibBookTag("z9")
        val old = book("old", id = 10L, tags = listOf(tag))
        // 模拟竞态：串行写入索引里出现「旧条目 + 下载完成后新 adopt 的新 id」两条同 zlib tag 记录。
        val adopted = book("adopted", id = 11L, tags = listOf(tag))
        val unrelated = book("unrelated", id = 12L, tags = listOf(zlibBookTag("other")))
        File(dir, "books.json").writeText(codec().encode(listOf(old, adopted, unrelated)))

        val store = LibraryIndexStore(dir, codec())
        store.load { it }

        val reclaimed = store.removeBooksByTags(setOf(tag), excludeIds = setOf(old.id))

        assertEquals("只回收新 adopt 的条目，不重复删旧 id", listOf(adopted.id), reclaimed.map { it.id })
        assertEquals("无关的其它 zlib 书不能误删", listOf(old.id, unrelated.id), store.books.map { it.id }.sorted())
        assertEquals("落盘也只剩旧条目和无关书", listOf(old.id, unrelated.id), decodeIndex(dir).map { it.id }.sorted())
        assertTrue("空 tag 集合直接 no-op", store.removeBooksByTags(emptySet()).isEmpty())
    }

    @Test
    fun removalNotifiesBusBeforeHashingAndReclaimsAfterwards() {
        val source = sourceFile("src/main/java/app/particlebook/android/ui/data/LibraryStore.kt")
        val internal = functionBody(source, "private suspend fun removeBooksInternal(")
        val removeIdx = internal.indexOf("indexStore.remove(ids)")
        val busIdx = internal.indexOf("LibraryRemovalBus.notifyRemoved(removed)")
        val tombstoneIdx = internal.indexOf("rememberDeletedFileTombstones(removed)")
        val reclaimIdx = internal.indexOf("reclaimAdoptedDuringRemoval(removed)")

        assertTrue("先提交索引删除", removeIdx >= 0)
        assertTrue("bus 必须在索引删除之后", removeIdx < busIdx)
        assertTrue("bus 必须前移到指纹哈希之前（管理器墓碑先写）", busIdx < tombstoneIdx)
        assertTrue("哈希之后再按 zlib tag 回收竞态 adopt", tombstoneIdx < reclaimIdx)
        assertTrue(
            "回收只应在正常删书（notifyRemovalBus）路径执行，静默回收不能递归",
            internal.substring(busIdx, reclaimIdx).contains("if (notifyRemovalBus)"),
        )

        val reclaim = functionBody(source, "private suspend fun reclaimAdoptedDuringRemoval(")
        assertTrue("回收用本次删书的 zlib tag", reclaim.contains("zlibRemovalTags(removed)"))
        assertTrue("锁内按 tag 删除新条目", reclaim.contains("indexStore.removeBooksByTags(tags, removedIds)"))
        assertTrue("回收路径不能再发 bus（防递归）", !reclaim.contains("LibraryRemovalBus"))
    }

    // ---------------------------------------------------------------- helpers

    private fun codec(): TestIndexCodec = TestIndexCodec()

    private fun newTempDir(name: String): File = File(root, name).apply { mkdirs() }

    private fun book(title: String, id: Long = title.hashCode().toLong() and 0x7FFFFFFF, tags: List<String> = emptyList()): Book =
        Book(
            id = id,
            title = title,
            author = null,
            format = BookFormat.Epub,
            filePath = File(root, title + ".epub").absolutePath,
            fileSize = 0L,
            tags = tags,
        )

    private fun decodeIndex(dir: File): List<Book> =
        codec().decode(File(dir, "books.json").readText())

    /** 截取从签名到下一个顶层成员声明前的源码块（够用于契约断言）。 */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assumeTrue("找不到函数 " + signature, start >= 0)
        val tail = source.substring(start + signature.length)
        val nextPrivate = tail.indexOf("\n    private fun ")
        val nextPrivateSuspend = tail.indexOf("\n    private suspend fun ")
        val nextSuspend = tail.indexOf("\n    suspend fun ")
        val next = listOf(nextPrivate, nextPrivateSuspend, nextSuspend).filter { it >= 0 }.minOrNull() ?: tail.length
        return tail.substring(0, next)
    }

    /** 与其它源码契约测试相同的定位方式：从测试工作目录向上找 android/app 根。 */
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
        assumeTrue("找不到 " + relative + "（工作目录=" + System.getProperty("user.dir") + "），跳过源码契约检查", found != null)
        return found!!.readText()
    }
}
