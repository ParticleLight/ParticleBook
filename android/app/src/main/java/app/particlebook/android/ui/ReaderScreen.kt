package app.particlebook.android.ui

import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.Window
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.particlebook.android.reader.AnnotationStore
import app.particlebook.android.reader.ReaderAnnotation
import app.particlebook.android.ui.data.Book
import app.particlebook.android.ui.data.BookFormat
import app.particlebook.android.ui.data.ReadingProgress
import app.particlebook.android.reader.ReaderLocator
import app.particlebook.android.reader.export.AnnotationExportPlanner
import app.particlebook.android.reader.pdf.PdfNormalizedRect
import app.particlebook.android.reader.pdf.PdfSelectionController
import app.particlebook.android.reader.pdf.PdfTextLayerController
import app.particlebook.android.reader.pdf.PdfTextLayerDocumentSource
import app.particlebook.android.reader.pdf.PdfTtsFollow
import app.particlebook.android.reader.pdf.PdfTtsPage
import app.particlebook.android.reader.pdf.perf.PdfNavSource
import app.particlebook.android.reader.pdf.perf.PdfOrigin
import app.particlebook.android.reader.pdf.perf.PdfPageCache
import app.particlebook.android.reader.pdf.perf.PdfPresentWindow
import app.particlebook.android.reader.pdf.perf.PdfReaderAttempt
import app.particlebook.android.reader.pdf.perf.PdfSessionClosed
import app.particlebook.android.reader.pdf.search.PdfSearchHit
import app.particlebook.android.reader.pdf.search.PdfSearchSession
import app.particlebook.android.reader.pdf.search.PdfSearchSessionOpenResult
import app.particlebook.android.reader.tts.AndroidTtsSpeaker
import app.particlebook.android.reader.tts.TtsPlayback
import app.particlebook.android.reader.tts.TtsPlaybackState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 日志 tag：安装后由 Lead 抓 logcat 用。 */
private const val TAG = "PBReader"

/** 性能体检日志 tag（release 也打，格式 `PBPERF|phase=...|ms=...|extra=k:v`）。 */
private const val PERF_TAG = "PBPERF"

/** chrome 显示后无操作自动隐藏的时间。 */
private const val CHROME_AUTO_HIDE_MS = 4000L

/** 页与页之间的间距。 */
private val PageGap = 12.dp

/** A4 高宽比，只用于未渲染页的占位块高度，渲染完成后按真实尺寸。 */
private const val PlaceholderAspect = 1.414f

/** 页面宽/高（A4）。用来估算「按视口高度适配」时该渲染多宽。 */
private const val PageWidthOverHeight = 0.7071f

/** 单页位图内存上限（ARGB_8888 = 宽×高×4 字节）。横屏撑满宽度时曾到 2400×3398 ≈ 32MB。 */
private const val MaxPageBitmapBytes = 12 * 1024 * 1024

/** 朗读高亮叠色：浅色画布 / 白纸位图上的透明度。 */
private const val TtsHighlightLightAlpha = 0.24f

/** 深色方案下的透明度：同族颜色更深，压低一点避免盖住字。 */
private const val TtsHighlightDarkAlpha = 0.32f

/** 书内搜索命中：自己一层淡色，和朗读高亮叠在同一页时仍能看出各是各的。 */
private const val SearchHighlightLightAlpha = 0.28f

/** 深色方案下 tertiaryContainer 与纸的对比更强，透明度压低一点。 */
private const val SearchHighlightDarkAlpha = 0.36f

/**
 * 朗读高亮色（PDF）。
 *
 * PDF 页是原版位图，底色永远是白纸，所以叠色的对比对象是「纸」而不是「画布」：
 * - 浅色方案直接用 primary（#2563EB，深蓝），叠白纸清晰可辨；
 * - 深色方案的 primary 是 #B0C6FF（淡蓝），叠白纸几乎看不见，因此换同族的
 *   primaryContainer（#00429B，深蓝）。
 * 两侧同属 primary 色族，和 EPUB 侧的朗读高亮是同一个观感；两档都只引用语义角色，
 * 没有裸色，深色模式也不会退化成看不见的高亮。
 */
@Composable
private fun pdfTtsHighlightColor(): Color {
    val scheme = MaterialTheme.colorScheme
    val darkCanvas = scheme.surface.luminance() < 0.5f
    val role = if (darkCanvas) scheme.primaryContainer else scheme.primary
    return role.copy(alpha = if (darkCanvas) TtsHighlightDarkAlpha else TtsHighlightLightAlpha)
}

/**
 * 书内搜索命中色（PDF）。
 *
 * 和朗读高亮同一套「叠在白纸位图上」的取舍，但换一个色族：朗读走 primary，
 * 搜索走 tertiary，两者同时出现在一页时不会混成一片。深色方案下 tertiary 太淡，
 * 换 tertiaryContainer；两档都只引用语义角色，没有裸色。
 */
@Composable
private fun pdfSearchHighlightColor(): Color {
    val scheme = MaterialTheme.colorScheme
    val darkCanvas = scheme.surface.luminance() < 0.5f
    val role = if (darkCanvas) scheme.tertiaryContainer else scheme.tertiary
    return role.copy(alpha = if (darkCanvas) SearchHighlightDarkAlpha else SearchHighlightLightAlpha)
}

/** 页位图缓存总字节上限 = min(48MB, 堆上限 / 8)：约 3–6 页 A4 位图，避免整本 PDF 常驻。 */
private fun pageCacheLimitBytes(): Int {
    val maxMemory = Runtime.getRuntime().maxMemory()
    return min(48L * 1024 * 1024, maxMemory / 8).toInt()
}

/**
 * 阅读器分派：PDF 走原生纵向连续滚动；EPUB 走原生 Canvas 分页；
 * TXT / Markdown 走按段落流式渲染的滚动式阅读器（文本没有固定版式，不做假分页）。
 * 进入即切沉浸（隐藏系统栏），退出恢复。
 */
