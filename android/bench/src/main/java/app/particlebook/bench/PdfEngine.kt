package app.particlebook.bench

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfiumCore
import java.io.File
import java.io.FileOutputStream

data class PdfChar(val x: Float, val y: Float, val w: Float, val h: Float, val c: String)
data class PdfTextLayer(val page: Int, val width: Int, val height: Int, val chars: List<PdfChar>)

/** PDFium-backed replacement for the Windows mutool subprocess pipeline. */
class PdfEngine(private val context: Context, private val cacheDir: File) {
    private val core = PdfiumCore(context)
    private val docs = HashMap<Int, PdfDocument>()
    private var nextId = 1

    class Handle(val id: Int, val pageCount: Int, val widths: IntArray, val heights: IntArray)

    fun open(bytes: ByteArray): Handle? {
        val t0 = System.nanoTime()
        val doc = try {
            core.newDocument(bytes)
        } catch (e: Exception) {
            Log.e(TAG, "newDocument failed", e)
            return null
        }
        val n = doc.getPageCount()
        val w = IntArray(n)
        val h = IntArray(n)
        for (i in 0 until n) {
            val page = doc.openPage(i)
            w[i] = page.getPageWidthPoint()
            h[i] = page.getPageHeightPoint()
            page.close()
        }
        val id = nextId++
        docs[id] = doc
        Log.i(TAG, String.format("pdf_open pages=%d docBytes=%d took=%.1fms", n, bytes.size, (System.nanoTime() - t0) / 1e6))
        return Handle(id, n, w, h)
    }

    fun renderPage(id: Int, pageIndex: Int, width: Int, height: Int): String? {
        val doc = docs[id] ?: return null
        val t0 = System.nanoTime()
        val page = doc.openPage(pageIndex)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        page.renderPageBitmap(bmp, 0, 0, width, height, true, false)
        page.close()
        val tRender = (System.nanoTime() - t0) / 1e6
        val f = File(cacheDir, "pdf" + id + "_p" + pageIndex + "_" + width + "x" + height + ".png")
        val t1 = System.nanoTime()
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        val tEncode = (System.nanoTime() - t1) / 1e6
        Log.i(TAG, String.format("pdf_page page=%d %dx%d render=%.1fms encode=%.1fms bytes=%d", pageIndex, width, height, tRender, tEncode, f.length()))
        return f.name
    }

    fun textLayer(id: Int, pageIndex: Int): PdfTextLayer? {
        val doc = docs[id] ?: return null
        val page = doc.openPage(pageIndex)
        val pw = page.getPageWidthPoint()
        val ph = page.getPageHeightPoint()
        val tp = page.openTextPage()
        val count = tp.textPageCountChars()
        val chars = ArrayList<PdfChar>(count)
        for (i in 0 until count) {
            val c = tp.textPageGetUnicode(i)
            val box = tp.textPageGetCharBox(i)
            if (box == null) continue
            chars.add(PdfChar(box.left, box.top, box.width(), box.height(), c.toString()))
        }
        tp.close()
        page.close()
        return PdfTextLayer(pageIndex, pw, ph, chars)
    }

    fun extractAllText(id: Int): List<String> {
        val doc = docs[id] ?: return emptyList()
        val out = ArrayList<String>(doc.getPageCount())
        for (i in 0 until doc.getPageCount()) {
            val page = doc.openPage(i)
            val tp = page.openTextPage()
            out.add(tp.textPageGetText(0, tp.textPageCountChars()) ?: "")
            tp.close()
            page.close()
        }
        return out
    }

    fun close(id: Int) {
        docs.remove(id)?.close()
    }

    companion object { const val TAG = "PBPdf" }
}