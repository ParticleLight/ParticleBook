package app.particlebook.android.ui

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.particlebook.android.reader.text.TextImage
import app.particlebook.android.reader.text.TextImageExport
import app.particlebook.android.reader.text.TextImageExportSnapshot
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class ImageSaveFailure(val userMessage: String) : Exception(userMessage)

internal data class TextImageSaveAction(
    val enabled: Boolean,
    val onSave: (TextImage, Int) -> Unit,
)

/**
 * 在 AppRoot 无条件调用：阅读器/书架数据尚未恢复时也注册 launcher 并处理系统回投。
 * 保存请求的快照独立于当前书和图片，切图、关查看器或换书均不改变已确认的请求。
 * 快照 token 可恢复，8 MiB 原图不放进 Bundle。成功/失败用 Toast，反馈也能显示在全屏 Dialog 上。
 */
@Composable
internal fun rememberTextImageSaveAction(bookTitle: String): TextImageSaveAction {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val snapshots = remember(context.cacheDir) {
        TextImageExportSnapshot(File(context.cacheDir, "image-exports"))
    }
    var pendingToken by rememberSaveable { mutableStateOf<String?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var writing by remember { mutableStateOf(false) }

    fun notify(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    // StartActivityForResult 保持固定 contract，Intent 的 MIME 随本次原图格式变化。
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val token = pendingToken
        pendingToken = null
        val uri = result.data?.data.takeIf { result.resultCode == Activity.RESULT_OK }
        if (uri == null) {
            if (token != null) scope.launch(Dispatchers.IO) { snapshots.discard(token) }
        } else {
            writing = true
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    // 用户已确认目标，读快照到写完/反馈之间不因 Activity 重建而放弃请求。
                    withContext(NonCancellable) {
                        val bytes = withContext(Dispatchers.IO) {
                            token?.let { snapshots.read(it) }
                                ?: throw ImageSaveFailure("图片缓存已失效，请重新打开图片再保存")
                        }
                        notify("正在保存图片…")
                        withContext(Dispatchers.IO) {
                            val stream = context.contentResolver.openOutputStream(uri, "wt")
                                ?: error("Image export destination is not available")
                            stream.use { it.write(bytes) }
                        }
                        notify("图片已保存")
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    notify((failure as? ImageSaveFailure)?.userMessage ?: "图片保存失败，请检查所选文件或重新选择位置")
                } finally {
                    // 提供方也可能返回既有文档，失败时只清本应用快照，不删除用户选中的 URI。
                    withContext(NonCancellable + Dispatchers.IO) {
                        if (token != null) snapshots.discard(token)
                    }
                    writing = false
                }
            }
        }
    }

    return TextImageSaveAction(
        enabled = !preparing && !writing && pendingToken == null,
        onSave = { image, imageIndex ->
            // 连点与长按共用一道门；pending 绑定按下时的原图，不读取之后的当前图片。
            if (!preparing && !writing && pendingToken == null) {
                preparing = true
                scope.launch {
                    var stagedToken: String? = null
                    var launched = false
                    try {
                        val payload = withContext(Dispatchers.IO) {
                            snapshots.discardExpired(keepToken = null)
                            val prepared = TextImageExport.prepare(image, bookTitle, imageIndex + 1)
                                ?: throw ImageSaveFailure("这张图片没有可保存的原图，请重新打开图片后重试")
                            stagedToken = try {
                                snapshots.stage(prepared)
                            } catch (_: IOException) {
                                throw ImageSaveFailure("图片缓存写入失败，请重试")
                            }
                            prepared
                        }
                        ensureActive()
                        pendingToken = stagedToken
                        createDocument.launch(
                            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = payload.mimeType
                                putExtra(Intent.EXTRA_TITLE, payload.fileName)
                                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            },
                        )
                        launched = true
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        notify((failure as? ImageSaveFailure)?.userMessage ?: "图片保存失败，请重试")
                    } finally {
                        if (!launched) {
                            pendingToken = null
                            // withContext 返回前取消也能拿到 IO 块内已生成的 token，避免遗留本次快照。
                            withContext(NonCancellable + Dispatchers.IO) {
                                stagedToken?.let { snapshots.discard(it) }
                            }
                        }
                        preparing = false
                    }
                }
            }
        },
    )
}
