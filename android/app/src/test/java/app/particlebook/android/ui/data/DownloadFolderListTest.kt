package app.particlebook.android.ui.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * D1 A-lite：默认下载目录的应用内文件列表。
 *
 * 纯函数部分锁定「过滤半成品 / 时间倒序 / 字段完整」；源码契约锁定记录页把默认目录路由到
 * 应用内列表页，而 SAF 自定义目录仍走系统文件管理器。真机路径由 p15 验收。
 */
class DownloadFolderListTest {

    @Test
    fun filtersDirectoriesHiddenAndPartialArtifacts() {
        val dir = newTempDir()
        try {
            File(dir, "book.pdf").writeText("pdf")
            File(dir, "book.pdf.part").writeText("partial")
            File(dir, "book.pdf.part.meta").writeText("meta")
            File(dir, "book.pdf.part.part").writeText("partial2")
            File(dir, "book.pdf.tmp").writeText("tmp")
            File(dir, ".hidden.pdf").writeText("hidden")
            File(dir, "subdir").mkdirs()
            // 文件名中间含 .part 但完整后缀是 .pdf：不能被误过滤。
            File(dir, "chapter.part1.pdf").writeText("pdf2")

            val entries = DownloadLocation.downloadFolderEntries(dir.listFiles().orEmpty().toList())

            assertEquals(
                listOf("book.pdf", "chapter.part1.pdf"),
                entries.map { it.name }.sorted(),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun sortsByModifiedTimeDescendingThenName() {
        val dir = newTempDir()
        try {
            val old = fileWithTime(dir, "old.pdf", 1_600_000_000_000L)
            val newest = fileWithTime(dir, "newest.epub", 1_600_000_300_000L)
            val tieB = fileWithTime(dir, "b.pdf", 1_600_000_100_000L)
            val tieA = fileWithTime(dir, "a.pdf", 1_600_000_100_000L)
            // 保证 old 一定不是最新；引用避免编译器告警。
            assertTrue(old.isFile)

            val entries = DownloadLocation.downloadFolderEntries(
                listOf(old, newest, tieB, tieA),
            )

            assertEquals(
                listOf("newest.epub", "a.pdf", "b.pdf", "old.pdf"),
                entries.map { it.name },
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun entryCarriesNamePathSizeAndModifiedTime() {
        val dir = newTempDir()
        try {
            val file = File(dir, "book.epub")
            file.writeText("hello")
            file.setLastModified(1_600_000_200_000L)

            val entry = DownloadLocation.downloadFolderEntries(listOf(file)).single()

            assertEquals("book.epub", entry.name)
            assertEquals(file.absolutePath, entry.path)
            assertEquals(5L, entry.sizeBytes)
            assertEquals(1_600_000_200_000L, entry.modifiedAt)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun emptyInputProducesEmptyList() {
        assertTrue(DownloadLocation.downloadFolderEntries(emptyList()).isEmpty())
    }

    @Test
    fun downloadFolderScreenListsDefaultDirectoryAndOffersSettingsEntry() {
        val source = sourceFile(
            "src/main/java/app/particlebook/android/ui/ZlibDownloadFolderScreen.kt",
        )
        assertTrue("必须读取默认下载目录文件", source.contains("DownloadLocation.listDefaultFolderEntries"))
        assertTrue("必须说明目录仅本应用可见", source.contains("应用专用目录，其他应用不可见"))
        assertTrue("必须提供切换下载目录入口", source.contains("切换下载目录"))
        assertTrue("未导入文件要提示先去下载记录页", source.contains("尚未导入书架"))
        assertTrue("已导入文件要能打开阅读器", source.contains("onOpenBook(book)"))
    }

    @Test
    fun downloadsScreenRoutesDefaultDirectoryToInAppList() {
        val source = sourceFile(
            "src/main/java/app/particlebook/android/ui/ZlibDownloadsScreen.kt",
        )
        assertTrue("记录页要暴露应用内文件夹入口", source.contains("onOpenDownloadFolder"))
        assertTrue("按下载目录类型分流", source.contains("DownloadLocation.isCustom(context)"))
        assertTrue("默认目录走应用内列表", source.contains("onOpenDownloadFolder()"))
        assertTrue("SAF 目录仍走系统文件管理器", source.contains("DownloadLocation.openFolder(context)"))
        assertTrue("系统入口失败仍带真实路径提示", source.contains("openFolderFailureMessage(context)"))
    }

    @Test
    fun appRootWiresDownloadFolderRoute() {
        val source = sourceFile("src/main/java/app/particlebook/android/ui/AppRoot.kt")
        assertTrue("必须有独立路由", source.contains("\"downloadFolder\""))
        assertTrue("记录页入口要压栈", source.contains("pushRoute(\"downloadFolder\")"))
        assertTrue("路由要渲染新页面", source.contains("ZlibDownloadFolderScreen("))
    }

    private fun fileWithTime(dir: File, name: String, modifiedAt: Long): File {
        val file = File(dir, name)
        file.writeText("x")
        file.setLastModified(modifiedAt)
        return file
    }

    private fun newTempDir(): File =
        Files.createTempDirectory("pb-download-folder-test").toFile()

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
