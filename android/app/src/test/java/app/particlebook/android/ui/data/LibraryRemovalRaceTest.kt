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
        assertTrue(
            "task-108 P2-3：marker-only 不是已提交空快照，必须保留下载补扫资格",
            store.lastLoadLostIndex,
        )
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

        val deleteBody = functionBody(source, "private fun deleteEmptyMarkerLocked(")
        assertTrue("task-108 P2-4：非空提交删 marker 必须有限次重试", deleteBody.contains("EMPTY_MARKER_DELETE_ATTEMPTS"))
        assertTrue("task-108 P2-4：删除失败必须记日志", deleteBody.contains("delete empty index marker failed"))
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
        // task-108 P1-1：删前就已经存在同 zlib tag 的合法重复条目（历史并发/手工 adopt 残留）。
        val preexistingDuplicate = book("preexisting-duplicate", id = 11L, tags = listOf(tag))
        val unrelated = book("unrelated", id = 12L, tags = listOf(zlibBookTag("other")))
        File(dir, "books.json").writeText(codec().encode(listOf(old, preexistingDuplicate, unrelated)))

        val store = LibraryIndexStore(dir, codec())
        store.load { it }

        val removal = store.removeWithSnapshot(setOf(old.id))
        assertEquals("先提交本次删书", listOf(old.id), removal.removed.map { it.id })
        assertEquals("删前快照必须包含全部既有 id", setOf(10L, 11L, 12L), removal.preexistingIds)

        // 删书提交之后才 adopt 成功的新条目（新 id，同 zlib tag）。
        val adoptedFile = File(dir, "adopted.epub").apply {
            writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04) + "adopted".toByteArray())
        }
        val adopted = store.recoverCandidates(
            listOf(LibraryFileCandidate(file = adoptedFile, title = "adopted", tags = listOf(tag))),
        ).adopted.single()
        assertTrue("新 adopt 的 id 不在删前快照里", adopted.id !in removal.preexistingIds)

        val reclaimed = store.removeBooksByTags(
            setOf(tag),
            excludeIds = setOf(old.id),
            preexistingIds = removal.preexistingIds,
        )

        assertEquals("只回收删后新 adopt 的条目", listOf(adopted.id), reclaimed.map { it.id })
        assertEquals(
            "删前已有的同 tag 合法重复条目和无关书都不能误删",
            listOf(preexistingDuplicate.id, unrelated.id),
            store.books.map { it.id }.sorted(),
        )
        assertEquals(
            "落盘也保留删前重复条目和无关书",
            listOf(preexistingDuplicate.id, unrelated.id),
            decodeIndex(dir).map { it.id }.sorted(),
        )
        assertTrue("空 tag 集合直接 no-op", store.removeBooksByTags(emptySet()).isEmpty())
    }

    @Test
    fun removalNotifiesBusBeforeHashingAndReclaimsAfterwards() {
        val source = sourceFile("src/main/java/app/particlebook/android/ui/data/LibraryStore.kt")
        val internal = functionBody(source, "private suspend fun removeBooksInternal(")
        val removeIdx = internal.indexOf("indexStore.removeWithSnapshot(ids)")
        val busIdx = internal.indexOf("LibraryRemovalBus.notifyRemoved(removed)")
        val tombstoneIdx = internal.indexOf("rememberDeletedFileTombstones(removed)")
        val reclaimIdx = internal.indexOf("reclaimAdoptedDuringRemoval(removed, removal.preexistingIds)")

        assertTrue("先提交索引删除并取得删前 id 快照", removeIdx >= 0)
        assertTrue("bus 必须在索引删除之后", removeIdx < busIdx)
        assertTrue("bus 必须前移到指纹哈希之前（管理器墓碑先写）", busIdx < tombstoneIdx)
        assertTrue("哈希之后再按 zlib tag 回收竞态 adopt", tombstoneIdx < reclaimIdx)
        assertTrue(
            "回收只应在正常删书（notifyRemovalBus）路径执行，静默回收不能递归",
            internal.substring(busIdx, reclaimIdx).contains("if (notifyRemovalBus)"),
        )

        val reclaim = functionBody(source, "private suspend fun reclaimAdoptedDuringRemoval(")
        assertTrue("回收用本次删书的 zlib tag", reclaim.contains("zlibRemovalTags(removed)"))
        assertTrue(
            "锁内按 tag + 删前 id 快照删除新条目",
            reclaim.contains("indexStore.removeBooksByTags(tags, removedIds, preexistingIds)"),
        )
        assertTrue("回收路径不能再发 bus（防递归）", !reclaim.contains("LibraryRemovalBus"))
    }


    // ---------------------------------------------------------------- task-108 P2 marker 容差/时钟

    @Test
    fun markerOnlyRecoveryKeepsDownloadScanEligible() = runBlocking {
        val dir = newTempDir("marker-only-scan")
        val now = 2_000_000L
        File(dir, "books.json.bak1").writeText(codec().encode(listOf(book("deleted"))))
        assertTrue(File(dir, "books.json.bak1").setLastModified(now - 60_000L))
        File(dir, "books.json.empty").writeText(now.toString())
        var info: LibraryIndexLoadInfo? = null

        val store = LibraryIndexStore(dir, codec(), now = { now })
        val loaded = store.loadWithRecovery(
            merge = { it },
            recover = { observed, _ ->
                info = observed
                emptyList()
            },
        )

        assertTrue("marker-only 恢复仍应是空书架", loaded.isEmpty())
        assertTrue("空恢复必须标记为 marker-only", info!!.markerOnly)
        assertEquals("marker-only 的恢复边界应保留 marker 时间", now, info!!.snapshotModified)
        assertTrue("task-108 P2-3：marker-only 不能抑制下载补扫", store.lastLoadLostIndex)

        val markerTime = info!!.snapshotModified ?: 0L
        assertTrue(
            "下载记录比 marker 新时必须允许补扫",
            shouldRecoverDownloadedBooks(
                rebuilt = true,
                recoveredSnapshotModified = markerTime,
                newestDownloadFinishedAt = markerTime + 10_000L,
            ),
        )
        val downloadDir = newTempDir("marker-only-downloads")
        val downloaded = File(downloadDir, "new.pdf").apply { writeText("%PDF-1.4\n%%EOF\n") }
        val candidates = buildDownloadRecoveryCandidates(
            downloadDir = downloadDir,
            loaded = emptyList(),
            hints = listOf(
                LibraryDownloadHint(
                    destPath = downloaded.absolutePath,
                    title = "new",
                    author = null,
                    bookId = "z-new",
                    finishedAt = markerTime + 10_000L,
                ),
            ),
            minFinishedAt = markerTime,
        )
        assertEquals("marker 之后下载的新书应进入补扫候选", listOf(downloaded.name), candidates.map { it.file.name })
        assertTrue("补扫候选应带 zlib 身份 tag", candidates.single().tags.contains(zlibBookTag("z-new")))
    }

    @Test
    fun parsedEmptySnapshotStillSuppressesDownloadScan() = runBlocking {
        val dir = newTempDir("parsed-empty-snapshot")
        val now = 2_000_000L
        File(dir, "books.json.bak1").writeText(codec().encode(emptyList()))
        assertTrue(File(dir, "books.json.bak1").setLastModified(now))
        File(dir, "books.json.empty").writeText(now.toString())
        var info: LibraryIndexLoadInfo? = null

        val store = LibraryIndexStore(dir, codec(), now = { now })
        val loaded = store.loadWithRecovery(
            merge = { it },
            recover = { observed, _ ->
                info = observed
                emptyList()
            },
        )

        assertTrue(loaded.isEmpty())
        assertFalse("解析到真实空快照，不是 marker-only", info!!.markerOnly)
        assertFalse("真实空快照是已提交状态，不应再扫下载目录", store.lastLoadLostIndex)
    }

    @Test
    fun nonEmptySnapshotSameMillisecondAsMarkerIsRecovered() = runBlocking {
        val dir = newTempDir("marker-same-ms")
        val now = 2_000_000L
        File(dir, "books.json.bak1").writeText(codec().encode(listOf(book("same-ms"))))
        assertTrue(File(dir, "books.json.bak1").setLastModified(now))
        File(dir, "books.json.empty").writeText(now.toString())

        val loaded = LibraryIndexStore(dir, codec(), now = { now }).load { it }

        assertEquals("同毫秒的合法新快照不能被 marker 误判为删前旧数据", listOf("same-ms"), loaded.map { it.title })
    }

    @Test
    fun nonEmptySnapshotWithinToleranceIsRecovered() = runBlocking {
        val dir = newTempDir("marker-tolerance")
        val now = 2_000_000L
        File(dir, "books.json.bak1").writeText(codec().encode(listOf(book("rollback"))))
        assertTrue(File(dir, "books.json.bak1").setLastModified(now - 1_000L))
        File(dir, "books.json.empty").writeText(now.toString())

        val loaded = LibraryIndexStore(dir, codec(), now = { now }).load { it }

        assertEquals("2s 容差内的回拨/同提交快照必须恢复", listOf("rollback"), loaded.map { it.title })
    }

    @Test
    fun nonEmptySnapshotOlderThanToleranceIsRejected() = runBlocking {
        val dir = newTempDir("marker-stale-boundary")
        val now = 2_000_000L
        File(dir, "books.json.bak1").writeText(codec().encode(listOf(book("deleted"))))
        assertTrue(File(dir, "books.json.bak1").setLastModified(now - 2_001L))
        File(dir, "books.json.empty").writeText(now.toString())

        val store = LibraryIndexStore(dir, codec(), now = { now })
        val loaded = store.load { it }

        assertTrue("超出容差的删前快照不能复活", loaded.isEmpty())
        assertTrue("被 marker 否决时是 marker-only 恢复", store.lastLoadLostIndex)
    }

    @Test
    fun futureMarkerIsIgnoredSoRealSnapshotSurvives() = runBlocking {
        val dir = newTempDir("future-marker")
        val now = 2_000_000L
        File(dir, "books.json.bak1").writeText(codec().encode(listOf(book("real"))))
        assertTrue(File(dir, "books.json.bak1").setLastModified(now - 60_000L))
        File(dir, "books.json.empty").writeText((now + 10 * 60_000L).toString())

        val loaded = LibraryIndexStore(dir, codec(), now = { now }).load { it }

        assertEquals("未来 marker 是非法证据，不能永久压制真实快照", listOf("real"), loaded.map { it.title })
    }

    @Test
    fun invalidMarkerContentIsIgnored() = runBlocking {
        val dir = newTempDir("invalid-marker")
        val now = 2_000_000L
        File(dir, "books.json.bak1").writeText(codec().encode(listOf(book("real"))))
        assertTrue(File(dir, "books.json.bak1").setLastModified(now - 60_000L))
        File(dir, "books.json.empty").writeText("not-a-timestamp")

        val loaded = LibraryIndexStore(dir, codec(), now = { now }).load { it }

        assertEquals("无法解析的 marker 应被忽略", listOf("real"), loaded.map { it.title })
    }

    @Test
    fun futureSnapshotCannotBypassMarker() = runBlocking {
        val dir = newTempDir("future-snapshot-vs-marker")
        val now = 2_000_000L
        File(dir, "books.json.bak1").writeText(codec().encode(listOf(book("deleted"))))
        assertTrue(File(dir, "books.json.bak1").setLastModified(now + 10 * 60_000L))
        File(dir, "books.json.empty").writeText(now.toString())

        val store = LibraryIndexStore(dir, codec(), now = { now })
        val loaded = store.load { it }

        assertTrue("未来 mtime 快照不能绕过 marker 复活删前数据", loaded.isEmpty())
        assertTrue("跳过未来快照后仍是 marker-only 恢复", store.lastLoadLostIndex)
    }

    @Test
    fun clockRollbackWithinToleranceAcceptsNewSnapshot() = runBlocking {
        val dir = newTempDir("clock-rollback")
        val now = 2_000_000L
        File(dir, "books.json.bak1").writeText(codec().encode(listOf(book("after-rollback"))))
        assertTrue(File(dir, "books.json.bak1").setLastModified(now))
        // marker 时间比当前时钟快 1s：模拟回拨，仍在 2s 容差内。
        File(dir, "books.json.empty").writeText((now + 1_000L).toString())

        val loaded = LibraryIndexStore(dir, codec(), now = { now }).load { it }

        assertEquals("回拨 1s 的新快照不能被 marker 否决", listOf("after-rollback"), loaded.map { it.title })
    }

    @Test
    fun nonEmptyCommitRetriesMarkerDeletionAndLogsFailure() = runBlocking {
        val dir = newTempDir("marker-delete-failure")
        val logs = mutableListOf<String>()
        val store = LibraryIndexStore(dir, codec(), log = { message, _ -> logs += message })
        store.load { it }
        val marker = File(dir, "books.json.empty")
        assertTrue("空索引加载后应留下 marker", marker.isFile)
        assertTrue(marker.delete())
        assertTrue("用非空目录模拟 marker 删除失败", marker.mkdirs())
        File(marker, "keep").writeText("x")

        val file = File(dir, "book.epub").apply {
            writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04) + "book".toByteArray())
        }
        val added = store.registerFile(file, "book", null, emptyList()).getOrThrow()

        assertEquals("book", added.title)
        assertTrue("非空提交删 marker 失败必须记日志: " + logs, logs.any { it.contains("delete empty index marker failed") })
        assertTrue("marker 删除失败不能让索引提交失败", File(dir, "books.json").isFile)
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
