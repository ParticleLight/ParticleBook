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
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Z-Library 封面缓存（P1b §4.3 / P1.5 §8.1）：
 * 内存 LruCache（16MB，按 Bitmap 字节数）-> filesDir/zlib_covers/<bookId>.jpg -> 网络 fetchBytes。
 * 150×200 缩略图约 120KB/张，16MB 约容 130 张；若站点改大图需同步调 MEMORY_MAX_BYTES 或降 targetWidth。
 *
 * T13 磁盘加固（搜索页会成批抓封面）：
 * 1. 落盘走 ZlibCoverDiskCache.write：唯一 tmp + fsync + rename，进程被杀/同 key 并发写都不留半截文件；
 * 2. 磁盘字节 decode 失败视为坏文件，删除后重新下载，避免一张截断文件让该书封面永久占位；
 * 3. ZlibCoverDiskCache.prune 按 lastModified 淘汰，上限 MAX_DISK_BYTES / MAX_DISK_FILES，搜索越用越大不再发生；
 * 4. 失败静默返回 null（UI 显示占位块，不弹错、不崩溃）。
 */
object ZlibCoverCache {

    private const val TAG = "PBZlib"

    /** 单张封面文件上限（超限不落盘，防止下载到异常大图）。 */
    private const val MAX_BYTES = 8 * 1024 * 1024

    /** 内存缓存上限 16MB，按 Bitmap 字节数计数（不是条目数）。 */
    private const val MEMORY_MAX_BYTES = 16 * 1024 * 1024

    private val memory = object : LruCache<String, Bitmap>(MEMORY_MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * 内存缓存同步命中（只读，UI 主线程可安全调用）。
     * 列表滚动复用时用它做 produceState 的初值：命中就直接出图，避免先闪一帧首字占位。
     */
    fun peek(bookId: String): Bitmap? =
        if (bookId.isBlank()) null else memory.get(bookId)

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
        memory.get(bookId)?.let { return@withContext it }
        val dir = coverDir(context)
        val diskFile = ZlibCoverDiskCache.file(dir, safeName(bookId))
        var bytes = ZlibCoverDiskCache.read(diskFile)
        var bitmap = bytes?.let { decodeSampled(it, targetWidthPx) }
        if (bitmap == null) {
            if (bytes != null) {
                // 磁盘上是坏/截断文件：删掉，允许下面重新下载（否则永久占位）。
                ZlibCoverDiskCache.delete(diskFile)
            }
            if (!coverUrl.isNullOrBlank()) {
                val fetched = runCatching { ZlibClient.get(context).fetchBytes(coverUrl) }
                    .getOrNull()
                    ?.takeIf { it.size <= MAX_BYTES }
                if (fetched != null) {
                    bitmap = decodeSampled(fetched, targetWidthPx)
                    if (bitmap != null && ZlibCoverDiskCache.write(dir, diskFile, fetched)) {
                        ZlibCoverDiskCache.prune(dir, keep = diskFile)
                    }
                }
            }
        }
        if (bitmap != null) {
            memory.put(bookId, bitmap)
        } else {
            Log.w(TAG, "cover load failed book=" + bookId)
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

/**
 * 封面磁盘缓存的文件层（纯 JVM，可单测；不依赖 Bitmap / Android Context）。
 *
 * 原子写：同目录唯一 .tmp -> fsync -> rename；并发写同一个 key 时后 rename 的成功者生效，
 * 读方永远只会看到旧文件或新文件的完整内容。rename 不支持覆盖的文件系统退回 copy。
 */
internal object ZlibCoverDiskCache {

    /** 磁盘缓存总上限 64MB。 */
    const val MAX_DISK_BYTES: Long = 64L * 1024 * 1024

    /** 磁盘缓存文件数上限 300（约合 150×200 缩略图 30-40MB，留出余量）。 */
    const val MAX_DISK_FILES: Int = 300

    /** 超过这个时间的 .tmp 视为上次进程被杀留下的垃圾；prune 时清掉，不误删正在写的 tmp。 */
    internal const val STALE_TMP_MS: Long = 10L * 60 * 1000

    fun file(dir: File, baseName: String): File = File(dir, baseName + ".jpg")

    fun read(file: File): ByteArray? =
        runCatching { if (file.isFile) file.readBytes() else null }.getOrNull()

    fun delete(file: File) {
        runCatching { file.delete() }
    }

    /** 原子落盘；返回 true 表示目标文件已可读。失败时清理 tmp，不留半成品。 */
    fun write(dir: File, target: File, bytes: ByteArray): Boolean {
        dir.mkdirs()
        val tmp = File(dir, target.name + "." + UUID.randomUUID().toString() + ".tmp")
        return try {
            FileOutputStream(tmp).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            moveIntoPlace(tmp, target)
            true
        } catch (throwable: Throwable) {
            // 纯 JVM 文件层不能调 android.util.Log（主机单测里未 mock）：失败静默返回 false，封面下次重下即可。
            runCatching { tmp.delete() }
            false
        }
    }

    /**
     * 同目录移动：Files.move(REPLACE_EXISTING) 在 Android ext4 和 Windows NTFS 上都落到 rename/MoveFileEx，
     * 对读方是原子替换。并发写同 key 时目标可能正被别的写者替换，偶发 IOException，短暂重试即可。
     */
    private fun moveIntoPlace(tmp: File, target: File) {
        repeat(5) { attempt ->
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                return
            } catch (ignored: IOException) {
                // 竞争窗口：重试；五次都失败才退到最后兜底。
            }
            if (attempt < 4) {
                try {
                    Thread.sleep(2L)
                } catch (ignored: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    /**
     * 清理磁盘缓存：先删过期 .tmp（上次进程被杀留下的），再按 lastModified 从旧到新淘汰
     * .jpg，直到文件数与总字节都不超上限。keep 是刚写入的文件，本次不删。
     * 返回删除的文件数。
     */
    fun prune(
        dir: File,
        maxBytes: Long = MAX_DISK_BYTES,
        maxFiles: Int = MAX_DISK_FILES,
        keep: File? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): Int {
        val all = dir.listFiles() ?: return 0
        var removed = 0
        all.forEach { file ->
            if (file.isFile && file.name.endsWith(".tmp") &&
                nowMs - file.lastModified() > STALE_TMP_MS
            ) {
                if (runCatching { file.delete() }.getOrDefault(false)) removed += 1
            }
        }
        val covers = all.filter { it.isFile && it.name.endsWith(".jpg") }
        var total = covers.sumOf { it.length() }
        var count = covers.size
        if (total <= maxBytes && count <= maxFiles) return removed
        covers.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= maxBytes && count <= maxFiles) return@forEach
            if (keep != null && file.absolutePath == keep.absolutePath) return@forEach
            val size = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) {
                total -= size
                count -= 1
                removed += 1
            }
        }
        return removed
    }
}
