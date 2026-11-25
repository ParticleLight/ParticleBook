package app.particlebook.android.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.particlebook.android.reader.ReaderLocator
import app.particlebook.android.reader.fb2.Fb2Parser
import app.particlebook.android.reader.html.HtmlTextExtractor
import app.particlebook.android.reader.text.TextDocument
import app.particlebook.android.reader.text.TextDocumentParser
import app.particlebook.android.reader.text.TextOutline
import app.particlebook.android.reader.text.TextSearchEngine
import app.particlebook.android.reader.text.TextSearchHit
import app.particlebook.android.reader.text.TextFileDecoder
import app.particlebook.android.reader.text.TextParagraph
import app.particlebook.android.reader.text.TextSpeech
import app.particlebook.android.reader.tts.AndroidTtsSpeaker
import app.particlebook.android.reader.tts.TtsPlayback
import app.particlebook.android.reader.tts.TtsPlaybackState
import app.particlebook.android.reader.tts.TtsSentence
import app.particlebook.android.ui.data.Book
import app.particlebook.android.ui.data.BookFormat
import app.particlebook.android.ui.data.ReadingProgress
import java.io.File
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

    // 起始位置：搜索命中（字符偏移）优先，否则回上次读到的段落下标。
    val startIndex = remember(document, initialLocator) {
        val doc = document
        if (doc == null || doc.paragraphs.isEmpty()) {
            0
        } else {
            val offset = (initialLocator as? ReaderLocator.Text)?.offset
            val fromLocator = if (offset != null && offset >= 0) paragraphIndexForOffset(doc, offset) else null
            fromLocator ?: ReadingProgress.savedPage(context, book.id)
                ?.coerceIn(0, doc.paragraphs.lastIndex) ?: 0
        }
    }
    LaunchedEffect(document) {
        if (document != null && startIndex > 0) listState.scrollToItem(startIndex)
    }

    // 进度落库：滚停 600ms 只写一次。一次拖动可能连过几十段，逐段落库既费 IO 也没有信息量。
    LaunchedEffect(document) {
        val doc = document
        if (doc == null || doc.paragraphs.size <= 1) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }.collectLatest { index ->
            delay(ProgressSaveDelayMillis)
            ReadingProgress.saveTextProgress(
                context = context,
                bookId = book.id,
                locator = ReaderLocator.Text(offset = doc.charOffsets.getOrElse(index) { 0 }, length = 0),
                paragraph = index,
                paragraphs = doc.paragraphs.size,
            )
        }
    }

    // 离开阅读器时补落一次：防抖窗口（600ms）里按返回时，collectLatest 会被取消，
    // 最后一次位置就再也写不进去了。onDispose 里同步入队（saveTextProgress 本身只是 enqueue）。
    DisposableEffect(book.filePath) {
        onDispose {
            val doc = document
            if (doc != null && doc.paragraphs.size > 1) {
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

    // 搜什么都要先停一下：整本 indexOf 是 O(n·m)，逐键在组合阶段同步跑会在长文本上直接卡住主线程。
    // 停停顿时间由 LaunchedEffect 的重启天然取消上一次查询，不需要额外的 Job 管理。
    var searchHits by remember(document) { mutableStateOf<List<TextSearchHit>>(emptyList()) }
    LaunchedEffect(document, searchQuery) {
        val doc = document
        if (doc == null || searchQuery.isBlank()) {
            searchHits = emptyList()
            return@LaunchedEffect
        }
        delay(SearchDebounceMillis)
        searchHits = withContext(Dispatchers.Default) { TextSearchEngine.search(doc, searchQuery) }
    }

    // 目录：只有带标题的文档才有（纯 TXT 通常没有），无标题时顶栏连入口都不出现。
    val outline = remember(document) {
        document?.let { TextOutline.build(it) } ?: emptyList()
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

    /** 从当前可见段落起读；不可见文字（空段）向后找第一段。 */
    fun startAloud() {
        val doc = document
        if (doc == null || doc.paragraphs.isEmpty()) {
            scope.launch { snackbarHostState.showSnackbar("这本书没有可朗读的文字") }
            return
        }
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

    val percent by remember(document) {
        derivedStateOf {
            val total = document?.paragraphs?.size ?: 0
            if (total <= 0) 0 else ((listState.firstVisibleItemIndex + 1) * 100 / total).coerceIn(0, 100)
        }
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
                        Text(
                            text = "已读 " + percent + "%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(Spacing.xs))
                    }
                    if (outline.isNotEmpty()) {
                        IconButton(onClick = { tocOpen = true }) {
                            Icon(Icons.AutoMirrored.Outlined.List, contentDescription = "目录")
                        }
                    }
                    // 入口按"从哪开始念"来理解：已在朗读时再点一次是从当前可见段落重新起读，
                    // 而不是停止——控制条上已经有停止键，顶栏再放一个停止会让人以为朗读坏了。
                    TextButton(onClick = { startAloud() }) { Text("朗读") }
                    IconButton(onClick = { searchOpen = true }) {
                        Icon(Icons.Outlined.Search, contentDescription = "书内搜索")
                    }
                    IconButton(onClick = { settingsOpen = true }) {
                        Icon(Icons.Outlined.FormatSize, contentDescription = "阅读设置")
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
                )
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
            totalHitLimit = TextSearchEngine.DEFAULT_LIMIT,
            onQueryChange = { next ->
                searchQuery = next
                // 换词就撤掉上一次跳转的命中高亮：否则那一段会一直带着底色，像被永久标记了。
                activeHit = null
            },
            onPick = { picked ->
                activeHit = picked
                searchOpen = false
                // 同上：跳到命中处就停读，否则声音停留在一屏之外。
                stopAloud()
                // 段落是排版单位：滚到该段首（段内精确到字符需要 TextLayoutResult，收益不值这个复杂度），
                // 段内命中位置靠行内高亮标出来。
                scope.launch { listState.animateScrollToItem(picked.paragraphIndex) }
            },
            onDismiss = { searchOpen = false },
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
                TextParagraphView(
                    paragraph = paragraph,
                    baseSizeSp = sizeSp,
                    lineHeightSp = lineHeightSp,
                    first = index == 0,
                    highlightStart = hitStart,
                    highlightEnd = hitEnd,
                    speechStart = speechRangeStart,
                    speechEnd = speechRangeEnd,
                )
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
        hitBackground,
        hitForeground,
        speechBackground,
        speechForeground,
    ) {
        val hasHit = highlightStart in 0 until highlightEnd
        val hasSpeech = speechStart in 0 until speechEnd
        if (!hasHit && !hasSpeech) {
            AnnotatedString(text)
        } else {
            buildAnnotatedString {
                append(text)
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
            modifier = Modifier.padding(
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
            modifier = Modifier.padding(vertical = Spacing.xxs),
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

/** 字符偏移 → 段落下标：charOffsets 升序，取最后一个不超过 offset 的段。只在打开时算一次。 */
private fun paragraphIndexForOffset(document: TextDocument, offset: Int): Int {
    var index = 0
    for (i in document.charOffsets.indices) {
        if (document.charOffsets[i] <= offset) index = i else break
    }
    return index
}

/**
 * 打开文本类书籍：先按 BOM/编码猜解出字符串，再按格式选解析器。
 *
 * - TXT：空行分段、段内软换行折成空格；
 * - Markdown：空行分段、保留段内换行、识别 # 标题；
 * - FB2：交给 [Fb2Parser] 从 XML 里取出章节标题与正文段落，再经
 *   [TextDocumentParser.fromParagraphs] 统一切分与偏移计算；
 * - HTML：交给 [HtmlTextExtractor] 剥掉标签取出标题与段落，同样经 fromParagraphs 归一。
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
                document.paragraphs.map { TextParagraph(it.text, it.headingLevel) },
            )
        }

        BookFormat.Html -> TextDocumentParser.fromParagraphs(HtmlTextExtractor.extract(text))
        BookFormat.Markdown -> TextDocumentParser.parse(text, markdown = true)
        else -> TextDocumentParser.parse(text, markdown = false)
    }
}
