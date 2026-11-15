package app.particlebook.android.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * Android SharedPreferences 适配器。
 *
 * 只做键值存取，schema / 范围 / 迁移都在纯 JVM 的 [SettingsSchema]/[SettingsStore]。
 * 书级键使用 "book.<bookId>.<key>" 前缀；所有全局键使用 "global." 前缀，
 * 这样同一个 prefs 文件里还可以安全存放 meta 数据，不会和设置键混淆。
 *
 * Double 在 SharedPreferences 中没有原生类型：值本身按字符串保存，同时写一个
 * 伴生标记键 "<key>__pb_double"，读回时还原为 Double，避免 Float 精度损失（lineHeight 1.8），
 * 也避免普通字符串值恰好以某个前缀开头时被误判。
 */
class SharedPreferencesSettingsStorage(
    private val prefs: SharedPreferences,
    private val legacyPrefs: SharedPreferences? = null,
) : SettingsStorage, SettingsLegacySource {

    override fun readAll(): Map<String, Any?> {
        val all = prefs.all
        val result = LinkedHashMap<String, Any?>()
        for ((key, value) in all) {
            if (!key.endsWith(DOUBLE_MARKER_SUFFIX)) result[key] = value
        }
        for ((key, value) in all) {
            if (!key.endsWith(DOUBLE_MARKER_SUFFIX) || value != DOUBLE_MARKER) continue
            val base = key.removeSuffix(DOUBLE_MARKER_SUFFIX)
            val text = all[base] as? String ?: continue
            result[base] = text.toDoubleOrNull() ?: text
        }
        return result
    }

    override fun put(key: String, value: Any?) {
        if (value == null) {
            remove(key)
            return
        }
        val editor = prefs.edit()
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Double -> {
                editor.putString(key, value.toString())
                editor.putString(key + DOUBLE_MARKER_SUFFIX, DOUBLE_MARKER)
            }
            is String -> editor.putString(key, value)
            is Set<*> -> {
                @Suppress("UNCHECKED_CAST")
                editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
            else -> editor.putString(key, value.toString())
        }
        check(editor.commit()) { "failed to persist setting " + key }
    }

    override fun remove(key: String) {
        val marker = key + DOUBLE_MARKER_SUFFIX
        if (!prefs.contains(key) && !prefs.contains(marker)) return
        check(prefs.edit().remove(key).remove(marker).commit()) { "failed to remove setting " + key }
    }

    override fun clear() {
        check(prefs.edit().clear().commit()) { "failed to clear settings" }
    }

    /** 旧 SharedPreferences("pb_mobile_ui") -> 新 schema 的一次性迁移来源。 */
    override fun readLegacy(): Map<String, Any?> {
        val source = legacyPrefs ?: return emptyMap()
        val result = LinkedHashMap<String, Any?>()
        for ((key, value) in source.all) {
            if (!key.endsWith(DOUBLE_MARKER_SUFFIX)) result[key] = value
        }
        return result
    }

    companion object {
        const val DOUBLE_MARKER_SUFFIX = "__pb_double"
        const val DOUBLE_MARKER = "1"

        /** 生产入口；新偏好与旧 UI 偏好分开，迁移只在首次 load 时发生。 */
        fun from(context: Context): SharedPreferencesSettingsStorage = SharedPreferencesSettingsStorage(
            prefs = context.getSharedPreferences(SettingsStore.PREFERENCES_NAME, Context.MODE_PRIVATE),
            legacyPrefs = context.getSharedPreferences(
                SettingsStore.LEGACY_PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            ),
        )
    }
}