package app.pb.spike2t

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.Choreographer
import android.view.View
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Custom View that paints a PDF page out of a tile cache.
 *
 * Content space: page points * zoom == pixels, 1 content px == 1 view px.
 *   view = content - (panX, panY)
 * Tiles are laid out on a fixed grid of tilePx in content space, so at every zoom level a tile is
 * a constant 512x512 device-pixel unit of work.
 *
 * Gesture mode draws one low-resolution full-page bitmap (cheap, always present) instead of tiles;
 * the high-resolution tiles are rebuilt after the gesture ends.
 */
class TileView(context: Context) : View(context) {

    var cache: TileCache? = null

    /** (key, docTag, page, scale, tx, ty, tw, th) — invoked from onDraw for every missing tile. */
    var onNeedTile: ((String, String, Int, Float, Int, Int, Int, Int) -> Unit)? = null

    var lowRes: Bitmap? = null
    var tilePx = 512
    var gestureActive = false

    /**
     * A/B switch for the pan experiment, so the frame interval can be attributed:
     * 0 = tiles (real path), 1 = background only (measures what the compositor costs with no
     * bitmap work at all), 2 = one low-res page bitmap (single 360px texture instead of 15).
     */
    var abMode = 0

    var docTag = ""
        private set
    var page = 0
        private set
    var pageWpt = 595f
        private set
    var pageHpt = 842f
        private set
    var zoom = 1.8f
    var panX = 0f
    var panY = 0f

    private val pageBg = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val placeholder = Paint().apply { color = 0xFFEDEDED.toInt(); style = Paint.Style.FILL }
    private val lowPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val tmpSpan = IntArray(4)
    private val tmpRect = RectF()

    private var animKind = 0 // 0 none, 1 pan, 2 zoom
    private var animStart = 0L
    private var animDur = 0L
    private var animFromX = 0f
    private var animToX = 0f
    private var animFromY = 0f
    private var animToY = 0f
    private var animDone: (() -> Unit)? = null

    var onFirstDraw: (() -> Unit)? = null

    var drawnFrames = 0L
        private set

