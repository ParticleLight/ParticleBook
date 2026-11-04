package app.particlebook.android.ui

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T23：下载取消/历史持久化收口。
 *
 * 覆盖：字节已完/导入中取消无效、CANCELLED 记录字段（保留 received/total 供续传）、
 * 续传来源判定、续传任务重置、cacheDir 半成品按 CANCELLED 前缀保留、CANCELLED 不被启动恢复改写。
 */
class ZlibDownloadCancelHistoryTest {

    private fun task(
        status: ZlibDownloadStatus = ZlibDownloadStatus.DOWNLOADING,
        received: Long = 512L,
        total: Long = 2048L,
        importing: Boolean = false,
        cancelling: Boolean = false,
        saf: Boolean = false,
        destPath: String = "/storage/emulated/0/Z-Library/book.pdf",
    ) = ZlibDownloadTask(
        id = "zlib:b1",
        bookId = "b1",
        title = "测试书",
        author = "作者",
        format = "pdf",
        downloadPath = "/dl/b1",
        fileName = "测试书.pdf",
        destPath = destPath,
        saf = saf,
        received = received,
        total = total,
        status = status,
        importing = importing,
        cancelling = cancelling,
    )

    @Test
    fun cancelIsIgnoredWhileImporting() {
        assertEquals(
            ZlibCancelDisposition.IGNORE_IMPORTING,
            zlibCancelDisposition(received = 0L, total = 2048L, importing = true),
        )
    }

    @Test
    fun cancelIsIgnoredWhenAllBytesReceived() {
        // total 已知且已收满：网络阶段事实结束，取消无效（不产生半成品、也不会误删已完成文件）。
        assertEquals(
            ZlibCancelDisposition.IGNORE_COMPLETE,
            zlibCancelDisposition(received = 2048L, total = 2048L, importing = false),
        )
        assertEquals(
            ZlibCancelDisposition.IGNORE_COMPLETE,
            zlibCancelDisposition(received = 4096L, total = 2048L, importing = false),
        )
    }

    @Test
    fun cancelWorksBeforeCompleteAndWhenLengthUnknown() {
        assertEquals(
            ZlibCancelDisposition.CANCEL,
            zlibCancelDisposition(received = 512L, total = 2048L, importing = false),
        )
        // 服务器没给 Content-Length 时无法判定是否已收满，尊重用户取消（.part 保留可续传）。
        assertEquals(
            ZlibCancelDisposition.CANCEL,
            zlibCancelDisposition(received = 512L, total = -1L, importing = false),
        )
    }

    @Test
    fun cancelledHistoryRecordKeepsProgressAndClearsError() {
        val running = task(received = 512L, total = 2048L).copy(
            errorKind = "NETWORK",
            errorMessage = "旧错误",
            cancelling = true,
        )
        val cancelled = zlibCancelledHistoryRecord(running, finishedAt = 1234L)

        assertEquals(ZlibDownloadStatus.CANCELLED, cancelled.status)
        assertFalse(cancelled.isActive)
        assertEquals(512L, cancelled.received)
        // 关键：不能像 FAILED 历史那样把 total 改写成实际字节数，否则取消记录会显示成 100%。
        assertEquals(2048L, cancelled.total)
        assertEquals("/storage/emulated/0/Z-Library/book.pdf", cancelled.destPath)
        assertEquals("测试书.pdf", cancelled.fileName)
        assertNull(cancelled.errorKind)
        assertNull(cancelled.errorMessage)
        assertFalse(cancelled.importing)
        assertFalse(cancelled.cancelling)
        assertEquals(1234L, cancelled.finishedAt)
    }

    @Test
    fun resumeSourceDependsOnRemainingPartialBytes() {
        val cancelled = zlibCancelledHistoryRecord(task(), finishedAt = 1L)
        assertEquals(ZlibResumeSource.PARTIAL, zlibResumeSourceFor(cancelled, partialBytes = 512L))
        assertEquals(ZlibResumeSource.RESTART, zlibResumeSourceFor(cancelled, partialBytes = 0L))
        // 非取消记录不提供续传入口。
        val failed = task(status = ZlibDownloadStatus.FAILED)
        assertEquals(ZlibResumeSource.UNAVAILABLE, zlibResumeSourceFor(failed, partialBytes = 512L))
        val completed = task(status = ZlibDownloadStatus.COMPLETED)
        assertEquals(ZlibResumeSource.UNAVAILABLE, zlibResumeSourceFor(completed, partialBytes = 512L))
    }

