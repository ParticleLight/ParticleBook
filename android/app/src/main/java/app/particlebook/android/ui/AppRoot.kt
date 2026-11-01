package app.particlebook.android.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import app.particlebook.android.ui.data.Book
import app.particlebook.android.ui.data.BookFormat
import app.particlebook.android.ui.data.LibraryStore
import kotlinx.coroutines.launch

/** 底栏两个目的地：书架 / 设置（找书 P0 未实现，FindScreen 保留待 P1 插回）。阅读器是全屏任务页，不在这里。 */
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
    Settings(
        label = "设置",
        icon = Icons.Outlined.Settings,
        selectedIcon = Icons.Filled.Settings,
    ),
}

/** 轻量持久化：主题与字号。UI 状态只有这两项值得跨进程保留。 */
private class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("pb_mobile_ui", Context.MODE_PRIVATE)

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
        get() = prefs.getInt(KEY_FONT_SIZE, 18)
        set(value) {
            prefs.edit().putInt(KEY_FONT_SIZE, value).apply()
        }

    /** 正文行高倍数（1.3 / 1.5 / 1.7 / 2.0）。 */
    var lineHeight: Float
        get() = prefs.getFloat(KEY_LINE_HEIGHT, 1.7f)
        set(value) {
            prefs.edit().putFloat(KEY_LINE_HEIGHT, value).apply()
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

/**
 * App 根：底栏两目的地（书架 / 设置）；打开书籍时全屏进入阅读器（不带底栏）。
 * 导航用最朴素的状态机，避免为两个目的地引入 navigation-compose。
 *
 * 手机横屏（宽 ≥600dp 且高 <480dp）时竖向空间被底栏吃光，改左侧 NavigationRail + 书架列表
 * （spec §2.1.8 C 方案）：不允许再用「缩小列数的大封面网格」。
 */
@Composable
fun ParticleBookApp() {
    val context = LocalContext.current
    val preferences = remember { AppPreferences(context) }
    var themeModeName by rememberSaveable { mutableStateOf(preferences.themeMode.name) }
    val themeMode = AppThemeMode.entries.firstOrNull { it.name == themeModeName } ?: AppThemeMode.System
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    var openBookId by rememberSaveable { mutableLongStateOf(-1L) }
    var fontSize by rememberSaveable { mutableIntStateOf(preferences.fontSize) }
    var lineHeight by rememberSaveable { mutableFloatStateOf(preferences.lineHeight) }
    var defaultSortName by rememberSaveable { mutableStateOf(preferences.defaultSort) }
    val defaultSort = LibrarySort.entries.firstOrNull { it.name == defaultSortName }
        ?: LibrarySort.RecentRead

    val store = remember { LibraryStore(context) }
    LaunchedEffect(Unit) { store.ensureLoaded() }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val books: List<Book> = store.books
    val openBook = remember(books, openBookId) { books.firstOrNull { it.id == openBookId } }

    // 多选状态只在会话内有效（书源本身也还没有持久化）。
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var deleteDialogOpen by remember { mutableStateOf(false) }

    LaunchedEffect(books, openBookId) {
        if (openBookId >= 0L && openBook == null) openBookId = -1L
    }

    fun exitSelection() {
        selectionMode = false
        selectedIds = emptySet()
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            store.importDocument(uri)
                .onSuccess { book ->
                    snackbarHostState.showSnackbar("已导入《" + book.title + "》")
                    if (book.format == BookFormat.Pdf) openBookId = book.id
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
            exitSelection()
            snackbarHostState.showSnackbar("已删除 " + ids.size + " 本书")
        }
    }

    BackHandler(enabled = openBook == null && selectionMode) { exitSelection() }
    BackHandler(enabled = openBook == null && !selectionMode && tabIndex != 0) { tabIndex = 0 }

    ParticleBookTheme(mode = themeMode) {
        if (openBook != null) {
            ReaderScreen(book = openBook, onBack = { openBookId = -1L })
        } else {
            val configuration = LocalConfiguration.current
            // 914×411dp 的手机横屏命中；竖屏、以及高度 ≥480dp 的平板横屏都不命中，保持底栏。
            val compactLandscape =
                configuration.screenWidthDp >= 600 && configuration.screenHeightDp < 480
            AppShell(
                compactLandscape = compactLandscape,
                tabIndex = tabIndex,
                onTabSelected = { tabIndex = it },
                snackbarHostState = snackbarHostState,
                selectionMode = selectionMode,
                onDelete = { deleteDialogOpen = true },
            ) {
                when (tabIndex) {
                    0 -> LibraryScreen(
                        books = books,
                        loading = store.loading,
                        error = store.error,
                        selectionMode = selectionMode,
                        selectedIds = selectedIds,
                        onOpenBook = { book ->
                            exitSelection()
                            openBookId = book.id
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
                        onRetry = { scope.launch { store.reload() } },
                        defaultSort = defaultSort,
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
                                snackbarHostState.showSnackbar("已清除本会话的阅读记录")
                            }
                        },
                    )
                }
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
 * 导航外壳：竖屏 = 底部 NavigationBar；手机横屏 = 左侧 NavigationRail 80dp（标签常显）。
 * 多选时底部换成 SelectionBar，rail 仍显示（spec §2.1.8）。
 */
@Composable
private fun AppShell(
    compactLandscape: Boolean,
    tabIndex: Int,
    onTabSelected: (Int) -> Unit,
    snackbarHostState: SnackbarHostState,
    selectionMode: Boolean,
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (compactLandscape) {
        // 整个区域先避让右侧系统栏（横屏三键导航是右侧竖条）：TopAppBar / FAB 都在这个 Row 里，
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
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    if (selectionMode) SelectionBar(onDelete = onDelete)
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
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (selectionMode) {
                    SelectionBar(onDelete = onDelete)
                } else {
                    NavigationBar {
                        TopLevel.entries.forEachIndexed { index, destination ->
                            NavigationBarItem(
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
                                label = { Text(destination.label) },
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = innerPadding.calculateBottomPadding())
                    // 横屏时系统三键导航在屏幕右侧（edge-to-edge 之后内容会画到它下面）：
                    // 只避让左右安全区，底部由 bottomBar 高度负责，避免双倍 inset。
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
                    ),
            ) {
                content()
            }
        }
    }
}

/** 多选底栏：书柜/更多是 P1 占位（禁用），删除是主操作。 */
@Composable
private fun SelectionBar(onDelete: () -> Unit) {
    BottomAppBar {
        TextButton(onClick = {}, enabled = false) { Text("加入书柜") }
        TextButton(onClick = {}, enabled = false) { Text("更多") }
        TextButton(onClick = onDelete) {
            Text("删除", color = MaterialTheme.colorScheme.error)
        }
    }
}
