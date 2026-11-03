package app.particlebook.android.ui

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U10：进程重建/崩溃后的下载恢复（纯函数，不依赖 Context/Robolectric）。
 *
 * 覆盖两件事：
 * 1) 启动读回的非终态记录必须改写成可重试的 FAILED(INTERRUPTED)，终态记录原样保留（幂等）；
 * 2) 启动清理 cacheDir/zlib-downloads 的半成品（.part / .part.meta），不误删其他文件。
 */
class ZlibDownloadRecoveryTest {

    private fun record(
        bookId: String,
        status: ZlibDownloadStatus,
        destPath: String = "/sdcard/" + bookId + ".epub",
        saf: Boolean = false,
        errorKind: String? = null,
    ) = ZlibDownloadTask(
        id = "zlib:" + bookId,
        bookId = bookId,
        title = "book-" + bookId,
        author = null,
        format = "epub",
        downloadPath = "/d/" + bookId,
        fileName = bookId + ".epub",
        destPath = destPath,
        saf = saf,
        received = 1024L,
        total = 4096L,
        status = status,
        errorKind = errorKind,
        finishedAt = 0L,
    )

    @Test
    fun activeRecordsBecomeRetryableInterruptedFailures() {
        val now = 1_700_000_000_000L
        val loaded = listOf(
            record("preparing", ZlibDownloadStatus.PREPARING),
            record("downloading", ZlibDownloadStatus.DOWNLOADING, saf = true, destPath = "content://doc/1"),
        )

        val recovered = zlibRecoverInterrupted(loaded, now)

        assertEquals(2, recovered.size)
        recovered.forEach { task ->
            assertEquals("必须落为 FAILED，UI 才会给「重试」: " + task.bookId, ZlibDownloadStatus.FAILED, task.status)
            assertEquals(ZlibDownloadManager.ZLIB_INTERRUPTED_KIND, task.errorKind)
            assertEquals(ZlibDownloadManager.ZLIB_INTERRUPTED_MESSAGE, task.errorMessage)
            assertEquals("中断时间写实: " + task.bookId, now, task.finishedAt)
            assertFalse("不能再被当成进行中: " + task.bookId, task.isActive)
            assertFalse(task.importing)
            assertFalse(task.cancelling)
        }
        // SAF 半截文档 uri 必须保留在记录里（重试复用/删除都靠它，不能变成孤儿）。
        assertEquals("content://doc/1", recovered[1].destPath)
    }

    @Test
    fun terminalRecordsAreLeftUntouched() {
        val now = 1_700_000_000_000L
        val completed = record("done", ZlibDownloadStatus.COMPLETED, errorKind = null)
        val failed = record("failed", ZlibDownloadStatus.FAILED, errorKind = "NETWORK")
        val cancelled = record("cancelled", ZlibDownloadStatus.CANCELLED)

        val loaded = listOf(completed, failed, cancelled)
        val recovered = zlibRecoverInterrupted(loaded, now)

        assertEquals("终态不能改写", loaded, recovered)
        assertEquals("NETWORK", recovered[1].errorKind)
        assertNull(recovered[0].errorKind)
        assertEquals(cancelled, recovered[2])
    }

    @Test
    fun recoveryIsIdempotent() {
        val loaded = listOf(record("a", ZlibDownloadStatus.DOWNLOADING))
        val once = zlibRecoverInterrupted(loaded, 1L)
        val twice = zlibRecoverInterrupted(once, 2L)
        assertEquals("已恢复的记录再恢复一次必须不变（避免每次启动刷新 finishedAt）", once, twice)
        assertEquals(1L, once[0].finishedAt)
    }

    @Test
    fun tempDownloadDirCleanupRemovesPartialsButNotOtherDirs() {
        val root = Files.createTempDirectory("pb-ztemp").toFile()
        try {
            val cacheDir = File(root, "zlib-downloads").apply { mkdirs() }
            File(cacheDir, "zlib_123.part").writeText("half")
            File(cacheDir, "zlib_123.part.meta").writeText("meta")
            File(cacheDir, "zlib_456.part").writeText("half")
            val unrelated = File(root, "keep.txt").apply { writeText("keep") }

            val removed = zlibCleanTempDownloads(cacheDir)

            assertEquals("三个半成品都要删", 3, removed)
            assertFalse("临时目录应清空并删除", cacheDir.exists())
            assertTrue("不能误删 cacheDir 之外的文件", unrelated.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingTempDirCleanupIsNoOp() {
        val root = Files.createTempDirectory("pb-ztemp-missing").toFile()
        try {
            assertEquals(0, zlibCleanTempDownloads(File(root, "not-there")))
        } finally {
            root.deleteRecursively()
        }
    }
}
