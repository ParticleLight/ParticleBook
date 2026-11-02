package app.particlebook.android.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.Window
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.particlebook.android.MainActivity
import app.particlebook.android.ui.data.Book
import app.particlebook.android.ui.data.BookFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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

/**
 * 阅读器：PDF 走原生纵向连续滚动；EPUB 暂未迁移，给「兼容模式」出口。
 * 进入即切沉浸（隐藏系统栏），退出恢复。
 */
@Composable
fun ReaderScreen(book: Book, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    when (book.format) {
        BookFormat.Pdf -> PdfReaderScreen(book = book, onBack = onBack)
        BookFormat.Epub -> EpubReaderScreen(book = book, onBack = onBack)
    }
}

// ---------------------------------------------------------------- PDF 阅读器

private sealed interface PdfState {
    data object Loading : PdfState
    class Ready(val source: PdfSource) : PdfState
    class Error(val message: String) : PdfState
}

/** 持有已打开的 PdfSource，保证「一次打开、退出时关闭」。 */
private class PdfSourceHolder {
    var source: PdfSource? = null
        private set

    fun attach(open: PdfSource) {
        close()
        source = open
    }

    fun close() {
        source?.close()
        source = null
    }
}

@Composable
private fun PdfReaderScreen(book: Book, onBack: () -> Unit) {
    ImmersiveSystemBars()
    var state by remember(book.filePath) { mutableStateOf<PdfState>(PdfState.Loading) }
    var reloadTick by remember { mutableIntStateOf(0) }
    val holder = remember(book.filePath) { PdfSourceHolder() }

    LaunchedEffect(book.filePath, reloadTick) {
        holder.close()
        state = PdfState.Loading
        val file = File(book.filePath)
        val openBegin = SystemClock.elapsedRealtime()
        Log.d(TAG, "open start path=" + book.filePath + " exists=" + file.exists() + " size=" + file.length())
        state = try {
            val opened = PdfSource.open(file)
            Log.i(
                PERF_TAG,
                "PBPERF|phase=pdf_open|ms=" + (SystemClock.elapsedRealtime() - openBegin) +
                    "|extra=pages:" + opened.pageCount,
            )
            Log.d(TAG, "open ok pages=" + opened.pageCount)
            holder.attach(opened)
            PdfState.Ready(opened)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Log.e(TAG, "open failed path=" + book.filePath, throwable)
            PdfState.Error(throwable.describe())
        }
    }

    DisposableEffect(book.filePath) {
        onDispose {
            Log.d(TAG, "dispose pdf reader")
            holder.close()
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
                if (current.source.pageCount <= 0) {
                    PdfOpenError(
                        message = "这份 PDF 没有可显示的页面",
                        onRetry = { reloadTick++ },
                        onBack = onBack,
                    )
                } else {
                    PdfReadingPane(
                        book = book,
                        source = current.source,
                        onBack = onBack,
                    )
                }
            }
        }
    }
}

