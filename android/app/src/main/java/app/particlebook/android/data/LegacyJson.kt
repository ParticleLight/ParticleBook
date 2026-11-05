package app.particlebook.android.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 把 org.json 隔在接口后面：生产用 [OrgJsonParser]，纯 JVM 单测注入 [TestJson] 的最小实现。
 * 这样迁移逻辑可以在 `testDebugUnitTest` 里真实跑，而不是只做字符串断言。
 */
interface LegacyJsonParser {
    fun parseObject(raw: String): Map<String, Any?>
    fun parseArray(raw: String): List<Any?>
}

/** Android 生产实现；org.json 在 JVM 单测里是 “not mocked”，所以单测不直接用它。 */
class OrgJsonParser : LegacyJsonParser {
    override fun parseObject(raw: String): Map<String, Any?> =
        normalize(JSONObject(raw)) as Map<String, Any?>

    override fun parseArray(raw: String): List<Any?> =
        normalize(JSONArray(raw)) as List<Any?>

    private fun normalize(value: Any?): Any? {
        if (value == null || value === JSONObject.NULL) return null
        return when (value) {
            is JSONObject -> {
                val out = LinkedHashMap<String, Any?>()
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    out[key] = normalize(value.opt(key))
                }
                out
            }
            is JSONArray -> (0 until value.length()).map { index -> normalize(value.opt(index)) }
            else -> value
        }
    }
}

/** 读取 books.json 与下载历史 JSON；只做结构映射，不做文件 IO。 */
class LegacyLibraryReader(private val parser: LegacyJsonParser = OrgJsonParser()) {

    fun readBooks(raw: String): List<LegacyBook> {
        val root = if (raw.trimStart().startsWith("[")) {
            mapOf("books" to parser.parseArray(raw))
        } else {
            parser.parseObject(raw)
        }
        val array = root["books"] as? List<*> ?: throw IllegalArgumentException("books.json 缺少 books 数组")
        return array.mapNotNull { value -> mapBook(value as? Map<*, *> ?: return@mapNotNull null) }
    }

    fun readDownloads(raw: String): List<LegacyDownload> {
        val root = if (raw.trimStart().startsWith("[")) {
            mapOf("downloads" to parser.parseArray(raw))
        } else {
            parser.parseObject(raw)
        }
        val array = root["downloads"] as? List<*> ?: return emptyList()
        return array.mapNotNull { value ->
            mapDownload(value as? Map<*, *> ?: return@mapNotNull null)
        }
    }

    fun readProgress(prefs: Map<String, Any?>): List<LegacyProgress> {
        data class Acc(var page: Int? = null, var total: Int? = null, var lastRead: Long? = null)
        val acc = LinkedHashMap<Long, Acc>()
        for ((key, value) in prefs) {
            when {
                key.startsWith("page_") -> {
                    val bookId = key.removePrefix("page_").toLongOrNull() ?: continue
                    if (bookId <= 0) continue
                    val page = asInt(value) ?: continue
                    acc.getOrPut(bookId) { Acc() }.page = page
                }
                key.startsWith("pages_") -> {
                    val bookId = key.removePrefix("pages_").toLongOrNull() ?: continue
                    if (bookId <= 0) continue
                    val total = asInt(value) ?: continue
                    acc.getOrPut(bookId) { Acc() }.total = total
                }
                key.startsWith("last_read_") -> {
                    val bookId = key.removePrefix("last_read_").toLongOrNull() ?: continue
                    if (bookId <= 0) continue
                    val lastRead = asLong(value) ?: continue
                    acc.getOrPut(bookId) { Acc() }.lastRead = lastRead
                }
            }
        }
        return acc.map { (bookId, value) ->
            LegacyProgress(bookId, value.page, value.total, value.lastRead)
        }
    }

