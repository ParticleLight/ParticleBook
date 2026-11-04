package app.particlebook.android.ui

import androidx.compose.runtime.saveable.SaverScope
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * task-21：导航栈条目携带 detail 参数（详情 A → 推荐 → 详情 B → 返回 = A）。
 *
 * 本工程没有 compose-ui-test，UI 层只能做两件事：
 * 1. 纯逻辑：push/pop 与 [NavStackSaver] 的栈语义（可完整单测）；
 * 2. 源码契约：AppRoot.kt 不再用全局 detailHash/detailSlug，detail 层从条目取参并持有独立 ViewModel。
 */
class ZlibDetailNavigationTest {

    private val saverScope = object : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }

    @Test
    fun returningFromRecommendedBookRestoresPreviousDetailParams() {
        // 书架 → 详情 A → 推荐 → 详情 B
        var stack = emptyList<NavEntry>()
            .push("discover")
            .push("detail", "hashA", "slugA")
            .push("detail", "hashB", "slugB")
        assertEquals(listOf("discover", "detail", "detail"), stack.map { it.route })
        assertEquals("hashB", stack.last().hash)
        assertEquals("slugB", stack.last().slug)

        // 系统返回弹掉 B：栈顶回到 A，A 层参数没有被 B 覆盖（旧实现读全局状态，这里会是 hashB）。
        stack = stack.pop()
        assertEquals(2, stack.size)
        assertEquals("detail", stack.last().route)
        assertEquals("hashA", stack.last().hash)
        assertEquals("slugA", stack.last().slug)
    }

    @Test
    fun popOnlyAffectsTopLayer() {
        val stack = emptyList<NavEntry>()
            .push("detail", "hashA", "slugA")
            .push("detail", "hashB", null)
        val once = stack.pop()
        assertEquals(1, once.size)
        assertEquals("hashA", once.last().hash)
        assertEquals("slugA", once.last().slug)
        assertEquals(emptyList<NavEntry>(), once.pop())
        assertEquals(emptyList<NavEntry>(), emptyList<NavEntry>().pop())
    }

    @Test
    fun popWithExpectedIgnoresMismatchedTop() {
        // 迟到回调场景：login 已出栈、detail 成为栈顶时，回调里的 pop(expected = "login") 不许弹详情。
        val stack = emptyList<NavEntry>()
            .push("detail", "hashA", "slugA")
            .push("login")
        val mismatched = stack.pop("detail")
        assertEquals(stack, mismatched)
        val popped = stack.pop("login")
        assertEquals(1, popped.size)
        assertEquals("detail", popped.last().route)
        assertEquals("hashA", popped.last().hash)
    }

    @Test
    fun blankDetailParamsAreNormalizedToNull() {
        val entry = emptyList<NavEntry>().push("detail", "   ", "").last()
        assertNull("空白 hash 必须归一为 null", entry.hash)
        assertNull("空 slug 必须归一为 null", entry.slug)
        assertEquals("  hashA  ".trim(), emptyList<NavEntry>().push("detail", "  hashA  ", " slug ").last().hash)
    }

    @Test
    fun paramsSurviveProcessRestore() {
        val stack = emptyList<NavEntry>()
            .push("discover")
            .push("detail", "hashA", "slugA")
            .push("detail", "hashB", null)
            .push("login")
        val saved = with(NavStackSaver) { saverScope.save(stack) }
        val restored = with(NavStackSaver) { saved?.let { restore(it) } }
        assertEquals(
            stack.map { Triple(it.route, it.hash, it.slug) },
            restored?.map { Triple(it.route, it.hash, it.slug) },
        )
    }

    @Test
    fun appRootReadsDetailParamsFromStackEntry() {
        val source = appRootSource()
        assertFalse("全局 detailHash 状态必须删除（旧实现返回会看到第二本书）", source.contains("var detailHash"))
        assertFalse("全局 detailSlug 状态必须删除", source.contains("var detailSlug"))
        assertFalse("根级共享 detailViewModel 必须删除", source.contains("ZlibDetailViewModel(scope, appContext)"))
        assertTrue("detail 层必须按条目取 hash", source.contains("hash = entry.hash.orEmpty()"))
        assertTrue("detail 层必须按条目取 slug", source.contains("slug = entry.slug?.takeIf { it.isNotBlank() }"))
        assertTrue(
            "每层 detail 必须有独立 ViewModel，否则隐藏层会被新书覆盖",
            source.contains("ZlibDetailViewModel(layerScope, appContext)"),
        )
        assertTrue(
            "推荐卡点击必须压入新的 detail 条目",
            source.contains("pushRoute(\"detail\", hash, slug)"),
        )
        assertTrue(
            "登录返程判定要拿路由列表（NavEntry → route）",
            source.contains("navStack.map { it.route }"),
        )
    }

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
            "找不到 AppRoot.kt（工作目录=" + System.getProperty("user.dir") + "），跳过源码契约检查",
            found != null,
        )
        return found!!.readText()
    }
}
