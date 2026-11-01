package app.pb.spike2t

import android.graphics.Bitmap

/**
 * Byte-bounded LRU over rendered tiles.
 *
 * Every method is synchronized: an access-ordered LinkedHashMap re-links itself on get(), so the
 * UI thread's get() in onDraw and the render thread's put()/trim() race structurally, and trim()'s
 * iterator then throws ConcurrentModificationException. The lock is only held for map bookkeeping,
 * never around a bitmap render.
 *
 * Deliberately does NOT recycle evicted bitmaps: under hardware acceleration a bitmap that was
 * handed to Canvas.drawBitmap in the current display list can still be read by the render thread
 * when the next tile render evicts it. Dropping the reference is enough — the GC reclaims it.
 * The bound is still enforced byte-exactly, which is what the 32 MB contract is about.
 */
class TileCache(private val maxBytes: Long) {
    private val map = LinkedHashMap<String, Bitmap>(64, 0.75f, true)

    var bytes = 0L
        private set
    var hits = 0L
        private set
    var misses = 0L
        private set
    var evicts = 0L
        private set

    @Synchronized
    fun get(key: String): Bitmap? {
        val b = map[key]
        if (b != null) hits++ else misses++
        return b
    }

    /** Non-counting, order-preserving existence check (used by completeness polling). */
    @Synchronized
    fun contains(key: String): Boolean = map.containsKey(key)

    @Synchronized
    fun put(key: String, bmp: Bitmap) {
        val old = map.put(key, bmp)
        if (old != null) bytes -= old.byteCount.toLong()
        bytes += bmp.byteCount.toLong()
        trim()
    }

    @Synchronized
    fun trim() {
        if (bytes <= maxBytes) return
        val it = map.entries.iterator()
        while (bytes > maxBytes && it.hasNext()) {
            val e = it.next()
            bytes -= e.value.byteCount.toLong()
            evicts++
            it.remove()
        }
    }

    @Synchronized
    fun evictMatching(pred: (String) -> Boolean): Int {
        var n = 0
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (pred(e.key)) {
                bytes -= e.value.byteCount.toLong()
                it.remove()
                n++
            }
        }
        return n
    }

    @Synchronized
    fun clear() {
        map.clear()
        bytes = 0
    }

    @Synchronized
    fun count(): Int = map.size

    fun limitBytes(): Long = maxBytes
}
