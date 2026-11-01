package app.pb.spike2t

import android.view.Choreographer

/**
 * Chained Choreographer.FrameCallback sampler. Records the interval between successive
 * vsync callbacks (not the callback's own work). Must be started/stopped on the UI thread.
 */
class FrameSampler : Choreographer.FrameCallback {
    private val intervals = ArrayList<Long>()
    private var last = 0L
    private var running = false
    private val choreo: Choreographer get() = Choreographer.getInstance()

    fun start() {
        intervals.clear()
        last = 0L
        if (running) {
            try { choreo.removeFrameCallback(this) } catch (e: Throwable) {}
        }
        running = true
        choreo.postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (last != 0L) intervals.add(frameTimeNanos - last)
        last = frameTimeNanos
        choreo.postFrameCallback(this)
    }

    fun stop(): List<Double> {
        running = false
        try { choreo.removeFrameCallback(this) } catch (e: Throwable) {}
        return intervals.map { it / 1e6 }
    }

    fun sampleCount(): Int = intervals.size

    fun snapshot(): List<Double> = intervals.map { it / 1e6 }
}
