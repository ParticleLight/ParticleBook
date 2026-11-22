package app.particlebook.android.ui.data

import android.content.Context
import android.util.Log
import app.particlebook.android.data.LegacyMigrationRunner
import app.particlebook.android.data.LibraryRepository
import app.particlebook.android.data.LibraryRuntime
import app.particlebook.android.data.ProgressCatalog
import app.particlebook.android.data.ProgressSnapshot
import app.particlebook.android.reader.ReaderLocator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow

/** Process-wide progress cache. Only initialization and the writer touch disk. */
@Suppress("UNUSED_PARAMETER") // Keep the existing Context-based reader and shelf call sites.
object ReadingProgress {
    private const val TAG = "PBReadingProgress"
    private val catalog = ProgressCatalog(CoroutineScope(SupervisorJob() + Dispatchers.IO), Dispatchers.IO) {
        Log.w(TAG, "SQLite progress write failed; will retry on flush", it)
    }

    /** Snapshot keys are shelf ids, not SQLite ids. */
    val snapshots: StateFlow<Map<Long, ProgressSnapshot>> = catalog.snapshots

    /** Read the latest bookshelf on IO after serializing against initialization and removal. */
    suspend fun initialize(context: Context, latestBooks: () -> List<Book>): Boolean {
        val appContext = context.applicationContext
        return try {
            catalog.initialize(
                repository = { openCatalog(appContext) },
                latestRows = { LibraryCatalogBridge.bookRows(latestBooks()) },
                readPreferences = {
                    appContext.getSharedPreferences(LegacyMigrationRunner.PREFS_NAME, Context.MODE_PRIVATE).all
                },
            )
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "SQLite progress initialization failed; retry on next initialize", error)
            false
        }
    }

    fun snapshot(bookId: Long): ProgressSnapshot? = snapshots.value[bookId]

    fun percentLabel(context: Context, bookId: Long): String? = snapshot(bookId)?.percentLabel()

    fun hasProgress(context: Context, bookId: Long): Boolean = snapshot(bookId)?.hasProgress() == true

    fun locator(context: Context, bookId: Long): ReaderLocator? = snapshot(bookId)?.locator()

    fun savedPage(context: Context, bookId: Long): Int? = snapshot(bookId)?.savedPage()

    fun lastReadAt(context: Context, bookId: Long): Long = snapshot(bookId)?.updatedAt ?: 0L

    fun fraction(context: Context, bookId: Long): Float? = snapshot(bookId)?.fraction()

    fun a11yStatus(context: Context, bookId: Long): String = percentLabel(context, bookId) ?: "未读"

    fun saveEpubProgress(context: Context, bookId: Long, locator: ReaderLocator, page: Int, pages: Int) {
        if (locator is ReaderLocator.Epub) save(bookId, locator, page, pages)
    }

    fun savePdfProgress(context: Context, bookId: Long, page: Int, pages: Int) {
        if (pages > 0) save(bookId, ReaderLocator.Pdf(page.coerceIn(0, pages - 1)), page, pages)
    }

    private fun save(bookId: Long, locator: ReaderLocator, page: Int, pages: Int) {
        catalog.enqueue(bookId, locator, page, pages)
    }

    /** Called after the shelf commits. Clear only rows with no remaining shelf alias. */
    suspend fun clear(context: Context, removed: List<Book>, latestBooks: () -> List<Book>): Int = catalog.clear(
        removed = LibraryCatalogBridge.bookRows(removed),
        repository = { openCatalog(context.applicationContext) },
        latestRows = { LibraryCatalogBridge.bookRows(latestBooks()) },
    )

    /** True means all accepted writes before this call reached SQLite. */
    suspend fun flush(): Boolean = catalog.flush()

    private fun openCatalog(context: Context): LibraryRepository {
        LibraryRuntime.ensureCatalogReady(context)
        return LibraryRuntime.repository(context)
    }
}

/** Legacy fixture keys; runtime never accesses them outside migration. */
internal fun progressKeys(bookId: Long): List<String> =
    listOf("page_" + bookId, "pages_" + bookId, "last_read_" + bookId)
