package app.particlebook.android.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.particlebook.android.data.LibraryRuntime
import app.particlebook.android.data.ReadingSessionRecorder
import app.particlebook.android.reader.ReaderLocator
import app.particlebook.android.ui.about.AboutJson
import app.particlebook.android.ui.about.ChangelogScreen
import app.particlebook.android.ui.about.LicensesScreen
import app.particlebook.android.ui.data.Book
import app.particlebook.android.ui.data.BookFormat
import app.particlebook.android.ui.data.LibraryStore
import app.particlebook.android.ui.data.ReadingProgress
import app.particlebook.android.ui.discover.BooklistDetailScreen
import app.particlebook.android.ui.discover.BooklistsScreen
import app.particlebook.android.ui.discover.CategoriesScreen
import app.particlebook.android.ui.discover.CategoryListScreen
import app.particlebook.android.ui.discover.DiscoverRepositoryProvider
import app.particlebook.android.ui.discover.PopularScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 导航三个等权目的地：书架 / Z-Library / 设置。阅读器是全屏任务页，不在这里。 */
private enum class TopLevel(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    Library(
        label = "书架",
        icon = Icons.AutoMirrored.Outlined.MenuBook,
        selectedIcon = Icons.AutoMirrored.Filled.MenuBook,
    ),
    Discover(
        label = "Z-Library",
        icon = Icons.Outlined.Explore,
        selectedIcon = Icons.Filled.Explore,
    ),
    Settings(
        label = "设置",
        icon = Icons.Outlined.Settings,
        selectedIcon = Icons.Filled.Settings,
    ),
}

/**
 * 轻量持久化：主题、字号、行高、默认排序。
 *
 * 字号/行高从 P1 起走 [SettingsStore]（A023-1 的 37 键 schema + 旧键迁移）；
 * 主题与排序仍留在旧 pb_mobile_ui，因为 AppThemeMode 有 System、LibrarySort 有 Rating，
 * 都不在桌面 37 键的枚举里，强行映射会丢值。旧键 font_size/line_height 首次 load 时
 * 由 SettingsSchema 自动迁移，不会丢用户设置。
 */
private class AppPreferences(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("pb_mobile_ui", Context.MODE_PRIVATE)
    private val settings by lazy {
        app.particlebook.android.settings.SettingsStore(
            app.particlebook.android.settings.SharedPreferencesSettingsStorage.from(appContext),
        )
    }

    var themeMode: AppThemeMode
        get() = runCatching {
            AppThemeMode.valueOf(
                prefs.getString(KEY_THEME, AppThemeMode.System.name) ?: AppThemeMode.System.name,
            )
        }.getOrDefault(AppThemeMode.System)
        set(value) {
            prefs.edit().putString(KEY_THEME, value.name).apply()
        }

    var fontSize: Int
        get() = runCatching { settings.getInt("fontSize") }.getOrDefault(18)
        set(value) {
            runCatching { settings.set("fontSize", value) }
        }

    /** 正文行高倍数（1.3 / 1.5 / 1.7 / 2.0）。 */
    var lineHeight: Float
        get() = runCatching { settings.getDouble("lineHeight").toFloat() }.getOrDefault(1.7f)
        set(value) {
            runCatching { settings.set("lineHeight", value.toDouble()) }
        }

    /** 书架默认排序，存 LibrarySort.name。 */
    var defaultSort: String
        get() = prefs.getString(KEY_DEFAULT_SORT, LibrarySort.RecentRead.name)
            ?: LibrarySort.RecentRead.name
        set(value) {
            prefs.edit().putString(KEY_DEFAULT_SORT, value).apply()
        }

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_FONT_SIZE = "font_size"
        const val KEY_LINE_HEIGHT = "line_height"
        const val KEY_DEFAULT_SORT = "default_sort"
    }
}

private data class EpubOpenTiming(val bookId: Long, val startedAtNanos: Long)

/**
 * task-21：二级页导航条目。
 *
 * 旧实现把 detail 的 (hash, slug) 放在 AppRoot 全局状态里，栈内所有 detail 层共读同一份，
 * 于是「详情 A → 推荐 → 详情 B」后返回，底层 A 层也显示 B。现在 detail 条目自带参数，
 * 每层只读自己这一份，返回自然回到上一本书。
 *
 * 其余路由暂不携带参数（书单 href / 分类 id 仍走 AppRoot 级状态，等 search-ux 接阅读器路由时
 * 可按同一模式扩展）。
 */
internal data class NavEntry(
    val route: String,
    val hash: String? = null,
    val slug: String? = null,
)

/** 压栈：空白的 hash/slug 归一为 null，避免 detail 层出现空串参数。 */
internal fun List<NavEntry>.push(next: String, hash: String? = null, slug: String? = null): List<NavEntry> =
    this + NavEntry(
        route = next,
        hash = hash?.trim()?.takeIf { it.isNotEmpty() },
        slug = slug?.trim()?.takeIf { it.isNotEmpty() },
    )

/** 出栈；[expected] 非空时只在栈顶路由匹配时才执行（防迟到异步回调误弹），否则原样返回。 */
internal fun List<NavEntry>.pop(expected: String? = null): List<NavEntry> {
    if (isEmpty()) return this
    if (expected != null && last().route != expected) return this
    return dropLast(1)
}

/**
 * task-21：进程重建（rememberSaveable）时保留每层参数。
 * 扁平三元组 route/hash/slug 存字符串，不依赖 Bundle 之外的序列化；容量极小（栈深个位数）。
 */
