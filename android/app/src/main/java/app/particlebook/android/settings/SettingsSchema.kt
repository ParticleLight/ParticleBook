package app.particlebook.android.settings

/**
 * P1-A023：设置 schema —— 37 键（5 个可书级覆盖）。
 *
 * 与桌面 particlebook-cpp/src/platform/SettingsService.cpp:708-770 的 schema 对齐：
 * 类型、默认值、范围、枚举、书级归属只在这里声明；SettingsStore 只负责持久化。
 *
 * 纯 Kotlin，不依赖 Android framework；JVM 单测可以直接构造 Map 做 normalize。
 */

enum class SettingsScope {
    GLOBAL,
    BOOK,
}

enum class SettingType {
    BOOL,
    INT,
    DOUBLE,
    STRING,
    ENUM,
}

enum class SettingsSeverity {
    WARNING,
    ERROR,
}

enum class UnknownKeyPolicy {
    PRESERVE,
    DROP,
    REJECT,
}

/**
 * 一条设置的定义。
 *
 * [secretLike] 只用于将来可能出现的、名字像密钥的已知键；当前 schema 中没有任何
 * 已知键是 secret（translation.apiKey 不属于 settings）。未知键仍由
 * [SettingsSchema.isSecretLikeKey] 在 normalize 时强制剔除。
 */
data class SettingSpec(
    val key: String,
    val type: SettingType,
    val defaultValue: Any,
    val minValue: Double? = null,
    val maxValue: Double? = null,
    val enumValues: List<String> = emptyList(),
    val maxLength: Int = 0,
    val isUrl: Boolean = false,
    val scope: SettingsScope = SettingsScope.GLOBAL,
    val secretLike: Boolean = false,
) {
    init {
        require(key.isNotBlank()) { "setting key must not be blank" }
        when (type) {
            SettingType.BOOL -> require(defaultValue is Boolean)
            SettingType.INT -> require(defaultValue is Int)
            SettingType.DOUBLE -> require(defaultValue is Double)
            SettingType.STRING, SettingType.ENUM -> require(defaultValue is String)
        }
        if (type == SettingType.INT || type == SettingType.DOUBLE) {
            require(minValue != null && maxValue != null) { "numeric setting must declare min/max" }
            require(minValue!! <= maxValue!!) { "numeric setting min must not exceed max" }
        }
        if (type == SettingType.ENUM) {
            require(enumValues.isNotEmpty()) { "enum setting must declare allowed values" }
            require(defaultValue is String && defaultValue in enumValues) {
                "enum default must be allowed"
            }
        }
    }

    val bookScoped: Boolean get() = scope == SettingsScope.BOOK
}

data class SettingsIssue(
    val key: String,
    val code: String,
    val message: String,
    val severity: SettingsSeverity = SettingsSeverity.WARNING,
)

data class SettingsOptions(
    val unknownKeys: UnknownKeyPolicy = UnknownKeyPolicy.PRESERVE,
    /** 旧数据 / 渲染层字符串化的值："18" -> 18、"false" -> false。 */
    val migrateLegacyTypes: Boolean = true,
    /** theme_mode / font_size / line_height / default_sort 旧键映射。 */
    val migrateLegacyKeys: Boolean = true,
)

enum class SettingsErrorCode {
    UNKNOWN_KEY,
    SECRET_KEY,
    INVALID_VALUE,
    WRONG_SCOPE,
    INVALID_BOOK_ID,
    VERSION_UNSUPPORTED,
}

