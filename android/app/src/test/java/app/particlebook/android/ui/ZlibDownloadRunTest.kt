package app.particlebook.android.ui

import app.particlebook.android.zlib.ZlibPartMeta
import app.particlebook.android.zlib.ZlibPartMetaStore
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** U3：取消控制块与世代注册表（纯 JVM）。 */
class ZlibDownloadRunTest {

    @Test
    fun cancelKeepsFirstReasonAndSetsFlag() {
        val run = ZlibDownloadRun(taskId = "zlib:b1", generation = 1L)
        assertFalse(run.cancelFlag.get())
        assertTrue(run.cancel(ZlibCancelReason.USER))
        assertFalse("第二个取消不应覆盖原因", run.cancel(ZlibCancelReason.STALL))
        assertEquals(ZlibCancelReason.USER, run.cancelReason)
        assertTrue(run.cancelFlag.get())
    }

    @Test
    fun registryOnlyCurrentRunCanCancel() {
        val registry = ZlibDownloadRunRegistry()
        val first = registry.begin("t")
        val second = registry.begin("t")
        assertFalse("旧世代不是当前运行", registry.isCurrent(first))
        assertTrue(registry.isCurrent(second))
        assertTrue(registry.cancel("t", ZlibCancelReason.USER))
        assertTrue(second.cancelFlag.get())
        assertFalse("旧世代的 flag 不应被置位", first.cancelFlag.get())
        registry.release(second)
        assertNull(registry.current("t"))
        assertFalse("没有在跑的运行时取消返回 false", registry.cancel("t", ZlibCancelReason.USER))
    }

    @Test
    fun cancelKeepsPartialFileAndMeta() {
        val dir = Files.createTempDirectory("pb-part").toFile()
        try {
            val target = File(dir, "book.epub")
            val part = ZlibDownloadFiles.partFileOf(target)
            assertEquals("book.epub.part", part.name)
            part.writeBytes(ByteArray(1024))
            ZlibPartMetaStore.write(part, ZlibPartMeta(etag = "e1", total = 2048L, host = "dln1"))
            assertTrue("取消后应能续传（.part 存在）", ZlibDownloadFiles.hasPartial(target))
            // 只有用户点「删除」才清理半成品与续传元数据。
            ZlibDownloadFiles.deletePartial(target)
            assertFalse(part.exists())
            assertFalse(ZlibPartMetaStore.metaFile(part).exists())
            assertFalse(ZlibDownloadFiles.hasPartial(target))
            assertFalse("删除半成品不应动目标文件", target.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
