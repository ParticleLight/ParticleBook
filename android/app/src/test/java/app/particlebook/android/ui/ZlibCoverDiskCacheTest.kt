package app.particlebook.android.ui

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 封面磁盘缓存文件层（T13 P2）：原子写、坏文件删除、容量淘汰、同 key 并发写。
 * 纯 JVM，不依赖 Bitmap / Android Context。
 */
class ZlibCoverDiskCacheTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("pb-zlib-covers").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun writeCreatesCompleteFileAndLeavesNoTemp() {
        val target = ZlibCoverDiskCache.file(dir, "book1")
        val bytes = ByteArray(4096) { (it % 251).toByte() }

        assertTrue(ZlibCoverDiskCache.write(dir, target, bytes))

        assertTrue(target.isFile)
        assertArrayEquals(bytes, target.readBytes())
        assertArrayEquals(bytes, ZlibCoverDiskCache.read(target))
        assertTrue(
            "落盘后不能留 .tmp 半成品",
            dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") },
        )
    }

    @Test
    fun writeAtomicallyReplacesExistingCover() {
        val target = ZlibCoverDiskCache.file(dir, "book1")
        assertTrue(ZlibCoverDiskCache.write(dir, target, ByteArray(1024) { 1 }))

        val replacement = ByteArray(2048) { 2 }
        assertTrue(ZlibCoverDiskCache.write(dir, target, replacement))

        assertArrayEquals(replacement, ZlibCoverDiskCache.read(target))
        assertEquals(1, dir.listFiles().orEmpty().count { it.name.endsWith(".jpg") })
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun readMissingFileReturnsNull() {
        assertNull(ZlibCoverDiskCache.read(ZlibCoverDiskCache.file(dir, "missing")))
    }

    @Test
    fun deleteRemovesCorruptFile() {
        val target = ZlibCoverDiskCache.file(dir, "bad")
        target.writeBytes(byteArrayOf(1, 2, 3))

        ZlibCoverDiskCache.delete(target)

        assertFalse(target.exists())
        assertNull(ZlibCoverDiskCache.read(target))
    }

    @Test
    fun pruneByFileCountDropsOldest() {
        val files = (0 until 6).map { index ->
            ZlibCoverDiskCache.file(dir, "book" + index).apply { writeBytes(ByteArray(10)) }
        }
        files.forEachIndexed { index, file -> file.setLastModified(1_000_000L + index * 2_000L) }

        val removed = ZlibCoverDiskCache.prune(dir, maxBytes = 1_000_000L, maxFiles = 2)

        assertEquals(4, removed)
        val remaining = dir.listFiles().orEmpty()
            .filter { it.name.endsWith(".jpg") }
            .map { it.name }
            .sorted()
        assertEquals(listOf("book4.jpg", "book5.jpg"), remaining)
    }

    @Test
    fun pruneByBytesDropsOldest() {
        val files = (0 until 5).map { index ->
            ZlibCoverDiskCache.file(dir, "book" + index).apply { writeBytes(ByteArray(40)) }
        }
        files.forEachIndexed { index, file -> file.setLastModified(2_000_000L + index * 2_000L) }

        ZlibCoverDiskCache.prune(dir, maxBytes = 100L, maxFiles = 100)

        val remaining = dir.listFiles().orEmpty()
            .filter { it.name.endsWith(".jpg") }
            .map { it.name }
            .sorted()
        assertEquals(listOf("book3.jpg", "book4.jpg"), remaining)
    }

    @Test
    fun pruneKeepsFreshlyWrittenCoverEvenIfOldest() {
        val keep = ZlibCoverDiskCache.file(dir, "fresh")
        keep.writeBytes(ByteArray(10))
        keep.setLastModified(3_000_000L)
        (0 until 3).forEach { index ->
            ZlibCoverDiskCache.file(dir, "old" + index).apply {
                writeBytes(ByteArray(10))
                setLastModified(3_100_000L + index)
            }
        }

        ZlibCoverDiskCache.prune(dir, maxBytes = 1_000_000L, maxFiles = 2, keep = keep)

        assertTrue("刚写入的封面不能被自己触发的清理删掉", keep.isFile)
        assertEquals(2, dir.listFiles().orEmpty().count { it.name.endsWith(".jpg") })
    }

    @Test
    fun pruneRemovesOnlyStaleTempFiles() {
        val stale = File(dir, "cover.jpg.old.tmp").apply {
            writeBytes(ByteArray(10))
            setLastModified(4_000_000L)
        }
        val fresh = File(dir, "cover.jpg.new.tmp").apply { writeBytes(ByteArray(10)) }
        val now = 4_000_000L + ZlibCoverDiskCache.STALE_TMP_MS + 1

        ZlibCoverDiskCache.prune(
            dir,
            maxBytes = 1_000_000L,
            maxFiles = 100,
            nowMs = now,
        )

        assertFalse("上次进程被杀留下的 tmp 应清理", stale.exists())
        assertTrue("正在写的新 tmp 不能删", fresh.exists())
    }

    @Test
    fun concurrentWritesToSameKeyLeaveOneCompleteFile() {
        val target = ZlibCoverDiskCache.file(dir, "book1")
        val ones = ByteArray(8192) { 1 }
        val twos = ByteArray(8192) { 2 }
        val errors = java.util.Collections.synchronizedList(ArrayList<Throwable>())
        val failedWrites = java.util.concurrent.atomic.AtomicInteger()
        val threads = (0 until 4).map { index ->
            Thread {
                repeat(25) {
                    try {
                        if (!ZlibCoverDiskCache.write(dir, target, if (index % 2 == 0) ones else twos)) {
                            failedWrites.incrementAndGet()
                        }
                    } catch (throwable: Throwable) {
                        errors.add(throwable)
                    }
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertTrue("并发写不能抛异常: " + errors, errors.isEmpty())
        println("concurrent write transient failures=" + failedWrites.get())

        val written = target.readBytes()
        assertEquals(8192, written.size)
        assertTrue(
            "同 key 并发写后目标文件必须完整（全 1 或全 2，不能混写）",
            written.all { it == 1.toByte() } || written.all { it == 2.toByte() },
        )
        assertTrue(
            "并发写不能留 .tmp",
            dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") },
        )
    }
}
