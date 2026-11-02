package app.particlebook.android.zlib

/**
 * P1b 数据模型（docs/android-port/zlibrary-p1b-spec.md §2，签名冻结）。
 */

/** 书籍详情页解析结果；[downloadPath] 为站内相对路径（如 `/dl/AX1jDr5bOm`）。 */
data class ZBookDetail(
    val bookId: String,
    val hash: String,
    val title: String,
    val authors: List<String>,
    val language: String?,
    val year: String?,
    val format: String?,
    val fileSize: String?,
    val isbn: String?,
    val coverUrl: String?,
    val detailPath: String,
    val downloadPath: String,
)

/** 已登录账号（来自页面 `const CurrentUser = new User({...})` 或 prefs 缓存）。 */
data class ZAccount(val email: String?, val name: String?)

/** 登录结果（§2 冻结）。失败时 [Failed.message] 为页面原文或诊断文本，UI 直接展示。 */
sealed class ZLoginResult {
    data class Ok(val account: ZAccount) : ZLoginResult()

    data class Failed(val kind: Kind, val message: String?) : ZLoginResult() {
        enum class Kind { BAD_CREDENTIALS, CHALLENGE_FAILED, HTTP, NETWORK, PARSE }
    }
}

/** /dl 页面解析出的可下载直链。 */
data class ZDownloadHandle(val directUrl: String, val contentType: String?)

/** 线路信息（UI 线路弹层用）。[index] 从 1 开始；无可用线路时为 0。 */
data class ZMirrorInfo(val index: Int, val total: Int, val mirrors: List<String>)
