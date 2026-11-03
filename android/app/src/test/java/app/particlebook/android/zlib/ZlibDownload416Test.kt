package app.particlebook.android.zlib

import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lead m02083：真实 CDN/中间层偶发「无 Range 请求也回 416」，线上会变成永久下载失败。
 *
 * 防御：streamOnce 遇到 416 只重试一次——
 *  - 本次带 Range（有 .part）→ 删半成品，打 `dl_416 retry=fresh`，重发无 Range 请求；
 *  - 本次没带 Range（全新下载）→ 保持 URL 直接重发无 Range 请求，打 `dl_416 retry=no_range`；
 *  - 第二次仍 416 → HTTP 失败。
 *
 * 用本机 127.0.0.1 极简 HTTP 桩验证真实请求序列（含 Range 头）。
 */
class ZlibDownload416Test {

    @Test(timeout = 60_000)
    fun freshDownloadRetriesOnceWithoutRange() {
        val server = Mock416Server(mode = Mock416Mode.FRESH)
        server.start()
        val client = ZlibClient()
        val logs = Collections.synchronizedList(ArrayList<String>())
        client.testLogger = { line -> logs.add(line) }
        client.mirror = server.baseUrl
        val dir = Files.createTempDirectory("zlib-416-fresh").toFile()
        val dest = File(dir, "book.pdf")
        try {
            val file = client.download("/dl/mock", dest, { _, _ -> })
            assertEquals("最终文件长度应为第二次完整响应的字节数", Mock416Server.BODY_SIZE.toLong(), file.length())
            assertTrue("文件内容应是第二次响应的全 B", file.readBytes().all { it == 'B'.code.toByte() })
            assertFalse("成功后不得残留 .part", File(dir, "book.pdf.part").exists())
            val requests = server.requests()
            assertEquals("应共有 3 次请求（解析 /dl + 416 + 重试成功），实际=" + requests.size, 3, requests.size)
            assertNull("全新下载第一次流式请求不带 Range", requests[1].range)
            assertNull("416 重试必须不带 Range", requests[2].range)
            assertTrue(
                "应记录 retry=no_range 日志，实际=" + logs,
                logs.any { it.contains("retry=no_range") },
            )
        } finally {
            server.close()
            dir.deleteRecursively()
        }
    }

    @Test(timeout = 60_000)
    fun rangedRetryDropsPartAndFetchesFresh() {
        val server = Mock416Server(mode = Mock416Mode.RESUME)
        server.start()
        val client = ZlibClient()
        val logs = Collections.synchronizedList(ArrayList<String>())
        client.testLogger = { line -> logs.add(line) }
        client.mirror = server.baseUrl
        val dir = Files.createTempDirectory("zlib-416-resume").toFile()
        val dest = File(dir, "book.pdf")
        val part = File(dir, "book.pdf.part")
        try {
            // 预置 512B 半成品 + 续传元数据 → 首请求必带 Range。
            part.writeBytes(ByteArray(512) { 'A'.code.toByte() })
            ZlibPartMetaStore.write(part, ZlibPartMeta(etag = "\"v1\"", lastModified = null, total = 2048L, host = "127.0.0.1"))

            val file = client.download("/dl/mock", dest, { _, _ -> })

            assertEquals("416 后应从头下完整文件", Mock416Server.BODY_SIZE.toLong(), file.length())
            assertTrue("内容应是全 B（不得拼接旧半成品）", file.readBytes().all { it == 'B'.code.toByte() })
            assertFalse("重试前应删掉 .part", part.exists())
            assertFalse("重试前应删掉 .part.meta", ZlibPartMetaStore.metaFile(part).exists())

            val requests = server.requests()
            assertEquals("应共有 3 次请求（解析 /dl + Range 416 + 无 Range 成功），实际=" + requests.size, 3, requests.size)
            assertEquals("第一次流式请求应带 Range=bytes=512-", "bytes=512-", requests[1].range)
            assertNull("416 重试必须去掉 Range", requests[2].range)
            assertTrue(
                "应记录 retry=fresh 日志，实际=" + logs,
                logs.any { it.contains("retry=fresh") },
            )
        } finally {
            server.close()
            dir.deleteRecursively()
        }
    }
}

internal enum class Mock416Mode { FRESH, RESUME }

internal class Mock416Server(private val mode: Mock416Mode) {

    data class RecordedRequest(val method: String, val path: String, val range: String?)

    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val counter = AtomicInteger(0)
    private val recorded = Collections.synchronizedList(ArrayList<RecordedRequest>())
    private var acceptor: Thread? = null

    val baseUrl: String get() = "http://127.0.0.1:" + socket.localPort

    fun start() {
        acceptor = thread(isDaemon = true, name = "mock-416-server") {
            try {
                while (true) {
                    val client = socket.accept()
                    thread(isDaemon = true) { serve(client) }
                }
            } catch (e: IOException) {
                // 测试结束关闭 ServerSocket。
            }
        }
    }

    fun requests(): List<RecordedRequest> = synchronized(recorded) { ArrayList(recorded) }

    fun close() {
        runCatching { socket.close() }
        acceptor?.interrupt()
    }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = 5_000
            val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val output = socket.getOutputStream()
            val requestLine = reader.readLine() ?: return
            val method = requestLine.substringBefore(' ')
            val path = requestLine.substringAfter(' ').substringBefore(' ')
            var range: String? = null
            while (true) {
                val header = reader.readLine() ?: break
                if (header.isEmpty()) break
                if (header.startsWith("Range:", ignoreCase = true)) {
                    range = header.substringAfter(':').trim()
                }
            }
            val index = counter.getAndIncrement()
            recorded.add(RecordedRequest(method, path, range))
            if (method == "HEAD") {
                output.write(headers(200, Mock416Server.BODY_SIZE).toByteArray(Charsets.ISO_8859_1))
                output.flush()
                return
            }
            when {
                index == 0 -> {
                    // 解析阶段：/dl 二进制直出（非 HTML）→ 直接把当前 URL 当直链，不读 body。
                    output.write(headers(200, 4).toByteArray(Charsets.ISO_8859_1))
                    output.write("ABCD".toByteArray(Charsets.ISO_8859_1))
                    output.flush()
                }
                index == 1 -> {
                    // 流式第一次：违规 416。
                    output.write(headers(416, 0).toByteArray(Charsets.ISO_8859_1))
                    output.flush()
                }
                else -> {
                    // 重试：完整 2048B。
                    output.write(headers(200, Mock416Server.BODY_SIZE).toByteArray(Charsets.ISO_8859_1))
                    output.write(ByteArray(Mock416Server.BODY_SIZE) { 'B'.code.toByte() })
                    output.flush()
                }
            }
        } catch (e: IOException) {
            // 客户端断开是预期行为。
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun headers(status: Int, contentLength: Int): String {
        val reason = if (status == 416) "Range Not Satisfiable" else "OK"
        return "HTTP/1.1 " + status + " " + reason + "\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "Content-Length: " + contentLength + "\r\n" +
            "Connection: close\r\n\r\n"
    }

    companion object {
        const val BODY_SIZE = 2048
    }
}
