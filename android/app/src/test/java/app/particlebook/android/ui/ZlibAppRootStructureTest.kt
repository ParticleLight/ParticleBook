package app.particlebook.android.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * U7 / P2 的结构守卫（本工程没有 compose-ui-test，只能对 AppRoot.kt 做源码级契约检查）：
 *
 * - U7：下载任务流只允许在 ZlibDownloadCardsHost 子树里 collect，
 *   ParticleBookApp 根节点不得订阅下载进度（否则 150ms 一次的任务列表刷新会重组整棵树）；
 * - P2：navStack 只有栈顶层拿全屏约束，非栈顶层被压成 0 约束（保留组合、不触发列表分页/封面加载）。
 */
class ZlibAppRootStructureTest {

    private fun appRootSource(): String {
        val relative = "src/main/java/app/particlebook/android/ui/AppRoot.kt"
        val candidates = ArrayList<File>()
        candidates += File(relative)
        candidates += File("app/" + relative)
        var dir: File? = File(System.getProperty("user.dir") ?: "").absoluteFile
        repeat(6) {
            val current = dir
            if (current != null) {
                candidates += File(current, "android/app/" + relative)
                candidates += File(current, "app/" + relative)
                candidates += File(current, relative)
                dir = current.parentFile
            }
        }
        val found = candidates.firstOrNull { it.isFile }
        assumeTrue(
            "找不到 AppRoot.kt（工作目录=" + System.getProperty("user.dir") + "），跳过结构守卫",
            found != null,
        )
        return found!!.readText()
    }

    private fun rootBody(source: String): String {
        val start = source.indexOf("fun ParticleBookApp()")
        val end = source.indexOf("private fun ZlibDownloadCardsHost(")
        assertTrue("AppRoot.kt 结构变了：找不到 ParticleBookApp/ZlibDownloadCardsHost", start in 0 until end)
        return source.substring(start, end)
    }

    @Test
    fun rootNodeDoesNotCollectDownloadTasks() {
        val source = appRootSource()
        val marker = "ZlibDownloadManager.tasks.collectAsState()"
        val occurrences = source.split(marker).size - 1
        assertEquals("tasks.collectAsState() 全文件只应出现在卡片宿主里", 1, occurrences)
        assertFalse(
            "ParticleBookApp 根节点不能在根作用域订阅下载任务（U7）",
            rootBody(source).contains(marker),
        )
        val hostIndex = source.indexOf("private fun ZlibDownloadCardsHost(")
        assertTrue("订阅必须在 ZlibDownloadCardsHost 之后", source.indexOf(marker) > hostIndex)
    }

    @Test
    fun onlyTopNavLayerGetsFullSizeConstraints() {
        val source = appRootSource()
        assertTrue(
            "navStack 渲染必须区分栈顶（P2）",
            source.contains("val isActiveLayer = index == navStack.lastIndex"),
        )
        assertTrue(
            "非栈顶层必须被压成 0 约束，隐藏期间不做常规布局",
            source.contains("if (isActiveLayer) Modifier.fillMaxSize() else Modifier.size(0.dp)"),
        )
    }
}
