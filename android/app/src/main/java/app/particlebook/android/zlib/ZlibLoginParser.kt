package app.particlebook.android.zlib

/**
 * 登录相关 HTML 解析（P1b 3.2 / 3.6，纯函数可单测）。
 *
 * 实测：错误密码 POST /login → 200、21,020B，页面含
 * 「div class="form-error validation-error">Incorrect email or password」；
 * 登录态判据：任意页面脚本里的 const CurrentUser = new User(... | null)。
 */
object ZlibLoginParser {

    sealed class AccountState {
        data class LoggedIn(val account: ZAccount) : AccountState()

        /** 明确未登录：CurrentUser = new User(null)。 */
        object Anonymous : AccountState()

        /** 页面里没有 CurrentUser 片段，判不出。 */
        object Unknown : AccountState()
    }

    private val FORM_ERROR = Regex(
        """<div[^>]*class\s*=\s*["'][^"']*form-error[^"']*["'][^>]*>([\s\S]*?)</div>""",
        RegexOption.IGNORE_CASE,
    )
    private val CURRENT_USER = Regex("""CurrentUser\s*=\s*new\s+User\s*\(\s*""", RegexOption.IGNORE_CASE)
    private val EMAIL = Regex(""""email"\s*:\s*"([^"]*)"""")
    private val NAME = Regex(""""name"\s*:\s*"([^"]*)"""")

    /** 错误文案（页面原文，如 Incorrect email or password）；无错误返回 null。 */
    fun formError(html: String): String? =
        FORM_ERROR.find(html)?.groupValues?.get(1)
            ?.let(ZlibHtml::clean)
            ?.takeIf { it.isNotEmpty() }
            ?.take(300)

    /** 从页面脚本解析登录态；只认「CurrentUser = new User(」后的第一个 token。 */
    fun accountState(html: String): AccountState {
        val match = CURRENT_USER.find(html) ?: return AccountState.Unknown
        val start = match.range.last + 1
        if (start >= html.length) return AccountState.Unknown
        val rest = html.substring(start)
        if (rest.startsWith("null")) return AccountState.Anonymous
        if (!rest.startsWith("{")) return AccountState.Unknown
        val json = readBalancedObject(rest)
        if (json.isEmpty()) return AccountState.Unknown
        val email = EMAIL.find(json)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        val name = NAME.find(json)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        // new User({...}) 形态即已登录；email 可能为空（某些页面脚本裁剪）。
        return AccountState.LoggedIn(ZAccount(email, name))
    }

    /**
     * 站点 JSON 接口 /papi/user/get-downloads 的登录判据：
     * 已登录返回 {"downloads":[...]}，未登录返回 {"success":0,...}。
     */
    fun looksLoggedInByDownloads(body: String): Boolean = Regex(""""downloads"\s*:""").containsMatchIn(body)

    /** 从第一个 { 开始取配平的 JSON 对象（跳过字符串内的括号）。 */
    private fun readBalancedObject(text: String): String {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in text.indices) {
            val ch = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(0, index + 1)
                }
            }
        }
        return ""
    }
}