@Composable
fun ReaderScreen(
    book: Book,
    onBack: () -> Unit,
    annotationStore: AnnotationStore? = null,
    openStartedAtNanos: Long? = null,
    /**
     * 全文搜索命中位置：PDF 由 PdfReadingPane 按页落位（见 [initialPdfPage]），
     * EPUB 由 EpubReaderScreen 按章/块落位。两边都只消费一次：打开期优先于上次阅读位置，
     * 重试或重组不会把用户拽回命中处。
     */
    initialLocator: ReaderLocator? = null,
    /** 排版输入（字号 / 行高）：EPUB 与文本阅读器消费；PDF 是固定版式，只透传不生效。 */
    fontSizeSp: Float = ReaderTypographySpec.DEFAULT_FONT_SIZE_SP,
    lineHeightMultiplier: Float = ReaderTypographySpec.DEFAULT_LINE_HEIGHT,
    onFontSizeChange: (Float) -> Unit = {},
    onLineHeightChange: (Float) -> Unit = {},
) {
    BackHandler(onBack = onBack)
    when (book.format) {
        BookFormat.Pdf -> PdfReaderScreen(
            book = book,
            onBack = onBack,
            annotationStore = annotationStore,
            initialLocator = initialLocator as? ReaderLocator.Pdf,
        )
        BookFormat.Epub -> EpubReaderScreen(
            book = book,
            onBack = onBack,
            annotationStore = annotationStore,
            openStartedAtNanos = openStartedAtNanos,
            initialLocator = initialLocator,
            fontSizeSp = fontSizeSp,
            lineHeightMultiplier = lineHeightMultiplier,
            onFontSizeChange = onFontSizeChange,
            onLineHeightChange = onLineHeightChange,
        )
        BookFormat.Text, BookFormat.Markdown, BookFormat.Fb2, BookFormat.Html -> TextReaderScreen(
            book = book,
            onBack = onBack,
            initialLocator = initialLocator,
            fontSizeSp = fontSizeSp,
            lineHeightMultiplier = lineHeightMultiplier,
            onFontSizeChange = onFontSizeChange,
            onLineHeightChange = onLineHeightChange,
        )
        BookFormat.Unsupported -> UnsupportedReaderScreen(book = book, onBack = onBack)
    }
}

/**
 * 未知格式入口：不把 AZW3/DOCX/MOBI 之类硬塞进 EPUB 解析器，也不让点击无反应。
 * 明确告诉用户当前支持哪几种格式，并保留返回路径。
 */
@Composable
private fun UnsupportedReaderScreen(book: Book, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    ImmersiveSystemBars()
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text("暂不支持此格式", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "《" + book.title + "》是 " + book.format.label + " 格式，当前原生阅读器支持 EPUB、PDF、TXT、Markdown、FB2 与 HTML。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onBack) { Text("返回书架") }
        }
        TextButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            Spacer(Modifier.width(4.dp))
            Text("返回")
        }
    }
}

// ---------------------------------------------------------------- PDF 阅读器

private sealed interface PdfState {
    data object Loading : PdfState
    class Ready(val pageCount: Int) : PdfState
    class Error(val message: String) : PdfState
}

@Composable
private fun PdfReaderScreen(
    book: Book,
    onBack: () -> Unit,
    annotationStore: AnnotationStore? = null,
    /** 搜索命中页：打开期优先于上次阅读位置，只消费一次（见 PdfReadingPane 的 pendingJump）。 */
    initialLocator: ReaderLocator.Pdf? = null,
) {
    ImmersiveSystemBars()
    var reloadTick by remember(book.id, book.filePath) { mutableIntStateOf(0) }
    val attempt = remember(book.id, book.filePath, reloadTick) { PdfReaderAttempt(book.id, File(book.filePath)) }
    var state by remember(attempt) { mutableStateOf<PdfState>(PdfState.Loading) }

    DisposableEffect(attempt) { onDispose { attempt.close() } }
    LaunchedEffect(attempt) {
        state = try {
            PdfState.Ready(attempt.open())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "PDF open failed", error)
            PdfState.Error(error.describe())
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        when (val current = state) {
            is PdfState.Loading -> PdfLoading()
            is PdfState.Error -> PdfOpenError(
                message = current.message,
                onRetry = { reloadTick++ },
                onBack = onBack,
            )
            is PdfState.Ready -> {
                if (current.pageCount <= 0) {
                    PdfOpenError(
                        message = "这份 PDF 没有可显示的页面",
                        onRetry = { reloadTick++ },
                        onBack = onBack,
                    )
                } else {
                    key(attempt) {
                        PdfReadingPane(
                            book = book,
                            attempt = attempt,
                            pageCount = current.pageCount,
                            onBack = onBack,
                            annotationStore = annotationStore,
                            initialLocator = initialLocator,
                        )
                    }
                }
            }
        }
    }
}

