package app.particlebook.android

import android.content.Context
import app.particlebook.bench.PdfEngine
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/** P0 bridge: answers the renderer's invoke() calls from a canned fixture so the
 *  real React bundle boots before the C++ core is ported. Every call is logged
 *  with its duration so logcat carries the bridge round-trip numbers. */
class Bridge(private val context: Context, private val filesDir: File) {
    private val pool = Executors.newFixedThreadPool(4)
    private val fixtures: JSONObject by lazy { JSONObject(Assets.readText(context, "fixtures.json")) }
    private val books: JSONArray by lazy { fixtures.optJSONArray("books") ?: JSONArray() }
    private val settings: JSONObject by lazy { fixtures.optJSONObject("settings") ?: JSONObject() }
    private val pdf: PdfEngine by lazy { PdfEngine(context, File(context.filesDir, "_pb_pdf").apply { mkdirs() }) }

    fun onMessage(raw: String, reply: (String) -> Unit) {
        val t0 = System.nanoTime()
        val msg = try { JSONObject(raw) } catch (e: Exception) { Log.w(TAG, "bad message"); return }
        when (msg.optString("type")) {
            "invoke" -> {
                val id = msg.optInt("id")
                val method = msg.optString("method")
                val params = msg.optJSONObject("params") ?: JSONObject()
                pool.execute {
                    val out = JSONObject()
                    out.put("type", "response")
                    out.put("id", id)
                    try {
                        out.put("result", handle(method, params))
                    } catch (e: Exception) {
                        out.put("error", e.message ?: "bridge_error")
                    }
                    val ms = (System.nanoTime() - t0) / 1_000_000.0
                    Log.i(TAG, String.format(Locale.ROOT, "INVOKE %s %.2fms", method, ms))
                    reply(out.toString())
                }
            }
            "subscribe", "unsubscribe" -> { }
        }
    }

    private fun handle(method: String, params: JSONObject): Any? = when (method) {
        "app:getVersion" -> "2.2.1-p0"
        "db:getBooks" -> books
        "db:getBook" -> {
            val id = params.optInt("id", -1)
            var found: JSONObject? = null
            for (i in 0 until books.length()) {
                val b = books.getJSONObject(i)
                if (b.optInt("id") == id) found = b
            }
            found
        }
        "db:getBooksInShelf" -> JSONArray()
        "db:getBookshelves" -> fixtures.optJSONArray("bookshelves") ?: JSONArray()
        "library:getTags" -> JSONArray()
        "library:getSeries" -> JSONArray()
        "db:getSettings" -> settings
        "db:getBookSettings" -> JSONObject()
        "db:getBookmarks" -> JSONArray()
        "db:getHighlights" -> JSONArray()
        "db:getNotes" -> JSONArray()
        "db:getDownloads" -> JSONArray()
        "db:getAllReadingTime" -> JSONObject()
        "db:getAllReadingProgress" -> JSONObject()
        "app:getPendingOpenFiles" -> JSONArray()
        "db:updateSettings", "db:updateProgress", "db:getProgress" -> null
        "book:cover" -> null
        "file:read" -> {
            val name = File(params.optString("path")).name
            JSONObject().put("_pb_url", "https://appassets.androidplatform.net/_pb_files/" + name)
        }
        "pdf:open" -> {
            val f = File(filesDir, File(params.optString("filePath")).name)
            val h = if (f.exists()) pdf.open(f.readBytes()) else null
            if (h == null) null else {
                val bounds = JSONArray()
                for (i in 0 until h.pageCount) {
                    bounds.put(JSONObject().put("width", h.widths[i]).put("height", h.heights[i]))
                }
                JSONObject().put("id", h.id).put("pageCount", h.pageCount).put("pageBounds", bounds)
            }
        }
        "pdf:renderPage" -> {
            val name = pdf.renderPage(
                params.optInt("id"),
                params.optInt("pageNum"),
                params.optInt("width", 800),
                params.optInt("height", 1200)
            )
            if (name == null) null else "https://appassets.androidplatform.net/_pb_pdf/" + name
        }
        "pdf:textLayer" -> {
            val tl = pdf.textLayer(params.optInt("id"), params.optInt("pageNum"))
            if (tl == null) null else {
                val arr = JSONArray()
                for (c in tl.chars) {
                    arr.put(JSONObject().put("x", c.x).put("y", c.y).put("w", c.w).put("h", c.h).put("c", c.c))
                }
                JSONObject().put("page", tl.page).put("width", tl.width).put("height", tl.height).put("chars", arr)
            }
        }
        "pdf:extractText" -> {
            val pages = JSONArray()
            val texts = pdf.extractAllText(params.optInt("id"))
            for (i in texts.indices) pages.put(JSONObject().put("pageNum", i).put("text", texts[i]))
            JSONObject().put("pages", pages)
        }
        "pdf:close" -> {
            pdf.close(params.optInt("id"))
            null
        }
        "bench:mark" -> {
            Log.i(TAG, "BENCH " + params.toString())
            null
        }
        else -> {
            Log.w(TAG, "UNHANDLED " + method)
            null
        }
    }

    companion object { const val TAG = "PBBridge" }
}