package app.particlebook.android.ui

import app.particlebook.android.seedCopyNames
import app.particlebook.android.ui.data.Book
import app.particlebook.android.ui.data.BookFormat
import app.particlebook.android.ui.data.LibraryRemovalBus
import app.particlebook.android.ui.data.coverCacheFile
import app.particlebook.android.ui.data.evictCoverFiles
import app.particlebook.android.ui.data.filterDeletedSeedBooks
import app.particlebook.android.ui.data.progressKeys
import app.particlebook.android.ui.data.zlibBookTag
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * task-60 P3-4 / P3-5：删书级联清理 + 内置样书删除不复活。
 *
 * 关键行为都用 JVM 可测的纯函数锁定：
 * - 进度键 / 封面缓存文件 / 样书复制清单 / fixtures 过滤 / 下载记录按 path+tag 关联；
 * - 删除流程本身依赖 Android Context，用源码契约断言「确实调用了级联清理」。
 */
class LibraryDeletionCascadeTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("pb-delete-cascade-").toFile()
    }

    @After
    fun tearDown() {
        LibraryRemovalBus.listener = null
        root.deleteRecursively()
    }

    @Test
    fun progressKeysCoverPagePagesAndLastRead() {
        assertEquals(
            listOf("page_42", "pages_42", "last_read_42"),
            progressKeys(42L),
        )
    }

    @Test
    fun filterDeletedSeedBooksDropsRemovedSamplesOnly() {
        val seedA = book(File(root, "seed-01.epub").absolutePath, title = "样书 1")
        val seedB = book(File(root, "seed-03.pdf").absolutePath, title = "样书 2")
        val other = book(File(root, "import-123.epub").absolutePath, title = "用户书")

        val filtered = filterDeletedSeedBooks(listOf(seedA, seedB, other), setOf("seed-01.epub"))
        assertEquals("只应过滤掉已删除的样书", listOf(seedB, other), filtered)
        assertEquals("没有删除名单时原样返回", listOf(seedA, seedB, other), filterDeletedSeedBooks(listOf(seedA, seedB, other), emptySet()))
    }

    @Test
    fun seedCopyNamesSkipsDeletedAndExistingButCopiesEmptyFile() {
        val dir = File(root, "seed-dir").apply { mkdirs() }
        File(dir, "seed-01.epub").writeText("already there")
        File(dir, "seed-03.pdf").writeText("") // 0 字节视为没复制成功，应重新复制

        val names = seedCopyNames(
            seedNames = listOf("seed-01.epub", "seed-02.epub", "seed-03.pdf"),
            deleted = setOf("seed-02.epub"),
            dir = dir,
        )

        assertEquals("已存在非空 / 已删除的样书都不复制，0 字节要补复制", listOf("seed-03.pdf"), names)
    }

    @Test
    fun evictCoverFilesDeletesOnlyTargetIds() {
        val covers = File(root, "covers").apply { mkdirs() }
        coverCacheFile(covers, 7L).writeText("png-7")
        coverCacheFile(covers, 8L).writeText("png-8")
        File(covers, "keep.txt").writeText("unrelated")

        val deleted = evictCoverFiles(covers, listOf(7L, 9L))

        assertEquals("只删存在的目标 id 文件", 1, deleted)
        assertFalse(coverCacheFile(covers, 7L).exists())
        assertTrue(coverCacheFile(covers, 8L).isFile)
        assertTrue(File(covers, "keep.txt").isFile)
    }

    @Test
    fun removalBusNotifiesOnlyNonEmptyLists() {
        val seen = ArrayList<List<Book>>()
        LibraryRemovalBus.listener = { seen += it }

        val removed = listOf(book(File(root, "a.epub").absolutePath, id = 1L))
        LibraryRemovalBus.notifyRemoved(removed)
        LibraryRemovalBus.notifyRemoved(emptyList())

        assertEquals(1, seen.size)
        assertEquals(removed, seen.single())
    }

    @Test
    fun downloadHistoryMatchesRemovedBookByPathOrZlibTag() {
        val removedA = book("/lib/_pb_files/default-dir.epub", id = 1L)
        val removedB = book(
            "/lib/_pb_files/saf-copy.epub",
            id = 2L,
            tags = listOf(zlibBookTag("z1")),
        )
        val records = listOf(
            record(bookId = "default", destPath = removedA.filePath).copy(id = "r1"),
            record(bookId = "z1", destPath = "content://doc/saf-copy").copy(id = "r2"),
            record(bookId = "other", destPath = "/elsewhere/other.epub").copy(id = "r3"),
        )

        val matched = zlibHistoryForRemovedBooks(records, listOf(removedA, removedB)).map { it.id }

        assertEquals("默认目录按 path、SAF 副本按 zlib tag 关联，其它记录不能误删", listOf("r1", "r2"), matched)
    }

    @Test
    fun storeCascadesProgressCoverSeedAndDownloadRecordCleanup() {
        val source = sourceFile("src/main/java/app/particlebook/android/ui/data/LibraryStore.kt")
        assertTrue("删书要清阅读进度", source.contains("ReadingProgress.clear(context, removedIds)"))
        assertTrue("删书要清封面缓存", source.contains("BookCoverLoader.evict(context, removedIds)"))
        assertTrue("删内置样书要记入删除名单", source.contains("Assets.markSeedDeleted"))
        assertTrue("删书要通知下载记录级联清理", source.contains("LibraryRemovalBus.notifyRemoved(removed)"))
        assertTrue("加载时不能合并已删样书", source.contains("filterDeletedSeedBooks"))
        assertTrue("启动时不再复制已删样书", source.contains("Assets.deletedSeedNames"))
    }

    @Test
    fun downloadManagerRegistersRemovalListenerAndDeletesRecordOnly() {
        val source = sourceFile("src/main/java/app/particlebook/android/ui/ZlibDownloadManager.kt")
        assertTrue("下载管理器要注册删书监听", source.contains("LibraryRemovalBus.listener = ::removeDownloadHistoryForBooks"))
        assertTrue("级联清理按 path/tag 匹配", source.contains("zlibHistoryForRemovedBooks"))
        assertTrue("下载记录只能 RECORD_ONLY（文件归书架/用户）", source.contains("removeHistory(record.bookId, deleteFile = false)"))
        assertTrue("历史未加载完要排队", source.contains("removedWhileHistoryLoading"))
    }

    // ---------------------------------------------------------------- helpers

    private fun book(
        path: String,
        id: Long = 1L,
        title: String = File(path).nameWithoutExtension,
        tags: List<String> = emptyList(),
    ): Book = Book(
        id = id,
        title = title,
        author = null,
        format = if (path.endsWith(".pdf")) BookFormat.Pdf else BookFormat.Epub,
        filePath = path,
        fileSize = 0L,
        tags = tags,
    )

    private fun record(bookId: String, destPath: String): ZlibDownloadTask = ZlibDownloadTask(
        id = "task-" + bookId,
        bookId = bookId,
        title = "书",
        author = null,
        format = "epub",
        downloadPath = destPath,
        fileName = File(destPath).name,
        destPath = destPath,
    )

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
