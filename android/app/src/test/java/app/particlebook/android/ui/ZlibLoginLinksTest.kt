package app.particlebook.android.ui

import app.particlebook.android.zlib.ZMirrorItem
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * B12 修正（realnet T26/T52/T57）：站点已无找回密码页（全部线路软 404），登录页只保留注册外链。
 * 注册外链必须落在「官方非网关」线路；没有可用官方线路时不渲染入口（恢复 B12 原语义）。
 * 源码级守卫防止「忘记密码」死链、主线程读注册表、静默外链失败被重新加回来。
 */
class ZlibLoginLinksTest {

    @Test
    fun buildsRegistrationLinkOnCurrentMirror() {
        assertEquals(
            "https://z-lib.by/registration",
            zlibAccountHelpUrl("https://z-lib.by", "registration"),
        )
    }

    @Test
    fun trailingSlashOnMirrorAndPathDoNotDoubleSlash() {
        assertEquals(
            "https://z-lib.by/registration",
            zlibAccountHelpUrl("https://z-lib.by/", "/registration"),
        )
        assertEquals(
            "https://z-lib.by/registration",
            zlibAccountHelpUrl("  https://z-lib.by//  ", "registration/"),
        )
    }

    @Test
    fun queryAndFragmentOnMirrorAreDroppedBeforeJoiningPath() {
        assertEquals(
            "https://z-lib.by/registration",
            zlibAccountHelpUrl("https://z-lib.by/?utm=1", "registration"),
        )
        assertEquals(
            "https://z-lib.by/registration",
            zlibAccountHelpUrl("https://z-lib.by#top", "registration"),
        )
        assertEquals(
            "https://z-lib.by/path/registration",
            zlibAccountHelpUrl("https://z-lib.by/path/?a=b#frag", "registration"),
        )
        // 只剩查询串的“线路”视作无效，入口隐藏而不是拼出相对链接。
        assertNull(zlibAccountHelpUrl("?x=1", "registration"))
    }

    @Test
    fun noCurrentMirrorHidesEntry() {
        assertNull(zlibAccountHelpUrl(null, "registration"))
        assertNull(zlibAccountHelpUrl("", "registration"))
        assertNull(zlibAccountHelpUrl("   ", "registration"))
    }

    // ------------------------------------------------------------ B12/T52 注册 base 选择

    @Test
    fun registrationBaseUsesFirstOfficialLineInRegistryOrder() {
        assertEquals(
            "https://z-lib.by",
            zlibRegistrationBase(listOf("https://z-lib.by", "https://zh.z-lib.gd")),
        )
    }

    @Test
    fun registrationBaseSkipsGatewayMirrors() {
        // 2026-11-04 实测：zlib.ch / zlib.re 的 /registration 301 -> z-lib.by 首页，不能作为注册 base。
        assertEquals(
            "https://zh.z-lib.gd",
            zlibRegistrationBase(
                listOf("https://zlib.ch", "https://zlib.re", "https://zh.z-lib.gd"),
            ),
        )
        assertEquals(
            "https://z-lib.by",
            zlibRegistrationBase(listOf("https://zlib.ch", "https://zlib.re", "https://z-lib.by")),
        )
        // 网关子域同样要跳过（如 www.zlib.ch）。
        assertEquals(
            "https://z-lib.by",
            zlibRegistrationBase(listOf("https://www.zlib.ch/registration", "https://z-lib.by")),
        )
    }

    @Test
    fun registrationEntryIsHiddenWhenNoOfficialLineIsUsable() {
        // P2-2：只有网关/无效 URL/空列表时没有可用官方线路，入口返回 null（隐藏），不再给恒 fallback。
        assertNull(zlibRegistrationBase(emptyList()))
        assertNull(
            zlibRegistrationBase(listOf("https://zlib.ch", "https://zlib.re", "not a url", "")),
        )
        assertNull(zlibRegistrationLink(emptyList()))
        assertNull(
            zlibRegistrationLink(listOf("https://zlib.ch", "https://www.zlib.re/registration")),
        )
    }

    @Test
    fun registrationBaseNormalizesOriginAndDropsPathQuery() {
        assertEquals(
            "https://z-lib.by",
            zlibRegistrationBase(listOf("  HTTPS://Z-LIB.BY/some/path?utm=1#top  ")),
        )
        assertEquals(
            "https://z-lib.by:8443",
            zlibRegistrationBase(listOf("https://z-lib.by:8443/book/1")),
        )
        assertEquals(
            "http://zh.z-lib.gd",
            zlibRegistrationBase(listOf("http://zh.z-lib.gd/")),
        )
        assertNull(zlibMirrorOrigin(null))
        assertNull(zlibMirrorOrigin("ftp://z-lib.by"))
    }

    @Test
    fun registrationUrlCombinesChosenBaseWithRegistrationPath() {
        assertEquals(
            "https://z-lib.by/registration",
            zlibRegistrationLink(listOf("https://zlib.ch", "https://z-lib.by")),
        )
        // 只有网关时最终 URL 为 null，UI 用 if (registrationUrl != null) 隐藏入口。
        assertNull(zlibRegistrationLink(listOf("https://zlib.ch")))
    }

    // ------------------------------------------------------------ B12/T57 选路健康（P2-3）

