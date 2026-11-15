package app.particlebook.android.settings

import app.particlebook.android.platform.DiagnosticsRedactor
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * P1-A023：SharedPreferences 持久化层（global + per-book 前缀）。
 *
 * 核心 [SettingsStore] 只依赖 [SettingsStorage] / [SettingsLegacySource]，
 * 因此 JVM 单测可以注入内存 fake，不需要 Robolectric；Android 适配器在
 * SharedPreferencesSettingsStorage.kt。
 *
 * 存储布局：
 *   global.<key>            37 键（其中 5 个可被书级覆盖）
 *   global.schemaVersion    schema 版本
 *   book.<bookId>.<key>     某本书的 5 个覆盖值 / 书级未知键
 *   meta.legacyBackup.*     迁移前快照，不参与设置读取，也不进入脱敏导出。
 * 其他前缀（如 meta.）不参与设置读取，导出时原样保留。
 */

interface SettingsStorage {
    fun readAll(): Map<String, Any?>
    fun put(key: String, value: Any?)
    fun remove(key: String)
    fun clear()
}

/** 旧 SharedPreferences("pb_mobile_ui") 的只读来源，用于一次性键名迁移。 */
interface SettingsLegacySource {
    fun readLegacy(): Map<String, Any?>
}

class SettingsVersionException(val foundVersion: Int) : Exception(
    "settings schema version " + foundVersion + " is newer than supported " + SettingsSchema.VERSION,
)

/**
 * [SettingsStore.importFromJson] 的结果。
 *
 * - [values] 是恢复后的全局设置（含默认值补齐的 37 键与未知键）；
 * - [bookValues] 只包含备份里出现的书，未出现的书保持原样；
 * - [issues] 是导入过程中的 warning（未知键保留、旧键迁移等），error 不会出现在这里。
 */
data class SettingsImportResult(
    val values: SettingsValues,
    val bookValues: Map<String, SettingsValues> = emptyMap(),
    val issues: List<SettingsIssue> = emptyList(),
) {
    val importedKeyCount: Int get() = values.asMap().size
}

