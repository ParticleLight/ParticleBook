package app.particlebook.android.reader.pdf.perf

/** A frame's stamps belong only to its originating window and its commit callback. */
internal class PdfDrawFrame(private val window: Any) {
    private var valid = true
    private val drawn = linkedMapOf<PdfPerfSession.Request, Long>()

    fun record(window: Any, request: PdfPerfSession.Request, ns: Long) {
        if (valid && this.window === window) drawn.putIfAbsent(request, ns)
    }

    fun commit(window: Any, ns: Long, perf: PdfPerfSession) {
        if (valid && this.window === window) {
            for ((request, draw) in drawn) perf.confirmPresent(request, draw, ns)
        }
        cancel()
    }

    fun cancel() {
        valid = false
        drawn.clear()
    }
}