    private fun mirrorRow(
        url: String,
        tier: String = "official",
        rank: Int = 1,
        current: Boolean = false,
        demoted: Boolean = false,
    ): ZMirrorItem = ZMirrorItem(
        url = url,
        host = url.substringAfter("://").substringBefore('/'),
        tier = tier,
        rank = rank,
        latencyMs = -1L,
        lastOkAt = 0L,
        successFresh = false,
        current = current,
        pinned = false,
        demoted = demoted,
    )

    @Test
    fun registrationCandidatesPreferCurrentAndKeepRegistryOrder() {
        assertEquals(
            listOf("https://z-lib.by", "https://zh.z-lib.gd"),
            zlibRegistrationCandidates(
                listOf(mirrorRow("https://z-lib.by"), mirrorRow("https://zh.z-lib.gd")),
            ),
        )
        // current 排最前，其余保持 registry 顺序。
        assertEquals(
            listOf("https://zh.z-lib.gd", "https://z-lib.by"),
            zlibRegistrationCandidates(
                listOf(
                    mirrorRow("https://z-lib.by"),
                    mirrorRow("https://zh.z-lib.gd", current = true),
                ),
            ),
        )
    }

    @Test
    fun registrationCandidatesSkipUnhealthyAndNonOfficialRows() {
        val rows = listOf(
            mirrorRow("https://zh.z-lib.gd", rank = 2), // 推广页
            mirrorRow("https://zh.dfj101.ru", rank = 3), // 其它
            mirrorRow("https://zh.zzz101.ru", rank = 4), // 不通
            mirrorRow("https://z-lib.by", demoted = true), // 降权中
            mirrorRow("https://z-lib.gd", tier = "dynamic"),
            mirrorRow("https://zh.singlelogin.rs", tier = "fallback"),
        )
        assertTrue(zlibRegistrationCandidates(rows).isEmpty())
        // 网关也能过健康过滤（rank 正常），但会在 zlibRegistrationBase 里被排除，最终入口隐藏。
        assertNull(
            zlibRegistrationLink(
                zlibRegistrationCandidates(listOf(mirrorRow("https://zlib.ch"))),
            ),
        )
        // 只有通过健康过滤的 official 行会进入最终 URL 选择。
        assertEquals(
            "https://zh.zlib0.ru/registration",
            zlibRegistrationLink(
                zlibRegistrationCandidates(
                    rows + mirrorRow("https://zh.zlib0.ru", current = true),
                ),
            ),
        )
    }

    @Test
    fun browserOpenFailurePromptIsActionableAndContainsUrl() {
        val message = zlibBrowserErrorMessage("https://z-lib.by/registration")
        assertTrue(message.contains("无法打开浏览器"))
        assertTrue(message.contains("手动"))
        assertTrue(message.contains("https://z-lib.by/registration"))
    }

    @Test
    fun loginScreenLoadsRegistryOffMainThreadAndReportsBrowserFailure() {
        val source = loginScreenSource()
        assertFalse(
            "P2-1：不得在 Compose 组合（主线程）里同步调 mirrorRows()",
            source.contains("val registrationBase = remember(context) {"),
        )
        assertTrue(
            "P2-1：注册 base 必须在 IO 线程加载（与 ZlibMirrorSheet 一致）",
            source.contains("withContext(Dispatchers.IO)"),
        )
        assertTrue(
            "P2-1：加载状态用 produceState 驱动",
            source.contains("produceState<String?>(initialValue = null, context)"),
        )
        assertTrue(
            "P2-4：openInBrowser 失败必须回传并渲染提示",
            source.contains("browserOpenFailed = !openInBrowser(context, registrationUrl)"),
        )
        assertTrue(
            "P2-4：失败提示文案由可测纯函数生成",
            source.contains("zlibBrowserErrorMessage(registrationUrl)"),
        )
    }

    @Test
    fun loginScreenDoesNotLinkToPasswordRecovery() {
        val source = loginScreenSource()
        assertFalse(
            "站点 /password-recovery 全线软 404，登录页不得再拼该路径",
            source.contains("password-recovery"),
        )
        assertFalse(
            "不得再渲染「忘记密码」按钮（死链）",
            source.contains("Text(\"忘记密码\")"),
        )
        assertTrue(
            "注册外链必须保留，并改走官方线路选择",
            source.contains("zlibRegistrationLink("),
        )
        assertTrue(
            "官方线路应先经健康过滤（tier == official / current 优先）",
            source.contains("zlibRegistrationCandidates("),
        )
        assertFalse(
            "注册外链不得再用当前线路拼（网关线路 301 后落首页）",
            source.contains("zlibAccountHelpUrl(currentMirrorBase"),
        )
        assertFalse(
            "P2-2：不得再无条件回退 z-lib.by（无可用官方线路时应隐藏入口）",
            source.contains("ZLIB_REGISTRATION_FALLBACK_BASE"),
        )
    }

    private fun loginScreenSource(): String {
        val relative = "src/main/java/app/particlebook/android/ui/ZlibLoginScreen.kt"
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
            "找不到 ZlibLoginScreen.kt（工作目录=" + System.getProperty("user.dir") + "），跳过源码守卫",
            found != null,
        )
        return found!!.readText()
    }
}
