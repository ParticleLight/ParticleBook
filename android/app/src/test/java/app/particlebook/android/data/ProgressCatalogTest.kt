package app.particlebook.android.data

import app.particlebook.android.reader.ReaderLocator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressCatalogTest {
    private val original = BookRow(id = 42, stableKey = "hash:shared", format = "pdf")
    private val other = BookRow(id = 2, stableKey = "hash:other", format = "pdf")
    private val alias = original.copy(id = 43)

    private fun repository() = TestLibraryRepository().apply {
        saveBook(original.copy(id = 70))
        saveBook(other)
        saveProgress(ProgressRow(70, ReaderLocator.Pdf(9).encode(), 9, 10, updatedAt = 50))
        markProgressBackfillComplete()
    }

    @Test(timeout = 5_000)
    fun delayedClearPreservesNewAliasAndDoesNotRollBackItsActiveCatalog() = runBlocking {
        val repo = repository()
        val writer = Job()
        try {
            val catalog = ProgressCatalog(CoroutineScope(coroutineContext + writer), Dispatchers.Unconfined)
            var shelf = listOf(original, other)
            catalog.initialize({ repo }, { shelf }, { error("marker already set") })
            val delayed = CompletableDeferred<Unit>()
            val clear = async(start = CoroutineStart.UNDISPATCHED) {
                delayed.await()
                catalog.clear(listOf(original), { repo }, { shelf })
            }
            // The removed alias has gone and a new alias is already initialized before clear resumes.
            shelf = listOf(other, alias)
            catalog.initialize({ repo }, { shelf }, { error("hydration runs once") })
            delayed.complete(Unit)
            clear.await()
            assertNull(catalog.snapshots.value[42])
            assertEquals(9, catalog.snapshots.value[43]?.page)
            catalog.enqueue(43, ReaderLocator.Pdf(4), 4, 10)
            assertEquals(4, catalog.snapshots.value[43]?.page)
            catalog.enqueue(42, ReaderLocator.Pdf(8), 8, 10)
            assertTrue(catalog.flush())
            assertEquals(4, repo.progress(70)?.page)
            assertNull(catalog.snapshots.value[42])
        } finally {
            writer.cancel()
        }
    }

    @Test(timeout = 5_000)
    fun emptyCatalogBeforeClearStillDeletesCanonicalRowAndNewAliasStartsUnread() = runBlocking {
        val repo = repository()
        val writer = Job()
        try {
            val catalog = ProgressCatalog(CoroutineScope(coroutineContext + writer), Dispatchers.Unconfined)
            catalog.initialize({ repo }, { listOf(original) }, { emptyMap() })
            catalog.initialize({ repo }, { emptyList() }, { error("hydrate once") })
            catalog.clear(listOf(original), { repo }, { emptyList() })
            assertNull(repo.progress(70))
            catalog.initialize({ repo }, { listOf(alias) }, { error("hydrate once") })
            assertNull(catalog.snapshots.value[43])
            catalog.enqueue(43, ReaderLocator.Pdf(3), 3, 10)
            assertTrue(catalog.flush())
            assertEquals(3, repo.progress(70)?.page)
        } finally {
            writer.cancel()
        }
    }

    @Test(timeout = 5_000)
    fun removalBeforeFirstHydrationResolvesStableIdentityAndDoesNotReadRetiredPrefs() = runBlocking {
        val repo = repository()
        val writer = Job()
        try {
            val catalog = ProgressCatalog(CoroutineScope(coroutineContext + writer), Dispatchers.Unconfined)
            catalog.clear(listOf(original), { repo }, { listOf(other) })
            assertNull(repo.progress(70))
            catalog.initialize({ repo }, { listOf(other) }, { error("marker already set") })
            catalog.enqueue(42, ReaderLocator.Pdf(8), 8, 10)
            assertTrue(catalog.flush())
            assertNull(repo.progress(70))
            assertNull(catalog.snapshots.value[42])
        } finally {
            writer.cancel()
        }
    }

    @Test(timeout = 5_000)
    fun removalBeforeFirstHydrationProtectsRemainingCanonicalAlias() = runBlocking {
        val repo = repository()
        val writer = Job()
        try {
            val catalog = ProgressCatalog(CoroutineScope(coroutineContext + writer), Dispatchers.Unconfined)
            catalog.clear(listOf(original), { repo }, { listOf(alias) })
            assertEquals(9, repo.progress(70)?.page)
            catalog.initialize({ repo }, { listOf(alias) }, { error("marker already set") })
            assertEquals(9, catalog.snapshots.value[43]?.page)
        } finally {
            writer.cancel()
        }
    }

    @Test(timeout = 5_000)
    fun alreadyCancelledCallerStillHandsOffCommittedRemovalBeforeNextSuspendPoint() = runBlocking {
        val repo = repository()
        val writer = Job()
        try {
            val catalog = ProgressCatalog(CoroutineScope(coroutineContext + writer), Dispatchers.Unconfined)
            catalog.initialize({ repo }, { listOf(original) }, { emptyMap() })
            val caller = launch(start = CoroutineStart.UNDISPATCHED) {
                // Represents cancellation observed just after the index mutation committed.
                coroutineContext.cancel()
                catalog.clear(listOf(original), { repo }, { emptyList() })
                CompletableDeferred<Unit>().await() // A later tombstone mutex may suspend here.
            }
            caller.join()
            assertTrue(caller.isCancelled)
            assertTrue(catalog.flush())
            assertNull(repo.progress(70))
            assertNull(catalog.snapshots.value[42])
        } finally {
            writer.cancel()
        }
    }

    @Test(timeout = 5_000)
    fun failureToOpenRepositoryKeepsRemovalPendingForNextInitialize() = runBlocking {
        val repo = repository()
        val writer = Job()
        try {
            val catalog = ProgressCatalog(CoroutineScope(coroutineContext + writer), Dispatchers.Unconfined)
            val failure = runCatching {
                catalog.clear(listOf(original), { error("database opening failed") }, { emptyList() })
            }
            assertTrue(failure.isFailure)
            assertNotNull(repo.progress(70))
            catalog.initialize({ repo }, { listOf(alias) }, { emptyMap() })
            // A current alias protects the row when the previously failed removal is retried.
            assertEquals(9, repo.progress(70)?.page)
            assertEquals(9, catalog.snapshots.value[43]?.page)
            assertFalse(catalog.snapshots.value.containsKey(42L))
        } finally {
            writer.cancel()
        }
    }
}
