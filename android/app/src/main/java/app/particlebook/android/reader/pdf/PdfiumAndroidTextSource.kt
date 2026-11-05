package app.particlebook.android.reader.pdf

import android.content.Context
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfPage
import io.legere.pdfiumandroid.PdfTextPage
import io.legere.pdfiumandroid.PdfiumCore
import java.io.Closeable
import java.io.File

/**
 * PDFium 1.0.30 的 Android 适配层。
 *
 * 核心 PdfTextSource 只依赖纯 JVM 句柄，本文件是唯一 import Android / PDFium 的地方。
 * 调用方必须在使用完后 close 返回的 PdfTextSource；文件描述符默认由 source 负责关闭。
 */
object PdfiumAndroidTextSource {

    fun open(
        context: Context,
        file: File,
        password: String? = null,
        cacheLimit: Int = 3,
    ): PdfTextOpenResult {
        val descriptor = try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (t: Throwable) {
            return PdfTextOpenResult.Failure(PdfTextFailureReason.OPEN_FAILED, t.message, t)
        }
        return openDescriptor(context, descriptor, password, cacheLimit, closeDescriptor = true)
    }

    /**
     * 打开已创建的 fd。closeDescriptor=false 时 fd 仍由调用方拥有；无论成功失败，
     * 只要 source 接管了 fd，close 时都会关闭。
     */
    fun openDescriptor(
        context: Context,
        descriptor: ParcelFileDescriptor,
        password: String? = null,
        cacheLimit: Int = 3,
        closeDescriptor: Boolean = true,
    ): PdfTextOpenResult {
        val core = try {
            PdfiumCore(context)
        } catch (t: Throwable) {
            if (closeDescriptor) closeQuietly(descriptor)
            return PdfTextOpenResult.Failure(PdfTextFailureReason.OPEN_FAILED, t.message, t)
        }
        val document = try {
            if (password == null) core.newDocument(descriptor) else core.newDocument(descriptor, password)
        } catch (t: Throwable) {
            if (closeDescriptor) closeQuietly(descriptor)
            return PdfTextOpenResult.Failure(classifyPdfOpenFailure(t), t.message, t)
        }
        val handle = AndroidDocument(descriptor, document, closeDescriptor)
        return PdfTextSource.open({ handle }, cacheLimit)
    }

    private fun closeQuietly(closeable: Closeable?) {
        try {
            closeable?.close()
        } catch (_: Throwable) {
        }
    }

    private class AndroidDocument(
        private val descriptor: ParcelFileDescriptor,
        private val document: PdfDocument,
        private val closeDescriptor: Boolean,
    ) : PdfTextDocumentHandle {
        override val pageCount: Int get() = document.getPageCount()

        override fun openPage(pageIndex: Int): PdfTextPageHandle {
            return AndroidPage(document.openPage(pageIndex))
        }

        override fun close() {
            try {
                document.close()
            } finally {
                if (closeDescriptor) closeQuietly(descriptor)
            }
        }
    }

    private class AndroidPage(private val page: PdfPage) : PdfTextPageHandle {
        override val widthPoints: Int get() = page.getPageWidthPoint()
        override val heightPoints: Int get() = page.getPageHeightPoint()

        override val cropBox: PdfBox?
            get() = toPdfBox(page.getPageCropBox())

        override val rotationDegrees: Int
            get() = when (page.getPageRotation()) {
                1 -> 90
                2 -> 180
                3 -> 270
                else -> 0
            }

        override fun openTextPage(): PdfTextTextHandle = AndroidTextPage(page.openTextPage())

        override fun close() {
            page.close()
        }
    }

    private class AndroidTextPage(private val textPage: PdfTextPage) : PdfTextTextHandle {
        override val charCount: Int get() = textPage.textPageCountChars()

        override fun charAt(index: Int): Char = textPage.textPageGetUnicode(index)

        override fun charBox(index: Int): PdfBox? {
            val rect: RectF? = try {
                textPage.textPageGetCharBox(index)
            } catch (t: Throwable) {
                null
            }
            return toPdfBox(rect)
        }

        override fun text(): String? {
            val count = charCount
            if (count <= 0) return ""
            return textPage.textPageGetText(0, count)
        }

        override fun close() {
            textPage.close()
        }
    }

    /**
     * RectF 的四条边按大小排序后再构造 PdfBox。这样无需依赖 crop box / char box
     * 的字段上下顺序差异，也不会把缺失框的 (-1,-1,-1,-1) 当成有效矩形。
     */
    private fun toPdfBox(rect: RectF?): PdfBox? {
        if (rect == null) return null
        return PdfBox.fromEdges(rect.left, rect.top, rect.right, rect.bottom)
    }
}
