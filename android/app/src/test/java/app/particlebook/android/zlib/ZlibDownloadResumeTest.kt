package app.particlebook.android.zlib

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ZlibDownloadResumeTest {

    @Test
    fun contentRangeParsing() {
        assertEquals(500L to 1_500L, ZlibResumePolicy.contentRange("bytes 500-1499/1500"))
        assertEquals(0L to null, ZlibResumePolicy.contentRange("bytes 0-1499/*"))
        assertNull(ZlibResumePolicy.contentRange(null))
        assertNull(ZlibResumePolicy.contentRange("garbage"))
    }

    @Test
    fun serverHonorsRangeAppends() {
        val decision = ZlibResumePolicy.decide(
            partLength = 500L,
            status = 206,
            contentRange = "bytes 500-1499/1500",
            contentLength = 1_000L,
        )
        assertEquals(ZlibResumePolicy.Decision(append = true, offset = 500L, total = 1_500L), decision)
    }

    @Test
    fun serverIgnoresRangeRestartsFromZero() {
        // 服务端不支持 Range：回 200 + 全量 body，必须截断重写，避免拼出损坏文件。
        val decision = ZlibResumePolicy.decide(
            partLength = 500L,
            status = 200,
            contentRange = null,
            contentLength = 1_500L,
        )
        assertEquals(ZlibResumePolicy.Decision(append = false, offset = 0L, total = 1_500L), decision)
    }

    @Test
    fun mismatchedContentRangeRestartsFromZero() {
        // 服务端回了别的区间（不是从 .part 末尾开始）→ 不追加。
        val decision = ZlibResumePolicy.decide(
            partLength = 500L,
            status = 206,
            contentRange = "bytes 0-1499/1500",
            contentLength = 1_500L,
        )
        assertEquals(ZlibResumePolicy.Decision(append = false, offset = 0L, total = 1_500L), decision)
    }

    @Test
    fun cancelFlagBeforeStartThrowsCancelledWithoutNetwork() {
        val client = ZlibClient()
        val dir = Files.createTempDirectory("zlib-cancel").toFile()
        val dest = File(dir, "book.pdf")
        val cancelFlag = AtomicBoolean(true)
        try {
            client.download("/dl/AX1jDr5bOm", dest, { _, _ -> }, cancelFlag)
            fail("expected ZlibException(CANCELLED)")
        } catch (e: ZlibException) {
            assertEquals(ZlibException.Kind.CANCELLED, e.kind)
            assertEquals("cancelled", e.message)
        }
        assertFalse("dest file must not exist", dest.exists())
        assertFalse("cancel must not leave .part", File(dir, "book.pdf.part").exists())
        assertFalse("no network should be attempted (no .part written)", dir.listFiles().orEmpty().any { it.name.endsWith(".part") })
        dir.deleteRecursively()
    }
}
