package app.particlebook.android.reader.epub.parser

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import app.particlebook.android.reader.epub.EpubChapter
import app.particlebook.android.reader.epub.layout.EpubChapterLayout
import app.particlebook.android.reader.epub.layout.EpubLayoutConfig
import app.particlebook.android.reader.epub.layout.EpubLayoutEngine
import app.particlebook.android.reader.epub.layout.LayoutResult
import app.particlebook.android.reader.epub.layout.StaticLayoutBlockMeasurer
import java.io.File

/**
 * M0 设备探针入口。debug-only Activity 只需调用：
 *
 *   M0Probe.run(context, File(path))        // 首章
 *   M0Probe.run(context, File(path), 2)     // 指定 spine 下标
 *
 * 内部完成 ZIP/OPF/TOC 解析 -> 章节 XHTML 解析 -> StaticLayout 布局 -> 行切片分页，
 * 记录各阶段耗时、块/行/页数和进程 PSS，并 Log.i("PBM0", json)。
 * 不依赖 Activity/Manifest，不修改全局状态。
 */
object M0Probe {

    data class M0Result(
        val file: String,
        val bookTitle: String,
        val spineCount: Int,
        val tocCount: Int,
        val manifestCount: Int,
        val archiveBytes: Long,
        val chapterIndex: Int,
        val chapterHref: String,
        val chapterTitle: String?,
        val blockCount: Int,
        val lineCount: Int,
        val pageCount: Int,
        val openMs: Long,
        val chapterReadMs: Long,
        val layoutMs: Long,
        val totalMs: Long,
        val memoryBeforeKb: Long,
        val memoryAfterLayoutKb: Long,
        val memoryAfterCloseKb: Long,
        val peakMemoryKb: Long,
        val warnings: List<String>,
        val error: String? = null,
    ) {
        fun toJson(): String {
            val sb = StringBuilder(512)
            sb.append('{')
            field(sb, "file", file, first = true)
            field(sb, "bookTitle", bookTitle)
            number(sb, "spineCount", spineCount)
            number(sb, "tocCount", tocCount)
            number(sb, "manifestCount", manifestCount)
            number(sb, "archiveBytes", archiveBytes)
            number(sb, "chapterIndex", chapterIndex)
            field(sb, "chapterHref", chapterHref)
            field(sb, "chapterTitle", chapterTitle)
            number(sb, "blockCount", blockCount)
            number(sb, "lineCount", lineCount)
            number(sb, "pageCount", pageCount)
            number(sb, "openMs", openMs)
            number(sb, "chapterReadMs", chapterReadMs)
            number(sb, "layoutMs", layoutMs)
            number(sb, "totalMs", totalMs)
            number(sb, "memoryBeforeKb", memoryBeforeKb)
            number(sb, "memoryAfterLayoutKb", memoryAfterLayoutKb)
            number(sb, "memoryAfterCloseKb", memoryAfterCloseKb)
            number(sb, "peakMemoryKb", peakMemoryKb)
            field(sb, "warnings", warnings.joinToString(" | "))
            field(sb, "error", error)
            sb.append('}')
            return sb.toString()
        }

        private fun field(sb: StringBuilder, name: String, value: String?, first: Boolean = false) {
            if (!first) sb.append(',')
            sb.append('"').append(name).append('"').append(':')
            if (value == null) {
                sb.append("null")
            } else {
                sb.append('"').append(escape(value)).append('"')
            }
        }

        private fun number(sb: StringBuilder, name: String, value: Long) {
            sb.append(',').append('"').append(name).append('"').append(':').append(value)
        }

        private fun number(sb: StringBuilder, name: String, value: Int) {
            sb.append(',').append('"').append(name).append('"').append(':').append(value)
        }

        private fun escape(value: String): String {
            val out = StringBuilder(value.length + 8)
            for (ch in value) {
                when (ch) {
                    '"' -> { out.append('\\').append('"') }
                    '\\' -> { out.append('\\').append('\\') }
                    '\n' -> { out.append('\\').append('n') }
                    '\r' -> { out.append('\\').append('r') }
                    '\t' -> { out.append('\\').append('t') }
                    else -> if (ch < ' ') {
                        out.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        out.append(ch)
                    }
                }
            }
            return out.toString()
        }
    }

