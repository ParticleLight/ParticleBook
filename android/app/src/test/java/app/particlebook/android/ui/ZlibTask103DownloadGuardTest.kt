package app.particlebook.android.ui

import app.particlebook.android.ui.data.Book
import app.particlebook.android.ui.data.BookFormat
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * task-103：6c34d5d 复核的两个 P1（download-ux 写作用域）。
 *
 * - P1-1 记录页整行点击绕过书架副本完整性校验，会打开坏书；
 * - P1-2 详情页把 provider 临时读失败（UNKNOWN）当损坏，直接 force 重下并先删唯一记录；
 * - P2-1 force 与冷启历史异步加载竞态（removeHistoryRecord early-return 后旧记录复活）；
 * - P2-2 SAF 重新导入仍拿旧 total 当 expectedBytes；
 * - P2-5 UNKNOWN 文案与「重试检查」入口。
 */
class ZlibTask103DownloadGuardTest {

    @Test
    fun recordRowOpensOnlyWhenShelfCopyIsComplete() {
        val book = book()
        assertTrue(
            "记录完成 + 已导入 + 书架副本完整 → 整行可点",
            zlibRecordRowOpenable(true, book, true),
        )
        assertFalse(
            "书架副本损坏时不能点行打开坏书",
            zlibRecordRowOpenable(true, book, false),
        )
        assertFalse(
            "副本完整性还在检查（null）时先不可点",
            zlibRecordRowOpenable(true, book, null),
        )
        assertFalse("没有书架条目不可点", zlibRecordRowOpenable(true, null, true))
        assertFalse("记录未完成不可点", zlibRecordRowOpenable(false, book, true))
    }

    @Test
    fun unknownTargetRechecksInsteadOfForcingRedownload() {
        val book = book()
        assertEquals(
            ZlibDetailCompletedAction.RECHECK,
            zlibDetailCompletedAction(book, bookFileExists = false, ZlibDownloadTargetState.UNKNOWN),
        )
        assertEquals(
            ZlibDetailCompletedAction.RECHECK,
            zlibDetailCompletedAction(null, bookFileExists = false, ZlibDownloadTargetState.UNKNOWN),
        )
        assertEquals(
            "明确缺失 → 重新下载",
            ZlibDetailCompletedAction.REDOWNLOAD,
            zlibDetailCompletedAction(null, bookFileExists = false, ZlibDownloadTargetState.MISSING),
        )
        assertEquals(
            "明确不完整 → 重新下载",
            ZlibDetailCompletedAction.REDOWNLOAD,
            zlibDetailCompletedAction(null, bookFileExists = false, ZlibDownloadTargetState.INCOMPLETE),
        )
    }

    @Test
    fun unknownTargetLabelIsNotCorrupt() {
        assertEquals("暂时无法确认", zlibImportStateLabel(ZlibDownloadTargetState.UNKNOWN))
        assertNotEquals("文件损坏", zlibImportStateLabel(ZlibDownloadTargetState.UNKNOWN))
    }

    @Test
    fun removedBeforeHistoryLoadIsFilteredFromLoadedRecords() {
        val a = task("book-a")
        val b = task("book-b")
        val loaded = listOf(a, b)
        assertEquals(
            "冷启期间删掉的记录不能从磁盘复活",
            listOf(a),
            zlibFilterRemovedHistory(loaded, setOf("book-b")),
        )
        assertEquals(
            "没有待删 bookId 时保持原列表",
            loaded,
            zlibFilterRemovedHistory(loaded, emptySet()),
        )
    }

    @Test
    fun recordPageClickGuardAndRetryEntryAreWired() {
        val source = sourceFile("src/main/java/app/particlebook/android/ui/ZlibDownloadsScreen.kt")
        assertTrue(
            "行 clickable 与 onOpen 共用完整性判据",
            source.contains("zlibRecordRowOpenable("),
        )
        assertTrue(
            "旧的无完整性判断已删除",
            !source.contains("val clickable = recordCompleted && imported != null"),
        )
        assertTrue("UNKNOWN 要有重试检查按钮", source.contains("\"重试检查\""))
        assertTrue(
            "UNKNOWN 分支单独处理，不能落到「文件损坏」",
            source.contains("targetCheck.state == ZlibDownloadTargetState.UNKNOWN"),
        )
        assertTrue("导入中异常/取消要复位", source.contains("finally {"))
    }

