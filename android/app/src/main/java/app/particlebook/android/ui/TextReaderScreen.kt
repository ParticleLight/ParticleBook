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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.particlebook.android.reader.ReaderLocator
import app.particlebook.android.reader.text.TextDocument
import app.particlebook.android.reader.text.TextDocumentParser
import app.particlebook.android.reader.text.TextFileDecoder
import app.particlebook.android.reader.text.TextParagraph
import app.particlebook.android.ui.data.Book
import app.particlebook.android.ui.data.BookFormat
import app.particlebook.android.ui.data.ReadingProgress
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val markdown = book.format == BookFormat.Markdown

    var document by remember(book.filePath) { mutableStateOf<TextDocument?>(null) }
    var failure by remember(book.filePath) { mutableStateOf<String?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // 打开：读字节 → 解码（BOM/UTF-8/GB18030）→ 分段，整块放 IO，主线程只接结果。
    LaunchedEffect(book.filePath) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = File(book.filePath).readBytes()
                TextDocumentParser.parse(TextFileDecoder.decode(bytes).text, markdown)
            }
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
        topBar = {
            ReaderTopBar(
                title = book.title,
                onBack = onBack,
                actions = {
                    if (paragraphCount > 1) {
                        Text(
                            text = "已读 " + percent + "%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(Spacing.xs))
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
                    onBack = onBack,
                )

                current == null -> TextReaderLoading()

                current.isEmpty -> TextReaderMessage(
                    title = "这个文件没有文字",
                    detail = "《" + book.title + "》解出来是空文档，可能是空文件或编码无法识别。",
                    onBack = onBack,
                )

                else -> TextFlow(
                    document = current,
                    listState = listState,
                    fontSizeSp = fontSizeSp,
                    lineHeightMultiplier = lineHeightMultiplier,
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
}

/** 滚动停止多久后落一次进度。 */
private const val ProgressSaveDelayMillis = 600L

/** 正文最大行宽：平板上正文铺满会一行上千像素，读起来像海报。 */
private val TextColumnMaxWidth = 760.dp

/** 段落流：一段一项，段间靠内边距呼吸，不用分隔线（文本本来就没有格子）。 */
@Composable
private fun TextFlow(
    document: TextDocument,
    listState: LazyListState,
    fontSizeSp: Float,
    lineHeightMultiplier: Float,
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
                TextParagraphView(
                    paragraph = paragraph,
                    baseSizeSp = sizeSp,
                    lineHeightSp = lineHeightSp,
                    first = index == 0,
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
) {
    val level = paragraph.headingLevel
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
            text = paragraph.text,
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
            text = paragraph.text,
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
