package app.particlebook.android.data

import app.particlebook.android.reader.ReaderLocator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressCoordinatorTest {
    private class Gate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        suspend fun waitHere() {
            entered.complete(Unit)
            release.await()
        }
    }

    private class ControlledStore : ProgressStore {
        val rows = LinkedHashMap<Long, ProgressSnapshot>()
        val operations = mutableListOf<String>()
        var beforeSave: suspend (ProgressSnapshot) -> Unit = {}
        var beforeDelete: suspend (Set<Long>) -> Unit = {}
        override fun snapshot(bookId: Long): ProgressSnapshot? = rows[bookId]
        override fun snapshots(): Map<Long, ProgressSnapshot> = rows.toMap()
        override suspend fun hydrate(): Map<Long, ProgressSnapshot> = snapshots()
        override suspend fun save(snapshot: ProgressSnapshot) {
            operations += "put:" + snapshot.bookId + ":" + snapshot.page
            beforeSave(snapshot)
            rows[snapshot.bookId] = snapshot
        }
        override suspend fun saveAll(snapshots: Collection<ProgressSnapshot>) = snapshots.forEach { save(it) }
        override suspend fun delete(bookIds: Set<Long>) {
            operations += "delete:" + bookIds.sorted().joinToString(",")
            beforeDelete(bookIds)
            rows.keys.removeAll(bookIds)
        }
        override suspend fun flush() { operations += "flush" }
    }

    private fun pdf(page: Int, id: Long = 1L) = ProgressSnapshot(
        bookId = id, locatorJson = ReaderLocator.Pdf(page).encode(), page = page, totalPages = 10,
    )

    @Test(timeout = 5_000)
    fun oldPutCompletionDoesNotRollBackNewSnapshot() = runBlocking {
        val store = ControlledStore()
        val older = Gate()
        val newer = Gate()
        store.beforeSave = { if (it.page == 1) older.waitHere() else newer.waitHere() }
        val coordinator = ProgressCoordinator(store, emptyMap(), this)
        assertTrue(coordinator.enqueue(pdf(1)))
        older.entered.await()
        assertTrue(coordinator.enqueue(pdf(2)))
        older.release.complete(Unit)
        newer.entered.await()
        assertEquals(1, store.snapshot(1)?.page)
        assertEquals(2, coordinator.snapshot(1)?.page)
        newer.release.complete(Unit)
        assertTrue(coordinator.close())
        assertEquals(pdf(2), store.snapshot(1))
    }

    @Test(timeout = 5_000)
    fun deleteCompletionLeavesLaterPutVisibleAndFailedPutIsRetried() = runBlocking {
        val store = ControlledStore().apply { rows[1L] = pdf(1) }
        val gate = Gate()
        store.beforeDelete = { gate.waitHere() }
        var attempts = 0
        store.beforeSave = { if (++attempts == 1) error("temporary disk error") }
        val coordinator = ProgressCoordinator(store, store.snapshots(), this)
        assertTrue(coordinator.enqueueDelete(setOf(1L)))
        gate.entered.await()
        assertTrue(coordinator.enqueue(pdf(2)))
        gate.release.complete(Unit)
        assertTrue(coordinator.flush())
        assertEquals(2, attempts)
        assertEquals(pdf(2), coordinator.snapshot(1))
        assertEquals(pdf(2), store.snapshot(1))
        assertTrue(coordinator.close())
    }

    @Test(timeout = 5_000)
    fun laterSuccessfulAliasPutSupersedesFailedDeleteOfSameRow() = runBlocking {
        val store = ControlledStore().apply { rows[9L] = pdf(1, 9) }
        store.beforeDelete = { error("sqlite busy") }
        val coordinator = ProgressCoordinator(store, store.snapshots(), this, mapOf(1L to 9L, 2L to 9L))
        assertTrue(coordinator.enqueueDelete(setOf(1L)))
        assertTrue(coordinator.enqueue(pdf(3, 2)))
        assertTrue(coordinator.flush())
        assertEquals(listOf("delete:9", "put:9:3", "flush"), store.operations)
        assertEquals(pdf(3, 9), store.snapshot(9))
        assertEquals(pdf(3, 1), coordinator.snapshot(1))
        assertEquals(pdf(3, 2), coordinator.snapshot(2))
        assertTrue(coordinator.close())
    }

    @Test(timeout = 5_000)
    fun failedPutDoesNotResurfaceAfterSuccessfulDelete() = runBlocking {
        val store = ControlledStore()
        store.beforeSave = { error("sqlite busy") }
        val coordinator = ProgressCoordinator(store, emptyMap(), this)
        assertTrue(coordinator.enqueue(pdf(1)))
        assertTrue(coordinator.enqueueDelete(setOf(1L)))
        assertTrue(coordinator.flush())
        assertEquals(listOf("put:1:1", "delete:1", "flush"), store.operations)
        assertNull(coordinator.snapshot(1))
        assertNull(store.snapshot(1))
        assertTrue(coordinator.close())
    }

    @Test(timeout = 5_000)
    fun flushIsARealBarrierAndTransientFailureIsReportedAndRetryable() = runBlocking {
        val store = ControlledStore()
        val later = Gate()
        val errors = mutableListOf<Exception>()
        var fail = true
        store.beforeSave = {
            if (it.bookId == 2L) later.waitHere() else if (fail) error("disk full")
        }
        val coordinator = ProgressCoordinator(store, emptyMap(), this, onWriteFailure = { errors += it })
        assertTrue(coordinator.enqueue(pdf(1)))
        val barrier = async(start = CoroutineStart.UNDISPATCHED) { coordinator.flush() }
        assertTrue(coordinator.enqueue(pdf(2, 2)))
        assertFalse(barrier.await())
        later.entered.await()
        assertEquals(2, errors.size)
        assertEquals(pdf(1), coordinator.snapshot(1))
        assertNull(store.snapshot(1))
        fail = false
        later.release.complete(Unit)
        assertTrue(coordinator.flush())
        assertEquals(pdf(1), store.snapshot(1))
        assertEquals(pdf(2, 2), store.snapshot(2))
        assertTrue(coordinator.close())
    }

    @Test(timeout = 5_000)
    fun closeLinearizesWithEnqueueAndConcurrentFlush() = runBlocking {
        val store = ControlledStore()
        val gate = Gate()
        store.beforeSave = { gate.waitHere() }
        val coordinator = ProgressCoordinator(store, emptyMap(), this)
        assertTrue(coordinator.enqueue(pdf(1)))
        gate.entered.await()
        val closing = async(start = CoroutineStart.UNDISPATCHED) { coordinator.close() }
        val flush = async(start = CoroutineStart.UNDISPATCHED) { coordinator.flush() }
        assertFalse(coordinator.enqueue(pdf(2)))
        assertFalse(coordinator.enqueueDelete(setOf(1L)))
        gate.release.complete(Unit)
        assertTrue(closing.await())
        assertTrue(flush.await())
        assertTrue(coordinator.close())
        assertEquals(listOf("put:1:1", "flush"), store.operations)
        assertEquals(pdf(1), coordinator.snapshot(1))
    }

    @Test(timeout = 5_000)
    fun cancelWriterCompletesBothActiveAndQueuedBarriers() = runBlocking {
        val writerJob = Job()
        val store = ControlledStore()
        val gate = Gate()
        store.beforeSave = { error("retry me") }
        val coordinator = ProgressCoordinator(store, emptyMap(), CoroutineScope(coroutineContext + writerJob))
        assertTrue(coordinator.enqueue(pdf(1)))
        // Give the first put a barrier to complete, then suspend the retry in the next flush.
        assertFalse(coordinator.flush())
        store.beforeSave = { gate.waitHere() }
        val active = async(start = CoroutineStart.UNDISPATCHED) { coordinator.flush() }
        gate.entered.await()
        val queued = async(start = CoroutineStart.UNDISPATCHED) { coordinator.close() }
        writerJob.cancel()
        withTimeout(2_000) {
            assertFalse(active.await())
            assertFalse(queued.await())
        }
        assertFalse(coordinator.enqueue(pdf(2)))
    }

    @Test(timeout = 5_000)
    fun removalAfterEmptyCatalogDoesNotResurrectUnderNewAlias() = runBlocking {
        val store = ControlledStore().apply { rows[70L] = pdf(9, 70) }
        val coordinator = ProgressCoordinator(store, store.snapshots(), this, mapOf(42L to 70L))
        coordinator.replaceBookIds(emptyMap())
        assertFalse(coordinator.enqueue(pdf(8, 42)))
        coordinator.forgetBooks(setOf(42L))
        assertTrue(coordinator.flush())
        assertNull(store.snapshot(70))
        coordinator.replaceBookIds(mapOf(43L to 70L))
        assertNull(coordinator.snapshot(43))
        assertFalse(coordinator.enqueue(pdf(8, 42)))
        assertTrue(coordinator.close())
    }

    @Test(timeout = 5_000)
    fun libraryRemovalRejectsLateCallbackAndPreservesAnotherAlias() = runBlocking {
        val store = ControlledStore().apply { rows[9L] = pdf(1, 9) }
        val coordinator = ProgressCoordinator(store, store.snapshots(), this, mapOf(1L to 9L, 2L to 9L))
        assertEquals(1, coordinator.forgetBooks(setOf(1L)))
        coordinator.replaceBookIds(mapOf(1L to 9L, 2L to 9L)) // stale catalog sync
        assertFalse(coordinator.enqueue(pdf(3, 1)))
        assertNull(coordinator.snapshot(1))
        assertEquals(pdf(1, 2), coordinator.snapshot(2))
        assertEquals(1, coordinator.forgetBooks(setOf(2L)))
        assertFalse(coordinator.enqueue(pdf(4, 2)))
        assertTrue(coordinator.flush())
        assertNull(store.snapshot(9))
        coordinator.replaceBookIds(mapOf(3L to 9L))
        assertTrue(coordinator.enqueue(pdf(5, 3)))
        assertTrue(coordinator.close())
        assertEquals(pdf(5, 9), store.snapshot(9))
    }
}
