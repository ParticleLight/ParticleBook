package app.particlebook.android.zlib

import android.content.SharedPreferences

/**
 * T24：pin（用户手动固定的线路）持久化出口。
 *
 * 独立接口是为了让纯 JVM 单测注入内存实现——[ZlibClient] 在无 Context 创建时拿不到
 * SharedPreferences，但 pin 的持久化/恢复语义仍必须可测。
 */
internal interface ZlibMirrorPinStore {
    fun readPin(): String?

    /** null/空白 = 取消 pin。 */
    fun writePin(url: String?)
}

/**
 * 生产实现：存到 [ZlibClient] 用的同一个 prefs 文件（"zlib"），但用**独立 key**，
 * 避免「用户 pin」被搜索成功后的 [ZlibClient.KEY_MIRROR] 候选缓存覆盖而丢失。
 */
internal class ZlibPrefsMirrorPinStore(
    private val prefs: SharedPreferences?,
) : ZlibMirrorPinStore {

    override fun readPin(): String? = prefs?.getString(KEY_PIN, null)

    override fun writePin(url: String?) {
        val editor = prefs?.edit() ?: return
        if (url.isNullOrBlank()) {
            editor.remove(KEY_PIN)
        } else {
            editor.putString(KEY_PIN, url)
        }
        editor.apply()
    }

    companion object {
        const val KEY_PIN: String = "zlib_pin_mirror"
    }
}
