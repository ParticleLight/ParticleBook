package app.particlebook.android.zlib

import java.io.Closeable
import java.net.ServerSocket
import app.particlebook.android.ui.ZlibUiError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                assertEquals("204 是书籍级确定性不可用，必须给可读文案", ZlibErrorMessages.BOOK_UNAVAILABLE, e.message)
                assertEquals(ZlibErrorMessages.BOOK_UNAVAILABLE, ZlibErrorMessages.from(e))
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

    /** 详情正文桩：looksLikeDetailPage 需要 details-book + book-title。 */
    private val detailBody = """
        <html><body><div class="details-book cardBooks" data-book_id="42">
        <h1 class="book-title">Retry OK</h1>
        <a class="btn btn-default dlButton addDownloadedBook" href="/dl/OK">pdf, 1 MB</a>
        </div></body></html>
    """.trimIndent()

    /** T37/task-33：首镜像 204 → 换一条镜像重试一次；第二条成功就正常返回详情。 */
    @Test
    fun detail204RetriesAlternateMirrorThenSucceeds() {
        StubHttpServer(204, "").use { first ->
            StubHttpServer(200, detailBody).use { second ->
                val client = clientFor(first.baseUrl)
                client.detailRetryMirrorForTest = { second.baseUrl }

                val detail = client.fetchDetail("d5eARxJrQ5")

                assertEquals("Retry OK", detail.title)
                assertEquals("/dl/OK", detail.downloadPath)
            }
        }
    }

    /**
     * T37/task-33：两条镜像都 204 → 书籍级确定性不可用，文案可读、不按网络错误。
     * 同时断言网络层与 UI 层共用同一文案，且确实发生了换线重试。
     */
    @Test
    fun detail204OnBothMirrorsIsReadableUnavailable() {
        StubHttpServer(204, "").use { first ->
            StubHttpServer(204, "").use { second ->
                val logs = ArrayList<String>()
                val client = ZlibClient().also {
                    it.mirror = first.baseUrl
                    it.testLogger = { line -> logs.add(line) }
                    it.navFetcherForTest = { null }
                    it.detailRetryMirrorForTest = { second.baseUrl }
                }
                try {
                    client.fetchDetail("d5eARxJrQ5")
                    throw AssertionError("两条镜像都 204 必须抛错，不能返回空白详情")
                } catch (e: ZlibException) {
                    assertEquals(ZlibException.Kind.HTTP, e.kind)
                    assertEquals(204, e.httpCode)
                    assertEquals(ZlibErrorMessages.BOOK_UNAVAILABLE, e.message)
                    val networkText = ZlibErrorMessages.from(e)
                    assertEquals(ZlibErrorMessages.BOOK_UNAVAILABLE, networkText)
                    assertFalse("不能显示网络错误文案: " + networkText, networkText.contains("网络"))
                    assertEquals(
                        "UI 文案必须与网络层一致",
                        ZlibErrorMessages.BOOK_UNAVAILABLE,
                        ZlibUiError.message(e),
                    )
                }
                assertTrue("必须换线重试一次: " + logs, logs.any { it.startsWith("detail_204_retry") })
            }
        }
    }
}
