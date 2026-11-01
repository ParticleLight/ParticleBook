package app.pb.spike2t

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfPage
import io.legere.pdfiumandroid.PdfiumCore
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Thin PDFium wrapper for tiled rendering.
 *
 * Tile geometry: the page is covered by a grid of tilePx x tilePx cells laid out in *content*
 * pixel space (1 content px == 1 screen px at the current zoom). A tile is rendered with
 * FPDF_RenderPageBitmapWithMatrix wrapped by PdfPage.renderPageBitmap(bitmap, matrix, clip, ...):
 *   device = scale * pagePt + translate,  translate = -(tileOriginInContentPx)
 *   clip    = (0,0,tileW,tileH)  in device space
 * so PDFium's renderer clips away every object outside the tile and only pays for the tile area.
 *
 * tileMode == "crop" is the fallback used when the matrix path is unavailable or geometrically
 * wrong: it renders the whole page and crops. Costs full-page work per tile — never desired, and
 * reported honestly in the log (mode:... field).
 */
class PdfDoc(private val context: Context, val tag: String) {
    private val core = PdfiumCore(context)
    private var doc: PdfDocument? = null

    var pageCount = 0
        private set
    var docBytes = 0
        private set
    var tileMode = "matrix"
        private set

    private val wpt = ArrayList<Int>()
    private val hpt = ArrayList<Int>()

