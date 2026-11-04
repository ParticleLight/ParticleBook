package app.particlebook.android.ui

import java.util.Base64

/**
 * A13：详情页「在线阅读」按钮的链接解析状态（详情页 → reader 链接 → 外部浏览器）。
 *
 * task-77（p15 task-69/76 真机实测）：匿名详情页 HTML 本身就带 reader-link（access_level=limited），
 * 点击后在系统浏览器/LITERA Reader 能读到正文 —— 在线阅读不需要登录，因此不再按登录态禁用按钮。
 * 仍不在 App 内嵌 WebView，只把带票据的 reader URL 交给系统浏览器；按钮下方说明打开方式与
 * 无权限时的下载兜底。
 */
internal sealed interface ZlibReaderLaunchState {
    /** 尚未请求（按钮初始态，不显示加载）。 */
    data object Idle : ZlibReaderLaunchState
    data object Loading : ZlibReaderLaunchState
    data class Ready(val readerUrl: String) : ZlibReaderLaunchState
    data class Error(
        val message: String,
        /** task-89：可判定的失败类别，只用于结构化埋点；null 表示旧构造/未分类。 */
        val failure: ZlibReaderFailure? = null,
    ) : ZlibReaderLaunchState
}

/** 站点阅读器对所有账号都受限（realnet task-33）时的统一主提示，不承诺可读。 */
internal const val ZLIB_READER_LIMITED_MESSAGE =
    "在线阅读当前受限，建议下载到书架。"

/**
 * 没有在线阅读入口时的独立文案（书未上架 / 已下架 / 站点改版）。
 * task-43 P3：不再复用中性提示 —— 用户要知道是「这本书没有入口」，而不是泛泛的「受限」。
 */
internal const val ZLIB_READER_MISSING_LINK_MESSAGE =
    "该书未提供在线阅读入口。$ZLIB_READER_LIMITED_MESSAGE"

/**
 * task-58：抓详情 HTML 时 cookie 已失效，站点 200 返回登录页（页面里没有 reader-link）。
 * 不能落「未提供在线阅读入口」——要明确告诉用户会话可能过期、可重新登录。
 */
internal const val ZLIB_READER_SESSION_EXPIRED_MESSAGE =
    "登录可能已失效，可重新登录后重试。$ZLIB_READER_LIMITED_MESSAGE"

/**
 * task-58：抓详情 HTML 时落到站点人机验证页（挑战未通过/被网关拦截）。
 * 与登录失效区分：先建议稍后重试，仍失败再重新登录。
 */
internal const val ZLIB_READER_CHALLENGE_PAGE_MESSAGE =
    "站点要求人机验证，请稍后重试；仍失败可重新登录。$ZLIB_READER_LIMITED_MESSAGE"

/**
 * task-77：匿名详情页没有 reader-link 时的提示 —— 门控从「全局未登录禁用」改成「按书判定」，
 * 不能断言登录后一定可读；给登录重试与下载两条路。
 */
internal const val ZLIB_READER_LOGIN_REQUIRED_MESSAGE =
    "此书可能需要登录后阅读：可登录后重试，也可下载到书架阅读。"

/** task-77：点击前的按钮说明 —— 匿名也可用；浏览器打开 + 按书判定的登录/无权限兜底。 */
internal const val ZLIB_READER_BROWSER_HINT =
    "将在浏览器打开；公共书籍无需登录，部分书籍可能需要登录后阅读，若提示无权限请下载到书架。"

/** 有阅读链接但系统没有可用浏览器。 */
internal const val ZLIB_READER_NO_BROWSER_MESSAGE =
    "未找到可用的浏览器；在线阅读当前受限，建议下载到书架。"

/** 已把 reader URL 交给系统浏览器（成功路径，不再显示「受限」式提示）。 */
internal const val ZLIB_READER_OPENED_MESSAGE =
    "已在浏览器打开；若提示无权限，请下载到书架阅读。"

/** 链接校验失败原因 → 用户文案；所有失败都带上「当前受限」主提示，不承诺可读。 */
internal fun zlibReaderUrlFailureMessage(reason: ZlibReaderUrl.Failure): String = when (reason) {
    ZlibReaderUrl.Failure.NOT_FOUND -> ZLIB_READER_MISSING_LINK_MESSAGE
    ZlibReaderUrl.Failure.MALFORMED ->
        "在线阅读链接格式异常，请稍后重试。$ZLIB_READER_LIMITED_MESSAGE"
    ZlibReaderUrl.Failure.INSECURE_SCHEME ->
        "在线阅读链接不是 HTTPS，为安全起见已阻止打开。$ZLIB_READER_LIMITED_MESSAGE"
    ZlibReaderUrl.Failure.UNTRUSTED_HOST ->
        "在线阅读链接的域名不受信任，已阻止打开。$ZLIB_READER_LIMITED_MESSAGE"
    ZlibReaderUrl.Failure.NOT_READ_PATH ->
        "在线阅读链接无效（不是阅读页面），已阻止打开。$ZLIB_READER_LIMITED_MESSAGE"
    ZlibReaderUrl.Failure.MISSING_TICKET ->
        "在线阅读链接缺少访问票据，请稍后重试。$ZLIB_READER_LIMITED_MESSAGE"
}