class SettingsStore(
    private val storage: SettingsStorage,
    private val legacy: SettingsLegacySource? = null,
    private val options: SettingsOptions = SettingsOptions(),
) {
    private val lock = Any()

    /** 读取全局设置；首次读取会补默认值、执行旧键/版本迁移并落盘。 */
    fun load(): SettingsValues = synchronized(lock) { loadDetailedLocked().values }

    /** 读取全局设置并保留 issues / 源版本，供迁移诊断使用。 */
    fun loadDetailed(): SettingsNormalization = synchronized(lock) { loadDetailedLocked() }

    fun get(key: String): Any? = load()[key]

    fun getBool(key: String): Boolean = load().getBool(key)

    fun getInt(key: String): Int = load().getInt(key)

    fun getDouble(key: String): Double = load().getDouble(key)

    fun getString(key: String): String = load().getString(key)

    /**
     * 设置一个全局键。类型/长度/URL 等 Error 会抛 [SettingsException]（INVALID_VALUE）；
     * 数值越界按 schema 夹取、未知枚举回退默认值（记录 warning 后落盘）。
     */
    fun set(key: String, value: Any?): SettingsValues = synchronized(lock) {
        setLocked(SettingsScope.GLOBAL, null, key, value)
    }

    /** 恢复单个全局键的默认值。 */
    fun reset(key: String): SettingsValues = synchronized(lock) {
        val spec = requireSpec(key)
        setLocked(SettingsScope.GLOBAL, null, spec.key, spec.defaultValue)
    }

    /** 恢复全局默认值；保留未知键和所有书级覆盖。 */
    fun resetAll(): SettingsValues = synchronized(lock) {
        val current = loadDetailedLocked().values
        val unknown = current.asMap().filterKeys { SettingsSchema.find(it) == null }
        val raw = LinkedHashMap<String, Any?>(SettingsSchema.DEFAULTS)
        unknown.forEach { (key, value) -> raw[key] = value }
        val normalized = SettingsSchema.normalizeDetailed(raw, SettingsScope.GLOBAL, options)
        persistGlobalLocked(normalized.values)
        normalized.values
    }

    /** 读取某本书的覆盖值；只含出现的键（不填默认值），未知键保留。 */
    fun getBook(bookId: String): SettingsValues = synchronized(lock) {
        readBookLocked(validBookId(bookId))
    }

    /** 设置某本书的一个书级键；全局键 / 未知键 / secret 键会被拒绝。 */
    fun setBook(bookId: String, key: String, value: Any?): SettingsValues = synchronized(lock) {
        setLocked(SettingsScope.BOOK, validBookId(bookId), key, value)
    }

    /** 批量设置某本书的书级键；先整体校验，任一键非法则不落盘。 */
    fun setBook(bookId: String, values: Map<String, Any?>): SettingsValues = synchronized(lock) {
        val id = validBookId(bookId)
        if (values.isEmpty()) return@synchronized readBookLocked(id)
        for (key in values.keys) validateBookKey(key)
        val current = readBookLocked(id)
        val raw = current.asMap().toMutableMap()
        raw.putAll(values)
        val normalized = SettingsSchema.normalizeDetailed(raw, SettingsScope.BOOK, options)
        for (key in values.keys) {
            val error = normalized.issues.firstOrNull {
                it.key == key && it.severity == SettingsSeverity.ERROR
            }
            if (error != null) {
                throw SettingsException(SettingsErrorCode.INVALID_VALUE, key, error.message)
            }
        }
        persistBookLocked(id, normalized.values)
        normalized.values
    }

    /** 某本书最终生效的设置：全局值 + 该书 5 个书级覆盖。 */
    fun effective(bookId: String): SettingsValues = synchronized(lock) {
        val id = validBookId(bookId)
        loadDetailedLocked().values.withBookOverrides(readBookLocked(id))
    }

    /**
     * 删除某本书的覆盖值。key = null 清空整本书；否则只删一个书级键。
     * 删除后 effective() 自动回落到全局值。
     */
    fun resetBook(bookId: String, key: String? = null): SettingsValues = synchronized(lock) {
        val id = validBookId(bookId)
        val prefix = bookPrefix(id)
        if (key == null) {
            storage.readAll().keys
                .filter { it.startsWith(prefix) }
                .forEach(storage::remove)
        } else {
            validateBookKey(key)
            storage.remove(prefix + key)
        }
        readBookLocked(id)
    }

    /**
     * 导出用于备份 / 诊断的脱敏快照；保留未知键结构，但递归剔除疑似密钥键，
     * 并对 URL token / cookie / 邮箱 / 绝对路径做文本脱敏。
     *
     * 该方法不返回 SecretStore 的内容；翻译 API Key 只能由 SettingsSecretBridge 读取。
     * 迁移前快照（meta.legacyBackup.*）不放入导出，避免旧数据夹带敏感值。
     */
    fun exportRedacted(): Map<String, Any?> = synchronized(lock) { exportRedactedLocked() }

    /**
     * 导出设置备份 JSON（[exportRedacted] 的 JSON 形式，扁平键值对象）。
     *
     * 内容边界：
     * - 37 个全局键 + 备份里出现的 `book.<bookId>.<key>` 覆盖值 + 未知设置键；
     * - 不含 `schemaVersion`、`meta.legacyBackup.*` 与其他非设置命名空间；
     * - 不含 SecretStore / 翻译 API Key —— 密钥只能由 [SettingsSecretBridge] 持有；
     * - 字符串值继续走 [DiagnosticsRedactor]，密钥风格的键递归剔除。
     *
     * 导出前先执行一次 [load]，保证首次运行的默认值也已经落盘。
     */
    fun exportToJson(): String = synchronized(lock) {
        loadDetailedLocked()
        SettingsTransferJson.encode(exportTransferMapLocked())
    }

    /**
     * 从 [exportToJson] / [SettingsExportFile] 产出的扁平 JSON 恢复设置。
     *
     * 安全与一致性：
     * - 任何密钥风格字段（顶层 `translation.apiKey` 或嵌套 `{"token": ...}`）都会让
     *   整次导入以 [SettingsErrorCode.SECRET_KEY] 失败，存储不发生任何写入；
     * - 未知键按 [options] 的 [UnknownKeyPolicy] 处理（默认保留，REJECT 时抛
     *   [SettingsErrorCode.UNKNOWN_KEY]），书级未知键同样保留；
     * - 所有全局值和书级值都通过 schema 校验后才落盘，不会出现导入一半的状态；
     * - 异常信息只包含键名 / 位置，不包含导入 JSON 或值，避免 Key 进入日志。
     *
     * 恢复语义：备份中的全局键整体替换当前全局设置（缺失键回到默认值）；
     * 备份中出现 `book.<bookId>.<key>` 时替换对应书的覆盖值，未出现在备份里的书不动。
     */
    fun importFromJson(
        json: String,
        options: SettingsOptions = SettingsOptions(),
    ): SettingsImportResult = synchronized(lock) {
        val parsed = try {
            SettingsTransferJson.decodeObject(json)
        } catch (error: SettingsTransferParseException) {
            throw SettingsException(
                SettingsErrorCode.INVALID_VALUE,
                null,
                "invalid settings export JSON: " + error.message,
            )
        }
        if (parsed.isEmpty()) {
            throw SettingsException(SettingsErrorCode.INVALID_VALUE, null, "settings export is empty")
        }
        rejectSecretLikeImportFields(parsed)

        val globalRaw = LinkedHashMap<String, Any?>()
        val bookRaw = LinkedHashMap<String, LinkedHashMap<String, Any?>>()
        for ((key, value) in parsed) {
            when {
                key.startsWith(BOOK_PREFIX) -> {
                    val rest = key.removePrefix(BOOK_PREFIX)
                    // 书级键本身可能带点（如 future.flag），bookId 取第一个分隔符；
                    // 生产 bookId 是 LibraryStore 的数字 id，不包含点。
                    val separator = rest.indexOf('.')
                    if (separator <= 0 || separator == rest.length - 1) {
                        throw SettingsException(
                            SettingsErrorCode.INVALID_VALUE,
                            key,
                            "malformed book setting key in settings export",
                        )
                    }
                    val bookId = validBookId(rest.substring(0, separator))
                    val settingKey = rest.substring(separator + 1)
                    bookRaw.getOrPut(bookId) { LinkedHashMap() }[settingKey] = value
                }
                key == SettingsSchema.VERSION_KEY -> {
                    val version = importVersion(value)
                    if (version == null || version < 1) {
                        throw SettingsException(
                            SettingsErrorCode.INVALID_VALUE,
                            key,
                            "schemaVersion in settings export must be a positive integer",
                        )
                    }
                    if (version > SettingsSchema.VERSION) {
                        throw SettingsException(
                            SettingsErrorCode.VERSION_UNSUPPORTED,
                            key,
                            "settings schema version " + version +
                                " is newer than supported " + SettingsSchema.VERSION,
                        )
                    }
                    globalRaw[key] = version
                }
                else -> globalRaw[key] = value
            }
        }

        val normalizedGlobal = SettingsSchema.normalizeDetailed(
            globalRaw,
            SettingsScope.GLOBAL,
            options,
        )
        throwIfImportRejected(normalizedGlobal, "global")
        val issues = mutableListOf<SettingsIssue>()
        issues += normalizedGlobal.issues
        val bookValues = LinkedHashMap<String, SettingsValues>()
        for ((bookId, raw) in bookRaw) {
            val normalizedBook = SettingsSchema.normalizeDetailed(raw, SettingsScope.BOOK, options)
            val scopeIssue = normalizedBook.issues.firstOrNull { it.code == "scope" }
            if (scopeIssue != null) {
                throw SettingsException(
                    SettingsErrorCode.WRONG_SCOPE,
                    scopeIssue.key,
                    "book settings export only accepts book-scoped keys",
                )
            }
            throwIfImportRejected(normalizedBook, "book " + bookId)
            issues += normalizedBook.issues
            bookValues[bookId] = normalizedBook.values
        }

        // 全部校验通过后才写入；前面任何异常都不会改动现有设置。
        persistGlobalLocked(normalizedGlobal.values)
        for ((bookId, values) in bookValues) persistBookLocked(bookId, values)
        SettingsImportResult(normalizedGlobal.values, bookValues, issues)
    }

    /**
     * 迁移前的原始设置快照（扁平键 => 值）。只在首次迁移时写入一次；
     * 键名前缀 meta.legacyBackup. 已去掉，方便诊断页展示和恢复。
     */
    fun legacyBackup(): Map<String, Any?> = synchronized(lock) {
        val result = LinkedHashMap<String, Any?>()
        for ((key, value) in storage.readAll()) {
            if (key.startsWith(BACKUP_PREFIX)) result[key.removePrefix(BACKUP_PREFIX)] = value
        }
        result
    }

    /** 清空整个设置存储（含书级与 meta 键），主要给测试 / 重置入口使用。 */
    fun clearAll() = synchronized(lock) { storage.clear() }

    // ── 内部：加载 / 迁移 ───────────────────────────────────────────
    private fun loadDetailedLocked(): SettingsNormalization {
        val snapshot = storage.readAll()
        val raw = LinkedHashMap<String, Any?>()
        for ((key, value) in snapshot) {
            if (key.startsWith(GLOBAL_PREFIX)) raw[key.removePrefix(GLOBAL_PREFIX)] = value
        }
        val missingVersion = !raw.containsKey(SettingsSchema.VERSION_KEY)
        var usedLegacy = false
        val legacyValues = if (missingVersion) legacy?.readLegacy().orEmpty() else emptyMap()
        for ((key, value) in legacyValues) {
            if (!raw.containsKey(key)) {
                raw[key] = value
                usedLegacy = true
            }
        }

        val normalized = SettingsSchema.normalizeDetailed(raw, SettingsScope.GLOBAL, options)
        if (normalized.sourceVersion > SettingsSchema.VERSION) {
            throw SettingsVersionException(normalized.sourceVersion)
        }
        // A023-2：首次迁移 / 升级前保留一份原始快照，且不覆盖已经存在的备份。
        if (missingVersion || normalized.migrated || usedLegacy ||
            normalized.sourceVersion in 1 until SettingsSchema.VERSION
        ) {
            ensureLegacyBackupLocked(snapshot, legacyValues)
        }
        if (missingVersion || normalized.migrated || usedLegacy) {
            persistGlobalLocked(normalized.values)
        }
        return normalized
    }

    private fun ensureLegacyBackupLocked(
        snapshot: Map<String, Any?>,
        legacyValues: Map<String, Any?>,
    ) {
        if (snapshot.keys.any { it.startsWith(BACKUP_PREFIX) }) return
        for ((key, value) in snapshot) {
            if (key.startsWith(BACKUP_PREFIX)) continue
            if (key.startsWith(GLOBAL_PREFIX) &&
                SettingsSchema.isSecretLikeKey(key.removePrefix(GLOBAL_PREFIX))
            ) {
                continue
            }
            storage.put(BACKUP_PREFIX + key, redactForExport(value))
        }
        for ((key, value) in legacyValues) {
            if (SettingsSchema.isSecretLikeKey(key)) continue
            storage.put(BACKUP_PREFIX + LEGACY_BACKUP_PREFIX + key, redactForExport(value))
        }
    }

    private fun exportRedactedLocked(): Map<String, Any?> {
        val logical = LinkedHashMap<String, Any?>()
        for ((key, value) in storage.readAll()) {
            when {
                key.startsWith(BACKUP_PREFIX) -> Unit
                key == GLOBAL_PREFIX + SettingsSchema.VERSION_KEY -> Unit
                key.startsWith(GLOBAL_PREFIX) -> logical[key.removePrefix(GLOBAL_PREFIX)] = value
                key.startsWith(BOOK_PREFIX) -> logical[key] = value
                else -> logical[key] = value
            }
        }
        @Suppress("UNCHECKED_CAST")
        return redactForExport(SettingsSchema.redactSecretLikeKeys(logical)) as Map<String, Any?>
    }

    /** 设置备份只收 global./book. 两个命名空间，避免把迁移快照等非设置数据带出去。 */
    private fun exportTransferMapLocked(): Map<String, Any?> {
        val logical = LinkedHashMap<String, Any?>()
        for ((key, value) in storage.readAll()) {
            when {
                key.startsWith(BACKUP_PREFIX) -> Unit
                key == GLOBAL_PREFIX + SettingsSchema.VERSION_KEY -> Unit
                key.startsWith(GLOBAL_PREFIX) -> logical[key.removePrefix(GLOBAL_PREFIX)] = value
                key.startsWith(BOOK_PREFIX) -> logical[key] = value
            }
        }
        @Suppress("UNCHECKED_CAST")
        return redactForExport(SettingsSchema.redactSecretLikeKeys(logical)) as Map<String, Any?>
    }

    /**
     * 导入前的密钥字段兜底：字段名或嵌套键名像密钥就整体拒绝。
     * 错误信息只带顶层键名，不带任何值。
     */
    private fun rejectSecretLikeImportFields(parsed: Map<String, Any?>) {
        for ((key, value) in parsed) {
            if (SettingsSchema.isSecretLikeKey(key)) {
                throw SettingsException(
                    SettingsErrorCode.SECRET_KEY,
                    key,
                    "settings import rejected: secret-like fields must stay in SecretStore",
                )
            }
            if (SettingsSchema.containsSecretLikeKey(value)) {
                throw SettingsException(
                    SettingsErrorCode.SECRET_KEY,
                    key,
                    "settings import rejected: nested secret-like fields must stay in SecretStore",
                )
            }
        }
    }

    private fun throwIfImportRejected(normalization: SettingsNormalization, scope: String) {
        val error = normalization.issues.firstOrNull { it.severity == SettingsSeverity.ERROR }
            ?: return
        val code = when (error.code) {
            "secret" -> SettingsErrorCode.SECRET_KEY
            "unknown" -> SettingsErrorCode.UNKNOWN_KEY
            "version" -> SettingsErrorCode.VERSION_UNSUPPORTED
            "scope" -> SettingsErrorCode.WRONG_SCOPE
            else -> SettingsErrorCode.INVALID_VALUE
        }
        throw SettingsException(
            code,
            error.key,
            "settings import rejected (" + scope + "): " + error.message,
        )
    }

    private fun importVersion(value: Any?): Int? = when (value) {
        is Int -> value
        is Long -> value.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
        is Double -> value.takeIf {
            it.isFinite() && it >= 1.0 && it <= Int.MAX_VALUE.toDouble() && it == Math.floor(it)
        }?.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }

    /** 导出/备份文本脱敏：键已剔除，这里再处理字符串里的 URL token、路径等。 */
    private fun redactForExport(value: Any?): Any? = when (value) {
        is String -> DiagnosticsRedactor.redact(value)
        is Map<*, *> -> {
            val result = LinkedHashMap<String, Any?>()
            for ((key, child) in value) {
                val text = key?.toString().orEmpty()
                if (!SettingsSchema.isSecretLikeKey(text)) result[text] = redactForExport(child)
            }
            result
        }
        is List<*> -> value.map { redactForExport(it) }
        else -> value
    }

    private fun readBookLocked(bookId: String): SettingsValues {
        val prefix = bookPrefix(bookId)
        val raw = LinkedHashMap<String, Any?>()
        for ((key, value) in storage.readAll()) {
            if (key.startsWith(prefix)) raw[key.removePrefix(prefix)] = value
        }
        return SettingsSchema.normalizeDetailed(raw, SettingsScope.BOOK, options).values
    }

    // ── 内部：写入 ──────────────────────────────────────────────────
    private fun setLocked(
        scope: SettingsScope,
        bookId: String?,
        key: String,
        value: Any?,
    ): SettingsValues {
        val spec = SettingsSchema.find(key)
        if (SettingsSchema.isSecretLikeKey(key)) {
            throw SettingsException(
                SettingsErrorCode.SECRET_KEY,
                key,
                "secret-like keys must use SecretStore, not settings",
            )
        }
        if (spec == null) {
            throw SettingsException(SettingsErrorCode.UNKNOWN_KEY, key, "unknown setting key")
        }
        if (scope == SettingsScope.BOOK && !spec.bookScoped) {
            throw SettingsException(
                SettingsErrorCode.WRONG_SCOPE,
                key,
                "global setting cannot be written as a book override",
            )
        }

        val current = if (scope == SettingsScope.GLOBAL) {
            loadDetailedLocked().values
        } else {
            readBookLocked(bookId!!)
        }
        val raw = current.asMap().toMutableMap()
        raw[key] = value
        val normalized = SettingsSchema.normalizeDetailed(raw, scope, options)
        val error = normalized.issues.firstOrNull {
            it.key == key && it.severity == SettingsSeverity.ERROR
        }
        if (error != null) {
            throw SettingsException(SettingsErrorCode.INVALID_VALUE, key, error.message)
        }
        if (scope == SettingsScope.GLOBAL) {
            persistGlobalLocked(normalized.values)
        } else {
            persistBookLocked(bookId!!, normalized.values)
        }
        return normalized.values
    }

    private fun persistGlobalLocked(values: SettingsValues) {
        val desired = LinkedHashMap<String, Any?>()
        values.asMap().forEach { (key, value) -> desired[GLOBAL_PREFIX + key] = value }
        desired[GLOBAL_PREFIX + SettingsSchema.VERSION_KEY] = values.schemaVersion
        val existing = storage.readAll()
        for (key in existing.keys) {
            if (key.startsWith(GLOBAL_PREFIX) && !desired.containsKey(key)) storage.remove(key)
        }
        for ((key, value) in desired) storage.put(key, value)
    }

    private fun persistBookLocked(bookId: String, values: SettingsValues) {
        val prefix = bookPrefix(bookId)
        val desired = LinkedHashMap<String, Any?>()
        values.asMap().forEach { (key, value) -> desired[prefix + key] = value }
        val existing = storage.readAll()
        for (key in existing.keys) {
            if (key.startsWith(prefix) && !desired.containsKey(key)) storage.remove(key)
        }
        for ((key, value) in desired) storage.put(key, value)
    }

    // ── 内部：校验 / 前缀 ───────────────────────────────────────────
    private fun requireSpec(key: String): SettingSpec =
        SettingsSchema.find(key) ?: throw SettingsException(
            SettingsErrorCode.UNKNOWN_KEY,
            key,
            "unknown setting key",
        )

    private fun validateBookKey(key: String) {
        if (SettingsSchema.isSecretLikeKey(key)) {
            throw SettingsException(
                SettingsErrorCode.SECRET_KEY,
                key,
                "secret-like keys must use SecretStore, not settings",
            )
        }
        val spec = SettingsSchema.find(key)
            ?: throw SettingsException(SettingsErrorCode.UNKNOWN_KEY, key, "unknown setting key")
        if (!spec.bookScoped) {
            throw SettingsException(
                SettingsErrorCode.WRONG_SCOPE,
                key,
                "global setting cannot be written as a book override",
            )
        }
    }

    private fun validBookId(bookId: String): String {
        if (bookId.isBlank()) {
            throw SettingsException(SettingsErrorCode.INVALID_BOOK_ID, null, "bookId must not be blank")
        }
        if (bookId.length > MAX_BOOK_ID_LENGTH) {
            throw SettingsException(
                SettingsErrorCode.INVALID_BOOK_ID,
                null,
                "bookId is longer than " + MAX_BOOK_ID_LENGTH,
            )
        }
        return bookId
    }

    private fun bookPrefix(bookId: String): String = BOOK_PREFIX + bookId + "."

    companion object {
        const val GLOBAL_PREFIX = "global."
        const val BOOK_PREFIX = "book."
        const val BACKUP_PREFIX = "meta.legacyBackup."
        const val LEGACY_BACKUP_PREFIX = "legacy."
        const val MAX_BOOK_ID_LENGTH = 512

        /** 新设置仓库的 SharedPreferences 名；旧 UI 偏好仍由 legacy 来源读取。 */
        const val PREFERENCES_NAME = "particlebook_settings"
        const val LEGACY_PREFERENCES_NAME = "pb_mobile_ui"
    }
}

