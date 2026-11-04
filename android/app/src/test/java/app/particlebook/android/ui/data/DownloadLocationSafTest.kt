package app.particlebook.android.ui.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * task-45 P2-2：SAF 下载目录的授权校验。
 *
 * 真机上系统可能回收 SAF 持久授权，旧实现只看 prefs 里有没有 uri，导致「设置显示自定义目录，
 * 下载实际写不进去」；这里锁定「持久写授权 + 目录可创建文件才启用」的判定，以及
 * 设置页/下载页的配套兜底（失效回默认、书架加载中禁用导入）。
 */
class DownloadLocationSafTest {

    @Test
    fun safTreeAcceptableRequiresPersistedAndWritable() {
        assertTrue("持久授权 + 可写才允许启用", safTreeAcceptable(persisted = true, writable = true))
        assertFalse("没有持久授权不能启用", safTreeAcceptable(persisted = false, writable = true))
        assertFalse("目录不可写不能启用", safTreeAcceptable(persisted = true, writable = false))
        assertFalse("两者都没有不能启用", safTreeAcceptable(persisted = false, writable = false))
    }

    @Test
    fun downloadLocationChecksPersistedWritePermissionAndReleasesOldTree() {
        val source = sourceFile("src/main/java/app/particlebook/android/ui/data/DownloadLocation.kt")
        assertTrue("启用前必须查持久授权", source.contains("persistedUriPermissions"))
        assertTrue("下载要写文件，读+写权限都必须有", source.contains("isWritePermission"))
        assertTrue("目录要支持创建文件", source.contains("FLAG_DIR_SUPPORTS_CREATE"))
        assertTrue("授权失效要有启动校验入口", source.contains("fun validateStoredTree"))
        assertTrue("失效要能释放旧授权", source.contains("releasePersistableUriPermission"))
        assertTrue("保存必须走统一判定", source.contains("safTreeAcceptable(persisted = persisted"))
    }

    @Test
    fun settingsSurfacesInvalidSafFallbackInsteadOfPretendingSuccess() {
        val source = sourceFile("src/main/java/app/particlebook/android/ui/SettingsScreen.kt")
        assertTrue("启动时要校验持久授权", source.contains("validateStoredTree(context.applicationContext)"))
        assertTrue("失败提示必须说明目录未更改", source.contains("无法使用该文件夹（需要可写权限），下载目录未更改"))
        assertTrue("授权失效要提示已回默认目录", source.contains("下载目录授权已失效，已恢复默认目录，请重新选择"))
    }

    @Test
    fun downloadsScreenDisablesImportWhileLibraryIsLoading() {
        val source = sourceFile("src/main/java/app/particlebook/android/ui/ZlibDownloadsScreen.kt")
        assertTrue("导入按钮要受书架加载状态门控", source.contains("importEnabled = !libraryLoading"))
        assertTrue("加载中按钮要禁用", source.contains("enabled = importEnabled"))
        assertTrue("加载中文案要明确", source.contains("书架加载中"))
        assertTrue("点击导入前先等书架就绪", source.contains("store.ensureLoaded()"))
    }

    /** 与 DownloadFolderOpenTest 相同的源码定位：从测试工作目录向上找 android/app 根。 */
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
        val found = candidates.firstOrNull { it.isFile }
        assumeTrue(
            "找不到 " + relative + "（工作目录=" + System.getProperty("user.dir") + "），跳过源码契约检查",
            found != null,
        )
        return found!!.readText()
    }
}
