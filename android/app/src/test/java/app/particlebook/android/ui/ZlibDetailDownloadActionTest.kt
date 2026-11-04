package app.particlebook.android.ui

import app.particlebook.android.ui.data.Book
import app.particlebook.android.ui.data.BookFormat
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * task-55 / task-73：详情页完成态的状态映射与接线契约。
 *
 * 真机回归的核心场景：
 * - 下载记录是 COMPLETED，但用户删过书架条目（或旧版本没打 zlib 标签）→ 不能显示不可点的「已下载」；
 * - 书架条目还在但文件被外部删除/截断 → 不能 OPEN，必须给「重新导入/重新下载」；
 * - 目标文件还在 → 只重新导入（不耗下载额度）；文件被外部删除 → 才允许重新下载；
 * - Manager 发现书架已有同一本时，详情页要能提示「已在书架」并给出打开入口。
 */
class ZlibDetailDownloadActionTest {

    @Test
    fun shelfBookWithCompleteFileOpens() {
        val book = book()
        assertEquals(
            ZlibDetailCompletedAction.OPEN,
            zlibDetailCompletedAction(
                completedBook = book,
                bookFileExists = true,
                targetState = ZlibDownloadTargetState.COMPLETE,
            ),
        )
        assertEquals(
            "书架副本能打开时，下载目标是否还在不影响打开",
            ZlibDetailCompletedAction.OPEN,
            zlibDetailCompletedAction(
                completedBook = book,
                bookFileExists = true,
                targetState = ZlibDownloadTargetState.MISSING,
            ),
        )
    }

    @Test
    fun shelfBookWithMissingFileUsesDownloadTarget() {
        val book = book()
        assertEquals(
            "书架条目指向的文件已丢失，但下载目标还在 → 重新导入修复路径",
            ZlibDetailCompletedAction.REIMPORT,
            zlibDetailCompletedAction(
                completedBook = book,
                bookFileExists = false,
                targetState = ZlibDownloadTargetState.COMPLETE,
            ),
        )
        assertEquals(
            "书架条目文件丢失且下载目标也没了 → 只能重新下载",
            ZlibDetailCompletedAction.REDOWNLOAD,
            zlibDetailCompletedAction(
                completedBook = book,
                bookFileExists = false,
                targetState = ZlibDownloadTargetState.MISSING,
            ),
        )
    }

    @Test
    fun missingShelfEntryWithFileOffersReimport() {
        assertEquals(
            ZlibDetailCompletedAction.REIMPORT,
            zlibDetailCompletedAction(
                completedBook = null,
                bookFileExists = false,
                targetState = ZlibDownloadTargetState.COMPLETE,
            ),
        )
    }

    @Test
    fun incompleteTargetOffersRedownloadDirectly() {
        val book = book()
        assertEquals(
            "目标确认不完整时不能先给重新导入再失败，必须直接重新下载",
            ZlibDetailCompletedAction.REDOWNLOAD,
            zlibDetailCompletedAction(
                completedBook = book,
                bookFileExists = false,
                targetState = ZlibDownloadTargetState.INCOMPLETE,
            ),
        )
        assertEquals(
            "没有书架条目且目标损坏 → 直接重新下载",
            ZlibDetailCompletedAction.REDOWNLOAD,
            zlibDetailCompletedAction(
                completedBook = null,
                bookFileExists = false,
                targetState = ZlibDownloadTargetState.INCOMPLETE,
            ),
        )
    }

    @Test
    fun unknownTargetOffersRecheckInsteadOfRedownload() {
        val book = book()
        // task-103（P1-2）：provider 临时读失败不等于文件损坏；不能自动 force 重下
        //（先删历史会丢唯一 COMPLETED 记录，还可能白耗下载额度）。
        assertEquals(
            ZlibDetailCompletedAction.RECHECK,
            zlibDetailCompletedAction(
                completedBook = book,
                bookFileExists = false,
                targetState = ZlibDownloadTargetState.UNKNOWN,
            ),
        )
        assertEquals(
            ZlibDetailCompletedAction.RECHECK,
            zlibDetailCompletedAction(
                completedBook = null,
                bookFileExists = false,
                targetState = ZlibDownloadTargetState.UNKNOWN,
            ),
        )
    }

    @Test
    fun missingShelfEntryAndFileOffersRedownload() {
        assertEquals(
            ZlibDetailCompletedAction.REDOWNLOAD,
            zlibDetailCompletedAction(
                completedBook = null,
                bookFileExists = false,
                targetState = ZlibDownloadTargetState.MISSING,
            ),
        )
    }

    @Test
    fun detailScreenWiresAlreadyInLibraryAndReimport() {
        val source = sourceFile("src/main/java/app/particlebook/android/ui/ZlibDetailScreen.kt")
        assertTrue("Manager 回调要接进详情页", source.contains("onAlreadyInLibrary"))
        assertTrue("要提示已在书架", source.contains("\"已在书架\""))
        assertTrue("提示动作要能打开书", source.contains("actionLabel = \"打开\""))
        assertTrue("完成但书架无条目要给重新导入", source.contains("\"重新导入\""))
        assertTrue("文件已丢要能重新下载", source.contains("\"重新下载\""))
        assertTrue(
            "所有普通 start 入口都要带回调",
            source.contains("ZlibDownloadManager.start(detail, store, onAlreadyInLibrary)"),
        )
        assertTrue(
            "重新下载必须跳过旧 COMPLETED 记录的重导入早退",
            source.contains("forceRedownload ="),
        )
        // task-55 明确要求保留在线读按钮区的可见性参数，防止重构误删。
        assertTrue("在线读按钮区保持 layerVisible", source.contains("layerVisible = layerVisible"))
    }

    @Test
    fun managerReimportsCompletedRecordWhenShelfEntryMissing() {
        val source = sourceFile("src/main/java/app/particlebook/android/ui/ZlibDownloadManager.kt")
        assertTrue(
            "COMPLETED 但书架无条目要重新导入而不是直接 return",
            source.contains("zlib_download_completed_reimport"),
        )
        assertTrue("重新导入走 reimportTask", source.contains("reimportTask("))
        assertTrue(
            "COMPLETED 目标的重新导入必须先做完整性检查",
            source.contains("zlibReimportDecision(target.state)"),
        )
        assertTrue(
            "重新导入失败要落到 IMPORT_FAILED 并提示重新下载",
            source.contains("zlib_download_reimport_rejected"),
        )
        assertTrue("重新导入有强制重下出口", source.contains("forceRedownload"))
    }

    private fun book(): Book = Book(
        id = 7L,
        title = "书架标题",
        author = "作者",
        format = BookFormat.Pdf,
        filePath = File(System.getProperty("java.io.tmpdir"), "pb-task55.pdf").absolutePath,
        fileSize = 1024L,
        tags = listOf("zlib:42"),
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
