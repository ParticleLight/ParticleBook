package app.pb.spike2t

import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Spike 2 · native PDF surface with tiled PDFium rendering.
 *
 * Threads:
 *   UI thread        — onDraw only (paint cached bitmaps, request missing tiles)
 *   "pdf-render"     — every PDFium call, strictly serial
 *   "bench"          — scenario orchestration, blocks on latches
 *
 * Starts every scenario automatically, logs to logcat tag PBPERF, prints PBDONE and stays up.
 */
class MainActivity : Activity() {

    private lateinit var view: TileView
    private lateinit var status: TextView

    private val cache = TileCache(32L * 1024 * 1024)
    private lateinit var pdfThread: HandlerThread
    private lateinit var pdfHandler: Handler
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "bench") }

    private val inflight = HashSet<String>()
    private val inflightLock = Any()

    private lateinit var doc1: PdfDoc
    private lateinit var doc2: PdfDoc

    @Volatile private var logTileEvents = false
    private var tileEventCount = 0
    private var refreshHz = 60.0
    private var viewW = 0
    private var viewH = 0
    private var tCreate = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tCreate = System.nanoTime()
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this)
        view = TileView(this)
        view.cache = cache
        view.onNeedTile = { key, tag, pg, sc, tx, ty, tw, th ->
            requestTile(key, tag, pg, sc, tx, ty, tw, th)
        }
        view.onFirstDraw = {
            Perf.time("spike2t", "first_frame", (System.nanoTime() - tCreate) / 1e6)
        }
        root.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        status = TextView(this)
        status.setTextColor(0xFFFFFFFF.toInt())
        status.setBackgroundColor(0xCC000000.toInt())
        status.textSize = 11f
        status.setPadding(14, 10, 14, 10)
        status.text = "spike2t init"
        root.addView(status, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START))
        setContentView(root)

        pdfThread = HandlerThread("pdf-render")
        pdfThread.start()
        pdfHandler = Handler(pdfThread.looper)
        doc1 = PdfDoc(this, "d1")
        doc2 = PdfDoc(this, "d2")

        try {
            refreshHz = windowManager.defaultDisplay.refreshRate.toDouble()
        } catch (e: Throwable) {
        }
        Perf.time("spike2t", "refresh_hz", refreshHz)

        val autorun = intent?.getBooleanExtra("autorun", true) ?: true
        val scenario = intent?.getStringExtra("scenario")
        if (autorun) root.postDelayed({ start(scenario) }, 500)
    }

    // ------------------------------------------------------------------ plumbing

    private fun <T> uiVal(block: () -> T): T {
        var res: T? = null
        var err: Throwable? = null
        val latch = CountDownLatch(1)
        runOnUiThread {
            try {
                res = block()
            } catch (e: Throwable) {
                err = e
            } finally {
                latch.countDown()
            }
        }
        latch.await(30, TimeUnit.SECONDS)
        val e = err
        if (e != null) throw e
        @Suppress("UNCHECKED_CAST")
        return res as T
    }

    private fun ui(block: () -> Unit) {
        uiVal { block() }
    }

    private fun <T> pdf(block: () -> T): T {
        var res: T? = null
        var err: Throwable? = null
        val latch = CountDownLatch(1)
        pdfHandler.post {
            try {
                res = block()
            } catch (e: Throwable) {
                err = e
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(120, TimeUnit.SECONDS)) throw IllegalStateException("pdf thread timeout")
        val e = err
        if (e != null) throw e
        @Suppress("UNCHECKED_CAST")
        return res as T
    }

    private fun awaitViewportComplete(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (uiVal { view.viewportComplete() }) return true
            Thread.sleep(8L)
        }
        return false
    }

    /** Posts one Choreographer callback on the UI thread; returns the vsync time of that frame. */
    private fun awaitFrame(): Long {
        val out = LongArray(1)
        val latch = CountDownLatch(1)
        runOnUiThread {
            view.invalidate()
            Choreographer.getInstance().postFrameCallback { ft ->
                out[0] = ft
                latch.countDown()
            }
        }
        if (!latch.await(15, TimeUnit.SECONDS)) return System.nanoTime()
        return if (out[0] == 0L) System.nanoTime() else out[0]
    }

    private fun startAnimAndWait(start: ((() -> Unit) -> Unit)): Boolean {
        val latch = CountDownLatch(1)
        runOnUiThread { start { latch.countDown() } }
        return latch.await(60, TimeUnit.SECONDS)
    }

    private fun pssKb(): LongArray {
        return try {
            val mi = Debug.MemoryInfo()
            Debug.getMemoryInfo(mi)
            // getMemoryStat() needs the debug.memory_info.* sysprops and returns null on a normal
            // build (that is where run 1's native_kb:-1 came from). The deprecated-but-populated
            // fields come from the same /proc walk and are always available.
            val pss = mi.totalPss.toLong()
            val nat = mi.nativePss.toLong()
            val jav = mi.dalvikPss.toLong()
            longArrayOf(pss, nat, jav)
        } catch (e: Throwable) {
            longArrayOf(-1L, -1L, -1L)
        }
    }

    private fun fmt(d: Double): String = String.format(Locale.US, "%.1f", d)
    private fun fmt3(d: Float): String = String.format(Locale.US, "%.3f", d)

    private fun updateStatus(t: String) {
        runOnUiThread {
            try {
                status.text = t
            } catch (e: Throwable) {
            }
        }
    }

    private fun requestTile(key: String, tag: String, pg: Int, scale: Float, tx: Int, ty: Int, tw: Int, th: Int) {
        synchronized(inflightLock) {
            if (inflight.contains(key)) return
            inflight.add(key)
        }
        val isDoc1 = tag == doc1.tag
        val doc = if (isDoc1) doc1 else doc2
        val tilePx = view.tilePx
        pdfHandler.post {
            var ms = -1.0
            var err: String? = null
            try {
                val t0 = System.nanoTime()
                val b = doc.renderTile(pg, scale, tilePx, tx, ty, tw, th)
                ms = (System.nanoTime() - t0) / 1e6
                if (b != null) cache.put(key, b) else err = "null"
            } catch (e: Throwable) {
                err = e.javaClass.name
                Log.w(Perf.TAG, "tile render failed " + key, e)
            } finally {
                synchronized(inflightLock) { inflight.remove(key) }
            }
            if (logTileEvents && isDoc1) {
                tileEventCount++
                Perf.event("T1", "tile", pg, tw, th, ms, "tx:" + tx + ",ty:" + ty + ",key:" + key + (if (err != null) ",error:" + err else ""))
            }
            runOnUiThread {
                try {
                    view.invalidate()
                } catch (e: Throwable) {
                }
            }
        }
    }

    // ------------------------------------------------------------------ driver

    private fun start(scenario: String?) {
        val set: Set<String>? = if (scenario.isNullOrBlank()) null else
            scenario.split(',').map { it.trim().uppercase(Locale.US) }.filter { it.isNotEmpty() }.toSet()

        fun want(s: String): Boolean = set == null || set.isEmpty() || set.contains(s)

        worker.execute {
            try {
                ui {
                    viewW = view.width
                    viewH = view.height
                }
                var tries = 0
                while ((viewW <= 0 || viewH <= 0) && tries < 60) {
                    Thread.sleep(50)
                    ui {
                        viewW = view.width
                        viewH = view.height
                    }
                    tries++
                }
                runBench(set, ::want)
            } catch (e: Throwable) {
                Log.e(Perf.TAG, "bench failed", e)
                Perf.event("T0", "error", -1, -1, -1, 0.0, "error:" + e.javaClass.name)
                updateStatus("FAILED " + e.javaClass.name)
                Perf.done("ALL", 0, "error:" + e.javaClass.simpleName)
            }
        }
    }

    private fun runBench(set: Set<String>?, want: (String) -> Boolean) {
        updateStatus("open seed-03.pdf ...")

        // ---- open (T1 / open) ----
        val openMs = pdf { doc1.open("seed-03.pdf") }
        Perf.event(
            "T1", "open", -1, -1, -1, openMs,
            "pages:" + doc1.pageCount + ",bytes:" + doc1.docBytes +
                ",pt:" + doc1.widthPt(0) + "x" + doc1.heightPt(0) + ",mode:" + doc1.tileMode
        )
        updateStatus("open " + fmt(openMs) + "ms pages=" + doc1.pageCount + " pt=" + doc1.widthPt(0) + "x" + doc1.heightPt(0))

        val fit = viewW.toFloat() / doc1.widthPt(0)

        // ---- T1 first_screen ----
        var t1Runs = 0
        if (want("T1")) {
            tileEventCount = 0
            logTileEvents = true
            val t0 = System.nanoTime()
            ui {
                view.gestureActive = false
                view.setPage(doc1.tag, doc1.widthPt(0), doc1.heightPt(0), 0, fit)
                view.panX = 0f
                view.panY = 0f
                view.invalidate()
            }
            val ok = awaitViewportComplete(20000L)
            val ft = awaitFrame()
            logTileEvents = false
            val tiles = uiVal { view.visibleTileCount() }
            // first_screen = setPage -> every visible tile drawn and one frame presented. It starts
            // *after* open() returned, so it excludes parsing the document.
            // read_path     = open() + first_screen: open a file and get readable text on screen.
            // from_create   = Activity.onCreate -> same point: the cold-start number, which also pays
            //                 for process start, view inflation, thread start, JIT and the autorun delay.
            val firstScreen = (ft - t0) / 1e6
            val readPath = openMs + firstScreen
            Perf.event(
                "T1", "first_screen", 0, viewW, viewH, firstScreen,
                "tiles:" + tiles + ",tileEvents:" + tileEventCount + ",ok:" + (if (ok) 1 else 0) +
                    ",mode:" + doc1.tileMode + ",open_ms:" + fmt(openMs) +
                    ",read_path_ms:" + fmt(readPath) + ",from_create:" + fmt((ft - tCreate) / 1e6)
            )
            Perf.time("spike2t", "read_path", readPath)
            updateStatus("T1 first_screen " + fmt((ft - t0) / 1e6) + "ms tiles=" + tiles)
            t1Runs = 1
            Perf.done("T1", t1Runs)
        }

        // ---- tiling correctness probe (instrumentation, not a benchmark) ----
        val probe = try {
            pdf { doc1.probeTiling(0, fit, 512) }
        } catch (e: Throwable) {
            "mode:" + doc1.tileMode + ",probe:error:" + e.javaClass.name
        }
        Perf.event("T1", "tileprobe", 0, 512, 512, 0.0, probe)
        updateStatus("tileprobe " + probe)

        // Low-res full-page placeholder used during zoom gestures.
        val lowresMs = try {
            pdf {
                val lw = 360
                val lh = (lw.toDouble() * doc1.heightPt(0) / doc1.widthPt(0)).roundToInt().coerceAtLeast(16)
                val t0 = System.nanoTime()
                val b = doc1.renderFull(0, lw, lh)
                val dt = (System.nanoTime() - t0) / 1e6
                ui { view.lowRes = b }
                dt
            }
        } catch (e: Throwable) {
            -1.0
        }
        Perf.event("T1", "lowres", 0, 360, -1, lowresMs, "px:360x" + (360.0 * doc1.heightPt(0) / doc1.widthPt(0)).roundToInt())

        if (want("T2")) runT2(fit)
        if (want("T3")) runT3(fit)
        if (want("T4")) runT4(fit)
        if (want("T5")) runT5(fit)
        if (want("T6")) runT6()
        if (want("T7")) runT7(fit)

        // Leave a clean, fully rendered screen up for screenshots. When T7 ran we leave it at 2x
        // zoom, panned to the middle of the page, so the screenshot is visual proof that a zoomed
        // tile grid has no seams, blanks or misalignment.
        val demoZoom = if (want("T7")) 2f else 1f
        ui {
            view.gestureActive = false
            view.abMode = 0 // T3 leaves the last config (background only) on; restore real tiles
            view.setPage(doc1.tag, doc1.widthPt(0), doc1.heightPt(0), 0, fit * demoZoom)
            if (demoZoom > 1f) {
                view.panX = (doc1.widthPt(0) * fit * demoZoom - viewW) / 2f
                view.panY = (doc1.heightPt(0) * fit * demoZoom - viewH) / 2f
            } else {
                view.panX = 0f
                view.panY = 0f
            }
            view.invalidate()
        }
        awaitViewportComplete(20000L)
        awaitFrame()
        val tiles = uiVal { view.visibleTileCount() }
        val p = pssKb()
        Perf.mem("ALL", "pss", p[0], p[1], p[2])
        updateStatus("DONE  view=" + viewW + "x" + viewH + "  tiles=" + tiles + "  cache=" + cache.bytes / 1024 + "KB")
        val total = t1Runs + 3 + 11 + 5 + 20 + 5 + 12
        Perf.done("ALL", total)
    }

    // ---- T2 tile_cost: five questions, all at fit width unless stated ----
    //  a) page-handle effect: same tile, page re-opened per render vs one PdfPage kept open
    //  b) tile-size sweep 512/1024/2048: is a tile's cost proportional to its own area, or to the page?
    //  c) overload: matrix+clip vs the classic offset overload, same tile, pixel-compared first
    //  d) zoom 1x/2x/3x: the protocol number
    //  e) N-tile serial total vs one full-page render at the same zoom: the fixed per-call overhead,
    //     which is what actually decides whether tiling is worth doing
    private fun runT2(fit: Float) {
        updateStatus("T2 tile_cost ...")
        val reps = 5
        var runs = 0
        val fw1 = (doc1.widthPt(0) * fit).roundToInt().coerceAtLeast(1)
        val fh1 = (doc1.heightPt(0) * fit).roundToInt().coerceAtLeast(1)

        // Renders `reps` timed samples plus one unreported warmup and emits one `phase`_`stat` line.
        fun sweep(sc: String, phase: String, extra: String, w: Int, h: Int, render: () -> Bitmap?): Double {
            try {
                render()?.recycle()
            } catch (e: Throwable) {
            }
            val samples = ArrayList<Double>(reps)
            var err = ""
            for (k in 0 until reps + 1) {
                val r = try {
                    pdf {
                        val t0 = System.nanoTime()
                        val b = render()
                        val dt = (System.nanoTime() - t0) / 1e6
                        if (b != null) b.recycle()
                        dt
                    }
                } catch (e: Throwable) {
                    err = ",error:" + e.javaClass.name
                    -1.0
                }
                if (r >= 0) {
                    if (k > 0) samples.add(r)
                    Perf.event(sc, phase, 0, w, h, r, extra + (if (k == 0) ",warm:1" else ""))
                }
                runs++
            }
            Perf.event(
                sc, phase + "_stat", 0, w, h, Stats.mean(samples),
                extra + err + "," + Stats.line(samples, refreshHz)
            )
            return Stats.mean(samples)
        }

        // (a) page-handle effect ------------------------------------------------------------
        for (keep in booleanArrayOf(false, true)) {
            doc1.keepPagesOpen = keep
            sweep(
                "T2", "tile", "zoom:1.0,tx:0,ty:0,kpx:256,ab:pagehandle,pagehandle:" + (if (keep) "cached" else "fresh"),
                512, 512
            ) { doc1.renderTile(0, fit, 512, 0, 0, 512, 512) }
        }
        doc1.keepPagesOpen = true

        // (b) tile-size sweep ---------------------------------------------------------------
        for (tp in intArrayOf(512, 1024, 2048)) {
            val tw = min(tp, fw1)
            val th = min(tp, fh1)
            sweep(
                "T2", "tile", "zoom:1.0,tx:0,ty:0,tilesize:" + tp + ",kpx:" + ((tw * th) / 1000) + ",ab:tilesize",
                tw, th
            ) { doc1.renderTile(0, fit, tp, 0, 0, tw, th) }
        }

        // (c) overload comparison -----------------------------------------------------------
        val ovlProbe = try {
            pdf { doc1.probeOverloads(0, fit, 512) }
        } catch (e: Throwable) {
            "probe:error:" + e.javaClass.name
        }
        Perf.event("T2", "overload_probe", 0, 512, 512, 0.0, ovlProbe)
        for (tp in intArrayOf(512, 1024)) {
            val tw = min(tp, fw1)
            val th = min(tp, fh1)
            sweep("T2", "tile", "zoom:1.0,tx:0,ty:0,tilesize:" + tp + ",ab:overload,overload:matrix", tw, th) {
                doc1.renderTile(0, fit, tp, 0, 0, tw, th)
            }
            sweep("T2", "tile", "zoom:1.0,tx:0,ty:0,tilesize:" + tp + ",ab:overload,overload:offset", tw, th) {
                doc1.renderTileOffset(0, fit, tp, 0, 0, tw, th)
            }
        }

        // (d) per-zoom tile cost (the protocol number) --------------------------------------
        for (z in intArrayOf(1, 2, 3)) {
            val sc = fit * z
            val cwPx = doc1.widthPt(0) * sc
            val chPx = doc1.heightPt(0) * sc
            val maxTx = max(0, ((cwPx - 1f) / 512).toInt())
            val maxTy = max(0, ((chPx - 1f) / 512).toInt())
            val tx = min(1, maxTx)
            val ty = min(1, maxTy)
            val tw = min(512, (cwPx - tx * 512f).roundToInt().coerceAtLeast(1))
            val th = min(512, (chPx - ty * 512f).roundToInt().coerceAtLeast(1))
            sweep(
                "T2", "tile", "zoom:" + z + ".0,tx:" + tx + ",ty:" + ty + ",kpx:" + ((tw * th) / 1000) +
                    ",scale:" + fmt3(sc) + ",pagehandle:cached", tw, th
            ) { doc1.renderTile(0, sc, 512, tx, ty, tw, th) }

            // Full-page reference: how much the tiled path avoids at the same zoom.
            val fwz = cwPx.roundToInt()
            val fhz = chPx.roundToInt()
            val fullMs = try {
                pdf {
                    val t0 = System.nanoTime()
                    val b = doc1.renderFull(0, fwz, fhz)
                    val dt = (System.nanoTime() - t0) / 1e6
                    if (b != null) b.recycle()
                    dt
                }
            } catch (e: Throwable) {
                -1.0
            }
            Perf.event(
                "T2", "full_ref", 0, fwz, fhz, fullMs,
                "zoom:" + z + ".0,px:" + fwz + "x" + fhz + ",pagehandle:cached"
            )
        }

        // (e) N-tile serial total vs one full-page render -----------------------------------
        val scQ = fit * 1.25f
        val cwQ = (doc1.widthPt(0) * scQ).roundToInt().coerceAtLeast(1)
        val chQ = (doc1.heightPt(0) * scQ).roundToInt().coerceAtLeast(1)
        val cols = (cwQ + 511) / 512
        val rows = (chQ + 511) / 512
        var tilesMs = -1.0
        var fullMsQ = -1.0
        try {
            // two passes; the second is the reported one so the first absorbs cache warm-up
            var last = -1.0
            for (pass in 0 until 2) {
                last = pdf {
                    val t0 = System.nanoTime()
                    for (r in 0 until rows) {
                        for (c in 0 until cols) {
                            val tw = min(512, cwQ - c * 512)
                            val th = min(512, chQ - r * 512)
                            doc1.renderTile(0, scQ, 512, c, r, tw, th)?.recycle()
                        }
                    }
                    (System.nanoTime() - t0) / 1e6
                }
            }
            tilesMs = last
            var lastF = -1.0
            for (pass in 0 until 2) {
                lastF = pdf {
                    val t0 = System.nanoTime()
                    val b = doc1.renderFull(0, cwQ, chQ)
                    val dt = (System.nanoTime() - t0) / 1e6
                    if (b != null) b.recycle()
                    dt
                }
            }
            fullMsQ = lastF
        } catch (e: Throwable) {
        }
        val nTiles = rows * cols
        Perf.event(
            "T2", "tiles_serial", 0, cwQ, chQ, tilesMs,
            "tiles:" + nTiles + ",zoom:1.25,cols:" + cols + ",rows:" + rows
        )
        Perf.event("T2", "full", 0, cwQ, chQ, fullMsQ, "zoom:1.25,tiles:" + nTiles)
        if (tilesMs > 0 && fullMsQ > 0) {
            Perf.event(
                "T2", "tile_overhead", 0, cwQ, chQ, tilesMs - fullMsQ,
                "tiles:" + nTiles + ",zoom:1.25,ratio:" + String.format(Locale.US, "%.2f", tilesMs / fullMsQ) +
                    ",per_tile:" + String.format(Locale.US, "%.1f", tilesMs / nTiles) +
                    ",full_ms:" + String.format(Locale.US, "%.1f", fullMsQ)
            )
        }
        // (f) cold -> warm curve: is full-page 1080x1529 = 580 ms the real cost, or first touch?
        // Page 0 has been rendered many times by now; page 1 is fresh, so the two curves separate
        // PDFium's one-off page work (content-stream parse, font/image decode) from steady state.
        for (pg in intArrayOf(0, 1)) {
            val fwp = (doc1.widthPt(pg) * fit).roundToInt().coerceAtLeast(1)
            val fhp = (doc1.heightPt(pg) * fit).roundToInt().coerceAtLeast(1)
            for (seq in 0 until 5) {
                val ms = try {
                    pdf {
                        val t0 = System.nanoTime()
                        val b = doc1.renderFull(pg, fwp, fhp)
                        val dt = (System.nanoTime() - t0) / 1e6
                        if (b != null) b.recycle()
                        dt
                    }
                } catch (e: Throwable) {
                    -1.0
                }
                Perf.event(
                    "T2", "full_curve", pg, fwp, fhp, ms,
                    "seq:" + seq + ",zoom:1.0,annot:0,pagehandle:" + (if (doc1.keepPagesOpen) "cached" else "fresh")
                )
                runs++
            }
        }
        updateStatus("T2 done")
        Perf.done("T2", runs)
    }

    // ---- T3 pan: screen-width pan at 2x, frames from Choreographer, three A/B render modes ----
    // ---- T7 arch: viewport band vs N tiles vs one full page, same visible pixels ----
    // The question L3 has to answer: if a tile costs nothing beyond the pixels it rasterises, is a
    // fixed 512 px grid worth anything over ONE bitmap the size of the viewport? All three paths
    // render at pan (0,0) so they cover exactly the same on-screen area; only (a) and (b) do work
    // for pixels nobody sees. Also prints the memory each architecture keeps resident.
    private fun runT7(fit: Float) {
        updateStatus("T7 arch ...")
        val reps = 3
        var runs = 0

        fun timed(extra: String, w: Int, h: Int, body: () -> Bitmap?): Double {
            try {
                body()?.recycle()
            } catch (e: Throwable) {
            }
            val samples = ArrayList<Double>(reps)
            var err = ""
            for (k in 0 until reps + 1) {
                val r = try {
                    pdf {
                        val t0 = System.nanoTime()
                        val b = body()
                        val dt = (System.nanoTime() - t0) / 1e6
                        if (b != null) b.recycle()
                        dt
                    }
                } catch (e: Throwable) {
                    err = ",error:" + e.javaClass.name
                    -1.0
                }
                if (r >= 0) {
                    if (k > 0) samples.add(r)
                    Perf.event("T7", "arch", 0, w, h, r, extra + ",rep:" + k)
                    runs++
                }
            }
            Perf.event("T7", "arch_stat", 0, w, h, Stats.mean(samples), extra + "," + Stats.line(samples, refreshHz) + err)
            return Stats.mean(samples)
        }

        // Resident-memory arithmetic for the "3 pages open" case, in MB. The band's size depends on
        // the viewport, not on the page, so it does not grow with zoom; a full-page bitmap does.
        val fullFitMb = doc1.widthPt(0) * fit * doc1.heightPt(0) * fit * 4 / 1048576.0
        val bandMb = viewW.toDouble() * viewH * 4 / 1048576.0
        Perf.event(
            "T7", "mem", -1, viewW, viewH, 0.0,
            String.format(
                Locale.US,
                "fit_page_mb:%.2f,band_mb:%.2f,3_full_pages_mb:%.2f,3_bands_mb:%.2f,tile_cache_limit_mb:32.00",
                fullFitMb, bandMb, fullFitMb * 3, bandMb * 3
            )
        )

        for (z in intArrayOf(2, 3)) {
            val sc = fit * z
            val fw = (doc1.widthPt(0) * sc).roundToInt().coerceAtLeast(1)
            val fh = (doc1.heightPt(0) * sc).roundToInt().coerceAtLeast(1)
            val bw = min(viewW, fw)
            val bh = min(viewH, fh)
            val cols = (bw + 511) / 512
            val rows = (bh + 511) / 512
            // Warm the page once so we measure the steady state, not the page parse.
            try {
                pdf { doc1.renderFull(0, 64, 64)?.recycle() }
            } catch (e: Throwable) {
            }

            val fullMs = timed(
                "ab:full,zoom:" + z + ",px:" + fw + "x" + fh + ",mb:" + (fw * fh * 4 / 1048576),
                fw, fh
            ) { doc1.renderFull(0, fw, fh) }

            val tileMs = timed(
                "ab:tiles,zoom:" + z + ",tiles:" + (cols * rows) + ",px:" + bw + "x" + bh,
                bw, bh
            ) {
                var last: Bitmap? = null
                for (rr in 0 until rows) {
                    for (cc in 0 until cols) {
                        val tw = min(512, bw - cc * 512)
                        val th = min(512, bh - rr * 512)
                        if (tw <= 0 || th <= 0) continue
                        val b = doc1.renderTileOffset(0, sc, 512, cc, rr, tw, th)
                        if (b != null) {
                            last?.recycle()
                            last = b
                        }
                    }
                }
                last
            }

            val bandMs = timed(
                "ab:band,zoom:" + z + ",px:" + bw + "x" + bh + ",mb:" + (bw * bh * 4 / 1048576),
                bw, bh
            ) { doc1.renderTileOffset(0, sc, 512, 0, 0, bw, bh) }

            val ratio = String.format(
                Locale.US, "zoom:%d,band_over_tiles:%.3f,band_over_full:%.3f,tiles_over_full:%.3f",
                z, if (tileMs > 0) bandMs / tileMs else -1.0,
                if (fullMs > 0) bandMs / fullMs else -1.0,
                if (fullMs > 0) tileMs / fullMs else -1.0
            )
            Perf.event(
                "T7", "arch_ratio", 0, bw, bh, bandMs,
                ratio + ",band_ms:" + String.format(Locale.US, "%.1f", bandMs) +
                    ",tiles_ms:" + String.format(Locale.US, "%.1f", tileMs) +
                    ",full_ms:" + String.format(Locale.US, "%.1f", fullMs)
            )
        }
        updateStatus("T7 done")
    }

    private fun runT3(fit: Float) {
        updateStatus("T3 pan ...")
        val sc = fit * 2f
        ui {
            view.gestureActive = false
            cache.evictMatching { it.startsWith(doc1.tag + "#") }
            view.setPage(doc1.tag, doc1.widthPt(0), doc1.heightPt(0), 0, sc)
            view.panX = 0f
            view.panY = 0f
            view.invalidate()
        }
        awaitViewportComplete(20000L)
        var runs = 0
        // abMode 0 = tiles (the real path), 2 = one low-res full-page bitmap, 1 = background only.
        // The two stripped-down modes bracket how much of the frame interval is this view's own
        // bitmap blitting on a software-GL emulator rather than anything PDFium does.
        for (cfg in arrayOf(Triple(0, 5, "tiles"), Triple(2, 3, "lowres"), Triple(1, 3, "bg"))) {
            val mode = cfg.first
            val reps = cfg.second
            val tag = cfg.third
            val all = ArrayList<Double>()
            ui {
                view.abMode = mode
                view.panX = 0f
                view.panY = 0f
                view.invalidate()
            }
            awaitFrame()
            for (r in 0 until reps) {
                val sampler = FrameSampler()
                val forward = r % 2 == 0
                startAnimAndWait { done ->
                    sampler.start()
                    view.animatePanBy(if (forward) viewW.toFloat() else -viewW.toFloat(), 0f, 400L, done)
                }
                val samples = uiVal { sampler.stop() }
                all.addAll(samples)
                Perf.event(
                    "T3", "frame_stat", 0, viewW, viewH, Stats.mean(samples),
                    "rep:" + r + ",zoom:2.0,ab:" + tag + "," + Stats.line(samples, refreshHz)
                )
                runs++
            }
            Perf.event(
                "T3", "frame_stat_all", 0, viewW, viewH, Stats.mean(all),
                "reps:" + reps + ",zoom:2.0,ab:" + tag + ",tiles:" + uiVal { view.visibleTileCount() } +
                    "," + Stats.line(all, refreshHz)
            )
            updateStatus("T3 " + tag + " n=" + all.size + " avg=" + fmt(Stats.mean(all)) + "ms")
        }
        ui { view.abMode = 0 }
        Perf.done("T3", runs)
    }

    // ---- T4 zoom: gesture with low-res placeholder, then hi-res re-render ----
    private fun runT4(fit: Float) {
        updateStatus("T4 zoom ...")
        val reps = 5
        for (r in 0 until reps) {
            ui {
                view.gestureActive = false
                cache.evictMatching { it.startsWith(doc1.tag + "#") }
                view.setPage(doc1.tag, doc1.widthPt(0), doc1.heightPt(0), 0, fit)
                view.panX = 0f
                view.panY = 0f
                view.invalidate()
            }
            awaitViewportComplete(20000L)
            val sampler = FrameSampler()
            ui {
                sampler.start()
                view.gestureActive = true
                view.invalidate()
            }
            startAnimAndWait { done -> ui { view.animateZoomTo(fit * 3f, 400L, done) } }
            val tilesHi = uiVal { view.visibleTileCount() }
            startAnimAndWait { done -> ui { view.animateZoomTo(fit, 400L, done) } }
            val gs = uiVal { sampler.stop() }
            Perf.event(
                "T4", "gesture_stat", 0, viewW, viewH, Stats.mean(gs),
                "rep:" + r + ",zlo:" + fmt3(fit) + ",zhi:" + fmt3(fit * 3f) + ",tiles_hi:" + tilesHi +
                    "," + Stats.line(gs, refreshHz)
            )
            // Release: clear tiles, re-render hi-res at the new zoom.
            val t0 = System.nanoTime()
            ui {
                view.gestureActive = false
                cache.evictMatching { it.startsWith(doc1.tag + "#") }
                view.setPage(doc1.tag, doc1.widthPt(0), doc1.heightPt(0), 0, fit)
                view.panX = 0f
                view.panY = 0f
                view.invalidate()
            }
            val ok = awaitViewportComplete(20000L)
            val ft = awaitFrame()
            val tiles = uiVal { view.visibleTileCount() }
            Perf.event(
                "T4", "rerender", 0, viewW, viewH, (ft - t0) / 1e6,
                "rep:" + r + ",tiles:" + tiles + ",tiles_hi:" + tilesHi + ",ok:" + (if (ok) 1 else 0)
            )
            updateStatus("T4 rep" + r + " g_n=" + gs.size + " rerender=" + fmt((ft - t0) / 1e6) + "ms")
        }
        Perf.done("T4", reps)
    }

    // ---- T5 mem: 20 pan+zoom cycles under the 32 MB cap ----
    private fun runT5(fit: Float) {
        updateStatus("T5 mem ...")
        val zoomHi = fit * 2f
        ui {
            view.gestureActive = false
            cache.clear()
            view.setPage(doc1.tag, doc1.widthPt(0), doc1.heightPt(0), 0, fit)
            view.panX = 0f
            view.panY = 0f
            view.invalidate()
        }
        awaitViewportComplete(20000L)
        val p0 = pssKb()
        Perf.mem("T5", "pss", p0[0], p0[1], p0[2])
        val hit0 = cache.hits
        val ev0 = cache.evicts
        val miss0 = cache.misses
        val cycles = 20
        for (i in 0 until cycles) {
            val sc = if (i % 2 == 0) fit * 2f else fit
            ui {
                view.gestureActive = false
                view.setPage(doc1.tag, doc1.widthPt(0), doc1.heightPt(0), 0, sc)
                view.panX = 0f
                view.panY = 0f
                view.invalidate()
            }
            startAnimAndWait { done -> ui { view.animatePanBy(viewW.toFloat(), 0f, 150L, done) } }
            ui {
                view.gestureActive = true
                view.invalidate()
            }
            startAnimAndWait { done -> ui { view.animateZoomTo(zoomHi, 200L, done) } }
            ui { view.gestureActive = false }
            startAnimAndWait { done -> ui { view.animateZoomTo(fit, 200L, done) } }
            if (i % 5 == 4) updateStatus("T5 " + (i + 1) + "/" + cycles + " cache=" + cache.bytes / 1024 + "KB")
        }
        val p1 = pssKb()
        Perf.mem("T5", "pss", p1[0], p1[1], p1[2])
        Perf.event(
            "T5", "cache", -1, -1, -1, 0.0,
            "hit:" + (cache.hits - hit0) + ",evict:" + (cache.evicts - ev0) + ",miss:" + (cache.misses - miss0) +
                ",bytes:" + cache.bytes + ",limit:" + cache.limitBytes() + ",tiles:" + cache.count() + ",cycles:" + cycles
        )
        Perf.done("T5", cycles)
    }

    // ---- T6 turn_tile: page turns through the tile path ----
    private fun runT6() {
        updateStatus("T6 turn_tile ...")
        val openMs = pdf { doc2.open("realbook.pdf") }
        Perf.event(
            "T6", "open", -1, -1, -1, openMs,
            "pages:" + doc2.pageCount + ",bytes:" + doc2.docBytes +
                ",pt:" + doc2.widthPt(0) + "x" + doc2.heightPt(0) + ",mode:" + doc2.tileMode
        )
        val turns = 5
        for (i in 0 until turns) {
            val pg = i % doc2.pageCount
            val f2 = viewW.toFloat() / doc2.widthPt(pg)
            val t0 = System.nanoTime()
            ui {
                view.gestureActive = false
                view.setPage(doc2.tag, doc2.widthPt(pg), doc2.heightPt(pg), pg, f2)
                view.panX = 0f
                view.panY = 0f
                view.invalidate()
            }
            val ok = awaitViewportComplete(20000L)
            val ft = awaitFrame()
            val tiles = uiVal { view.visibleTileCount() }
            Perf.event(
                "T6", "first_screen", pg, viewW, viewH, (ft - t0) / 1e6,
                "turn:" + i + ",tiles:" + tiles + ",ok:" + (if (ok) 1 else 0) + ",pt:" + doc2.widthPt(pg) + "x" + doc2.heightPt(pg)
            )
            updateStatus("T6 turn" + i + " page" + pg + " " + fmt((ft - t0) / 1e6) + "ms")
        }
        val p = pssKb()
        Perf.mem("T6", "pss", p[0], p[1], p[2])
        Perf.done("T6", turns)
    }
}
