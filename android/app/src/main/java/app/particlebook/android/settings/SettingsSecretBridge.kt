package app.particlebook.android.settings

import app.particlebook.android.platform.SecretStore

/**
 * 翻译 API Key 的 SecretStore 绑定。
 *
 * 明确边界：API Key 绝不进入 SettingsSchema / SharedPreferences / 备份；
 * SettingsStore.exportRedacted() 只导出非 secret 设置。UI 通过本桥读写 Key，
 * Key 本身只由 Android Keystore 加密后落盘。
 *
 * [SecretStore] 的 get/put/delete 是线程安全的；本桥再串行化 alias 切换和值操作，
 * 避免 setAlias 与 put 交错导致写错槽位。
 */
class SettingsSecretBridge(
    private val secretStore: SecretStore,
    private val initialAlias: String = API_KEY_ALIAS,
) {
    private val lock = Any()
    private var alias: String = initialAlias

    init {
        // SecretStore 的默认槽位是 "default"；桥接层构造时强制切到翻译 Key 槽位。
        synchronized(lock) {
            secretStore.setAlias(initialAlias)
            alias = initialAlias
        }
    }

    fun setAlias(value: String) = synchronized(lock) {
        secretStore.setAlias(value)
        alias = value
    }

    /** 返回已保存的 API Key；未保存返回 null；Keystore 不可用等错误原样抛出。 */
    fun getApiKey(): String? = synchronized(lock) { secretStore.get() }

    fun putApiKey(value: String) = synchronized(lock) { secretStore.put(value) }

    fun deleteApiKey() = synchronized(lock) { secretStore.delete() }

    fun currentAlias(): String = synchronized(lock) { alias }

    companion object {
        /** 与桌面 settings 的 translation apiKey 概念对应，但只存 SecretStore。 */
        const val API_KEY_ALIAS = "translation.apiKey"
    }
}