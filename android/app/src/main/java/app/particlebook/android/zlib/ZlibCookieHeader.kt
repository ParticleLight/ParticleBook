package app.particlebook.android.zlib

/**
 * Cookie 头规范化（R7-F-FIX，纯 JVM 可单测）。
 *
 * 根因：java.net.CookieManager 遇到带 `Version=1` 的 cookie（z-lib 的 remix_userkey 就是）
 * 会按 RFC 2965 输出。真实形态（Android 12 / AOSP InMemoryCookieStore.sortByPath）是
 * **单条拼接串**：`$Version="1"; remix_userkey="7920…";$Path="/";$Domain=".z-lib.by"; …`
 * ——值带引号、混入伪属性；JDK 实现还可能拆成多个 header 值（本类两种都兼容）。
 * 原实现只取 `firstOrNull()` → Cookie 头只剩 `$Version="1"`（或带引号 + 伪属性），
 * 服务端识别不到 remix_userkey → 按匿名处理 → /dl 返回限额页（DAILY_LIMIT）。
 *
 * 这里统一解析成 RFC 6265（version-0）格式 `name=value; name2=value2`：
 * - 跳过 `$Version/$Path/$Domain` 等伪属性；
 * - 去掉值两侧引号；
 * - 合并手动挑战 cookie（c_token/c_time），同名以手动为准。
 */
internal object ZlibCookieHeader {

    /** 解析任意 Cookie 头片段（可含 $ 伪属性、引号、多条拼接）为有序 name→value。 */
    fun parse(raw: String?): LinkedHashMap<String, String> {
        val result = LinkedHashMap<String, String>()
        if (raw.isNullOrBlank()) return result
        splitPairs(raw).forEach { part ->
            val index = part.indexOf('=')
            if (index <= 0) return@forEach
            val name = part.substring(0, index).trim()
            if (name.isEmpty() || name.startsWith("$")) return@forEach
            if (name.equals("Version", ignoreCase = true)) return@forEach
            var value = part.substring(index + 1).trim()
            if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
                value = value.substring(1, value.length - 1)
            }
            result[name] = value
        }
        return result
    }

    /**
     * 按 `;` 或 `,` 切分（引号内不切）。
     *
     * CookieManager.get() 对 Version=1 cookie 返回的是**多个 header 值**，
     * 上层用 `; ` 拼接是一等公民；但 RFC 2965 的原样输出里也用 `, ` 分隔，
     * 这里两种都吃，避免 `$Version="1", remix_userkey=...` 被当成一个属性整条丢掉。
     */
    private fun splitPairs(raw: String): List<String> {
        val parts = ArrayList<String>()
        val current = StringBuilder()
        var inQuotes = false
        for (ch in raw) {
            when {
                ch == '"' -> {
                    inQuotes = !inQuotes
                    current.append(ch)
                }
                (ch == ';' || ch == ',') && !inQuotes -> {
                    parts.add(current.toString())
                    current.setLength(0)
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) parts.add(current.toString())
        return parts
    }

    /** 规范化后的发送用 Cookie 头；为空返回 null。 */
    fun merge(raw: String?, extra: Map<String, String>?): String? {
        val merged = parse(raw)
        extra?.forEach { (name, value) ->
            if (name.isNotBlank() && !name.startsWith("$")) merged[name] = value
        }
        return merged.entries.joinToString("; ") { it.key + "=" + it.value }.ifEmpty { null }
    }

    /** 诊断输出：cookie 值截断到 [valueChars] 位（避免把登录 token 打进日志）。 */
    fun redact(header: String?, valueChars: Int = 6): String {
        if (header.isNullOrBlank()) return "none"
        return parse(header).entries.joinToString("; ") { (name, value) ->
            if (value.length <= valueChars) name + "=" + value
            else name + "=" + value.take(valueChars) + "…"
        }.ifEmpty { "none" }
    }
}
