package app.particlebook.android.reader.pdf.perf

import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo

/** One observer per reader. A PreDraw probe never credits a reused display list or an empty frame. */
internal class PdfPresentWindow(private val view: View, private val perf: PdfPerfSession) {
    private class Probe(val observer: ViewTreeObserver, val window: Any, val frame: PdfDrawFrame) {
        lateinit var callback: Runnable
        var candidates: Map<Page, PdfPerfSession.Request> = emptyMap()
    }

    private val pages = linkedSetOf<Page>()
    private val probes = linkedSetOf<Probe>()
    private var observer: ViewTreeObserver? = null
    private var frame: Probe? = null
    private var closed = false
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        prepare()
        true
    }
    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = bindObserver()
        override fun onViewDetachedFromWindow(view: View) = unbindObserver()
    }

    fun start() {
        view.addOnAttachStateChangeListener(attachListener)
        if (view.isAttachedToWindow) bindObserver()
    }

    fun isVisible(): Boolean = !closed && view.isAttachedToWindow && view.isShown &&
        view.windowVisibility == View.VISIBLE

    fun isActive(): Boolean = isVisible() && view.hasWindowFocus()

    fun close() {
        if (closed) return
        closed = true
        view.removeOnAttachStateChangeListener(attachListener)
        unbindObserver()
        for (page in pages.toList()) page.dispose()
    }

    fun newPage(index: Int, inViewport: (Int) -> Boolean) = Page(index, inViewport)

    private fun bindObserver() {
        unbindObserver()
        observer = view.viewTreeObserver.also { it.addOnPreDrawListener(preDraw) }
    }

    private fun unbindObserver() {
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw)
        observer = null
        frame = null
        for (probe in probes.toList()) cancel(probe)
    }

    private fun prepare() {
        frame = null
        if (!isActive()) return
        val candidates = pages.mapNotNull { page -> page.prepare()?.let { page to it } }.toMap()
        if (candidates.isEmpty() || Build.VERSION.SDK_INT < 29 || !view.isHardwareAccelerated) return
        val tree = observer?.takeIf { it.isAlive } ?: return
        val token = view.windowToken ?: return
        if (probes.size >= 8) cancel(probes.first())
        val probe = Probe(tree, token, PdfDrawFrame(token))
        probe.candidates = candidates
        probe.callback = Runnable {
            val commit = perf.now()
            if (probes.remove(probe)) {
                if (isActive() && view.windowToken === probe.window && observer === probe.observer) {
                    probe.frame.commit(probe.window, commit, perf)
                } else probe.frame.cancel()
                if (frame === probe) frame = null
            }
        }
        probes.add(probe)
        frame = probe
        tree.registerFrameCommitCallback(probe.callback)
        // The bitmap can already be in an offscreen display list. Re-record only pending visible pages.
        for (page in candidates.keys) page.node?.requestDraw()
    }

    private fun cancel(probe: Probe) {
        probes.remove(probe)
        probe.frame.cancel()
        if (Build.VERSION.SDK_INT >= 29 && probe.observer.isAlive) {
            probe.observer.unregisterFrameCommitCallback(probe.callback)
        }
    }

    inner class Page(private val index: Int, private val inViewport: (Int) -> Boolean) {
        private var original: PdfPerfSession.Request? = null
        private var candidate: PdfPerfSession.Request? = null
        private var producer: Long? = null
        private var visible = false
        private var offered = false
        internal var node: ImageDrawNode? = null

        fun register() { if (!closed) pages.add(this) }

        fun requested(request: PdfPerfSession.Request) {
            original = request
            candidate = request
        }

        fun ready(request: PdfPerfSession.Request, producer: Long) {
            this.producer = producer
            if (candidate !== request) perf.releaseRequest(request)
        }

        fun visibleNow(): Boolean = isActive() && inViewport(index)

        internal fun prepare(): PdfPerfSession.Request? {
            if (!visibleNow()) {
                if (visible) {
                    if (producer != null) candidate?.let { perf.releaseRequest(it) }
                    candidate = null
                    offered = false
                }
                visible = false
                return null
            }
            visible = true
            val request = candidate
            if (request != null && perf.observeVisible(request)) {
                if (perf.awaitingPresent(request) && node?.hasArea() == true) {
                    offered = true
                    return request
                }
                return null
            }
            val owner = producer ?: return null
            if (offered || node?.hasArea() != true) return null
            candidate?.let { perf.releaseRequest(it) }
            val reused = perf.startRequest(index, PdfOrigin.REUSED) ?: return null
            perf.finishRequest(reused, PdfOutcome.OK, PdfCacheResult.REUSED, owner)
            candidate = reused
            offered = true
            return reused
        }

        fun modifier(): Modifier = ImageDrawElement(this)

        internal fun drawn() {
            val probe = frame ?: return
            val token = view.windowToken ?: return
            if (!visibleNow() || node?.hasArea() != true) return
            val request = probe.candidates[this] ?: return
            if (candidate === request) probe.frame.record(token, request, perf.now())
        }

        fun dispose() {
            pages.remove(this)
            original?.let { perf.releaseRequest(it) }
            candidate?.takeIf { it !== original }?.let { perf.releaseRequest(it) }
            candidate = null
            original = null
            producer = null
        }
    }

    private data class ImageDrawElement(val page: Page) : ModifierNodeElement<ImageDrawNode>() {
        override fun create() = ImageDrawNode(page)
        override fun update(node: ImageDrawNode) = node.bind(page)
        override fun InspectorInfo.inspectableProperties() { name = "pdfPresentProbe" }
    }

    internal class ImageDrawNode(private var page: Page) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {
        private var coordinates: LayoutCoordinates? = null

        override fun onAttach() { page.node = this }
        override fun onDetach() {
            if (page.node === this) page.node = null
            coordinates = null
        }
        override fun onGloballyPositioned(coordinates: LayoutCoordinates) { this.coordinates = coordinates }

        fun bind(next: Page) {
            if (next === page) return
            if (isAttached && page.node === this) page.node = null
            page = next
            if (isAttached) page.node = this
        }

        fun hasArea(): Boolean = coordinates?.takeIf { it.isAttached }?.boundsInWindow()?.let {
            it.width > 0f && it.height > 0f
        } == true

        fun requestDraw() { if (isAttached) invalidateDraw() }

        override fun ContentDrawScope.draw() {
            drawContent()
            if (drawContext.canvas.nativeCanvas.isHardwareAccelerated) page.drawn()
        }
    }
}
