package app.particlebook.android.zlib

import java.io.File

/**
 * `.part` 的续传校验元数据（P1c 收尾④，审计 A1）。
 *
 * 只发 `Range` 不校验版本时，「文件换了但长度一样」会拼出**长度正确、内容损坏**的书。
 * 这里把服务端的 `ETag` / `Last-Modified` / 总长 / host 存到 `<name>.part.meta`，
 * 下次续传带 `If-Range: <validator>`；服务端发现版本变了会回 200（而不是 206），
 * 现有 [ZlibResumePolicy.decide] 已按 200 截断重下。
 */
internal data class ZlibPartMeta(
    val etag: String? = null,
    val lastModified: String? = null,
    val total: Long = -1L,
    val host: String? = null,
) {

    /** If-Range 值：ETag 优先（强校验），其次 Last-Modified；都没有则不带。 */
    val validator: String? get() =
        etag?.takeIf { it.isNotBlank() } ?: lastModified?.takeIf { it.isNotBlank() }
}

/** `.part.meta` 的读写（纯 JVM，无 Android 依赖；行式 `key=value`，转义换行/反斜杠）。 */
internal object ZlibPartMetaStore {

    fun metaFile(partFile: File): File =
        File(partFile.absoluteFile.parentFile ?: File("."), partFile.name + ".meta")

    fun read(partFile: File): ZlibPartMeta? = try {
        val file = metaFile(partFile)
        if (!file.isFile) null else parse(file.readText(Charsets.UTF_8))
    } catch (e: Exception) {
        null
    }

    fun write(partFile: File, meta: ZlibPartMeta) {
        try {
            metaFile(partFile).writeText(serialize(meta), Charsets.UTF_8)
        } catch (e: Exception) {
            // 元数据只是优化：写不进去不影响下载本身。
        }
    }

    fun delete(partFile: File) {
        try {
            metaFile(partFile).delete()
        } catch (e: Exception) {
            // ignore
        }
    }

    fun serialize(meta: ZlibPartMeta): String = listOf(
        "etag=" + escape(meta.etag),
        "last_modified=" + escape(meta.lastModified),
        "total=" + meta.total,
        "host=" + escape(meta.host),
    ).joinToString("\n")

    fun parse(text: String): ZlibPartMeta {
        val values = HashMap<String, String>()
        text.lineSequence().forEach { line ->
            val index = line.indexOf('=')
            if (index > 0) values[line.substring(0, index).trim()] = line.substring(index + 1)
        }
        val total = values["total"]?.toLongOrNull() ?: -1L
        return ZlibPartMeta(
            etag = unescape(values["etag"]),
            lastModified = unescape(values["last_modified"]),
            total = total,
            host = unescape(values["host"]),
        )
    }

    private fun escape(value: String?): String = when {
        value.isNullOrEmpty() -> ""
        else -> value.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n")
    }

    private fun unescape(value: String?): String? {
        if (value.isNullOrEmpty()) return null
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val c = value[index]
            if (c == '\\' && index + 1 < value.length) {
                when (value[index + 1]) {
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    '\\' -> out.append('\\')
                    else -> out.append(value[index + 1])
                }
                index += 2
            } else {
                out.append(c)
                index++
            }
        }
        return out.toString()
    }
}
