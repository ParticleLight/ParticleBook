package app.particlebook.android.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * task-93：记录页手工导入/详情页完成态的目标完整性守卫。
 *
 * 回归来源 docs/android-port/t88-review-08fe451.md：
 * - 记录页只按 DownloadLocation.exists 判断「导入书架」，0 字节/截断文件也能导入；
 * - 两处 adopt 没传 existingFileValidator，坏书架条目被当幂等命中；
 * - start() 的书架早退早于 forceRedownload，强制重下被吞；
 * - reimport 用旧 total 当 expectedBytes，完整文件被误判 INCOMPLETE；
 * - COMPLETED 目标缺失/损坏时详情页先给「重新导入」，失败落 IMPORT_FAILED 后才有「重新下载」。
 */
class ZlibTask93ImportGuardTest {

    @Test
    fun importStateLabelsAreUserFacingThreeStates() {
        assertNull("完整目标不显示异常标签", zlibImportStateLabel(ZlibDownloadTargetState.COMPLETE))
        assertEquals("文件缺失", zlibImportStateLabel(ZlibDownloadTargetState.MISSING))
        assertEquals("文件损坏", zlibImportStateLabel(ZlibDownloadTargetState.INCOMPLETE))
        assertEquals("文件损坏", zlibImportStateLabel(ZlibDownloadTargetState.UNKNOWN))
    }

    @Test
    fun staleRecordedTotalIsNotUsedAsIntegrityExpectation() {
        val complete = tempPdf()
        try {
            val actual = complete.length()
            assertEquals(
                "用实际长度校验时完整书应通过",
                ZlibDownloadTargetState.COMPLETE,
                zlibInspectBookFile(complete, actual),
            )
            assertEquals(
                "旧 total 与当前长度不符时旧逻辑会误判，这正是 task-93 要避免的",
                ZlibDownloadTargetState.INCOMPLETE,
                zlibInspectBookFile(complete, actual + 4096L),
            )
        } finally {
            complete.delete()
        }
    }

    @Test
    fun importTargetCarriesActualSizeAndResolvedFile() {
        val file = tempPdf()
        try {
            val target = ZlibImportTarget(
                state = zlibInspectBookFile(file, file.length()),
                size = file.length(),
                file = file,
            )
            assertEquals(ZlibDownloadTargetState.COMPLETE, target.state)
            assertEquals(file.length(), target.size)
            assertEquals(file.absolutePath, target.file?.absolutePath)
        } finally {
            file.delete()
        }
    }

    @Test
    fun recordPageInspectsTargetBeforeAdoptAndPassesValidator() {
        val source = sourceFile("src/main/java/app/particlebook/android/ui/ZlibDownloadsScreen.kt")
        assertTrue(
            "手工导入前要重新检查目标完整性",
            source.contains("ZlibDownloadManager.inspectImportTarget(context, record)"),
        )
        assertTrue("拒绝非完整目标并提示原因", source.contains("zlibReimportFailureMessage(check.state)"))
        assertTrue("三态标签由纯函数给出", source.contains("zlibImportStateLabel(targetCheck.state)"))
        assertTrue("已导入但书架副本损坏时不能给打开", source.contains("importedBookComplete == true"))
        assertTrue("坏书架条目要给重新导入入口", source.contains("\"重新导入\""))
        assertTrue("校验器用目标真实长度", source.contains("zlibExistingFileValidator(check.size)"))
        val adoptCalls = Regex("store\\.(adoptLocalFile|adoptDocument)\\(").findAll(source).count()
        val validatorArgs = Regex("existingFileValidator\\s*=").findAll(source).count()
        assertEquals("记录页两处 adopt 都必须传完整性校验器", adoptCalls, validatorArgs)
        assertEquals(2, adoptCalls)
        assertFalse("不能再只按 exists 显示导入入口", source.contains("fileExists == true"))
    }

    @Test
    fun detailCompletedActionUsesTargetStateNotExistence() {
        val source = sourceFile("src/main/java/app/particlebook/android/ui/ZlibDetailScreen.kt")
        assertTrue(
            "完成态检查读目标三态",
            source.contains("targetState = check.targetState"),
        )
        assertTrue(
            "完成态目标检查走统一导入检查",
            source.contains("inspectImportTarget(context, record)"),
        )
        assertFalse("不能再用 exists 判断目标可用", source.contains("targetExists"))
        assertTrue(
            "只有完整目标才给重新导入",
            source.contains("targetState == ZlibDownloadTargetState.COMPLETE"),
        )
        assertTrue("目标损坏要能直接重新下载", source.contains("下载文件已损坏"))
    }

    @Test
    fun forceRedownloadRunsBeforeLibraryShortcut() {
        val source = sourceFile("src/main/java/app/particlebook/android/ui/ZlibDownloadManager.kt")
        val force = source.indexOf("zlib_download_force_redownload")
        val library = source.indexOf("zlibCompleteLibraryBook(taggedBooks)")
        assertTrue("force 清理必须存在", force >= 0)
        assertTrue("书架命中判定必须存在", library >= 0)
        assertTrue("force 清理必须早于书架命中早退", force < library)
        assertTrue(
            "force 时跳过书架命中扫描",
            source.contains("val taggedBooks = if (forceRedownload)"),
        )
    }

    @Test
    fun reimportUsesActualTargetLengthInsteadOfRecordedTotal() {
        val source = sourceFile("src/main/java/app/particlebook/android/ui/ZlibDownloadManager.kt")
        assertTrue(
            "重新导入统一走 inspectImportTarget",
            source.contains("val target = inspectImportTarget(context, task)"),
        )
        assertTrue(
            "SAF 检查要传目标实际长度，未知时降级为 -1",
            source.contains("expectedBytes = actual.takeIf { it > 0L } ?: -1L"),
        )
        assertFalse(
            "不能再拿旧 total 当 File 的 expectedBytes",
            source.contains("zlibInspectBookFile(file, task.total)"),
        )
        assertTrue(
            "start 的中断恢复判定也不能用旧 total",
            source.contains("inspectImportTarget(context, interruptedRecord).state"),
        )
        assertFalse(
            "中断恢复不能再拿旧 total 校验 File 目标",
            source.contains("zlibInspectBookFile(it, interruptedRecord.total)"),
        )
        val start = source.indexOf("private suspend fun resolveReimportTarget")
        val end = source.indexOf("private data class ZlibReimportTarget", start)
        assertTrue("要能定位 resolveReimportTarget", start >= 0 && end > start)
        val reimportBody = source.substring(start, end)
        assertFalse(
            "重新导入不能再用旧 total 做 SAF 首次裁决",
            reimportBody.contains("inspectSafDownloadTarget"),
        )
    }

    private fun tempPdf(): File {
        val bytes = ByteArray(2_048)
        "%PDF-1.7\n".toByteArray(Charsets.ISO_8859_1).copyInto(bytes)
        "%%EOF\n".toByteArray(Charsets.ISO_8859_1).copyInto(bytes, bytes.size - 6)
        return createTempFile("pb-task93", ".pdf").apply { writeBytes(bytes) }
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