/** SettingsStore 的结构化错误；message 只放诊断信息，不放 secret。 */
class SettingsException(
    val code: SettingsErrorCode,
    val key: String? = null,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** normalize 后的设置值；保持 canonical 类型（Boolean / Int / Double / String）。 */
data class SettingsValues(
    val values: Map<String, Any?>,
    val schemaVersion: Int = SettingsSchema.VERSION,
) {
    operator fun get(key: String): Any? = values[key]

    fun containsKey(key: String): Boolean = values.containsKey(key)

    fun asMap(): Map<String, Any?> = values

    fun getBool(key: String): Boolean =
        values[key] as? Boolean ?: (SettingsSchema.find(key)?.defaultValue as? Boolean ?: false)

    fun getInt(key: String): Int = when (val value = values[key]) {
        is Int -> value
        is Long -> value.toInt()
        is Number -> value.toInt()
        else -> (SettingsSchema.find(key)?.defaultValue as? Int) ?: 0
    }

    fun getDouble(key: String): Double = when (val value = values[key]) {
        is Double -> value
        is Float -> value.toDouble()
        is Number -> value.toDouble()
        else -> (SettingsSchema.find(key)?.defaultValue as? Double) ?: 0.0
    }

    fun getString(key: String): String =
        values[key] as? String ?: (SettingsSchema.find(key)?.defaultValue as? String).orEmpty()

    /**
     * 全局值 + 书级覆盖。只允许 5 个 bookScoped 键覆盖；书级行里误写的全局键
     * 或未知键不参与 effective 计算（但仍可被 book() 保留）。
     */
    fun withBookOverrides(book: SettingsValues): SettingsValues {
        val merged = LinkedHashMap(values)
        for ((key, value) in book.values) {
            if (SettingsSchema.find(key)?.bookScoped == true) merged[key] = value
        }
        return SettingsValues(merged, schemaVersion)
    }
}

/** normalize 的完整结果（values + issues + 源版本），SettingsStore 用它判断旧/新版本。 */
data class SettingsNormalization(
    val values: SettingsValues,
    val issues: List<SettingsIssue>,
    val sourceVersion: Int,
    val migrated: Boolean,
) {
    val hasErrors: Boolean get() = issues.any { it.severity == SettingsSeverity.ERROR }
}

object SettingsSchema {
    const val VERSION = 1
    const val VERSION_KEY = "schemaVersion"

    // ── 37 个键：基础 12 + 封面缓存 1 + TTS 6 + 翻译 7 + 阅读目标 2 + 词典 4 + 备份 5 ──
    val GLOBAL_SPECS: List<SettingSpec> = listOf(
        enum("theme", "dark", listOf("light", "dark", "sepia")),
        enum("accentColor", "blue", listOf("blue", "purple", "green", "orange")),
        int("fontSize", 18, 12, 32, SettingsScope.BOOK),
        string("fontFamily", "Georgia, Noto Serif SC, serif", 200, SettingsScope.BOOK),
        double("lineHeight", 1.8, 1.2, 3.0, SettingsScope.BOOK),
        int("margin", 40, 0, 100, SettingsScope.BOOK),
        enum("textAlign", "justify", listOf("left", "justify"), SettingsScope.BOOK),
        bool("autoSaveProgress", true),
        bool("showReadingTime", true),
        enum("defaultViewMode", "grid", listOf("grid", "list")),
        enum("defaultSortBy", "last_opened", listOf("title", "author", "added_at", "last_opened")),
        enum("language", "zh", listOf("zh", "en")),

        int("library.coverCacheSize", 300, 50, 1000),

        bool("tts.enabled", false),
        string("tts.voice", "", 128),
        int("tts.rate", 0, -10, 10),
        int("tts.volume", 100, 0, 100),
        bool("tts.highlightSentence", true),
        int("tts.sleepTimerMinutes", 0, 0, 180),

        bool("translation.enabled", false),
        string("translation.provider", "openai", 64),
        string("translation.baseUrl", "", 2048, isUrl = true),
        string("translation.model", "", 200),
        string("translation.apiFormat", "chat_completion", 32),
        string("translation.targetLanguage", "zh", 32),
        int("translation.timeoutSeconds", 60, 5, 300),

        int("reading.dailyGoalMinutes", 30, 0, 1440),
        int("reading.weeklyGoalMinutes", 180, 0, 10080),

        bool("dictionary.enabled", true),
        string("dictionary.provider", "local", 64),
        bool("dictionary.autoLookup", false),
        int("dictionary.maxResults", 5, 1, 50),

        bool("backup.enabled", true),
        string("backup.path", "", 1024),
        int("backup.intervalDays", 7, 1, 365),
        int("backup.keepCount", 5, 1, 50),
        string("backup.lastBackupAt", "", 64),
    )

    /** 5 个可按书覆盖的键；它们是 GLOBAL_SPECS 的子集。 */
    val BOOK_SPECS: List<SettingSpec> = GLOBAL_SPECS.filter { it.bookScoped }

    val BOOK_KEYS: Set<String> = BOOK_SPECS.mapTo(LinkedHashSet()) { it.key }

    val DEFAULTS: Map<String, Any> = GLOBAL_SPECS.associateTo(LinkedHashMap()) { it.key to it.defaultValue }

    private val BY_KEY: Map<String, SettingSpec> = GLOBAL_SPECS.associateBy { it.key }

    fun find(key: String): SettingSpec? = BY_KEY[key]

    fun isBookKey(key: String): Boolean = find(key)?.bookScoped == true

    fun defaults(): SettingsValues = SettingsValues(LinkedHashMap(DEFAULTS), VERSION)

    fun bookDefaults(): SettingsValues = SettingsValues(
        BOOK_SPECS.associateTo(LinkedHashMap()) { it.key to it.defaultValue },
        VERSION,
    )

    fun normalize(
        raw: Map<String, Any?>,
        scope: SettingsScope = SettingsScope.GLOBAL,
        options: SettingsOptions = SettingsOptions(),
    ): SettingsValues = normalizeDetailed(raw, scope, options).values

    fun normalizeBook(
        raw: Map<String, Any?>,
        options: SettingsOptions = SettingsOptions(),
    ): SettingsValues = normalizeDetailed(raw, SettingsScope.BOOK, options).values

    /**
     * 规范化 + 迁移 + 记录 issues。
     *
     * - missing version：sourceVersion = 0，按当前版本补默认值；
     * - 旧版本：迁移到当前版本并给 warning；
     * - 更高版本：给 ERROR，SettingsStore.load 会抛 SettingsVersionException，
     *   不降级写坏新数据。
     */
    fun normalizeDetailed(
        raw: Map<String, Any?>,
        scope: SettingsScope = SettingsScope.GLOBAL,
        options: SettingsOptions = SettingsOptions(),
    ): SettingsNormalization {
        val issues = mutableListOf<SettingsIssue>()
        var migrated = false
        val sourceVersion = if (scope == SettingsScope.GLOBAL) {
            readVersion(raw[VERSION_KEY], options, issues).also {
                if (it in 1 until VERSION) {
                    migrated = true
                    issues += issue(
                        VERSION_KEY,
                        "version",
                        "settings schema upgraded from " + it + " to " + VERSION,
                    )
                }
            }
        } else {
            if (raw.containsKey(VERSION_KEY)) {
                issues += issue(
                    VERSION_KEY,
                    "version",
                    "schemaVersion is global-only and ignored in book settings",
                )
            }
            VERSION
        }

        // 旧键先映射到 canonical 键；canonical 已存在时旧键只记录 warning，不覆盖。
        val input = LinkedHashMap<String, Any?>()
        if (scope == SettingsScope.GLOBAL && options.migrateLegacyKeys) {
            for (mapping in LEGACY_MAPPINGS) {
                if (!raw.containsKey(mapping.oldKey)) continue
                if (raw.containsKey(mapping.targetKey)) {
                    issues += issue(
                        mapping.oldKey,
                        "migrated",
                        "legacy setting ignored because " + mapping.targetKey + " already exists",
                    )
                    continue
                }
                val mapped = mapping.mapValue(raw[mapping.oldKey])
                input[mapping.targetKey] = mapped
                migrated = true
                issues += issue(
                    mapping.oldKey,
                    "migrated",
                    "legacy setting migrated to " + mapping.targetKey,
                )
            }
        }
        for ((key, value) in raw) {
            if (key == VERSION_KEY) continue
            if (scope == SettingsScope.GLOBAL && options.migrateLegacyKeys && isLegacyKey(key)) continue
            if (input.containsKey(key)) continue
            input[key] = value
        }

        val specs = if (scope == SettingsScope.GLOBAL) GLOBAL_SPECS else BOOK_SPECS
        val output = LinkedHashMap<String, Any?>()
        for (spec in specs) {
            if (!input.containsKey(spec.key)) {
                if (scope == SettingsScope.GLOBAL) output[spec.key] = spec.defaultValue
                continue
            }
            val value = input.remove(spec.key)
            val coerced = coerce(spec, value, options, issues)
            if (coerced != null) {
                output[spec.key] = coerced
            } else if (scope == SettingsScope.GLOBAL) {
                output[spec.key] = spec.defaultValue
            }
        }

        // 书级行里出现了全局键：忽略并告警，绝不让书级行污染全局设置。
        for (key in input.keys.toList()) {
            val spec = find(key)
            if (spec != null) {
                issues += issue(key, "scope", "global setting ignored in book settings")
                input.remove(key)
            }
        }

        // 未知键：先剔除疑似密钥（含嵌套），再按策略保留 / 丢弃 / 拒绝。
        for ((key, value) in input) {
            if (isSecretLikeKey(key)) {
                issues += issue(
                    key,
                    "secret",
                    "key name looks like a secret; secrets belong in SecretStore, not settings",
                    SettingsSeverity.ERROR,
                )
                continue
            }
            val nestedSecretPath = firstSecretLikePath(value, key)
            if (nestedSecretPath != null) {
                issues += issue(
                    nestedSecretPath,
                    "secret",
                    "nested key looks like a secret; whole unknown setting dropped",
                    SettingsSeverity.ERROR,
                )
                continue
            }
            when (options.unknownKeys) {
                UnknownKeyPolicy.PRESERVE -> {
                    output[key] = value
                    issues += issue(key, "unknown", "unknown setting preserved")
                }
                UnknownKeyPolicy.DROP -> issues += issue(key, "unknown", "unknown setting dropped")
                UnknownKeyPolicy.REJECT -> issues += issue(
                    key,
                    "unknown",
                    "unknown setting rejected",
                    SettingsSeverity.ERROR,
                )
            }
        }

        return SettingsNormalization(
            values = SettingsValues(output, VERSION),
            issues = issues,
            sourceVersion = sourceVersion,
            migrated = migrated,
        )
    }

    fun resolveEffective(global: SettingsValues, book: SettingsValues): SettingsValues =
        global.withBookOverrides(book)

    // ── 密钥边界工具（与桌面 SettingsService 的 IsSecretLikeKey 对齐）────────
    fun isSecretLikeKey(key: String): Boolean {
        if (key.isEmpty()) return false
        val segments = splitKey(key)
        if (segments.isEmpty()) return false
        val joined = segments.joinToString("")
        if (joined == "key") return true
        for (marker in JOINED_SECRET_MARKERS) {
            if (joined.contains(marker)) return true
        }
        return segments.any { segmentLooksSecret(it) }
    }

    fun containsSecretLikeKey(value: Any?): Boolean = firstSecretLikePath(value, "") != null

    /** 递归剔除疑似密钥键，返回副本；用于设置导出 / 备份 / 日志前的兜底脱敏。 */
    fun redactSecretLikeKeys(value: Any?): Any? = when (value) {
        is Map<*, *> -> {
            val redacted = LinkedHashMap<String, Any?>()
            for ((key, child) in value) {
                val text = key?.toString().orEmpty()
                if (isSecretLikeKey(text)) continue
                redacted[text] = redactSecretLikeKeys(child)
            }
            redacted
        }
        is List<*> -> value.map { redactSecretLikeKeys(it) }
        else -> value
    }

    // ── 内部：schema 构造 ────────────────────────────────────────────
    private fun bool(key: String, defaultValue: Boolean): SettingSpec =
        SettingSpec(key, SettingType.BOOL, defaultValue)

    private fun int(
        key: String,
        defaultValue: Int,
        min: Int,
        max: Int,
        scope: SettingsScope = SettingsScope.GLOBAL,
    ): SettingSpec = SettingSpec(
        key = key,
        type = SettingType.INT,
        defaultValue = defaultValue,
        minValue = min.toDouble(),
        maxValue = max.toDouble(),
        scope = scope,
    )

    private fun double(
        key: String,
        defaultValue: Double,
        min: Double,
        max: Double,
        scope: SettingsScope = SettingsScope.GLOBAL,
    ): SettingSpec = SettingSpec(
        key = key,
        type = SettingType.DOUBLE,
        defaultValue = defaultValue,
        minValue = min,
        maxValue = max,
        scope = scope,
    )

    private fun string(
        key: String,
        defaultValue: String,
        maxLength: Int,
        scope: SettingsScope = SettingsScope.GLOBAL,
        isUrl: Boolean = false,
    ): SettingSpec = SettingSpec(
        key = key,
        type = SettingType.STRING,
        defaultValue = defaultValue,
        maxLength = maxLength,
        isUrl = isUrl,
        scope = scope,
    )

    private fun enum(
        key: String,
        defaultValue: String,
        allowed: List<String>,
        scope: SettingsScope = SettingsScope.GLOBAL,
    ): SettingSpec = SettingSpec(
        key = key,
        type = SettingType.ENUM,
        defaultValue = defaultValue,
        enumValues = allowed,
        scope = scope,
    )

    // ── 内部：版本 ───────────────────────────────────────────────────
    private fun readVersion(
        raw: Any?,
        options: SettingsOptions,
        issues: MutableList<SettingsIssue>,
    ): Int {
        if (raw == null) return 0
        val version = integralValue(raw, options.migrateLegacyTypes)
        if (version == null) {
            issues += issue(
                VERSION_KEY,
                "version",
                "schemaVersion must be an integer",
                SettingsSeverity.ERROR,
            )
            return VERSION
        }
        if (version < 1) {
            issues += issue(
                VERSION_KEY,
                "version",
                "invalid schemaVersion, reset to current",
                SettingsSeverity.ERROR,
            )
            return VERSION
        }
        if (version > VERSION) {
            issues += issue(
                VERSION_KEY,
                "version",
                "settings were written by a newer version; unknown keys are preserved",
                SettingsSeverity.ERROR,
            )
            return version.toInt()
        }
        return version.toInt()
    }

    // ── 内部：单值类型 / 范围 / 枚举 / URL ──────────────────────────
    private fun coerce(
        spec: SettingSpec,
        raw: Any?,
        options: SettingsOptions,
        issues: MutableList<SettingsIssue>,
    ): Any? {
        if (spec.secretLike) {
            issues += issue(
                spec.key,
                "secret",
                "secret-like settings must use SecretStore",
                SettingsSeverity.ERROR,
            )
            return null
        }
        return when (spec.type) {
            SettingType.BOOL -> coerceBool(spec, raw, options, issues)
            SettingType.INT -> coerceInt(spec, raw, options, issues)
            SettingType.DOUBLE -> coerceDouble(spec, raw, options, issues)
            SettingType.STRING -> coerceString(spec, raw, issues)
            SettingType.ENUM -> coerceEnum(spec, raw, issues)
        }
    }

    private fun coerceBool(
        spec: SettingSpec,
        raw: Any?,
        options: SettingsOptions,
        issues: MutableList<SettingsIssue>,
    ): Boolean? {
        if (raw is Boolean) return raw
        if (options.migrateLegacyTypes) {
            if (raw is String) {
                when (raw.lowercase()) {
                    "true", "1" -> {
                        issues += migratedIssue(spec.key, "legacy string migrated to bool")
                        return true
                    }
                    "false", "0" -> {
                        issues += migratedIssue(spec.key, "legacy string migrated to bool")
                        return false
                    }
                }
            }
            val number = integralValue(raw, false)
            if (number != null && (number == 0L || number == 1L)) {
                issues += migratedIssue(spec.key, "legacy 0/1 migrated to bool")
                return number == 1L
            }
        }
        issues += typeIssue(spec.key, "expected boolean")
        return null
    }

    private fun coerceInt(
        spec: SettingSpec,
        raw: Any?,
        options: SettingsOptions,
        issues: MutableList<SettingsIssue>,
    ): Int? {
        var value = integralValue(raw, options.migrateLegacyTypes)
        if (value == null) {
            issues += typeIssue(spec.key, "expected integer")
            return null
        }
        if (raw is String) {
            issues += migratedIssue(spec.key, "legacy string migrated to integer")
        }
        val min = spec.minValue!!.toLong()
        val max = spec.maxValue!!.toLong()
        if (value < min) {
            issues += rangeIssue(spec.key, "below minimum " + min + ", clamped")
            value = min
        } else if (value > max) {
            issues += rangeIssue(spec.key, "above maximum " + max + ", clamped")
            value = max
        }
        return value.toInt()
    }

    private fun coerceDouble(
        spec: SettingSpec,
        raw: Any?,
        options: SettingsOptions,
        issues: MutableList<SettingsIssue>,
    ): Double? {
        var value = when (raw) {
            is Double -> raw
            is Float -> raw.toDouble()
            is Number -> raw.toDouble()
            is String -> if (options.migrateLegacyTypes) raw.toDoubleOrNull() else null
            else -> null
        }?.takeIf { it.isFinite() }
        if (value == null) {
            issues += typeIssue(spec.key, "expected number")
            return null
        }
        if (raw is String) {
            issues += migratedIssue(spec.key, "legacy string migrated to number")
        }
        val min = spec.minValue!!
        val max = spec.maxValue!!
        if (value < min) {
            issues += rangeIssue(spec.key, "below minimum " + min + ", clamped")
            value = min
        } else if (value > max) {
            issues += rangeIssue(spec.key, "above maximum " + max + ", clamped")
            value = max
        }
        return value
    }

    private fun coerceString(
        spec: SettingSpec,
        raw: Any?,
        issues: MutableList<SettingsIssue>,
    ): String? {
        if (raw !is String) {
            issues += typeIssue(spec.key, "expected string")
            return null
        }
        if (spec.maxLength > 0 && raw.toByteArray(Charsets.UTF_8).size > spec.maxLength) {
            issues += issue(
                spec.key,
                "length",
                "longer than " + spec.maxLength + " bytes",
                SettingsSeverity.ERROR,
            )
            return null
        }
        if (spec.isUrl && raw.isNotEmpty()) {
            if (!isHttpUrl(raw)) {
                issues += issue(
                    spec.key,
                    "url",
                    "must be an http(s) URL",
                    SettingsSeverity.ERROR,
                )
                return null
            }
            if (urlLooksSensitive(raw)) {
                issues += issue(
                    spec.key,
                    "url",
                    "URL appears to contain credentials; keep secrets in SecretStore",
                )
            }
        }
        return raw
    }

    private fun coerceEnum(
        spec: SettingSpec,
        raw: Any?,
        issues: MutableList<SettingsIssue>,
    ): String? {
        if (raw !is String) {
            issues += typeIssue(spec.key, "expected string")
            return null
        }
        if (raw in spec.enumValues) return raw
        issues += issue(spec.key, "enum", "unknown value, using default")
        return null
    }

    private fun integralValue(raw: Any?, migrateStrings: Boolean): Long? = when (raw) {
        is Byte -> raw.toLong()
        is Short -> raw.toLong()
        is Int -> raw.toLong()
        is Long -> raw
        is Float -> raw.takeIf {
            it.isFinite() && it == it.toLong().toFloat()
        }?.toLong()
        is Double -> raw.takeIf {
            it.isFinite() && kotlin.math.abs(it) <= MAX_EXACT_DOUBLE && it == it.toLong().toDouble()
        }?.toLong()
        is String -> if (migrateStrings) raw.trim().toLongOrNull() else null
        else -> null
    }

    // ── 内部：旧键迁移 ───────────────────────────────────────────────
    private data class LegacyMapping(
        val oldKey: String,
        val targetKey: String,
        val mapValue: (Any?) -> Any?,
    )

    private val LEGACY_MAPPINGS: List<LegacyMapping> = listOf(
        LegacyMapping("theme_mode", "theme") { legacyTheme(it) },
        LegacyMapping("font_size", "fontSize") { it },
        LegacyMapping("line_height", "lineHeight") { it },
        LegacyMapping("default_sort", "defaultSortBy") { legacySort(it) },
    )

    private fun isLegacyKey(key: String): Boolean = LEGACY_MAPPINGS.any { it.oldKey == key }

    private fun legacyTheme(raw: Any?): Any? = when (raw?.toString()?.trim()?.lowercase()) {
        "light" -> "light"
        "dark" -> "dark"
        "sepia" -> "sepia"
        // Android 旧的 AppThemeMode.System 没有桌面 schema 对应值；按默认暗色迁移。
        "system" -> "dark"
        else -> raw
    }

    private fun legacySort(raw: Any?): Any? {
        val text = raw?.toString()?.trim()?.lowercase() ?: return raw
        return when (text.replace("_", "").replace("-", "")) {
            "recentread", "lastopened" -> "last_opened"
            "addedat", "added" -> "added_at"
            "title" -> "title"
            "author" -> "author"
            // LibrarySort.Rating 在桌面 schema 没有同名项：落到稳定的默认排序。
            "rating" -> "last_opened"
            else -> raw
        }
    }

    // ── 内部：密钥识别 / URL ─────────────────────────────────────────
    private val JOINED_SECRET_MARKERS = listOf(
        "apikey",
        "privatekey",
        "accesskey",
        "clientsecret",
        "authorization",
    )

    private fun segmentLooksSecret(segment: String): Boolean {
        if (segment in EXACT_SECRET_SEGMENTS) return true
        if (SECRET_SUFFIXES.any { segment.endsWith(it) }) return true
        if (SECRET_CONTAINS.any { segment.contains(it) }) return true
        return false
    }

    private val EXACT_SECRET_SEGMENTS = setOf(
        "auth",
        "bearer",
        "token",
        "secret",
        "password",
        "passwd",
        "passphrase",
        "credential",
    )

    private val SECRET_SUFFIXES = listOf(
        "apikey",
        "token",
        "secret",
        "password",
        "passwd",
        "credential",
        "credentials",
        "privatekey",
        "accesskey",
        "sessionkey",
        "signingkey",
        "encryptionkey",
    )

    private val SECRET_CONTAINS = listOf(
        "apikey",
        "privatekey",
        "accesskey",
        "clientsecret",
        "authorization",
    )

    private fun splitKey(key: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                parts += current.toString()
                current.clear()
            }
        }
        for (index in key.indices) {
            val c = key[index]
            if (c == '.' || c == '_' || c == '-' || c == '/' || c == ':' || c == ' ' || c == '@') {
                flush()
                continue
            }
            if (c.isUpperCase() && current.isNotEmpty()) {
                val previous = key[index - 1]
                if (previous.isLowerCase() || previous.isDigit()) flush()
            }
            if (c.isLetterOrDigit()) current.append(c.lowercaseChar())
        }
        flush()
        return parts
    }

    private fun firstSecretLikePath(value: Any?, path: String): String? = when (value) {
        is Map<*, *> -> {
            var found: String? = null
            for ((key, child) in value) {
                val childPath = if (path.isEmpty()) key?.toString().orEmpty() else path + "." + key
                val text = key?.toString().orEmpty()
                if (isSecretLikeKey(text)) {
                    found = childPath
                    break
                }
                found = firstSecretLikePath(child, childPath)
                if (found != null) break
            }
            found
        }
        is List<*> -> {
            var found: String? = null
            for ((index, child) in value.withIndex()) {
                found = firstSecretLikePath(child, path + "[" + index + "]")
                if (found != null) break
            }
            found
        }
        else -> null
    }

    private fun isHttpUrl(text: String): Boolean {
        val lower = text.lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    private fun urlLooksSensitive(text: String): Boolean {
        val lower = text.lowercase()
        val scheme = lower.indexOf("://")
        if (scheme >= 0) {
            val authorityBegin = scheme + 3
            val authorityEnd = lower.indexOfAny(charArrayOf('/', '?', '#'), authorityBegin)
            val authority = lower.substring(
                authorityBegin,
                if (authorityEnd < 0) lower.length else authorityEnd,
            )
            if (authority.contains('@')) return true
        }
        return URL_SECRET_HINTS.any { lower.contains(it) }
    }

    private val URL_SECRET_HINTS = listOf(
        "api_key=",
        "apikey=",
        "access_token=",
        "token=",
        "secret=",
        "password=",
        "signature=",
        "key=",
    )

    private const val MAX_EXACT_DOUBLE = 9007199254740992.0

    // ── 内部：issue 构造 ─────────────────────────────────────────────
    private fun issue(
        key: String,
        code: String,
        message: String,
        severity: SettingsSeverity = SettingsSeverity.WARNING,
    ): SettingsIssue = SettingsIssue(key, code, message, severity)

    private fun typeIssue(key: String, message: String): SettingsIssue =
        issue(key, "type", message, SettingsSeverity.ERROR)

    private fun migratedIssue(key: String, message: String): SettingsIssue =
        issue(key, "migrated", message)

    private fun rangeIssue(key: String, message: String): SettingsIssue =
        issue(key, "range", message)
}
