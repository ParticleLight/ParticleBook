package app.particlebook.android.reader.pdf.perf

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Retains the value on the producing side until the caller actually resumes. */
internal class PdfResourceHandoff<T : Any>(private val discard: (T) -> Unit) {
    private var finished = false
    private var value: T? = null

    fun publish(resource: T) {
        val keep = synchronized(this) {
            if (finished) false else {
                check(value == null)
                value = resource
                true
            }
        }
        if (!keep) discard(resource)
    }

    @Synchronized
    fun pending(): T? = value

    @Synchronized
    fun adopt(): T? {
        finished = true
        return value.also { value = null }
    }

    fun abandon() {
        adopt()?.let(discard)
    }
}

/** withContext returning a closeable directly can drop it on prompt cancellation. */
internal suspend fun <T : Any> readPdfResource(
    dispatcher: CoroutineDispatcher,
    discard: (T) -> Unit,
    prepare: (suspend (T) -> Boolean)? = null,
    create: () -> T?,
): T? {
    val handoff = PdfResourceHandoff(discard)
    try {
        withContext(dispatcher) { create()?.let(handoff::publish) }
        val pending = handoff.pending() ?: return null
        if (prepare != null && !prepare(pending)) return null
        coroutineContext.ensureActive()
        return handoff.adopt()
    } finally {
        handoff.abandon()
    }
}

internal class PdfSessionClosed : IllegalStateException("PDF 已关闭")

internal fun Throwable.toPdfOutcome(): PdfOutcome = when (this) {
    is kotlinx.coroutines.CancellationException -> PdfOutcome.CANCELLED
    is PdfSessionClosed -> PdfOutcome.CLOSED
    else -> PdfOutcome.ERROR
}
