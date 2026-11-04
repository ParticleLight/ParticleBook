package app.particlebook.android.ui.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * task-79：删书级联的线程与静默回收契约。
 *
 * LibraryStore.removeBooks 依赖 Android Context，JVM 单测无法实例化；这里锁定承重行为：
 * - 正常删书：索引/文件/进度/封面清理后，把 bus 事件切到主线程（NonCancellable）派发，
 *   与下载管理器的主线程 history/pending 队列串行（消除「入队后初始化清空」的 TOCTOU）；
 * - 墓碑命中后的静默回收：走同一套清理，但绝不再次通知 bus（避免下载管理器回调递归）。
 */
class LibraryRemovalSilentTest {

    @Test
    fun removeBooksDispatchesBusOnMainThreadAndIsNotCancellable() {
        val source = sourceFile("src/main/java/app/particlebook/android/ui/data/LibraryStore.kt")
        val body = functionBody(source, "suspend fun removeBooks(ids: Set<Long>)")
        assertTrue("正常删书走 notifyRemovalBus=true", body.contains("notifyRemovalBus = true"))
        val internalBody = functionBody(source, "private suspend fun removeBooksInternal(")
        assertTrue("bus 事件必须在 Main.immediate 派发", internalBody.contains("Dispatchers.Main.immediate"))
        assertTrue("索引已删，级联通知不能被取消", internalBody.contains("NonCancellable"))
        assertTrue("仍要通知 bus", internalBody.contains("LibraryRemovalBus.notifyRemoved(removed)"))
        assertTrue("通知失败只记日志，不能误报删除失败", internalBody.contains("notify removed books failed"))
    }

    @Test
    fun removeBooksSilentlyUsesSameCleanupButNeverNotifiesBus() {
        val source = sourceFile("src/main/java/app/particlebook/android/ui/data/LibraryStore.kt")
        val silent = functionBody(source, "suspend fun removeBooksSilently(ids: Set<Long>)")
        assertTrue("静默回收不能通知 bus（防递归）", silent.contains("notifyRemovalBus = false"))

        val internalBody = functionBody(source, "private suspend fun removeBooksInternal(")
        assertTrue(
            "静默回收必须复用同一套级联清理（进度/封面/样书）",
            internalBody.contains("ReadingProgress.clear(context, removedIds)") &&
                internalBody.contains("BookCoverLoader.evict(context, removedIds)") &&
                internalBody.contains("markDeletedSeeds(removed)"),
        )
        assertTrue("bus 只在 notifyRemovalBus 为真时触发", internalBody.contains("if (notifyRemovalBus) {"))
    }

    @Test
    fun removalBusStillSynchronousForDirectCallers() {
        // 现有 LibraryDeletionCascadeTest 依赖同步通知；主线程派发只发生在 LibraryStore 的删书路径，
        // bus 本身保持同步，便于单测和其它调用方直接使用。
        val seen = ArrayList<List<Book>>()
        LibraryRemovalBus.listener = { seen += it }
        try {
            val books = listOf(book(1L))
            LibraryRemovalBus.notifyRemoved(books)
            LibraryRemovalBus.notifyRemoved(emptyList())
            assertTrue("直接调用应同步回调且忽略空列表", seen.size == 1 && seen.single() == books)
        } finally {
            LibraryRemovalBus.listener = null
        }
    }

    @Test
    fun oldCallersThatCannotRunOnMainThreadUseExplicitSuspendApi() {
        // 文件里不应再有「IO 线程直接 notifyRemoved」的旧写法：正常删书与静默回收都走 removeBooksInternal。
        val source = sourceFile("src/main/java/app/particlebook/android/ui/data/LibraryStore.kt")
        val removeBooksUsages = source.split("LibraryRemovalBus.notifyRemoved(").size - 1
        assertTrue("只应有一处 notifyRemoved 调用（在 Main.immediate 内）", removeBooksUsages == 1)
        assertFalse(
            "removeBooks 不能自己直接调用 bus（必须在 internal 的 Main 派发里）",
            functionBody(source, "suspend fun removeBooks(ids: Set<Long>)")
                .contains("LibraryRemovalBus.notifyRemoved("),
        )
    }

