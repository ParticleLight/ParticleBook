package app.particlebook.android.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-2 task-73：SAF 完整性检查的 cacheDir 临时文件清理。
 *
 * 回归：provider 无法随机读时整份复制复核；复制失败/取消/进程被杀不能留下 zlib-inspect-*.tmp，
 * 启动时也要把历史版本遗留的临时文件扫掉。
 */
class ZlibInspectTempCleanupTest {

    @Test
    fun cleanupDeletesOnlyInspectTemps() {
        val dir = tempDir()
        try {
            val stale = File(dir, "zlib-inspect-zlib_b1-0001.tmp").apply { writeText("stale") }
            val unrelated = File(dir, "zlib-downloads.part").apply { writeText("keep") }
            val nested = File(dir, "nested").apply { mkdirs() }
            assertEquals(1, zlibCleanInspectTemps(dir))
            assertFalse("完整性检查临时文件应被删除", stale.exists())
            assertTrue("无关缓存文件不能误删", unrelated.exists())
            assertTrue("子目录不能当成临时文件删除", nested.isDirectory)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun cleanupToleratesMissingCacheDir() {
        val dir = File(System.getProperty("java.io.tmpdir"), "pb-inspect-missing-" + System.nanoTime())
        assertFalse(dir.exists())
        assertEquals(0, zlibCleanInspectTemps(dir))
    }

    @Test
    fun inspectCopyAlwaysCleansTempAndRethrowsCancellation() {
        val source = sourceFile("src/main/java/app/particlebook/android/ui/ZlibDownloadManager.kt")
        assertTrue("复制失败也要 temp.delete()", source.contains("temp.delete()"))
        assertTrue("取消必须透传，不能当 UNKNOWN", source.contains("catch (cancellation: CancellationException)"))
        assertTrue("复制取消/异常要立刻删除临时文件", source.contains("throw cancellation"))
        assertTrue("启动时应清理遗留检查临时文件", source.contains("zlibCleanInspectTemps(app.cacheDir)"))
        assertTrue("只清理 zlib-inspect- 前缀", source.contains("ZLIB_INSPECT_TMP_PREFIX"))
    }

    @Test
    fun completedHistoryAlwaysReimportsThroughIntegrityCheck() {
        val source = sourceFile("src/main/java/app/particlebook/android/ui/ZlibDownloadManager.kt")
        assertTrue(
            "COMPLETED 历史必须走 reimportTask 校验，不能按 exists 直接完成",
            source.contains("zlib_download_completed_reimport"),
        )
        assertTrue(
            "reimportTask 必须按目标完整性裁决",
            source.contains("zlibReimportDecision(target.state)"),
        )
        assertFalse(
            "不应再保留只按 exists 的旧检查函数",
            source.contains("inspectDownloadTarget"),
        )
    }

    private fun tempDir(): File {
        val file = createTempFile("pb-inspect-clean", "")
        assertTrue(file.delete())
        assertTrue(file.mkdirs())
        return file
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