internal val NavStackSaver: Saver<List<NavEntry>, Any> = listSaver(
    save = { entries -> entries.flatMap { listOf(it.route, it.hash.orEmpty(), it.slug.orEmpty()) } },
    restore = { saved ->
        saved.chunked(3)
            .filter { it.size == 3 }
            .map { parts ->
                NavEntry(
                    route = parts[0],
                    hash = parts[1].takeIf { value -> value.isNotEmpty() },
                    slug = parts[2].takeIf { value -> value.isNotEmpty() },
                )
            }
    },
)

/**
 * App 根：竖屏 = 悬浮胶囊导航（书架 / 设置 / 导入）；手机横屏 = 左侧 NavigationRail（A2 几何不变）。
 * 打开书籍时全屏进入阅读器（不带导航），返回时刷新 Hero 的「最近阅读」。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ParticleBookApp() {
    val context = LocalContext.current
    val preferences = remember { AppPreferences(context) }
    var themeModeName by rememberSaveable { mutableStateOf(preferences.themeMode.name) }
    val themeMode = AppThemeMode.entries.firstOrNull { it.name == themeModeName } ?: AppThemeMode.System
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    var openBookId by rememberSaveable { mutableLongStateOf(-1L) }
    // 路由可以恢复，用户点击的计时只属于当前打开会话。
    var epubOpenTiming by remember { mutableStateOf<EpubOpenTiming?>(null) }
    var fontSize by rememberSaveable { mutableIntStateOf(preferences.fontSize) }
    var lineHeight by rememberSaveable { mutableFloatStateOf(preferences.lineHeight) }
    var defaultSortName by rememberSaveable { mutableStateOf(preferences.defaultSort) }
    // 书架显示时订阅进度快照；阅读期间暂停订阅，避免隐藏书架随每次翻页重组。
    var progressEpoch by remember { mutableIntStateOf(0) }
    var progressReadyBookIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var progressReady by remember { mutableStateOf(false) }
    var progressLoadFailed by remember { mutableStateOf(false) }
    var progressRetry by remember { mutableIntStateOf(0) }
    var readerGeneration by remember { mutableLongStateOf(0L) }
    // 全文搜索命中位置：随本次打开一起交给阅读器，关闭阅读器即清空（重开同一本书不回到命中处）。
    var pendingJumpLocator by remember { mutableStateOf<Pair<Long, String>?>(null) }
    // 全文搜索覆盖层：压在 tab 之上、阅读器之下；命中后随开书一起关闭。
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    // 阅读统计 / 关于组覆盖层：同一层级，互斥使用（设置页一次只会点开一个）。
    var statsOpen by rememberSaveable { mutableStateOf(false) }
    var changelogOpen by rememberSaveable { mutableStateOf(false) }
    var licensesOpen by rememberSaveable { mutableStateOf(false) }
    // 书源管理：与统计/关于三页同构的全屏覆盖层，持有自己的探测状态。
    var zlibSourcesOpen by rememberSaveable { mutableStateOf(false) }
    // 书籍详情弹层：只记 id，数据始终从 store.books 现取（评分改完立刻回显）。
    var detailBookId by rememberSaveable { mutableStateOf<Long?>(null) }
    val defaultSort = LibrarySort.entries.firstOrNull { it.name == defaultSortName }
        ?: LibrarySort.RecentRead

    val store = remember { LibraryStore(context) }
    LaunchedEffect(Unit) { store.ensureLoaded() }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Z-Library P1b：账号状态、全屏二级页（登录 / 详情 / 下载记录）、下载卡片。
    val appContext = context.applicationContext
    val accountViewModel = remember(scope, appContext) {
        ZlibAccountViewModel(scope, appContext)
    }
    // P1.5 发现面：数据源由 discover 包提供（zlib 落地前是“接入中”占位，不造假数据）。
    val discoverRepository = remember(appContext) { DiscoverRepositoryProvider.create(appContext) }
    // 跨书全文搜索：查的是 search_documents（书被打开过一次后由 EpubSearchIndexing 建索引）。
    val searchViewModel = remember(scope, appContext) {
        SearchViewModel(scope, { LibraryRuntime.repository(appContext) })
    }
    // 阅读统计：页码只在 ReadingProgress 的落库路径汇总，记录器订阅那一个观察点，
    // 三个阅读器都不用改。会话随「打开/关闭书」开始与结束，期间每 60s 落一段，
    // 进程被杀最多丢一分钟；写库一律离开主线程（仓库契约是同步的）。
    val sessionRecorder = remember(appContext) {
        ReadingSessionRecorder(repositoryProvider = { LibraryRuntime.repository(appContext) })
    }
    DisposableEffect(sessionRecorder) {
        ReadingProgress.pageObserver = { _, page -> sessionRecorder.onPageChanged(page) }
        onDispose {
            ReadingProgress.pageObserver = null
            sessionRecorder.cancel()
        }
    }
    val statsViewModel = remember(scope, appContext) {
        StatsViewModel(scope, { LibraryRuntime.repository(appContext) })
    }
    // 只在覆盖层打开时重算：VM 在应用启动时就构造过一次（那时还没有会话行），
    // 不刷新会一直显示打开应用那一刻的空态。
    LaunchedEffect(statsOpen) {
        if (statsOpen) statsViewModel.refresh()
    }
    LaunchedEffect(appContext) { ZlibDownloadManager.init(appContext) }
    // P1.5 §11.7：二级页返回栈。空 = 主 tab 外壳；push/pop 只增删栈顶，
    // 栈内页面全部保持组合（见下方渲染），因此返回不重建父页、滚动位置/已加载页/展开态都不丢。
    var navStack by rememberSaveable(stateSaver = NavStackSaver) {
        mutableStateOf<List<NavEntry>>(emptyList())
    }
    var booklistHref by rememberSaveable { mutableStateOf("") }
    var categoryId by rememberSaveable { mutableStateOf("") }
    var categorySlug by rememberSaveable { mutableStateOf("") }
    var categoryName by rememberSaveable { mutableStateOf("") }

    // U5：登录返程。登录页压栈时记录「来源栈顶」（空串 = 主 tab）并递增会话号；
    // 异步登录成功回调只在「会话未换代 + login 仍是栈顶」时弹栈，用户已手动返回或
    // 重新进入登录页时只更新账号状态、绝不覆盖当前导航。
    var loginSession by rememberSaveable { mutableLongStateOf(0L) }
    var loginReturnTo by rememberSaveable { mutableStateOf("") }

    /** [hash]/[slug] 仅 detail 路由使用（task-21）：参数跟着栈条目走，不落全局状态。 */
    fun pushRoute(next: String, hash: String? = null, slug: String? = null) {
        navStack = navStack.push(next, hash, slug)
    }

    /**
     * 出栈。
     *
     * [expected] 非空时只在栈顶等于该路由时才弹栈，防止“迟到”的异步回调误伤下层页面：
     * 例如登录请求未完成时用户先返回（login 已出栈、detail 变成栈顶），随后登录成功
     * 回调再 pop，就会把 detail 也弹掉。异步回调一律传 expected = "login"。
     */
    fun popRoute(expected: String? = null) {
        if (navStack.isEmpty()) return
        if (expected != null && navStack.last().route != expected) return
        val removed = navStack.last()
        navStack = navStack.pop(expected)
        // U5：登录页真正出栈（返回键/系统返回/成功回调）即作废返程快照。
        if (removed.route == "login") loginReturnTo = ""
    }

    /** 登录是模态任务页：压栈，成功后 pop 回来源页（详情/发现页/主 tab 都能原样恢复）。 */
    val openLogin: () -> Unit = {
        loginSession += 1L
        loginReturnTo = navStack.lastOrNull()?.route.orEmpty()
        pushRoute("login")
    }
    val books: List<Book> = store.books
    val openBook = remember(books, openBookId) { books.firstOrNull { it.id == openBookId } }
    val annotationStore = remember(appContext) { LibraryRuntime.lazyAnnotationStore(appContext) }

    DisposableEffect(openBook?.id) {
        val book = openBook
        if (book != null) {
            sessionRecorder.start(book.id, ReadingProgress.savedPage(appContext, book.id))
        }
        onDispose {
            if (book != null) {
                scope.launch(Dispatchers.IO) { sessionRecorder.stop() }
            }
        }
    }
    LaunchedEffect(openBook?.id) {
        if (openBook == null) return@LaunchedEffect
        while (true) {
            delay(60_000L)
            withContext(Dispatchers.IO) { sessionRecorder.flushIfDue() }
        }
    }

    LaunchedEffect(books, store.loading, progressRetry) {
        if (store.loading) return@LaunchedEffect
        progressLoadFailed = false
        val initialized = ReadingProgress.initialize(appContext) { store.books }
        if (initialized) {
            progressReadyBookIds = books.map { it.id }.toSet()
            progressReady = true
        }
        progressLoadFailed = !initialized
    }
    LaunchedEffect(openBookId) {
        if (openBookId >= 0L) return@LaunchedEffect
        ReadingProgress.snapshots.collect { progressEpoch += 1 }
    }

    // 多选状态只在会话内有效（书源本身也还没有持久化）。
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var deleteDialogOpen by remember { mutableStateOf(false) }

    fun openReader(book: Book, fromBookClick: Boolean) {
        readerGeneration += 1L
        epubOpenTiming = if (fromBookClick && book.format == BookFormat.Epub) {
            EpubOpenTiming(book.id, System.nanoTime())
        } else {
            null
        }
        openBookId = book.id
    }

    fun closeReader() {
        epubOpenTiming = null
        pendingJumpLocator = null
        openBookId = -1L
    }

    LaunchedEffect(books, store.loading, openBookId) {
        if (!store.loading && openBookId >= 0L && openBook == null) closeReader()
    }

    fun exitSelection() {
        selectionMode = false
        selectedIds = emptySet()
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            store.importDocument(uri)
                .onSuccess { outcome ->
                    val book = outcome.book
                    // task-70：重复导入（同 URI / 同内容）命中已有条目时提示「已在书架」并直接跳过去。
                    if (outcome.alreadyInLibrary) {
                        snackbarHostState.showSnackbar("已在书架：《" + book.title + "》")
                        openReader(book, fromBookClick = false)
                    } else {
                        snackbarHostState.showSnackbar("已导入《" + book.title + "》")
                        if (book.format == BookFormat.Pdf) openReader(book, fromBookClick = false)
                    }
                }
                .onFailure { throwable ->
                    snackbarHostState.showSnackbar("导入失败：" + (throwable.message ?: "未知错误"))
                }
        }
    }
    val launchImport: () -> Unit = {
        importLauncher.launch(
            arrayOf("application/pdf", "application/epub+zip", "application/octet-stream"),
        )
    }

    val confirmDelete: () -> Unit = {
        val ids = selectedIds
        deleteDialogOpen = false
        scope.launch {
            store.removeBooks(ids)
                .onSuccess { removedCount ->
                    exitSelection()
                    snackbarHostState.showSnackbar("已删除 " + removedCount + " 本书")
                }
                .onFailure {
                    // 写索引失败：内存/磁盘都没变，保留选中态让用户可以直接重试。
                    snackbarHostState.showSnackbar("删除失败，请重试")
                }
        }
    }

    BackHandler(enabled = openBook == null && selectionMode) { exitSelection() }
    // 返回 = 弹栈：书单详情 → 书单列表 → 发现页 / 书架，父页状态原样保留。
    BackHandler(enabled = openBook == null && navStack.isNotEmpty()) { popRoute() }
    BackHandler(enabled = openBook == null && navStack.isEmpty() && !selectionMode && tabIndex != 0) {
        tabIndex = 0
    }

    val reduceMotion = Motion.isReducedMotion(LocalContext.current)

    ParticleBookTheme(mode = themeMode) {
        Box(modifier = Modifier.fillMaxSize()) {
        val configuration = LocalConfiguration.current
        // 914×411dp 的手机横屏命中；竖屏、以及高度 ≥480dp 的平板横屏都不命中。
        val compactLandscape =
            configuration.screenWidthDp >= 600 && configuration.screenHeightDp < 480

        // 主 tab 外壳永远保持组合：二级页与阅读器都是叠层，返回时书架/发现页/设置页的
        // 滚动位置、搜索词、选中态原样保留（§11.7 页面状态保持）。
        AppShell(
            compactLandscape = compactLandscape,
            tabIndex = tabIndex,
            onTabSelected = { tabIndex = it },
            snackbarHostState = snackbarHostState,
            selectionMode = selectionMode,
            onDelete = { deleteDialogOpen = true },
            onBookInfo = selectedIds.singleOrNull()?.let { id ->
                { detailBookId = id }
            },
        ) {
            when (tabIndex) {
                0 -> LibraryScreen(
                    books = books,
                    loading = store.loading,
                    error = store.error,
                    selectionMode = selectionMode,
                    selectedIds = selectedIds,
                    onOpenBook = { book ->
                        openReader(book, fromBookClick = true)
                        exitSelection()
                    },
                    onEnterSelection = { book ->
                        selectionMode = true
                        selectedIds = setOf(book.id)
                    },
                    onToggleSelection = { book ->
                        val next = if (book.id in selectedIds) {
                            selectedIds - book.id
                        } else {
                            selectedIds + book.id
                        }
                        if (next.isEmpty()) {
                            exitSelection()
                        } else {
                            selectedIds = next
                        }
                    },
                    onSelectAll = { selectedIds = books.map { it.id }.toSet() },
                    onExitSelection = { exitSelection() },
                    onStartSelectionMode = {
                        selectionMode = true
                        selectedIds = emptySet()
                    },
                    onImportClick = launchImport,
                    onCrossBookSearch = { searchOpen = true },
                    onReadingStatsClick = { statsOpen = true },
                    onDownloadsClick = {
                        // 下载历史是 Z-Library tab 的二级页：切过去再压栈，书架不留覆盖层。
                        tabIndex = 1
                        pushRoute("downloads")
                    },
                    onRetry = { scope.launch { store.reload() } },
                    defaultSort = defaultSort,
                    progressEpoch = progressEpoch,
                )

                1 -> DiscoverScreen(
                    account = accountViewModel.account,
                    onLoginClick = { openLogin() },
                    onLogout = { accountViewModel.logout() },
                    onDownloadsClick = { pushRoute("downloads") },
                    onOpenDetail = { hash, slug -> pushRoute("detail", hash, slug) },
                    onBooklistsClick = { pushRoute("booklists") },
                    onPopularClick = { pushRoute("popular") },
                    onCategoriesClick = { pushRoute("categories") },
                )

                else -> SettingsScreen(
                    themeMode = themeMode,
                    onThemeModeChange = { mode ->
                        themeModeName = mode.name
                        preferences.themeMode = mode
                    },
                    fontSize = fontSize,
                    onFontSizeChange = { size ->
                        fontSize = size
                        preferences.fontSize = size
                    },
                    lineHeight = lineHeight,
                    onLineHeightChange = { value ->
                        lineHeight = value
                        preferences.lineHeight = value
                    },
                    defaultSort = defaultSort,
                    onDefaultSortChange = { option ->
                        defaultSortName = option.name
                        preferences.defaultSort = option.name
                    },
                    onImportClick = launchImport,
                    onClearReadingRecords = {
                        scope.launch {
                            // 真清数据：progress 表 + reading_sessions 表（失败保留快照，可直接重试）。
                            val cleared = withContext(Dispatchers.IO) {
                                runCatching { ReadingProgress.clearAll(appContext) }.getOrDefault(false)
                            }
                            if (cleared) {
                                // 书架进度条与统计页都读同一批数据：清完立刻重算，别等下次打开。
                                progressEpoch += 1
                                statsViewModel.refresh()
                                snackbarHostState.showSnackbar("已清除所有阅读记录")
                            } else {
                                snackbarHostState.showSnackbar("清除失败，阅读记录未改动")
                            }
                        }
                    },
                    account = accountViewModel.account,
                    onLoginClick = { openLogin() },
                    onLogout = { accountViewModel.logout() },
                    onDownloadsClick = { pushRoute("downloads") },
                    onReadingStatsClick = { statsOpen = true },
                    onChangelogClick = { changelogOpen = true },
                    onLicensesClick = { licensesOpen = true },
                    onZlibSourcesClick = { zlibSourcesOpen = true },
                    onSettingsRestored = {
                        // 设置恢复只覆盖 37 个全局键；字号/行高在 AppRoot 里另有
                        // rememberSaveable 状态，必须从 SettingsStore 重新读取。
                        fontSize = preferences.fontSize
                        lineHeight = preferences.lineHeight
                    },
                )
            }
        }

        // P1.5 二级页：整条返回栈都保持组合（父页在栈里不会离开组合），最上层不透明背景截住输入。
        // 返回 = popRoute()，父页的 ViewModel / LazyListState / 展开态自然原样保留。
        for (index in navStack.indices) {
            val entry = navStack[index]
            val stackRoute = entry.route
            // P2（p15-code-audit §1）：只有栈顶是活跃层。非栈顶层仍然保持组合
            // （ViewModel / LazyListState / 已加载页原样保留），但被压成 0 约束：
            // 隐藏期间不做常规布局、不组合列表可见项，因此不会触发列表自动翻页、
            // 懒加载封面等由布局驱动的副作用。回到栈顶时恢复全屏约束。
            val isActiveLayer = index == navStack.lastIndex
            key(index, stackRoute) {
                Box(
                    modifier = Modifier
                        .then(if (isActiveLayer) Modifier.fillMaxSize() else Modifier.size(0.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .pointerInput(stackRoute) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent()
                                }
                            }
                        },
                ) {
                    when (stackRoute) {
                        "login" -> {
                            // U5：记下这一层登录页所属会话与来源快照，迟到成功回调不跨会话/跨导航。
                            val openSession = remember { loginSession }
                            val returnTo = remember { loginReturnTo }
                            ZlibLoginScreen(
                                viewModel = accountViewModel,
                                onBack = { popRoute() },
                                onLoginSuccess = {
                                    if (
                                        canCompleteLoginReturn(
                                            openSession,
                                            loginSession,
                                            navStack.map { it.route },
                                            returnTo,
                                        )
                                    ) {
                                        popRoute(expected = "login")
                                    } else {
                                        // 用户已返回/已重新登录：只保留账号状态更新，不动导航。
                                        loginReturnTo = ""
                                    }
                                },
                            )
                        }

                        "detail" -> {
                            // task-21：每层详情独立 ViewModel —— 共享 ViewModel 时，A 层隐藏期间
                            // 会被 B 层的加载结果覆盖，返回 A 仍显示 B。参数也从本层条目取。
                            val layerScope = rememberCoroutineScope()
                            val layerViewModel = remember(layerScope) {
                                ZlibDetailViewModel(layerScope, appContext)
                            }
                            ZlibDetailScreen(
                                viewModel = layerViewModel,
                                store = store,
                                hash = entry.hash.orEmpty(),
                                slug = entry.slug?.takeIf { it.isNotBlank() },
                                // task-58：账号状态从 Observable 的账号 ViewModel 传入，
                                // 登录/退出后详情层随重组立即刷新按钮，不再组合期同步读 prefs；
                                // accountKey 让换账号时重建在线阅读入口状态，不复用旧会话票据。
                                loggedIn = accountViewModel.account != null,
                                accountKey = accountViewModel.account?.email
                                    ?: accountViewModel.account?.name,
                                onBack = { popRoute() },
                                onOpenBook = { book -> openReader(book, fromBookClick = true) },
                                onRequireLogin = { openLogin() },
                                // A12 + task-21：推荐卡压入新的 detail 条目（带新 hash/slug），
                                // 返回时弹掉本层，自然回到上一本书。
                                onOpenDetail = { hash, slug -> pushRoute("detail", hash, slug) },
                            )
                        }

                        "downloads" -> ZlibDownloadsScreen(
                            store = store,
                            onBack = { popRoute() },
                            onOpenBook = { book -> openReader(book, fromBookClick = true) },
                            // D1 A-lite：默认下载目录无法被系统文件管理器直达，压入应用内文件列表页。
                            onOpenDownloadFolder = { pushRoute("downloadFolder") },
                        )

                        // D1 A-lite：应用内下载文件夹浏览器（默认目录）。SAF 自定义目录不经过这里。
                        "downloadFolder" -> ZlibDownloadFolderScreen(
                            store = store,
                            onBack = { popRoute() },
                            onOpenBook = { book -> openReader(book, fromBookClick = true) },
                            // 下载目录切换仍在设置页：清空二级栈回到主 tab，避免设置页被压在下载页下面。
                            onOpenSettings = {
                                navStack = emptyList()
                                tabIndex = 2
                            },
                        )

                        // P1.5 发现面（书单 / 榜单 / 分类）：返回回父页，不跳级、不重建。
                        "booklists" -> BooklistsScreen(
                            repository = discoverRepository,
                            onBack = { popRoute() },
                            onOpenBooklist = { href ->
                                booklistHref = href
                                pushRoute("booklist")
                            },
                        )

                        "booklist" -> BooklistDetailScreen(
                            repository = discoverRepository,
                            href = booklistHref,
                            onBack = { popRoute() },
                            onOpenBook = { hash, slug -> pushRoute("detail", hash, slug) },
                        )

                        "popular" -> PopularScreen(
                            repository = discoverRepository,
                            onBack = { popRoute() },
                            onOpenBook = { hash, slug -> pushRoute("detail", hash, slug) },
                        )

                        "categories" -> CategoriesScreen(
                            repository = discoverRepository,
                            onBack = { popRoute() },
                            onOpenCategory = { id, slug, name ->
                                categoryId = id
                                categorySlug = slug
                                categoryName = name
                                pushRoute("categoryList")
                            },
                        )

                        "categoryList" -> CategoryListScreen(
                            repository = discoverRepository,
                            categoryId = categoryId,
                            slug = categorySlug,
                            name = categoryName,
                            onBack = { popRoute() },
                            onOpenBook = { hash, slug -> pushRoute("detail", hash, slug) },
                        )

                        else -> Unit
                    }
                }
            }
        }

        // 全文搜索：全屏覆盖层。命中后把 locator 交给阅读器（pendingJumpLocator），
        // 搜索层自身不持有业务状态，关闭即清词，重开不会看到上一轮的残留结果。
        if (searchOpen) {
            val closeSearch: () -> Unit = {
                searchOpen = false
                searchViewModel.updateQuery("")
                searchViewModel.dispose()
            }
            BackHandler(onBack = closeSearch)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent()
                            }
                        }
                    },
            ) {
                SearchScreen(
                    state = searchViewModel.state,
                    onQueryChange = searchViewModel::updateQuery,
                    onBack = closeSearch,
                    onOpenHit = { hit ->
                        val target = books.firstOrNull { it.id == hit.bookId }
                        if (target == null) {
                            scope.launch { snackbarHostState.showSnackbar("这本书已经不在书架里") }
                        } else {
                            pendingJumpLocator = target.id to hit.locator
                            closeSearch()
                            openReader(target, fromBookClick = false)
                        }
                    },
                )
            }
        }

        // 阅读统计 / 更新日志 / 开源许可：三个全屏覆盖层，与搜索层同构（不透明背景 + 拦截输入 +
        // BackHandler），关闭即从组合移除，下次打开重新读资产与统计。
        if (statsOpen) {
            val closeStats: () -> Unit = { statsOpen = false }
            BackHandler(onBack = closeStats)
            FullScreenOverlay(onDismiss = closeStats) {
                StatsScreen(
                    state = statsViewModel.state,
                    onBack = closeStats,
                    onRetry = statsViewModel::refresh,
                )
            }
        }
        if (changelogOpen) {
            val closeChangelog: () -> Unit = { changelogOpen = false }
            val entries = remember(appContext) {
                AboutJson.parseChangelog(readAsset(appContext, "about/changelog.json"))
            }
            val versionName = remember(appContext) {
                runCatching {
                    appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
                }.getOrNull().orEmpty()
            }
            BackHandler(onBack = closeChangelog)
            FullScreenOverlay(onDismiss = closeChangelog) {
                ChangelogScreen(
                    entries = entries,
                    currentVersion = versionName,
                    onBack = closeChangelog,
                )
            }
        }
        if (licensesOpen) {
            val closeLicenses: () -> Unit = { licensesOpen = false }
            val entries = remember(appContext) {
                AboutJson.parseLicenses(readAsset(appContext, "about/licenses.json"))
            }
            BackHandler(onBack = closeLicenses)
            FullScreenOverlay(onDismiss = closeLicenses) {
                LicensesScreen(entries = entries, onBack = closeLicenses)
            }
        }

        if (zlibSourcesOpen) {
            val closeSources: () -> Unit = { zlibSourcesOpen = false }
            BackHandler(onBack = closeSources)
            FullScreenOverlay(onDismiss = closeSources) {
                ZlibSourcesScreen(onBack = closeSources)
            }
        }

        // 书籍详情：从书架多选底栏的「书籍信息」进入；只对当前 books 里的活条目渲染，
        // 删除或数据重载后自动消失（不额外维护副本）。
        val detailBook = detailBookId?.let { id -> books.firstOrNull { it.id == id } }
        if (detailBook != null) {
            val closeDetail: () -> Unit = { detailBookId = null }
            BackHandler(onBack = closeDetail)
            val progressLabel = remember(detailBook.id, progressEpoch) {
                ReadingProgress.percentLabel(appContext, detailBook.id)?.takeIf { it.isNotBlank() }
            }
            BookDetailSheet(
                book = detailBook,
                progressLabel = progressLabel,
                onRatingChange = { rating ->
                    // 写索引是 suspend + IO：评分失败只回滚到旧值（store 不提交内存），这里不伪造成功。
                    scope.launch { store.setRating(detailBook.id, rating) }
                },
                onDismiss = closeDetail,
            )
        }

        // 阅读器：全屏覆盖层（主外壳 + 返回栈保持组合）。用 AnimatedContent 的 target 值兜住
        // 退出动画期间的最后一本书，避免退回书架时内容闪空。
        AnimatedContent(
            targetState = openBook,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                // 无障碍：系统移除动画时只做瞬时切换，不做缩放/位移。
                // 用 EnterTransition.None / ExitTransition.None 表达「零时长」，
                // 不写 tween(0)：时长字面量属于动效 token 的地盘（Motion.kt）。
                if (reduceMotion) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else if (targetState != null) {
                    // 书架 ↔ 阅读器：缩放 + 淡入淡出（SharedTransitionLayout 没有稳定 API，spec 允许的降级方案）。
                    (fadeIn(Motion.enterFade<Float>()) +
                        scaleIn(initialScale = 0.94f, animationSpec = Motion.enterSpatial<Float>()))
                        .togetherWith(fadeOut(Motion.exitFade<Float>()))
                } else {
                    fadeIn(Motion.enterFade<Float>())
                        .togetherWith(
                            fadeOut(Motion.exitFade<Float>()) +
                                scaleOut(targetScale = 0.96f, animationSpec = Motion.exitSpatial<Float>()),
                        )
                }
            },
            label = "shelfToReader",
        ) { readingBook ->
            if (readingBook != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent()
                                }
                            }
                        },
                ) {
                    if (progressReady && readingBook.id in progressReadyBookIds) {
                        ReaderScreen(
                            book = readingBook,
                            annotationStore = annotationStore,
                            initialLocator = pendingJumpLocator
                                ?.takeIf { it.first == readingBook.id }
                                ?.let { ReaderLocator.decode(it.second) },
                            openStartedAtNanos = epubOpenTiming?.takeIf { it.bookId == readingBook.id }?.startedAtNanos,
                            onBack = {
                                val closingGeneration = readerGeneration
                                scope.launch {
                                    val saved = ReadingProgress.flush()
                                    if (openBookId == readingBook.id && readerGeneration == closingGeneration) {
                                        closeReader()
                                        progressEpoch += 1
                                        if (!saved) {
                                            snackbarHostState.showSnackbar("阅读进度尚未保存，稍后自动重试")
                                        }
                                    }
                                }
                            },
                        )
                    } else {
                        BackHandler { closeReader() }
                        Column(
                            modifier = Modifier.align(Alignment.Center).padding(Spacing.lg),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(Spacing.md),
                        ) {
                            if (progressLoadFailed) {
                                Text("阅读记录加载失败", style = MaterialTheme.typography.titleMedium)
                                Text("请重试以恢复上次的阅读位置", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                FilledTonalButton(onClick = { progressRetry += 1 }) { Text("重试") }
                            } else {
                                LoadingIndicator()
                                Text("正在恢复阅读位置…", style = MaterialTheme.typography.bodyMedium)
                            }
                            TextButton(onClick = { closeReader() }) { Text("返回书架") }
                        }
                    }
                }
            } else {
                Box(Modifier.fillMaxSize())
            }
        }

        // R5-d / P1c / U6：同一次下载失败只在一个位置表达，避免「页内错误 + 全局卡片」同屏。
        // §11.6 + U6 冻结：全局下载卡片只在主 tab（书架/发现/设置，navStack 为空）渲染。
        // 二级页（登录/详情/发现面/下载记录等）各自有内联状态；阅读器是全屏沉浸页也不渲染。
        // 主 tab 上最多显示 2 张卡片，其余收进「还有 N 个 · 查看全部」进下载记录页（U6）。
        if (navStack.isEmpty() && openBook == null) {
            // U7：下载任务订阅下移到卡片子树，根节点不再 collect tasks（150ms 进度更新
            // 只重组这里，不重建整个 AppShell / navStack / 阅读器树）。
            ZlibDownloadCardsHost(
                store = store,
                onLogin = openLogin,
                onViewAll = { pushRoute("downloads") },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        }
    }

    if (deleteDialogOpen) {
        ParticleBookTheme(mode = themeMode) {
            AlertDialog(
                onDismissRequest = { deleteDialogOpen = false },
                title = { Text("删除 " + selectedIds.size + " 本书？") },
                text = { Text("会从书架移除，不会删除手机上的文件。此操作不可撤销。") },
                confirmButton = {
                    TextButton(onClick = confirmDelete) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteDialogOpen = false }) { Text("取消") }
                },
            )
        }
    }
}

