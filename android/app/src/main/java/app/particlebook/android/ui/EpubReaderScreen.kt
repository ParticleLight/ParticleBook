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
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import app.particlebook.android.reader.search.EpubSearchIndexing
import app.particlebook.android.reader.epub.layout.StaticLayoutBlockMeasurer
import app.particlebook.android.reader.epub.parser.EpubArchive
import app.particlebook.android.reader.epub.perf.EpubPerfEvent
import app.particlebook.android.reader.epub.perf.EpubPerfTracker
import app.particlebook.android.reader.epub.render.EpubHighlight
import app.particlebook.android.reader.epub.render.EpubPageIndex
import app.particlebook.android.reader.epub.render.EpubPageView
import app.particlebook.android.reader.epub.render.EpubSelectionRange
import app.particlebook.android.reader.tts.AndroidTtsSpeaker
import app.particlebook.android.reader.tts.TtsPlayback
import app.particlebook.android.reader.tts.TtsPlaybackState
import app.particlebook.android.reader.tts.TtsSentence
import app.particlebook.android.reader.tts.TtsText
import app.particlebook.android.ui.data.Book
import app.particlebook.android.ui.data.ReadingProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** 朗读高亮叠色透明度：够看清「正在念这一句」，又不盖住文字。 */
private const val TtsHighlightAlpha = 0.24f

/**
 * 朗读高亮色（EPUB）。
 *
 * EPUB 页纸本身就是主题画布（正文色取 onSurface），所以这里直接取强调色 primary：
 * 浅色方案是深蓝叠浅纸、深色方案是亮蓝叠暗底，两套都有可辨的色差，
 * 不会出现「深色模式下高亮等于隐形」。与 PDF 侧同属 primary 色族，观感一致。
 * 返回值要交给自绘 View（EpubHighlight 用 ARGB Int），因此在这里转成 Int。
 */
@Composable
private fun epubTtsHighlightArgb(): Int =
    MaterialTheme.colorScheme.primary.copy(alpha = TtsHighlightAlpha).toArgb()

/**
 * 当前朗读句子的锚点：句子的字符偏移只在"生成它的那一页、那一版布局"里才有意义。
 *
 * 三者缺一都会退化成"朗读继续、高亮错位"：翻页时页号变、重排后页号不变但页界变、
 * 换书/重试打开后页号常常还是 0。所以判定高亮必须同时比 page + chapter + layoutRevision。
 */
private data class TtsSentenceAnchor(
    val chapterIndex: Int,
    val pageIndex: Int,
    val layoutRevision: Int,
) {
    fun matches(chapterIndex: Int, pageIndex: Int, layoutRevision: Int): Boolean =
        this.chapterIndex == chapterIndex &&
            this.pageIndex == pageIndex &&
            this.layoutRevision == layoutRevision
}

private data class ChapterRequest(
    val chapterIndex: Int,
    val page: Int?,
    val locator: ReaderLocator.Epub? = null,
    val userInitiated: Boolean = false,
)

/** 新建笔记时捕获的选区；对话框打开期间选区状态可能变化，必须先快照。 */
private data class EpubNoteDraft(
    val existing: ReaderAnnotation?,
    val selection: EpubSelection?,
)

