package app.particlebook.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.particlebook.android.reader.ReaderLocator
import app.particlebook.android.reader.epub.EpubReaderController
import app.particlebook.android.reader.epub.EpubTocTarget
import app.particlebook.android.reader.epub.layout.EpubChapterLayout
import app.particlebook.android.reader.epub.layout.EpubLayoutConfig
import app.particlebook.android.reader.epub.layout.StaticLayoutBlockMeasurer
import app.particlebook.android.reader.epub.parser.EpubArchive
import app.particlebook.android.reader.epub.render.EpubPageIndex
import app.particlebook.android.reader.epub.render.EpubPageView
import app.particlebook.android.reader.epub.render.EpubSelectionRange
import app.particlebook.android.ui.data.Book
import app.particlebook.android.ui.data.ReadingProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

private data class ChapterRequest(
    val chapterIndex: Int,
    val page: Int?,
    val locator: ReaderLocator.Epub? = null,
)

private data class OpenedEpub(
    val controller: EpubReaderController,
    val archive: EpubArchive?,
    val chapterIndex: Int,
    val restore: ReaderLocator.Epub?,
    val restorePage: Int?,
)

private sealed interface EpubOpenState {
    data object Loading : EpubOpenState
    data object Ready : EpubOpenState
    data class Error(val message: String) : EpubOpenState
}

