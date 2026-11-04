package app.particlebook.android.ui

import app.particlebook.android.ui.data.Book
import app.particlebook.android.ui.data.BookFormat
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * task-79：删书时取消在途下载 + 墓碑防复活（Manager 侧）。
 *
 * 纯函数/内存集合覆盖墓碑持久化、冷启删书队列 TOCTOU、写盘快照合并；Manager 的
 * Android 协程接线用源码契约锁定（真机行为由 p15 复测）。
 */
class ZlibRemovalTombstoneTest {

    @Test
    fun removedBookIdsOnlyExtractZlibTags() {
        val books = listOf(
            book(1L, tags = listOf("zlib:111", "saf-uri:abc", "fp:1-deadbeef")),
            book(2L, tags = listOf("zlib:222", "zlib:")),
            book(3L, tags = emptyList()),
        )

        assertEquals("只取非空 zlib:<bookId>，其它身份 tag 不参与", setOf("111", "222"), zlibRemovedBookIds(books))
        assertTrue(zlibRemovedBookIds(emptyList()).isEmpty())
        assertEquals("重复 tag 去重", setOf("111"), zlibRemovedBookIds(listOf(book(4L, tags = listOf("zlib:111", "zlib:111")))))
    }

    @Test
    fun tombstonesPersistAcrossReloadAndClearOnExplicitAction() {
        val persistence = FakePersistence(setOf("111"))
        val tombstones = ZlibDownloadTombstones(persistence)
        tombstones.reload()

        assertTrue(tombstones.contains("111"))
        assertFalse(tombstones.contains("222"))
        assertFalse(tombstones.contains(""))

        tombstones.addAll(listOf("222", "  "))
        assertEquals("新增墓碑立即持久化", setOf("111", "222"), persistence.stored)

        val restarted = ZlibDownloadTombstones(persistence)
        restarted.reload()
        assertTrue("重启后墓碑仍生效", restarted.contains("222"))

        tombstones.remove("111")
        assertFalse(tombstones.contains("111"))
        assertEquals("显式重下后清墓碑并落盘", setOf("222"), persistence.stored)
        assertEquals("snapshot 是只读副本", setOf("222"), tombstones.snapshot())
    }

    @Test
    fun removalsOnlyDrainAfterHistoryLoaded() {
        val queued = listOf(book(1L, tags = listOf("zlib:111")), book(2L, tags = listOf("zlib:222")))

        assertTrue("历史未读完：删书事件必须留在队列里", zlibRemovalsToDrain(historyLoaded = false, queued = queued).isEmpty())
        assertEquals("历史已读完：补做队列", queued, zlibRemovalsToDrain(historyLoaded = true, queued = queued))
        assertTrue(zlibRemovalsToDrain(historyLoaded = true, queued = emptyList()).isEmpty())
    }

    @Test
    fun recordsForDiskPrefersPendingAndDeduplicates() {
        val historyA = task("a", ZlibDownloadStatus.COMPLETED)
        val historyB = task("b", ZlibDownloadStatus.COMPLETED)
        val pendingB = task("b", ZlibDownloadStatus.DOWNLOADING)
        val pendingC = task("c", ZlibDownloadStatus.PREPARING)

        val merged = zlibRecordsForDisk(listOf(historyA, historyB), listOf(pendingC, pendingB))

        assertEquals("非终态快照在前，且同书以 pending 为准", listOf("c", "b", "a"), merged.map { it.bookId })
        assertEquals(3, merged.size)
        assertEquals("pending 覆盖旧终态", ZlibDownloadStatus.DOWNLOADING, merged[1].status)
        assertEquals("没有 pending 时原样返回历史", listOf(historyA, historyB), zlibRecordsForDisk(listOf(historyA, historyB), emptyList()))
    }

    @Test
    fun managerWiresTombstonesCancelAndSilentReclaim() {
        val source = sourceFile("src/main/java/app/particlebook/android/ui/ZlibDownloadManager.kt")

        assertTrue("删书监听改走墓碑入口", source.contains("LibraryRemovalBus.listener = ::onLibraryBooksRemoved"))
        assertTrue("墓碑要从 SharedPreferences 恢复", source.contains("SharedPreferencesZlibTombstonePersistence"))
        assertTrue("删书先写墓碑", source.contains("tombstones.addAll(ids)"))
        assertTrue("删书要取消在途 run", source.contains("cancelActiveDownloadsForRemovedBooks") && source.contains("runs.cancel(task.id, ZlibCancelReason.USER)"))
        assertTrue("历史未加载完要入队并重试 drain", source.contains("drainRemovedWhileHistoryLoading()"))
        assertTrue("历史加载完先按墓碑清残留", source.contains("purgeTombstonedHistory()"))
        assertTrue("失败收尾墓碑早退", source.contains("zlib_download_dropped_tombstoned"))
        assertTrue("完成回调墓碑早退", source.contains("zlib_download_drop_complete_tombstoned"))
        assertTrue("导入失败墓碑早退", source.contains("zlib_download_drop_import_failed_tombstoned"))
        assertTrue("补记录墓碑早退", source.contains("zlib_download_drop_library_record_tombstoned"))
        assertTrue("addHistory 也要兜底拒写", source.contains("zlib_download_drop_history_tombstoned"))
        assertTrue("adopt 命中墓碑要静默回收", source.contains("store.removeBooksSilently(setOf(book.id))"))
        assertEquals(
            "5 条 adopt 成功路径都要先 reclaim",
            5,
            Regex("if \\(reclaimTombstonedAdoption\\(").findAll(source).count(),
        )
        assertTrue("显式重下清墓碑", source.contains("tombstones.remove(detail.bookId)"))
        assertTrue("显式重试清墓碑", source.contains("tombstones.remove(current.bookId)"))
        assertTrue("显式续传清墓碑", source.contains("tombstones.remove(bookId)"))
        assertTrue("显式重新导入清墓碑", source.contains("tombstones.remove(task.bookId)"))
        assertTrue("pending 快照原子读", source.contains("withPendingRecords") && source.contains("zlibRecordsForDisk(_history.value, pending.values.toList())"))
        assertTrue("保留 task-73 重新导入/完整性接线", source.contains("zlibReimportDecision") && source.contains("zlibExistingFileValidator"))
    }

    // ---------------------------------------------------------------- helpers

    private fun book(id: Long, tags: List<String>): Book = Book(
        id = id,
        title = "书" + id,
        author = null,
        format = BookFormat.Epub,
        filePath = File(System.getProperty("java.io.tmpdir"), "pb-book-" + id + ".epub").absolutePath,
        fileSize = 1024L,
        tags = tags,
    )

    private fun task(bookId: String, status: ZlibDownloadStatus): ZlibDownloadTask = ZlibDownloadTask(
        id = "zlib:" + bookId,
        bookId = bookId,
        title = "书" + bookId,
        author = null,
        format = "epub",
        downloadPath = "/d/" + bookId + ".epub",
        fileName = bookId + ".epub",
        destPath = "/tmp/" + bookId + ".epub",
        status = status,
    )

    private class FakePersistence(initial: Set<String>) : ZlibTombstonePersistence {
        var stored: Set<String> = initial.toSet()

        override fun read(): Set<String> = stored

        override fun write(ids: Set<String>) {
            stored = ids.toSet()
        }
    }

    /** 与 DownloadFolderOpenTest 相同的源码定位：从测试工作目录向上找 android/app 根。 */
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
