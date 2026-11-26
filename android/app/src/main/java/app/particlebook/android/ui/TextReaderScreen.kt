package app.particlebook.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.particlebook.android.reader.AnnotationStore
import app.particlebook.android.reader.ReaderAnnotation
import app.particlebook.android.reader.ReaderAnnotationType
import app.particlebook.android.reader.ReaderLocator
import app.particlebook.android.reader.export.AnnotationExportPlanner
import app.particlebook.android.reader.fb2.Fb2Parser
import app.particlebook.android.reader.html.HtmlTextExtractor
import app.particlebook.android.reader.text.TextAnnotations
import app.particlebook.android.reader.text.TextDocument
import app.particlebook.android.reader.text.TextDocumentParser
import app.particlebook.android.reader.text.TextOutline
import app.particlebook.android.reader.text.TextImage
import app.particlebook.android.reader.text.TextImageCatalog
import app.particlebook.android.reader.text.TextSearchEngine
import app.particlebook.android.reader.text.TextSearchHit
import app.particlebook.android.reader.text.TextFileDecoder
import app.particlebook.android.reader.text.TextParagraph
import app.particlebook.android.reader.text.TextProgressJump
import app.particlebook.android.reader.text.TextProgressTarget
import app.particlebook.android.reader.text.TextSpeech
import app.particlebook.android.reader.tts.AndroidTtsSpeaker
import app.particlebook.android.reader.tts.TtsPlayback
import app.particlebook.android.reader.tts.TtsPlaybackState
import app.particlebook.android.reader.tts.TtsSentence
import app.particlebook.android.ui.data.Book
import app.particlebook.android.ui.data.BookFormat
import app.particlebook.android.ui.data.ReadingProgress
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/**
 * 纯文本 / Markdown 阅读器（滚动式）。
 *
 * 文本没有固定版式，硬做分页只会把排版交给一个假想的「页」，所以这里按段落流式渲染：
 * LazyColumn 一段一项，字号/行高直接吃全局排版设置，和 EPUB 用同一套 token。
 *
 * 进度口径：段落下标当 page、段落总数当 totalPages 写进 progress 表（见
 * [ReadingProgress.saveTextProgress]）。书架百分比、Hero「继续阅读」、阅读统计这些既有链路
 * 一行都不用改就能认这本书；恢复时 savedPage 直接就是段落下标。
 *
 * Markdown 目前只做「标题放大加粗」，其余标记按源码显示——完整富文本需要独立的解析与排版层，
 * 不在这一批范围内，宁可显示原文也不假装渲染。
 *
 * 搜索：整本已在内存里，[TextSearchEngine] 同步扫一遍喂给弹层；点结果只滚到段首，段内命中
 * 位置靠行内底色标出（精确滚到字符要等 TextLayoutResult 排版完成，收益不值这个复杂度）。
 */
