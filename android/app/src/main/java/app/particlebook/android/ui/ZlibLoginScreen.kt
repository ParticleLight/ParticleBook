package app.particlebook.android.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.particlebook.android.zlib.ZMirrorItem
import app.particlebook.android.zlib.ZlibClient
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Z-Library 登录页（P1b §4.2，全屏 route zlib/login）。
 *
 * 密码不做持久化、不打日志；错误显示在按钮上方且失败不关闭页面。
 */
@Composable
fun ZlibLoginScreen(
    viewModel: ZlibAccountViewModel,
    onBack: () -> Unit,
    onLoginSuccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    val passwordFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val busy = viewModel.busy
    val error = viewModel.error
    val canSubmit = email.isNotBlank() && password.isNotEmpty() && !busy
    // P2-4：openInBrowser 失败（无浏览器 / 系统拒绝）时在入口下方给出可操作提示，不再静默。
    var browserOpenFailed by remember { mutableStateOf(false) }
    // B12 修正（realnet T26，2026-11-04）：站点已无找回密码页（全部线路软 404），
    // 因此不再生成「忘记密码」链接（不做死链），只保留实测 200 的注册外链。
    // B12/T52/T57 修正（realnet，2026-11-04）：注册外链不能再用「当前线路」直接拼。网关线路
    // zlib.ch / zlib.re 会把 /registration 301 到 z-lib.by 首页（实测），用户点「注册」会落到
    // 首页而不是注册页；改为从镜像注册表取官方线路（official，registry 排序 APP/未降权优先），
    // 跳过已知网关。P2-2：没有任何可用官方线路时返回 null，注册入口隐藏（恢复 B12 原语义）。
    // P2-1：mirrorRows() 首次加载会读注册表文件，不能在 Compose 组合（主线程）里同步调用，
    // 与 ZlibMirrorSheet 一致放到 IO 线程，加载完成前不渲染入口。
    val context = LocalContext.current
    val registrationState = produceState<String?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) {
            val officialMirrors = zlibRegistrationCandidates(
                ZlibClient.get(context).mirrorRows(),
            )
            zlibRegistrationLink(officialMirrors)
        }
    }
    val registrationUrl = registrationState.value

    fun submit() {
        if (!canSubmit) return
        keyboard?.hide()
        focusManager.clearFocus()
        viewModel.login(email.trim(), password) {
            keyboard?.hide()
            onLoginSuccess()
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .bottomBarPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screen),
        ) {
            Spacer(Modifier.height(Spacing.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Spacer(Modifier.height(Spacing.lg))
            Text(
                text = "登录 Z-Library",
                style = AppType.pageTitle,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "使用你的 Z-Library 账号，可下载更多书籍",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.xl))
            OutlinedTextField(
                value = email,
                onValueChange = {
                    email = it
                    if (error != null) viewModel.clearError()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentType = ContentType.EmailAddress },
                label = { Text("邮箱") },
                placeholder = { Text("you@example.com") },
                singleLine = true,
                enabled = !busy,
                isError = error != null,
                shape = RoundedCornerShape(Radius.cover),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(
                    onNext = { passwordFocus.requestFocus() },
                ),
            )
            Spacer(Modifier.height(Spacing.md))
            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    if (error != null) viewModel.clearError()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(passwordFocus)
                    .semantics { contentType = ContentType.Password },
                label = { Text("密码") },
                singleLine = true,
                enabled = !busy,
                isError = error != null,
                // P1-4：错误挂在字段上（M3 supportingText 位置），TalkBack 能与输入框关联。
                supportingText = error?.let { message ->
                    {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                },
                shape = RoundedCornerShape(Radius.cover),
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(
                        onClick = { passwordVisible = !passwordVisible },
                        enabled = !busy,
                    ) {
                        Icon(
                            imageVector = if (passwordVisible) {
                                Icons.Outlined.VisibilityOff
                            } else {
                                Icons.Outlined.Visibility
                            },
                            contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            Spacer(Modifier.height(Spacing.lg))
            Button(
                onClick = { submit() },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                enabled = canSubmit,
                shape = RoundedCornerShape(Radius.cover),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        // 登录中按钮是禁用态（灰底），用 primary 才看得见。
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text("登录中…", fontWeight = FontWeight.Medium)
                } else {
                    Text("登录", fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = "登录凭据仅保存在本机，用于访问你账号可下载的内容。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // P2-1：与左对齐的字段/错误同一条左缘，不再居中。
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.xs))
            // 说明行始终显示：用户找不到「忘记密码」时知道原因，而不是以为页面漏了入口。
            Text(
                text = "忘记密码？站点暂未提供找回密码页。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            if (registrationUrl != null) {
                Spacer(Modifier.height(Spacing.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    TextButton(
                        onClick = {
                            browserOpenFailed = !openInBrowser(context, registrationUrl)
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("注册")
                    }
                }
                if (browserOpenFailed) {
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = zlibBrowserErrorMessage(registrationUrl),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            Spacer(Modifier.height(Spacing.xxl))
        }
    }
}

/**
 * B12/T52：已知网关线路（2026-11-04 实测：任意路径都 301 → https://z-lib.by/ 首页）。
 * 这些线路能返回 200，但页面不是站点本体，不能用来拼注册页等深链。
 */
private val ZLIB_GATEWAY_HOSTS: Set<String> = setOf("zlib.ch", "zlib.re")

/**
 * B12/T52/T57：从镜像注册表的官方线路里挑注册外链 base。
 *
 * 官方线路按 registry 顺序传入（APP/未降权/低延迟优先，见 ZlibMirrorRegistry），这里只做两件事：
 * 1. 归一成 scheme://host[:port]（注册页在站点根路径，丢掉线路自带的 path/query）；
 * 2. 跳过 [ZLIB_GATEWAY_HOSTS]。
 *
 * P2-2：没有任何可用官方非网关线路时返回 null；登录页据此隐藏注册入口，恢复 B12
 * 「无线路时不渲染」的语义，避免离线/只有网关时给用户一个点开就失败的假入口。
 * z-lib.by 本身就是 OFFICIAL_SEEDS 第一项，正常 registry 下仍会选中它。
 *
 * 不在 UI 里联网做 HEAD 校验：注册页 URL 固定为 /registration，z-lib.by 已实测 200 且带真表单；
 * 网关线路靠静态 host 名单排除，无需额外网络等待。
 */
internal fun zlibRegistrationBase(officialMirrors: List<String>): String? =
    officialMirrors.asSequence()
        .mapNotNull(::zlibMirrorOrigin)
        .distinct()
        .filterNot { zlibIsGatewayHost(zlibOriginHost(it)) }
        .firstOrNull()

/** B12/T57：注册入口的最终 URL；null 表示 registry 无可用官方线路，入口应隐藏。 */
internal fun zlibRegistrationLink(officialMirrors: List<String>): String? =
    zlibAccountHelpUrl(zlibRegistrationBase(officialMirrors), "registration")

// ZMirrorItem.rank 取值（对齐 ZlibMirrorRegistry.Rank）：0 APP / 1 UNKNOWN / 2 PAGE / 3 OTHER / 4 FAIL。
private const val ZLIB_MIRROR_RANK_PAGE = 2
private const val ZLIB_MIRROR_RANK_OTHER = 3
private const val ZLIB_MIRROR_RANK_FAIL = 4

/**
 * B12/T57（P2-3）：从 registry 单快照挑注册页候选 URL。
 *
 * 只保留 official、未降权、且探测 rank 不是「推广页 / 其它 / 不通」的线路；当前线路排最前，
 * 其余保持 registry 顺序。这样不会把 nav 页/推广页当注册页，也不会优先选一条正在降权的线路。
 * rank=UNKNOWN（未探测）仍保留：新装/离线时还没探测，不能因此把所有官方 seed 都排除。
 */
internal fun zlibRegistrationCandidates(rows: List<ZMirrorItem>): List<String> =
    rows.asSequence()
        .filter { it.tier == "official" }
        .filterNot {
            it.demoted ||
                it.rank == ZLIB_MIRROR_RANK_PAGE ||
                it.rank == ZLIB_MIRROR_RANK_OTHER ||
                it.rank == ZLIB_MIRROR_RANK_FAIL
        }
        .sortedByDescending { it.current }
        .map { it.url }
        .toList()

/** P2-4：外链打开失败时的内联提示（无浏览器/Intent 被系统拒绝）。 */
internal fun zlibBrowserErrorMessage(url: String): String =
    "无法打开浏览器，请手动在浏览器访问：" + url

/** 线路 URL 归一成 origin（小写 scheme/host、保留端口、去掉 path/query/fragment）；无效返回 null。 */
internal fun zlibMirrorOrigin(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val uri = runCatching {
        URI(if (trimmed.contains("://")) trimmed else "https://$trimmed")
    }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    val host = uri.host?.lowercase()?.trim().orEmpty()
    if (host.isEmpty() || host.contains(' ')) return null
    val port = if (uri.port > 0) ":" + uri.port else ""
    return scheme + "://" + host + port
}

private fun zlibOriginHost(origin: String): String =
    runCatching { URI(origin).host.orEmpty().lowercase() }.getOrDefault("")

private fun zlibIsGatewayHost(host: String): Boolean =
    ZLIB_GATEWAY_HOSTS.any { host == it || host.endsWith("." + it) }

/**
 * B12 修正：注册页 URL。找回密码页已从产品中移除（realnet T26：站点全部线路软 404），
 * 本函数不要再拼该路径。用字符串拼接而非 Uri 解析，避免线路 URL 自带的路径/查询参数被丢掉。
 */
internal fun zlibAccountHelpUrl(mirrorBase: String?, path: String): String? {
    val rawBase = mirrorBase?.trim() ?: return null
    // 线路 URL 可能带查询串/片段（例如镜像列表里出现 "https://host/?..."）。直接拼接会得到
    // "https://host/?x=1/registration" 这种坏链接，所以先截掉 ?/# 再补路径。
    val base = rawBase.substringBefore('?').substringBefore('#').trimEnd('/')
    val suffix = path.trim().trim('/')
    if (base.isEmpty() || suffix.isEmpty()) return null
    return base + "/" + suffix
}

/** B12/T57：用系统浏览器打开注册页；返回 false 表示设备无浏览器或 Intent 被系统拒绝（调用方提示用户）。 */
private fun openInBrowser(context: Context, url: String): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(intent) }
        .onFailure { e -> Log.w("ZlibLogin", "open browser failed: " + url, e) }
        .isSuccess
}
