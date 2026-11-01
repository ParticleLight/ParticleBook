package app.particlebook.android.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_FONT_SIZE = "font_size"
    }
}

/**
 * App 根：底栏两目的地（书架 / 设置）；打开书籍时全屏进入阅读器（不带底栏）。
 * 导航用最朴素的状态机，避免为两个目的地引入 navigation-compose。
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
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    if (selectionMode) {
                        SelectionBar(
                            onDelete = { deleteDialogOpen = true },
                        )
                    } else {
                        NavigationBar {
                            TopLevel.entries.forEachIndexed { index, destination ->
                                NavigationBarItem(
                                    selected = tabIndex == index,
                                    onClick = { tabIndex = index },
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
                        .padding(bottom = innerPadding.calculateBottomPadding()),
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
