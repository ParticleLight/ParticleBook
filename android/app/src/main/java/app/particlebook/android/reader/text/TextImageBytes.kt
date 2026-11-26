package app.particlebook.android.reader.text

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * 有界读取原图；文件分支为阻塞 IO，统一由 IO 线程调用。
 *
 * 保存用 [read] 取得独立快照，不转码、不缩放。显示内嵌图时直接借用 [TextImage.bytes]
 * 解码，不经过 [read]，避免每次解码多复制最多 8 MiB；只有文件图走 [readFile]。
 *
 * bytes 非 null 时优先，即使为空或超限也不回退 path，以免保存成另一张图。
 * 空、超限、缺失、目录或读盘异常返回 null。
 */
internal object TextImageBytes {

    /** 读盘缓冲：64 KB 一次，比默认 8 KB 少几次系统调用，又不会为一张小图预分配整块上限。 */
    private const val READ_CHUNK_BYTES = 64 * 1024

    /** 保存 / 导出专用：返回独立原图快照；显示内嵌图应直接借用原数组。 */
    fun read(image: TextImage): ByteArray? {
        val embedded = image.bytes
        if (embedded != null) {
            if (embedded.isEmpty() || embedded.size > TextImage.MAX_BYTES) return null
            return embedded.copyOf()
        }
        return readFile(image.path)
    }

    /**
     * 返回新读出的原图数组，供文件图解码或保存使用；不修改源文件。
     * 先检查 length，再最多读取 MAX_BYTES + 1 字节并重新校验，防止文件在检查后增长。
     * 路径为空、缺失、目录、读失败、空文件或超限时返回 null。
     */
    fun readFile(path: String?): ByteArray? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!runCatching { file.isFile }.getOrDefault(false)) return null
        val declaredLength = runCatching { file.length() }.getOrDefault(-1L)
        if (declaredLength <= 0L || declaredLength > TextImage.MAX_BYTES.toLong()) return null
        return runCatching { FileInputStream(file).use { readBounded(it) } }.getOrNull()
    }

    /**
     * 最多读 [TextImage.MAX_BYTES] + 1 字节：多读的 1 字节只用来判断「文件比声明的更长」，
     * 不进入返回结果。0 字节或超限返回 null。
     */
    private fun readBounded(input: InputStream): ByteArray? {
        val limit = TextImage.MAX_BYTES + 1
        val out = ByteArrayOutputStream(READ_CHUNK_BYTES)
        val buffer = ByteArray(READ_CHUNK_BYTES)
        var total = 0
        while (total < limit) {
            val want = minOf(buffer.size, limit - total)
            val read = input.read(buffer, 0, want)
            // read == 0 只可能出现在非阻塞流上；普通文件读到 0 说明没有进展，就此收手，
            // 免得在异常流上死循环。
            if (read <= 0) break
            out.write(buffer, 0, read)
            total += read
        }
        if (total <= 0 || total > TextImage.MAX_BYTES) return null
        return out.toByteArray()
    }
}
