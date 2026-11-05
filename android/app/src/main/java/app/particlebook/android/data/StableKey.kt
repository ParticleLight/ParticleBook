package app.particlebook.android.data

import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.Locale

/**
 * stable_key 规则（v1）。
 *
 * 优先级：zlib:<bookId> > sha256:<64hex> > saf-uri:<sha256(uri)> > fp:<size>-<hash> > legacy:<旧id>。
 * 数字 id 永不变：迁移时旧 books.json 的 id 原样写入 books.id；stable_key 只用于跨来源认领，
 * 不替代自增 id。fp: 是临时身份，后续拿到完整 SHA-256 时应升级为 sha256:（见 docs）。
 */
object StableKey {
    const val ZLIB_PREFIX = "zlib:"
    const val SHA256_PREFIX = "sha256:"
    const val SAF_URI_PREFIX = "saf-uri:"
    const val FINGERPRINT_PREFIX = "fp:"
    const val LEGACY_PREFIX = "legacy:"

    private val SYSTEM_EXACT = setOf("zlib-download", "recovered", "missing")
    private val SYSTEM_PREFIXES = listOf(ZLIB_PREFIX, SHA256_PREFIX, SAF_URI_PREFIX, FINGERPRINT_PREFIX)

    fun stableKeyFor(legacyId: Long, tags: List<String>, contentHash: String? = null): String {
        val zlibId = zlibId(tags)
        if (zlibId != null) return ZLIB_PREFIX + zlibId
        val sha = normalizeSha256(contentHash) ?: normalizeSha256(tagValue(tags, SHA256_PREFIX))
        if (sha != null) return SHA256_PREFIX + sha
        val saf = tagValue(tags, SAF_URI_PREFIX)
        if (!saf.isNullOrBlank()) return SAF_URI_PREFIX + saf
        val fp = tagValue(tags, FINGERPRINT_PREFIX)
        if (!fp.isNullOrBlank()) return FINGERPRINT_PREFIX + fp
        return LEGACY_PREFIX + legacyId
    }

    fun zlibId(tags: List<String>): Long? =
        tagValue(tags, ZLIB_PREFIX)?.trim()?.toLongOrNull()?.takeIf { it > 0 }

    fun isSystemTag(tag: String): Boolean {
        val normalized = tag.trim().lowercase(Locale.ROOT)
        return normalized in SYSTEM_EXACT || SYSTEM_PREFIXES.any { normalized.startsWith(it) }
    }

    fun userTags(tags: List<String>): List<String> = tags
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !isSystemTag(it) }
        .distinct()
        .toList()

    fun tagValue(tags: List<String>, prefix: String): String? = tags
        .asSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith(prefix, ignoreCase = true) }
        ?.substring(prefix.length)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    fun normalizeSha256(value: String?): String? {
        val normalized = value?.trim()?.lowercase(Locale.ROOT) ?: return null
        return normalized.takeIf { it.length == 64 && it.all { ch -> ch in '0'..'9' || ch in 'a'..'f' } }
    }

    fun contentHashFromTags(tags: List<String>): String? {
        normalizeSha256(tagValue(tags, SHA256_PREFIX))?.let { return it }
        val fp = tagValue(tags, FINGERPRINT_PREFIX) ?: return null
        // fp:<size>-<sha256>；兼容只有 hash 的旧数据。
        return normalizeSha256(fp.substringAfterLast('-', missingDelimiterValue = fp))
    }

    /** PB-008 目标格式（不含 mobi/azw 等未承诺格式）。 */
    val SUPPORTED_FORMATS: Set<String> = linkedSetOf(
        "epub", "pdf", "txt", "html", "md", "fb2", "azw3", "docx", "cbz",
    )

    /**
     * 把旧 format / MIME / 扩展名收敛到 canonical 小写。
     * 未知格式保留原值（小写、去前导点），不再冒充 epub；空值单独记 "unknown"。
     */
    fun normalizeFormat(raw: String): String {
        val lower = raw.trim().lowercase(Locale.ROOT).removePrefix(".")
        return when {
            lower.isEmpty() -> "unknown"
            lower in SUPPORTED_FORMATS -> lower
            lower == "text/plain" -> "txt"
            lower == "text/html" || lower == "htm" || lower == "xhtml" || lower == "application/xhtml+xml" -> "html"
            lower == "text/markdown" || lower == "markdown" -> "md"
            lower == "application/pdf" -> "pdf"
            lower == "application/epub+zip" || lower == "epub+zip" -> "epub"
            lower == "application/x-mobipocket-ebook" || lower == "application/vnd.amazon.ebook" -> "azw3"
            else -> lower
        }
    }

    fun isSupportedFormat(value: String): Boolean = normalizeFormat(value) in SUPPORTED_FORMATS

    fun rawFormat(raw: String): String = raw.trim().lowercase(Locale.ROOT)

    fun formatNeedsNormalization(raw: String): Boolean = rawFormat(raw) != normalizeFormat(raw)

    fun fileNameFromPath(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return ""
        val withoutQuery = trimmed.substringBefore('?').substringBefore('#')
        val name = withoutQuery.substringAfterLast('/')
        return if (name.isNotBlank()) name else withoutQuery
    }

    /** 旧 books.json 的 addedAt/下载 finishedAt 既可能是 ISO 字符串，也可能是 epoch ms。 */
    fun parseEpochMillis(raw: String?, fallback: Long = 0L): Long {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return fallback
        text.toLongOrNull()?.let { return it }
        return try {
            Instant.parse(text).toEpochMilli()
        } catch (_: Exception) {
            try {
                OffsetDateTime.parse(text).toInstant().toEpochMilli()
            } catch (_: Exception) {
                try {
                    ZonedDateTime.parse(text).toInstant().toEpochMilli()
                } catch (_: Exception) {
                    fallback
                }
            }
        }
    }

    fun sha256Hex(value: String): String = sha256Hex(value.toByteArray(Charsets.UTF_8))

    fun sha256Hex(value: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value)
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    /** 最小 JSON 字符串转义；仅用于迁移 locator，不引入 org.json 依赖。 */
    fun jsonString(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (ch.code < 0x20) {
                    append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    append(ch)
                }
            }
        }
        append('"')
    }
}