/** 打开 PDF 时的等待态：用 material3 1.5.0 统一的 LoadingIndicator（形状变形替代旧圆环）。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PdfLoading() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        LoadingIndicator(
            modifier = Modifier.size(Spacing.xl),
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = "正在打开…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PdfOpenError(message: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.BrokenImage,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(Spacing.md))
        Text("无法打开这本书", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.lg))
        FilledTonalButton(onClick = onRetry) { Text("重试") }
        Spacer(Modifier.height(Spacing.xs))
        TextButton(onClick = onBack) { Text("返回书架") }
    }
}

/** 主阅读区：纵向连续滚动 + 覆盖式 chrome + 页码/目录 sheet。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PdfReadingPane(
    book: Book,
    attempt: PdfReaderAttempt,
    pageCount: Int,
    onBack: () -> Unit,
    annotationStore: AnnotationStore? = null,
    /** 全文搜索命中页；页数对不上（页数变了/换了文件）就当没有，交回原有恢复逻辑。 */
    initialLocator: ReaderLocator.Pdf? = null,
) {
    val context = LocalContext.current
    val restoredProgress = remember(book.id) { ReadingProgress.snapshot(book.id) }
    val restoredLocator = remember(restoredProgress) { restoredProgress?.locator() as? ReaderLocator.Pdf }
    val preserveUnrestoredLocator = remember(restoredProgress, pageCount) {
        restoredProgress?.locatorJson != null &&
            (restoredLocator == null || restoredLocator.page !in 0 until pageCount)
    }
    // 命中页只属于「这本书的这一次打开」：remember(book.filePath) 存住，首帧布局后置空。
    // 不能每次重组都拿它当起点，否则用户往下翻会被拽回命中处。
    var pendingJump by remember(book.filePath) {
        mutableStateOf(initialLocator?.takeIf { it.page in 0 until pageCount })
    }
    val startPage = remember(book.id, pageCount) {
        initialPdfPage(
            jump = pendingJump,
            restored = restoredLocator,
            savedPage = ReadingProgress.savedPage(context, book.id),
            pageCount = pageCount,
        )
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startPage)
    val view = LocalView.current
    val presenter = remember(attempt, view) { PdfPresentWindow(view, attempt.perf) }
    DisposableEffect(presenter) {
        presenter.start()
        onDispose { presenter.close() }
    }
    TrackPdfDrag(listState, pageCount, attempt, presenter)
    // 有命中页时这次打开本身就是一次显式导航：别让「失效的恢复位置」保护拦住跳转后的进度落盘。
    val progressState = remember(book.id) {
        PdfProgressState(preserveUnrestoredLocator && pendingJump == null)
    }
    fun beginProgressNavigation() {
        progressState.navigationStarted(
            if (listState.layoutInfo.visibleItemsInfo.isNotEmpty()) computeCurrentPage(listState, pageCount) else null,
        )
    }
    val scope = rememberCoroutineScope()
    // 搜索与渲染 / 文本层各持一份只读 fd：application context 只是为了不让这次打开
    // 跟着 Activity 走，句柄本身仍由 PdfSearchSession.close() 归还。
    val appContext = context.applicationContext
    // 性能体检：翻页计时用（纵向连续滚动，没有离散翻页手势）。
    var scrollStartMs by remember { mutableLongStateOf(0L) }
    var lastTurnPage by remember(book.id) { mutableIntStateOf(startPage) }
    var chromeVisible by remember { mutableStateOf(true) }
    var chromeTick by remember { mutableIntStateOf(0) }
    var tocOpen by remember { mutableStateOf(false) }
    var moreMenuOpen by remember { mutableStateOf(false) }
    // 标注导出：PDF 没有常驻标注侧栏，只在打开弹层时按需把这本书的标注读一次。
    var exportOpen by remember(book.filePath) { mutableStateOf(false) }
    var exportAnnotations by remember(book.filePath) { mutableStateOf<List<ReaderAnnotation>?>(null) }
    var exportReadFailed by remember(book.filePath) { mutableStateOf(false) }
    var detailsOpen by remember { mutableStateOf(false) }
    var pageJumpOpen by remember { mutableStateOf(false) }
    var bookmark by rememberSaveable(book.id) { mutableStateOf(false) }
    var sliderDragging by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableFloatStateOf(0f) }
    // P1：PDF 文本层（可见页 ±1，只读）。失败不阻断渲染，只记日志。
    var textLayer by remember(book.filePath) { mutableStateOf<PdfTextLayerController?>(null) }
    // A022-2：文本层打不开时（扫描版 PDF）给一次明确提示，而不是让长按毫无反应。
    var textLayerUnavailable by remember(book.filePath) { mutableStateOf(false) }
    // 书内搜索：面板开关、输入、状态机、当前命中（高亮用）与在跑的任务。
    // 都按 book.filePath 记忆 —— 换一本书不会把上一本的命中页带过来。
    var searchOpen by remember(book.filePath) { mutableStateOf(false) }
    var searchQuery by remember(book.filePath) { mutableStateOf("") }
    var searchState by remember(book.filePath) { mutableStateOf<PdfSearchUiState>(PdfSearchUiState.Idle) }
    var searchHighlight by remember(book.filePath) { mutableStateOf<PdfSearchHit?>(null) }
    var searchJob by remember(book.filePath) { mutableStateOf<Job?>(null) }
    LaunchedEffect(textLayerUnavailable) {
        if (!textLayerUnavailable) return@LaunchedEffect
        delay(8000)
        textLayerUnavailable = false
    }
    // A022-2：PDF 文本层就绪后才有选区；文本层失败时 state 为 null，长按不拦截页面点击。
    val selectionController = remember(textLayer) {
        textLayer?.let { PdfSelectionController(PdfTextLayerDocumentSource(it)) }
    }
    val selectionState = selectionController?.let {
        rememberPdfSelectionState(it, book.id, annotationStore)
    }

    val currentPage by remember(listState, pageCount) {
        derivedStateOf { computeCurrentPage(listState, pageCount) }
    }
    val displayPage = if (sliderDragging) {
        sliderValue.roundToInt().coerceIn(0, pageCount - 1)
    } else {
        currentPage.coerceIn(0, pageCount - 1)
    }

    // 朗读：整套引擎/状态机/控制条都复用 EPUB 那份（reader/tts + ui/TtsBar），PDF 这边只补两件它
    // 没有的东西：①文本来源是本页字符层（PdfTtsPage），②句子高亮靠字符框而不是 blockIndex。
    // 朗读永远只覆盖「当前这一页」，换页即停（见下方 LaunchedEffect），避免念到屏幕外的内容。
    var ttsState by remember { mutableStateOf<TtsPlaybackState>(TtsPlaybackState.Idle) }
    var ttsPage by remember { mutableStateOf<PdfTtsPage?>(null) }
    var ttsRects by remember { mutableStateOf<List<PdfNormalizedRect>>(emptyList()) }
    var ttsRate by remember { mutableStateOf(1.0f) }
    // 与 EPUB 侧同一套做法：焦点回调需要 playback，用持有者打破循环。
    val ttsPlaybackHolder = remember { mutableStateOf<TtsPlayback?>(null) }
    val ttsSpeaker = remember(context) {
        AndroidTtsSpeaker(context, onFocusLost = { ttsPlaybackHolder.value?.pause() })
    }
    val ttsPlayback = remember(ttsSpeaker) {
        TtsPlayback(
            speaker = ttsSpeaker,
            onStateChanged = { state ->
                ttsState = state
                // 回到 Idle 说明本页念完或被停止：句子坐标与矩形一起作废，避免残留高亮。
                if (state is TtsPlaybackState.Idle) {
                    ttsPage = null
                    ttsRects = emptyList()
                }
            },
        )
    }
    SideEffect { ttsPlaybackHolder.value = ttsPlayback }
    DisposableEffect(ttsPlayback) {
        onDispose { ttsPlayback.shutdown() }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val notify: (String) -> Unit = { message ->
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    // 导出弹层打开才读标注：读完就绪前 exportDocument 为 null，弹层自然不出现；
    // 关掉时清空，用户改过标注后再打开能拿到最新一份。
    LaunchedEffect(exportOpen, annotationStore, book.id) {
        if (!exportOpen) {
            exportAnnotations = null
            return@LaunchedEffect
        }
        val store = annotationStore ?: return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) { runCatching { store.list(book.id) } }
        exportAnnotations = loaded.getOrNull()
        exportReadFailed = loaded.isFailure
    }
    LaunchedEffect(exportReadFailed) {
        if (!exportReadFailed) return@LaunchedEffect
        exportReadFailed = false
        exportOpen = false
        notify("标注读取失败，请重试")
    }
    // PDF 没有目录标题可对位，章节一律由 planner 归到「第 N 页」。
    val exportDocument = remember(exportAnnotations) {
        exportAnnotations?.let { AnnotationExportPlanner.plan(book, it) }
    }

    // 文本层按页取（PDF 没有整篇粒度可用，也没必要把整本书读进内存）。loadPage 会走
    // PdfTextLayerController 的页缓存，通常命中当前页 ±1 的预载窗口；仍在 IO 线程执行，
    // 因为未命中时它会同步调用 PDFium。
    val startTts: () -> Unit = {
        val layer = textLayer
        if (layer == null) {
            // textLayerUnavailable 8 秒后会自动复位，不能拿它判断"到底有没有文本层"；
            // 这里只描述当前事实：文本层还没就绪。
            notify("本页文字还没就绪，暂时不能朗读")
        } else {
            scope.launch {
                val page = currentPage.coerceIn(0, pageCount - 1)
                val reading = try {
                    attempt.whileOpen {
                        withContext(Dispatchers.IO) { PdfTtsPage.from(layer.loadPage(page)) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (closed: PdfSessionClosed) {
                    return@launch
                } catch (error: Exception) {
                    Log.e(TAG, "pdf tts read page failed page=" + (page + 1), error)
                    notify("读取本页文字失败：" + error.describe())
                    return@launch
                }
                if (reading.sentences.isEmpty()) {
                    notify("本页没有可朗读的文字")
                    return@launch
                }
                ttsPlayback.setRate(ttsRate)
                // start 返回 false = 没开始（引擎未就绪）：此时先别挂本页句子，
                // 否则 Idle 状态下 ttsPage 仍指着上一页，高亮/矩形会留一层空壳。
                if (!ttsPlayback.start(reading.sentences)) {
                    notify("系统朗读引擎不可用")
                    return@launch
                }
                ttsPage = reading
            }
        }
    }

    // 当前句高亮：几何直接用句子的字符框在内存里算（PdfTtsPage 构造时已经握着那批 PdfChar），
    // 不再回头找文本层控制器要锁 —— 那条路在朗读进行中不返回，控制条照常推进而矩形永远为空。
    LaunchedEffect(ttsState.sentenceIndex, ttsPage, currentPage) {
        val reading = ttsPage
        val index = ttsState.sentenceIndex
        ttsRects = if (reading == null || index < 0 || reading.page != currentPage) {
            emptyList()
        } else {
            reading.rects(index)
        }
    }

    // 翻页即停（与 EPUB 同一语义）：PDF 是连续滚动，currentPage 会随滚动反复重算，
    // 判定收在 PdfTtsFollow 里，只有「念的已经不是屏幕上那一页」才停。
    LaunchedEffect(currentPage, ttsPage) {
        if (PdfTtsFollow.shouldStop(ttsPage, currentPage)) ttsPlayback.stop()
    }
    // 换书即停（EPUB 侧同一个坑）：换书后页号常常还是原来那个，PdfTtsFollow 只比页号，
    // 比不出「这已经不是同一本 PDF」，只有文档标识能发现，所以单独按它兜底。
    LaunchedEffect(book.filePath) {
        if (ttsState !is TtsPlaybackState.Idle) ttsPlayback.stop()
    }

    // 翻页：从滚动开始（手势）到新页成为当前页并停顿的首个空闲帧；每翻到一页打一次。
    LaunchedEffect(listState, pageCount) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) {
                beginProgressNavigation()
                scrollStartMs = SystemClock.elapsedRealtime()
            } else if (scrollStartMs > 0L) {
                val page = computeCurrentPage(listState, pageCount)
                if (page != lastTurnPage) {
                    Log.i(
                        PERF_TAG,
                        "PBPERF|phase=turn|page:" + page + "|ms=" +
                            (SystemClock.elapsedRealtime() - scrollStartMs) +
                            "|extra=from:" + lastTurnPage,
                    )
                    lastTurnPage = page
                }
                scrollStartMs = 0L
            }
        }
    }

    val wakeChrome: () -> Unit = {
        chromeVisible = true
        chromeTick++
    }
    val toggleChrome: () -> Unit = {
        if (chromeVisible) {
            chromeVisible = false
        } else {
            wakeChrome()
        }
    }

    LaunchedEffect(currentPage, sliderDragging) {
        if (!sliderDragging) sliderValue = currentPage.toFloat()
    }
    // SQLite 是进度真源；只同步入队，磁盘写入由进程级单 writer 执行。
    fun saveProgress(page: Int) {
        if (!progressState.visiblePage(page)) return
        ReadingProgress.savePdfProgress(context, book.id, page, pageCount)
    }
    val saveVisibleProgress: () -> Unit = {
        if (listState.layoutInfo.visibleItemsInfo.isNotEmpty()) {
            saveProgress(computeCurrentPage(listState, pageCount))
        }
    }
    val leaveReader: () -> Unit = {
        saveVisibleProgress()
        onBack()
    }
    BackHandler(onBack = leaveReader)
    LaunchedEffect(book.id, listState, pageCount) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.isNotEmpty() to computeCurrentPage(listState, pageCount)
        }.collect { (laidOut, page) ->
            if (laidOut) saveProgress(page)
        }
    }
    // 命中页已经通过 initialFirstVisibleItemIndex 落位，这里只负责「消费掉」：
    // 首帧布局完成后清空，之后的滚动、重组、换页都不会再把它当起点。
    LaunchedEffect(listState, book.filePath) {
        if (pendingJump == null) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.isNotEmpty() }.first { it }
        pendingJump = null
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, book.id, listState, pageCount) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) saveVisibleProgress()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(
        chromeVisible, chromeTick, tocOpen, moreMenuOpen, detailsOpen, pageJumpOpen, sliderDragging, searchOpen,
    ) {
        if (chromeVisible && !tocOpen && !moreMenuOpen && !detailsOpen && !pageJumpOpen && !sliderDragging && !searchOpen) {
            delay(CHROME_AUTO_HIDE_MS)
            chromeVisible = false
        }
    }

    val jumpToPage: (Int, PdfNavSource) -> Unit = { index, source ->
        beginProgressNavigation()
        val target = index.coerceIn(0, pageCount - 1)
        val laidOut = listState.layoutInfo.visibleItemsInfo.isNotEmpty()
        // A TOC confirmation belongs to this reader even while its dialog holds focus.
        val nav = if (laidOut && presenter.isVisible()) {
            attempt.perf.startNav(computeCurrentPage(listState, pageCount), target, source)
        } else null
        scope.launch {
            try {
                listState.scrollToItem(target)
                // Let the new layout launch its visible requests before settling the navigation.
                withFrameNanos { }
                snapshotFlow { listState.isScrollInProgress }.first { !it }
                if (nav != null) {
                    if (presenter.isActive() && listState.layoutInfo.visibleItemsInfo.isNotEmpty()) {
                        attempt.perf.finishNav(nav, computeCurrentPage(listState, pageCount))
                    } else attempt.perf.finishNav(nav, null, "window_inactive")
                }
                wakeChrome()
            } finally {
                if (nav != null) attempt.perf.finishNav(nav, null, "interrupted")
            }
        }
    }

    /*
     * 书内搜索（整本）。
     *
     * 用独立的 PdfSearchSession（自己的 fd + cacheLimit=1 的文本源）在 IO 线程逐页扫：
     * 阅读屏那份文本层控制器只服务可见页 ±1，拿它扫全书会把当前页挤出缓存、
     * 让翻页反复重开 native 页句柄。扫描结束（含取消 / 异常）一定 close。
     *
     * 进度不逐页写 Compose state：CONFLATED channel 只保留最新一格，主线程按帧丢弃中间值，
     * 停顿与取消都不需要额外标志位 —— 取消就是 cancel 这个 Job。
     */
    val startSearch: () -> Unit = {
        val query = searchQuery.trim()
        val file = File(book.filePath)
        if (query.isEmpty()) {
            searchState = PdfSearchUiState.Idle
        } else {
            searchJob?.cancel()
            searchHighlight = null
            searchState = PdfSearchUiState.Scanning(0, pageCount, 0)
            searchJob = scope.launch {
                val progress = Channel<Pair<Int, Int>>(Channel.CONFLATED)
                val pump = launch {
                    for ((scanned, hits) in progress) {
                        searchState = PdfSearchUiState.Scanning(scanned, pageCount, hits)
                    }
                }
                try {
                    when (val opened = withContext(Dispatchers.IO) { PdfSearchSession.open(appContext, file) }) {
                        is PdfSearchSessionOpenResult.Failure -> {
                            val detail = opened.message
                            searchState = PdfSearchUiState.Failed(
                                if (detail.isNullOrBlank()) "打不开这份 PDF 的文本层，无法搜索"
                                else "打不开这份 PDF 的文本层，无法搜索（" + detail + "）",
                            )
                        }

                        is PdfSearchSessionOpenResult.Success -> {
                            val session = opened.session
                            try {
                                val report = withContext(Dispatchers.IO) {
                                    session.scan(query) { scanned, hits ->
                                        progress.trySend(scanned to hits)
                                    }
                                }
                                searchState = PdfSearchUiState.Done(report)
                            } finally {
                                withContext(NonCancellable + Dispatchers.IO) { session.close() }
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    searchState = PdfSearchUiState.Failed("搜索失败：" + error.describe())
                } finally {
                    progress.close()
                    pump.cancel()
                    // 只清掉自己的引用：用户可能已经用新关键词又发起了一次搜索，
                    // 旧 Job 的收尾若无条件置空，会把新 Job 一起忘掉，之后停止/关闭都取消不到它。
                    if (searchJob == currentCoroutineContext()[Job]) searchJob = null
                }
            }
        }
    }
    // 离开阅读屏 / 换书：正在跑的扫描没有意义了，连任务带会话一起收掉。
    DisposableEffect(book.filePath) {
        onDispose {
            searchJob?.cancel()
            searchJob = null
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        val density = LocalDensity.current
        val viewportWidthPx = with(density) { maxWidth.toPx() }
        val viewportHeightPx = with(density) { maxHeight.toPx() }
        // 渲染宽度三重取小：视口宽 / 按高度整页可见 / 单页 12MB 上限，横屏不会再把整页裁掉。
        val pageWidthPx = computeRenderWidthPx(viewportWidthPx, viewportHeightPx)
        val pageWidthDp = with(density) { pageWidthPx.toDp() }
        // 页位图 LRU 缓存：显式字节上限 + in-use 保护（正在显示的页不会被回收）。
        val pageCache = remember(attempt, pageWidthPx) {
            PdfPageCache<Bitmap>(
                byteLimit = pageCacheLimitBytes(),
                sizeOf = { it.allocationByteCount.toLong() },
                recycle = Bitmap::recycle,
                stat = { count, bytes -> Log.i(PERF_TAG, "PBPERF|phase=cache_stat|ms=0|extra=count:" + count + ",bytes:" + bytes) },
                evicted = { page -> Log.i(PERF_TAG, "PBPERF|phase=cache_evict|ms=0|extra=page:" + page) },
            )
        }
        DisposableEffect(pageCache) {
            attempt.attachCache(pageCache)
            onDispose { attempt.retireCache(pageCache) }
        }
        // 系统内存吃紧（TRIM_MEMORY_RUNNING_LOW 起）时主动丢掉缓存里非显示页。
        DisposableEffect(pageCache, appContext) {
            val callbacks = object : ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) {
                    if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                        Log.i(PERF_TAG, "PBPERF|phase=cache_trim|ms=0|extra=level:" + level)
                        pageCache.trim()
                    }
                }

                override fun onLowMemory() {
                    Log.i(PERF_TAG, "PBPERF|phase=cache_trim|ms=0|extra=level:" + ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
                    pageCache.trim()
                }

                override fun onConfigurationChanged(newConfig: Configuration) {
                    // 配置变化会重建界面，缓存随组合一起重建，这里无需处理。
                }
            }
            appContext.registerComponentCallbacks(callbacks)
            onDispose { appContext.unregisterComponentCallbacks(callbacks) }
        }
        // 文本层在独立 IO lane 打开，延后 600ms 避开首帧渲染。
        LaunchedEffect(attempt) {
            val begin = SystemClock.elapsedRealtime()
            try {
                val controller = attempt.openText(appContext)
                textLayer = controller
                if (controller == null) {
                    textLayerUnavailable = true
                } else {
                    Log.i(PERF_TAG, "PBPERF|phase=pdf_text_open|ms=" + (SystemClock.elapsedRealtime() - begin) +
                        "|extra=pages:" + controller.pageCount)
                }
            } catch (closed: PdfSessionClosed) {
                // The attempt was disposed while probing.
            }
        }
        // 可见页 ±1；滚动中不重复预载，页面稳定后一次。
        LaunchedEffect(textLayer, currentPage, pageCount, listState.isScrollInProgress) {
            val layer = textLayer ?: return@LaunchedEffect
            if (listState.isScrollInProgress) return@LaunchedEffect
            val begin = SystemClock.elapsedRealtime()
            val window = try {
                attempt.whileOpen { withContext(Dispatchers.IO) { layer.preloadVisible(currentPage, 1) } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (window != null) {
                Log.i(
                    PERF_TAG,
                    "PBPERF|phase=pdf_text_preload|ms=" + (SystemClock.elapsedRealtime() - begin) +
                        "|extra=page:" + currentPage + ",loaded:" + window.pages.size +
                        ",failed:" + window.failures.size,
                )
            }
        }
        // 空闲时预取相邻 2 页；滚动中不插队，避免和当前页渲染抢同一条 PDF 线程。
        LaunchedEffect(pageCache, currentPage, pageCount, listState.isScrollInProgress) {
            if (listState.isScrollInProgress || pageCount <= 1) return@LaunchedEffect
            attempt.prefetch(pageCache, pageWidthPx, currentPage + 1)
            attempt.prefetch(pageCache, pageWidthPx, currentPage - 1)
        }
        val chromeShiftPx = with(density) { 8.dp.roundToPx() }
        val chromeMotion = Motion.enterSpatial<androidx.compose.ui.unit.IntOffset>()
        val chromeFade = Motion.enterFade<Float>()
        val chromeMotionOut = Motion.exitSpatial<androidx.compose.ui.unit.IntOffset>()
        val chromeFadeOut = Motion.exitFade<Float>()
        // 朗读高亮只交给"正在念的那一页"，其余页传 null：参数不变，LazyColumn 会跳过它们。
        val ttsHighlightPage = ttsPage?.page ?: -1
        // 搜索命中同理：只有当前命中页分到矩形，其余页保持 null。
        val searchHighlightPage = searchHighlight?.page ?: -1
        val searchHighlightRects = searchHighlight?.rects ?: emptyList()

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(pageCount) {
                    detectTapGestures(onTap = { toggleChrome() })
                },
            verticalArrangement = Arrangement.spacedBy(PageGap),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = Spacing.sm,
                bottom = 96.dp,
            ),
        ) {
            items(count = pageCount, key = { index -> index }) { index ->
                PdfPageItem(
                    cache = pageCache,
                    attempt = attempt,
                    presenter = presenter,
                    listState = listState,
                    widthPx = pageWidthPx,
                    pageIndex = index,
                    widthDp = pageWidthDp,
                    selectionState = selectionState,
                    ttsHighlight = if (index == ttsHighlightPage) ttsRects else null,
                    searchHighlight = if (index == searchHighlightPage) searchHighlightRects else null,
                )
            }
        }

        // chrome 收起后，屏幕最上方留一条 2dp 阅读进度线（点按切回 chrome 后隐藏，避免和顶栏重影）。
        if (!chromeVisible) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(2.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(((displayPage + 1f) / pageCount).coerceIn(0f, 1f))
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.tertiary),
                )
            }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(chromeFade) + slideInVertically(chromeMotion) { -chromeShiftPx },
            exit = fadeOut(chromeFadeOut) + slideOutVertically(chromeMotionOut) { -chromeShiftPx },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ReaderTopBar(
                title = book.title,
                onBack = leaveReader,
                actions = {
                    TextButton(onClick = startTts) { Text("朗读") }
                    IconButton(onClick = {
                        bookmark = !bookmark
                        wakeChrome()
                    }) {
                        Icon(
                            imageVector = if (bookmark) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                            contentDescription = if (bookmark) "取消书签" else "加书签",
                        )
                    }
                    IconButton(onClick = {
                        moreMenuOpen = true
                        wakeChrome()
                    }) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "更多",
                        )
                    }
                    DropdownMenu(
                        expanded = moreMenuOpen,
                        onDismissRequest = { moreMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("目录") },
                            onClick = {
                                moreMenuOpen = false
                                tocOpen = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("搜索") },
                            onClick = {
                                moreMenuOpen = false
                                searchOpen = true
                                wakeChrome()
                            },
                        )
                        DropdownMenuItem(
                            // PDF 是固定版式：字号/行高只对 EPUB 正文有意义，这里不放假入口。
                            text = { Text("阅读设置（PDF 为固定版式）") },
                            enabled = false,
                            onClick = {},
                        )
                        DropdownMenuItem(
                            text = { Text("导出标注") },
                            // 标注存储没就绪时不留假入口；真要读失败会在打开时提示并收起弹层。
                            enabled = annotationStore != null,
                            onClick = {
                                moreMenuOpen = false
                                exportOpen = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("书籍详情") },
                            onClick = {
                                moreMenuOpen = false
                                detailsOpen = true
                            },
                        )
                    }
                },
            )
        }

        // A022-2：扫描版/无文本层 PDF 的提示；8 秒后自动消失，不长期遮挡正文。
        if (textLayerUnavailable) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 88.dp, start = Spacing.md, end = Spacing.md),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
                tonalElevation = Elevation.level1,
                shadowElevation = Elevation.level3,
            ) {
                Text(
                    text = "此 PDF 没有文本层，无法选择文字",
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        val activePdfSelection = selectionState?.selection?.takeIf { it.hasText }
        if (selectionState != null && activePdfSelection != null) {
            PdfSelectionToolbar(
                state = selectionState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp),
            )
        }
        if (ttsState !is TtsPlaybackState.Idle) {
            // 朗读中底栏换成控制条：同一组件、同一屏底位置（Spacing.md 与悬浮胶囊一致）。
            // 它不受 chrome 自动隐藏影响 —— 念到一半控件消失，用户就没法暂停或停止。
            TtsBar(
                state = ttsState,
                rate = ttsRate,
                onPlayPause = {
                    if (ttsState is TtsPlaybackState.Playing) ttsPlayback.pause() else ttsPlayback.resume()
                },
                onPrevious = { ttsPlayback.previous() },
                onNext = { ttsPlayback.next() },
                onCycleRate = {
                    val next = TtsRates[(TtsRates.indexOf(ttsRate) + 1).mod(TtsRates.size)]
                    ttsRate = next
                    ttsPlayback.setRate(next)
                },
                onStop = { ttsPlayback.stop() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = Spacing.md),
            )
        } else {
            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(chromeFade) + slideInVertically(chromeMotion) { chromeShiftPx },
                exit = fadeOut(chromeFadeOut) + slideOutVertically(chromeMotionOut) { chromeShiftPx },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                PdfBottomBar(
                    pageCount = pageCount,
                    displayPage = displayPage,
                    sliderValue = sliderValue,
                    onSliderChange = { value ->
                        sliderDragging = true
                        sliderValue = value
                    },
                    onSliderFinished = {
                        val target = sliderValue.roundToInt().coerceIn(0, pageCount - 1)
                        sliderDragging = false
                        jumpToPage(target, PdfNavSource.SLIDER)
                    },
                    onOpenToc = {
                        tocOpen = true
                        wakeChrome()
                    },
                    onOpenPageJump = {
                        pageJumpOpen = true
                        wakeChrome()
                    },
                )
            }
        }
        // 朗读/文本层提示走 snackbar：PDF 阅读页没有固定底栏文字区，提示不该长期占正文高度。
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 96.dp),
        )
    }

    if (searchOpen) {
        PdfSearchSheet(
            query = searchQuery,
            state = searchState,
            onQueryChange = { searchQuery = it },
            onSubmit = startSearch,
            onStop = {
                // 停止只取消扫描、不动输入框：用户改个词就能立刻再搜。
                searchJob?.cancel()
                searchJob = null
                searchState = PdfSearchUiState.Idle
            },
            onPick = { hit ->
                // 命中页 + 矩形都在扫描时算好了，这里只负责跳页；高亮留给 LazyColumn 那一页。
                searchHighlight = hit
                searchOpen = false
                jumpToPage(hit.page, PdfNavSource.SEARCH)
            },
            onDismiss = {
                searchOpen = false
                searchJob?.cancel()
                searchJob = null
            },
        )
    }

    if (tocOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { tocOpen = false },
            sheetState = sheetState,
            // spec §5.3：insets 不交给 sheet 组件，底部由内容自己 navigationBarsPadding()。
            contentWindowInsets = { WindowInsets(0) },
        ) {
            ImmersiveDialogWindow()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = Spacing.md),
            ) {
                Text(
                    text = "目录",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = Spacing.md),
                )
                Text(
                    text = "共 " + pageCount + " 页",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xxs),
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = Spacing.md,
                        end = Spacing.md,
                        top = Spacing.sm,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.tocGap),
                    verticalArrangement = Arrangement.spacedBy(Spacing.tocGap),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(count = pageCount, key = { index -> index }) { index ->
                        TocPageCell(
                            pageIndex = index,
                            selected = index == currentPage,
                            onClick = {
                                tocOpen = false
                                jumpToPage(index, PdfNavSource.TOC)
                            },
                        )
                    }
                }
            }
        }
    }

    if (detailsOpen) {
        AlertDialog(
            onDismissRequest = { detailsOpen = false },
            // spec §5.3：dialog 自己处理 insets，窗口不做系统栏 fit，避免开合时整页下移。
            properties = DialogProperties(decorFitsSystemWindows = false),
            title = { Text("书籍详情") },
            text = {
                Column {
                    ImmersiveDialogWindow()
                    Text("书名：" + book.title, style = MaterialTheme.typography.bodyMedium)
                    Text("作者：" + book.authorLabel, style = MaterialTheme.typography.bodyMedium)
                    Text("格式：" + book.format.label, style = MaterialTheme.typography.bodyMedium)
                    Text("大小：" + book.sizeLabel, style = MaterialTheme.typography.bodyMedium)
                    Text("页数：" + pageCount + " 页", style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(onClick = { detailsOpen = false }) { Text("关闭") }
            },
        )
    }

    if (exportOpen && exportDocument != null) {
        AnnotationExportSheet(
            document = exportDocument,
            onDismiss = { exportOpen = false },
            onNotify = notify,
        )
    }

    if (pageJumpOpen) {
        PdfPageJumpDialog(
            pageCount = pageCount,
            currentPage = currentPage.coerceIn(0, pageCount - 1),
            onDismiss = { pageJumpOpen = false },
            onConfirm = { page ->
                pageJumpOpen = false
                // 与底栏 slider 一样是显式页码导航：复用现有进度通道与导航结束钩子
                // （perf 里也沿用 slider 源，不为一次跳转新增枚举）。
                jumpToPage(page - 1, PdfNavSource.SLIDER)
            },
        )
    }
}

@Composable
private fun TocPageCell(pageIndex: Int, selected: Boolean, onClick: () -> Unit) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Surface(
        color = container,
        shape = MaterialTheme.shapes.small,
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = (pageIndex + 1).toString(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = content,
            )
        }
    }
}

