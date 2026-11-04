package app.particlebook.android.zlib

import java.io.Closeable
import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T31：详情页 204 / 空 body 必须归为明确错误。
 *
 * realnet task-26 实测：部分 /book/<hash> 返回 204 空响应；若按普通解析失败处理，UI 会得到
 * 含糊提示甚至空白详情。这里用回环桩覆盖 204 与 200 空 body 两条路径。
 */
class ZlibDetailResponseTest {

    /** 固定状态码 + body 的回环 HTTP 桩（204 不写 body）。 */
    private class StubHttpServer(
        private val status: Int,
        private val body: String,
    ) : Closeable {
        private val server = ServerSocket(0)
        val baseUrl: String = "http://127.0.0.1:" + server.localPort
        private val thread = Thread {
            try {
                server.soTimeout = 5_000
                while (!server.isClosed) {
                    val socket = try {
                        server.accept()
                    } catch (t: Throwable) {
                        break
                    }
                    socket.use {
                        val reader = it.getInputStream().bufferedReader(Charsets.UTF_8)
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        val reason = when (status) {
                            200 -> "OK"
                            204 -> "No Content"
                            else -> "ERROR"
                        }
                        val head = "HTTP/1.1 " + status + " " + reason + "\r\n" +
                            "Content-Type: text/html; charset=utf-8" + "\r\n" +
                            "Content-Length: " + bytes.size + "\r\n" +
                            "Connection: close" + "\r\n\r\n"
                        it.getOutputStream().write(head.toByteArray(Charsets.UTF_8))
                        if (status != 204 && bytes.isNotEmpty()) {
                            it.getOutputStream().write(bytes)
                        }
                        it.getOutputStream().flush()
                    }
                }
            } catch (_: Throwable) {
                // close() 中断 accept：正常收尾。
            }
        }

        init {
            thread.isDaemon = true
            thread.start()
        }

        override fun close() {
            runCatching { server.close() }
        }
    }

    private fun clientFor(baseUrl: String): ZlibClient = ZlibClient().also {
        it.mirror = baseUrl
        it.testLogger = { }
        it.navFetcherForTest = { null }
    }

    @Test
    fun detail204IsExplicitHttpError() {
        StubHttpServer(204, "").use { server ->
            try {
                clientFor(server.baseUrl).fetchDetail("d5eARxJrQ5")
                throw AssertionError("204 必须抛错，不能返回空白详情")
            } catch (e: ZlibException) {
                assertEquals("204 是 HTTP 层明确错误", ZlibException.Kind.HTTP, e.kind)
                assertEquals(204, e.httpCode)
                assertTrue("文案应带状态码: " + e.message, e.message!!.contains("204"))
                assertTrue("文案应为中文且点明空响应: " + e.message, e.message!!.contains("空响应"))
            }
        }
    }

    @Test
    fun detailEmpty200IsExplicitParseError() {
        StubHttpServer(200, "").use { server ->
            try {
                clientFor(server.baseUrl).fetchDetail("ky860RNPro")
                throw AssertionError("200 空 body 必须抛错，不能渲染空白详情")
            } catch (e: ZlibException) {
                assertEquals("空 body 属页面结构问题", ZlibException.Kind.PARSE, e.kind)
                assertTrue("文案应点明空响应: " + e.message, e.message!!.contains("空响应"))
                assertTrue("文案应为中文: " + e.message, e.message!!.contains("请切换线路"))
            }
        }
    }
}
