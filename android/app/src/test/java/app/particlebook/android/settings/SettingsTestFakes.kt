package app.particlebook.android.settings

import app.particlebook.android.platform.SecretStore
import app.particlebook.android.platform.SecretStoreError
import app.particlebook.android.platform.SecretStoreException

/** 纯 JVM 的 SettingsStorage fake，行为贴近 SharedPreferences。 */
internal class FakeSettingsStorage(initial: Map<String, Any?> = emptyMap()) : SettingsStorage {
    private val data = LinkedHashMap<String, Any?>()

    init {
        data.putAll(initial)
    }

    override fun readAll(): Map<String, Any?> = LinkedHashMap(data)

    override fun put(key: String, value: Any?) {
        if (value == null) data.remove(key) else data[key] = value
    }

    override fun remove(key: String) {
        data.remove(key)
    }

    override fun clear() {
        data.clear()
    }

    fun snapshot(): Map<String, Any?> = LinkedHashMap(data)

    fun contains(key: String): Boolean = data.containsKey(key)

    fun size(): Int = data.size
}

internal class FakeLegacySettingsSource(
    private val values: Map<String, Any?>,
) : SettingsLegacySource {
    override fun readLegacy(): Map<String, Any?> = LinkedHashMap(values)
}

internal class FakeSecretStore : SecretStore {
    private var selected = "default"
    private val slots = LinkedHashMap<String, String>()

    val aliasCalls = mutableListOf<String>()
    var failGet: SecretStoreException? = null
    var failPut: SecretStoreException? = null
    var failDelete: SecretStoreException? = null

    override fun setAlias(alias: String) {
        aliasCalls += alias
        selected = alias
    }

    override fun get(): String? {
        failGet?.let { throw it }
        return slots[selected]
    }

    override fun put(value: String) {
        failPut?.let { throw it }
        slots[selected] = value
    }

    override fun delete() {
        failDelete?.let { throw it }
        slots.remove(selected)
    }

    fun storedFor(alias: String): String? = slots[alias]

    companion object {
        fun unavailable(): SecretStoreException =
            SecretStoreException(SecretStoreError.KEY_UNAVAILABLE, "keystore unavailable")
    }
}