/**
 * `filesDir/settings-export.json` 的纯 JVM 读写辅助类，不依赖 Android framework / UI。
 *
 * 生产入口传 `context.filesDir`；JVM 单测传 JUnit TemporaryFolder 的目录。
 * 写入先落同目录临时文件并 fsync，再原子 rename，避免进程被杀留下半个备份。
 */
class SettingsExportFile(private val directory: File) {

    fun file(): File = File(directory, FILE_NAME)

    /** 导出当前设置并原子写入 [file]；成功返回目标文件。 */
    fun write(store: SettingsStore): File {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw java.io.IOException("cannot create settings export directory")
        }
        val target = file()
        val temp = File(directory, FILE_NAME + "." + UUID.randomUUID().toString() + ".tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(store.exportToJson().toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
        return target
    }

    /** 读取 [file] 并恢复到 [store]；文件不存在抛 [FileNotFoundException]。 */
    fun restore(
        store: SettingsStore,
        options: SettingsOptions = SettingsOptions(),
    ): SettingsImportResult {
        val source = file()
        if (!source.isFile) {
            throw FileNotFoundException("settings export file not found: " + FILE_NAME)
        }
        return store.importFromJson(source.readText(Charsets.UTF_8), options)
    }

    companion object {
        const val FILE_NAME = "settings-export.json"
    }
}

