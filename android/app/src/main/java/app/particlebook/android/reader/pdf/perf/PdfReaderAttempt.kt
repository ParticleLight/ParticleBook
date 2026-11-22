package app.particlebook.android.reader.pdf.perf

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import app.particlebook.android.reader.pdf.PdfTextLayerController
import app.particlebook.android.reader.pdf.PdfTextLayerOpenResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Main-thread owner of one reader attempt. Only cleanup survives disposal of the composition. */
internal class PdfReaderAttempt(private val bookId: Long, private val file: File) {
    private val trace = lazy {
        PdfPerfSession(processId, sessionIds.incrementAndGet(), bookId, SystemClock::elapsedRealtimeNanos, sink = { row ->
            val json = JSONObject()
            for ((key, value) in row) json.put(key, value ?: JSONObject.NULL)
            Log.i("PBPDF", json.toString())
        })
    }
    val perf get() = trace.value
    private val caches = mutableSetOf<PdfPageCache<Bitmap>>()
    private var source: PdfBitmapSource? = null
    private var text: PdfTextLayerController? = null
    private var textClose = PdfCloseResult.NOT_OPENED
    private var pageCount = 0
    private var work = 0
    private var closing = false
    private val workDrained = CompletableDeferred<Unit>()

    suspend fun open(): Int = whileOpen {
        val op = perf.queueOp() ?: throw PdfSessionClosed()
        try {
            val worker = PdfBitmapSource().also { source = it }
            pageCount = worker.open(file, perf, op)
            val end = perf.now()
            perf.finishOp(op, PdfOutcome.OK, pageCount, end)
            Log.i("PBPERF", "PBPERF|phase=pdf_open|ms=" + (end - op.queuedNs) / 1_000_000 + "|extra=pages:" + pageCount)
            pageCount
        } catch (error: Throwable) {
            perf.finishOp(op, error.toPdfOutcome())
            source?.beginClose()
            throw error
        }
    }

    fun attachCache(cache: PdfPageCache<Bitmap>) {
        if (closing) cache.beginClose() else caches.add(cache)
    }

    fun retireCache(cache: PdfPageCache<Bitmap>) {
        val receipt = cache.beginClose()
        if (!closing) cleanup.launch {
            receipt.await()
            if (!closing) caches.remove(cache)
        }
    }

    suspend fun load(cache: PdfPageCache<Bitmap>, width: Int, request: PdfPerfSession.Request): PdfPageCache.Page<Bitmap> {
        try {
            return whileOpen {
                val worker = source ?: throw PdfSessionClosed()
                val result = cache.read(request.page) { onQueued ->
                    val op = perf.queueOp(request) ?: throw PdfSessionClosed()
                    onQueued(op.id)
                    try {
                        val bitmap = worker.render(request.page, width, perf, op)
                        val end = perf.now()
                        perf.finishOp(op, PdfOutcome.OK, endNs = end)
                        Log.i("PBPERF", "PBPERF|phase=page_render|page:" + request.page + "|ms=" +
                            (end - op.queuedNs) / 1_000_000 + "|extra=size:" + bitmap.width + "x" + bitmap.height)
                        PdfPageCache.Page(bitmap, op.id)
                    } catch (error: Throwable) {
                        perf.finishOp(op, error.toPdfOutcome())
                        throw error
                    }
                }
                perf.finishRequest(request, PdfOutcome.OK, result.cache, result.page.producer)
                result.page
            }
        } catch (error: Throwable) {
            perf.finishRequest(request, error.toPdfOutcome(), null, request.producer)
            throw error
        }
    }

    suspend fun prefetch(cache: PdfPageCache<Bitmap>, width: Int, page: Int) {
        if (page !in 0 until pageCount) return
        val request = perf.startRequest(page, PdfOrigin.PREFETCH) ?: return
        try {
            load(cache, width, request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (closed: PdfSessionClosed) {
            // Disposal or a retired (resized) cache.
        } catch (error: Exception) {
            Log.w("PBReader", "prefetch failed page=" + (page + 1), error)
        }
    }

    /** Opening and probing keep the unadopted controller owned across both suspension points. */
    suspend fun openText(context: Context): PdfTextLayerController? = whileOpen {
        delay(600)
        var discard: Deferred<PdfCloseResult>? = null
        try {
            val controller = readPdfResource(
                Dispatchers.IO,
                discard = { resource: PdfTextLayerController ->
                    discard = cleanup.async(Dispatchers.IO) { closeText(resource) }
                },
                prepare = { resource ->
                    val hasText = withContext(Dispatchers.IO) {
                        try {
                            (0 until minOf(resource.pageCount, 2)).any { page -> resource.loadPage(page).hasTextLayer }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            true // Probe failure has always been non-fatal.
                        }
                    }
                    if (closing) throw PdfSessionClosed()
                    if (!hasText) Log.w("PBReader", "pdf text layer empty pages=" + resource.pageCount)
                    hasText
                },
            ) {
                when (val opened = PdfTextLayerController.open(context, file)) {
                    is PdfTextLayerOpenResult.Success -> opened.controller
                    is PdfTextLayerOpenResult.Failure -> {
                        Log.w("PBReader", "pdf text layer unavailable reason=" + opened.reason)
                        null
                    }
                }
            }
            text = controller
            controller
        } finally {
            val receipt = discard
            if (receipt != null) withContext(NonCancellable) { textClose = receipt.await() }
        }
    }

    suspend fun <T> whileOpen(block: suspend () -> T): T {
        if (closing) throw PdfSessionClosed()
        work++
        try {
            return block()
        } finally {
            work--
            if (closing && work == 0) workDrained.complete(Unit)
        }
    }

    fun close() {
        if (closing) return
        closing = true
        if (!trace.isInitialized()) return
        perf.beginClose()
        val owned = caches.toList()
        val receipts = owned.map { it.beginClose() }
        val platform = source?.beginClose()
        if (work == 0) workDrained.complete(Unit)
        cleanup.launch {
            workDrained.await()
            val releasedText = text?.let { layer -> withContext(Dispatchers.IO) { closeText(layer) } } ?: textClose
            text = null
            val releasedPlatform = platform?.await() ?: PdfCloseResult.NOT_OPENED
            for (receipt in receipts) receipt.await()
            val remaining = owned.map { it.snapshot() }
            val closed = perf.seal(remaining.sumOf { it.first }, remaining.sumOf { it.second }, releasedPlatform, releasedText)
            if (closed) Log.i("PBPERF", "PBPERF|phase=reader_close|ms=0|extra=pages:" + pageCount)
            else Log.e("PBReader", "PDF session closed with incomplete operation evidence")
            caches.clear()
        }
    }

    private fun closeText(layer: PdfTextLayerController): PdfCloseResult =
        if (layer.closeWithReceipt()) PdfCloseResult.OK else PdfCloseResult.ERROR

    private companion object {
        val processId = UUID.randomUUID().toString()
        val sessionIds = AtomicLong()
        val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