    // ---------------------------------------------------------------- task-73 需要的可注入完整性校验

    @Test
    fun registerFileRepointsExistingEntryWhenValidatorRejectsBrokenFile() = kotlinx.coroutines.runBlocking {
        val dir = Files.createTempDirectory("pb-validator-reject-").toFile()
        try {
            val store = LibraryIndexStore(dir, TestIndexCodec())
            store.load { it }
            val broken = File(dir, "broken.epub").apply { writeBytes(ByteArray(0)) }
            val zlibTag = zlibBookTag("z1")
            val first = store.registerFile(broken, "broken", null, listOf(zlibTag)).getOrThrow()
            val fresh = File(dir, "fresh.epub").apply { writeText("PK complete epub") }

            val second = store.registerFile(
                file = fresh,
                title = "fresh",
                author = null,
                tags = listOf(zlibTag),
                existingFileValidator = { it.length() > 0L },
            ).getOrThrow()

            assertTrue("坏文件不能被当成幂等命中", second.id == first.id)
            assertTrue("条目要改指到这次的新文件", second.filePath == fresh.absolutePath)
            assertTrue("只保留一条", store.books.size == 1)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun registerFileKeepsExistingEntryWhenValidatorAccepts() = kotlinx.coroutines.runBlocking {
        val dir = Files.createTempDirectory("pb-validator-accept-").toFile()
        try {
            val store = LibraryIndexStore(dir, TestIndexCodec())
            store.load { it }
            val good = File(dir, "good.epub").apply { writeText("PK complete epub") }
            val zlibTag = zlibBookTag("z2")
            val first = store.registerFile(good, "good", null, listOf(zlibTag)).getOrThrow()
            val another = File(dir, "another.epub").apply { writeText("PK another epub") }

            val second = store.registerFile(
                file = another,
                title = "another",
                author = null,
                tags = listOf(zlibTag),
                existingFileValidator = { it.length() > 0L },
            ).getOrThrow()

            assertTrue("校验通过时保持原条目/原路径", second.id == first.id && second.filePath == good.absolutePath)
            assertTrue("不能多登记一条", store.books.size == 1)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun adoptDocumentChecksValidatorBeforeEarlyReturnAndForwardsIt() {
        val source = sourceFile("src/main/java/app/particlebook/android/ui/data/LibraryStore.kt")
        val adoptDocument = functionBody(source, "suspend fun adoptDocument(")
        assertTrue("早退前要校验已有文件完整性", adoptDocument.contains("existingFileValidator == null || existingFileValidator(existingFile)"))
        assertTrue("校验失败要继续复制并重指", adoptDocument.contains("existing entry unusable"))
        assertTrue("要转发给 adoptLocalFile", adoptDocument.contains("existingFileValidator = existingFileValidator"))

        val adoptLocalFile = functionBody(source, "suspend fun adoptLocalFile(")
        assertTrue("adoptLocalFile 要透传校验器", adoptLocalFile.contains("existingFileValidator = existingFileValidator"))
        val registerFile = functionBody(source, "suspend fun registerFile(")
        assertTrue("registerFile 支持可选校验器", registerFile.contains("existingFileValidator: ((File) -> Boolean)? = null"))
        assertTrue(
            "校验失败按文件缺失处理（repoint）",
            registerFile.contains("existingFileValidator != null && !existingFileValidator(existingFile)"),
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun book(id: Long): Book = Book(
        id = id,
        title = "书",
        author = null,
        format = BookFormat.Epub,
        filePath = "/lib/_pb_files/book-$id.epub",
        fileSize = 0L,
    )

    /** 截取从签名到下一个顶层函数声明前的源码块（够用于契约断言）。 */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assumeTrue("找不到函数 " + signature, start >= 0)
        val tail = source.substring(start + signature.length)
        val next = tail.indexOf("\n    suspend fun ")
            .takeIf { it >= 0 }
            ?: tail.indexOf("\n    private suspend fun ")
                .takeIf { it >= 0 }
                ?: tail.length
        return tail.substring(0, next)
    }

    /** 与其它源码契约测试相同的定位方式：从测试工作目录向上找 android/app 根。 */
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
