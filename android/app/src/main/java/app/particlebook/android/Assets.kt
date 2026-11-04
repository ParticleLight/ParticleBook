package app.particlebook.android

import android.content.Context
import java.io.File

object Assets {
    private const val PREFS_NAME = "pb_assets"
    private const val KEY_DELETED_SEED = "deleted_seed"

    fun readText(context: Context, path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }

    fun glue(context: Context): String = readText(context, "bridge.js")

    fun copyAssetIfMissing(context: Context, assetPath: String, dest: File) {
        if (dest.exists() && dest.length() > 0) return
        dest.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
    }

    /** assets/seed 下的内置样书名。 */
    fun seedNames(context: Context): Set<String> =
        runCatching { context.assets.list("seed")?.toSet() ?: emptySet() }
            .getOrElse { emptySet() }

    /** P3-5：用户从书架删掉的内置样书名；seed() 不再复制、fixtures 也不再合并回来。 */
    fun deletedSeedNames(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_DELETED_SEED, emptySet())?.toSet() ?: emptySet()

    /** P3-5：删书成功后记录被删掉的样书名（只增不减，直到清应用数据）。 */
    fun markSeedDeleted(context: Context, names: Collection<String>) {
        val deleted = names.filter { it.isNotBlank() }.toSet()
        if (deleted.isEmpty()) return
        prefs(context).edit()
            .putStringSet(KEY_DELETED_SEED, deletedSeedNames(context) + deleted)
            .apply()
    }

    fun seed(context: Context, filesDir: File) {
        val deleted = deletedSeedNames(context)
        seedCopyNames(seedNames(context), deleted, filesDir).forEach { name ->
            copyAssetIfMissing(context, "seed/" + name, File(filesDir, name))
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/**
 * P3-5：本次启动应复制哪些内置样书（跳过已删除名单与已存在的非空文件）。
 * 纯函数便于 JVM 单测；真正的复制仍由 [Assets.copyAssetIfMissing] 完成。
 */
internal fun seedCopyNames(seedNames: Collection<String>, deleted: Set<String>, dir: File): List<String> =
    seedNames
        .filter { name ->
            name !in deleted && File(dir, name).let { !it.exists() || it.length() <= 0L }
        }
        .sorted()
