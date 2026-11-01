package app.pb.spike2

import android.app.Activity
import android.app.ActivityManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceHolder
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfiumCore
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Spike2 · S2-A 全页直渲染基准（app.pb.spike2）
 *
 * 唯一数据通道 = logcat tag PBPERF 的结构化行（见 docs/android-port/spike2-protocol.md §3）：
 *   PBPERF|scenario=S2|phase=render|page=0|w=1188|h=1682|ms=42.700|extra=path:argb8888
 *   PBDONE|scenario=S2|runs=10|extra=ok
 *   PBTIME|app=spike2|phase=first_frame|ms=210.500
 *
 * 所有 PDFium 调用都在单条 HandlerThread 上串行执行（库内部有全局锁，多线程也不会更快）。
 * UI 呈现类测量（present / turn）通过 runOnUiThread + Choreographer 在 UI 线程上取首帧。
 *
 * 启动参数：
 *   adb shell am start -n app.pb.spike2/.BenchActivity --es scenario "S2,S4" --ei runs 5
 *   scenario 省略或 "all" = 全部场景；S1 总是先跑（后续场景需要已打开的文档）。
 */
class BenchActivity : Activity(), SurfaceHolder.Callback {

    private lateinit var root: FrameLayout
    private lateinit var iv: ImageView
    private lateinit var sv: SurfaceView
    private lateinit var core: PdfiumCore
    private lateinit var bgThread: HandlerThread
    private lateinit var bg: Handler

    private var doc: PdfDocument? = null       // seed-03.pdf
    private var docReal: PdfDocument? = null   // realbook.pdf
    private var seedBytes = 0
    private var realBytes = 0
    private var seedOpenStartNs = 0L
    private var firstScreenLogged = false
    private var lastPresented: Bitmap? = null

    private val surfaceLatch = CountDownLatch(1)
    @Volatile private var surface: Surface? = null

    private val tBoot = System.nanoTime()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // S7 冷启动快照：在任何 PDFium 调用之前
        pssPoint("S7", "cold")
        core = PdfiumCore(this)