/**
 * U7：下载卡片宿主。任务流只在这个子树里 collect —— 进度刷新只重组本 composable，
 * 不再把下载列表作为 AppRoot 的参数，根节点的重组范围收敛到「卡片出现/消失」。
 */
@Composable
private fun ZlibDownloadCardsHost(
    store: LibraryStore,
    onLogin: () -> Unit,
    onViewAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tasks by ZlibDownloadManager.tasks.collectAsState()
    if (tasks.isEmpty()) return
    // P1c P2-5：token 是「距屏底总量」，与胶囊上沿（124/132dp）留 16/12dp；
    // 不再叠 navigationBarsPadding()（旧实现实际 176dp，与胶囊间距 52dp）。
    val bottomPadding = if (LocalDensity.current.fontScale >= 1.3f) {
        Spacing.listBottomInsetLarge + Spacing.sm
    } else {
        Spacing.listBottomInset + Spacing.sm
    }
    ZlibDownloadCardsOverlay(
        tasks = tasks,
        onCancel = { id -> ZlibDownloadManager.cancel(id) },
        onRetry = { id -> ZlibDownloadManager.retry(id, store) },
        onDismiss = { id -> ZlibDownloadManager.dismiss(id) },
        onDiscard = { id -> ZlibDownloadManager.discard(id) },
        onReimport = { id -> ZlibDownloadManager.reimport(id, store) },
        onLogin = onLogin,
        onViewAll = onViewAll,
        modifier = modifier.padding(bottom = bottomPadding),
    )
}