/** 底部悬浮胶囊（§17.3）：surfaceContainerHigh@92% + 1dp 白描边 + 柔和投影，距底 insets + 12dp。 */
@Composable
private fun PdfBottomBar(
    pageCount: Int,
    displayPage: Int,
    sliderValue: Float,
    onSliderChange: (Float) -> Unit,
    onSliderFinished: () -> Unit,
    onOpenToc: () -> Unit,
    /** 点页码（"n / m"）打开「跳到第 N 页」输入框。 */
    onOpenPageJump: () -> Unit,
) {
    ReaderBottomPill {
        IconButton(onClick = onOpenToc) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.List,
                contentDescription = "目录",
            )
        }
        // 页码本身就是跳转入口：48dp 触控高度对齐两侧图标按钮，滚动条与胶囊节奏不变。
        Box(
            modifier = Modifier
                .height(48.dp)
                .widthIn(min = 64.dp)
                .clip(MaterialTheme.shapes.small)
                .clickable(onClickLabel = "跳到指定页", onClick = onOpenPageJump),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = (displayPage + 1).toString() + " / " + pageCount,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (pageCount > 1) {
            Spacer(Modifier.width(Spacing.xs))
            Slider(
                value = sliderValue.coerceIn(0f, (pageCount - 1).toFloat()),
                onValueChange = onSliderChange,
                onValueChangeFinished = onSliderFinished,
                valueRange = 0f..(pageCount - 1).toFloat(),
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        contentDescription = "跳转页码"
                        stateDescription = "第 " + (displayPage + 1) + " 页，共 " + pageCount + " 页"
                    },
            )
        }
    }
}