    var animating = false
        private set

    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            onAnimFrame(frameTimeNanos)
        }
    }

    fun setPage(tag: String, wpt: Int, hpt: Int, newPage: Int, newZoom: Float) {
        docTag = tag
        pageWpt = wpt.toFloat()
        pageHpt = hpt.toFloat()
        page = newPage
        zoom = newZoom
        invalidate()
    }

    fun contentW(): Float = pageWpt * zoom
    fun contentH(): Float = pageHpt * zoom

    fun tileKey(tx: Int, ty: Int): String =
        docTag + "#p" + page + "#s" + Math.round(zoom * 1000f) + "#" + tx + "," + ty

    private fun tileSizeAt(tx: Int, ty: Int): IntArray {
        val cw = contentW()
        val ch = contentH()
        val tp = tilePx.toFloat()
        val tw = min(tilePx, (cw - tx * tp).roundToInt().coerceAtLeast(1))
        val th = min(tilePx, (ch - ty * tp).roundToInt().coerceAtLeast(1))
        return intArrayOf(tw, th)
    }

    private fun maxTileX(): Int = max(0, ((contentW() - 1f) / tilePx).toInt())
    private fun maxTileY(): Int = max(0, ((contentH() - 1f) / tilePx).toInt())

    /** [x0, x1, y0, y1] of visible tiles, inclusive; x1 < x0 means nothing visible. */
    private fun visibleSpan(): IntArray {
        val cw = contentW()
        val ch = contentH()
        val tp = tilePx.toFloat()
        val vx0 = max(0f, panX)
        val vx1 = min(cw, panX + width)
        val vy0 = max(0f, panY)
        val vy1 = min(ch, panY + height)
        if (vx1 - vx0 < 0.5f || vy1 - vy0 < 0.5f) {
            tmpSpan[0] = 0; tmpSpan[1] = -1; tmpSpan[2] = 0; tmpSpan[3] = -1
            return tmpSpan
        }
        tmpSpan[0] = floor(vx0 / tp).toInt().coerceAtLeast(0)
        tmpSpan[1] = floor((vx1 - 0.5f) / tp).toInt().coerceAtMost(maxTileX())
        tmpSpan[2] = floor(vy0 / tp).toInt().coerceAtLeast(0)
        tmpSpan[3] = floor((vy1 - 0.5f) / tp).toInt().coerceAtMost(maxTileY())
        return tmpSpan
    }

    fun visibleTileCount(): Int {
        val s = visibleSpan()
        if (s[1] < s[0] || s[3] < s[2]) return 0
        return (s[1] - s[0] + 1) * (s[3] - s[2] + 1)
    }

    fun viewportComplete(): Boolean {
        if (gestureActive) return false
        val c = cache ?: return false
        val s = visibleSpan()
        if (s[1] < s[0] || s[3] < s[2]) return true
        for (ty in s[2]..s[3]) {
            for (tx in s[0]..s[1]) {
                if (!c.contains(tileKey(tx, ty))) return false
            }
        }
        return true
    }

    fun pendingTileCount(): Int {
        val c = cache ?: return -1
        val s = visibleSpan()
        if (s[1] < s[0] || s[3] < s[2]) return 0
        var n = 0
        for (ty in s[2]..s[3]) {
            for (tx in s[0]..s[1]) {
                if (!c.contains(tileKey(tx, ty))) n++
            }
        }
        return n
    }

    private fun normalizePan() {
        val cw = contentW()
        val ch = contentH()
        panX = if (cw <= width) (cw - width) / 2f else panX.coerceIn(0f, cw - width)
        panY = if (ch <= height) (ch - height) / 2f else panY.coerceIn(0f, ch - height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        normalizePan()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        normalizePan()

        if (drawnFrames == 0L) {
            val cb = onFirstDraw
            onFirstDraw = null
            if (cb != null) cb()
        }
        drawnFrames++

        canvas.drawColor(0xFF303030.toInt())
        canvas.save()
        canvas.translate(-panX, -panY)

        val cw = contentW()
        val ch = contentH()
        canvas.drawRect(0f, 0f, cw, ch, pageBg)

        val mode = if (gestureActive) 2 else abMode
        if (mode == 1) {
            // background only
        } else if (mode == 2) {
            val lr = lowRes
            if (lr != null) {
                tmpRect.set(0f, 0f, cw, ch)
                canvas.drawBitmap(lr, null, tmpRect, lowPaint)
            }
        } else {
            drawTiles(canvas, cw, ch)
        }
        canvas.restore()
    }

    private fun drawTiles(canvas: Canvas, cw: Float, ch: Float) {
        val s = visibleSpan()
        if (s[1] < s[0] || s[3] < s[2]) return
        val visible = (s[1] - s[0] + 1) * (s[3] - s[2] + 1)
        // One ring of prefetch, but only when it does not push the cache past its own budget.
        val ring = if (visible <= 12) 1 else 0
        val px0 = (s[0] - ring).coerceAtLeast(0)
        val px1 = (s[1] + ring).coerceAtMost(maxTileX())
        val py0 = (s[2] - ring).coerceAtLeast(0)
        val py1 = (s[3] + ring).coerceAtMost(maxTileY())

        val c = cache
        val tp = tilePx.toFloat()
        val scale = zoom
        val cb = onNeedTile

        for (ty in py0..py1) {
            for (tx in px0..px1) {
                val size = tileSizeAt(tx, ty)
                val tw = size[0]
                val th = size[1]
                val key = tileKey(tx, ty)
                val bmp = if (c != null) c.get(key) else null
                if (bmp != null && bmp.width == tw && bmp.height == th) {
                    canvas.drawBitmap(bmp, tx * tp, ty * tp, tilePaint)
                } else {
                    val isVisible = tx >= s[0] && tx <= s[1] && ty >= s[2] && ty <= s[3]
                    if (isVisible) {
                        canvas.drawRect(tx * tp, ty * tp, tx * tp + tw, ty * tp + th, placeholder)
                    }
                    if (cb != null) cb(key, docTag, page, scale, tx, ty, tw, th)
                }
            }
        }
    }

    // ---------------- programmatic animations (bench driver) ----------------

    fun animatePanBy(dx: Float, dy: Float, durMs: Long, done: (() -> Unit)? = null) {
        animKind = 1
        animFromX = panX
        animToX = panX + dx
        animFromY = panY
        animToY = panY + dy
        startAnim(durMs, done)
    }

    fun animateZoomTo(target: Float, durMs: Long, done: (() -> Unit)? = null) {
        animKind = 2
        animFromX = zoom
        animToX = target
        startAnim(durMs, done)
    }

    private fun startAnim(durMs: Long, done: (() -> Unit)?) {
        animStart = 0L
        animDur = durMs
        animDone = done
        animating = true
        val ch = Choreographer.getInstance()
        ch.removeFrameCallback(frameCb)
        ch.postFrameCallback(frameCb)
    }

    private fun onAnimFrame(frameTimeNanos: Long) {
        if (animStart == 0L) animStart = frameTimeNanos
        var p = if (animDur <= 0L) 1f else ((frameTimeNanos - animStart) / 1e6f) / animDur
        if (p < 0f) p = 0f
        if (p > 1f) p = 1f
        val e = p * p * (3f - 2f * p) // smoothstep
        when (animKind) {
            1 -> {
                panX = animFromX + (animToX - animFromX) * e
                panY = animFromY + (animToY - animFromY) * e
            }
            2 -> {
                zoom = animFromX + (animToX - animFromX) * e
            }
        }
        invalidate()
        if (p >= 1f) {
            animating = false
            val d = animDone
            animDone = null
            if (d != null) d()
        } else {
            Choreographer.getInstance().postFrameCallback(frameCb)
        }
    }
}
