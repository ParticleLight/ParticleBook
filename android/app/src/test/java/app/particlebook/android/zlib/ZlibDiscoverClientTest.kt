package app.particlebook.android.zlib

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * P1.5 客户端接线：fetchBooklists / fetchBooklist / fetchPopular / fetchCategoryPage 走
 * 「镜像 + 挑战/代理/cookie 链路」发**站内相对路径**，用本机 HTTP 桩验证 URL 拼接与解析结果。
 *
 * 夹具直接复用 test/resources/zlib/ 的真实页面。
 */
class ZlibDiscoverClientTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("zlib/$name")!!.use {
            it.readBytes().toString(Charsets.UTF_8)
        }

    @Test(timeout = 60_000)
    fun fetchBooklistsParsesRealPageThroughMirror() {
        val routes = mapOf("/booklists" to fixture("booklists.html"))
        MockDiscoverServer(routes).use { server ->
            val client = ZlibClient()
            client.testLogger = { }
            client.mirror = server.baseUrl

            val page = client.fetchBooklists()

            assertEquals(13, page.booklists.size)
            assertEquals("305827", page.booklists.first().id)
            assertTrue(server.paths().contains("/booklists"))
        }
    }

    @Test(timeout = 60_000)
    fun fetchCategoryPageUsesNumericIdAndPageParam() {
        val routes = mapOf("/category/10/Computers?page=2" to fixture("category-10.html"))
        MockDiscoverServer(routes).use { server ->
            val client = ZlibClient()
            client.testLogger = { }
            client.mirror = server.baseUrl

            val page = client.fetchCategoryPage("10", "Computers", 2)

            assertEquals(2, page.page)
            assertEquals(50, page.books.size)
            assertEquals(3, page.totalPages)
            assertTrue("实际请求=" + server.paths(), server.paths().contains("/category/10/Computers?page=2"))
        }
    }

    @Test
    fun rejectsBadBooklistHrefAndCategoryIdWithoutNetwork() {
        val client = ZlibClient()
        try {
            // 绝对 URL 只保留 path（外部主机被剥掉）；非 /booklist/ 路径直接拒绝，不发网络。
            client.fetchBooklist("https://evil.example.com/admin/secret")
            fail("expected ZlibException(PARSE)")
        } catch (e: ZlibException) {
            assertEquals(ZlibException.Kind.PARSE, e.kind)
        }
        try {
            client.fetchCategoryPage("10; DROP TABLE", "Computers")
            fail("expected ZlibException(PARSE)")
        } catch (e: ZlibException) {
            assertEquals(ZlibException.Kind.PARSE, e.kind)
        }
    }
}

/** 极简 HTTP 桩：按 path(+query) 返回预置 HTML；未命中返回 404。 */
private class MockDiscoverServer(private val routes: Map<String, String>) : AutoCloseable {

    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val recorded = Collections.synchronizedList(ArrayList<String>())
    private var acceptor: Thread? = null

    val baseUrl: String get() = "http://127.0.0.1:" + socket.localPort

    fun paths(): List<String> = synchronized(recorded) { ArrayList(recorded) }

    init {
        acceptor = thread(isDaemon = true, name = "mock-discover-server") {
            try {
                while (true) {
                    val conn = socket.accept()
                    thread(isDaemon = true) { serve(conn) }
                }
            } catch (e: IOException) {
                // 测试结束关闭 ServerSocket。
            }
        }
    }

    override fun close() {
        runCatching { socket.close() }
        acceptor?.interrupt()
    }

    private fun serve(conn: Socket) {
        try {
            conn.soTimeout = 5_000
            val reader = conn.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val output = conn.getOutputStream()
            val requestLine = reader.readLine() ?: return
            val method = requestLine.substringBefore(' ')
            val path = requestLine.substringAfter(' ').substringBefore(' ')
            while (true) {
                val header = reader.readLine() ?: break
                if (header.isEmpty()) break
            }
            recorded.add(path)
            val body = routes[path]
            if (method == "HEAD") {
                output.write(headers(if (body != null) 200 else 404, body?.toByteArray(Charsets.UTF_8)?.size ?: 0).toByteArray(Charsets.ISO_8859_1))
                output.flush()
                return
            }
            if (body == null) {
                output.write(headers(404, 0).toByteArray(Charsets.ISO_8859_1))
            } else {
                val bytes = body.toByteArray(Charsets.UTF_8)
                output.write(headers(200, bytes.size).toByteArray(Charsets.ISO_8859_1))
                output.write(bytes)
            }
            output.flush()
        } catch (e: IOException) {
            // 客户端断开是预期行为。
        } finally {
            runCatching { conn.close() }
        }
    }

    private fun headers(status: Int, contentLength: Int): String {
        val reason = if (status == 200) "OK" else "Not Found"
        return "HTTP/1.1 " + status + " " + reason + "\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: " + contentLength + "\r\n" +
            "Connection: close\r\n\r\n"
    }
}
