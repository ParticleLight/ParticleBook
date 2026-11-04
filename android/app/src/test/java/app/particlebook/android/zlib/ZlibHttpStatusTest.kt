package app.particlebook.android.zlib

import java.io.Closeable
import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T37：服务端返回 4xx/5xx 时必须归为 HTTP kind 并带真实状态码。
 *
 * 真机缺陷：500 时日志 status=500，但 UI 显示「网络连接失败…错误类型：NETWORK」，
 * 应该显示「服务器返回错误 500」。根因是抛出点把 5xx 归到了 NETWORK/PARSE。
 * 这里用回环桩覆盖发现页 / 分类页 / 详情页三条 HTTP 抛出路径，并回归 403。
 */
class ZlibHttpStatusTest {

    /** 固定状态码 + body 的回环 HTTP 桩。 */
    private class StubHttpServer(
        private val status: Int,
        private val body: String = "",
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
                            403 -> "Forbidden"
                            500 -> "Internal Server Error"
                            502 -> "Bad Gateway"
                            503 -> "Service Unavailable"
                            else -> "ERROR"
                        }
                        val head = "HTTP/1.1 " + status + " " + reason + "\r\n" +
                            "Content-Type: text/html; charset=utf-8" + "\r\n" +
                            "Content-Length: " + bytes.size + "\r\n" +
                            "Connection: close" + "\r\n\r\n"
                        it.getOutputStream().write(head.toByteArray(Charsets.UTF_8))
                        if (bytes.isNotEmpty()) it.getOutputStream().write(bytes)
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

    private fun assertHttpError(e: ZlibException, expectedCode: Int) {
        assertEquals("4xx/5xx 必须归 HTTP kind（不能是 NETWORK/PARSE）", ZlibException.Kind.HTTP, e.kind)
        assertEquals("httpCode 必须是真实状态码", expectedCode, e.httpCode ?: -1)
        val text = ZlibErrorMessages.from(e)
        assertTrue("UI 文案应带状态码: " + text, text.contains("服务器返回错误 " + expectedCode))
        assertTrue("不能显示网络失败文案: " + text, !text.contains("网络异常") && !text.contains("网络连接失败"))
    }

    @Test
    fun discoverServerErrorsAreHttpWithCode() {
        for (status in intArrayOf(500, 502, 503)) {
            StubHttpServer(status).use { server ->
                try {
                    clientFor(server.baseUrl).fetchPopular()
                    throw AssertionError("HTTP " + status + " 必须抛错")
                } catch (e: ZlibException) {
                    assertHttpError(e, status)
                }
            }
        }
    }

    @Test
    fun categoryServerErrorsAreHttpWithCode() {
        for (status in intArrayOf(500, 502, 503)) {
            StubHttpServer(status).use { server ->
                try {
                    clientFor(server.baseUrl).fetchCategory("/popular")
                    throw AssertionError("HTTP " + status + " 必须抛错")
                } catch (e: ZlibException) {
                    assertHttpError(e, status)
                }
            }
        }
    }

    @Test
    fun detailServerErrorsAreHttpWithCode() {
        for (status in intArrayOf(500, 502, 503)) {
            StubHttpServer(status).use { server ->
                try {
                    clientFor(server.baseUrl).fetchDetail("d5eARxJrQ5")
                    throw AssertionError("HTTP " + status + " 必须抛错")
                } catch (e: ZlibException) {
                    assertHttpError(e, status)
                }
            }
        }
    }

    /** 403 原本就正确，回归防止本次改动把它归到别的 kind。 */
    @Test
    fun forbiddenStillHttpWith403() {
        StubHttpServer(403).use { server ->
            try {
                clientFor(server.baseUrl).fetchPopular()
                throw AssertionError("403 必须抛错")
            } catch (e: ZlibException) {
                assertHttpError(e, 403)
            }
        }
    }
}
