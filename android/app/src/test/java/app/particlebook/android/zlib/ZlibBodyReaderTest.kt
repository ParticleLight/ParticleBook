package app.particlebook.android.zlib

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** task-51 D11：响应体读取必须有上限。 */
class ZlibBodyReaderTest {

    @Test
    fun readsWithinLimit() {
        val body = "hello world".toByteArray()
        assertEquals(
            "hello world",
            ZlibBodyReader.read(ByteArrayInputStream(body), 1_000L, Charsets.UTF_8),
        )
    }

    @Test
    fun throwsHttpWhenOverLimit() {
        val body = ByteArray(4_096) { 'x'.code.toByte() }
        try {
            ZlibBodyReader.read(ByteArrayInputStream(body), 1_024L, Charsets.UTF_8)
            fail("expected ZlibException(HTTP)")
        } catch (e: ZlibException) {
            assertEquals(ZlibException.Kind.HTTP, e.kind)
        }
    }
}
