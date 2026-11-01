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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** 日志 tag：安装后由 Lead 抓 logcat 用。 */
private const val TAG = "PBReader"

/** chrome 显示后无操作自动隐藏的时间。 */
private const val CHROME_AUTO_HIDE_MS = 4000L

/** 页与页之间的间距。 */
private val PageGap = 8.dp

/** A4 高宽比，只用于未渲染页的占位块高度，渲染完成后按真实尺寸。 */
private const val PlaceholderAspect = 1.414f

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
        Log.d(TAG, "open start path=" + book.filePath + " exists=" + file.exists() + " size=" + file.length())
        state = try {
            val opened = PdfSource.open(file)
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
    val startPage = remember(book.id, pageCount) {
        progressPrefs.getInt(progressKey, 0).coerceIn(0, pageCount - 1)
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startPage)
    val scope = rememberCoroutineScope()
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
    LaunchedEffect(currentPage) {
        if (listState.layoutInfo.totalItemsCount > 0) {
            progressPrefs.edit().putInt(progressKey, currentPage).apply()
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
        val pageWidthPx = with(density) { maxWidth.toPx().roundToInt() }.coerceAtLeast(1)
        val chromeShiftPx = with(density) { 8.dp.roundToPx() }
        val chromeMotion = tween<androidx.compose.ui.unit.IntOffset>(durationMillis = 150)
        val chromeFade = tween<Float>(durationMillis = 150)

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(pageCount) {
                    detectTapGestures(onTap = { toggleChrome() })
                },
            verticalArrangement = Arrangement.spacedBy(PageGap),
        ) {
            items(count = pageCount, key = { index -> index }) { index ->
                PdfPageItem(
                    source = source,
                    pageIndex = index,
                    widthPx = pageWidthPx,
                )
            }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(chromeFade) + slideInVertically(chromeMotion) { -chromeShiftPx },
            exit = fadeOut(chromeFade) + slideOutVertically(chromeMotion) { -chromeShiftPx },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                tonalElevation = 3.dp,
            ) {
                TopAppBar(
                    title = {
                        Text(
                            text = book.title,
                            style = MaterialTheme.typography.titleMedium,
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
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(chromeFade) + slideInVertically(chromeMotion) { chromeShiftPx },
            exit = fadeOut(chromeFade) + slideOutVertically(chromeMotion) { chromeShiftPx },
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
        ) {
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
            title = { Text("书籍详情") },
            text = {
                Column {
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

@Composable
private fun PdfBottomBar(
    pageCount: Int,
    displayPage: Int,
    sliderValue: Float,
    onSliderChange: (Float) -> Unit,
    onSliderFinished: () -> Unit,
    onOpenToc: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(Spacing.readerBottomBar)
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
                modifier = Modifier.widthIn(min = 72.dp),
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
    class Ready(val bitmap: Bitmap) : PageRender
    class Failed(val message: String) : PageRender
}

/** 单页：渲染成功显示位图，未完成显示占位块（不插 spinner），失败给内联错误 + 重试。 */
@Composable
private fun PdfPageItem(source: PdfSource, pageIndex: Int, widthPx: Int) {
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
            Log.d(
                TAG,
                "render ok page=" + (pageIndex + 1) + " size=" + bitmap.width + "x" + bitmap.height +
                    " ms=" + (SystemClock.elapsedRealtime() - began),
            )
            PageRender.Ready(bitmap)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Log.e(TAG, "render failed page=" + (pageIndex + 1) + " widthPx=" + widthPx, throwable)
            PageRender.Failed(throwable.describe())
        }
    }

    when (val current = render) {
        is PageRender.Loading -> PagePlaceholder(pageIndex = pageIndex)
        is PageRender.Ready -> Image(
            bitmap = current.bitmap.asImageBitmap(),
            contentDescription = "第 " + (pageIndex + 1) + " 页",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth(),
        )
        is PageRender.Failed -> PageRenderError(
            pageIndex = pageIndex,
            message = current.message,
            onRetry = { retry++ },
        )
    }
}

@Composable
private fun PagePlaceholder(pageIndex: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
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
