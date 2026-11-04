package app.particlebook.android.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-2 task-73：重新导入前的完整性裁决。
 *
 * 真机回归：下载记录 COMPLETED，但目标被外部截断/写成 0 字节时，点「重新导入」不能
 * 假成功把坏书登记进书架；必须落到 IMPORT_FAILED 并提示用户重新下载。
 */
class ZlibReimportDecisionTest {

    @Test
    fun completePdfCanBeImported() {
        val file = tempPdf(complete = true)
        try {
            assertEquals(
                ZlibDownloadTargetState.COMPLETE,
                zlibInspectBookFile(file),
            )
            assertEquals(
                ZlibReimportDecision.IMPORT,
                zlibReimportDecision(zlibInspectBookFile(file)),
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun zeroByteTargetIsRejected() {
        val file = createTempFile("pb-reimport-zero", ".pdf")
        try {
            assertEquals(0L, file.length())
            assertEquals(
                ZlibDownloadTargetState.INCOMPLETE,
                zlibInspectBookFile(file),
            )
            assertEquals(
                ZlibReimportDecision.FAIL_INCOMPLETE,
                zlibReimportDecision(zlibInspectBookFile(file)),
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun truncatedPdfIsRejected() {
        val file = tempPdf(complete = false)
        try {
            assertEquals(
                ZlibDownloadTargetState.INCOMPLETE,
                zlibInspectBookFile(file),
            )
            assertEquals(
                ZlibReimportDecision.FAIL_INCOMPLETE,
                zlibReimportDecision(zlibInspectBookFile(file)),
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun missingTargetIsRejected() {
        val file = File(System.getProperty("java.io.tmpdir"), "pb-reimport-missing-" + System.nanoTime())
        assertFalse(file.exists())
        assertEquals(ZlibDownloadTargetState.MISSING, zlibInspectBookFile(file))
        assertEquals(
            ZlibReimportDecision.FAIL_MISSING,
            zlibReimportDecision(ZlibDownloadTargetState.MISSING),
        )
    }

    @Test
    fun unknownTargetIsConservativelyRejected() {
        assertEquals(
            ZlibReimportDecision.FAIL_INCOMPLETE,
            zlibReimportDecision(ZlibDownloadTargetState.UNKNOWN),
        )
    }

    @Test
    fun rejectionMessagesTellUserToRedownload() {
        assertTrue(zlibReimportFailureMessage(ZlibDownloadTargetState.INCOMPLETE).contains("重新下载"))
        assertTrue(zlibReimportFailureMessage(ZlibDownloadTargetState.UNKNOWN).contains("重新下载"))
        assertTrue(zlibReimportFailureMessage(ZlibDownloadTargetState.MISSING).contains("重新下载"))
        assertFalse(zlibReimportFailureMessage(ZlibDownloadTargetState.MISSING).contains("重新导入"))
    }

    @Test
    fun importFailureCanCarryRedownloadMessage() {
        val task = ZlibDownloadTask(
            id = "zlib:b1",
            bookId = "b1",
            title = "T",
            author = null,
            format = "pdf",
            downloadPath = "/d",
            fileName = "T.pdf",
            destPath = "/tmp/T.pdf",
            status = ZlibDownloadStatus.DOWNLOADING,
            received = 512L,
            total = 1024L,
        )
        val failed = ZlibDownloadCompletion.importFailed(
            task = task,
            cause = IllegalStateException("truncated"),
            messageOverride = zlibReimportFailureMessage(ZlibDownloadTargetState.INCOMPLETE),
        )
        assertEquals(ZLIB_IMPORT_FAILED_KIND, failed.errorKind)
        assertTrue(failed.errorMessage!!.contains("重新下载"))
        assertFalse(failed.errorMessage!!.contains("重新导入"))
    }

    @Test
    fun existingFileValidatorRejectsTruncatedShelfCopy() {
        val complete = tempPdf(complete = true)
        val truncated = tempPdf(complete = false)
        val zero = createTempFile("pb-reimport-shelf-zero", ".pdf")
        try {
            assertTrue(zlibExistingFileValidator(complete.length())(complete))
            assertFalse("截断的旧书架副本不能命中幂等", zlibExistingFileValidator(truncated.length())(truncated))
            assertFalse("0 字节文件不能命中幂等", zlibExistingFileValidator(0L)(zero))
            assertFalse(
                "长度与预期不符也不能命中幂等",
                zlibExistingFileValidator(complete.length() + 1)(complete),
            )
        } finally {
            complete.delete()
            truncated.delete()
            zero.delete()
        }
    }

    @Test
    fun everyAdoptionCallPassesIntegrityValidator() {
        val source = sourceFile("src/main/java/app/particlebook/android/ui/ZlibDownloadManager.kt")
        val adoptCalls = Regex("""store\.(adoptLocalFile|adoptDocument)\(""").findAll(source).count()
        val validatorArgs = Regex("""existingFileValidator\s*=""").findAll(source).count()
        assertEquals("每处书架登记都必须传完整性校验器", adoptCalls, validatorArgs)
        assertTrue("至少覆盖下载完成/提前导入/重新导入三条路径", adoptCalls >= 6)
    }

    @Test
    fun managerReimportValidatesBeforeAdopt() {
        val source = sourceFile("src/main/java/app/particlebook/android/ui/ZlibDownloadManager.kt")
        assertTrue("重新导入要先解析真实目标", source.contains("resolveReimportTarget"))
        assertTrue("重新导入要按完整性裁决", source.contains("zlibReimportDecision(target.state)"))
        assertTrue("拒绝后要落 IMPORT_FAILED", source.contains("zlib_download_reimport_rejected"))
        assertTrue("详情页要有重新下载出口", source.contains("forceRedownload"))
        assertTrue(
            "COMPLETED/IMPORT_FAILED 的早退必须统一交给 reimportTask 校验",
            source.contains("zlib_download_completed_reimport"),
        )
    }

    private fun tempPdf(complete: Boolean): File {
        val bytes = ByteArray(2_048)
        "%PDF-1.7\n".toByteArray(Charsets.ISO_8859_1).copyInto(bytes)
        if (complete) {
            "%%EOF\n".toByteArray(Charsets.ISO_8859_1).copyInto(bytes, bytes.size - 6)
        }
        return createTempFile("pb-reimport", ".pdf").apply { writeBytes(bytes) }
    }

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