        root = FrameLayout(this)
        iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.rgb(32, 32, 32))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(iv)
        sv = SurfaceView(this).apply {
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        sv.holder.addCallback(this)
        root.addView(sv)
        setContentView(root)

        // launch -> first frame（含冷启；用于和 P0 的 am start -W TotalTime 对照）
        Choreographer.getInstance().postFrameCallback {
            val ms = (System.nanoTime() - tBoot) / 1e6
            Log.i(TAG, String.format(Locale.US, "PBTIME|app=spike2|phase=first_frame|ms=%.3f", ms))
        }

        bgThread = HandlerThread("bench")
        bgThread.start()
        bg = Handler(bgThread.looper)
        bg.post { runRequested() }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { doc?.close() } catch (t: Throwable) { }
        try { docReal?.close() } catch (t: Throwable) { }
        doc = null
        docReal = null
        bgThread.quitSafely()
    }

    // ---------------------------------------------------------------- 场景编排

    private fun runRequested() {
        val raw = intent.getStringExtra("scenario")?.trim().orEmpty()
        val runs = intent.getIntExtra("runs", 5).coerceIn(1, 50)
        val all = listOf("S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8", "S9", "S10")
        val requested: List<String> = when {
            raw.isEmpty() || raw.equals("all", true) -> all
            else -> raw.split(",").map { it.trim().uppercase(Locale.US) }.filter { it.isNotEmpty() }
        }
        // S1 必须最先（后续场景依赖已打开的文档）
        val order = LinkedHashSet<String>()
        order.add("S1")
        order.addAll(requested)

        var executed = 0
        for (code in order) {
            try {
                when (code) {
                    "S1" -> s1Open()
                    "S2" -> s2RenderFull(runs)
                    "S3" -> s3Render565(runs)
                    "S4" -> s4Turn(runs)
                    "S5" -> s5TextLayer()
                    "S6" -> s6DpiScale(runs)
                    "S7" -> s7Pss()
                    "S8" -> s8HardwareBitmap(runs)
                    "S9" -> s9PngLegacy(runs)
                    "S10" -> s10SurfacePage(runs)
                    else -> {
                        perf(code, "unknown", -1, -1, -1, 0.0, "error:no-such-scenario")
                        done(code, 0, "error:unknown-scenario")
                    }
                }
                executed++
            } catch (t: Throwable) {
                Log.e(TAG, "scenario " + code + " failed", t)
                perf(code, "fatal", -1, -1, -1, 0.0, "error:" + t.javaClass.simpleName)
                done(code, 0, "error:" + t.javaClass.simpleName)
            }
        }
        Log.i(TAG, String.format(Locale.US, "PBDONE|scenario=ALL|runs=%d|extra=ok", executed))
    }

    // ---------------------------------------------------------------- S1 open

    private fun openAsset(asset: String): Triple<PdfDocument, Int, Int> {
        val bytes = assets.open(asset).use { it.readBytes() }
        val d = core.newDocument(bytes)
        val p = d.openPage(0)
        val pw: Int
        val ph: Int
        try {
            pw = p.getPageWidthPoint()
            ph = p.getPageHeightPoint()
        } finally {
            p.close()
        }
        return Triple(d, pw, ph)
    }

    private fun s1Open() {
        var n = 0
        for (asset in listOf(ASSET_SEED, ASSET_REAL)) {
            try {
                val t0 = System.nanoTime()
                val (d, pw, ph) = openAsset(asset)
                val ms = (System.nanoTime() - t0) / 1e6
                val pages = d.getPageCount()
                when (asset) {
                    ASSET_SEED -> { seedBytes = d.getPageCount(); doc = d; seedOpenStartNs = t0 }
                    else -> { realBytes = d.getPageCount(); docReal = d }
                }
                perf(
                    "S1", "open", -1, -1, -1, ms,
                    String.format(
                        Locale.US,
                        "asset:%s,pages:%d,pt:%dx%d,mode:bitmap",
                        asset, pages, pw, ph
                    )
                )
                n++
            } catch (t: Throwable) {
                perf("S1", "open", -1, -1, -1, 0.0, "asset:$asset,error:" + t.javaClass.simpleName)
            }
        }
        done("S1", n)
    }

    private fun ensureSeed(): PdfDocument? {
        if (doc == null) {
            try {
                val (d, _, _) = openAsset(ASSET_SEED)
                doc = d
            } catch (t: Throwable) {
                perf("S0", "open", -1, -1, -1, 0.0, "error:" + t.javaClass.simpleName)
                return null
            }
        }
        return doc
    }

    private fun ensureReal(): PdfDocument? {
        if (docReal == null) {
            try {
                val (d, _, _) = openAsset(ASSET_REAL)
                docReal = d
            } catch (t: Throwable) {
                perf("S0", "open", -1, -1, -1, 0.0, "error:" + t.javaClass.simpleName)
                return null
            }
        }
        return docReal
    }

    // ---------------------------------------------------------------- S2 整页 ARGB_8888

    private fun s2RenderFull(runs: Int) {
        val d = ensureSeed() ?: return
        val (w, h) = sizeOverride() ?: SIZES[0]
        val pages = minOf(d.getPageCount(), MAX_PAGES)
        var n = 0
        for (pi in 0 until pages) {
            val p = d.openPage(pi)
            try {
                for (i in 0 until runs) {
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    val t0 = System.nanoTime()
                    p.renderPageBitmap(bmp, 0, 0, w, h, true, false)
                    val renderMs = (System.nanoTime() - t0) / 1e6
                    perf(
                        "S2", "render", pi, w, h, renderMs,
                        String.format(Locale.US, "path:argb8888,cold:%d,annot:1", if (i == 0) 1 else 0)
                    )
                    val presentMs = presentFirstFrame(bmp)
                    perf(
                        "S2", "present", pi, w, h, presentMs,
                        String.format(Locale.US, "path:argb8888,cold:%d", if (i == 0) 1 else 0)
                    )
                    if (!firstScreenLogged) {
                        firstScreenLogged = true
                        // L3「首屏」直接口径：进程启动 -> 第一页上屏
                        Log.i(
                            TAG,
                            String.format(
                                Locale.US, "PBTIME|app=spike2|phase=first_screen|ms=%.3f",
                                (System.nanoTime() - tBoot) / 1e6
                            )
                        )
                        if (seedOpenStartNs > 0) {
                            Log.i(
                                TAG,
                                String.format(
                                    Locale.US, "PBTIME|app=spike2|phase=open_to_first_screen|ms=%.3f",
                                    (System.nanoTime() - seedOpenStartNs) / 1e6
                                )
                            )
                        }
                    }
                    n++
                }
            } finally {
                p.close()
            }
        }
        done("S2", n)
    }

    /** intent extra size=WxH 覆盖 S2 渲染尺寸（默认 1188x1682，与 P0 同口径）。 */
    private fun sizeOverride(): Pair<Int, Int>? {
        val s = intent.getStringExtra("size") ?: return null
        val parts = s.lowercase(Locale.US).split("x")
        if (parts.size != 2) return null
        val w = parts[0].trim().toIntOrNull() ?: return null
        val h = parts[1].trim().toIntOrNull() ?: return null
        if (w <= 0 || h <= 0) return null
        return w to h
    }

    // ---------------------------------------------------------------- S3 RGB_565

    private fun s3Render565(runs: Int) {
        val d = ensureSeed() ?: return
        val (w, h) = SIZES[0]
        var n = 0
        val p = d.openPage(0)
        try {
            for (i in 0 until runs) {
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
                bmp.eraseColor(Color.WHITE)
                val t0 = System.nanoTime()
                p.renderPageBitmap(bmp, 0, 0, w, h, true, false)
                val ms = (System.nanoTime() - t0) / 1e6
                perf(
                    "S3", "render", 0, w, h, ms,
                    String.format(
                        Locale.US, "cfg:rgb565,cold:%d,bytes:%d", if (i == 0) 1 else 0, bmp.byteCount
                    )
                )
                n++
                bmp.recycle()
            }
        } finally {
            p.close()
        }
        done("S3", n)
    }

    // ---------------------------------------------------------------- S4 连续翻页

    private fun s4Turn(runs: Int) {
        val d = ensureReal() ?: return
        val (w, h) = SIZES[1]
        var n = 0
        pssPoint("S4", "before")
        for (rnd in 0 until runs) {
            for (pi in 0 until TURN_PAGES) {
                if (pi >= d.getPageCount()) continue
                val p = d.openPage(pi)
                try {
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    val t0 = System.nanoTime()
                    p.renderPageBitmap(bmp, 0, 0, w, h, true, false)
                    val renderMs = (System.nanoTime() - t0) / 1e6
                    val presentMs = presentFirstFrame(bmp)
                    val totalMs = (System.nanoTime() - t0) / 1e6
                    perf(
                        "S4", "total", pi, w, h, totalMs,
                        String.format(
                            Locale.US, "round:%d,render:%.3f,present:%.3f,cold:%d",
                            rnd, renderMs, presentMs, if (rnd == 0) 1 else 0
                        )
                    )
                    n++
                } finally {
                    p.close()
                }
            }
        }
        pssPoint("S4", "after")
        done("S4", n)
    }

    // ---------------------------------------------------------------- S5 文本层

    private fun s5TextLayer() {
        val d = ensureSeed() ?: return
        var n = 0
        for (pi in 0 until d.getPageCount()) {
            val p = d.openPage(pi)
            try {
                val pw = p.getPageWidthPoint()
                val ph = p.getPageHeightPoint()
                val t0 = System.nanoTime()
                val tp = p.openTextPage()
                val chars: Int
                val boxes: Int
                try {
                    chars = tp.textPageCountChars()
                    var b = 0
                    for (i in 0 until chars) {
                        if (tp.textPageGetCharBox(i) != null) b++
                    }
                    boxes = b
                } finally {
                    tp.close()
                }
                val ms = (System.nanoTime() - t0) / 1e6
                perf("S5", "text", pi, pw, ph, ms, "chars:$chars,boxes:$boxes")
                n++
            } finally {
                p.close()
            }
        }
        done("S5", n)
    }

    // ---------------------------------------------------------------- S6 dpi 曲线

    private fun s6DpiScale(runs: Int) {
        val d = ensureSeed() ?: return
        var n = 0
        val p = d.openPage(0)
        try {
            val pw = p.getPageWidthPoint().toDouble()
            val ph = p.getPageHeightPoint().toDouble()
            for (rnd in 0 until runs) {
                for (dpi in listOf(72, 130, 200)) {
                    val w = Math.round(pw / 72.0 * dpi).toInt().coerceAtLeast(1)
                    val h = Math.round(ph / 72.0 * dpi).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    val t0 = System.nanoTime()
                    p.renderPageBitmap(bmp, 0, 0, w, h, true, false)
                    val ms = (System.nanoTime() - t0) / 1e6
                    perf(
                        "S6", "render", 0, w, h, ms,
                        String.format(
                            Locale.US, "dpi:%d,bytes:%d,cold:%d",
                            dpi, bmp.byteCount, if (rnd == 0) 1 else 0
                        )
                    )
                    n++
                    bmp.recycle()
                }
            }
        } finally {
            p.close()
        }
        done("S6", n)
    }

    // ---------------------------------------------------------------- S7 PSS

    private fun pssPoint(scenario: String, tag: String) {
        try {
            val mi = Debug.MemoryInfo()
            Debug.getMemoryInfo(mi)
            perf(
                scenario, "pss", -1, -1, -1, 0.0,
                String.format(
                    Locale.US, "tag:%s,pss_kb:%d,native_kb:%d,java_kb:%d",
                    tag, mi.totalPss, mi.nativePss, mi.dalvikPss
                )
            )
        } catch (t: Throwable) {
            perf(scenario, "pss", -1, -1, -1, 0.0, "tag:$tag,error:" + t.javaClass.simpleName)
        }
    }

    private fun s7Pss() {
        val d = ensureSeed() ?: return
        pssPoint("S7", "open")
        val (w, h) = SIZES[0]
        val p0 = d.openPage(0)
        try {
            for (i in 0 until 10) {
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                p0.renderPageBitmap(bmp, 0, 0, w, h, true, false)
                bmp.recycle()
            }
        } finally {
            p0.close()
        }
        pssPoint("S7", "10pages")
        for (pi in 0..30) {
            if (pi >= d.getPageCount()) break
            val p = d.openPage(pi)
            try {
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                p.renderPageBitmap(bmp, 0, 0, w, h, true, false)
                bmp.recycle()
            } finally {
                p.close()
            }
        }
        pssPoint("S7", "30pages")
        done("S7", 4)
    }

    // ---------------------------------------------------------------- S8 HARDWARE bitmap（静默失败探测）

    private fun s8HardwareBitmap(runs: Int) {
        val d = ensureSeed() ?: return
        val (w, h) = SIZES[0]
        var n = 0
        var prevCrc = 0L
        var changedCount = 0
        var paintedCount = 0
        var silentCount = 0
        var throwCount = 0
        for (i in 0 until runs) {
            val p = d.openPage(0)
            var ink = -1.0
            var blank = -1.0
            var crc = 0L
            var px8 = "na"
            var err = "none"
            var ms = 0.0
            try {
                // API 35：createBitmap(w,h,Config.HARDWARE) 会抛 "can't create mutable bitmap with Config.HARDWARE"。
                // 唯一可行路径：先建软件位图、再 copy 成 immutable HARDWARE 位图。
                val hw = try {
                    val sw0 = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    sw0.eraseColor(Color.WHITE)
                    val hwCopy = sw0.copy(Bitmap.Config.HARDWARE, false)
                    sw0.recycle()
                    hwCopy
                } catch (t: Throwable) {
                    err = t.javaClass.simpleName
                    null
                }
                if (hw == null) {
                    perf("S8", "render_hw", 0, w, h, 0.0, "cfg:hardware,seq:" + (i + 1) + ",err:" + err + ",verdict:throw")
                    n++
                    continue
                }
                try {
                    val t0 = System.nanoTime()
                    p.renderPageBitmap(hw, 0, 0, w, h, true, false)
                    ms = (System.nanoTime() - t0) / 1e6
                } catch (t: Throwable) {
                    err = t.javaClass.simpleName
                }
                // HARDWARE bitmap 无法 getPixels，用 copy() 读回软件位图（drawBitmap 到软件 Canvas 会被拒）
                val sw = try {
                    hw.copy(Bitmap.Config.ARGB_8888, false)
                } catch (t: Throwable) {
                    if (err == "none") err = t.javaClass.simpleName
                    null
                }
                if (sw != null) {
                    try {
                        // 采样步长 8：整图逐像素太慢，且此处只需白/透明占比 + CRC 指纹
                        val step = 8
                        var y = 0
                        var total = 0L
                        var white = 0L
                        var zero = 0L
                        val row = IntArray(w)
                        val crc32 = java.util.zip.CRC32()
                        val sb = StringBuilder()
                        var sampled = 0
                        while (y < h) {
                            sw.getPixels(row, 0, w, 0, y, w, 1)
                            var x = 0
                            while (x < w) {
                                val c = row[x]
                                total++
                                if (c == 0) zero++
                                if (c == -0x1) white++
                                crc32.update((c ushr 24) xor (c ushr 16) xor (c ushr 8) xor c)
                                if (sampled < 8) {
                                    sb.append(Integer.toHexString(c))
                                    sb.append('/')
                                    sampled++
                                }
                                x += step
                            }
                            y += step
                        }
                        crc = crc32.value
                        px8 = sb.toString()
                        ink = if (total > 0) white.toDouble() / total else -1.0
                        blank = if (total > 0) zero.toDouble() / total else -1.0
                    } finally {
                        sw.recycle()
                    }
                }
                hw.recycle()
            } finally {
                p.close()
            }
            // 判据（Lead 批准的三方交叉的前两项）：白像素占比>=0.5 = painted；整张透明 = silent-fail；
            // 第三项 jniPdfium 日志由 harness 侧 full.log 抓（"Bitmap format must be RGBA_8888 or RGB_565"）。
            val changed = if (i > 0 && crc != prevCrc) 1 else 0
            if (i > 0) changedCount += changed
            prevCrc = crc
            val verdict: String = when {
                err != "none" && ink < 0.0 -> "unreadable"  // HARDWARE 位图读不回像素（预期：PDFium 静默失败）
                err != "none" -> "throw"
                ink >= 0.9999 -> "silent-fail"  // 像素与 eraseColor(WHITE) 完全一致 = PDFium 没画（HARDWARE 无法 lockPixels）
                ms < 10.0 -> "silent-fail"      // 毫秒级返回 = 没真正绘制
                else -> "painted"
            }
            when (verdict) {
                "throw" -> throwCount++
                "painted" -> paintedCount++
                else -> silentCount++
            }
            perf(
                "S8", "render_hw", 0, w, h, ms,
                String.format(
                    Locale.US,
                    "cfg:hardware,hw:1,seq:%d,white:%.4f,blank:%.4f,crc:%s,changed:%d,px8:%s,err:%s,verdict:%s",
                    i + 1, ink, blank, java.lang.Long.toHexString(crc), changed, px8, err, verdict
                )
            )
            n++
        }
        val overall = when {
            throwCount > 0 -> "throw"
            silentCount > 0 && paintedCount == 0 -> "silent-fail"
            else -> "painted"
        }
        done(
            "S8", n,
            String.format(
                Locale.US,
                "verdict:%s,painted:%d,silent:%d,throw:%d,changed:%d,bytes:%d",
                overall, paintedCount, silentCount, throwCount, changedCount, w * h * 4
            )
        )
    }

    // ---------------------------------------------------------------- S9 PNG 旧链路（同批次对照）

    private fun s9PngLegacy(runs: Int) {
        val d = ensureSeed() ?: return
        val (w, h) = SIZES[0]
        var n = 0
        for (i in 0 until runs) {
            val p = d.openPage(0)
            var bmp: Bitmap? = null
            var renderMs = 0.0
            try {
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                val t0 = System.nanoTime()
                p.renderPageBitmap(bmp, 0, 0, w, h, true, false)
                renderMs = (System.nanoTime() - t0) / 1e6
                perf(
                    "S9", "render", 0, w, h, renderMs,
                    String.format(Locale.US, "cold:%d", if (i == 0) 1 else 0)
                )
            } finally {
                p.close()
            }
            if (bmp != null) {
                // 拆开：encode = 纯 PNG 编码（ByteArrayOutputStream），write = 纯落盘 + fsync
                val bos = java.io.ByteArrayOutputStream(4 * 1024 * 1024)
                val t1 = System.nanoTime()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
                val png = bos.toByteArray()
                val encodeMs = (System.nanoTime() - t1) / 1e6
                perf(
                    "S9", "encode", 0, w, h, encodeMs,
                    String.format(Locale.US, "bytes:%d,path:baos", png.size)
                )
                val f = File(cacheDir, String.format(Locale.US, "spike2_s9_%d_%dx%d.png", i, w, h))
                val t2 = System.nanoTime()
                val fos = FileOutputStream(f)
                try {
                    fos.write(png)
                    fos.flush()
                    fos.fd.sync()
                } finally {
                    fos.close()
                }
                val writeMs = (System.nanoTime() - t2) / 1e6
                perf(
                    "S9", "write", 0, w, h, writeMs,
                    String.format(Locale.US, "bytes:%d,dst:cacheDir", f.length())
                )
                perf(
                    "S9", "total", 0, w, h, renderMs + encodeMs + writeMs,
                    String.format(
                        Locale.US, "render:%.3f,encode:%.3f,write:%.3f",
                        renderMs, encodeMs, writeMs
                    )
                )
                n++
                bmp.recycle()
            }
        }
        done("S9", n)
    }

    // ---------------------------------------------------------------- S10 renderPage(Surface) 整页直写

    private fun s10SurfacePage(runs: Int) {
        val d = ensureSeed() ?: return
        runOnUiThread { sv.visibility = View.VISIBLE }
        val got = surfaceLatch.await(6, TimeUnit.SECONDS)
        if (!got) {
            perf("S10", "render", 0, -1, -1, 0.0, "error:surface-timeout")
            done("S10", 0, "error:surface-timeout")
            return
        }
        val s = surface
        if (s == null || !s.isValid) {
            perf("S10", "render", 0, -1, -1, 0.0, "error:surface-invalid")
            done("S10", 0, "error:surface-invalid")
            return
        }
        val surfW = sv.width
        val surfH = sv.height
        // idx0 = drawSize 与 Surface 实际尺寸一致（整屏，整页直写能力 + stride 基准）
        // idx1 = 1080x1529 对照（小于 Surface，检查 stride 错位/下半屏残留）
        val sizes = listOf(surfW to surfH, SIZES[1])
        val seqCount = runs.coerceAtLeast(2)
        var n = 0
        var firstFrameLogged = false
        for ((idx, sz) in sizes.withIndex()) {
            val (dw, dh) = sz
            if (dw <= 0 || dh <= 0) continue
            for (seq in 1..seqCount) {
                val p = d.openPage(0)
                var ms = 0.0
                try {
                    val t0 = System.nanoTime()
                    // 坑（pdf-research 已核实）：native 只取第 2 个 int 当 start_x 且同时当 start_y
                    // => startX = startY = 0 永远传 0
                    p.renderPage(s, 0, 0, dw, dh, false)
                    ms = (System.nanoTime() - t0) / 1e6
                    perf(
                        "S10", "render", idx, dw, dh, ms,
                        String.format(
                            Locale.US,
                            "mode:surface,seq:%d,startX:0,annot:0,drawW:%d,drawH:%d,surfaceW:%d,surfaceH:%d",
                            seq, dw, dh, surfW, surfH
                        )
                    )
                } catch (t: Throwable) {
                    perf(
                        "S10", "render", idx, dw, dh, ms,
                        String.format(
                            Locale.US,
                            "mode:surface,seq:%d,error:%s,drawW:%d,drawH:%d,surfaceW:%d,surfaceH:%d",
                            seq, t.javaClass.simpleName, dw, dh, surfW, surfH
                        )
                    )
                } finally {
                    p.close()
                }
                val frameMs = awaitNextFrame()
                // 协议要求 first_frame；后续 warm surface 的帧用 present 区分（首帧含 surface 创建开销）
                val phase = if (!firstFrameLogged) "first_frame" else "present"
                perf(
                    "S10", phase, idx, dw, dh, frameMs,
                    String.format(
                        Locale.US,
                        "mode:surface,seq:%d,drawW:%d,drawH:%d,surfaceW:%d,surfaceH:%d",
                        seq, dw, dh, surfW, surfH
                    )
                )
                firstFrameLogged = true
                n++
            }
            // 每换一次 drawSize 后采样：中部应有页面内容（白底≈1.0）；drawH<surfaceH 时底部带若仍有白色
            // 说明上次整屏内容未清 => 残留/stride 错位证据
            val midY = (surfH / 2 - 16).coerceAtLeast(0)
            val mid = sampleSurfaceWhite(Rect(0, midY, Math.min(surfW, 64), (midY + 32).coerceAtMost(surfH)))
            val botY = (surfH - 64).coerceAtLeast(0)
            val bot = sampleSurfaceWhite(Rect(0, botY, Math.min(surfW, 64), surfH))
            perf(
                "S10", "check", idx, dw, dh, 0.0,
                String.format(
                    Locale.US,
                    "mode:surface,seq:%d,drawW:%d,drawH:%d,surfaceW:%d,surfaceH:%d,mid_white:%.3f,bot_white:%.3f",
                    seqCount, dw, dh, surfW, surfH, mid, bot
                )
            )
        }
        done("S10", n)
    }

    /** PixelCopy 采样 SurfaceView 指定区域的白像素占比（HARDWARE surface 无法直接读像素）。失败返回 -1。 */
    private fun sampleSurfaceWhite(rect: Rect): Double {
        if (rect.width() <= 0 || rect.height() <= 0) return -1.0
        val bmp = try {
            Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
        } catch (t: Throwable) {
            return -1.0
        }
        val latch = CountDownLatch(1)
        var code = -1
        try {
            runOnUiThread {
                try {
                    PixelCopy.request(
                        sv, rect, bmp,
                        { c -> code = c; latch.countDown() },
                        Handler(Looper.getMainLooper())
                    )
                } catch (t: Throwable) {
                    code = -2
                    latch.countDown()
                }
            }
            if (!latch.await(2, TimeUnit.SECONDS)) return -1.0
        } catch (t: Throwable) {
            return -1.0
        }
        if (code != PixelCopy.SUCCESS) {
            bmp.recycle()
            return -1.0
        }
        var white = 0
        var total = 0
        try {
            val w = bmp.width
            val row = IntArray(w)
            for (y in 0 until bmp.height) {
                bmp.getPixels(row, 0, w, 0, y, w, 1)
                for (x in 0 until w) {
                    total++
                    if (row[x] == -0x1) white++
                }
            }
        } finally {
            bmp.recycle()
        }
        return if (total > 0) white.toDouble() / total else -1.0
    }

    // ---------------------------------------------------------------- UI 呈现辅助

    /** 把 bitmap 贴到 ImageView，返回「从调用到 Choreographer 首帧」的毫秒数（失败 -1）。 */
    private fun presentFirstFrame(bmp: Bitmap): Double {
        val latch = CountDownLatch(1)
        val t0 = System.nanoTime()
        runOnUiThread {
            iv.setImageBitmap(bmp)
            // 回收上一张：此时 ImageView 已换成新图；同一 UI 线程内操作，不会画到已回收位图
            val old = lastPresented
            lastPresented = bmp
            if (old != null && old !== bmp && !old.isRecycled) old.recycle()
            Choreographer.getInstance().postFrameCallback { latch.countDown() }
        }
        val ok = try {
            latch.await(3, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            false
        }
        return if (ok) (System.nanoTime() - t0) / 1e6 else -1.0
    }

    private fun awaitNextFrame(): Double {
        val latch = CountDownLatch(1)
        val t0 = System.nanoTime()
        runOnUiThread { Choreographer.getInstance().postFrameCallback { latch.countDown() } }
        val ok = try {
            latch.await(3, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            false
        }
        return if (ok) (System.nanoTime() - t0) / 1e6 else -1.0
    }

    // ---------------------------------------------------------------- 日志

    private fun perf(scenario: String, phase: String, page: Int, w: Int, h: Int, ms: Double, extra: String) {
        Log.i(
            TAG,
            String.format(
                Locale.US, "PBPERF|scenario=%s|phase=%s|page=%d|w=%d|h=%d|ms=%.3f|extra=%s",
                scenario, phase, page, w, h, ms, extra.replace('\n', '_').replace('|', '/')
            )
        )
    }

    private fun done(scenario: String, runs: Int, extra: String = "ok") {
        Log.i(TAG, String.format(Locale.US, "PBDONE|scenario=%s|runs=%d|extra=%s", scenario, runs, extra))
    }

    // ---------------------------------------------------------------- Surface 回调

    override fun surfaceCreated(holder: SurfaceHolder) {
        surface = holder.surface
        surfaceLatch.countDown()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surface = null
    }

    companion object {
        private const val TAG = "PBPERF"
        private const val ASSET_SEED = "seed-03.pdf"
        private const val ASSET_REAL = "realbook.pdf"
        private const val TURN_PAGES = 5
        private const val MAX_PAGES = 4

        /** 1188x1682 = 与 P0 探针完全一致的对照尺寸；1080x1529 = 本机屏宽的自然渲染尺寸。 */
        private val SIZES = listOf(1188 to 1682, 1080 to 1529)
    }
}