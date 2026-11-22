package app.particlebook.android.reader.pdf.perf

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Byte LRU with a per-page render gate. An existing lease pins the page even while it loads. */
internal class PdfPageCache<T : Any>(
    private val byteLimit: Int,
    private val sizeOf: (T) -> Long,
    private val recycle: (T) -> Unit,
    private val stat: (Int, Long) -> Unit = { _, _ -> },
    private val evicted: (Int) -> Unit = {},
) {
    data class Page<T>(val bitmap: T, val producer: Long)
    data class Read<T>(val page: Page<T>, val cache: PdfCacheResult)
    private class Slot {
        val mutex = Mutex()
        var users = 0
        var producer: Long? = null
    }

    private val lock = Any()
    private val pages = LinkedHashMap<Int, Page<T>>(8, 0.75f, true)
    private val leases = mutableMapOf<Int, Int>()
    private val slots = mutableMapOf<Int, Slot>()
    private var bytes = 0L
    private var renders = 0
    private var closed = false
    private val drained = CompletableDeferred<Unit>()

    fun acquire(page: Int): Boolean = synchronized(lock) {
        if (closed) return false
        leases[page] = (leases[page] ?: 0) + 1
        true
    }

    fun release(page: Int) = synchronized(lock) {
        val count = leases[page] ?: return@synchronized
        if (count == 1) leases.remove(page) else leases[page] = count - 1
        evictLocked(force = closed)
        checkDrainedLocked()
    }

    suspend fun read(page: Int, render: suspend (onQueued: (Long) -> Unit) -> Page<T>): Read<T> {
        val slot: Slot
        val joinedProducer: Long?
        synchronized(lock) {
            requireOpenLocked()
            pages[page]?.let { return Read(it, PdfCacheResult.HIT) }
            slot = slots.getOrPut(page) { Slot() }
            slot.users++
            joinedProducer = slot.producer
        }
        try {
            return slot.mutex.withLock {
                synchronized(lock) {
                    requireOpenLocked()
                    pages[page]?.let {
                        val disposition = if (joinedProducer == it.producer) PdfCacheResult.JOIN else PdfCacheResult.HIT
                        return@withLock Read(it, disposition)
                    }
                }
                // Source handoff completed. Until insertion succeeds this caller owns the bitmap.
                val result = render { synchronized(lock) { slot.producer = it } }
                var published = false
                try {
                    currentCoroutineContext().ensureActive()
                    synchronized(lock) {
                        requireOpenLocked()
                        val size = sizeOf(result.bitmap)
                        pages[page] = result
                        bytes += size
                        published = true
                        renders++
                        evictLocked()
                        if (renders % 10 == 0) stat(pages.size, bytes)
                        Read(result, PdfCacheResult.MISS)
                    }
                } finally {
                    synchronized(lock) { slot.producer = null }
                    if (!published) recycle(result.bitmap)
                }
            }
        } finally {
            synchronized(lock) {
                if (--slot.users == 0) slots.remove(page)
                checkDrainedLocked()
            }
        }
    }

    fun trim() = synchronized(lock) {
        evictLocked(force = true)
        stat(pages.size, bytes)
    }

    fun beginClose() = synchronized(lock) {
        if (!closed) {
            closed = true
            evictLocked(force = true)
            checkDrainedLocked()
        }
        drained
    }

    fun snapshot(): Pair<Int, Long> = synchronized(lock) { pages.size to bytes }

    private fun requireOpenLocked() {
        if (closed) throw PdfSessionClosed()
    }

    private fun checkDrainedLocked() {
        if (closed && leases.isEmpty() && slots.isEmpty() && drained.complete(Unit)) stat(pages.size, bytes)
    }

    private fun evictLocked(force: Boolean = false) {
        var removed = false
        val iterator = pages.entries.iterator()
        while (iterator.hasNext() && (force || bytes > byteLimit)) {
            val (page, resource) = iterator.next()
            if (leases.containsKey(page)) continue
            iterator.remove()
            bytes -= sizeOf(resource.bitmap)
            recycle(resource.bitmap)
            evicted(page)
            removed = true
        }
        if (removed) stat(pages.size, bytes)
    }
}