/** task-77：按钮可用性只取决于是否正在解析（避免重复请求），不再要求登录。 */
internal fun zlibReaderActionEnabled(
    state: ZlibReaderLaunchState,
): Boolean = state !is ZlibReaderLaunchState.Loading

/**
 * 按钮下方提示：优先级 打开失败（无浏览器）> 已打开 > 解析错误 > 外部浏览器说明。
 * task-77：去掉未登录说明 —— 匿名同样显示入口；页面本身没有 reader-link 时，
 * 解析错误文案里会带出「需要登录/会话失效」的下一步。
 * 打开成功后（opened=true）改「已在浏览器打开」，不再让用户误以为失败。
 */
internal fun zlibReaderActionHint(
    state: ZlibReaderLaunchState,
    launchError: String? = null,
    opened: Boolean = false,
): String = when {
    launchError != null -> launchError
    opened -> ZLIB_READER_OPENED_MESSAGE
    state is ZlibReaderLaunchState.Error -> state.message
    else -> ZLIB_READER_BROWSER_HINT
}

/**
 * task-43 P2-1：解析完成时是否拉起浏览器 —— 只有本层仍可见且确实拿到 Ready 才打开。
 * 抽成纯函数便于 JVM 单测（Compose 可见性本身无法离线单测）。
 */
internal fun zlibReaderShouldOpenBrowser(
    layerVisible: Boolean,
    state: ZlibReaderLaunchState,
): Boolean = layerVisible && state is ZlibReaderLaunchState.Ready

/**
 * task-58 P2-1：一次「交给系统浏览器」尝试后的归并结果。
 *
 * 成功与失败都必须消费 Ready：失败（无浏览器/系统拒绝）时旧 URL 不会变好，
 * 且它可能属于已过期的登录会话；下次点击应重新解析，不能复用。
 */
internal data class ZlibReaderOpenOutcome(
    val opened: Boolean,
    val launchError: String?,
    val consumeReady: Boolean,
)

/** 打开尝试无论成功失败都消费 Ready，只有副文案不同。 */
internal fun zlibReaderOpenOutcome(browserLaunched: Boolean): ZlibReaderOpenOutcome =
    if (browserLaunched) {
        ZlibReaderOpenOutcome(
            opened = true,
            launchError = null,
            consumeReady = true,
        )
    } else {
        ZlibReaderOpenOutcome(
            opened = false,
            launchError = ZLIB_READER_NO_BROWSER_MESSAGE,
            consumeReady = true,
        )
    }

/** 按钮文案：加载中/失败重试/普通入口。 */
internal fun zlibReaderActionLabel(state: ZlibReaderLaunchState): String = when (state) {
    is ZlibReaderLaunchState.Loading -> "正在获取链接…"
    is ZlibReaderLaunchState.Error -> "重新尝试在线阅读"
    else -> "在线阅读"
}

/**
 * task-89 A13：一次「在线阅读」尝试的最终结果。wire 值固定，供 p15 按行解析
 * zlib_reader_open ... outcome=<wire>；新增值只能追加，不能改旧值。
 */
internal enum class ZlibReaderOpenResult(val wire: String) {
    READY("ready"),
    OPEN_FAILED("open_failed"),
    NO_BROWSER("no_browser"),
    LOGIN_PAGE("login_page"),
    CHALLENGE("challenge"),
    NOT_FOUND("not_found"),
}

/**
 * task-89：解析阶段的失败归因（不参与用户文案）。
 * LOGIN_PAGE 同时覆盖「已登录但会话失效」与「匿名需要登录」两种情况 —— 站点给的都是登录页。
 */
internal enum class ZlibReaderFailure {
    LOGIN_PAGE,
    CHALLENGE,
    NOT_FOUND,
    OTHER,
}

/** task-89：JWT 里可读出的权限标记；字段缺失/无法解析时记 unknown，不猜测。 */
internal enum class ZlibReaderAccess(val wire: String) {
    TRUE("true"),
    FALSE("false"),
    UNKNOWN("unknown"),
}

/** task-89：reader URL 的 JWT 权限信息；读不出时两项都是 unknown。 */
internal data class ZlibReaderUrlAccess(
    val isPublic: ZlibReaderAccess = ZlibReaderAccess.UNKNOWN,
    val limited: ZlibReaderAccess = ZlibReaderAccess.UNKNOWN,
)

/** task-89：一次打开尝试的埋点事件（已脱敏，只含 book_hash、固定枚举与时长）。 */
internal data class ZlibReaderOpenEvent(
    val bookHash: String,
    val outcome: ZlibReaderOpenResult,
    val isPublic: ZlibReaderAccess,
    val limited: ZlibReaderAccess,
    val elapsedMs: Long,
)

private const val ZLIB_READER_LOG_BOOK_HASH_MAX = 64

