package app.particlebook.android.reader.pdf.perf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** All renderer access, including open and actual close, stays on its original serial lane. */
internal class PdfBitmapSource {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "PB-PdfRenderer").apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val closeResult = CompletableDeferred<PdfCloseResult>()
    @Volatile private var closing = false
    private var renderer: PdfRenderer? = null // worker only, including assignment before caller resumes
    private var openCleanupFailed = false

    suspend fun open(file: File, perf: PdfPerfSession, op: PdfPerfSession.Op): Int {
        require(file.exists()) { "文件不存在：" + file.name }
        return withContext(dispatcher) {
            if (closing) throw PdfSessionClosed()
            perf.beginOp(op)
            var count: Int? = null
            var outcome = PdfOutcome.ERROR
            try {
                val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val document = try {
                    PdfRenderer(fd) // takes ownership on success
                } catch (error: Throwable) {
                    if (runCatching { fd.close() }.isFailure) openCleanupFailed = true
                    throw error
                }
                renderer = document
                count = document.pageCount
                val first = if (count == 0) "none" else runCatching {
                    document.openPage(0).use { "${it.width}x${it.height}" }
                }.getOrDefault("unavailable")
                Log.d("PBReader", "renderer ready pages=$count firstPage=$first")
                outcome = PdfOutcome.OK
                count
            } catch (error: Throwable) {
                outcome = error.toPdfOutcome()
                throw error
            } finally {
                perf.workerEnded(op, outcome, count)
            }
        }
    }

    suspend fun render(page: Int, targetWidthPx: Int, perf: PdfPerfSession, op: PdfPerfSession.Op): Bitmap =
        checkNotNull(readPdfResource(dispatcher, Bitmap::recycle) {
            if (closing) throw PdfSessionClosed()
            val document = renderer ?: throw PdfSessionClosed()
            perf.beginOp(op)
            var bitmap: Bitmap? = null
            var outcome = PdfOutcome.ERROR
            try {
                document.openPage(page).use { handle ->
                    val width = targetWidthPx.coerceAtLeast(1)
                    val scale = width.toFloat() / handle.width.coerceAtLeast(1).toFloat()
                    val height = (handle.height.coerceAtLeast(1) * scale).roundToInt().coerceAtLeast(1)
                    val created = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap = created
                    created.eraseColor(Color.WHITE)
                    handle.render(created, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
                checkNotNull(bitmap).also { outcome = PdfOutcome.OK }
            } catch (error: Throwable) {
                outcome = error.toPdfOutcome()
                bitmap?.recycle()
                throw error
            } finally {
                perf.workerEnded(op, outcome)
            }
        })

    @Synchronized
    fun beginClose(): CompletableDeferred<PdfCloseResult> {
        if (closing) return closeResult
        closing = true
        try {
            executor.execute {
                var result = PdfCloseResult.NOT_OPENED
                try {
                    renderer?.let { document ->
                        document.close()
                        result = PdfCloseResult.OK
                    }
                    if (openCleanupFailed) result = PdfCloseResult.ERROR
                } catch (error: Exception) {
                    result = PdfCloseResult.ERROR
                    Log.e("PBReader", "PDF renderer close failed", error)
                } finally {
                    renderer = null
                    dispatcher.close()
                    closeResult.complete(result)
                }
            }
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            Log.e("PBReader", "PDF renderer close rejected", error)
            dispatcher.close()
            closeResult.complete(PdfCloseResult.ERROR)
        }
        return closeResult
    }
}
