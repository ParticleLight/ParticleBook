package app.particlebook.android.zlib

import java.security.MessageDigest

/**
 * Z-Library 反爬挑战求解（纯 Kotlin，无 Android 依赖，可在 JVM 单测里直接跑）。
 *
 * 挑战页逻辑（实测，见 docs/android-port/zlibrary-p1a-spec.md §1.3）：
 * 取 40 位大写 HEX 常量 C，求最小 i 使 `sha1(C + i)` 摘要满足
 * `digest[n] == 0xB0 && digest[n+1] == 0x0B`，然后带 cookie `c_token = C + i` 重放请求。
 *
 * **n 不固定**：页面 JS 写的是 `n1 = parseInt('0x' + C[0])`，即取常量首字符的十六进制值：
 * 旧常量 `549E96…`（首位 5）→ n=5；服务端轮换到 `690F18…`（首位 6）→ n=6。
 * 写死 5/6 会在常量轮换后恒 503（R7-A 真机根因）。
 */
object ZlibChallenge {

    /** nonce 搜索上限（实测 348,329 命中）。 */
    const val MAX_NONCE: Int = 5_000_000

    private val CONSTANT_RE = Regex("'([0-9A-Fa-f]{40})'")

    data class Solution(val constant: String, val nonce: Int) {
        /** 直接写进 cookie 的 token：常量 + nonce。 */
        val token: String get() = constant + nonce
    }

    /** 从挑战页 HTML 提取 40 位 HEX 常量；提取不到返回 null。 */
    fun extractConstant(html: String): String? =
        CONSTANT_RE.find(html)?.groupValues?.get(1)?.uppercase()

    /** 提取常量并暴力求解 nonce；任一环节失败返回 null。 */
    fun solve(html: String): Solution? {
        val constant = extractConstant(html) ?: return null
        val nonce = solveNonce(constant) ?: return null
        return Solution(constant, nonce)
    }

    /**
     * 页面 JS 的校验下标：`n1 = parseInt('0x' + 常量首字符)`（0..15）；非法常量返回 null。
     */
    fun nonceIndex(constant: String): Int? {
        val first = constant.firstOrNull() ?: return null
        val index = Character.digit(first, 16)
        if (index < 0 || index + 1 >= 20) return null
        return index
    }

    /**
     * 暴力搜索满足摘要字节条件的 nonce。
     *
     * 用预分配缓冲写十进制数字，避免 34 万次字符串分配（实测 < 400ms）。
     */
    fun solveNonce(constant: String, maxNonce: Int = MAX_NONCE): Int? {
        val index = nonceIndex(constant) ?: return null
        val prefix = constant.toByteArray(Charsets.US_ASCII)
        val buffer = ByteArray(prefix.size + 8)
        prefix.copyInto(buffer)
        val digest = MessageDigest.getInstance("SHA-1")
        val target = 0xB0.toByte()
        val targetNext = 0x0B.toByte()

        for (i in 0 until maxNonce) {
            var end = prefix.size + digitsOf(i)
            if (i == 0) {
                buffer[prefix.size] = '0'.code.toByte()
            } else {
                var value = i
                var index = end - 1
                while (value > 0) {
                    buffer[index--] = ('0'.code + value % 10).toByte()
                    value /= 10
                }
            }
            digest.update(buffer, 0, end)
            val sum = digest.digest()
            if (sum[index] == target && sum[index + 1] == targetNext) return i
        }
        return null
    }

    private fun digitsOf(value: Int): Int {
        if (value == 0) return 1
        var v = value
        var digits = 0
        while (v > 0) {
            v /= 10
            digits++
        }
        return digits
    }
}
