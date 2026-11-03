package app.particlebook.android.zlib

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * task-51 D21/D5：/dl 候选直链数量上限 + 外部地址门禁。
 *
 * 本机 127.0.0.1 极简 HTTP 桩：/dl/mock 返回 10 个候选链接，每个候选 HEAD/GET 都回 HTML
 * （不是书）→ 状态机只能探测前 MAX_DL_CANDIDATES(5) 个，随后抛 PARSE 且消息带 probed=5。
 */
class ZlibDownloadCandidatesTest {

    private fun clientFor(port: Int): ZlibClient {
        val client = ZlibClient()
        client.testLogger = { } // JVM 单测没有 android.util.Log
        client.mirror = "http://127.0.0.1:" + port
        return client
    }

    @Test(timeout = 60_000)
    fun candidateListProbesAtMostFive() {
        val serverSocket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val acceptor = thread(isDaemon = true, name = "mock-candidates-server") {
            try {
                while (true) {
                    val socket = serverSocket.accept()
                    thread(isDaemon = true) { serveCandidates(socket) }
                }
            } catch (e: IOException) {
                // 测试结束关闭 ServerSocket。
            }
        }
        val client = clientFor(serverSocket.localPort)
        try {
            try {
                client.resolveDownload("/dl/mock")
                fail("expected ZlibException(PARSE)")
            } catch (e: ZlibException) {
                assertEquals(ZlibException.Kind.PARSE, e.kind)
                val message = e.message ?: ""
                assertTrue("candidates 必须在上限处停止，实际: " + message, message.contains("probed=5"))
            }
        } finally {
            runCatching { serverSocket.close() }
            acceptor.interrupt()
        }
    }

    @Test
    fun foreignAbsoluteDownloadPathIsRejectedWithoutNetwork() {
        // mirror 保留默认（不联网也能构造），入口校验应在建立连接前抛 DOWNLOAD。
        val client = ZlibClient()
        client.testLogger = { }
        client.mirror = "https://z-lib.by"
        try {
            client.resolveDownload("https://evil.example/dl/x")
            fail("expected ZlibException(DOWNLOAD)")
        } catch (e: ZlibException) {
            assertEquals(ZlibException.Kind.DOWNLOAD, e.kind)
        }
    }
}

/** 10 个候选的 /dl 页面；每个候选都回 HTML（不可下载）。 */
private fun serveCandidates(socket: Socket) {
    try {
        socket.soTimeout = 5_000
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val reader = input.bufferedReader(Charsets.ISO_8859_1)
        val requestLine = reader.readLine() ?: return
        while (true) {
            val header = reader.readLine() ?: break
            if (header.isEmpty()) break
        }
        val path = requestLine.split(' ').getOrNull(1).orEmpty()
        if (path.startsWith("/dl/")) {
            val links = (1..10).joinToString("\n") {
                "<a class=\"btn dlButton\" href=\"/download/$it\">Download $it</a>"
            }
            val body = "<html><body>$links</body></html>".toByteArray(Charsets.UTF_8)
            output.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: " + body.size + "\r\nConnection: close\r\n\r\n")
                .toByteArray(Charsets.ISO_8859_1))
            output.write(body)
        } else {
            val body = "<html><body>not a book</body></html>".toByteArray(Charsets.UTF_8)
            output.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: " + body.size + "\r\nConnection: close\r\n\r\n")
                .toByteArray(Charsets.ISO_8859_1))
            output.write(body)
        }
        output.flush()
    } catch (e: IOException) {
        // 预期：客户端探测完断开。
    } finally {
        runCatching { socket.close() }
    }
}