/**
 * 导航外壳：
 * - 竖屏：内容全屏 + 底部悬浮胶囊 [NavCapsule]（288×64dp 三等分 / 距底 insets+12dp）；
 * - 手机横屏：左侧 NavigationRail 80dp（A2 几何不变，无底部胶囊）。
 * 多选时底部换成 SelectionBar（横屏 rail 仍显示）。
 */
@Composable
private fun AppShell(
    compactLandscape: Boolean,
    tabIndex: Int,
    onTabSelected: (Int) -> Unit,
    snackbarHostState: SnackbarHostState,
    selectionMode: Boolean,
    onDelete: () -> Unit,
    /** 单选时的「书籍信息」动作；多选或空选传 null（按钮禁用，不隐藏，避免底栏宽度跳动）。 */
    onBookInfo: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    if (compactLandscape) {
        // 整个区域先避让右侧系统栏（横屏三键导航是右侧竖条）：顶栏 / FAB 都在这个 Row 里，
        // 这样 FAB 不会被导航条压住；insets 在这里被消费，子级不会重复加内边距。
        Row(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End)),
        ) {
            NavigationRail(modifier = Modifier.fillMaxHeight()) {
                TopLevel.entries.forEachIndexed { index, destination ->
                    NavigationRailItem(
                        selected = tabIndex == index,
                        onClick = { onTabSelected(index) },
                        icon = {
                            Icon(
                                imageVector = if (tabIndex == index) {
                                    destination.selectedIcon
                                } else {
                                    destination.icon
                                },
                                contentDescription = null,
                            )
                        },
                        label = {
                            Text(
                                text = destination.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
            Scaffold(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                // 横屏 rail 布局没有悬浮胶囊：Snackbar 只避让系统导航条 + 16dp。
                snackbarHost = { AppSnackbarHost(snackbarHostState, aboveCapsule = false) },
                bottomBar = {
                    if (selectionMode) SelectionBar(onDelete = onDelete, onBookInfo = onBookInfo)
                },
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = innerPadding.calculateBottomPadding()),
                ) {
                    content()
                }
            }
        }
    } else {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.surface,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            // 竖屏主 tab 有悬浮胶囊：Snackbar 底边落在胶囊上沿 + 8dp（132/140dp，已含导航条）。
            snackbarHost = { AppSnackbarHost(snackbarHostState, aboveCapsule = true) },
            bottomBar = {
                if (selectionMode) SelectionBar(onDelete = onDelete, onBookInfo = onBookInfo)
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = innerPadding.calculateBottomPadding())
                    // 横屏时系统三键导航在屏幕右侧：只避让左右安全区，底部由胶囊自己处理。
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
                    ),
            ) {
                content()
                if (!selectionMode) {
                    NavCapsule(
                        tabIndex = tabIndex,
                        onTabSelected = onTabSelected,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = Spacing.sm),
                    )
                }
            }
        }
    }
}

