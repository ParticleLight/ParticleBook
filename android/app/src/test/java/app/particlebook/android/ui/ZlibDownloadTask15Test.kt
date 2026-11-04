package app.particlebook.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T15：下载状态机复核修复的回归测试（纯 JVM，不依赖 Context/Robolectric）。
 *
 * 覆盖：
 * - P0：导入成功写历史的记录必须是 COMPLETED（不能是导入前 DOWNLOADING 快照）；
 * - P1：看门狗只能取消自己那一代 run，不能误杀 retry 后的新下载；
 * - P1：removeHistory 记录不存在时回调 NOT_FOUND（记录页真实提示）。
 */
class ZlibDownloadTask15Test {

    private fun task(
        status: ZlibDownloadStatus = ZlibDownloadStatus.DOWNLOADING,
        importing: Boolean = false,
        received: Long = 512L,
        total: Long = -1L,
        finishedAt: Long = 0L,
    ) = ZlibDownloadTask(
        id = "zlib:b1",
        bookId = "b1",
        title = "T",
        author = null,
        format = "epub",
        downloadPath = "/d",
        fileName = "T.epub",
        destPath = "/sdcard/T.epub",
        status = status,
        received = received,
        total = total,
        importing = importing,
        finishedAt = finishedAt,
    )

    /** P0：历史记录必须来自 COMPLETED 副本，不能是「导入前快照」（DOWNLOADING/finishedAt=0）。 */
    @Test
    fun completedHistoryRecordIsCompletedNotImportingSnapshot() {
        val importingSnapshot = task(
            status = ZlibDownloadStatus.DOWNLOADING,
            importing = true,
            received = 2048L,
            total = 2048L,
        )
        val record = zlibCompletedHistoryRecord(importingSnapshot, size = 2048L)
        assertEquals(
            "历史记录必须是 COMPLETED，否则记录页显示未完成、详情页重复下载",
            ZlibDownloadStatus.COMPLETED,
            record.status,
        )
        assertTrue("finishedAt 必须写实，否则记录页显示未知时间", record.finishedAt > 0L)
        assertFalse(record.importing)
        assertFalse("COMPLETED 不能再算进行中", record.isActive)
        assertEquals(2048L, record.received)
    }

    /** P1：旧世代看门狗只能取消自己那一代，不能误杀 retry 后的新 run。 */
    @Test
    fun watchdogCannotCancelReplacedRun() {
        val registry = ZlibDownloadRunRegistry()
        val oldRun = registry.begin("zlib:b1")
        val newRun = registry.begin("zlib:b1")
        assertFalse("旧世代不是当前 run，cancelIfCurrent 必须 no-op", registry.cancelIfCurrent(oldRun, ZlibCancelReason.STALL))
        assertFalse("旧 run 的 flag 不能被置位", oldRun.cancelFlag.get())
        assertNull(oldRun.cancelReason)
        assertTrue("当前 run 仍应可被判定停滞", registry.cancelIfCurrent(newRun, ZlibCancelReason.STALL))
        assertTrue(newRun.cancelFlag.get())
        assertEquals(ZlibCancelReason.STALL, newRun.cancelReason)
    }

    /** P1：release 之后旧 run 不再是当前世代，迟到的看门狗 tick 为 no-op。 */
    @Test
    fun releasedRunIsNoLongerCurrent() {
        val registry = ZlibDownloadRunRegistry()
        val run = registry.begin("t")
        registry.release(run)
        assertFalse(registry.cancelIfCurrent(run, ZlibCancelReason.STALL))
        assertFalse("已释放的 run 不能被置取消位", run.cancelFlag.get())
    }

    /** UI 的用户取消路径保持原语义：取消 taskId 当前世代，不碰旧世代。 */
    @Test
    fun userCancelTargetsCurrentRunOnly() {
        val registry = ZlibDownloadRunRegistry()
        val stale = registry.begin("t")
        val current = registry.begin("t")
        assertTrue(registry.cancel("t", ZlibCancelReason.USER))
        assertTrue(current.cancelFlag.get())
        assertEquals(ZlibCancelReason.USER, current.cancelReason)
        assertFalse(stale.cancelFlag.get())
    }

    /** D8/T15：记录不存在时同步回调 NOT_FOUND，且返回 false。 */
    @Test
    fun removeUnknownHistoryReportsNotFound() {
        var result: ZlibHistoryDeleteResult? = null
        val removed = ZlibDownloadManager.removeHistory("no-such-book", deleteFile = true) { result = it }
        assertFalse(removed)
        assertEquals(ZlibHistoryDeleteResult.NOT_FOUND, result)
    }
}