/** JSON 解析失败的内部异常；message 只含语法原因与偏移，绝不回显原始内容。 */
internal class SettingsTransferParseException(message: String) : Exception(message)

/**
 * 设置备份的最小 JSON 编解码器。
 *
 * 刻意不依赖 org.json：framework 的 org.json 在 JVM 单测里是 "not mocked"，
 * 自带编解码器才能让 export/import 的往返在 `testDebugUnitTest` 里真实执行。
 * 只支持设置备份需要的 JSON 子集：对象 / 数组 / 字符串 / 数字 / 布尔 / null。
 */
internal object SettingsTransferJson {

    fun encode(value: Any?): String {
        val output = StringBuilder()
        appendValue(value, output)
        return output.toString()
    }

    fun decodeObject(raw: String): Map<String, Any?> = SettingsTransferParser(raw).parseDocument()

    private fun appendValue(value: Any?, output: StringBuilder) {
        when (value) {
            null -> output.append("null")
            is Boolean -> output.append(if (value) "true" else "false")
            is Int -> output.append(value)
            is Long -> output.append(value)
            is Short -> output.append(value.toInt())
            is Byte -> output.append(value.toInt())
            is Float -> appendDouble(value.toDouble(), output)
            is Double -> appendDouble(value, output)
            is String -> appendString(value, output)
            is Map<*, *> -> {
                output.append('{')
                var first = true
                for ((key, child) in value) {
                    val name = key as? String
                        ?: throw IllegalStateException("settings export object keys must be strings")
                    if (!first) output.append(',')
                    first = false
                    appendString(name, output)
                    output.append(':')
                    appendValue(child, output)
                }
                output.append('}')
            }
            is Iterable<*> -> {
                output.append('[')
                var first = true
                for (child in value) {
                    if (!first) output.append(',')
                    first = false
                    appendValue(child, output)
                }
                output.append(']')
            }
            is Array<*> -> appendValue(value.asList(), output)
            else -> throw IllegalStateException(
                "settings export cannot encode " + value.javaClass.simpleName,
            )
        }
    }

