package app.particlebook.android.zlib

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset

/**
 * 有上限的响应体读取（task-51 D11）：任何 HTML/JSON 响应都不许无上限读进内存
 * （R7-G 的教训：/dl 302 被跟随会把整本书当 HTML 读成 String）。
 *
 * 纯 JVM，便于单测「超长 body 抛 HTTP」。
 */
internal object ZlibBodyReader {

    fun read(input: InputStream, maxBytes: Long, charset: Charset): String {
        val out = ByteArrayOutputStream(64 * 1024)
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                throw ZlibException(
                    "response too large (> " + maxBytes + " bytes)",
                    ZlibException.Kind.HTTP,
                )
            }
            out.write(buffer, 0, read)
        }
        return String(out.toByteArray(), charset)
    }
}