@Composable
internal fun TextReaderScreen(
    book: Book,
    onBack: () -> Unit,
    annotationStore: AnnotationStore? = null,
    initialLocator: ReaderLocator? = null,
    fontSizeSp: Float = ReaderTypographySpec.DEFAULT_FONT_SIZE_SP,
    lineHeightMultiplier: Float = ReaderTypographySpec.DEFAULT_LINE_HEIGHT,
    onFontSizeChange: (Float) -> Unit = {},
    onLineHeightChange: (Float) -> Unit = {},
) {
    ImmersiveSystemBars()
    val context = LocalContext.current

    var document by remember(book.filePath) { mutableStateOf<TextDocument?>(null) }
    var failure by remember(book.filePath) { mutableStateOf<String?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var searchOpen by remember(book.filePath) { mutableStateOf(false) }
    var tocOpen by remember(book.filePath) { mutableStateOf(false) }
    // 图片查看器：-1 表示关着，其余是「图片在 catalog 里的下标」。用下标而不是 TextImage 本身，
    // 是因为同一张图可能在书里出现多次（每处都要单独算「第 n / 共 m 张」）。
    var imageIndex by remember(book.filePath) { mutableStateOf(-1) }
    // 标注：与 EPUB / PDF 同一条加载路径（打开读一次，增删改后 version++ 重读）。
    // 文本侧标在「整段」上（见 TextAnnotations 的 KDoc），所以只需要一个段号，不需要字符选区。
    var annotations by remember(book.filePath) { mutableStateOf<List<ReaderAnnotation>>(emptyList()) }
    var annotationsLoading by remember(book.filePath) { mutableStateOf(false) }
    var annotationsLoadFailed by remember(book.filePath) { mutableStateOf(false) }
    var annotationVersion by remember(book.filePath) { mutableIntStateOf(0) }
    var annotationsOpen by remember(book.filePath) { mutableStateOf(false) }
    // 导出弹层与标注侧栏互斥：两个 ModalBottomSheet 同时开着时，关掉上层会把下层一起带走。
    var exportOpen by remember(book.filePath) { mutableStateOf(false) }
    // 顶栏动作收纳在溢出菜单里：目录 + 朗读 + 标注 + 搜索 + 设置一起挤在 360dp 宽的
    // 标题栏上，会把书名压成省略号（ReaderTopBar 的 title 是 maxLines = 1）。
    var overflowOpen by remember { mutableStateOf(false) }
    // 长按命中的段落下标；-1 表示工具条没开。
    var annotationTarget by remember(book.filePath) { mutableIntStateOf(-1) }
    // 正在写笔记的段落下标；-1 表示输入框没开。
    var noteTargetIndex by remember(book.filePath) { mutableIntStateOf(-1) }
    // 侧栏里点「编辑笔记」时正在改的那一条（新建笔记走 noteTargetIndex，两条路径不同）。
    var noteDraft by remember(book.filePath) { mutableStateOf<ReaderAnnotation?>(null) }
    var pendingDelete by remember(book.filePath) { mutableStateOf<ReaderAnnotation?>(null) }
    var searchQuery by remember(book.filePath) { mutableStateOf("") }
    var activeHit by remember(book.filePath) { mutableStateOf<TextSearchHit?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 朗读：以「段」为单位推进。TtsPlayback 一次只装一段的句子，念完一段回到 Idle，
    // 再由下面的续段 effect 装下一段——句子偏移始终是段内的，高亮不会跨段错位。
    var ttsState by remember(book.filePath) { mutableStateOf<TtsPlaybackState>(TtsPlaybackState.Idle) }
    var ttsSentence by remember(book.filePath) { mutableStateOf<TtsSentence?>(null) }
    var ttsRate by remember { mutableStateOf(1.0f) }
    // 正在朗读的段落下标；-1 表示没在朗读。
    var aloudParagraph by remember(book.filePath) { mutableIntStateOf(-1) }
    // 「续读」意图：只有它开着，一段念完才自动接下一段。用户主动停止时先关它再 stop()，
    // 否则 stop() 同步刷出的 Idle 会被续段逻辑当成"自然念完"而接着念。
    var aloudFollowing by remember(book.filePath) { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    // 失焦回调要拿到 playback，而 playback 又需要 speaker：用持有者打破这个循环。
    // SideEffect 在组合提交后赋值，真正的音频焦点变化只会发生在更晚。
    val ttsPlaybackHolder = remember { mutableStateOf<TtsPlayback?>(null) }
    val ttsSpeaker = remember(context) {
        AndroidTtsSpeaker(context, onFocusLost = { ttsPlaybackHolder.value?.pause() })
    }
    val ttsPlayback = remember(ttsSpeaker) {
        TtsPlayback(
            speaker = ttsSpeaker,
            onStateChanged = { ttsState = it },
            onSentence = { ttsSentence = it },
        )
    }
    SideEffect { ttsPlaybackHolder.value = ttsPlayback }
    DisposableEffect(ttsPlayback) {
        onDispose { ttsPlayback.shutdown() }
    }

    // 打开：读字节 → 解码（BOM/UTF-8/GB18030）→ 按格式分段，整块放 IO，主线程只接结果。
    LaunchedEffect(book.filePath) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching { openDocument(File(book.filePath), book.format) }
        }
        loaded.onSuccess { document = it }
            .onFailure { failure = it.message ?: "读取文件失败" }
    }

    val paragraphCount = document?.paragraphs?.size ?: 0

    // 起始位置：显式跳转（书内搜索命中）按字符偏移反查；正常打开则直接用上次保存的段落下标。
    //
    // 顺序不能反：图片段没有文本，charOffsets 会跟它后面那段共用同一个偏移
    // （"上/图/下" → [0, 1, 1]），从偏移反查永远落到靠后的那段，停在插图退出、重开会跳过整张图。
    // 保存侧写的 paragraph 是精确下标，只有它才分得清「图」与「图之后」。
    val startIndex = remember(document, initialLocator) {
        val doc = document
        when {
            doc == null || doc.paragraphs.isEmpty() -> 0
            else -> {
                val explicit = initialLocator as? ReaderLocator.Text
                val fromLocator = explicit?.takeIf { it.offset >= 0 }?.let {
                    TextAnnotations.paragraphIndexOfOffset(doc, it.offset)
                }
                fromLocator
                    ?: ReadingProgress.savedPage(context, book.id)?.coerceIn(0, doc.paragraphs.lastIndex)
                    ?: 0
            }
        }
    }
    LaunchedEffect(document) {
        if (document != null && startIndex > 0) listState.scrollToItem(startIndex)
    }

    // 标注读取：与 PDF 面板同一套纪律 —— 失败要记下来，否则 0 条会冒充「这本书没有标注」。
    LaunchedEffect(annotationStore, book.id, annotationVersion) {
        val store = annotationStore
        if (store == null) {
            annotations = emptyList()
            annotationsLoadFailed = false
            annotationsLoading = false
            return@LaunchedEffect
        }
        // 不先清空：增删改后重读时保留页面上的旧底色，避免闪一下再出现。
        annotationsLoading = true
        try {
            annotations = withContext(Dispatchers.IO) { store.list(book.id) }
            annotationsLoadFailed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            annotationsLoadFailed = true
            snackbarHostState.showSnackbar("读取标注失败：" + (error.message ?: "未知错误"))
        } finally {
            // runCatching 会吞掉取消，loading 就永远停在 true（侧栏一直转圈）。
            annotationsLoading = false
        }
    }

    /** 段落 → 该段的标注列表。只用于绘制与工具条状态，侧栏吃的是完整 annotations。 */
    val annotationsByParagraph = remember(document, annotations) {
        document?.let { TextAnnotations.byParagraph(it, annotations) } ?: emptyMap()
    }

    // 当前可见段落 → 落库。防抖、切后台、离开阅读器三处共用同一份口径，
    // 免得「最后一屏算哪一段」在三个地方各算一遍（差一段就够让恢复跳错）。
    //
    // latestDocument 走 rememberUpdatedState：DisposableEffect / 生命周期观察者的闭包
    // 只建一次，直接捕获 document 会永远读到首帧的 null。
    val latestDocument by rememberUpdatedState(document)
    val persistVisibleProgress: () -> Unit = remember(book.id) {
        {
            val doc = latestDocument
            if (doc != null && doc.paragraphs.isNotEmpty()) {
                val index = listState.firstVisibleItemIndex.coerceIn(0, doc.paragraphs.lastIndex)
                ReadingProgress.saveTextProgress(
                    context = context,
                    bookId = book.id,
                    locator = ReaderLocator.Text(offset = doc.charOffsets.getOrElse(index) { 0 }, length = 0),
                    paragraph = index,
                    paragraphs = doc.paragraphs.size,
                )
            }
        }
    }

    // 进度落库：滚停 600ms 只写一次。一次拖动可能连过几十段，逐段落库既费 IO 也没有信息量。
    // 单段文档也照写：段落数不是屏数，一篇没有空行的长 txt 只有一段却要滚几十屏。
    LaunchedEffect(document) {
        if (document == null) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }.collectLatest {
            delay(ProgressSaveDelayMillis)
            persistVisibleProgress()
        }
    }

    // 切后台补落一次：EPUB 与 PDF 在同一个事件上补存，文本这条链路原先只有防抖，
    // 「滚完立刻按 Home、随后进程被杀」会整段丢掉。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, book.filePath) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) persistVisibleProgress()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 离开阅读器时补落一次：防抖窗口（600ms）里按返回时，collectLatest 会被取消，
    // 最后一次位置就再也写不进去了。onDispose 里同步入队（saveTextProgress 本身只是 enqueue）。
    DisposableEffect(book.filePath) {
        onDispose { persistVisibleProgress() }
    }

    // 搜什么都要先停一下：整本 indexOf 是 O(n·m)，逐键在组合阶段同步跑会在长文本上直接卡住主线程。
    // 停停顿时间由 LaunchedEffect 的重启天然取消上一次查询，不需要额外的 Job 管理。
    var searchHits by remember(document) { mutableStateOf<List<TextSearchHit>>(emptyList()) }
    // 「是否还有更多命中」由引擎判定（内部多扫一条），不能拿 hits.size >= 上限去猜：
    // 命中数正好等于上限时并不存在第 201 条，猜出来的「显示前 200 处」是假话。
    var searchTruncated by remember(document) { mutableStateOf(false) }
    LaunchedEffect(document, searchQuery) {
        val doc = document
        if (doc == null || searchQuery.isBlank()) {
            searchHits = emptyList()
            searchTruncated = false
            return@LaunchedEffect
        }
        delay(SearchDebounceMillis)
        val result = withContext(Dispatchers.Default) { TextSearchEngine.searchDetailed(doc, searchQuery) }
        searchHits = result.hits
        searchTruncated = result.truncated
    }

    // 目录：只有带标题的文档才有（纯 TXT 通常没有），无标题时顶栏连入口都不出现。
    val outline = remember(document) {
        document?.let { TextOutline.build(it) } ?: emptyList()
    }

    // 插图列表：查看器要在书内前后翻，索引与正文共用同一次遍历结果。
    val images = remember(document) {
        document?.let { TextImageCatalog.images(it) } ?: emptyList()
    }

    // 当前段落：进度百分比、「目录里高亮哪一章」与朗读起点共用同一个可见位置。
    val visibleParagraph by remember { derivedStateOf { listState.firstVisibleItemIndex } }

    /** 这一段现在是否在屏幕里：在就别滚，省掉每段一次的无谓动画。 */
    fun isParagraphVisible(index: Int): Boolean {
        val items = listState.layoutInfo.visibleItemsInfo
        if (items.isEmpty()) return false
        return index >= items.first().index && index <= items.last().index
    }

    /**
     * 装载某一段；返回是否真的装载成功。
     *
     * [keepPaused] 为真时走 [TtsPlayback.load]，只换句子与光标、**不向引擎下发 speak**：
     * 暂停态跨段用 "start 后立刻 pause" 会先发出半个音节再被掐断，后台音乐也会被闪一次音频焦点。
     *
     * 引擎未就绪（装载返回 false）时状态机保持原状，界面不会出现"在播却没声音"；
     * 这时段号必须清回 -1——它同时喂给跟随滚动与"滚动即停"的判定，留着会把界面滚到一个没在念的段落。
     */
    fun playParagraph(index: Int, fromSentence: Int = 0, keepPaused: Boolean = false): Boolean {
        val doc = document ?: return false
        val paragraph = doc.paragraphs.getOrNull(index) ?: return false
        val sentences = TextSpeech.sentences(paragraph.text)
        if (sentences.isEmpty()) return false
        ttsPlayback.setRate(ttsRate)
        val loaded = if (keepPaused) {
            ttsPlayback.load(sentences, fromSentence, paused = true)
        } else {
            ttsPlayback.start(sentences, fromSentence)
        }
        if (!loaded) {
            aloudParagraph = -1
            return false
        }
        aloudParagraph = index
        return true
    }

    fun stopAloud() {
        // 顺序是契约：先关「续读」再停引擎。stop() 会同步把状态刷成 Idle，
        // 反序会被续段逻辑当成"自然念完"接着念下一段。
        aloudFollowing = false
        aloudParagraph = -1
        ttsPlayback.stop()
    }

    /**
     * 收起长按工具条。
     *
     * 工具条记的是「第几段」，它不会因为滚动而失效 —— 从目录或搜索结果跳到别处后，
     * 工具条仍停在屏幕上，此时点高亮/复制作用的是原来那一段，而用户看到的是完全不同的内容。
     * 所以每次主动改变阅读位置（目录、搜索、标注跳转、开始朗读）都要先收工具条。
     */
    fun dismissAnnotationTools() {
        annotationTarget = -1
    }

    /** 从当前可见段落起读；不可见文字（空段）向后找第一段。 */
    fun startAloud() {
        val doc = document
        if (doc == null || doc.paragraphs.isEmpty()) {
            scope.launch { snackbarHostState.showSnackbar("这本书没有可朗读的文字") }
            return
        }
        // 朗读控制条与标注工具条都在 BottomCenter，同时浮着会叠在一起。
        dismissAnnotationTools()
        val target = TextSpeech.firstSpeakableAtOrAfter(doc.paragraphs, visibleParagraph)
        if (target == null) {
            scope.launch { snackbarHostState.showSnackbar("这之后没有可朗读的文字") }
            return
        }
        aloudFollowing = true
        if (!playParagraph(target)) {
            aloudFollowing = false
            scope.launch { snackbarHostState.showSnackbar("系统朗读引擎不可用") }
        }
    }

    fun stepPreviousSentence() {
        val doc = document ?: return
        if (ttsState.sentenceIndex > 0) {
            ttsPlayback.previous()
            return
        }
        val previous = TextSpeech.previousSpeakable(doc.paragraphs, aloudParagraph) ?: return
        val lastSentence = TextSpeech.sentences(doc.paragraphs[previous].text).lastIndex
        playParagraph(
            index = previous,
            fromSentence = lastSentence,
            keepPaused = ttsState is TtsPlaybackState.Paused,
        )
    }

    fun stepNextSentence() {
        val doc = document ?: return
        if (ttsState.sentenceIndex < ttsState.sentenceTotal - 1) {
            ttsPlayback.next()
            return
        }
        val next = TextSpeech.nextSpeakable(doc.paragraphs, aloudParagraph)
        if (next == null) {
            stopAloud()
            scope.launch { snackbarHostState.showSnackbar("已经读到文件末尾") }
            return
        }
        if (!playParagraph(next, keepPaused = ttsState is TtsPlaybackState.Paused)) {
            // 装载失败还继续留着 aloudFollowing，续段逻辑会从"再下一段"接着找，
            // 用户想听的那一段就被静默跳过了。
            stopAloud()
            scope.launch { snackbarHostState.showSnackbar("系统朗读引擎不可用") }
        }
    }

    fun notify(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    /**
     * 长按某段：先停朗读再开工具条。
     *
     * 底部一次只留一个浮条 —— 朗读控制条与标注工具条同时浮着会叠在一起，
     * 而且"正在念这一段"和"我要标这一段"本来就是两种意图，不该并存。
     */
    fun openAnnotationTools(index: Int) {
        stopAloud()
        annotationTarget = index
    }

    /**
     * 给这一段上色。已经有高亮就改色而不是再叠一条：同一段两条高亮在列表里分不出谁是谁，
     * 用户点第二个色点的意思显然是"换成这个颜色"。
     */
    fun applyHighlight(index: Int, colorKey: String) {
        val doc = document ?: return
        val store = annotationStore ?: run {
            notify("标注存储未就绪")
            return
        }
        val existing = annotations.firstOrNull {
            it.type == ReaderAnnotationType.HIGHLIGHT && TextAnnotations.paragraphOf(doc, it) == index
        }
        scope.launch {
            val now = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (existing == null) {
                        TextAnnotations.create(
                            bookId = book.id,
                            document = doc,
                            paragraphIndex = index,
                            type = ReaderAnnotationType.HIGHLIGHT,
                            color = colorKey,
                            now = now,
                        )?.let(store::add)
                    } else {
                        store.update(existing.copy(color = colorKey, updatedAt = now))
                    }
                }
            }
            result.onSuccess { saved ->
                // 与保存笔记同一口径：create 在段落没有可标注文字时返回 null，
                // 此时别报「已标为高亮」——重读一遍什么都不会出现。
                if (existing == null && saved == null) {
                    notify("这一段没有可标注的文字")
                } else {
                    annotationVersion += 1
                    notify(if (existing == null) "已标为高亮" else "已更换高亮颜色")
                }
            }.onFailure { notify("保存高亮失败：" + (it.message ?: "未知错误")) }
        }
    }

    /** 书签按段落记：同一段只留一条，再点一次取消。 */
    fun toggleBookmark(index: Int) {
        val doc = document ?: return
        val store = annotationStore ?: run {
            notify("标注存储未就绪")
            return
        }
        val existing = annotations.firstOrNull {
            it.type == ReaderAnnotationType.BOOKMARK && TextAnnotations.paragraphOf(doc, it) == index
        }
        scope.launch {
            val now = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (existing == null) {
                        TextAnnotations.create(
                            bookId = book.id,
                            document = doc,
                            paragraphIndex = index,
                            type = ReaderAnnotationType.BOOKMARK,
                            now = now,
                        )?.let(store::add)
                    } else {
                        store.remove(existing.id)
                    }
                }
            }
            result.onSuccess { saved ->
                annotationVersion += 1
                when {
                    existing != null -> notify("已取消书签")
                    saved == null -> notify("这一段没有可标记的文字")
                    else -> notify("已加书签")
                }
            }.onFailure { notify("书签保存失败：" + (it.message ?: "未知错误")) }
        }
    }

    fun saveNewNote(text: String) {
        val doc = document ?: return
        val store = annotationStore ?: run {
            notify("标注存储未就绪")
            return
        }
        val index = noteTargetIndex
        if (index < 0 || text.isBlank()) return
        scope.launch {
            val now = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    TextAnnotations.create(
                        bookId = book.id,
                        document = doc,
                        paragraphIndex = index,
                        type = ReaderAnnotationType.NOTE,
                        // 与 PDF 侧同一口径：笔记用调色板蓝色，和默认黄色高亮分开。
                        color = EpubAnnotationPalette.note.key,
                        note = text.trim(),
                        now = now,
                    )?.let(store::add)
                }
            }
            result.onSuccess { saved ->
                noteTargetIndex = -1
                if (saved == null) {
                    notify("这一段没有可标注的文字")
                } else {
                    annotationVersion += 1
                    notify("笔记已保存")
                }
            }.onFailure { notify("保存笔记失败：" + (it.message ?: "未知错误")) }
        }
    }

    /** 改已有的笔记：只改 note 与 updatedAt，locator/颜色/引文都不动（与 EPUB/PDF 同一约定）。 */
    fun updateNote(annotation: ReaderAnnotation, text: String) {
        val store = annotationStore ?: run {
            notify("标注存储未就绪")
            return
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    store.update(annotationWithNote(annotation, text, System.currentTimeMillis()))
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
            }.onFailure { notify("更新笔记失败：" + (it.message ?: "未知错误")) }
        }
    }

    fun deleteAnnotation(annotation: ReaderAnnotation) {
        val store = annotationStore ?: run {
            notify("标注存储未就绪")
            return
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { store.remove(annotation.id) } }
            result.onSuccess { removed ->
                pendingDelete = null
                if (removed) {
                    annotationVersion += 1
                    notify("已删除标注")
                } else {
                    notify("标注不存在")
                }
            }.onFailure { notify("删除标注失败：" + (it.message ?: "未知错误")) }
        }
    }

    /** 侧栏点一条：只按段落跳，段内定位交给底色。坏 locator 不猜位置。 */
    fun jumpToAnnotation(annotation: ReaderAnnotation) {
        annotationsOpen = false
        dismissAnnotationTools()
        val doc = document ?: return
        if (annotation.locatorError != null) {
            notify("位置不可用，无法跳转")
            return
        }
        // 空文档时 paragraphOf 恒返回 0（见 TextAnnotations.paragraphIndexOfOffset 的约定），
        // 直接交给 coerceIn(0, -1) 会抛 IllegalArgumentException —— 而且是在 launch 里抛，没人接。
        // 文件被外部改写/截断后重开就是这个状态：旧标注还在，正文一篇空白。
        if (doc.paragraphs.isEmpty()) {
            notify("这本书没有可定位的正文")
            return
        }
        val index = TextAnnotations.paragraphOf(doc, annotation)
        if (index == null || index !in doc.paragraphs.indices) {
            // 不用 coerceIn 夹到末段：夹取看起来像"跳成功了"，实际停在毫不相干的位置。
            notify("这条标注不在当前正文里")
            return
        }
        stopAloud()
        scope.launch { listState.animateScrollToItem(index) }
    }

    fun copyParagraph(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            notify("这台设备没有可用的剪贴板")
            return
        }
        runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText(book.title, text))
            notify("已复制这一段")
        }.onFailure { notify("复制失败：" + (it.message ?: "未知错误")) }
        annotationTarget = -1
    }

    // 上次读失败过就先重读再开侧栏：否则 0 条会冒充「这本书没有标注」。
    val openAnnotations: () -> Unit = {
        if (annotationsLoadFailed) annotationVersion += 1
        annotationsOpen = true
    }

    // 返回书架前先停读：退出动画里阅读器还要在组合里停留一小段，
    // 那段时间引擎仍在出声，用户已经看到书架了还能听见朗读尾巴。
    val leaveReader: () -> Unit = {
        stopAloud()
        onBack()
    }
    BackHandler(onBack = leaveReader)

    // 一段念完 → 接下一段；念到末尾就收摊。ttsState 由状态机同步写回，
    // 这里等价于"自然播完"的通知渠道：用户主动停止会先把 aloudFollowing 关掉。
    LaunchedEffect(ttsState) {
        if (ttsState != TtsPlaybackState.Idle) return@LaunchedEffect
        if (!aloudFollowing) return@LaunchedEffect
        // 段号已清（用户停止 / 装载失败）时不该续：靠调用顺序保证太脆，写死成前置条件。
        if (aloudParagraph < 0) return@LaunchedEffect
        val doc = document ?: return@LaunchedEffect
        val next = TextSpeech.nextSpeakable(doc.paragraphs, aloudParagraph)
        if (next == null) {
            stopAloud()
            snackbarHostState.showSnackbar("已经读到文件末尾")
        } else if (!playParagraph(next)) {
            stopAloud()
            snackbarHostState.showSnackbar("系统朗读引擎不可用")
        }
    }

    // 念到屏幕外就把当前段滚进视野（已在视野里不动，免得每段抖一下）。
    LaunchedEffect(aloudParagraph) {
        if (aloudParagraph < 0) return@LaunchedEffect
        if (isParagraphVisible(aloudParagraph)) return@LaunchedEffect
        listState.animateScrollToItem(aloudParagraph)
    }

    // 用户一拖动就停读：屏幕上已经换了内容，继续念就是"念的不是你看到的"。
    //
    // 认 DragInteraction 而不是 isScrollInProgress：后者把自动跟随滚动也算进来，
    // 只能再拿一个布尔标志猜"这次是谁发起的"；而相邻段短于一次滚动动画时，
    // 上一轮 effect 的 finally 会把新一轮刚置上的标志清掉，朗读就这样被自己停掉。
    // 程序滚动（animateScrollToItem）不产生 DragInteraction，天然不用猜来源。
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) stopAloud()
        }
    }

    // 与「跳到位置」面板同一个口径：已读段数 / 总段数，四舍五入。
    // 原来用整数截断，4 段读到第 2 段时顶栏 50%、面板 50%，但 3 段时顶栏 66%、面板 67% —— 同屏两个数字。
    val percent by remember(document) {
        derivedStateOf {
            val total = document?.paragraphs?.size ?: 0
            if (total <= 0) 0 else (((listState.firstVisibleItemIndex + 1) * 100f) / total).roundToInt().coerceIn(0, 100)
        }
    }

    // 「跳到位置」面板：打开时把目标段落定死，拖动只更新文案。
    // TextProgressSheet 的 Slider 以 paragraphIndex 为 remember key，换段落会在拖动中被重建，
    // 所以预览阶段必须保持 paragraphIndex 不变（只换 fraction / 文案 / 章名）。
    var progressOpen by remember(book.filePath) { mutableStateOf(false) }
    var progressTarget by remember(book.filePath) { mutableStateOf<TextProgressTarget?>(null) }
    fun openProgress() {
        val doc = document ?: return
        progressTarget = TextProgressJump.targetOfParagraph(doc, outline, visibleParagraph)
        progressOpen = true
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            ReaderTopBar(
                title = book.title,
                onBack = leaveReader,
                actions = {
                    if (paragraphCount > 1) {
                        // 进度只写「12%」：带「已读」两个字会把标题挤没。
                        // 同时是「跳到位置」的入口——几百段的书里，想回到第三章只能一路狂滚。
                        TextButton(
                            onClick = { openProgress() },
                            contentPadding = PaddingValues(horizontal = Spacing.xs),
                        ) {
                            Text(
                                text = percent.toString() + "%",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (outline.isNotEmpty()) {
                        IconButton(onClick = { tocOpen = true }) {
                            Icon(Icons.AutoMirrored.Outlined.List, contentDescription = "目录")
                        }
                    }
                    // 入口按"从哪开始念"来理解：已在朗读时再点一次是从当前可见段落重新起读，
                    // 而不是停止——控制条上已经有停止键，顶栏再放一个停止会让人以为朗读坏了。
                    TextButton(onClick = { startAloud() }) { Text("朗读") }
                    // 标注 / 搜索 / 阅读设置收进溢出菜单：三个图标按钮加返回键、进度与朗读，
                    // 在 360dp 屏上会把书名压成省略号（ReaderTopBar 的 title 固定 maxLines = 1）。
                    Box {
                        IconButton(onClick = { overflowOpen = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "更多操作")
                        }
                        DropdownMenu(
                            expanded = overflowOpen,
                            onDismissRequest = { overflowOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (annotations.isEmpty()) "标注" else "标注（" + annotations.size + "）",
                                    )
                                },
                                enabled = annotationStore != null,
                                onClick = {
                                    overflowOpen = false
                                    openAnnotations()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("书内搜索") },
                                onClick = {
                                    overflowOpen = false
                                    searchOpen = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("阅读设置") },
                                onClick = {
                                    overflowOpen = false
                                    settingsOpen = true
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val current = document
            when {
                failure != null -> TextReaderMessage(
                    title = "打不开这个文件",
                    detail = failure ?: "",
                    onBack = leaveReader,
                )

                current == null -> TextReaderLoading()

                current.isEmpty -> TextReaderMessage(
                    title = "这个文件没有文字",
                    detail = "《" + book.title + "》解出来是空文档，可能是空文件或编码无法识别。",
                    onBack = leaveReader,
                )

                else -> TextFlow(
                    document = current,
                    listState = listState,
                    fontSizeSp = fontSizeSp,
                    lineHeightMultiplier = lineHeightMultiplier,
                    hit = activeHit,
                    speechParagraph = if (ttsState == TtsPlaybackState.Idle) -1 else aloudParagraph,
                    speechStart = ttsSentence?.startChar ?: -1,
                    speechEnd = ttsSentence?.endChar ?: -1,
                    annotations = annotationsByParagraph,
                    onLongPress = { index -> openAnnotationTools(index) },
                    onImageClick = { image -> imageIndex = TextImageCatalog.indexOfKey(current, image.key) ?: -1 },
                )
            }
            // 标注工具条：长按某段后浮在底部。与朗读控制条互斥（长按会先停朗读），
            // 所以两个都写 BottomCenter 也不会叠在一起。
            val target = annotationTarget
            if (target >= 0 && current != null && target in current.paragraphs.indices) {
                val marks = annotationsByParagraph[target].orEmpty()
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                ) {
                    TextAnnotationToolbar(
                        paragraphLength = current.paragraphs[target].text.length,
                        // 与正文底色用同一个函数：两边各写一套优先级时，先写笔记后点高亮的段
                        // 会一边显示笔记蓝、一边把黄色色点标成选中。
                        currentColor = TextAnnotations.paintColorOf(marks),
                        bookmarked = marks.any { it.type == ReaderAnnotationType.BOOKMARK },
                        onHighlight = { key ->
                            // 点下就收工具条：写入要走 IO，等回调关闭的话这期间还能再点一次，
                            // 两次都读到"还没有高亮"就会插两条，之后改色只改得到第一条。
                            dismissAnnotationTools()
                            applyHighlight(target, key)
                        },
                        onNote = {
                            noteTargetIndex = target
                            dismissAnnotationTools()
                        },
                        onToggleBookmark = {
                            dismissAnnotationTools()
                            toggleBookmark(target)
                        },
                        onCopy = { copyParagraph(current.paragraphs[target].text) },
                        onDismiss = { dismissAnnotationTools() },
                    )
                }
            }
            // 朗读控制条浮在正文之上：不做 mini-player 顶替底栏那一套，
            // 文本阅读器没有底栏，浮起来才不挤掉最后几行。
            if (ttsState != TtsPlaybackState.Idle) {
                TtsBar(
                    state = ttsState,
                    rate = ttsRate,
                    progressUnit = ParagraphProgressUnit,
                    onPlayPause = {
                        if (ttsState is TtsPlaybackState.Playing) ttsPlayback.pause() else ttsPlayback.resume()
                    },
                    onPrevious = { stepPreviousSentence() },
                    onNext = { stepNextSentence() },
                    onCycleRate = {
                        val next = TtsRates[(TtsRates.indexOf(ttsRate) + 1).mod(TtsRates.size)]
                        ttsRate = next
                        ttsPlayback.setRate(next)
                    },
                    onStop = { stopAloud() },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                )
            }
        }
    }

    if (tocOpen && outline.isNotEmpty()) {
        TextTocSheet(
            chapters = outline,
            activeParagraphIndex = visibleParagraph,
            onPick = { chapter ->
                tocOpen = false
                // 跳到别的章还接着念旧位置，听起来就是"念错了地方"：跳转即停。
                // 工具条同理：它记的是原来那一段，留着就能对屏幕外的段落动手。
                dismissAnnotationTools()
                stopAloud()
                scope.launch { listState.animateScrollToItem(chapter.paragraphIndex) }
            },
            onDismiss = { tocOpen = false },
        )
    }

    if (searchOpen && document != null) {
        TextSearchSheet(
            query = searchQuery,
            hits = searchHits,
            truncated = searchTruncated,
            onQueryChange = { next ->
                searchQuery = next
                // 换词就撤掉上一次跳转的命中高亮：否则那一段会一直带着底色，像被永久标记了。
                activeHit = null
            },
            onPick = { picked ->
                activeHit = picked
                searchOpen = false
                // 同上：跳到命中处就停读，否则声音停留在一屏之外。
                dismissAnnotationTools()
                stopAloud()
                // 段落是排版单位：滚到该段首（段内精确到字符需要 TextLayoutResult，收益不值这个复杂度），
                // 段内命中位置靠行内高亮标出来。
                scope.launch { listState.animateScrollToItem(picked.paragraphIndex) }
            },
            onDismiss = { searchOpen = false },
        )
    }

    val progressDoc = document
    val shownProgress = progressTarget
    if (progressOpen && progressDoc != null && shownProgress != null) {
        TextProgressSheet(
            target = shownProgress,
            canGoPreviousChapter = TextProgressJump
                .chapterStep(progressDoc, outline, shownProgress.paragraphIndex, -1) != null,
            canGoNextChapter = TextProgressJump
                .chapterStep(progressDoc, outline, shownProgress.paragraphIndex, 1) != null,
            // 拖动只换文案：Slider 以 paragraphIndex 为 key，换段会在拖动中把滑块重建掉。
            onSeek = { fraction ->
                val preview = TextProgressJump.targetOf(progressDoc, outline, fraction)
                progressTarget = shownProgress.copy(
                    fraction = preview.fraction,
                    percentLabel = preview.percentLabel,
                    positionLabel = preview.positionLabel,
                    chapterTitle = preview.chapterTitle,
                    chapterIndex = preview.chapterIndex,
                )
            },
            onJump = { fraction ->
                val landed = TextProgressJump.targetOf(progressDoc, outline, fraction)
                progressOpen = false
                progressTarget = landed
                dismissAnnotationTools()
                stopAloud()
                scope.launch { listState.scrollToItem(landed.paragraphIndex) }
            },
            onChapterStep = { direction ->
                val landed = TextProgressJump
                    .chapterStep(progressDoc, outline, shownProgress.paragraphIndex, direction)
                if (landed != null) {
                    progressOpen = false
                    progressTarget = landed
                    dismissAnnotationTools()
                    stopAloud()
                    scope.launch { listState.scrollToItem(landed.paragraphIndex) }
                }
            },
            onDismiss = { progressOpen = false },
        )
    }

    if (imageIndex in images.indices) {
        TextImageViewer(
            images = images,
            initialIndex = imageIndex,
            onDismiss = { imageIndex = -1 },
        )
    }

    if (annotationsOpen) {
        AnnotationListSheet(
            annotations = annotations,
            loading = annotationsLoading,
            emptyHint = "暂无标注。长按正文任意一段，用弹出的工具条加高亮、写笔记或加书签。",
            onDismiss = { annotationsOpen = false },
            onJump = { jumpToAnnotation(it) },
            onEditNote = {
                // 与 EPUB / PDF 同一顺序：先关侧栏再开编辑弹层，两个浮层不叠。
                annotationsOpen = false
                noteDraft = it
            },
            onDelete = { pendingDelete = it },
            // 导出与另两种格式共用同一套规划器：纯文本标注落在「正文」一节，
            // 位置文案由 textLocationOf 换成段号（字符偏移对读者没有意义）。
            onExport = {
                annotationsOpen = false
                exportOpen = true
            },
            // 文本没有章节锚点可对位（TXT 更是连标题都没有）：给段号，同段多条才分得清。
            subtitleOf = { annotation ->
                document?.let { doc ->
                    TextAnnotations.paragraphOf(doc, annotation)
                        ?.takeIf { it in doc.paragraphs.indices }
                        ?.let { "第 " + (it + 1) + " 段" }
                }
            },
            loadFailed = annotationsLoadFailed,
            onRetry = { annotationVersion += 1 },
        )
    }

    if (exportOpen) {
        val doc = document
        if (doc != null) {
            val exportDocument = remember(annotations, doc, book) {
                AnnotationExportPlanner.plan(
                    book = book,
                    annotations = annotations,
                    textLocationOf = { offset ->
                        "第 " + (TextAnnotations.paragraphIndexOfOffset(doc, offset) + 1) + " 段"
                    },
                )
            }
            AnnotationExportSheet(
                document = exportDocument,
                onDismiss = { exportOpen = false },
                onNotify = { notify(it) },
            )
        }
    }

    if (noteTargetIndex >= 0) {
        AnnotationNoteEditorDialog(
            title = "写笔记",
            initialText = "",
            onDismiss = { noteTargetIndex = -1 },
            onConfirm = { text -> saveNewNote(text) },
        )
    }

    noteDraft?.let { draft ->
        AnnotationNoteEditorDialog(
            title = "编辑笔记",
            initialText = draft.note,
            onDismiss = { noteDraft = null },
            onConfirm = { text -> updateNote(draft, text) },
        )
    }

    pendingDelete?.let { annotation ->
        AnnotationDeleteDialog(
            annotation = annotation,
            onDismiss = { pendingDelete = null },
            onConfirm = { deleteAnnotation(annotation) },
        )
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
}

/** 滚动停止多久后落一次进度。 */
private const val ProgressSaveDelayMillis = 600L

/** 输入停顿多久才真正开搜：逐键全表扫会卡住主线程。 */
private const val SearchDebounceMillis = 180L

/**
 * 朗读进度文案的单位。
 *
 * 文本阅读器按「段」推进，所以不能沿用状态机默认的「本页」——那会让用户以为自己在翻页。
 * 写成常量而不是就地字面量：文案散落在 TtsBar 与状态机两侧时，漏改一处就会静默显示错单位。
 */
private const val ParagraphProgressUnit = "本段"

/**
 * 打开前允许的最大文件字节数。
 *
 * 解码器的字符上限是 4M（[TextFileDecoder.MAX_TEXT_CHARS]），按 UTF-8 最多 4 字节/字符算约 16 MB，
 * 这里留一点余量。真正的意义是**在 readBytes 之前**就拦住超大文件，而不是读到内存里再截断。
 */
private const val MaxTextFileBytes = 24L * 1024 * 1024

/** 正文最大行宽：平板上正文铺满会一行上千像素，读起来像海报。 */
private val TextColumnMaxWidth = 760.dp

/** 段落流：一段一项，段间靠内边距呼吸，不用分隔线（文本本来就没有格子）。 */
@Composable
private fun TextFlow(
    document: TextDocument,
    listState: LazyListState,
    fontSizeSp: Float,
    lineHeightMultiplier: Float,
    hit: TextSearchHit?,
    speechParagraph: Int,
    speechStart: Int,
    speechEnd: Int,
    annotations: Map<Int, List<ReaderAnnotation>>,
    onLongPress: (Int) -> Unit,
    onImageClick: (TextImage) -> Unit,
) {
    val sizeSp = normalizeFontSize(fontSizeSp)
    val lineHeightSp = normalizeLineHeight(lineHeightMultiplier) * sizeSp
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = TextColumnMaxWidth),
            contentPadding = PaddingValues(
                start = Spacing.lg,
                end = Spacing.lg,
                top = Spacing.md,
                bottom = Spacing.xxl * 2,
            ),
        ) {
            itemsIndexed(document.paragraphs) { index, paragraph ->
                // 只有当前命中那一段带区间；命中切换时其余段回到 -1，高亮自然消失。
                val hitStart = if (hit?.paragraphIndex == index) hit.start else -1
                val hitEnd = if (hit?.paragraphIndex == index) hit.end else -1
                // 朗读区间同理：只有正在念的那一段带区间，换段后其余段自然回到 -1。
                val speechRangeStart = if (speechParagraph == index) speechStart else -1
                val speechRangeEnd = if (speechParagraph == index) speechEnd else -1
                // 这一段有高亮/笔记就整段带底色（标注是段落级的，没有更细的区间可用）。
                // 只有书签不上色：书签没有颜色，回退成默认黄会和高亮完全分不出来。
                // 颜色来自调色板 key；用户自己塞的十六进制也算，未知 key 回退黄色。
                val marks = annotations[index].orEmpty()
                val annotationBackground = if (!TextAnnotations.hasPaint(marks)) {
                    null
                } else {
                    annotationColor(TextAnnotations.paintColorOf(marks) ?: EpubAnnotationPalette.default.key)
                }
                val image = paragraph.image
                if (image != null) {
                    // 图片段：整段是一张图，不参与搜索命中 / 朗读 / 标注（text 为空，那三处都按空串跳过）。
                    TextImageBlock(
                        image = image,
                        modifier = Modifier.padding(vertical = Spacing.xs),
                        onClick = { onImageClick(image) },
                    )
                } else {
                    TextParagraphView(
                        paragraph = paragraph,
                        baseSizeSp = sizeSp,
                        lineHeightSp = lineHeightSp,
                        first = index == 0,
                        highlightStart = hitStart,
                        highlightEnd = hitEnd,
                        speechStart = speechRangeStart,
                        speechEnd = speechRangeEnd,
                        annotationBackground = annotationBackground,
                        onLongPress = { onLongPress(index) },
                    )
                }
            }
        }
    }
}

/**
 * 一段正文。标题按「用户字号 × 层级系数」缩放，不用固定 TextStyle 字号——
 * 否则用户放大字号时，标题会显得比正文还小。
 */
@Composable
private fun TextParagraphView(
    paragraph: TextParagraph,
    baseSizeSp: Float,
    lineHeightSp: Float,
    first: Boolean,
    highlightStart: Int = -1,
    highlightEnd: Int = -1,
    speechStart: Int = -1,
    speechEnd: Int = -1,
    annotationBackground: Color? = null,
    onLongPress: () -> Unit = {},
) {
    val level = paragraph.headingLevel
    val text = paragraph.text
    // 两种区间都直接标在已排好的文本上：不拆段、不重排，滚到该段就有上下文。
    // 搜索命中走 tertiary 系（"这是你要找的字"），朗读走 primary 系（"正在念这一句"）：
    // 语义不同就该长得不一样，同一段里也可能同时出现（从搜索结果跳过来接着朗读）。
    val hitBackground = MaterialTheme.colorScheme.tertiaryContainer
    val hitForeground = MaterialTheme.colorScheme.onTertiaryContainer
    val speechBackground = MaterialTheme.colorScheme.primaryContainer
    val speechForeground = MaterialTheme.colorScheme.onPrimaryContainer
    val decorated = remember(
        text,
        highlightStart,
        highlightEnd,
        speechStart,
        speechEnd,
        annotationBackground,
        hitBackground,
        hitForeground,
        speechBackground,
        speechForeground,
    ) {
        val hasHit = highlightStart in 0 until highlightEnd
        val hasSpeech = speechStart in 0 until speechEnd
        if (!hasHit && !hasSpeech && annotationBackground == null) {
            AnnotatedString(text)
        } else {
            buildAnnotatedString {
                append(text)
                // 标注在最下层：整段底色先铺，搜索命中与朗读句再压上去（后加的 SpanStyle 覆盖同属性）。
                if (annotationBackground != null && text.isNotEmpty()) {
                    addStyle(SpanStyle(background = annotationBackground), 0, text.length)
                }
                if (hasHit) {
                    val start = highlightStart.coerceIn(0, text.length)
                    val end = highlightEnd.coerceIn(start, text.length)
                    if (end > start) {
                        addStyle(
                            SpanStyle(
                                background = hitBackground,
                                color = hitForeground,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            start,
                            end,
                        )
                    }
                }
                if (hasSpeech) {
                    val start = speechStart.coerceIn(0, text.length)
                    val end = speechEnd.coerceIn(start, text.length)
                    if (end > start) {
                        addStyle(
                            SpanStyle(
                                background = speechBackground,
                                color = speechForeground,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            start,
                            end,
                        )
                    }
                }
            }
        }
    }
    // 长按手势绑在段落本身上：pointerInput 的 key 用 Unit，靠 rememberUpdatedState 拿最新回调，
    // 否则每次重组都会重启手势识别器（一段一次，滚起来不划算）。
    //
    // 除了触摸手势还要挂语义动作：pointerInput 不产生任何无障碍节点，读屏用户拿不到
    // 「标注这一段」的入口，整个标注功能对他们等于不存在。
    val currentLongPress by rememberUpdatedState(onLongPress)
    val longPressModifier = Modifier
        .pointerInput(Unit) {
            detectTapGestures(onLongPress = { currentLongPress() })
        }
        .semantics {
            onLongClick(label = "标注本段") {
                currentLongPress()
                true
            }
        }
    if (level in 1..6) {
        val scale = when (level) {
            1 -> 1.70f
            2 -> 1.50f
            3 -> 1.32f
            4 -> 1.20f
            5 -> 1.12f
            else -> 1.06f
        }
        Text(
            text = decorated,
            style = MaterialTheme.typography.titleLarge,
            fontSize = (baseSizeSp * scale).sp,
            lineHeight = (lineHeightSp * scale).sp,
            fontWeight = FontWeight.SemiBold,
            modifier = longPressModifier.padding(
                top = if (first) Spacing.xs else Spacing.lg,
                bottom = Spacing.xs,
            ),
        )
    } else {
        Text(
            text = decorated,
            style = MaterialTheme.typography.bodyLarge,
            fontSize = baseSizeSp.sp,
            lineHeight = lineHeightSp.sp,
            modifier = longPressModifier.padding(vertical = Spacing.xxs),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TextReaderLoading() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 与 EPUB / PDF 阅读器同一套「等待中」语汇：统一进度指示器的波浪线，不用已废弃的圆形转圈。
        LinearWavyProgressIndicator(
            modifier = Modifier
                .width(160.dp)
                .height(Spacing.xxs),
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
private fun TextReaderMessage(title: String, detail: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.xl, vertical = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.lg))
        Button(onClick = onBack) { Text("返回书架") }
    }
}

/**
 * 打开文本类书籍：先按 BOM/编码猜解出字符串，再按格式选解析器。
 *
 * - TXT：空行分段、段内软换行折成空格；
 * - Markdown：空行分段、保留段内换行、识别 # 标题；
 * - FB2：交给 [Fb2Parser] 从 XML 里取出章节标题、正文段落与内嵌图片，再经
 *   [TextDocumentParser.fromParagraphs] 统一切分与偏移计算；
 * - HTML：交给 [HtmlTextExtractor] 剥掉标签取出标题、段落与插图（相对路径按本书目录解析），
 *   同样经 fromParagraphs 归一。
 *
 * 四条路最终都得到同一个 [TextDocument]，所以阅读器、搜索与进度链路对格式零分叉。
 */
private fun openDocument(file: File, format: BookFormat): TextDocument {
    // 先按字节拦一道：解码器的字符上限是在**已经读进内存并解码之后**才截断的，
    // 一本几十 MB 的「合集」txt 会先把整个 ByteArray 和完整解码串塞进堆，中低端机直接 OOM。
    val bytes = file.length()
    if (bytes > MaxTextFileBytes) {
        error(
            "这个文件有 " + (bytes / (1024 * 1024)) + " MB，超过 " +
                (MaxTextFileBytes / (1024 * 1024)) + " MB 上限，暂时打不开；可以先在电脑上拆分或转成 EPUB。",
        )
    }
    val text = TextFileDecoder.decode(file.readBytes()).text
    return when (format) {
        BookFormat.Fb2 -> {
            val document = Fb2Parser.parse(text)
            TextDocumentParser.fromParagraphs(
                document.paragraphs.map { TextParagraph(it.text, it.headingLevel, it.image) },
            )
        }

        // baseDir 给 img 的相对路径用：书导入时文件与图片一起复制到书架目录，
        // 相对路径就是相对「这本书所在的目录」。
        BookFormat.Html -> TextDocumentParser.fromParagraphs(
            HtmlTextExtractor.extract(text, baseDir = file.parentFile),
        )
        BookFormat.Markdown -> TextDocumentParser.parse(text, markdown = true)
        else -> TextDocumentParser.parse(text, markdown = false)
    }
}