    private fun appendDouble(value: Double, output: StringBuilder) {
        if (!value.isFinite()) {
            throw IllegalStateException("settings export cannot encode non-finite number")
        }
        output.append(value.toString())
    }

    private fun appendString(value: String, output: StringBuilder) {
        output.append('"')
        for (character in value) {
            when (character) {
                '"' -> output.append("\\\"")
                '\\' -> output.append("\\\\")
                '\n' -> output.append("\\n")
                '\r' -> output.append("\\r")
                '\t' -> output.append("\\t")
                '\b' -> output.append("\\b")
                '\u000C' -> output.append("\\f")
                else -> if (character < ' ') {
                    output.append("\\u")
                    val hex = character.code.toString(16)
                    repeat(4 - hex.length) { output.append('0') }
                    output.append(hex)
                } else {
                    output.append(character)
                }
            }
        }
        output.append('"')
    }
}

private class SettingsTransferParser(private val text: String) {
    private var index = 0

    fun parseDocument(): Map<String, Any?> {
        skipWhitespace()
        if (index >= text.length || text[index] != '{') fail("expected a JSON object")
        val value = parseObject(0)
        skipWhitespace()
        if (index != text.length) fail("unexpected trailing content")
        return value
    }

    private fun parseObject(depth: Int): Map<String, Any?> {
        if (depth > MAX_DEPTH) fail("nesting too deep")
        expect('{')
        skipWhitespace()
        val result = LinkedHashMap<String, Any?>()
        if (peek() == '}') {
            index++
            return result
        }
        while (true) {
            skipWhitespace()
            if (peek() != '"') fail("expected a string key")
            val key = parseString()
            skipWhitespace()
            expect(':')
            val value = parseValue(depth + 1)
            if (result.containsKey(key)) fail("duplicate object key")
            result[key] = value
            skipWhitespace()
            when (peek()) {
                ',' -> index++
                '}' -> {
                    index++
                    return result
                }
                else -> fail("expected ',' or '}'")
            }
        }
    }