    private fun mapBook(raw: Map<*, *>): LegacyBook? {
        val id = asLong(raw["id"]) ?: return null
        if (id <= 0) return null
        val tags = when (val value = raw["tags"]) {
            is List<*> -> value.mapNotNull { it as? String }
            else -> emptyList()
        }
        return LegacyBook(
            id = id,
            title = asString(raw["title"]).orEmpty(),
            author = asString(raw["author"]).orEmpty(),
            format = asString(raw["format"]) ?: "epub",
            filePath = asString(raw["filePath"] ?: raw["file_path"]).orEmpty(),
            fileSize = asLong(raw["fileSize"] ?: raw["file_size"]) ?: 0L,
            addedAtRaw = asString(raw["addedAt"] ?: raw["added_at"]),
            tags = tags,
        )
    }

    private fun mapDownload(raw: Map<*, *>): LegacyDownload? {
        val title = asString(raw["title"]).orEmpty()
        val dest = asString(raw["filePath"] ?: raw["destPath"] ?: raw["file_path"]).orEmpty()
        val downloadPath = asString(raw["downloadPath"] ?: raw["download_path"]).orEmpty()
        if (title.isBlank() && dest.isBlank() && downloadPath.isBlank()) return null
        val format = asString(raw["format"]) ?: "epub"
        return LegacyDownload(
            bookId = asLong(raw["bookId"] ?: raw["book_id"]),
            title = title,
            author = asString(raw["author"]).orEmpty(),
            format = format,
            downloadPath = downloadPath,
            fileName = asString(raw["fileName"] ?: raw["file_name"]).orEmpty(),
            destPath = dest,
            storage = asString(raw["storage"]) ?: "file",
            size = asLong(raw["size"]) ?: 0L,
            total = asLong(raw["total"]) ?: 0L,
            status = asString(raw["status"]) ?: "completed",
            errorKind = asString(raw["errorKind"] ?: raw["error_kind"]),
            errorMessage = asString(raw["errorMessage"] ?: raw["error_message"]),
            finishedAt = parseTime(raw["finishedAt"] ?: raw["finished_at"]),
        )
    }
}

/** books.json / 下载历史 / prefs 三份旧数据的合并入口。 */
class LegacyLibraryLoader(private val reader: LegacyLibraryReader = LegacyLibraryReader()) {

    fun load(
        booksJson: String?,
        downloadsJson: String? = null,
        prefs: Map<String, Any?> = emptyMap(),
    ): LegacyLoadResult {
        val warnings = mutableListOf<String>()
        val books = if (booksJson.isNullOrBlank()) {
            emptyList()
        } else {
            try {
                reader.readBooks(booksJson)
            } catch (error: Exception) {
                return LegacyLoadResult(
                    snapshot = LegacySnapshot(emptyList(), reader.readProgress(prefs), emptyList()),
                    fatalError = "books.json 解析失败: " + (error.message ?: error.javaClass.simpleName),
                    warnings = listOf("books.json 未被修改，迁移中止"),
                )
            }
        }
        val downloads = if (downloadsJson.isNullOrBlank()) {
            emptyList()
        } else {
            try {
                reader.readDownloads(downloadsJson)
            } catch (error: Exception) {
                warnings += "下载历史解析失败，已跳过: " + (error.message ?: error.javaClass.simpleName)
                emptyList()
            }
        }
        val progress = try {
            reader.readProgress(prefs)
        } catch (error: Exception) {
            warnings += "阅读进度解析失败，已跳过: " + (error.message ?: error.javaClass.simpleName)
            emptyList()
        }
        return LegacyLoadResult(
            snapshot = LegacySnapshot(books, progress, downloads),
            warnings = warnings,
        )
    }
}

data class LegacyLoadResult(
    val snapshot: LegacySnapshot,
    val fatalError: String? = null,
    val warnings: List<String> = emptyList(),
)

private fun asLong(value: Any?): Long? = when (value) {
    is Number -> value.toLong()
    is String -> value.trim().toLongOrNull()
    else -> null
}

private fun asInt(value: Any?): Int? = when (value) {
    is Number -> value.toInt()
    is String -> value.trim().toIntOrNull()
    else -> null
}

private fun asString(value: Any?): String? = when (value) {
    null -> null
    is String -> value
    is Number, is Boolean -> value.toString()
    else -> null
}

private fun parseTime(value: Any?): Long = when (value) {
    is Number -> value.toLong()
    is String -> StableKey.parseEpochMillis(value)
    else -> 0L
}
