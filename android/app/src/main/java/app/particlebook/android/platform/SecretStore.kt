package app.particlebook.android.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 小型密钥/凭据存储契约。
 *
 * 调用方通过 [setAlias] 选择一个逻辑凭据槽位，然后使用 [get]/[put]/[delete] 读写。
 * 不同槽位相互隔离，可以保存多个 API key、cookie 或 token。
 *
 * 安全约束：
 * - 实现只能保存密文；缺少密钥或解密失败时必须抛 [SecretStoreException]，不得回退明文。
 * - [get] 在槽位从未写入时返回 null；写入过但无法读取时抛错，方便调用方区分「未配置」和「损坏」。
 * - 实现需线程安全；同一实例上的 setAlias/get/put/delete 可以并发调用。
 */
interface SecretStore {
    /** 选择当前逻辑槽位。非法 alias 抛 [SecretStoreException]，error = INVALID_ALIAS。 */
    fun setAlias(alias: String)

    /** 读取当前槽位；未配置返回 null，密钥不可用/密文损坏抛 [SecretStoreException]。 */
    fun get(): String?

    /** 覆盖写入当前槽位；加密或落盘失败抛 [SecretStoreException]。 */
    fun put(value: String)

    /** 删除当前槽位及其密钥；槽位不存在时也视为成功。 */
    fun delete()
}

/** 结构化错误码；调用方根据 error 决定提示、重试或引导重新配置。 */
enum class SecretStoreError {
    INVALID_ALIAS,
    KEY_UNAVAILABLE,
    ENCRYPT_FAILED,
    DECRYPT_FAILED,
    CIPHERTEXT_CORRUPT,
    STORAGE_FAILED,
    CRYPTO_UNAVAILABLE,
}

/**
 * SecretStore 的受检错误。
 *
 * message 与 cause 只用于诊断；禁止把明文 secret 放入 message 或 cause。
 */
