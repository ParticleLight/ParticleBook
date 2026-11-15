package app.particlebook.android.settings

import app.particlebook.android.platform.DiagnosticsRedactor

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
    fun exportRedacted(): Map<String, Any?> = synchronized(lock) {
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
        redactForExport(SettingsSchema.redactSecretLikeKeys(logical)) as Map<String, Any?>
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