    fun run(
        context: Context,
        epubFile: File,
        chapterIndex: Int = 0,
        config: EpubLayoutConfig? = null,
    ): M0Result {
        val started = SystemClock.elapsedRealtimeNanos()
        val memoryBefore = currentPssKb()
        var parser: EpubParser? = null
        var bookTitle = ""
        var spineCount = 0
        var tocCount = 0
        var manifestCount = 0
        var chapterHref = ""
        var chapterTitle: String? = null
        var blockCount = 0
        var lineCount = 0
        var pageCount = 0
        var openMs = 0L
        var chapterReadMs = 0L
        var layoutMs = 0L
        var warnings: List<String> = emptyList()
        var error: String? = null
        var memoryAfterLayout = memoryBefore
        var memoryAfterClose = memoryBefore
        try {
            val openStart = SystemClock.elapsedRealtimeNanos()
            parser = EpubParser.open(epubFile)
            openMs = elapsedMs(openStart)
            bookTitle = parser.book.metadata.title
            spineCount = parser.book.spine.size
            tocCount = countToc(parser.book.toc)
            manifestCount = parser.book.manifest.size
            warnings = parser.warnings
            if (spineCount == 0) throw EpubParseException("EMPTY_SPINE")
            val index = chapterIndex.coerceIn(0, spineCount - 1)
            chapterHref = parser.book.spine[index].href

            val readStart = SystemClock.elapsedRealtimeNanos()
            val chapter: EpubChapter = parser.readChapter(index)
            chapterReadMs = elapsedMs(readStart)
            chapterTitle = chapter.title
            blockCount = chapter.blocks.size

            val metrics = context.resources.displayMetrics
            val density = metrics.density.takeIf { it > 0f } ?: 1f
            val scaledDensity = metrics.scaledDensity.takeIf { it > 0f } ?: density
            val padding = (24f * density).toInt()
            val layoutConfig = config ?: EpubLayoutConfig(
                viewportWidthPx = metrics.widthPixels.coerceAtLeast(1),
                viewportHeightPx = metrics.heightPixels.coerceAtLeast(1),
                horizontalPaddingPx = padding,
                verticalPaddingPx = padding,
                baseTextSizeSp = 17f,
            )
            val layoutStart = SystemClock.elapsedRealtimeNanos()
            val engine = EpubLayoutEngine(StaticLayoutBlockMeasurer(density, scaledDensity))
            val layout: EpubChapterLayout = engine.layout(chapter, layoutConfig)
            layoutMs = elapsedMs(layoutStart)
            lineCount = layout.totalLines
            pageCount = layout.pageCount
            memoryAfterLayout = currentPssKb()
            Log.i("PBM0", "layout chapter=$chapterHref blocks=$blockCount lines=$lineCount pages=$pageCount")
        } catch (t: Throwable) {
            error = t.javaClass.simpleName + ": " + (t.message ?: "")
            memoryAfterLayout = currentPssKb()
            Log.w("PBM0", "probe failed", t)
        } finally {
            try {
                parser?.close()
            } catch (_: Exception) {
            }
            memoryAfterClose = currentPssKb()
        }
        val totalMs = elapsedMs(started)
        val result = M0Result(
            file = epubFile.absolutePath,
            bookTitle = bookTitle,
            spineCount = spineCount,
            tocCount = tocCount,
            manifestCount = manifestCount,
            archiveBytes = if (epubFile.isFile) epubFile.length() else 0L,
            chapterIndex = chapterIndex,
            chapterHref = chapterHref,
            chapterTitle = chapterTitle,
            blockCount = blockCount,
            lineCount = lineCount,
            pageCount = pageCount,
            openMs = openMs,
            chapterReadMs = chapterReadMs,
            layoutMs = layoutMs,
            totalMs = totalMs,
            memoryBeforeKb = memoryBefore,
            memoryAfterLayoutKb = memoryAfterLayout,
            memoryAfterCloseKb = memoryAfterClose,
            peakMemoryKb = maxOf(memoryBefore, memoryAfterLayout),
            warnings = warnings,
            error = error,
        )
        val json = result.toJson()
        Log.i("PBM0", json)
        return result
    }

    /** 给已经解析好 chapter 的调用方（例如 p15 的 Adapter 复用）。 */
    fun layoutChapter(
        context: Context,
        chapter: EpubChapter,
        config: EpubLayoutConfig,
    ): LayoutResult {
        val metrics = context.resources.displayMetrics
        val density = metrics.density.takeIf { it > 0f } ?: 1f
        val scaledDensity = metrics.scaledDensity.takeIf { it > 0f } ?: density
        val started = SystemClock.elapsedRealtimeNanos()
        val layout = EpubLayoutEngine(StaticLayoutBlockMeasurer(density, scaledDensity)).layout(chapter, config)
        return LayoutResult(
            blockCount = chapter.blocks.size,
            lineCount = layout.totalLines,
            pageCount = layout.pageCount,
            layoutMs = elapsedMs(started),
        )
    }

    private fun currentPssKb(): Long {
        return try {
            val info = Debug.MemoryInfo()
            Debug.getMemoryInfo(info)
            info.totalPss.toLong()
        } catch (_: Throwable) {
            -1L
        }
    }

    private fun countToc(entries: List<app.particlebook.android.reader.epub.EpubTocEntry>): Int {
        var count = 0
        for (entry in entries) count += 1 + countToc(entry.children)
        return count
    }

    private fun elapsedMs(startNanos: Long): Long =
        (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000L
}