    /**
     * Reusing the open FPDF_PAGE keeps PDFium's per-page render cache (decoded images, parsed
     * fonts, content-stream objects) alive across tile renders. Re-opening the page for every tile
     * throws that cache away and made tiles 5-10x more expensive in the first run.
     */
    private val pages = object : LinkedHashMap<Int, PdfPage>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PdfPage>): Boolean {
            if (size > 8) {
                eldest.value.close()
                return true
            }
            return false
        }
    }

    /** A/B switch: false reproduces the naive "open/close the page per render" pipeline. */
    var keepPagesOpen = true

    /**
     * renderAnnot maps to FPDF_FFLDraw (form-fill layer init + draw), which costs 5-30% on pages
     * with no form fields and is pure overhead for a reader that renders links itself.
     * The 3rd/4th booleans are renderAnnot and textMask; textMask is unread in 1.0.24.
     */
    var renderAnnot = false

    private fun pageFor(idx: Int): PdfPage {
        val d = doc ?: throw IllegalStateException("doc closed")
        return if (keepPagesOpen) {
            pages.getOrPut(idx) { d.openPage(idx) }
        } else {
            d.openPage(idx)
        }
    }

    fun open(asset: String): Double {
        val t0 = System.nanoTime()
        val data = context.assets.open(asset).use { it.readBytes() }
        docBytes = data.size
        val d = core.newDocument(data)
        doc = d
        pageCount = d.getPageCount()
        wpt.clear()
        hpt.clear()
        for (i in 0 until pageCount) {
            val p = d.openPage(i)
            try {
                wpt.add(p.getPageWidthPoint())
                hpt.add(p.getPageHeightPoint())
            } finally {
                p.close()
            }
        }
        return (System.nanoTime() - t0) / 1e6
    }

    fun widthPt(page: Int): Int = wpt[page]
    fun heightPt(page: Int): Int = hpt[page]

    /** Render one tile. tw/th are the clipped pixel size at the page's right/bottom edge. */
    fun renderTile(page: Int, scale: Float, tilePx: Int, tx: Int, ty: Int, tw: Int, th: Int): Bitmap? {
        if (doc == null) return null
        if (tw <= 0 || th <= 0) return null
        val p = pageFor(page)
        try {
            if (tileMode == "matrix") {
                val bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                val m = Matrix()
                m.setScale(scale, scale)
                m.postTranslate(-(tx.toFloat() * tilePx), -(ty.toFloat() * tilePx))
                val clip = RectF(0f, 0f, tw.toFloat(), th.toFloat())
                p.renderPageBitmap(bmp, m, clip, renderAnnot, false)
                return bmp
            }
            // Fallback: full page, then crop.
            val fw = (wpt[page] * scale).roundToInt().coerceAtLeast(1)
            val fh = (hpt[page] * scale).roundToInt().coerceAtLeast(1)
            val full = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888)
            full.eraseColor(Color.WHITE)
            p.renderPageBitmap(full, 0, 0, fw, fh, renderAnnot, false)
            val ox = min(tx * tilePx, fw - 1)
            val oy = min(ty * tilePx, fh - 1)
            val cw = min(tw, fw - ox)
            val ch = min(th, fh - oy)
            val out = Bitmap.createBitmap(full, ox, oy, cw, ch)
            full.recycle()
            return out
        } finally {
            if (!keepPagesOpen) p.close()
        }
    }

    /**
     * The same tile through the classic offset overload (FPDF_RenderPageBitmap). The bitmap is
     * tile-sized; startX/startY are the negated tile origin and drawSize is the *whole* scaled page,
     * so PDFium lays the page out at full size and blits only the part that lands in the bitmap.
     */
    fun renderTileOffset(page: Int, scale: Float, tilePx: Int, tx: Int, ty: Int, tw: Int, th: Int): Bitmap? {
        if (doc == null) return null
        if (tw <= 0 || th <= 0) return null
        val p = pageFor(page)
        try {
            val fw = (wpt[page] * scale).roundToInt().coerceAtLeast(1)
            val fh = (hpt[page] * scale).roundToInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            p.renderPageBitmap(bmp, -(tx * tilePx), -(ty * tilePx), fw, fh, renderAnnot, false)
            return bmp
        } finally {
            if (!keepPagesOpen) p.close()
        }
    }

    /**
     * Pixel comparison of the two render overloads on tile (0,0) of the same page/scale. If they
     * agree, switching the tile pipeline to the offset overload cannot change what is on screen.
     */
    fun probeOverloads(page: Int, scale: Float, tilePx: Int): String {
        val fw = (wpt[page] * scale).roundToInt().coerceAtLeast(2)
        val fh = (hpt[page] * scale).roundToInt().coerceAtLeast(2)
        val tw = min(tilePx, fw)
        val th = min(tilePx, fh)
        val a = try {
            renderTile(page, scale, tilePx, 0, 0, tw, th)
        } catch (e: Throwable) {
            return "matrix:error:" + e.javaClass.name
        } ?: return "matrix:null"
        val b = try {
            renderTileOffset(page, scale, tilePx, 0, 0, tw, th)
        } catch (e: Throwable) {
            a.recycle()
            return "offset:error:" + e.javaClass.name
        } ?: run {
            a.recycle()
            return "offset:null"
        }
        val pa = IntArray(tw * th)
        val pb = IntArray(tw * th)
        a.getPixels(pa, 0, tw, 0, 0, tw, th)
        b.getPixels(pb, 0, tw, 0, 0, tw, th)
        a.recycle()
        b.recycle()
        var diff = 0L
        var big = 0L
        for (i in pa.indices) {
            val x = pa[i]
            val y = pb[i]
            val la = ((x shr 16 and 0xFF) + (x shr 8 and 0xFF) + (x and 0xFF)) / 3
            val lb = ((y shr 16 and 0xFF) + (y shr 8 and 0xFF) + (y and 0xFF)) / 3
            val d = abs(la - lb)
            diff += d
            if (d > 32) big++
        }
        val n = pa.size.coerceAtLeast(1)
        return String.format(
            Locale.US, "vdiff:%.4f,bigpx:%.5f,px:%dx%d",
            diff.toDouble() / n, big.toDouble() / n, tw, th
        )
    }

    /** Whole page at an arbitrary pixel size (used for the low-res gesture placeholder and T2 reference). */
    fun renderFull(page: Int, w: Int, h: Int): Bitmap? {
        if (doc == null) return null
        val p = pageFor(page)
        try {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            p.renderPageBitmap(bmp, 0, 0, w, h, renderAnnot, false)
            return bmp
        } finally {
            if (!keepPagesOpen) p.close()
        }
    }

    /**
     * Self-check for the matrix tiling path: render tile (0,0) twice — once via the matrix API and
     * once as a crop of a full-page render — and compare. Switches to "crop" mode if they disagree.
     */
    fun probeTiling(page: Int, scale: Float, tilePx: Int): String {
        val fw = (wpt[page] * scale).roundToInt().coerceAtLeast(2)
        val fh = (hpt[page] * scale).roundToInt().coerceAtLeast(2)
        val full = try {
            renderFull(page, fw, fh)
        } catch (e: Throwable) {
            return "mode:matrix,probe:fullFailed,error:" + e.javaClass.name
        } ?: return "mode:matrix,probe:fullNull"
        val tw = min(tilePx, fw)
        val th = min(tilePx, fh)
        val tile = try {
            renderTile(page, scale, tilePx, 0, 0, tw, th)
        } catch (e: Throwable) {
            full.recycle()
            return "mode:matrix,probe:tileFailed,error:" + e.javaClass.name
        } ?: run {
            full.recycle()
            return "mode:matrix,probe:tileNull"
        }
        var diff = 0L
        var n = 0L
        var inkF = 0L
        var inkT = 0L
        val fp = IntArray(fw)
        val tp = IntArray(tw)
        for (y in 0 until th) {
            full.getPixels(fp, 0, fw, 0, y, fw, 1)
            tile.getPixels(tp, 0, tw, 0, y, tw, 1)
            for (x in 0 until tw) {
                val a = fp[x]
                val b = tp[x]
                val la = ((a shr 16 and 0xFF) + (a shr 8 and 0xFF) + (a and 0xFF)) / 3
                val lb = ((b shr 16 and 0xFF) + (b shr 8 and 0xFF) + (b and 0xFF)) / 3
                diff += abs(la - lb)
                n++
                if (la < 240) inkF++
                if (lb < 240) inkT++
            }
        }
        full.recycle()
        tile.recycle()
        val meanDiff = if (n > 0) diff.toDouble() / n else 999.0
        val inkFull = if (n > 0) inkF.toDouble() / n else 0.0
        val inkTile = if (n > 0) inkT.toDouble() / n else 0.0
        // A correct tile matches the crop to within antialiasing noise. A blank or misplaced tile
        // shows as a large mean difference.
        if (meanDiff > 12.0 || (inkFull > 0.01 && inkTile < inkFull * 0.5)) tileMode = "crop"
        return String.format(
            Locale.US,
            "mode:%s,diff:%.3f,inkFull:%.4f,inkTile:%.4f,px:%dx%d",
            tileMode, meanDiff, inkFull, inkTile, tw, th
        )
    }

    fun close() {
        for (pg in pages.values) {
            try {
                pg.close()
            } catch (e: Throwable) {
            }
        }
        pages.clear()
        try {
            doc?.close()
        } catch (e: Throwable) {
        }
        doc = null
    }
}
