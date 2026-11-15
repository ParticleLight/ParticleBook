package app.particlebook.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.rememberCoroutineScope
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
import app.particlebook.android.reader.AnnotationStore
import app.particlebook.android.reader.ReaderAnnotation
import app.particlebook.android.reader.adapter.EpubReaderAdapter
import app.particlebook.android.reader.ReaderAnnotationType
import app.particlebook.android.reader.ReaderLocator
import app.particlebook.android.reader.epub.EpubReaderController
import app.particlebook.android.reader.epub.EpubSelection
import app.particlebook.android.reader.epub.EpubTocTarget
import app.particlebook.android.reader.epub.layout.EpubChapterLayout
import app.particlebook.android.reader.epub.layout.EpubLayoutConfig
import app.particlebook.android.reader.epub.layout.StaticLayoutBlockMeasurer
import app.particlebook.android.reader.epub.parser.EpubArchive
import app.particlebook.android.reader.epub.render.EpubHighlight
import app.particlebook.android.reader.epub.render.EpubPageIndex
import app.particlebook.android.reader.epub.render.EpubPageView
import app.particlebook.android.reader.epub.render.EpubSelectionRange
import app.particlebook.android.ui.data.Book
import app.particlebook.android.ui.data.ReadingProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

private data class ChapterRequest(
    val chapterIndex: Int,
    val page: Int?,
    val locator: ReaderLocator.Epub? = null,
)

