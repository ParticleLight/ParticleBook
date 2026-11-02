package app.particlebook.android.ui.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.max

/**
 * 封面加载（P0 视觉改版）：PDF 渲第 1 页、EPUB 解 OPF 找 cover-image，
 * 统一裁成 3:4 的 488×651 位图（§17.1.6）；内存 LruCache + 磁盘缓存 filesDir/covers/<id>.png。
 *
 * 任何一步失败都返回 null（调用方回落到程序化生成封面），不崩溃。
 */
object BookCoverLoader {
    private const val TAG = "PBCover"

    /** 目标尺寸：3:4，网格封面 186×248dp = 488×651px（§17.1.6）。 */
    const val TARGET_WIDTH = 488
    const val TARGET_HEIGHT = 651

    /** 内存缓存 24MB（单张 488×651 ARGB ≈ 1.27MB，约 19 张；§17.1.6 上限 24 张）。 */
    private const val MEMORY_CACHE_BYTES = 24 * 1024 * 1024

    private val memory = object : LruCache<Long, Bitmap>(MEMORY_CACHE_BYTES) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount
    }

    private fun coversDir(context: Context): File =
        File(context.filesDir, "covers").apply { if (!exists()) mkdirs() }

    private fun cacheFile(context: Context, bookId: Long): File =
        File(coversDir(context), bookId.toString() + ".png")

    /** 内存里已有的封面（首帧直接画出来，避免闪一下生成封面）。 */
    fun fromMemory(bookId: Long): Bitmap? = memory.get(bookId)

    /** 加载封面；主线程调用安全（内部切 IO）。 */
    suspend fun load(context: Context, book: Book): Bitmap? = withContext(Dispatchers.IO) {
        memory.get(book.id)?.let { return@withContext it }
        val target = cacheFile(context, book.id)
        val disk = if (target.isFile) decodeScaled(target) else null
        val bitmap = disk ?: generate(book)?.also { bitmapValue ->
            runCatching { writePng(target, bitmapValue) }
                .onFailure { Log.w(TAG, "cover cache write failed id=" + book.id, it) }
        }
        if (bitmap != null) memory.put(book.id, bitmap)
        bitmap
    }

    private fun generate(book: Book): Bitmap? {
        val file = File(book.filePath)
        if (!file.isFile) return null
        return runCatching {
            when (book.format) {
                BookFormat.Pdf -> renderPdfCover(file)
                BookFormat.Epub -> renderEpubCover(file)
            }
        }.onFailure {
            Log.w(TAG, "cover generate failed id=" + book.id + ": " + it.message)
        }.getOrNull()
    }

    // ---------------------------------------------------------------- PDF

    private fun renderPdfCover(file: File): Bitmap? {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (renderer.pageCount <= 0) return null
                renderer.openPage(0).use { page ->
                    val pageWidth = page.width
                    val pageHeight = page.height
                    if (pageWidth <= 0 || pageHeight <= 0) return null
                    // 先按「盖满 3:4」放大渲染，再中心裁切，避免竖版 PDF 留白。
                    val scale = max(
                        TARGET_WIDTH.toFloat() / pageWidth,
                        TARGET_HEIGHT.toFloat() / pageHeight,
                    )
                    val renderWidth = (pageWidth * scale).toInt().coerceIn(1, 2400)
                    val renderHeight = (pageHeight * scale).toInt().coerceIn(1, 3200)
                    val bitmap = Bitmap.createBitmap(
                        renderWidth,
                        renderHeight,
                        Bitmap.Config.ARGB_8888,
                    )
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return cropToCover(bitmap)
                }
            }
        }
    }

    // ---------------------------------------------------------------- EPUB

    private fun renderEpubCover(file: File): Bitmap? {
        ZipFile(file).use { zip ->
            val container = zip.getEntry("META-INF/container.xml") ?: return null
            val opfPath = zip.getInputStream(container).use { parseRootfile(it) } ?: return null
            val opfEntry = zip.getEntry(opfPath) ?: return null
            val href = zip.getInputStream(opfEntry).use { parseCoverHref(it) } ?: return null
            val resolved = resolveRelative(opfPath, href)
            val coverEntry = zip.getEntry(resolved) ?: zip.getEntry(href) ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            zip.getInputStream(coverEntry).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            }
            val source = zip.getInputStream(coverEntry).use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            } ?: return null
            return cropToCover(source)
        }
    }

    private fun parseRootfile(input: java.io.InputStream): String? {
        val parser = android.util.Xml.newPullParser()
        parser.setInput(input, null)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG &&
                parser.name.equals("rootfile", ignoreCase = true)
            ) {
                val path = parser.getAttributeValue(null, "full-path")
                if (!path.isNullOrBlank()) return path
            }
            event = parser.next()
        }
        return null
    }

    private fun parseCoverHref(input: java.io.InputStream): String? {
        val parser = android.util.Xml.newPullParser()
        parser.setInput(input, null)
        val items = HashMap<String, String>()
        var coverImageHref: String? = null
        var metaCoverId: String? = null
        var firstImageHref: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when {
                    parser.name.equals("item", ignoreCase = true) -> {
                        val id = parser.getAttributeValue(null, "id")
                        val href = parser.getAttributeValue(null, "href")
                        val mediaType = parser.getAttributeValue(null, "media-type")
                        val properties = parser.getAttributeValue(null, "properties")
                        if (!id.isNullOrBlank() && !href.isNullOrBlank()) items[id] = href
                        if (properties != null && properties.contains("cover-image") && !href.isNullOrBlank()) {
                            coverImageHref = href
                        }
                        if (mediaType != null && mediaType.startsWith("image/") &&
                            !href.isNullOrBlank() && firstImageHref == null
                        ) {
                            firstImageHref = href
                        }
                    }

                    parser.name.equals("meta", ignoreCase = true) -> {
                        if (parser.getAttributeValue(null, "name").equals("cover", ignoreCase = true)) {
                            metaCoverId = parser.getAttributeValue(null, "content")
                        }
                    }
                }
            }
            event = parser.next()
        }
        if (coverImageHref != null) return coverImageHref
        if (metaCoverId != null) items[metaCoverId]?.let { return it }
        return firstImageHref
    }

    /** OPF 里的 href 相对 OPF 所在目录；顺带处理 %20 与 ../。 */
    private fun resolveRelative(opfPath: String, href: String): String {
        val decoded = runCatching {
            java.net.URLDecoder.decode(href, "UTF-8")
        }.getOrDefault(href)
        if (decoded.startsWith("/")) return decoded.removePrefix("/")
        val base = opfPath.substringBeforeLast('/', "")
        val joined = if (base.isEmpty()) decoded else base + "/" + decoded
        val parts = ArrayDeque<String>()
        for (part in joined.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(part)
            }
        }
        return parts.joinToString("/")
    }

    // ---------------------------------------------------------------- 位图工具

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= TARGET_WIDTH && height / (sample * 2) >= TARGET_HEIGHT) {
            sample *= 2
        }
        return sample
    }

    /** 中心裁成 3:4 并缩放到 600×800。 */
    private fun cropToCover(source: Bitmap): Bitmap {
        val targetAspect = TARGET_WIDTH.toFloat() / TARGET_HEIGHT.toFloat()
        val width = source.width
        val height = source.height
        val cropWidth: Int
        val cropHeight: Int
        if (width.toFloat() / height.toFloat() > targetAspect) {
            cropHeight = height
            cropWidth = (height * targetAspect).toInt()
        } else {
            cropWidth = width
            cropHeight = (width / targetAspect).toInt()
        }
        val x = (width - cropWidth) / 2
        val y = (height - cropHeight) / 2
        val cropped = Bitmap.createBitmap(
            source,
            x,
            y,
            cropWidth.coerceAtLeast(1),
            cropHeight.coerceAtLeast(1),
        )
        val scaled = Bitmap.createScaledBitmap(cropped, TARGET_WIDTH, TARGET_HEIGHT, true)
        if (cropped !== source && cropped !== scaled) cropped.recycle()
        if (source !== scaled && source !== cropped) source.recycle()
        return scaled
    }

    private fun decodeScaled(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
        }
        return BitmapFactory.decodeFile(file.path, options)
    }

    private fun writePng(target: File, bitmap: Bitmap) {
        target.parentFile?.mkdirs()
        target.outputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
    }
}
