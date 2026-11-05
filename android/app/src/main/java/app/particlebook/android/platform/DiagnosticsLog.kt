package app.particlebook.android.platform

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * 本地诊断日志：按大小滚动、按天数清理旧备份，并在写入/导出时脱敏。
 *
 * 设计为纯 JVM 类（不依赖 Android framework），方便单测；Android 侧只需传入应用私有目录，
 * 例如 context.filesDir/diagnostics。日志永远不应影响主流程：
 * - [log] 捕获所有 I/O 异常并记录在 [lastError]，不向调用方抛出；
 * - [clear]/[exportRedacted] 也尽量不抛；
 * - 写入前先脱敏，避免 token、cookie、邮箱、绝对路径落盘。
 *
 * 文件命名固定为：
 * - 当前：<fileStem>.log
 * - 备份：<fileStem>.1.log、<fileStem>.2.log …（数字越小越新）
 */
class DiagnosticsLog(
    private val directory: File,
    private val options: Options = Options(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * @param maxFileBytes 当前文件达到该字节数后滚动；单条超大日志仍允许独占一个文件。
     * @param maxBackupFiles 保留的备份数量，0 表示不保留备份。
     * @param maxAgeDays 备份超过该天数会在下次写入前删除；0 表示禁用按天清理。
     * @param fileStem 文件名前缀，只允许字母、数字、点、下划线、连字符，防止路径穿越。
     * @param maxLineChars 单条日志最大字符数，超出截断并追加省略号。
     */
    data class Options(
        val maxFileBytes: Long = 1_048_576L,
        val maxBackupFiles: Int = 3,
        val maxAgeDays: Int = 7,
        val fileStem: String = "particlebook",
        val maxLineChars: Int = 32_768,
    ) {
        init {
            require(maxFileBytes >= MIN_FILE_BYTES) {
                "maxFileBytes must be >= " + MIN_FILE_BYTES
            }
            require(maxBackupFiles in 0..MAX_BACKUP_FILES) {
                "maxBackupFiles must be in 0.." + MAX_BACKUP_FILES
            }
            require(maxAgeDays in 0..MAX_AGE_DAYS) {
                "maxAgeDays must be in 0.." + MAX_AGE_DAYS
            }
            require(fileStem.matches(STEM_PATTERN)) {
                "fileStem must match [A-Za-z0-9._-]{1,64}"
            }
            require(maxLineChars in MIN_LINE_CHARS..MAX_LINE_CHARS) {
                "maxLineChars must be in " + MIN_LINE_CHARS + ".." + MAX_LINE_CHARS
            }
        }

        private companion object {
            const val MIN_FILE_BYTES = 64L
            const val MAX_BACKUP_FILES = 64
            const val MAX_AGE_DAYS = 3650
            const val MIN_LINE_CHARS = 256
            const val MAX_LINE_CHARS = 1_048_576
            val STEM_PATTERN = Regex("[A-Za-z0-9._-]{1,64}")
        }
    }

    private val lock = Any()

    /** 最近一次后台写入/清理失败的原因；成功后清空。供调试页/测试读取，不写入日志本身。 */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * 追加一条日志。tag 会压平换行并截断到 64 字符；message 与 throwable 的堆栈会一起脱敏。
     * 任何 I/O 失败都不会抛出，只更新 [lastError]。
     */
    fun log(tag: String, message: String, throwable: Throwable? = null) {
        synchronized(lock) {
            try {
                ensureDirectory()
                val now = clock()
                purgeExpired(now)
                val bytes = formatLine(now, tag, message, throwable).toByteArray(Charsets.UTF_8)
                val current = currentFile()
                if (current.length() > 0 && current.length() + bytes.size > options.maxFileBytes) {
                    rotate()
                }
                append(currentFile(), bytes)
                lastError = null
            } catch (e: Exception) {
                lastError = e.javaClass.simpleName + ": " + (e.message ?: "")
            }
        }
    }

    /**
     * 返回当前文件与全部备份的脱敏拼接文本；文件名只使用 basename，不泄漏绝对路径。
     * 已经落盘的旧日志也会再次脱敏，作为防御性处理。
     */
    fun exportRedacted(): String = synchronized(lock) { exportUnlocked() }

    /**
     * 将 [exportRedacted] 的内容写到指定文件；返回目标文件。调用方负责选择可分享的位置。
     * 该便捷方法不改变日志自身状态。
     */
    fun exportRedactedTo(target: File): File = synchronized(lock) {
        target.parentFile?.mkdirs()
        target.writeText(exportUnlocked(), Charsets.UTF_8)
        target
    }

    /** 删除当前文件与所有匹配的备份；单个文件删除失败只记录 [lastError]。 */
    fun clear() {
        synchronized(lock) {
            try {
                val files = directory.listFiles().orEmpty().filter { it.isFile && matchesLogName(it.name) }
                for (file in files) {
                    if (!file.delete()) {
                        throw IOException("failed to delete " + file.name)
                    }
                }
                lastError = null
            } catch (e: Exception) {
                lastError = e.javaClass.simpleName + ": " + (e.message ?: "")
            }
        }
    }

    /** 当前存在的日志文件，顺序为 current、.1、.2 …；只统计 options 范围内的备份。 */
    fun files(): List<File> = synchronized(lock) { filesUnlocked() }

    /** 当前文件路径；不保证存在。 */
    fun currentFile(): File = File(directory, options.fileStem + ".log")

    private fun exportUnlocked(): String {
        val result = StringBuilder()
        for (file in filesUnlocked()) {
            result.append("# ").append(file.name).append('\n')
            val content = try {
                file.readText(Charsets.UTF_8)
            } catch (e: Exception) {
                "<unreadable>\n"
            }
            result.append(DiagnosticsRedactor.redact(content))
            if (content.isNotEmpty() && !content.endsWith('\n')) {
                result.append('\n')
            }
        }
        return result.toString()
    }

    private fun filesUnlocked(): List<File> {
        val result = ArrayList<File>()
        val current = currentFile()
        if (current.isFile) result.add(current)
        for (index in 1..options.maxBackupFiles) {
            val backup = backupFile(index)
            if (backup.isFile) result.add(backup)
        }
        return result
    }

    private fun formatLine(now: Long, tag: String, message: String, throwable: Throwable?): String {
        val stamp = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(now))
        val safeTag = tag
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace('\t', ' ')
            .trim()
            .take(64)
            .ifEmpty { "-" }
        val raw = buildString {
            append(stamp)
            append(" [")
            append(safeTag)
            append("] ")
            append(message)
            if (throwable != null) {
                append(" | ")
                append(throwable.stackTraceToString())
            }
        }
        val redacted = DiagnosticsRedactor.redact(raw)
        val flattened = redacted
            .replace("\r\n", " | ")
            .replace('\r', ' ')
            .replace("\n", " | ")
        val clipped = if (flattened.length > options.maxLineChars) {
            flattened.take(options.maxLineChars) + "…"
        } else {
            flattened
        }
        return clipped + "\n"
    }

    private fun append(file: File, bytes: ByteArray) {
        FileOutputStream(file, true).use { out ->
            out.write(bytes)
            out.flush()
        }
    }

    private fun rotate() {
        val max = options.maxBackupFiles
        val current = currentFile()
        if (max == 0) {
            if (current.exists() && !current.delete()) {
                throw IOException("failed to delete current log")
            }
            return
        }
        val oldest = backupFile(max)
        if (oldest.exists() && !oldest.delete()) {
            throw IOException("failed to delete oldest backup")
        }
        for (index in max - 1 downTo 1) {
            move(backupFile(index), backupFile(index + 1))
        }
        move(current, backupFile(1))
    }

    private fun move(source: File, target: File) {
        if (!source.isFile) return
        if (target.exists() && !target.delete()) {
            throw IOException("failed to replace " + target.name)
        }
        if (!source.renameTo(target)) {
            source.copyTo(target, overwrite = true)
            if (!source.delete()) {
                throw IOException("failed to remove " + source.name)
            }
        }
    }

    private fun purgeExpired(now: Long) {
        if (options.maxAgeDays <= 0) return
        val cutoff = now - options.maxAgeDays.toLong() * MILLIS_PER_DAY
        for (index in 1..options.maxBackupFiles) {
            val backup = backupFile(index)
            if (!backup.isFile) continue
            if (backup.lastModified() in 1 until cutoff && !backup.delete()) {
                throw IOException("failed to delete expired backup " + backup.name)
            }
        }
    }

    private fun ensureDirectory() {
        if (directory.exists()) {
            if (!directory.isDirectory) throw IOException("diagnostics path is not a directory")
            return
        }
        if (!directory.mkdirs() && !directory.isDirectory) {
            throw IOException("failed to create diagnostics directory")
        }
    }

    private fun backupFile(index: Int): File = File(directory, options.fileStem + "." + index + ".log")

    private fun matchesLogName(name: String): Boolean {
        if (name == options.fileStem + ".log") return true
        val prefix = options.fileStem + "."
        if (!name.startsWith(prefix) || !name.endsWith(".log")) return false
        val middle = name.substring(prefix.length, name.length - ".log".length)
        if (middle.isEmpty() || middle.length > 3) return false
        return middle.all { it.isDigit() } && middle.toIntOrNull() != null
    }

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
    }
}

