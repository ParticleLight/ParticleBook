package app.particlebook.android.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * A13：在线阅读页（reader.z-lib.by，第三方 LITERA SPA）。
 *
 * 为什么用 WebView 直开而不是原生复刻（P1d §1）：LITERA 是 Vite SPA，服务端逐页转码 PDF/EPUB，
 * 没有稳定 API；站点 README/审计建议 1–2 人日用 WebView 容器，与桌面版「站点内阅读」一致。
 *
 * 票据在详情页 reader-link 的 URL 里（file_access_token，约 7 天有效），reader 域和站点域不同，
 * **不共享站点 Cookie**；这里先走 ZlibClient 详情页（复用进程内挑战/会话）解析出 reader URL，
 * WebView 用自己的 cookie jar 直接 load，不需要先登录站点。
 *
 * 容器能力：加载进度、主帧失败重试、顶部返回 / 系统返回（优先回退 WebView 历史）、
 * 拦截非 http(s) 导航；不覆盖 SSL 错误（默认取消，安全优先）。
 */
@Composable
fun ZlibReaderScreen(
    bookHash: String,
    bookSlug: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel = remember(bookHash, bookSlug) {
        ZlibReaderViewModel(scope = scope, context = context)
    }
    ZlibReaderScreen(
        viewModel = viewModel,
        bookHash = bookHash,
        bookSlug = bookSlug,
        onBack = onBack,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ZlibReaderScreen(
    viewModel: ZlibReaderViewModel,
    bookHash: String,
    bookSlug: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val launchState = viewModel.state
    LaunchedEffect(bookHash, bookSlug) { viewModel.load(bookHash, bookSlug) }

    // 顶部返回与系统返回都优先回退 WebView 历史，没有历史才退出阅读页。
    var readerWebView by remember { mutableStateOf<WebView?>(null) }
    var readerCanGoBack by remember { mutableStateOf(false) }
    LaunchedEffect(launchState) {
        if (launchState !is ZlibReaderLaunchState.Ready) {
            readerWebView = null
            readerCanGoBack = false
        }
    }
    val handleBack: () -> Unit = {
        val webView = readerWebView
        if (webView != null && readerCanGoBack) webView.goBack() else onBack()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "在线阅读",
                        style = AppType.topBarTitle,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = handleBack, modifier = Modifier.size(48.dp)) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                ),
            )
        },
    ) { innerPadding ->
        when (val current = launchState) {
            is ZlibReaderLaunchState.Loading -> ReaderBoxLoading(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            is ZlibReaderLaunchState.Error -> ReaderBoxError(
                message = current.message,
                onRetry = { viewModel.retry(bookHash, bookSlug) },
                onBack = onBack,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            is ZlibReaderLaunchState.Ready -> ReaderWebContent(
                readerUrl = current.readerUrl,
                onBack = onBack,
                onRefreshReaderLink = { viewModel.retry(bookHash, bookSlug) },
                onWebViewReady = { readerWebView = it },
                onCanGoBackChanged = { readerCanGoBack = it },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .bottomBarPadding(),
            )
        }
    }
}

@Composable
private fun ReaderBoxLoading(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = "正在准备在线阅读…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReaderBoxError(
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = Spacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.BrokenImage,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = "无法打开在线阅读",
            style = AppType.sectionTitle,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.lg))
        Button(
            onClick = onRetry,
            shape = RoundedCornerShape(Radius.cover),
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text("重试")
        }
        TextButton(onClick = onBack) {
            Text("返回详情")
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ReaderWebContent(
    readerUrl: String,
    onBack: () -> Unit,
    onRefreshReaderLink: () -> Unit,
    onWebViewReady: (WebView) -> Unit,
    onCanGoBackChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var webState by remember(readerUrl) { mutableStateOf(ZlibReaderWebState()) }
    val webView = remember(readerUrl) {
        WebView(context).apply {
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            configureZlibReaderWebView(this)
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    webState = zlibReaderOnPageStarted(webState)
                    onCanGoBackChanged(view?.canGoBack() == true)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    val canGoBack = view?.canGoBack() == true
                    webState = zlibReaderOnPageFinished(webState, canGoBack)
                    onCanGoBackChanged(canGoBack)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    if (request == null || error == null || !request.isForMainFrame) return
                    webState = zlibReaderOnMainFrameError(webState, error.errorCode)
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    if (request == null || errorResponse == null || !request.isForMainFrame) return
                    webState = zlibReaderOnHttpError(webState, errorResponse.statusCode)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean = request != null && zlibReaderShouldBlockUrl(request.url.toString())
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    webState = zlibReaderOnProgress(webState, newProgress)
                }
            }
            loadUrl(readerUrl)
        }
    }

    LaunchedEffect(webView) { onWebViewReady(webView) }
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.webViewClient = WebViewClient()
            webView.destroy()
        }
    }
    BackHandler {
        if (webView.canGoBack()) webView.goBack() else onBack()
    }

    Box(modifier = modifier) {
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
        if (webState.loading) {
            val progress = webState.progress
            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                )
            }
        }
        webState.failedMessage?.let { message ->
            ReaderWebErrorOverlay(
                message = message,
                onRetry = {
                    webState = zlibReaderOnRetry(webState)
                    webView.reload()
                },
                // 票据过期（401/403）时同一 URL 重载无意义，给「重新获取链接」走详情页重解析。
                onRefreshLink = if (zlibReaderNeedsFreshLink(message)) onRefreshReaderLink else null,
                onClose = onBack,
            )
        }
    }
}

@Composable
private fun ReaderWebErrorOverlay(
    message: String,
    onRetry: () -> Unit,
    onRefreshLink: (() -> Unit)? = null,
    onClose: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            // 失败遮罩盖住 WebView 时吞掉触摸，避免点到看不见的页面。
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent()
                    }
                }
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.xl),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Outlined.BrokenImage,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = "在线阅读加载失败",
                style = AppType.sectionTitle,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.lg))
            Button(
                onClick = onRetry,
                shape = RoundedCornerShape(Radius.cover),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("重试")
            }
            if (onRefreshLink != null) {
                TextButton(onClick = onRefreshLink) {
                    Text("重新获取链接")
                }
            }
            TextButton(onClick = onClose) {
                Text("返回详情")
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun configureZlibReaderWebView(webView: WebView) {
    with(webView.settings) {
        // P1d §1.6：LITERA 是 JS SPA，必须开 JS + DOM storage；UA 保持系统默认
        // （不要伪装桌面，LITERA 有 is-desktop-app 探测，伪装可能进错分支）。
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        loadWithOverviewMode = false
        useWideViewPort = false
    }
    CookieManager.getInstance().setAcceptCookie(true)
}