    private fun parseArray(depth: Int): List<Any?> {
        if (depth > MAX_DEPTH) fail("nesting too deep")
        expect('[')
        skipWhitespace()
        val result = mutableListOf<Any?>()
        if (peek() == ']') {
            index++
            return result
        }
        while (true) {
            result += parseValue(depth + 1)
            skipWhitespace()
            when (peek()) {
                ',' -> index++
                ']' -> {
                    index++
                    return result
                }
                else -> fail("expected ',' or ']'")
            }
        }
    }

    private fun parseValue(depth: Int): Any? {
        skipWhitespace()
        if (index >= text.length) fail("unexpected end of JSON")
        return when (val character = text[index]) {
            '{' -> parseObject(depth)
            '[' -> parseArray(depth)
            '"' -> parseString()
            't' -> parseLiteral("true", true)
            'f' -> parseLiteral("false", false)
            'n' -> parseLiteral("null", null)
            '-', in '0'..'9' -> parseNumber()
            else -> fail("unexpected character")
        }
    }

    private fun parseLiteral(literal: String, value: Any?): Any? {
        if (!text.startsWith(literal, index)) fail("invalid literal")
        index += literal.length
        return value
    }

    private fun parseString(): String {
        expect('"')
        val result = StringBuilder()
        while (true) {
            if (index >= text.length) fail("unterminated string")
            val character = text[index++]
            when {
                character == '"' -> return result.toString()
                character == '\\' -> {
                    if (index >= text.length) fail("unterminated escape sequence")
                    when (val escaped = text[index++]) {
                        '"' -> result.append('"')
                        '\\' -> result.append('\\')
                        '/' -> result.append('/')
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000C')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            if (index + 4 > text.length) fail("incomplete unicode escape")
                            var code = 0
                            repeat(4) { code = code * 16 + hexValue(text[index++]) }
                            result.append(code.toChar())
                        }
                        else -> fail("invalid escape sequence")
                    }
                }
                character < ' ' -> fail("unescaped control character in string")
                else -> result.append(character)
            }
        }
    }

    private fun parseNumber(): Any {
        val start = index
        if (peek() == '-') index++
        if (index >= text.length) fail("invalid number")
        if (text[index] == '0') {
            index++
        } else {
            if (text[index] !in '1'..'9') fail("invalid number")
            while (index < text.length && text[index].isDigit()) index++
        }
        var isFloatingPoint = false
        if (index < text.length && text[index] == '.') {
            isFloatingPoint = true
            index++
            if (index >= text.length || !text[index].isDigit()) fail("invalid number")
            while (index < text.length && text[index].isDigit()) index++
        }
        if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
            isFloatingPoint = true
            index++
            if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
            if (index >= text.length || !text[index].isDigit()) fail("invalid number")
            while (index < text.length && text[index].isDigit()) index++
        }
        val token = text.substring(start, index)
        if (!isFloatingPoint) {
            val long = token.toLongOrNull()
            if (long != null) {
                return if (long in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                    long.toInt()
                } else {
                    long
                }
            }
        }
        val double = token.toDoubleOrNull()
        if (double == null || !double.isFinite()) fail("number out of range")
        return double
    }

    private fun hexValue(character: Char): Int = when (character) {
        in '0'..'9' -> character - '0'
        in 'a'..'f' -> character - 'a' + 10
        in 'A'..'F' -> character - 'A' + 10
        else -> fail("invalid unicode escape")
    }

    private fun skipWhitespace() {
        while (index < text.length) {
            when (text[index]) {
                ' ', '\t', '\n', '\r' -> index++
                else -> return
            }
        }
    }

    private fun peek(): Char = if (index < text.length) text[index] else '\u0000'

    private fun expect(expected: Char) {
        if (index >= text.length || text[index] != expected) {
            fail("expected '" + expected + "'")
        }
        index++
    }

    private fun fail(message: String): Nothing =
        throw SettingsTransferParseException(message + " at offset " + index)

    private companion object {
        const val MAX_DEPTH = 64
    }
}