/** Observe input without consuming it. Layout/restoration alone cannot create a navigation. */
@Composable
private fun TrackPdfDrag(list: LazyListState, count: Int, attempt: PdfReaderAttempt, window: PdfPresentWindow) {
    LaunchedEffect(list, attempt, window) {
        coroutineScope {
            var drag: DragInteraction.Start? = null
            var nav: app.particlebook.android.reader.pdf.perf.PdfPerfSession.Nav? = null
            var settling: Job? = null
            try {
                list.interactionSource.interactions.collect { interaction ->
                    when (interaction) {
                        is DragInteraction.Start -> {
                            settling?.cancel()
                            drag = interaction
                            nav = if (window.isActive() && list.layoutInfo.visibleItemsInfo.isNotEmpty()) {
                                attempt.perf.startNav(computeCurrentPage(list, count), null, PdfNavSource.DRAG)
                            } else null
                        }
                        is DragInteraction.Stop -> if (interaction.start === drag) {
                            drag = null
                            val active = nav
                            if (active != null) settling = launch {
                                try {
                                    // Let the drag hand off to fling before looking for idle.
                                    withFrameNanos { }
                                    snapshotFlow { list.isScrollInProgress }.first { !it }
                                    if (window.isActive() && list.layoutInfo.visibleItemsInfo.isNotEmpty()) {
                                        attempt.perf.finishNav(active, computeCurrentPage(list, count))
                                    } else attempt.perf.finishNav(active, null, "window_inactive")
                                } finally {
                                    attempt.perf.finishNav(active, null, "interrupted")
                                }
                            }
                        }
                        is DragInteraction.Cancel -> if (interaction.start === drag) {
                            drag = null
                            nav?.let { attempt.perf.finishNav(it, null, "interrupted") }
                            nav = null
                        }
                    }
                }
            } finally {
                nav?.let { attempt.perf.finishNav(it, null, "interrupted") }
            }
        }
    }
}

