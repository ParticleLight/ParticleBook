package app.particlebook.android.reader.pdf.perf

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PdfPageCacheTest {
    private class Resource { var recycles = 0 }
    private fun cache(limit: Int = 16) = PdfPageCache<Resource>(limit, { 8 }, { assertEquals(1, ++it.recycles) })

    @Test fun samePageJoinsProducerAndOtherPagesQueueIndependently() = runBlocking {
        val cache = cache()
        cache.acquire(1)
        val delivery = CompletableDeferred<Unit>()
        val resource = Resource()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            cache.read(1) { queued ->
                queued(11)
                delivery.await()
                PdfPageCache.Page(resource, 11)
            }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            cache.read(1) { error("duplicate render") }
        }
        assertFalse(second.isCompleted)
        val other = cache.read(2) { queued -> queued(12); PdfPageCache.Page(Resource(), 12) }
        assertEquals(PdfCacheResult.MISS, other.cache)
        delivery.complete(Unit)
        assertEquals(PdfCacheResult.MISS, first.await().cache)
        assertEquals(PdfCacheResult.JOIN, second.await().cache)
        assertSame(resource, second.await().page.bitmap)
        assertEquals(11L, second.await().page.producer)
        val warmed = cache.read(1) { error("cache hit rendered") }
        assertEquals(PdfCacheResult.HIT, warmed.cache)
        val closed = cache.beginClose()
        assertEquals(1 to 8L, cache.snapshot())
        assertFalse(closed.isCompleted)
        assertEquals(0, resource.recycles)
        cache.release(1)
        closed.await()
        assertEquals(0 to 0L, cache.snapshot())
        assertEquals(1, resource.recycles)
    }

    @Test fun closeBeforeInsertionDiscardsOnlyUnpublishedBitmapAndWaitsForDrain() = runBlocking {
        val cache = cache()
        val release = CompletableDeferred<Unit>()
        val inFlight = Resource()
        val load = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                cache.read(0) { queue -> queue(1); release.await(); PdfPageCache.Page(inFlight, 1) }
            }
        }
        val closing = cache.beginClose()
        assertFalse(cache.acquire(0))
        assertFalse(closing.isCompleted)
        release.complete(Unit)
        assertTrue(load.await().exceptionOrNull() is PdfSessionClosed)
        closing.await()
        assertEquals(1, inFlight.recycles)
        assertEquals(0 to 0L, cache.snapshot())
    }

    @Test fun failedFirstProducerAllowsRetryAndTrimHonorsAllLeases() = runBlocking {
        val cache = cache(8)
        assertTrue(cache.acquire(0))
        assertTrue(cache.acquire(0))
        assertTrue(runCatching { cache.read(0) { error("fake renderer failure") } }.isFailure)
        val winner = Resource()
        cache.read(0) { queued -> queued(2); PdfPageCache.Page(winner, 2) }
        cache.trim()
        val closed = cache.beginClose()
        cache.release(0)
        assertEquals(0, winner.recycles)
        assertFalse(closed.isCompleted)
        cache.release(0)
        closed.await()
        assertEquals(1, winner.recycles)
    }
}