/**
 * 悬浮胶囊底栏（§17.2.4 / R5）：288×64dp 三等分（88dp/项），无导入圆钮、无竖分隔。
 * full 圆角、surfaceContainerHigh@92% + 1dp 白 12% 描边 + 柔和投影；选中项为 primaryContainer 药丸。
 */
@Composable
private fun NavCapsule(
    tabIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = CircleShape
    // 200% 字号：胶囊全宽（288dp 不够分），高 72dp（V7 允许）；图标在上、标签在下，避免「Z-Libr…」截断。
    val largeFont = LocalDensity.current.fontScale >= 1.3f
    val widthModifier = if (largeFont) {
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md)
    } else {
        Modifier.width(Spacing.navCapsuleWidth)
    }
    // 72dp = 48dp 命中区 + 24dp 两行排布余量；64dp 是两行排布下的单项高度。
    val capsuleHeight = if (largeFont) Spacing.minTouchTarget + Spacing.lg else Spacing.navCapsuleHeight
    val itemHeight = if (largeFont) Spacing.minTouchTarget + Spacing.md else Spacing.minTouchTarget
    Surface(
        modifier = modifier
            .then(widthModifier)
            .height(capsuleHeight)
            .floatingShadow(shape),
        shape = shape,
        // P1-1：92% 半透明会让下方文字透出（对比 ≈1.9:1）→ 提到 98%，观感接近实底但仍带一点层次。
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.98f),
        // 描边走 outlineVariant：白色描边在浅色方案里等于隐形，深浅两套都要有轮廓。
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopLevel.entries.forEachIndexed { index, destination ->
                val selected = tabIndex == index
                val contentColor = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                val itemModifier = Modifier
                    .weight(1f)
                    .height(itemHeight)
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    )
                    .selectable(
                        selected = selected,
                        role = Role.Tab,
                        onClick = { onTabSelected(index) },
                    )
                    .padding(horizontal = 2.dp)
                if (largeFont) {
                    Column(
                        modifier = itemModifier,
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = if (selected) destination.selectedIcon else destination.icon,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = destination.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                } else {
                    Row(
                        modifier = itemModifier,
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (selected) destination.selectedIcon else destination.icon,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = destination.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = contentColor,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 全屏覆盖层容器：不透明背景 + 吞掉所有指针事件。
 *
 * 只做「盖住 + 拦住」，不持有业务状态：统计 / 更新日志 / 开源许可三页共用，
 * 关闭即从组合移除，下次打开重新读资产与统计（与搜索层同构）。
 */
@Composable
private fun FullScreenOverlay(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(onDismiss) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent()
                    }
                }
            },
    ) {
        content()
    }
}

