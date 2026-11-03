package app.particlebook.android.ui

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** U1：zlib_downloads.json 单写者队列 + 唯一 tmp + 损坏隔离。 */
class ZlibDownloadHistoryStoreTest {

    private fun record(bookId: String) = ZlibDownloadTask(
        id = "zlib:" + bookId,
        bookId = bookId,
        title = "book-" + bookId,
        author = null,
        format = "epub",
        downloadPath = "/d/" + bookId,
        fileName = bookId + ".epub",
        destPath = "/sdcard/" + bookId + ".epub",
        status = ZlibDownloadStatus.COMPLETED,
        received = 10L,
        total = 10L,
    )

    private fun newStore(dir: File): ZlibDownloadHistoryStore {
        val file = File(dir, "zlib_downloads.json")
        return ZlibDownloadHistoryStore(
            file = file,
            encode = { list -> "ids:" + list.joinToString(separator = ",") { it.bookId } },
            decode = { text ->
                if (!text.startsWith("ids:")) throw IllegalArgumentException("bad history")
                text.removePrefix("ids:").split(",").filter { it.isNotBlank() }.map { record(it) }
            },
        )
    }

    @Test
    fun missingFileReadsEmpty() {
        val dir = Files.createTempDirectory("pb-hist").toFile()
        try {
            assertTrue(newStore(dir).read().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun singleWriterKeepsLatestSnapshotAndCleansTmp() {
        val dir = Files.createTempDirectory("pb-hist").toFile()
        try {
            val store = newStore(dir)
            assertTrue("第一个 submit 拿到写权", store.submit(listOf(record("a"))))
            assertFalse("写入期间的 submit 不再拿写权", store.submit(listOf(record("a"), record("b"))))
            store.drain()
            assertEquals(listOf("a", "b"), store.read().map { it.bookId })
            val files = dir.listFiles()!!.toList()
            val leftovers = files.filter { it.name.endsWith(".tmp") }
            assertTrue("唯一 tmp 写完必须清理: " + leftovers, leftovers.isEmpty())
            assertEquals("目录里只应有正式历史文件: " + files, 1, files.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun concurrentSubmitAndDrainNeverCorrupts() {
        val dir = Files.createTempDirectory("pb-hist").toFile()
        try {
            val store = newStore(dir)
            val snapshot = (0 until 8).map { record("b" + it) }
            val start = CountDownLatch(1)
            val threads = (1..8).map {
                Thread {
                    start.await()
                    if (store.submit(snapshot)) store.drain()
                }.apply { start() }
            }
            start.countDown()
            threads.forEach { it.join() }
            assertEquals(8, store.read().size)
            val leftovers = dir.listFiles()!!.filter { it.name.endsWith(".tmp") }
            assertTrue("并发写不应留下 tmp: " + leftovers, leftovers.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun corruptedHistoryIsQuarantinedAndRebuilt() {
        val dir = Files.createTempDirectory("pb-hist").toFile()
        try {
            val file = File(dir, "zlib_downloads.json")
            file.writeText("garbage content")
            val store = newStore(dir)
            assertTrue(store.read().isEmpty())
            val bad = File(dir, "zlib_downloads.json.bad")
            assertTrue("损坏文件应留 .bad", bad.isFile)
            assertEquals("garbage content", bad.readText())
            assertFalse(file.exists())
            // 重建：下一次写入正常落盘。
            assertTrue(store.submit(listOf(record("c"))))
            store.drain()
            assertEquals(listOf("c"), store.read().map { it.bookId })
        } finally {
            dir.deleteRecursively()
        }
    }
}
