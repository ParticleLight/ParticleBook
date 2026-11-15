package app.particlebook.android.settings

import app.particlebook.android.platform.SecretStoreError
import app.particlebook.android.platform.SecretStoreException

/** 设置页需要展示的翻译 Key 状态（A023-3/4）。 */
enum class TranslationKeyState {
    NOT_CONFIGURED,
    CONFIGURED,
    CORRUPTED,
}

/**
 * Key 状态快照。[message] 是给用户看的中文提示，不包含 Key 本身。
 */
data class TranslationKeyStatus(
    val state: TranslationKeyState,
    val message: String? = null,
) {
    val isConfigured: Boolean get() = state == TranslationKeyState.CONFIGURED
    val isCorrupted: Boolean get() = state == TranslationKeyState.CORRUPTED

    /** 设置行右侧的短状态：已配置 / 未配置 / 损坏。 */
    val shortLabel: String
        get() = when (state) {
            TranslationKeyState.CONFIGURED -> "已配置"
            TranslationKeyState.NOT_CONFIGURED -> "未配置"
            TranslationKeyState.CORRUPTED -> "损坏"
        }
}

/** 保存 / 清除操作的统一结果；[message] 可直接显示在设置页。 */
data class TranslationKeyResult(
    val ok: Boolean,
    val message: String,
)

/**
 * 翻译 API Key 的设置页控制器。
 *
 * 只依赖 [SettingsSecretBridge]，不依赖 Compose / Context / AppRoot，便于 JVM 单测：
 * - [status] 把 SecretStore 的 null / 密文错误映射成三态，所有异常都转成中文提示；
 * - [save] / [clear] 包装 Keystore 异常，不把异常文本或 Key 明文传给 UI；
 * - [test] 走 [TranslationMock]，永不联网。
 *
 * 接线（Lead 在 SettingsScreen 中一行）：
 * TranslationKeySection(remember { TranslationKeyController(SettingsSecretBridge(AndroidSecretStore(context.applicationContext))) })
 */
class TranslationKeyController(
    private val bridge: SettingsSecretBridge,
) {

    /** 读取当前 Key；未配置或损坏时返回 null（损坏详情见 [status]）。 */
    fun read(): String? = try {
        bridge.getApiKey()
    } catch (_: SecretStoreException) {
        null
    } catch (_: Exception) {
        null
    }

    /** 三态状态；任何存储异常都映射为 CORRUPTED，并给出中文恢复提示。 */
    fun status(): TranslationKeyStatus = try {
        val key = bridge.getApiKey()
        if (key.isNullOrBlank()) {
            TranslationKeyStatus(TranslationKeyState.NOT_CONFIGURED)
        } else {
            TranslationKeyStatus(TranslationKeyState.CONFIGURED)
        }
    } catch (e: SecretStoreException) {
        TranslationKeyStatus(TranslationKeyState.CORRUPTED, messageFor(e.error))
    } catch (_: Exception) {
        TranslationKeyStatus(TranslationKeyState.CORRUPTED, "无法读取已保存的 Key，请重试")
    }

    /** 保存非空 Key；空白输入不落盘。 */
    fun save(rawKey: String): TranslationKeyResult {
        val key = rawKey.trim()
        if (key.isEmpty()) {
            return TranslationKeyResult(false, "请输入 API Key")
        }
        return try {
            bridge.putApiKey(key)
            TranslationKeyResult(true, "已保存 API Key")
        } catch (e: SecretStoreException) {
            TranslationKeyResult(false, messageFor(e.error))
        } catch (_: Exception) {
            TranslationKeyResult(false, "保存失败，请重试")
        }
    }

    /** 清除 Key；Keystore 删除失败时返回中文错误。 */
    fun clear(): TranslationKeyResult = try {
        bridge.deleteApiKey()
        TranslationKeyResult(true, "已清除 API Key")
    } catch (e: SecretStoreException) {
        TranslationKeyResult(false, messageForClear(e.error))
    } catch (_: Exception) {
        TranslationKeyResult(false, "清除失败，请重试")
    }

    /**
     * 设置页测试按钮：按当前 Key 状态调用 mock 翻译。
     * 不会把 Key 传给 mock 返回值，也不会写日志。
     */
    fun test(text: String = TranslationMock.DEFAULT_SAMPLE_TEXT): TranslationMockResult {
        val status = status()
        if (status.state == TranslationKeyState.NOT_CONFIGURED) {
            return TranslationMock.translate(null, text)
        }
        if (status.state == TranslationKeyState.CORRUPTED) {
            return TranslationMockResult(
                ok = false,
                message = status.message ?: "已保存的 Key 无法读取，请重新设置",
                translatedText = null,
            )
        }
        return TranslationMock.translate(read(), text)
    }

    private fun messageFor(error: SecretStoreError): String = when (error) {
        SecretStoreError.INVALID_ALIAS -> "Key 存储槽位无效，请重试"
        SecretStoreError.KEY_UNAVAILABLE,
        SecretStoreError.CRYPTO_UNAVAILABLE,
        -> "密钥存储不可用，请稍后重试或重新设置 Key"
        SecretStoreError.DECRYPT_FAILED,
        SecretStoreError.CIPHERTEXT_CORRUPT,
        -> "已保存的 Key 无法解密，请重新设置"
        SecretStoreError.ENCRYPT_FAILED,
        SecretStoreError.STORAGE_FAILED,
        -> "密钥保存失败，请重试"
    }

    private fun messageForClear(error: SecretStoreError): String = when (error) {
        SecretStoreError.ENCRYPT_FAILED,
        SecretStoreError.STORAGE_FAILED,
        -> "密钥清除失败，请重试"
        else -> messageFor(error)
    }
}