@Composable
private fun PdfLoading() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
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
private fun PdfReadingPane(book: Book, source: PdfSource, onBack: () -> Unit) {
    val pageCount = source.pageCount
    val context = LocalContext.current
    val progressPrefs = remember(context) {
        context.getSharedPreferences("pb_reading", Context.MODE_PRIVATE)
    }
    val progressKey = "page_" + book.id
    // 总页数也存一份：横屏书架列表要按进度显示「已读 xx%」（见 ui/shelf/ShelfListRow.kt）。
    val pagesKey = "pages_" + book.id
    val startPage = remember(book.id, pageCount) {
        progressPrefs.getInt(progressKey, 0).coerceIn(0, pageCount - 1)
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startPage)
    val scope = rememberCoroutineScope()
    // 性能体检：翻页计时用（纵向连续滚动，没有离散翻页手势）。
    var scrollStartMs by remember { mutableLongStateOf(0L) }
    var lastTurnPage by remember(book.id) { mutableIntStateOf(startPage) }
    var chromeVisible by remember { mutableStateOf(true) }
    var chromeTick by remember { mutableIntStateOf(0) }
    var tocOpen by remember { mutableStateOf(false) }
    var moreMenuOpen by remember { mutableStateOf(false) }
    var detailsOpen by remember { mutableStateOf(false) }
    var bookmark by rememberSaveable(book.id) { mutableStateOf(false) }
    var sliderDragging by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableFloatStateOf(0f) }

    val currentPage by remember(listState, pageCount) {
        derivedStateOf { computeCurrentPage(listState, pageCount) }
    }
    val displayPage = if (sliderDragging) {
        sliderValue.roundToInt().coerceIn(0, pageCount - 1)
    } else {
        currentPage.coerceIn(0, pageCount - 1)
    }

    // 翻页：从滚动开始（手势）到新页成为当前页并停顿的首个空闲帧；每翻到一页打一次。
    LaunchedEffect(listState, pageCount) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) {
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
    // 阅读进度落在 SharedPreferences：退出再进入回到上次页码（首帧布局前不写，避免把进度清成 0）。
    LaunchedEffect(currentPage, pageCount) {
        if (listState.layoutInfo.totalItemsCount > 0) {
            progressPrefs.edit()
                .putInt(progressKey, currentPage)
                .putInt(pagesKey, pageCount)
                // 「继续阅读」取最近一次打开的书（Hero 卡排序用）。
                .putLong("last_read_" + book.id, System.currentTimeMillis())
                .apply()
        }
    }
    LaunchedEffect(chromeVisible, chromeTick, tocOpen, moreMenuOpen, detailsOpen, sliderDragging) {
        if (chromeVisible && !tocOpen && !moreMenuOpen && !detailsOpen && !sliderDragging) {
            delay(CHROME_AUTO_HIDE_MS)
            chromeVisible = false
        }
    }

    val jumpToPage: (Int) -> Unit = { index ->
        scope.launch {
            listState.scrollToItem(index.coerceIn(0, pageCount - 1))
            wakeChrome()
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
        val chromeShiftPx = with(density) { 8.dp.roundToPx() }
        val chromeMotion = Motion.enterSpatial<androidx.compose.ui.unit.IntOffset>()
        val chromeFade = Motion.enterFade<Float>()
        val chromeMotionOut = Motion.exitSpatial<androidx.compose.ui.unit.IntOffset>()
        val chromeFadeOut = Motion.exitFade<Float>()

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
                    source = source,
                    pageIndex = index,
                    widthPx = pageWidthPx,
                    widthDp = pageWidthDp,
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
            // E3 取舍：不做真实 backdrop blur（成本高），用 0.88 alpha 的半透明底；
            // 关键是不能把 TopAppBar 的文字一起 blur 掉（会导致返回/书名看不见）。
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(88.dp),
                    contentAlignment = Alignment.Center,
                ) {
                TopAppBar(
                    title = {
                        Text(
                            text = book.title,
                            style = AppType.topBarTitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回书架",
                            )
                        }
                    },
                    actions = {
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
                                text = { Text("搜索（即将推出）") },
                                enabled = false,
                                onClick = {},
                            )
                            DropdownMenuItem(
                                text = { Text("阅读设置（即将推出）") },
                                enabled = false,
                                onClick = {},
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
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    ),
                )
                }
            }
        }

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
                    scope.launch { listState.scrollToItem(target) }
                    wakeChrome()
                },
                onOpenToc = {
                    tocOpen = true
                    wakeChrome()
                },
            )
        }
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
                                jumpToPage(index)
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
        shape = RoundedCornerShape(Spacing.xs),
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
) {
    val shape = CircleShape
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl)
            .navigationBarsPadding()
            .padding(bottom = Spacing.md)
            .height(64.dp)
            .floatingShadow(shape),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.12f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onOpenToc) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.List,
                    contentDescription = "目录",
                )
            }
            Text(
                text = (displayPage + 1).toString() + " / " + pageCount,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 64.dp),
            )
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
}

private sealed interface PageRender {
    data object Loading : PageRender
    class Ready(val bitmap: Bitmap, val renderBeginMs: Long) : PageRender
    class Failed(val message: String) : PageRender
}

