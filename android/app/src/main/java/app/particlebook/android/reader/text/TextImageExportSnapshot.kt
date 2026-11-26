package app.particlebook.android.reader.text

import java.io.File
import java.io.IOException

/**
 * 系统文件选择器可以跨 Activity 重建；原图留在私有缓存，saved state 只携带 [stage] 返回的 token。
 * 每次保存独占一份快照，切图不会改掉正在导出的内容。所有方法由 IO 线程调用。
 */
internal class TextImageExportSnapshot(private val directory: File) {
    /** 写完整后才交出 token；失败删除本次临时文件并抛出 IO 异常。 */
    fun stage(payload: TextImageExportPayload): String {
        require(payload.bytes.isNotEmpty() && payload.bytes.size <= TextImage.MAX_BYTES)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Image export cache is not available")
        }
        val file = File.createTempFile("image-", PART_SUFFIX, directory)
        val ready = File(directory, file.name.removeSuffix(PART_SUFFIX) + BIN_SUFFIX)
        try {
            file.outputStream().use { it.write(payload.bytes) }
            if (file.length() != payload.bytes.size.toLong() || ready.exists() || !file.renameTo(ready)) {
                throw IOException("Image export snapshot was not committed")
            }
        } catch (failure: Exception) {
            file.delete()
            throw failure
        }
        return ready.name
    }

    /** 缓存被系统清理、token 无效或快照超过大小限制时返回 null。 */
    fun read(token: String): ByteArray? = fileFor(token)?.let { TextImageBytes.readFile(it.path) }

    fun discard(token: String) {
        runCatching { fileFor(token)?.delete() }
    }

    /** 回收进程被终止后留下的过期快照；当前选择器持有的 token 始终保留。 */
    fun discardExpired(keepToken: String?, nowMillis: Long = System.currentTimeMillis()) {
        val files = runCatching { directory.listFiles() }.getOrNull() ?: return
        val cutoff = nowMillis - MAX_AGE_MILLIS
        for (file in files) {
            if (file.name != keepToken && isSnapshotName(file.name) && file.isFile && file.lastModified() <= cutoff) {
                runCatching { file.delete() }
            }
        }
    }

    private fun isSnapshotName(name: String): Boolean =
        TOKEN.matches(name) ||
            (name.endsWith(PART_SUFFIX) && TOKEN.matches(name.removeSuffix(PART_SUFFIX) + BIN_SUFFIX))

    private fun fileFor(token: String): File? = if (TOKEN.matches(token)) File(directory, token) else null

    private companion object {
        const val PART_SUFFIX = ".part"
        const val BIN_SUFFIX = ".bin"
        const val MAX_AGE_MILLIS = 24L * 60L * 60L * 1_000L
        val TOKEN = Regex("image-[a-zA-Z0-9-]+\\.bin")
    }
}
