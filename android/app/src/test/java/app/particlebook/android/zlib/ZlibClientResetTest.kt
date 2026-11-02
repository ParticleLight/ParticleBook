package app.particlebook.android.zlib

import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ZlibClient.resetConnectionState] 是纯内存操作（不发请求、不读 Settings），
 * 因此可以直接在 JVM 单测里验证：可构造、可重复调用、不破坏 mirror 缓存。
 */
class ZlibClientResetTest {

    @Test
    fun resetConnectionStateIsIdempotent() {
        val client = ZlibClient(null)
        client.resetConnectionState()
        client.resetConnectionState()
        assertNull(client.mirror)
    }

    @Test
    fun resetConnectionStateKeepsMirrorCache() {
        val client = ZlibClient(null)
        client.mirror = "https://z-lib.by"
        client.resetConnectionState()
        // 只清代理/探测临时态，不动「上次成功镜像」（prefs 里的缓存也不应被删）。
        org.junit.Assert.assertEquals("https://z-lib.by", client.mirror)
    }
}
