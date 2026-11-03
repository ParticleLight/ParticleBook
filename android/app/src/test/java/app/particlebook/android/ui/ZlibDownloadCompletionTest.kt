package app.particlebook.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** U4：下载完成不等于导入完成——只有进书架成功才 COMPLETED。 */
class ZlibDownloadCompletionTest {

    private fun task() = ZlibDownloadTask(
        id = "zlib:b1",
        bookId = "b1",
        title = "T",
        author = null,
        format = "epub",
        downloadPath = "/d",
        fileName = "T.epub",
        destPath = "/sdcard/T.epub",
        status = ZlibDownloadStatus.DOWNLOADING,
        received = 512L,
        total = 1024L,
    )

    @Test
    fun importingKeepsTaskActiveUntilAdopted() {
        val importing = ZlibDownloadCompletion.importing(task(), size = 1024L)
        assertTrue("导入中仍是进行中（卡片显示进度条外态）", importing.isActive)
        assertTrue(importing.importing)
        assertEquals(ZlibDownloadStatus.DOWNLOADING, importing.status)
        assertEquals(1024L, importing.received)
        assertEquals(1024L, importing.total)
    }

    @Test
    fun importedMarksCompletedOnlyAfterSuccess() {
        val imported = ZlibDownloadCompletion.imported(task(), size = 1024L)
        assertEquals(ZlibDownloadStatus.COMPLETED, imported.status)
        assertFalse(imported.importing)
        assertTrue(imported.finishedAt > 0L)
        assertFalse(imported.isActive)
    }

    @Test
    fun importFailureIsRetryableWithoutRedownload() {
        val failed = ZlibDownloadCompletion.importFailed(task(), IllegalStateException("disk full"))
        assertEquals(ZlibDownloadStatus.FAILED, failed.status)
        assertEquals(ZLIB_IMPORT_FAILED_KIND, failed.errorKind)
        assertTrue(failed.errorMessage!!.contains("重新导入"))
        assertEquals("已收字节保留，便于重新导入", 512L, failed.received)
        assertFalse(failed.importing)
    }

    @Test
    fun loginFailuresUseLoginAction() {
        assertTrue(zlibFailureNeedsLogin("LOGIN_REQUIRED"))
        assertTrue(zlibFailureNeedsLogin("DAILY_LIMIT"))
        assertFalse(zlibFailureNeedsLogin("NETWORK"))
        assertFalse(zlibFailureNeedsLogin(null))
    }
}
