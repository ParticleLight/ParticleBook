package app.particlebook.android.reader.pdf.perf

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class PdfResourceHandoffTest {
    private class Lane : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.addLast(block) }
        fun runOne() = queue.removeFirst().run()
        fun drain() { while (queue.isNotEmpty()) runOne() }
    }

    @Test fun cancellingQueuedWorkNeverCreatesResource() = runBlocking {
        val worker = Lane(); val caller = Lane()
        var created = 0
        val child = launch(caller, start = CoroutineStart.UNDISPATCHED) {
            readPdfResource(worker, { _: Any -> error("no resource") }) { created++; Any() }
        }
        child.cancel()
        worker.drain(); caller.drain(); child.join()
        assertEquals(0, created)
    }

    @Test fun cancellationAfterWorkerCreationBeforeCallerDeliveryDiscardsExactlyOnce() = runBlocking {
        val worker = Lane(); val caller = Lane()
        val resource = Any()
        val discarded = mutableListOf<Any>()
        var adopted: Any? = null
        val child = launch(caller, start = CoroutineStart.UNDISPATCHED) {
            adopted = readPdfResource(worker, discarded::add) { resource }
        }
        worker.runOne()
        child.cancel()
        caller.drain(); child.join()
        assertNull(adopted)
        assertEquals(listOf(resource), discarded)
    }

    @Test fun cancellingDuringTextProbeReleasesResourceInsteadOfPublishing() = runBlocking {
        val worker = Lane(); val caller = Lane()
        var probeRan = false
        val resource = Any()
        val discarded = mutableListOf<Any>()
        var adopted: Any? = null
        val child = launch(caller, start = CoroutineStart.UNDISPATCHED) {
            adopted = readPdfResource(worker, discard = discarded::add,
                prepare = { withContext(worker) { probeRan = true; true } }) { resource }
        }
        worker.runOne()  // Open complete.
        caller.runOne()  // Probe dispatched.
        worker.runOne()  // Probe complete, caller hasn't resumed.
        child.cancel()
        caller.drain(); child.join()
        assertTrue(probeRan)
        assertNull(adopted)
        assertEquals(listOf(resource), discarded)
    }

    @Test fun noTextAndLatePublicationEachDiscardOnceButAdoptionTransfersOwnership() = runBlocking {
        val worker = Lane(); val caller = Lane()
        val resources = List(3) { Any() }
        val discarded = mutableListOf<Any>()
        val disposed = PdfResourceHandoff<Any> { discarded.add(it) }
        disposed.abandon()
        disposed.publish(resources[0])
        disposed.abandon()
        val child = launch(caller, start = CoroutineStart.UNDISPATCHED) {
            assertNull(readPdfResource(worker, discarded::add, prepare = { false }) { resources[1] })
            assertSame(resources[2], readPdfResource(worker, discarded::add) { resources[2] })
        }
        worker.runOne(); caller.runOne()
        worker.runOne(); caller.drain(); child.join()
        assertEquals(resources.take(2), discarded)
    }
}
