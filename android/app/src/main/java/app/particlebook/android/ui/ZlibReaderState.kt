package app.particlebook.android.ui

/**
 * A13：详情页「在线阅读」按钮的链接解析状态（详情页 → reader 链接 → 外部浏览器）。
 *
 * realnet task-33：reader.z-lib.by 的内容接口对登录/匿名、移动/桌面 UA 都不可读
 * （fetch-config 401/422/204、fetch-sections 403），外部浏览器同样受限。
 * 所以不在 App 内嵌 WebView，也不向用户承诺可读；只把带票据的 reader URL 交给系统浏览器，
 * 并在按钮下方常驻「当前受限、建议下载」提示。
 */
internal sealed interface ZlibReaderLaunchState {
    /** 尚未请求（按钮初始态，不显示加载）。 */
    data object Idle : ZlibReaderLaunchState
    data object Loading : ZlibReaderLaunchState
    data class Ready(val readerUrl: String) : ZlibReaderLaunchState
    data class Error(val message: String) : ZlibReaderLaunchState
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

/** 未登录时按钮禁用，并在按钮下方说明原因（不承诺登录后可读）。 */
internal const val ZLIB_READER_LOGIN_REQUIRED_MESSAGE =
    "未登录：可先登录后重试，但在线阅读当前受限，建议下载到书架。"

/** 已登录时的按钮说明：站点阅读器受限，App 不承诺一定能读。 */
internal const val ZLIB_READER_BROWSER_HINT =
    "在线阅读当前受限，建议下载到书架。若浏览器提示无权限，请下载后本地阅读。"

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

/** 按钮可用性：未登录或正在解析时禁用（避免重复请求）。 */
internal fun zlibReaderActionEnabled(
    loggedIn: Boolean,
    state: ZlibReaderLaunchState,
): Boolean = loggedIn && state !is ZlibReaderLaunchState.Loading

/**
 * 按钮下方提示：优先级 打开失败（无浏览器）> 已打开 > 解析错误 > 未登录说明 > 外部浏览器说明。
 * 统一使用「在线阅读当前受限，建议下载到书架」口径，不承诺可读；
 * 打开成功后（opened=true）改「已在浏览器打开」，不再让用户误以为失败。
 */
internal fun zlibReaderActionHint(
    loggedIn: Boolean,
    state: ZlibReaderLaunchState,
    launchError: String? = null,
    opened: Boolean = false,
): String = when {
    launchError != null -> launchError
    opened -> ZLIB_READER_OPENED_MESSAGE
    state is ZlibReaderLaunchState.Error -> state.message
    !loggedIn -> ZLIB_READER_LOGIN_REQUIRED_MESSAGE
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

/** 按钮文案：加载中/失败重试/普通入口。 */
internal fun zlibReaderActionLabel(state: ZlibReaderLaunchState): String = when (state) {
    is ZlibReaderLaunchState.Loading -> "正在获取链接…"
    is ZlibReaderLaunchState.Error -> "重新尝试在线阅读"
    else -> "在线阅读"
}