/** 新建笔记时捕获的选区；对话框打开期间选区状态可能变化，必须先快照。 */
private data class EpubNoteDraft(
    val existing: ReaderAnnotation?,
    val selection: EpubSelection?,
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
fun EpubReaderScreen(
    book: Book,
    onBack: () -> Unit,
    annotationStore: AnnotationStore? = null,
) {
    ImmersiveSystemBars()
    BackHandler(onBack = onBack)
    val density = LocalDensity.current
    val densityValue = density.density
    val scaledDensity = density.density * density.fontScale
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val notify: (String) -> Unit = { message ->
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    var controller by remember(book.filePath) { mutableStateOf<EpubReaderController?>(null) }
    var archive by remember(book.filePath) { mutableStateOf<EpubArchive?>(null) }
    var openState by remember(book.filePath) { mutableStateOf<EpubOpenState>(EpubOpenState.Loading) }
    var reloadTick by remember(book.filePath) { mutableIntStateOf(0) }
    var chapterIndex by remember(book.filePath) { mutableIntStateOf(0) }
    var chapterLayout by remember(book.filePath) { mutableStateOf<EpubChapterLayout?>(null) }
    // PB-022：ReaderAdapter 需要 layoutConfig 才能跨章 goTo/getTextModel；
    // 配置由 EpubContent 实测视口后回传，适配器只在拿到配置后创建。
    var layoutConfig by remember(book.filePath) { mutableStateOf<EpubLayoutConfig?>(null) }
    var pageIndex by remember(book.filePath) { mutableIntStateOf(0) }
    var tocOpen by remember { mutableStateOf(false) }
    var selectedRange by remember { mutableStateOf<EpubSelectionRange?>(null) }
    var chapterRequest by remember(book.filePath) { mutableStateOf<ChapterRequest?>(null) }
    var requestVersion by remember(book.filePath) { mutableIntStateOf(0) }
    // 恢复定位完成前不允许写进度：否则 locator 解析失败时会把已保存的 page2 立刻覆盖成 0。
    var progressReady by remember(book.filePath) { mutableStateOf(false) }
    var annotations by remember(book.filePath) { mutableStateOf<List<ReaderAnnotation>>(emptyList()) }
    var annotationVersion by remember(book.filePath) { mutableIntStateOf(0) }
    var annotationsLoading by remember(book.filePath) { mutableStateOf(false) }
    var annotationsOpen by remember { mutableStateOf(false) }
    var noteDraft by remember(book.filePath) { mutableStateOf<EpubNoteDraft?>(null) }
    var pendingDelete by remember(book.filePath) { mutableStateOf<ReaderAnnotation?>(null) }
    val adapter = remember(controller, layoutConfig, annotationStore) {
        val active = controller
        val config = layoutConfig
        if (active != null && config != null) {
            EpubReaderAdapter(active, config, annotationStore)
        } else {
            null
        }
    }
    DisposableEffect(adapter) {
        onDispose { adapter?.close() }
    }

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
        layoutConfig = null
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
    LaunchedEffect(controller, adapter, chapterIndex, chapterLayout, pageIndex, progressReady) {
        if (!progressReady) return@LaunchedEffect
        val active = controller ?: return@LaunchedEffect
        val layout = chapterLayout ?: return@LaunchedEffect
        if (layout.pageCount <= 0 || active.currentChapterIndex != chapterIndex) return@LaunchedEffect
        // PB-022：进度位置统一经 ReaderAdapter 读取；adapter 未就绪时退回 controller。
        val locator = adapter?.getLocator() ?: active.locatorForPage(pageIndex) ?: return@LaunchedEffect
        val pages = layout.pageCount
        withContext(Dispatchers.IO) {
            ReadingProgress.saveEpubProgress(context, book.id, locator, pageIndex, pages)
        }
    }
    LaunchedEffect(annotationStore, book.id, annotationVersion) {
        val store = annotationStore
        if (store == null) {
            annotations = emptyList()
            annotationsLoading = false
            return@LaunchedEffect
        }
        // 不先清空：保存后 version++ 重读时保留旧高亮，避免闪一下再出现。
        annotationsLoading = true
        val result = withContext(Dispatchers.IO) { runCatching { store.list(book.id) } }
        annotationsLoading = false
        result.onSuccess { annotations = it }
            .onFailure { notify("读取标注失败：" + (it.message ?: "未知错误")) }
    }

    val captureSelection: () -> EpubSelection? = {
        val range = selectedRange
        val active = controller
        if (range == null || active == null) {
            null
        } else {
            active.selectionRangeInBlock(
                pageIndex = pageIndex,
                blockIndex = range.blockIndex,
                startChar = range.startChar,
                endChar = range.endChar,
            )
        }
    }
    val copySelection = {
        val text = captureSelection()?.text
        if (!text.isNullOrEmpty()) {
            clipboard?.setPrimaryClip(ClipData.newPlainText("EPUB 摘录", text))
            selectedRange = null
        }
    }
    val saveAnnotation: (ReaderAnnotationType, String?, String, EpubSelection) -> Unit =
        { type, color, note, selection ->
            val store = annotationStore
            if (store == null) {
                notify("标注存储未就绪")
            } else {
                val annotation = annotationFromSelection(
                    bookId = book.id,
                    type = type,
                    selection = selection,
                    color = color,
                    note = note,
                    now = System.currentTimeMillis(),
                )
                if (annotation == null) {
                    notify("选区已失效，请重新选择")
                } else {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                adapter?.applyAnnotation(annotation) ?: store.add(annotation)
                            }
                        }
                        result.onSuccess {
                            annotationVersion += 1
                            selectedRange = null
                            noteDraft = null
                            notify(
                                if (type == ReaderAnnotationType.NOTE) "笔记已保存" else "高亮已保存",
                            )
                        }.onFailure {
                            notify("保存标注失败：" + (it.message ?: "未知错误"))
                        }
                    }
                }
            }
        }
    val highlightSelection: (String) -> Unit = { colorKey ->
        val selection = captureSelection()
        if (selection == null) {
            notify("请先选中正文")
        } else {
            saveAnnotation(ReaderAnnotationType.HIGHLIGHT, colorKey, "", selection)
        }
    }
    val openNoteForSelection = {
        val selection = captureSelection()
        if (annotationStore == null) {
            notify("标注存储未就绪")
        } else if (selection == null) {
            notify("请先选中正文")
        } else {
            noteDraft = EpubNoteDraft(existing = null, selection = selection)
        }
    }
    val openNoteEditor: (ReaderAnnotation) -> Unit = { annotation ->
        annotationsOpen = false
        noteDraft = EpubNoteDraft(existing = annotation, selection = null)
    }
    val saveNote: (String) -> Unit = { text ->
        val draft = noteDraft
        val store = annotationStore
        if (draft == null) {
            Unit
        } else if (store == null) {
            notify("标注存储未就绪")
        } else {
            val existing = draft.existing
            if (existing == null) {
                val selection = draft.selection
                if (selection == null) {
                    notify("选区已失效，请重新选择")
                } else {
                    saveAnnotation(ReaderAnnotationType.NOTE, EpubAnnotationPalette.note.key, text, selection)
                }
            } else {
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            store.update(
                                annotationWithNote(existing, text, System.currentTimeMillis()),
                            )
                        }
                    }
                    result.onSuccess { updated ->
                        if (updated == null) {
                            notify("标注不存在或已被删除")
                        } else {
                            annotationVersion += 1
                            noteDraft = null
                            notify("笔记已更新")
                        }
                    }.onFailure {
                        notify("更新笔记失败：" + (it.message ?: "未知错误"))
                    }
                }
            }
        }
    }
    val deleteAnnotation: (ReaderAnnotation) -> Unit = { annotation ->
        val store = annotationStore
        if (store == null) {
            notify("标注存储未就绪")
        } else {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        adapter?.removeAnnotation(annotation.id) ?: store.remove(annotation.id)
                    }
                }
                result.onSuccess { removed ->
                    pendingDelete = null
                    if (removed) {
                        annotationVersion += 1
                        notify("已删除标注")
                    } else {
                        notify("标注不存在")
                    }
                }.onFailure {
                    notify("删除标注失败：" + (it.message ?: "未知错误"))
                }
            }
        }
    }
    val jumpToAnnotation: (ReaderAnnotation) -> Unit = { annotation ->
        val locator = annotation.locator as? ReaderLocator.Epub
        val damaged = annotation.locatorError != null
        val active = controller
        if (damaged) {
            // 侧栏是 ModalBottomSheet，Snackbar 会被它盖住；先关侧栏再提示。
            annotationsOpen = false
            notify("位置不可用，无法跳转")
        } else if (locator == null || active == null) {
            annotationsOpen = false
            notify("无法定位该标注")
        } else {
            val target = active.chapterIndexForHref(locator.chapterHref)
            if (target == null) {
                notify("标注所在章节已变化")
            } else {
                annotationsOpen = false
                requestChapter(target, 0, locator)
            }
        }
    }
    val openAnnotationList: () -> Unit = {
        if (annotationStore == null) {
            notify("标注存储未就绪")
        } else {
            annotationsOpen = true
        }
    }
    // A021-2：书签不是选区标注，直接记当前页起点 locator；侧栏与高亮/笔记共用同一张表。
    val addBookmark: () -> Unit = {
        val store = annotationStore
        val active = controller
        if (store == null) {
            notify("标注存储未就绪")
        } else if (active == null) {
            notify("阅读器尚未就绪")
        } else {
            val locator = runCatching { adapter?.getLocator() }.getOrNull()
                ?: active.locatorForPage(pageIndex)
            if (locator == null) {
                notify("当前位置不可用")
            } else {
                val quote = runCatching { active.pageText(pageIndex) }.getOrDefault("")
                    .take(80)
                    .trim()
                val annotation = ReaderAnnotation(
                    bookId = book.id,
                    type = ReaderAnnotationType.BOOKMARK,
                    locator = locator,
                    quote = quote,
                    createdAt = System.currentTimeMillis(),
                )
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            adapter?.applyAnnotation(annotation) ?: store.add(annotation)
                        }
                    }
                    result.onSuccess {
                        annotationVersion += 1
                        notify("书签已保存")
                    }.onFailure {
                        notify("保存书签失败：" + (it.message ?: "未知错误"))
                    }
                }
            }
        }
    }
    val pageHighlights = remember(annotations, chapterLayout, pageIndex) {
        chapterLayout?.let { annotationsOnPage(annotations, it, pageIndex) }.orEmpty()
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            ReaderTopBar(
                title = book.title,
                onBack = onBack,
                actions = {
                    IconButton(onClick = { tocOpen = true }) {
                        Icon(Icons.AutoMirrored.Outlined.List, contentDescription = "目录")
                    }
                    IconButton(onClick = addBookmark) {
                        Icon(Icons.Filled.BookmarkBorder, contentDescription = "添加书签")
                    }
                    TextButton(onClick = openAnnotationList) { Text("标注") }
                },
            )
        },
        bottomBar = {
            // 固定占位：排版首帧 chapterLayout 还是 null，如果这时底栏不占高度，
            // 正文会先按“无底栏”排一次，等底栏出现后再缩高，页数从 13 变 12，
            // 保存的页号/字符位置就对不上了。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp + Spacing.md),
            ) {
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
                        onHighlight = highlightSelection,
                        onOpenAnnotations = openAnnotationList,
                        onNote = openNoteForSelection,
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
                            highlights = pageHighlights,
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
                            onLayoutConfig = { layoutConfig = it },
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

    if (annotationsOpen) {
        EpubAnnotationSheet(
            annotations = annotations,
            loading = annotationsLoading,
            onDismiss = { annotationsOpen = false },
            onJump = jumpToAnnotation,
            onEditNote = openNoteEditor,
            onDelete = { pendingDelete = it },
        )
    }
    noteDraft?.let { draft ->
        EpubNoteEditorDialog(
            title = if (draft.existing == null) "新建笔记" else "编辑笔记",
            initialText = draft.existing?.note.orEmpty(),
            onDismiss = { noteDraft = null },
            onConfirm = saveNote,
        )
    }
    pendingDelete?.let { annotation ->
        EpubDeleteAnnotationDialog(
            annotation = annotation,
            onDismiss = { pendingDelete = null },
            onConfirm = { deleteAnnotation(annotation) },
        )
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
    highlights: List<EpubHighlight>,
    onLayout: (EpubChapterLayout, Int) -> Unit,
    onPageChanged: (Int) -> Unit,
    onRequestApplied: (ChapterRequest) -> Unit,
    onRequestChapter: (Int, Int?, ReaderLocator.Epub?) -> Unit,
    onLayoutConfig: (EpubLayoutConfig) -> Unit,
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
        LaunchedEffect(config) { onLayoutConfig(config) }
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
                    controller.pageAt(locator)
                        ?: initialPageFor(locator, layout, request.page ?: 0)
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
                    view.setHighlights(highlights)
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
    onHighlight: (String) -> Unit,
    onNote: () -> Unit,
    onOpenAnnotations: () -> Unit,
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
            if (selectionLength > 0) {
                // 选区工具条替换翻页行，但底栏总高不变，避免正文重排导致页数变化。
                EpubSelectionToolbar(
                    selectedLength = selectionLength,
                    onHighlight = onHighlight,
                    onNote = onNote,
                    onCopy = onCopySelection,
                )
            } else {
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
                            (pageIndex + 1) + " / " + pageCount,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onNext, enabled = nextEnabled) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "下一页")
                    }
                    TextButton(onClick = onOpenAnnotations) { Text("标注") }
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