/**
 * 原生 EPUB 阅读壳。当前接 [EpubReaderController]（search-ux，P1-1）；
 * 渲染由 [EpubPageView] 的 Canvas 完成，Compose 只负责顶栏/底栏/目录 sheet。
 *
 * 图片资源单独打开一个 [EpubArchive] 作为 resolver，避免把 parser 内部资源表
 * 暴露到控制器 API；关闭时一起释放。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpubReaderScreen(book: Book, onBack: () -> Unit) {
    ImmersiveSystemBars()
    BackHandler(onBack = onBack)
    val density = LocalDensity.current
    val densityValue = density.density
    val scaledDensity = density.density * density.fontScale
    val context = LocalContext.current

    var controller by remember(book.filePath) { mutableStateOf<EpubReaderController?>(null) }
    var archive by remember(book.filePath) { mutableStateOf<EpubArchive?>(null) }
    var openState by remember(book.filePath) { mutableStateOf<EpubOpenState>(EpubOpenState.Loading) }
    var reloadTick by remember(book.filePath) { mutableIntStateOf(0) }
    var chapterIndex by remember(book.filePath) { mutableIntStateOf(0) }
    var chapterLayout by remember(book.filePath) { mutableStateOf<EpubChapterLayout?>(null) }
    var pageIndex by remember(book.filePath) { mutableIntStateOf(0) }
    var tocOpen by remember { mutableStateOf(false) }
    var selectedRange by remember { mutableStateOf<EpubSelectionRange?>(null) }
    var chapterRequest by remember(book.filePath) { mutableStateOf<ChapterRequest?>(null) }
    var requestVersion by remember(book.filePath) { mutableIntStateOf(0) }
    // 恢复定位完成前不允许写进度：否则 locator 解析失败时会把已保存的 page2 立刻覆盖成 0。
    var progressReady by remember(book.filePath) { mutableStateOf(false) }

    // 只在阅读屏真正离开组合时释放；不能把 controller/archive 作为 key——
    // 打开成功会先赋值 controller 再赋值 archive，key 变化会在中途触发 onDispose
    // 把刚打开的 controller 关掉（真机表现为 "EpubReaderController is closed"）。
    DisposableEffect(Unit) {
        onDispose {
            archive?.close()
            controller?.close()
        }
    }

    LaunchedEffect(book.filePath, reloadTick) {
        openState = EpubOpenState.Loading
        chapterLayout = null
        pageIndex = 0
        chapterIndex = 0
        chapterRequest = null
        requestVersion = 0
        progressReady = false
        selectedRange = null
        val oldController = controller
        val oldArchive = archive
        controller = null
        archive = null
        oldArchive?.close()
        oldController?.close()
        try {
            val opened = withContext(Dispatchers.IO) {
                val openedController = EpubReaderController.open(
                    file = File(book.filePath),
                    measurer = StaticLayoutBlockMeasurer(densityValue, scaledDensity),
                    bookId = book.id,
                )
                val openedArchive = runCatching { EpubArchive.open(File(book.filePath)) }.getOrNull()
                val saved = ReadingProgress.locator(context, book.id) as? ReaderLocator.Epub
                val savedPage = ReadingProgress.savedPage(context, book.id)
                var targetChapter = 0
                var restore: ReaderLocator.Epub? = null
                if (saved != null) {
                    val index = openedController.chapterIndexForHref(saved.chapterHref)
                    if (index != null) {
                        targetChapter = index
                        restore = saved
                    }
                }
                OpenedEpub(
                    controller = openedController,
                    archive = openedArchive,
                    chapterIndex = targetChapter,
                    restore = restore,
                    restorePage = savedPage,
                )
            }
            controller = opened.controller
            archive = opened.archive
            chapterIndex = opened.chapterIndex
            chapterLayout = null
            pageIndex = 0
            if (opened.restore != null) {
                chapterRequest = ChapterRequest(
                    chapterIndex = opened.chapterIndex,
                    page = opened.restorePage ?: 0,
                    locator = opened.restore,
                )
                requestVersion += 1
            }
            openState = EpubOpenState.Ready
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            openState = EpubOpenState.Error(throwable.message ?: "EPUB 打开失败")
        }
    }

    val chapterCount = controller?.book?.spine?.size ?: 0
    val clipboard = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }
    val requestChapter: (Int, Int?, ReaderLocator.Epub?) -> Unit = { target, page, locator ->
        if (target in 0 until chapterCount) {
            chapterRequest = ChapterRequest(target, page, locator)
            requestVersion += 1
            chapterIndex = target
            chapterLayout = null
            pageIndex = 0
            selectedRange = null
        }
    }
    LaunchedEffect(controller, chapterIndex, chapterLayout, pageIndex, progressReady) {
        if (!progressReady) return@LaunchedEffect
        val active = controller ?: return@LaunchedEffect
        val layout = chapterLayout ?: return@LaunchedEffect
        if (layout.pageCount <= 0 || active.currentChapterIndex != chapterIndex) return@LaunchedEffect
        val locator = active.locatorForPage(pageIndex) ?: return@LaunchedEffect
        val pages = layout.pageCount
        withContext(Dispatchers.IO) {
            ReadingProgress.saveEpubProgress(context, book.id, locator, pageIndex, pages)
        }
    }
    val copySelection = {
        val range = selectedRange
        val c = controller
        val selection = if (range != null && c != null) {
            c.selectionRangeInBlock(
                pageIndex = pageIndex,
                blockIndex = range.blockIndex,
                startChar = range.startChar,
                endChar = range.endChar,
            )
        } else {
            null
        }
        val text = selection?.text
        if (!text.isNullOrEmpty()) {
            clipboard?.setPrimaryClip(ClipData.newPlainText("EPUB 摘录", text))
            selectedRange = null
        }
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ReaderTopBar(
                title = book.title,
                onBack = onBack,
                actions = {
                    IconButton(onClick = { tocOpen = true }) {
                        Icon(Icons.AutoMirrored.Outlined.List, contentDescription = "目录")
                    }
                },
            )
        },
        bottomBar = {
            val count = chapterLayout?.pageCount ?: 0
            if (count > 0) {
                ReaderBottomBar(
                    pageIndex = pageIndex,
                    pageCount = count,
                    overallPercent = controller?.percentAt(pageIndex) ?: 0f,
                    selectionLength = selectedRange?.length ?: 0,
                    previousEnabled = pageIndex > 0 || chapterIndex > 0,
                    nextEnabled = pageIndex < count - 1 || chapterIndex < chapterCount - 1,
                    onCopySelection = copySelection,
                    onPrevious = {
                        selectedRange = null
                        if (pageIndex > 0) {
                            val next = pageIndex - 1
                            pageIndex = controller?.setPage(next) ?: next
                        } else if (chapterIndex > 0) {
                            requestChapter(chapterIndex - 1, null, null)
                        }
                    },
                    onNext = {
                        selectedRange = null
                        if (pageIndex < count - 1) {
                            val next = pageIndex + 1
                            pageIndex = controller?.setPage(next) ?: next
                        } else if (chapterIndex < chapterCount - 1) {
                            requestChapter(chapterIndex + 1, 0, null)
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (val state = openState) {
                EpubOpenState.Loading -> CenterMessage("正在打开 EPUB…", showProgress = true)
                is EpubOpenState.Error -> EpubError(
                    message = state.message,
                    onRetry = { reloadTick += 1 },
                )
                EpubOpenState.Ready -> {
                    val c = controller
                    if (c == null) {
                        CenterMessage("正在准备阅读器…", showProgress = true)
                    } else {
                        EpubContent(
                            controller = c,
                            archive = archive,
                            chapterIndex = chapterIndex,
                            chapterCount = chapterCount,
                            chapterLayout = chapterLayout,
                            chapterRequest = chapterRequest,
                            requestVersion = requestVersion,
                            pageIndex = pageIndex,
                            selectedRange = selectedRange,
                            onLayout = { layout, page ->
                                chapterLayout = layout
                                pageIndex = page
                                selectedRange = null
                                progressReady = true
                            },
                            onPageChanged = { page ->
                                selectedRange = null
                                pageIndex = page
                            },
                            onRequestApplied = { applied ->
                                if (chapterRequest == applied) chapterRequest = null
                            },
                            onRequestChapter = requestChapter,
                            onSelectionChanged = { selectedRange = it },
                        )
                    }
                }
            }
        }
    }

    if (tocOpen) {
        val targets = controller?.tocTargets().orEmpty()
        val rows = remember(targets) { flattenToc(targets) }
        ModalBottomSheet(
            onDismissRequest = { tocOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            contentWindowInsets = { androidx.compose.foundation.layout.WindowInsets(0) },
        ) {
            ImmersiveDialogWindow()
            Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                Text(
                    text = "目录",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                )
                if (rows.isEmpty()) {
                    Text(
                        text = "本书没有可用目录",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Spacing.lg),
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(rows) { row ->
                            TocRowView(
                                row = row,
                                onClick = {
                                    val target = row.chapterIndex
                                    if (target != null) {
                                        tocOpen = false
                                        val href = if (row.href.startsWith("#")) {
                                            controller?.book?.spine?.getOrNull(target)?.href
                                                ?: row.href
                                        } else {
                                            row.href
                                        }
                                        requestChapter(
                                            target,
                                            0,
                                            href.takeIf { it.isNotBlank() }
                                                ?.let { ReaderLocator.Epub(it, 0, 0) },
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.lg))
            }
        }
    }
}

@Composable
private fun EpubContent(
    controller: EpubReaderController,
    archive: EpubArchive?,
    chapterIndex: Int,
    chapterCount: Int,
    chapterLayout: EpubChapterLayout?,
    chapterRequest: ChapterRequest?,
    requestVersion: Int,
    pageIndex: Int,
    selectedRange: EpubSelectionRange?,
    onLayout: (EpubChapterLayout, Int) -> Unit,
    onPageChanged: (Int) -> Unit,
    onRequestApplied: (ChapterRequest) -> Unit,
    onRequestChapter: (Int, Int?, ReaderLocator.Epub?) -> Unit,
    onSelectionChanged: (EpubSelectionRange?) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth
        val heightPx = constraints.maxHeight
        val horizontalPadding = with(density) { Spacing.md.toPx() }.roundToInt()
        val verticalPadding = with(density) { Spacing.lg.toPx() }.roundToInt()
        val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
        val config = remember(widthPx, heightPx, horizontalPadding, verticalPadding, textColor) {
            EpubLayoutConfig(
                viewportWidthPx = widthPx,
                viewportHeightPx = heightPx,
                horizontalPaddingPx = horizontalPadding,
                verticalPaddingPx = verticalPadding,
                defaultTextColor = textColor,
            )
        }
        var retryTick by remember { mutableIntStateOf(0) }
        var layoutLoading by remember { mutableStateOf(true) }
        var layoutError by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(controller, config, chapterIndex, requestVersion, retryTick) {
            if (widthPx <= 0 || heightPx <= 0) return@LaunchedEffect
            layoutLoading = true
            layoutError = null
            try {
                val request = chapterRequest?.takeIf { it.chapterIndex == chapterIndex }
                val layout = withContext(Dispatchers.IO) {
                    if (controller.currentChapterIndex != chapterIndex ||
                        controller.currentLayoutOrNull == null
                    ) {
                        controller.openChapter(chapterIndex)
                    }
                    controller.layoutChapter(config)
                }
                val restoredPage = request?.locator?.let { locator ->
                    val exact = controller.pageAt(locator)
                    val fallback = initialPageFor(locator, layout, request.page ?: 0)
                    if (exact == null) {
                        Log.i(
                            "PBEPUB",
                            "restore-miss href=" + locator.chapterHref +
                                " block=" + locator.blockIndex +
                                " char=" + locator.charOffset +
                                " chapter=" + controller.currentChapterIndex +
                                " layoutHref=" + layout.chapterHref +
                                " pages=" + layout.pageCount +
                                " fallback=" + fallback,
                        )
                    }
                    exact ?: fallback
                }
                val targetPage = when {
                    restoredPage != null -> restoredPage
                    request == null -> controller.currentPageIndex
                    request.page == null -> (layout.pageCount - 1).coerceAtLeast(0)
                    else -> request.page
                }
                val page = withContext(Dispatchers.IO) { controller.setPage(targetPage) }
                onLayout(layout, page)
                if (request != null) onRequestApplied(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                layoutError = throwable.message ?: "本章排版失败"
            } finally {
                layoutLoading = false
            }
        }

        val layout = chapterLayout
        if (layout != null) {
            AndroidView(
                factory = { ctx -> EpubPageView(ctx) },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    view.setChapter(layout, pageIndex, archive)
                    view.setSelection(selectedRange)
                    view.onPageRequested = { target ->
                        val count = layout.pageCount
                        when {
                            target < 0 && chapterIndex > 0 ->
                                onRequestChapter(chapterIndex - 1, null, null)
                            target >= count && chapterIndex < chapterCount - 1 ->
                                onRequestChapter(chapterIndex + 1, 0, null)
                            else -> {
                                val next = target.coerceIn(0, max(0, count - 1))
                                onPageChanged(controller.setPage(next))
                            }
                        }
                    }
                    view.onTap = { _, _ -> }
                    view.onSelectionChanged = onSelectionChanged
                },
            )
        } else if (layoutLoading) {
            CenterMessage("正在排版本章…", showProgress = true)
        } else if (layoutError != null) {
            EpubError(message = layoutError ?: "本章排版失败", onRetry = { retryTick += 1 })
        } else {
            CenterMessage("本章没有可显示内容")
        }

        if (layoutLoading && layout != null) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = Spacing.sm),
                strokeWidth = 2.dp,
            )
        }
    }
}

@Composable
private fun ReaderBottomBar(
    pageIndex: Int,
    pageCount: Int,
    overallPercent: Float,
    selectionLength: Int,
    previousEnabled: Boolean,
    nextEnabled: Boolean,
    onCopySelection: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    ReaderBottomPill {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
        ) {
            LinearProgressIndicator(
                progress = { overallPercent.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                IconButton(onClick = onPrevious, enabled = previousEnabled) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上一页")
                }
                Text(
                    text = "全书 " + (overallPercent * 100f).roundToInt() + "% · " +
                        (pageIndex + 1) + " / " + pageCount +
                        if (selectionLength > 0) " · 已选 " + selectionLength + " 字" else "",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onNext, enabled = nextEnabled) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "下一页")
                }
                if (selectionLength > 0) {
                    TextButton(onClick = onCopySelection) { Text("复制") }
                }
            }
        }
    }
}

@Composable
private fun CenterMessage(text: String, showProgress: Boolean = false) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (showProgress) {
                CircularProgressIndicator()
                Spacer(Modifier.height(Spacing.md))
            }
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EpubError(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.padding(Spacing.lg),
        ) {
            Text("EPUB 打开失败", style = MaterialTheme.typography.titleMedium)
            Text(message, color = MaterialTheme.colorScheme.error)
            Button(onClick = onRetry) { Text("重试") }
        }
    }
}

private data class TocRow(
    val title: String,
    val href: String,
    val chapterIndex: Int?,
    val depth: Int,
)

private fun flattenToc(entries: List<EpubTocTarget>, depth: Int = 0): List<TocRow> {
    val out = ArrayList<TocRow>()
    fun visit(list: List<EpubTocTarget>, level: Int) {
        for (entry in list) {
            out += TocRow(entry.title, entry.href, entry.chapterIndex, level)
            visit(entry.children, level + 1)
        }
    }
    visit(entries, depth)
    return out
}

@Composable
private fun TocRowView(row: TocRow, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = row.chapterIndex != null, onClick = onClick)
            .padding(
                start = Spacing.lg + (Spacing.md * row.depth.toFloat()),
                end = Spacing.lg,
                top = Spacing.sm,
                bottom = Spacing.sm,
            ),
    ) {
        Text(
            text = row.title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (row.chapterIndex != null) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * 恢复页的纯函数：同章 locator 优先映射到块/行所在页；跨章或块丢失时回退页码并 clamp。
 * 不依赖 Android，单测直接覆盖冷启恢复逻辑。
 */
internal fun initialPageFor(
    locator: ReaderLocator.Epub?,
    layout: EpubChapterLayout,
    fallbackPage: Int,
): Int {
    if (locator == null) return EpubPageIndex.clampPage(fallbackPage, layout.pageCount)
    return EpubPageIndex.pageForLocator(layout, locator)
        ?: EpubPageIndex.pageForBlockLine(layout, locator.blockIndex)
        ?: EpubPageIndex.clampPage(fallbackPage, layout.pageCount)
}