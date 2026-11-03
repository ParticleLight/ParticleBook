package app.particlebook.android.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T4 详情页/记录页依赖的下载管理契约（D8 / U3 / U9）：
 * UI 分支只看这几条谓词，逻辑错就会把「已取消」当进行中、或未登录时给出必然失败的重试。
 *
 * 不依赖 Context/Robolectric：只调纯谓词与「记录不存在」的删除守卫。
 */
class ZlibDownloadUiContractTest {

    private fun task(
        bookId: String = "b1",
        status: ZlibDownloadStatus = ZlibDownloadStatus.PREPARING,
        errorKind: String? = null,
    ) = ZlibDownloadTask(
        id = "zlib:" + bookId,
        bookId = bookId,
        title = "book-" + bookId,
        author = null,
        format = "epub",
        downloadPath = "/d/" + bookId,
        fileName = bookId + ".epub",
        destPath = "/sdcard/" + bookId + ".epub",
        status = status,
        errorKind = errorKind,
    )

    /** U3：详情页按 isActive 判断「下载中/取消中」；已取消与已完成都不算进行中。 */
    @Test
    fun onlyRunningStatusesAreActive() {
        assertTrue(task(status = ZlibDownloadStatus.PREPARING).isActive)
        assertTrue(task(status = ZlibDownloadStatus.DOWNLOADING).isActive)
        assertFalse(task(status = ZlibDownloadStatus.FAILED).isActive)
        assertFalse(task(status = ZlibDownloadStatus.COMPLETED).isActive)
        assertFalse("已取消不能当进行中，否则详情页不显示续传/删除", task(status = ZlibDownloadStatus.CANCELLED).isActive)
    }

    /** U9：needsLogin 分流 —— 只有登录类失败才把「重试」换成「去登录」。 */
    @Test
    fun loginFailuresAreRoutedToLogin() {
        assertTrue(zlibFailureNeedsLogin("LOGIN_REQUIRED"))
        assertTrue(zlibFailureNeedsLogin("DAILY_LIMIT"))
        assertFalse(zlibFailureNeedsLogin("NETWORK"))
        assertFalse(zlibFailureNeedsLogin("HTTP"))
        assertFalse(zlibFailureNeedsLogin(ZLIB_IMPORT_FAILED_KIND))
        assertFalse(zlibFailureNeedsLogin(null))
    }

    /** U4：导入失败用独立 kind，详情页据此给「重新导入」而不是普通「重试」。 */
    @Test
    fun importFailedKindIsDistinct() {
        assertFalse(zlibFailureNeedsLogin(ZLIB_IMPORT_FAILED_KIND))
        assertTrue(ZLIB_IMPORT_FAILED_KIND.isNotBlank())
    }

    /** D8：删除不存在的记录返回 false（记录页据此提示「记录已不存在」，且不误动文件）。 */
    @Test
    fun removingUnknownRecordReportsFalse() {
        assertFalse(ZlibDownloadManager.removeHistory("no-such-book", deleteFile = false))
        assertFalse(ZlibDownloadManager.removeHistory("no-such-book", deleteFile = true))
    }
}