/**
 * 诊断日志脱敏器。规则是固定占位符，调用方/测试可以依赖：
 * - URL/键值中的 token、access_token、api_key、password、secret、session、signature、jwt 等值 → <redacted>
 * - Cookie/Set-Cookie、Authorization 整段值 → <redacted>
 * - 邮箱 → <email>
 * - Android /data/... → <app-data>；/storage/...、/sdcard/... → <storage>
 * - Windows 用户目录 C:\Users\... → <user-path>
 *
 * 规则只做保守替换，不试图成为完整日志分类器；新增敏感字段时在这里集中扩展。
 */
object DiagnosticsRedactor {

    private const val REDACTED = "<redacted>"
    private const val EMAIL = "<email>"
    private const val APP_DATA = "<app-data>"
    private const val STORAGE = "<storage>"
    private const val USER_PATH = "<user-path>"

    private val headerPattern = Regex("(?im)^([ \\t]*(?:cookie|set-cookie)[ \\t]*:[ \\t]*).*$")
    private val authorizationPattern = Regex("(?im)^([ \\t]*authorization[ \\t]*:[ \\t]*).*$")
    private val urlCredentialPattern = Regex("(?i)(https?://)([^/\\s@]+)@")
    private val jsonSecretPattern = Regex(
        "(?i)(\"(?:token|access_token|refresh_token|id_token|api_key|apikey|password|secret|session|signature)\"\\s*:\\s*\")[^\"]*(\")",
    )
    private val keyValueSecretPattern = Regex(
        "(?i)(\\b(?:access_token|refresh_token|id_token|api_key|apikey|password|passwd|client_secret|secret|sessionid|session|signature|sig|token|jwt)\\b\\s*[=:]\\s*)([^\\s&,;\"'}\\]]+)",
    )
    private val bearerPattern = Regex("(?i)(\\bBearer\\s+)[A-Za-z0-9._~+/=-]+")
    private val emailPattern = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val androidDataPathPattern = Regex(
        "(^|[\\s\"'(=])(/data/(?:user/\\d+|data)/[^\\s\"'<>]+)",
    )
    private val storagePathPattern = Regex(
        "(^|[\\s\"'(=])(/storage/(?:emulated/\\d+|[A-Za-z0-9._-]+)/[^\\s\"'<>]+)",
    )
    private val sdcardPathPattern = Regex(
        "(^|[\\s\"'(=])(/sdcard/[^\\s\"'<>]+)",
    )
    private val windowsUserPathPattern = Regex(
        "(^|[\\s\"'(=])([A-Za-z]:\\\\Users\\\\[^\\s\"'<>]+)",
    )

    fun redact(input: String): String {
        if (input.isEmpty()) return input
        var value = input
        value = headerPattern.replace(value) { it.groupValues[1] + REDACTED }
        value = authorizationPattern.replace(value) { it.groupValues[1] + REDACTED }
        value = urlCredentialPattern.replace(value) { it.groupValues[1] + REDACTED + "@" }
        value = jsonSecretPattern.replace(value) { it.groupValues[1] + REDACTED + it.groupValues[2] }
        value = keyValueSecretPattern.replace(value) { it.groupValues[1] + REDACTED }
        value = bearerPattern.replace(value) { it.groupValues[1] + REDACTED }
        value = emailPattern.replace(value, EMAIL)
        value = androidDataPathPattern.replace(value) { it.groupValues[1] + APP_DATA }
        value = storagePathPattern.replace(value) { it.groupValues[1] + STORAGE }
        value = sdcardPathPattern.replace(value) { it.groupValues[1] + STORAGE }
        value = windowsUserPathPattern.replace(value) { it.groupValues[1] + USER_PATH }
        return value
    }
}
