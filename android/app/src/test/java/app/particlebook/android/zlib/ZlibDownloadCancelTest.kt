package app.particlebook.android.zlib

import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * P1c 收尾①（审计 A2）：取消必须**保留** `.part`（以及 .part.meta），
 * 重新下载才能从已下字节续传；只有「删除下载」才清文件。
 *
 * 用本机 127.0.0.1 的极简 HTTP 桩做一次真实流式下载（不走设备/外网）：
 * 下到 >8KB 时置 cancelFlag，断言抛 CANCELLED 且 .part 仍在、长度>0、目标文件不存在。
 */
class ZlibDownloadCancelTest {

    @Test(timeout = 60_000)
    fun cancelMidStreamKeepsResumablePart() {
        val serverSocket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val acceptor = thread(isDaemon = true, name = "mock-dl-server") {
            try {
                while (true) {
                    val socket = serverSocket.accept()
                    thread(isDaemon = true) { serveMockDownload(socket) }
                }
            } catch (e: IOException) {
                // 测试结束关闭 ServerSocket。
            }
        }
        val client = ZlibClient()
        client.testLogger = { } // JVM 单测没有 android.util.Log
        client.mirror = "http://127.0.0.1:" + serverSocket.localPort
        val dir = Files.createTempDirectory("zlib-cancel-mid").toFile()
        val dest = File(dir, "book.pdf")
        val part = File(dir, "book.pdf.part")
        val cancelFlag = AtomicBoolean(false)
        try {
            try {
                client.download(
                    "/dl/mock",
                    dest,
                    { received, _ -> if (received >= 8L * 1024) cancelFlag.set(true) },
                    cancelFlag,
                )
                fail("expected ZlibException(CANCELLED)")
            } catch (e: ZlibException) {
                assertEquals(ZlibException.Kind.CANCELLED, e.kind)
            }
            assertTrue("取消后 .part 必须保留（可续传）", part.isFile)
            assertTrue("取消后 .part 应有已下字节，实际=" + part.length(), part.length() > 0L)
            assertFalse("取消不得产出正式文件", dest.exists())
            assertTrue("续传元数据应存在", ZlibPartMetaStore.metaFile(part).isFile)
        } finally {
            runCatching { serverSocket.close() }
            acceptor.interrupt()
            dir.deleteRecursively()
        }
    }

    @Test
    fun cancelBeforeStartCreatesNoPart() {
        val client = ZlibClient()
        val dir = Files.createTempDirectory("zlib-cancel-early").toFile()
        val dest = File(dir, "book.pdf")
        try {
            try {
                client.download("/dl/mock", dest, { _, _ -> }, AtomicBoolean(true))
                fail("expected ZlibException(CANCELLED)")
            } catch (e: ZlibException) {
                assertEquals(ZlibException.Kind.CANCELLED, e.kind)
            }
            assertFalse(dest.exists())
            assertFalse(File(dir, "book.pdf.part").exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}

private const val MOCK_TOTAL_BYTES = 1_048_576

/** 极简 HTTP/1.1 桩：固定长度 octet-stream，每 8KB 慢速发送，模拟可取消的流式下载。 */
private fun serveMockDownload(socket: Socket) {
    try {
        socket.soTimeout = 5_000
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val reader = input.bufferedReader(Charsets.ISO_8859_1)
        val requestLine = reader.readLine() ?: return
        val method = requestLine.substringBefore(' ')
        while (true) {
            val header = reader.readLine() ?: break
            if (header.isEmpty()) break
        }
        if (method == "HEAD") {
            output.write(mockHeaders(0).toByteArray(Charsets.ISO_8859_1))
            output.flush()
            return
        }
        output.write(mockHeaders(MOCK_TOTAL_BYTES).toByteArray(Charsets.ISO_8859_1))
        output.flush()
        val chunk = ByteArray(8 * 1024) { 'A'.code.toByte() }
        var written = 0
        while (written < MOCK_TOTAL_BYTES) {
            output.write(chunk)
            output.flush()
            written += chunk.size
            Thread.sleep(80)
        }
    } catch (e: IOException) {
        // 客户端取消/断连是预期行为。
    } catch (e: InterruptedException) {
        // 测试结束。
    } finally {
        runCatching { socket.close() }
    }
}

private fun mockHeaders(contentLength: Int): String =
    "HTTP/1.1 200 OK\r\n" +
        "Content-Type: application/octet-stream\r\n" +
        "Content-Length: " + contentLength + "\r\n" +
        "Connection: close\r\n\r\n"