private fun LazyListState.isPdfPageInViewport(page: Int): Boolean {
    val layout = layoutInfo
    return layout.visibleItemsInfo.any {
        it.index == page && it.size > 0 && it.offset < layout.viewportEndOffset &&
            it.offset + it.size > layout.viewportStartOffset
    }
}

private sealed interface PageRender {
    data object Loading : PageRender
    class Ready(val bitmap: Bitmap, val renderBeginMs: Long) : PageRender
    class Failed(val message: String) : PageRender
}

/** 单页：缓存命中直接显示，未命中再渲染；未完成显示占位块（不插 spinner），失败给内联错误 + 重试。 */
@Composable
private fun PdfPageItem(
    cache: PdfPageCache<Bitmap>,
    attempt: PdfReaderAttempt,
    presenter: PdfPresentWindow,
    listState: LazyListState,
    widthPx: Int,
    pageIndex: Int,
    widthDp: Dp,
    selectionState: PdfSelectionState? = null,
    /** 正在朗读的那一句在本页的矩形（归一化）。null 表示本页没有朗读高亮。 */
    ttsHighlight: List<PdfNormalizedRect>? = null,
    /** 书内搜索命中的矩形（归一化）。null 表示本页不是当前命中页。 */
    searchHighlight: List<PdfNormalizedRect>? = null,
) {
    var retry by remember(cache, pageIndex) { mutableIntStateOf(0) }
    val page = remember(cache, presenter, listState, pageIndex, retry) {
        presenter.newPage(pageIndex, listState::isPdfPageInViewport)
    }
    var render by remember(page) { mutableStateOf<PageRender>(PageRender.Loading) }
    DisposableEffect(page) {
        val leased = cache.acquire(pageIndex)
        page.register()
        onDispose {
            page.dispose()
            if (leased) cache.release(pageIndex)
        }
    }
    LaunchedEffect(page) {
        val began = SystemClock.elapsedRealtime()
        val origin = if (page.visibleNow()) PdfOrigin.DEMAND else PdfOrigin.LAZY
        val request = attempt.perf.startRequest(pageIndex, origin) ?: return@LaunchedEffect
        page.requested(request)
        render = try {
            val loaded = attempt.load(cache, widthPx, request)
            page.ready(request, loaded.producer)
            PageRender.Ready(loaded.bitmap, began)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (closed: PdfSessionClosed) {
            PageRender.Loading
        } catch (error: Exception) {
            Log.e(TAG, "render failed page=" + (pageIndex + 1), error)
            PageRender.Failed(error.describe())
        }
    }

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        when (val current = render) {
            is PageRender.Loading -> PagePlaceholder(pageIndex = pageIndex, width = widthDp)
            is PageRender.Ready -> {
                // PBPERF 保留旧frame-ready代理。正式PBPDF从下方实际Image draw+commit采集。
                LaunchedEffect(current) {
                    withFrameNanos { }
                    Log.i(
                        PERF_TAG,
                        "PBPERF|phase=page_present|page:" + pageIndex + "|ms=" +
                            (SystemClock.elapsedRealtime() - current.renderBeginMs) +
                            "|extra=size:" + current.bitmap.width + "x" + current.bitmap.height,
                    )
                }
                Box(modifier = Modifier.width(widthDp)) {
                    Image(
                        bitmap = current.bitmap.asImageBitmap(),
                        contentDescription = "第 " + (pageIndex + 1) + " 页",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.extraSmall)
                            .then(page.modifier()),
                    )
                    // 高亮压在图片之上、选区之下：搜索在底、朗读在上，共用同一份归一化矩形坐标。
                    // 两者都只画不接手势，触摸继续落到选区与翻页上。
                    val searchRects = searchHighlight
                    if (searchRects != null && searchRects.isNotEmpty()) {
                        PdfRectHighlightOverlay(
                            rects = searchRects,
                            color = pdfSearchHighlightColor(),
                            modifier = Modifier.matchParentSize(),
                        )
                    }
                    val highlight = ttsHighlight
                    if (highlight != null && highlight.isNotEmpty()) {
                        PdfRectHighlightOverlay(
                            rects = highlight,
                            color = pdfTtsHighlightColor(),
                            modifier = Modifier.matchParentSize(),
                        )
                    }
                    if (selectionState != null) {
                        PdfSelectionOverlay(
                            pageIndex = pageIndex,
                            state = selectionState,
                            modifier = Modifier.matchParentSize(),
                        )
                    }
                }
            }
            is PageRender.Failed -> PageRenderError(
                pageIndex = pageIndex,
                message = current.message,
                onRetry = { retry++ },
            )
        }
    }
}