private data class OpenedEpub(
    val controller: EpubReaderController,
    val archive: EpubArchive?,
    val progress: EpubProgressRestore,
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
    openStartedAtNanos: Long? = null,
    /**
     * 跨书全文搜索的命中位置。只消费一次：打开期优先于上次阅读位置，重试/重排不会把用户拽回命中处。
     * 跨章命中由 ChapterRequest 带 locator 交给 EpubContent，落位后再反算页号。
     */
    initialLocator: ReaderLocator? = null,
    /**
     * 排版输入：字号（sp）与行高倍数。默认值 = 设置 schema 的默认，AppRoot 传持久化值。
     * 这两个数一变就必须重排——它们进 EpubLayoutConfig，进而决定分页与行几何。
     */
    fontSizeSp: Float = ReaderTypographySpec.DEFAULT_FONT_SIZE_SP,
    lineHeightMultiplier: Float = ReaderTypographySpec.DEFAULT_LINE_HEIGHT,
    onFontSizeChange: (Float) -> Unit = {},
    onLineHeightChange: (Float) -> Unit = {},
) {
    ImmersiveSystemBars()
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
    var settingsOpen by remember { mutableStateOf(false) }
    var selectedRange by remember { mutableStateOf<EpubSelectionRange?>(null) }
    var chapterRequest by remember(book.filePath) { mutableStateOf<ChapterRequest?>(null) }
    // 搜索命中跳转只属于当前这本书的这次打开：换书重新 remember，消费后置空。
    var pendingJump by remember(book.filePath) { mutableStateOf(initialLocator as? ReaderLocator.Epub) }
    var requestVersion by remember(book.filePath) { mutableIntStateOf(0) }
    var progressState by remember(book.filePath, book.id) { mutableStateOf(EpubProgressState()) }
    var annotations by remember(book.filePath) { mutableStateOf<List<ReaderAnnotation>>(emptyList()) }
    var annotationVersion by remember(book.filePath) { mutableIntStateOf(0) }
    var annotationsLoading by remember(book.filePath) { mutableStateOf(false) }
    var annotationsOpen by remember { mutableStateOf(false) }
    // 朗读：句子只取"当前这一页"，换页/换章时由浏览器动作重新取，避免念到屏幕外的内容。
    var ttsState by remember { mutableStateOf<TtsPlaybackState>(TtsPlaybackState.Idle) }
    var ttsSentence by remember { mutableStateOf<TtsSentence?>(null) }
    var ttsAnchor by remember { mutableStateOf<TtsSentenceAnchor?>(null) }
    var ttsRate by remember { mutableStateOf(1.0f) }
    // 布局代次：EpubReaderController.layoutRevision 在换章和"真的换掉布局"时各 +1
    // （分屏、折叠、旋转、系统字号都算）。页文本与句子偏移只在同一代次内有效，
    // 所以它同时进"停读"的 key 与高亮判定；同 config 的重复布局不会推进它，不会把朗读抖停。
    val layoutRevision = controller?.layoutRevision ?: 0
    val ttsContext = LocalContext.current
    // 失焦回调要拿到 playback，而 playback 又需要 speaker：用持有者打破这个循环。
    // SideEffect 在组合提交后赋值，真正的音频焦点变化只会发生在更晚。
    val ttsPlaybackHolder = remember { mutableStateOf<TtsPlayback?>(null) }
    val ttsSpeaker = remember(ttsContext) {
        AndroidTtsSpeaker(ttsContext, onFocusLost = { ttsPlaybackHolder.value?.pause() })
    }
    val ttsPlayback = remember(ttsSpeaker) {
        TtsPlayback(
            speaker = ttsSpeaker,
            onStateChanged = { ttsState = it },
            onSentence = { sentence ->
                ttsSentence = sentence
                // 句子清空说明朗读已经停下（念完/手动停/换页即停）：锚点一起作废，别留半截高亮。
                if (sentence == null) ttsAnchor = null
            },
        )
    }
    SideEffect { ttsPlaybackHolder.value = ttsPlayback }
    DisposableEffect(ttsPlayback) {
        onDispose { ttsPlayback.shutdown() }
    }
    var noteDraft by remember(book.filePath) { mutableStateOf<EpubNoteDraft?>(null) }
    var pendingDelete by remember(book.filePath) { mutableStateOf<ReaderAnnotation?>(null) }
    val perfTracker = remember(book.filePath, book.id) {
        EpubPerfTracker(
            bookId = book.id,
            initialOpenStartNs = openStartedAtNanos,
            log = { line -> Log.i(EpubPerfEvent.TAG, line) },
        )
    }
    // PBPERF open token 由 AppRoot/ReaderScreen 注入；置空时撤销尚未提交的首章事件。
    SideEffect { perfTracker.updateOpenStartNs(openStartedAtNanos) }
    DisposableEffect(perfTracker) {
        onDispose { perfTracker.close() }
    }
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
        progressState = EpubProgressState()
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
                val savedSnapshot = ReadingProgress.snapshot(book.id)
                val progress = EpubProgressRestore.from(
                    rawLocator = savedSnapshot?.locatorJson,
                    savedLocator = savedSnapshot?.locator(),
                    savedPage = savedSnapshot?.savedPage(),
                    chapterIndexForHref = openedController::chapterIndexForHref,
                )
                OpenedEpub(
                    controller = openedController,
                    archive = openedArchive,
                    progress = progress,
                )
            }
            controller = opened.controller
            archive = opened.archive
            // 搜索命中优先于上次阅读位置；命中章节不在当前 spine（书换过版本）时静默退回原进度。
            val jump = pendingJump?.takeIf { opened.controller.chapterIndexForHref(it.chapterHref) != null }
            pendingJump = null
            chapterIndex = jump?.let { opened.controller.chapterIndexForHref(it.chapterHref) }
                ?: opened.progress.chapterIndex
            chapterLayout = null
            pageIndex = 0
            progressState = opened.progress.state
            if (jump != null) {
                chapterRequest = ChapterRequest(chapterIndex = chapterIndex, page = 0, locator = jump)
                requestVersion += 1
            } else if (opened.progress.locator != null || opened.progress.page != null) {
                chapterRequest = ChapterRequest(
                    chapterIndex = opened.progress.chapterIndex,
                    page = opened.progress.page ?: 0,
                    locator = opened.progress.locator,
                )
                requestVersion += 1
            }
            openState = EpubOpenState.Ready
            // 跨书全文索引：打开即后台建/更新，失败只记日志（搜索页据此说明「这本书还没索引」）。
            EpubSearchIndexing.request(context, book.id, File(book.filePath))
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
            chapterRequest = ChapterRequest(target, page, locator, userInitiated = true)
            requestVersion += 1
            chapterIndex = target
            chapterLayout = null
            pageIndex = 0
            selectedRange = null
        }
    }
    val showProgressPage: (EpubChapterLayout, Int, Boolean) -> Unit = { layout, page, userInitiated ->
        val active = controller
        if (active != null && active.currentLayoutOrNull === layout &&
            active.currentChapterIndex == chapterIndex && page in 0 until layout.pageCount
        ) {
            val visible = EpubVisibleProgress(active.locatorForPage(page), page, layout.pageCount)
            progressState = progressState.pageShown(visible, userInitiated)
        }
    }
    val changeDisplayedPage: (Int) -> Unit = { page ->
        selectedRange = null
        chapterLayout?.let { showProgressPage(it, page, true) }
        pageIndex = page
    }
    val saveDisplayedProgress: () -> Unit = {
        progressState.writable?.let { visible ->
            // 同步更新缓存并入进程级 IO 队列；父级 Back / Activity ON_STOP 随后才能完整 flush。
            ReadingProgress.saveEpubProgress(context, book.id, visible.locator, visible.page, visible.pages)
        }
    }
    LaunchedEffect(book.id, progressState) { saveDisplayedProgress() }
    // 朗读只覆盖"当前这一页 + 当前这一版布局"：翻页/换章/重排后句子坐标变了，继续念会把高亮
    // 与文本对错位，因此这些变化都即停，由用户在新页重新点「朗读」。
    // 页号 0→0 的两种情形（换书、reloadTick 重试打开）只有文档标识能发现"文字已经不是同一本"。
    LaunchedEffect(chapterIndex, pageIndex, layoutRevision, book.filePath, reloadTick) {
        if (ttsState !is TtsPlaybackState.Idle) ttsPlayback.stop()
    }
    val latestSaveDisplayedProgress by rememberUpdatedState(saveDisplayedProgress)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) latestSaveDisplayedProgress()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val leaveReader: () -> Unit = {
        saveDisplayedProgress()
        onBack()
    }
    BackHandler(onBack = leaveReader)
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
    // 朗读高亮只在"念的还是当前页、且还是当前这一版布局"时绘制：翻页/重排后立刻消失，
    // 否则会用新布局的块偏移去画旧句子的区间，肉眼就是高亮错位。
    // 高亮色走主题语义角色（深色模式必须仍是同族强调色），作为 remember 键参与失效。
    val ttsHighlightArgb = epubTtsHighlightArgb()
    val ttsHighlights = remember(
        ttsSentence, ttsAnchor, pageIndex, chapterIndex, layoutRevision, controller, ttsHighlightArgb,
    ) {
        val sentence = ttsSentence
        val reader = controller
        if (sentence == null || reader == null ||
            ttsAnchor?.matches(chapterIndex, pageIndex, layoutRevision) != true
        ) {
            emptyList()
        } else {
            reader.blockRangesInPage(pageIndex, sentence.startChar, sentence.endChar)
                .map { EpubHighlight(it.blockIndex, it.startChar, it.endChar, ttsHighlightArgb) }
        }
    }
    val displayedHighlights = remember(pageHighlights, ttsHighlights) {
        if (ttsHighlights.isEmpty()) pageHighlights else pageHighlights + ttsHighlights
    }
    val startTts = {
        val reader = controller
        if (reader != null) {
            val sentences = TtsText.sentences(reader.pageText(pageIndex))
            if (sentences.isEmpty()) {
                scope.launch { snackbarHostState.showSnackbar("本页没有可朗读的文字") }
            } else {
                ttsPlayback.setRate(ttsRate)
                // start 返回 false = 没开始（引擎未就绪）：这时不该留锚点，否则界面会"显示在播
                // 却不出声"，高亮还会挂在下一次重排后的坐标上。
                if (ttsPlayback.start(sentences)) {
                    ttsAnchor = TtsSentenceAnchor(chapterIndex, pageIndex, layoutRevision)
                } else {
                    notify("系统朗读引擎不可用")
                }
            }
        }
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            ReaderTopBar(
                title = book.title,
                onBack = leaveReader,
                actions = {
                    IconButton(onClick = { tocOpen = true }) {
                        Icon(Icons.AutoMirrored.Outlined.List, contentDescription = "目录")
                    }
                    IconButton(onClick = { settingsOpen = true }) {
                        Icon(Icons.Outlined.FormatSize, contentDescription = "阅读设置")
                    }
                    TextButton(onClick = startTts) { Text("朗读") }
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
                                perfTracker.beginUserTurn(chapterIndex, next)
                                changeDisplayedPage(controller?.setPage(next) ?: next)
                            } else if (chapterIndex > 0) {
                                perfTracker.beginUserTurn(chapterIndex - 1, null)
                                requestChapter(chapterIndex - 1, null, null)
                            }
                        },
                        onNext = {
                            selectedRange = null
                            if (pageIndex < count - 1) {
                                val next = pageIndex + 1
                                perfTracker.beginUserTurn(chapterIndex, next)
                                changeDisplayedPage(controller?.setPage(next) ?: next)
                            } else if (chapterIndex < chapterCount - 1) {
                                perfTracker.beginUserTurn(chapterIndex + 1, 0)
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
                            perfTracker = perfTracker,
                            chapterIndex = chapterIndex,
                            chapterCount = chapterCount,
                            chapterLayout = chapterLayout,
                            chapterRequest = chapterRequest,
                            requestVersion = requestVersion,
                            pageIndex = pageIndex,
                            selectedRange = selectedRange,
                            highlights = displayedHighlights,
                            onLayout = { layout, page, request ->
                                progressState = progressState.initialLayoutApplied(
                                    layout, request?.locator, request?.userInitiated == true,
                                )
                                chapterLayout = layout
                                pageIndex = page
                                selectedRange = null
                                showProgressPage(layout, page, request?.userInitiated == true)
                            },
                            onPageChanged = changeDisplayedPage,
                            onRequestApplied = { applied ->
                                if (chapterRequest == applied) chapterRequest = null
                            },
                            onRequestChapter = requestChapter,
                            onLayoutConfig = { layoutConfig = it },
                            onSelectionChanged = { selectedRange = it },
                            fontSizeSp = fontSizeSp,
                            lineHeightMultiplier = lineHeightMultiplier,
                        )
                    }
                }
            }
            // 朗读控制条浮在正文之上、底栏之上：底栏必须始终可见（翻页箭头只在底栏里），
            // 因此不能像常规 mini-player 那样顶替底栏，否则朗读时无法翻页。
            if (ttsState !is TtsPlaybackState.Idle) {
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
                    onStop = {
                        // stop() 会同步回调 onSentence(null)，锚点在那条路径上一起清掉。
                        ttsPlayback.stop()
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                )
            }
        }
    }

    if (settingsOpen) {
        ReaderSettingsSheet(
            fontSizeSp = fontSizeSp,
            lineHeightMultiplier = lineHeightMultiplier,
            onFontSizeChange = onFontSizeChange,
            onLineHeightChange = onLineHeightChange,
            onDismiss = { settingsOpen = false },
        )
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EpubContent(
    controller: EpubReaderController,
    archive: EpubArchive?,
    perfTracker: EpubPerfTracker,
    chapterIndex: Int,
    chapterCount: Int,
    chapterLayout: EpubChapterLayout?,
    chapterRequest: ChapterRequest?,
    requestVersion: Int,
    pageIndex: Int,
    selectedRange: EpubSelectionRange?,
    highlights: List<EpubHighlight>,
    onLayout: (EpubChapterLayout, Int, ChapterRequest?) -> Unit,
    onPageChanged: (Int) -> Unit,
    onRequestApplied: (ChapterRequest) -> Unit,
    onRequestChapter: (Int, Int?, ReaderLocator.Epub?) -> Unit,
    onLayoutConfig: (EpubLayoutConfig) -> Unit,
    onSelectionChanged: (EpubSelectionRange?) -> Unit,
    fontSizeSp: Float,
    lineHeightMultiplier: Float,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth
        val heightPx = constraints.maxHeight
        val horizontalPadding = with(density) { Spacing.md.toPx() }.roundToInt()
        val verticalPadding = with(density) { Spacing.lg.toPx() }.roundToInt()
        val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
        val scaledDensity = density.density * density.fontScale
        val baseTextSizeSp = normalizeFontSize(fontSizeSp)
        val baseLineHeight = normalizeLineHeight(lineHeightMultiplier)
        val config = remember(
            widthPx,
            heightPx,
            horizontalPadding,
            verticalPadding,
            textColor,
            baseTextSizeSp,
            baseLineHeight,
            // sp→px 的换算因子也是排版输入：系统字体大小变了，同一 sp 排出来的行数就不同。
            scaledDensity,
        ) {
            EpubLayoutConfig(
                viewportWidthPx = widthPx,
                viewportHeightPx = heightPx,
                horizontalPaddingPx = horizontalPadding,
                verticalPaddingPx = verticalPadding,
                baseTextSizeSp = baseTextSizeSp,
                lineHeightMultiplier = baseLineHeight,
                defaultTextColor = textColor,
            )
        }
        LaunchedEffect(config) { onLayoutConfig(config) }
        var retryTick by remember { mutableIntStateOf(0) }
        var layoutLoading by remember { mutableStateOf(true) }
        // 上一轮真正排过版的配置：只用来判断「这次 config 变化是不是字号/行高」。
        var lastAppliedConfig by remember { mutableStateOf<EpubLayoutConfig?>(null) }
        var layoutError by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(controller, config, chapterIndex, requestVersion, retryTick) {
            if (widthPx <= 0 || heightPx <= 0) return@LaunchedEffect
            layoutLoading = true
            layoutError = null
            try {
                val request = chapterRequest?.takeIf { it.chapterIndex == chapterIndex }
                // 字号/行高变了：先把「当前页」按旧布局换算成 locator，重排后再用 locator 找回位置。
                // 不这么做，重排会把读者扔回本章第一页——页号只在旧布局里有意义。
                val previous = lastAppliedConfig
                val typographyChanged = previous != null &&
                    (
                        previous.baseTextSizeSp != config.baseTextSizeSp ||
                            previous.lineHeightMultiplier != config.lineHeightMultiplier
                        )
                val typographyAnchor = if (typographyChanged) {
                    withContext(Dispatchers.IO) {
                        runCatching { controller.locatorForPage(controller.currentPageIndex) }.getOrNull()
                    }
                } else {
                    null
                }
                val layout = withContext(Dispatchers.IO) {
                    if (controller.currentChapterIndex != chapterIndex ||
                        controller.currentLayoutOrNull == null
                    ) {
                        controller.openChapter(chapterIndex)
                    }
                    controller.layoutChapter(config)
                }
                // 字号/行高重排：locator 优先于「还停在第几页」，也优先于章节请求（请求是换章用的）。
                val typographyPage = typographyAnchor?.let { anchor ->
                    withContext(Dispatchers.IO) { runCatching { controller.pageAt(anchor) }.getOrNull() }
                }
                val restoredPage = typographyPage ?: request?.locator?.let { locator ->
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
                lastAppliedConfig = config
                onLayout(layout, page, request)
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
                    view.perfTracker = perfTracker
                    view.perfChapterIndex = chapterIndex
                    view.setChapter(layout, pageIndex, archive)
                    view.setHighlights(highlights)
                    view.setSelection(selectedRange)
                    view.onPageRequested = { target ->
                        val count = layout.pageCount
                        when {
                            target < 0 && chapterIndex > 0 -> {
                                perfTracker.beginUserTurn(chapterIndex - 1, null)
                                onRequestChapter(chapterIndex - 1, null, null)
                            }
                            target >= count && chapterIndex < chapterCount - 1 -> {
                                perfTracker.beginUserTurn(chapterIndex + 1, 0)
                                onRequestChapter(chapterIndex + 1, 0, null)
                            }
                            else -> {
                                val next = target.coerceIn(0, max(0, count - 1))
                                if (next != pageIndex) {
                                    perfTracker.beginUserTurn(chapterIndex, next)
                                }
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
            LoadingIndicator(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = Spacing.sm),
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
            LinearWavyProgressIndicator(
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CenterMessage(text: String, showProgress: Boolean = false) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (showProgress) {
                LoadingIndicator()
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