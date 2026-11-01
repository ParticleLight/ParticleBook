package app.particlebook.android

import android.content.Context
import java.io.File

object Assets {
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

    fun seed(context: Context, filesDir: File) {
        val seeds = try { context.assets.list("seed") ?: emptyArray() } catch (e: Exception) { emptyArray() }
        for (name in seeds) copyAssetIfMissing(context, "seed/" + name, File(filesDir, name))
    }
}