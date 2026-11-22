package app.particlebook.android.data

import app.particlebook.android.reader.ReaderLocator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Callers publish optimistic state and enqueue under one lock. Only the writer touches storage or the
 * failed-intent map. Its completions do not publish state, so a late put/delete can't undo a new edit.
 */
class ProgressCoordinator(
    private val store: ProgressStore,
    initial: Map<Long, ProgressSnapshot>,
    scope: CoroutineScope,
    initialBookIds: Map<Long, Long>? = null,
    private val snapshotSink: MutableStateFlow<Map<Long, ProgressSnapshot>> = MutableStateFlow(emptyMap()),
    private val onWriteFailure: (Exception) -> Unit = {},
    initiallyRetiredIds: Set<Long> = emptySet(),
) {
    private sealed interface Command {
        data class Put(val snapshot: ProgressSnapshot) : Command
        data class Delete(val ids: Set<Long>) : Command
        data class Flush(val done: CompletableDeferred<Boolean>) : Command
    }

    private val lock = Any()
    private val rows = initial.toMutableMap()
    private val retired = initiallyRetiredIds.toMutableSet()
    // Only active aliases can write. Keep known ids separately for deletion after a shelf refresh.
    private var bookIds = initialBookIds?.minus(retired)
    private val knownIds = bookIds?.toMutableMap() ?: mutableMapOf()
    private var aliases = bookIds?.entries?.groupBy({ it.value }, { it.key })
    private var closing: CompletableDeferred<Boolean>? = null
    private val channel = Channel<Command>(Channel.UNLIMITED, onUndeliveredElement = {
        if (it is Command.Flush) it.done.complete(false)
    })
    val snapshots: StateFlow<Map<Long, ProgressSnapshot>> = snapshotSink.asStateFlow()

    init { publishAll() }

    private val writer = scope.launch { runWriter() }.also { job ->
        // Also handles cancellation before the coroutine enters runWriter().
        job.invokeOnCompletion { channel.cancel() }
    }

    fun snapshot(bookId: Long): ProgressSnapshot? = snapshots.value[bookId]

    fun replaceBookIds(ids: Map<Long, Long>) = synchronized(lock) {
        val next = ids - retired
        knownIds.putAll(next)
        if (next == bookIds) return@synchronized
        bookIds = next
        aliases = next.entries.groupBy({ it.value }, { it.key })
        publishAll()
    }

    fun enqueue(snapshot: ProgressSnapshot): Boolean = synchronized(lock) {
        val id = databaseId(snapshot.bookId) ?: return@synchronized false
        put(snapshot.copy(bookId = id))
    }

    fun enqueueLocation(bookId: Long, locator: ReaderLocator, page: Int, total: Int): Boolean = synchronized(lock) {
        if (total <= 0) return@synchronized false
        val id = databaseId(bookId) ?: return@synchronized false
        val index = page.coerceIn(0, total - 1)
        val encoded = locator.encode()
        val previous = rows[id]
        // Back/onStop can report the same position that the reader already queued.
        if (previous?.locatorJson == encoded && previous.page == index && previous.totalPages == total) {
            return@synchronized true
        }
        put(ProgressSnapshot(
            bookId = id,
            locatorJson = encoded,
            page = index,
            totalPages = total,
            percent = index.toDouble() / total,
            updatedAt = System.currentTimeMillis(),
            finishedAt = previous?.finishedAt,
        ))
    }

    /** Clear progress but let a subsequently accepted write for this book win. */
    fun enqueueDelete(bookIds: Set<Long>): Boolean = synchronized(lock) {
        delete(bookIds.mapNotNull(::databaseId).toSet())
    }

    /** The committed shelf can be newer than the last initialize() catalog refresh. */
    fun forgetBooks(
        ids: Set<Long>,
        resolvedIds: Map<Long, Long> = emptyMap(),
        remainingDbIds: Set<Long>? = null,
    ): Int = synchronized(lock) {
        val newlyRetired = ids - retired
        if (newlyRetired.isEmpty()) return@synchronized 0
        val dbIds = newlyRetired.mapNotNull { id ->
            resolvedIds[id] ?: knownIds[id] ?: if (bookIds == null) id else null
        }.toSet()
        retired.addAll(newlyRetired)
        val count = newlyRetired.count { knownIds.remove(it) != null || it in snapshotSink.value }
        bookIds = bookIds?.minus(newlyRetired)
        aliases = bookIds?.entries?.groupBy({ it.value }, { it.key })
        check(delete(dbIds - (remainingDbIds ?: aliases?.keys.orEmpty()))) { "progress writer closed during deletion" }
        publishAll()
        count
    }

    suspend fun flush(): Boolean {
        val done = synchronized(lock) {
            closing ?: CompletableDeferred<Boolean>().also {
                if (!channel.trySend(Command.Flush(it)).isSuccess) it.complete(false)
            }
        }
        return done.await()
    }

    suspend fun close(): Boolean {
        val done = synchronized(lock) {
            closing ?: CompletableDeferred<Boolean>().also {
                closing = it
                if (!channel.trySend(Command.Flush(it)).isSuccess) it.complete(false)
                channel.close()
            }
        }
        val success = done.await()
        writer.join()
        return success
    }

    private fun databaseId(bookId: Long): Long? {
        if (bookId in retired) return null
        val current = bookIds ?: return bookId
        return current[bookId]
    }

    private fun put(snapshot: ProgressSnapshot): Boolean {
        if (closing != null || !channel.trySend(Command.Put(snapshot)).isSuccess) return false
        rows[snapshot.bookId] = snapshot
        val uiIds = aliases?.get(snapshot.bookId) ?: listOf(snapshot.bookId)
        snapshotSink.value = snapshotSink.value + uiIds.associateWith { snapshot.copy(bookId = it) }
        return true
    }

    private fun delete(ids: Set<Long>): Boolean {
        if (ids.isEmpty()) return true
        if (closing != null || !channel.trySend(Command.Delete(ids)).isSuccess) return false
        rows.keys.removeAll(ids)
        publishAll()
        return true
    }

    private fun publishAll() {
        val current = bookIds
        snapshotSink.value = if (current == null) rows.filterKeys { it !in retired } else buildMap {
            current.forEach { (uiId, dbId) -> rows[dbId]?.let { put(uiId, it.copy(bookId = uiId)) } }
        }
    }

    private suspend fun runWriter() {
        // One latest failed intent per canonical id. After a later successful intent there is
        // nothing to retry for that id, even if the earlier intent was from a different shelf alias.
        val failed = LinkedHashMap<Long, ProgressSnapshot?>()
        suspend fun applyPut(snapshot: ProgressSnapshot) {
            if (attempt { store.save(snapshot) }) failed.remove(snapshot.bookId)
            else failed[snapshot.bookId] = snapshot
        }
        suspend fun applyDelete(ids: Set<Long>) {
            if (attempt { store.delete(ids) }) failed.keys.removeAll(ids)
            else ids.forEach { failed[it] = null }
        }
        for (command in channel) {
            when (command) {
                is Command.Put -> applyPut(command.snapshot)
                is Command.Delete -> applyDelete(command.ids)
                is Command.Flush -> {
                    var success = false
                    try {
                        val retry = failed.toMap()
                        val deletes = retry.filterValues { it == null }.keys
                        if (deletes.isNotEmpty()) applyDelete(deletes)
                        retry.values.filterNotNull().forEach { applyPut(it) }
                        success = failed.isEmpty() && attempt { store.flush() }
                    } finally {
                        command.done.complete(success)
                    }
                }
            }
        }
    }

    private suspend fun attempt(operation: suspend () -> Unit): Boolean = try {
        operation()
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        onWriteFailure(error)
        false
    }
}