/** 日志字段清洗：只留字母/数字/下划线/连字符，防止换行/空格注入与超长字段；空值记 unknown。 */
internal fun zlibReaderLogBookHash(bookHash: String): String {
    val sanitized = bookHash.trim()
        .filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        .take(ZLIB_READER_LOG_BOOK_HASH_MAX)
    return sanitized.ifEmpty { "unknown" }
}

/**
 * task-89：纯函数格式化 zlib_reader_open 埋点行。
 *
 * 只输出 book_hash/outcome/is_public/limited/elapsed_ms —— 不包含 reader URL、query、票据或
 * JWT 原文，便于日志脱敏审计与 JVM 单测；elapsed 负数归零。
 */
internal fun zlibReaderOpenLogLine(event: ZlibReaderOpenEvent): String =
    "zlib_reader_open" +
        " book_hash=" + zlibReaderLogBookHash(event.bookHash) +
        " outcome=" + event.outcome.wire +
        " is_public=" + event.isPublic.wire +
        " limited=" + event.limited.wire +
        " elapsed_ms=" + event.elapsedMs.coerceAtLeast(0L)

/** 解析失败 → 埋点 outcome；未知/不可归类失败统一落 open_failed。 */
internal fun zlibReaderFailureOutcome(failure: ZlibReaderFailure?): ZlibReaderOpenResult = when (failure) {
    ZlibReaderFailure.LOGIN_PAGE -> ZlibReaderOpenResult.LOGIN_PAGE
    ZlibReaderFailure.CHALLENGE -> ZlibReaderOpenResult.CHALLENGE
    ZlibReaderFailure.NOT_FOUND -> ZlibReaderOpenResult.NOT_FOUND
    ZlibReaderFailure.OTHER, null -> ZlibReaderOpenResult.OPEN_FAILED
    else -> ZlibReaderOpenResult.OPEN_FAILED
}

/** task-89：系统浏览器拉起结果（供埋点映射；UI 文案仍走 zlibReaderOpenOutcome）。 */
internal enum class ZlibReaderBrowserLaunch {
    OPENED,
    NO_BROWSER,
    FAILED,
}

/** 浏览器拉起结果 → 埋点 outcome。 */
internal fun zlibReaderLaunchOutcome(launch: ZlibReaderBrowserLaunch): ZlibReaderOpenResult = when (launch) {
    ZlibReaderBrowserLaunch.OPENED -> ZlibReaderOpenResult.READY
    ZlibReaderBrowserLaunch.NO_BROWSER -> ZlibReaderOpenResult.NO_BROWSER
    ZlibReaderBrowserLaunch.FAILED -> ZlibReaderOpenResult.OPEN_FAILED
}

/**
 * task-89：从 reader URL 的 JWT 读 is_public / limited（access_level）。
 *
 * 只解析查询串里的 file_access_token/access_token，手工切 query 而不用 android.net.Uri，
 * 保证纯 JVM 单测可跑；base64url 用 java.util.Base64（minSdk 26 起可用）。
 * 任一步失败都返回 unknown/unknown，不影响打开流程。
 */
internal fun zlibReaderUrlAccess(readerUrl: String?): ZlibReaderUrlAccess {
    val token = zlibReaderAccessToken(readerUrl) ?: return ZlibReaderUrlAccess()
    val payload = zlibReaderJwtPayload(token) ?: return ZlibReaderUrlAccess()
    val isPublic = ZLIB_READER_JWT_IS_PUBLIC.find(payload)?.groupValues?.getOrNull(1)
        ?.let { if (it == "true") ZlibReaderAccess.TRUE else ZlibReaderAccess.FALSE }
        ?: ZlibReaderAccess.UNKNOWN
    val limited = ZLIB_READER_JWT_ACCESS_LEVEL.find(payload)?.groupValues?.getOrNull(1)
        ?.let { if (it == "limited") ZlibReaderAccess.TRUE else ZlibReaderAccess.FALSE }
        ?: ZlibReaderAccess.UNKNOWN
    return ZlibReaderUrlAccess(isPublic = isPublic, limited = limited)
}

private val ZLIB_READER_JWT_IS_PUBLIC = Regex(""""is_public"\s*:\s*"?(true|false)"?""")
private val ZLIB_READER_JWT_ACCESS_LEVEL = Regex(""""access_level"\s*:\s*"?(full|limited)"?""")

private fun zlibReaderAccessToken(readerUrl: String?): String? {
    val raw = readerUrl?.trim().orEmpty()
    val queryStart = raw.indexOf('?')
    if (queryStart < 0 || queryStart == raw.lastIndex) return null
    for (part in raw.substring(queryStart + 1).split('&')) {
        val eq = part.indexOf('=')
        if (eq <= 0) continue
        val name = part.substring(0, eq)
        if (name != "file_access_token" && name != "access_token") continue
        val value = part.substring(eq + 1)
        if (value.isNotBlank()) return value
    }
    return null
}

private fun zlibReaderJwtPayload(token: String): String? {
    val segments = token.split('.')
    if (segments.size < 2) return null
    return try {
        val segment = segments[1]
        val padded = segment + "=".repeat((4 - segment.length % 4) % 4)
        String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
    }
}