    @Test
    fun detailUnknownOffersRetryCheckWithoutForceRedownload() {
        val source = sourceFile("src/main/java/app/particlebook/android/ui/ZlibDetailScreen.kt")
        assertTrue("UNKNOWN 要有 RECHECK 动作", source.contains("RECHECK"))
        assertTrue("UNKNOWN 文案不能再说损坏", source.contains("暂时无法确认下载文件状态"))
        assertTrue("要能重跑检查", source.contains("completedCheckRetry++"))
        assertTrue(
            "只有 REDOWNLOAD 才 force",
            Regex("forceRedownload\\s*=\\s*action == ZlibDetailCompletedAction\\.REDOWNLOAD")
                .containsMatchIn(source),
        )
    }

    @Test
    fun managerDefersHistoryRemovalUntilLoaded() {
        val source = sourceFile("src/main/java/app/particlebook/android/ui/ZlibDownloadManager.kt")
        val removeStart = source.indexOf("private fun removeHistoryRecord")
        val removeEnd = source.indexOf("private fun takeRemovedBeforeHistoryLoad", removeStart)
        assertTrue("要能定位 removeHistoryRecord", removeStart >= 0 && removeEnd > removeStart)
        val body = source.substring(removeStart, removeEnd)
        assertTrue("读盘完成前先排队", body.contains("removedBeforeHistoryLoad.add(bookId)"))
        assertTrue("排队必须发生在 early-return 之前", body.contains("!_historyLoaded.value"))
        assertTrue("init 要过滤历史", source.contains("zlibFilterRemovedHistory"))
        assertTrue("init 要清理延迟删除队列", source.contains("takeRemovedBeforeHistoryLoad()"))
        assertTrue("force 日志仍在", source.contains("zlib_download_force_redownload"))
    }

    @Test
    fun safReimportUsesActualLengthNotStaleRecordedTotal() {
        val source = sourceFile("src/main/java/app/particlebook/android/ui/ZlibDownloadManager.kt")
        val start = source.indexOf("private suspend fun tryImportCompleteSafTarget")
        val end = source.indexOf("private suspend fun", start + 1)
        assertTrue("要能定位 tryImportCompleteSafTarget", start >= 0 && end > start)
        val body = source.substring(start, end)
        assertTrue(
            "SAF 预导入走 inspectImportTarget（实际长度）",
            body.contains("val check = inspectImportTarget(context, task)"),
        )
        assertFalse(
            "不能再用记录里的旧 total 做默认 expected",
            body.contains("inspectSafDownloadTarget(context, task)"),
        )
        assertTrue(
            "SAF 检查默认不做长度断言",
            source.contains("expectedBytes: Long = -1L"),
        )
        assertTrue(
            "长度元数据不一致时降级结构校验",
            source.contains("zlibInspectBookFile(temp, -1L)"),
        )
    }

    private fun book(): Book = Book(
        id = 103L,
        title = "task103",
        author = "作者",
        format = BookFormat.Pdf,
        filePath = File(System.getProperty("java.io.tmpdir"), "pb-task103.pdf").absolutePath,
        fileSize = 2048L,
        tags = listOf("zlib:103"),
    )

    private fun task(bookId: String): ZlibDownloadTask = ZlibDownloadTask(
        id = "zlib:" + bookId,
        bookId = bookId,
        title = "title-" + bookId,
        author = null,
        format = "pdf",
        downloadPath = "/dl/" + bookId,
        fileName = bookId + ".pdf",
        destPath = "/tmp/" + bookId + ".pdf",
    )

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
        return requireNotNull(candidates.firstOrNull { it.isFile }) {
            "找不到 " + relative + "（工作目录=" + System.getProperty("user.dir") + "）"
        }.readText()
    }
}