class SecretStoreException(
    val error: SecretStoreError,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * 密文 envelope：version|base64(iv)|base64(ciphertext)。
 *
 * ciphertext 是 AES/GCM 的 doFinal 输出，已包含 128-bit 认证 tag；iv 固定 12 字节。
 * 该对象不依赖 Android，可在 JVM 单测中完整验证格式与边界。
 */
class SecretEnvelope(
    val version: Int,
    val iv: ByteArray,
    val ciphertext: ByteArray,
)

object SecretEnvelopeCodec {
    const val VERSION = 1
    const val IV_BYTES = 12
    const val TAG_BYTES = 16

    private const val SEPARATOR = '|'

    fun encode(iv: ByteArray, ciphertext: ByteArray): String {
        if (iv.size != IV_BYTES) {
            throw SecretStoreException(
                SecretStoreError.CIPHERTEXT_CORRUPT,
                "invalid GCM IV length: " + iv.size,
            )
        }
        if (ciphertext.size < TAG_BYTES) {
            throw SecretStoreException(
                SecretStoreError.CIPHERTEXT_CORRUPT,
                "GCM ciphertext is shorter than the authentication tag",
            )
        }
        val encoder = Base64.getEncoder()
        return VERSION.toString() + SEPARATOR +
            encoder.encodeToString(iv) + SEPARATOR +
            encoder.encodeToString(ciphertext)
    }

    fun decode(encoded: String): SecretEnvelope {
        if (encoded.isBlank()) {
            throw corrupt("empty envelope")
        }
        val parts = encoded.split(SEPARATOR)
        if (parts.size != 3) {
            throw corrupt("envelope must have exactly three parts")
        }
        if (parts[0] != VERSION.toString()) {
            throw corrupt("unsupported envelope version: " + parts[0])
        }
        val decoder = Base64.getDecoder()
        val iv = try {
            decoder.decode(parts[1])
        } catch (e: IllegalArgumentException) {
            throw corrupt("invalid base64 IV", e)
        }
        val ciphertext = try {
            decoder.decode(parts[2])
        } catch (e: IllegalArgumentException) {
            throw corrupt("invalid base64 ciphertext", e)
        }
        if (iv.size != IV_BYTES) {
            throw corrupt("invalid GCM IV length: " + iv.size)
        }
        if (ciphertext.size < TAG_BYTES) {
            throw corrupt("GCM ciphertext is shorter than the authentication tag")
        }
        return SecretEnvelope(VERSION, iv, ciphertext)
    }

    private fun corrupt(message: String, cause: Throwable? = null): SecretStoreException =
        SecretStoreException(SecretStoreError.CIPHERTEXT_CORRUPT, message, cause)
}

/**
 * Android Keystore AES-GCM 实现。
 *
 * - 每个 alias 对应一个 AndroidKeyStore AES-256 密钥，密文写入应用私有 SharedPreferences。
 * - SharedPreferences 只保存 envelope 字符串，绝不保存明文。
 * - 密钥被系统删除/失效时抛 KEY_UNAVAILABLE；密文解析失败或 GCM tag 不匹配抛 CIPHERTEXT_CORRUPT。
 * - 当前不含用户认证要求，因此后台下载/同步在设备解锁后可用；若将来要求生物识别，需要新增错误分支。
 *
 * Keystore 只能在 Android 运行时验证；JVM 单测覆盖 envelope 与错误路径，真机验收由 p15 负责。
 */
class AndroidSecretStore(
    context: Context,
    initialAlias: String = DEFAULT_ALIAS,
) : SecretStore {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    @Volatile
    private var currentAlias: String = initialAlias

    init {
        validateAlias(initialAlias)
    }

    /**
     * 键库懒加载；AndroidKeyStore 初始化失败会被转成 CRYPTO_UNAVAILABLE，而不是泄漏 provider 异常。
     */
    private val keyStore: KeyStore by lazy {
        try {
            KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        } catch (e: Exception) {
            throw SecretStoreException(
                SecretStoreError.CRYPTO_UNAVAILABLE,
                "Android Keystore is unavailable",
                e,
            )
        }
    }

    override fun setAlias(alias: String) {
        validateAlias(alias)
        synchronized(lock) {
            currentAlias = alias
        }
    }

    override fun get(): String? = synchronized(lock) { readLocked() }

    override fun put(value: String) {
        synchronized(lock) { putLocked(value) }
    }

    override fun delete() {
        synchronized(lock) { deleteLocked() }
    }

    private fun readLocked(): String? {
        val alias = currentAlias
        val stored = try {
            prefs.getString(prefKey(alias), null)
        } catch (e: ClassCastException) {
            throw SecretStoreException(
                SecretStoreError.STORAGE_FAILED,
                "secret slot has an unexpected value type",
                e,
            )
        }
        if (stored == null) return null

        val envelope = SecretEnvelopeCodec.decode(stored)
        val key = existingKey(alias)
        val cipher = newCipher()
        try {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, envelope.iv))
            val plaintext = cipher.doFinal(envelope.ciphertext)
            return String(plaintext, Charsets.UTF_8)
        } catch (e: SecretStoreException) {
            throw e
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw keyUnavailable(e)
        } catch (e: AEADBadTagException) {
            throw SecretStoreException(
                SecretStoreError.CIPHERTEXT_CORRUPT,
                "GCM authentication failed",
                e,
            )
        } catch (e: GeneralSecurityException) {
            throw SecretStoreException(
                SecretStoreError.DECRYPT_FAILED,
                "secret decryption failed",
                e,
            )
        } catch (e: RuntimeException) {
            throw SecretStoreException(
                SecretStoreError.DECRYPT_FAILED,
                "secret decryption failed",
                e,
            )
        }
    }

    private fun putLocked(value: String) {
        val alias = currentAlias
        val key = getOrCreateKey(alias)
        val cipher = newCipher()
        val encoded = try {
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            if (iv == null || iv.size != SecretEnvelopeCodec.IV_BYTES) {
                throw SecretStoreException(
                    SecretStoreError.ENCRYPT_FAILED,
                    "GCM IV has an unexpected length",
                )
            }
            val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            SecretEnvelopeCodec.encode(iv, ciphertext)
        } catch (e: SecretStoreException) {
            throw e
        } catch (e: GeneralSecurityException) {
            throw SecretStoreException(
                SecretStoreError.ENCRYPT_FAILED,
                "secret encryption failed",
                e,
            )
        } catch (e: RuntimeException) {
            throw SecretStoreException(
                SecretStoreError.ENCRYPT_FAILED,
                "secret encryption failed",
                e,
            )
        }

        val committed = try {
            prefs.edit().putString(prefKey(alias), encoded).commit()
        } catch (e: RuntimeException) {
            throw SecretStoreException(
                SecretStoreError.STORAGE_FAILED,
                "failed to persist encrypted secret",
                e,
            )
        }
        if (!committed) {
            throw SecretStoreException(
                SecretStoreError.STORAGE_FAILED,
                "failed to persist encrypted secret",
            )
        }
    }

    private fun deleteLocked() {
        val alias = currentAlias
        try {
            keyStore.deleteEntry(keyAlias(alias))
        } catch (e: SecretStoreException) {
            throw e
        } catch (e: GeneralSecurityException) {
            throw keyUnavailable(e)
        } catch (e: RuntimeException) {
            throw keyUnavailable(e)
        }

        val removed = try {
            prefs.edit().remove(prefKey(alias)).commit()
        } catch (e: RuntimeException) {
            throw SecretStoreException(
                SecretStoreError.STORAGE_FAILED,
                "failed to remove encrypted secret",
                e,
            )
        }
        if (!removed) {
            throw SecretStoreException(
                SecretStoreError.STORAGE_FAILED,
                "failed to remove encrypted secret",
            )
        }
    }

    private fun existingKey(alias: String): SecretKey {
        val key = try {
            keyStore.getKey(keyAlias(alias), null)
        } catch (e: SecretStoreException) {
            throw e
        } catch (e: UnrecoverableKeyException) {
            throw keyUnavailable(e)
        } catch (e: GeneralSecurityException) {
            throw keyUnavailable(e)
        } catch (e: RuntimeException) {
            throw keyUnavailable(e)
        }
        if (key == null) {
            throw SecretStoreException(
                SecretStoreError.KEY_UNAVAILABLE,
                "secret key is missing",
            )
        }
        if (key !is SecretKey) {
            throw SecretStoreException(
                SecretStoreError.KEY_UNAVAILABLE,
                "keystore entry is not a secret key",
            )
        }
        return key
    }

    private fun getOrCreateKey(alias: String): SecretKey {
        val key = try {
            keyStore.getKey(keyAlias(alias), null)
        } catch (e: SecretStoreException) {
            throw e
        } catch (e: UnrecoverableKeyException) {
            throw keyUnavailable(e)
        } catch (e: GeneralSecurityException) {
            throw keyUnavailable(e)
        } catch (e: RuntimeException) {
            throw keyUnavailable(e)
        }
        if (key != null) {
            if (key !is SecretKey) {
                throw SecretStoreException(
                    SecretStoreError.KEY_UNAVAILABLE,
                    "keystore entry is not a secret key",
                )
            }
            return key
        }
        return try {
            generateKey(alias)
        } catch (e: SecretStoreException) {
            throw e
        } catch (e: GeneralSecurityException) {
            throw keyUnavailable(e)
        } catch (e: RuntimeException) {
            throw keyUnavailable(e)
        }
    }

    private fun generateKey(alias: String): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        val spec = KeyGenParameterSpec.Builder(
            keyAlias(alias),
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun newCipher(): Cipher = try {
        Cipher.getInstance(CIPHER_TRANSFORMATION)
    } catch (e: GeneralSecurityException) {
        throw SecretStoreException(
            SecretStoreError.CRYPTO_UNAVAILABLE,
            "AES/GCM cipher is unavailable",
            e,
        )
    }

    private fun keyUnavailable(cause: Throwable? = null): SecretStoreException =
        SecretStoreException(
            SecretStoreError.KEY_UNAVAILABLE,
            "secret key is unavailable",
            cause,
        )

    private fun prefKey(alias: String): String = PREF_PREFIX + alias

    private fun keyAlias(alias: String): String = KEY_PREFIX + alias

    private fun validateAlias(alias: String) {
        if (!ALIAS_PATTERN.matches(alias)) {
            throw SecretStoreException(
                SecretStoreError.INVALID_ALIAS,
                "alias must match [A-Za-z0-9._-]{1,128}",
            )
        }
    }

    companion object {
        const val DEFAULT_ALIAS = "default"

        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val TAG_BITS = 128
        private const val PREFS_NAME = "particlebook_secrets"
        private const val KEY_PREFIX = "pb_secret_"
        private const val PREF_PREFIX = "secret."
        private val ALIAS_PATTERN = Regex("[A-Za-z0-9._-]{1,128}")
    }
}