/** 单页：渲染成功显示位图，未完成显示占位块（不插 spinner），失败给内联错误 + 重试。 */
@Composable
private fun PdfPageItem(source: PdfSource, pageIndex: Int, widthPx: Int, widthDp: Dp) {
    var retry by remember { mutableIntStateOf(0) }
    val render by produceState<PageRender>(
        initialValue = PageRender.Loading,
        source,
        pageIndex,
        widthPx,
        retry,
    ) {
        value = try {
            val began = SystemClock.elapsedRealtime()
            val bitmap = source.renderPage(pageIndex, widthPx)
            val renderMs = SystemClock.elapsedRealtime() - began
            Log.d(
                TAG,
                "render ok page=" + (pageIndex + 1) + " size=" + bitmap.width + "x" + bitmap.height +
                    " ms=" + renderMs,
            )
            Log.i(
                PERF_TAG,
                "PBPERF|phase=page_render|page:" + pageIndex + "|ms=" + renderMs +
                    "|extra=size:" + bitmap.width + "x" + bitmap.height,
            )
            PageRender.Ready(bitmap, began)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Log.e(TAG, "render failed page=" + (pageIndex + 1) + " widthPx=" + widthPx, throwable)
            PageRender.Failed(throwable.describe())
        }
    }

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        when (val current = render) {
            is PageRender.Loading -> PagePlaceholder(pageIndex = pageIndex, width = widthDp)
            is PageRender.Ready -> {
                // 上屏：该页位图第一次真正被画到屏幕上的那一帧（每页只打一次）。
                LaunchedEffect(current) {
                    withFrameNanos { }
                    Log.i(
                        PERF_TAG,
                        "PBPERF|phase=page_present|page:" + pageIndex + "|ms=" +
                            (SystemClock.elapsedRealtime() - current.renderBeginMs) +
                            "|extra=size:" + current.bitmap.width + "x" + current.bitmap.height,
                    )
                }
                Image(
                    bitmap = current.bitmap.asImageBitmap(),
                    contentDescription = "第 " + (pageIndex + 1) + " 页",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .width(widthDp)
                        .clip(RoundedCornerShape(Spacing.xxs)),
                )
            }
            is PageRender.Failed -> PageRenderError(
                pageIndex = pageIndex,
                message = current.message,
                onRetry = { retry++ },
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
        shape = RoundedCornerShape(Spacing.sm),
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

// ---------------------------------------------------------------- EPUB

@Composable
private fun EpubReaderScreen(book: Book, onBack: () -> Unit) {
    val context = LocalContext.current
    var dialogOpen by rememberSaveable(book.id) { mutableStateOf(true) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "EPUB 阅读器尚未就绪",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.xl))
            FilledTonalButton(onClick = { dialogOpen = true }) { Text("用兼容模式打开") }
            Spacer(Modifier.height(Spacing.xs))
            TextButton(onClick = onBack) { Text("返回书架") }
        }
    }

    if (dialogOpen) {
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            icon = {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.MenuBook,
                    contentDescription = null,
                )
            },
            title = { Text("EPUB 阅读器尚未就绪") },
            text = {
                Text(
                    "Compose 版目前只提供 PDF 原生阅读，EPUB 排版仍留在旧版 WebView 阅读器里。" +
                        "要现在用兼容模式打开《" + book.title + "》吗？",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    dialogOpen = false
                    context.startActivity(Intent(context, MainActivity::class.java))
                }) {
                    Text("用兼容模式打开")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    dialogOpen = false
                    onBack()
                }) {
                    Text("返回书架")
                }
            },
        )
    }
}

// ---------------------------------------------------------------- 基础设施

/** 沉浸式：隐藏系统栏，下滑临时呼出；退出时恢复。 */
@Composable
private fun ImmersiveSystemBars() {
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
private fun ImmersiveDialogWindow() {
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

/**
 * PdfRenderer 的访问壳。
 * PdfRenderer 不是线程安全的，且 PDFium 的 native 调用本身串行：这里固定一条线程，
 * 打开 / 渲染 / 关闭都排在同一条线程上执行，避免跨线程使用导致的静默失败。
 */
private class PdfSource private constructor(
    private val renderer: PdfRenderer,
    private val descriptor: ParcelFileDescriptor,
    private val executor: ExecutorService,
    private val dispatcher: CoroutineDispatcher,
    val pageCount: Int,
) : Closeable {

    @Volatile
    private var closed = false

    suspend fun renderPage(pageIndex: Int, targetWidthPx: Int): Bitmap = withContext(dispatcher) {
        check(!closed) { "PDF 已关闭" }
        val page = renderer.openPage(pageIndex)
        try {
            val pageWidth = page.width.coerceAtLeast(1)
            val pageHeight = page.height.coerceAtLeast(1)
            val width = targetWidthPx.coerceAtLeast(1)
            val scale = width.toFloat() / pageWidth.toFloat()
            val height = (pageHeight * scale).roundToInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        } finally {
            runCatching { page.close() }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching {
            executor.execute {
                runCatching { renderer.close() }
                runCatching { descriptor.close() }
                runCatching { executor.shutdown() }
            }
        }.onFailure {
            runCatching { executor.shutdownNow() }
        }
    }

    companion object {
        suspend fun open(file: File): PdfSource {
            require(file.exists()) { "文件不存在：" + file.name }
            val executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "PB-PdfRenderer").apply { isDaemon = true }
            }
            val dispatcher = executor.asCoroutineDispatcher()
            return try {
                withContext(dispatcher) {
                    val descriptor = ParcelFileDescriptor.open(
                        file,
                        ParcelFileDescriptor.MODE_READ_ONLY,
                    )
                    val renderer = try {
                        PdfRenderer(descriptor)
                    } catch (throwable: Throwable) {
                        runCatching { descriptor.close() }
                        throw throwable
                    }
                    Log.d(
                        TAG,
                        "renderer ready pages=" + renderer.pageCount +
                            " firstPage=" + describeFirstPage(renderer),
                    )
                    PdfSource(renderer, descriptor, executor, dispatcher, renderer.pageCount)
                }
            } catch (throwable: Throwable) {
                runCatching { dispatcher.close() }
                throw throwable
            }
        }

        /** 只在创建线程上跑一次，把首页尺寸写进日志（点值，1/72 英寸）。 */
        private fun describeFirstPage(renderer: PdfRenderer): String {
            if (renderer.pageCount <= 0) return "none"
            return runCatching {
                val page = renderer.openPage(0)
                try {
                    page.width.toString() + "x" + page.height
                } finally {
                    runCatching { page.close() }
                }
            }.getOrElse { throwable -> "error:" + throwable.describe() }
        }
    }
}