    @Test
    fun resumeTaskResetsProgressButKeepsTargetForPartReuse() {
        val cancelled = zlibCancelledHistoryRecord(task(received = 512L, total = 2048L), 1234L)
        val resumed = zlibResumeTaskFromRecord(cancelled)

        assertEquals(ZlibDownloadStatus.PREPARING, resumed.status)
        assertTrue(resumed.isActive)
        assertEquals(0L, resumed.received)
        assertEquals(-1L, resumed.total)
        assertEquals(0L, resumed.finishedAt)
        assertNull(resumed.errorKind)
        assertFalse(resumed.importing)
        assertFalse(resumed.cancelling)
        // 目标路径/文件名必须原样保留：客户端靠同名 .part 续传。
        assertEquals(cancelled.destPath, resumed.destPath)
        assertEquals(cancelled.fileName, resumed.fileName)
        assertEquals(cancelled.downloadPath, resumed.downloadPath)
        assertEquals(cancelled.bookId, resumed.bookId)
    }

    @Test
    fun cleanTempKeepsPartsOfCancelledSafRecords() {
        val dir = Files.createTempDirectory("zlib-temp-keep").toFile()
        try {
            val keepPrefix = "zlib_b1.part"
            listOf(
                "zlib_b1.part",
                "zlib_b1.part.part",
                "zlib_b1.part.part.meta",
                "zlib_b1.part.meta",
                "zlib_b2.part.part",
            ).forEach { name -> File(dir, name).writeText("x") }

            val removed = zlibCleanTempDownloads(dir, setOf(keepPrefix))

            assertEquals(1, removed)
            assertTrue(dir.isDirectory)
            assertTrue(File(dir, "zlib_b1.part").isFile)
            assertTrue(File(dir, "zlib_b1.part.part").isFile)
            assertTrue(File(dir, "zlib_b1.part.part.meta").isFile)
            assertTrue(File(dir, "zlib_b1.part.meta").isFile)
            assertFalse(File(dir, "zlib_b2.part.part").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun cleanTempWithoutKeepRemovesEverythingAndDirectory() {
        val dir = Files.createTempDirectory("zlib-temp-all").toFile()
        try {
            File(dir, "zlib_b1.part.part").writeText("x")
            File(dir, "zlib_b2.part.part.meta").writeText("x")

            val removed = zlibCleanTempDownloads(dir, emptySet())

            assertEquals(2, removed)
            assertFalse(dir.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun cleanTempMissingDirectoryIsNoOp() {
        val dir = File(Files.createTempDirectory("zlib-temp-missing").toFile(), "absent")
        try {
            assertEquals(0, zlibCleanTempDownloads(dir, setOf("zlib_b1.part")))
        } finally {
            dir.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun recordOnlyDeleteCleansPartialUnlessCompleted() {
        // 完整文件要保留（删除记录不删文件），只有未完成记录的半成品是不可用数据。
        assertFalse(zlibShouldCleanPartialOnRecordOnly(ZlibDownloadStatus.COMPLETED))
        assertTrue(zlibShouldCleanPartialOnRecordOnly(ZlibDownloadStatus.CANCELLED))
        assertTrue(zlibShouldCleanPartialOnRecordOnly(ZlibDownloadStatus.FAILED))
        assertTrue(zlibShouldCleanPartialOnRecordOnly(ZlibDownloadStatus.DOWNLOADING))
    }

    @Test
    fun cancelledRecordIsNotRewrittenByStartupRecovery() {
        val cancelled = zlibCancelledHistoryRecord(task(received = 512L, total = 2048L), 1234L)
        val recovered = zlibRecoverInterrupted(listOf(cancelled), now = 9999L)

        assertEquals(1, recovered.size)
        assertEquals(ZlibDownloadStatus.CANCELLED, recovered[0].status)
        assertEquals(1234L, recovered[0].finishedAt)
        assertFalse(recovered[0].isActive)
    }

    @Test
    fun interruptedFailedRecordIsResumableFromHistory() {
        // D3：进程被杀后恢复出来的 FAILED(INTERRUPTED) 记录也保留 .part，记录页要给「重试」。
        val interrupted = task(status = ZlibDownloadStatus.FAILED).copy(
            errorKind = ZlibDownloadManager.ZLIB_INTERRUPTED_KIND,
        )
        assertTrue(zlibIsResumableHistoryRecord(interrupted))
        assertEquals(ZlibResumeSource.PARTIAL, zlibResumeSourceFor(interrupted, partialBytes = 512L))
        assertEquals(ZlibResumeSource.RESTART, zlibResumeSourceFor(interrupted, partialBytes = 0L))

        // 普通网络失败仍走详情/卡片重试，不从记录页开续传口子。
        val networkFailed = task(status = ZlibDownloadStatus.FAILED).copy(errorKind = "NETWORK")
        assertFalse(zlibIsResumableHistoryRecord(networkFailed))
        assertEquals(
            ZlibResumeSource.UNAVAILABLE,
            zlibResumeSourceFor(networkFailed, partialBytes = 512L),
        )

        assertTrue(zlibIsResumableHistoryRecord(zlibCancelledHistoryRecord(task(), 1L)))
        assertFalse(zlibIsResumableHistoryRecord(task(status = ZlibDownloadStatus.COMPLETED)))
    }
}
