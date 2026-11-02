package app.particlebook.android.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import app.particlebook.android.zlib.ZlibClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Z-Library 封面缓存（P1b §4.3）：
 * 内存 LruCache(8) → filesDir/zlib_covers/<bookId>.jpg → 网络 fetchBytes。
 * 失败静默返回 null（详情页显示 primaryContainer 占位，不弹错、不崩溃）。
 */
object ZlibCoverCache {

    private const val TAG = "PBZlib"
    private const val MEMORY_ENTRIES = 8
    private const val MAX_BYTES = 8 * 1024 * 1024

    private val memory = object : LruCache<String, Bitmap>(MEMORY_ENTRIES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private fun coverDir(context: Context): File =
        File(context.filesDir, "zlib_covers").apply { mkdirs() }

    private fun safeName(bookId: String): String =
        bookId.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "cover" }

    suspend fun load(
        context: Context,
        bookId: String,
        coverUrl: String?,
        targetWidthPx: Int,
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (coverUrl.isNullOrBlank()) return@withContext null
        memory.get(bookId)?.let { return@withContext it }
        val diskFile = File(coverDir(context), safeName(bookId) + ".jpg")
        var bytes: ByteArray? = runCatching {
            if (diskFile.isFile) diskFile.readBytes() else null
        }.getOrNull()
        if (bytes == null) {
            bytes = runCatching { ZlibClient.get(context).fetchBytes(coverUrl) }.getOrNull()
            if (bytes != null && bytes.size <= MAX_BYTES) {
                runCatching { diskFile.writeBytes(bytes) }
            } else if (bytes != null) {
                bytes = null
            }
        }
        val bitmap = bytes?.let { decodeSampled(it, targetWidthPx) }
        if (bitmap != null) {
            memory.put(bookId, bitmap)
        } else {
            Log.w(TAG, "cover decode failed book=" + bookId)
        }
        bitmap
    }

    /** 两次 decode：只读边界算 inSampleSize，再真正解码，避免 600×800 原图整张进内存。 */
    private fun decodeSampled(bytes: ByteArray, targetWidthPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val target = targetWidthPx.coerceAtLeast(64)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull()
    }
}