/** 读 assets 里的关于页数据；缺失或读失败返回空串，解析层按空表处理，页面走空态。 */
private fun readAsset(context: Context, path: String): String = runCatching {
    context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
}.getOrNull().orEmpty()

/**
 * 多选底栏，按 2026-11 的 docked toolbar 形态实现：baseline BottomAppBar 已废弃
 * （docs/android-port/m3/README.md §4），这里用 Surface + Row 保留同样的容器色调、
 * 最小触控高度与左右 16dp 内边距；窗口 insets 仍由外层 Scaffold 统一消费。
 *
 * 动作只有两个真功能：**书籍信息**（单选时可用，打开详情/评分弹层）与**删除**（主操作，
 * 放在行尾远离左侧拇指起点）。早前的「加入书柜 / 更多」是无对应模型的禁用占位，已移除。
 */
@Composable
private fun SelectionBar(onDelete: () -> Unit, onBookInfo: (() -> Unit)?) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 关键：Scaffold 的 contentWindowInsets 为 0（仅供悬浮胶囊自己避让），底栏就必须自己
                // 躲系统导航条。少了这一行，整条 48dp 高的底栏会正好落在导航条底下 —— 看得见一点、
                // 点不到（真机 Pixel 3 XL 手势导航实测：节点 bounds 全 0，点击穿透到书架卡片）。
                .navigationBarsPadding()
                .heightIn(min = Spacing.minTouchTarget)
                .padding(horizontal = Spacing.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBookInfo ?: {}, enabled = onBookInfo != null) {
                Text("书籍信息")
            }
            TextButton(onClick = onDelete) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