/**
 * 朗读中的句子高亮。
 *
 * 坐标用的是归一化矩形（0..1，左上原点）乘实测尺寸，和 PdfSelectionOverlay 完全同一套换算，
 * 所以"高亮"和"选区"不会因为对旋转/裁剪的理解不同而互相错位。
 *
 * 只画不接手势：触摸继续落到底层的选区与翻页手势上，朗读期间翻页/选字都不受影响。
 * 矩形由调用方在 IO 线程算好（PdfTextLayerController.selectionRects 需要先打开文本页），
 * 这里保持纯绘制，不在 draw 阶段做 I/O。
 */
@Composable
private fun PdfRectHighlightOverlay(
    rects: List<PdfNormalizedRect>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas
        rects.forEach { rect ->
            drawRect(
                color = color,
                topLeft = Offset(rect.left * width, rect.top * height),
                size = Size(rect.width * width, rect.height * height),
            )
        }
    }
}

@Composable
private fun PagePlaceholder(pageIndex: Int, width: Dp) {
    Box(
        modifier = Modifier
            .width(width)
            .aspectRatio(1f / PlaceholderAspect)
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "第 " + (pageIndex + 1) + " 页",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PageRenderError(pageIndex: Int, message: String, onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "第 " + (pageIndex + 1) + " 页渲染失败",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(Spacing.xxs))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.xs))
            TextButton(onClick = onRetry) {
                Text("重试", color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

/**
 * 单页渲染宽度（px）：
 * 1. 不超过视口宽度；
 * 2. 横屏等「矮窗口」按高度适配（整页可见，不再只看到局部）；
 * 3. 单页位图不超过 [MaxPageBitmapBytes]（ARGB_8888，A4 比例约 1490px 宽）。
 */
private fun computeRenderWidthPx(viewportWidthPx: Float, viewportHeightPx: Float): Int {
    val fitHeight = viewportHeightPx * PageWidthOverHeight
    val areaCap = sqrt(MaxPageBitmapBytes / 4f * PageWidthOverHeight)
    return min(min(viewportWidthPx, fitHeight), areaCap).roundToInt().coerceIn(160, 4096)
}

/** 当前页 = 视口内可见面积最大的那一项。 */
private fun computeCurrentPage(state: LazyListState, pageCount: Int): Int {
    val info = state.layoutInfo
    if (info.totalItemsCount == 0) return 0
    var bestIndex = 0
    var bestOverlap = -1
    for (item in info.visibleItemsInfo) {
        val start = maxOf(item.offset, info.viewportStartOffset)
        val end = minOf(item.offset + item.size, info.viewportEndOffset)
        val overlap = end - start
        if (overlap > bestOverlap) {
            bestOverlap = overlap
            bestIndex = item.index
        }
    }
    return bestIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
}

// ---------------------------------------------------------------- 基础设施

/** 沉浸式：隐藏系统栏，下滑临时呼出；退出时恢复。 */
@Composable
internal fun ImmersiveSystemBars() {
    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(view) {
        val window = context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/**
 * spec §5.3：ModalBottomSheet / AlertDialog 各自跑在独立 Dialog window 里，活动窗口的沉浸设置
 * 不会带过去 —— 一打开系统栏就重新出现、底层页面被 insets 顶下去。这里在 dialog 窗口里再隐藏一次。
 */
@Composable
internal fun ImmersiveDialogWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        var node: Any? = view
        var dialogWindow: Window? = null
        while (node != null) {
            if (node is DialogWindowProvider) {
                dialogWindow = node.window
                break
            }
            node = (node as? android.view.View)?.parent
        }
        val window = dialogWindow
        if (window != null) {
            // spec §5.3：dialog 窗口自身也要 edge-to-edge（insets 由内容自己 navigationBarsPadding 处理），
            // 只在 dialog window 上生效，不改活动窗口。
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowCompat.getInsetsController(window, view)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private fun Throwable.describe(): String {
    val text = message
    if (text != null && text.isNotBlank()) return text
    return this::class.java.simpleName
}