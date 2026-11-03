package app.particlebook.android.zlib

import org.junit.Assert.assertNull
import org.junit.Test

/** P1c 收尾③：debug 下换 mock/换镜像后清缓存，避免旧地址粘住真实流程。 */
class ZlibClientMirrorTest {

    @Test
    fun clearCachedMirrorResetsMemoryAndPrefsCache() {
        val client = ZlibClient()
        client.mirror = "http://127.0.0.1:18080"
        client.clearCachedMirror()
        assertNull("内存 mirror 必须清空", client.mirror)
    }

    @Test
    fun clearCachedMirrorIsIdempotent() {
        val client = ZlibClient()
        client.clearCachedMirror()
        client.clearCachedMirror()
        assertNull(client.mirror)
    }
}
