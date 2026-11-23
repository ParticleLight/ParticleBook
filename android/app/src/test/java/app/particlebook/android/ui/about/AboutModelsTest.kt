package app.particlebook.android.ui.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「关于」数据层单测：解析（正常 / 坏 JSON / 缺字段 / 类型不对）、分组排序、过滤、当前版本标记。
 *
 * org.json 在本地 JVM 上是 "not mocked"，所以解析类用例注入 [TestAboutJsonCodec]（见该文件说明）；
 * 另有一条用例专测生产 codec 的「不抛」契约——它在 JVM 与真机上的失败方式不同，但契约相同。
 */
class AboutModelsTest {

    // ------------------------------------------------------------ 更新日志解析

    @Test
    fun parseChangelogKeepsFileOrderAndFields() {
        val json = """
            {
              "schema": 1,
              "entries": [
                { "version": "未发布", "date": "2026-11-23", "highlights": ["跨书全文搜索", "PDF 页级跳转"] },
                { "version": "2.2.1-p0", "date": "2026-11-01", "highlights": ["首个版本号"] }
              ]
            }
        """.trimIndent()

        val entries = AboutJson.parseChangelog(json, TestAboutJsonCodec())

        assertEquals(2, entries.size)
        assertEquals("未发布", entries[0].version)
        assertEquals("2026-11-23", entries[0].date)
        assertEquals(listOf("跨书全文搜索", "PDF 页级跳转"), entries[0].highlights)
        assertEquals("2.2.1-p0", entries[1].version)
    }

    @Test
    fun parseChangelogDropsBlankVersionAndBlankHighlights() {
        val json = """
            {"entries":[
              {"version":"1.0","date":"2026-01-02","highlights":["新功能","","   "]},
              {"version":"   ","date":"2026-01-03","highlights":["不该出现"]}
            ]}
        """.trimIndent()

        val entries = AboutJson.parseChangelog(json, TestAboutJsonCodec())

        assertEquals(1, entries.size)
        assertEquals(listOf("新功能"), entries[0].highlights)
    }

    @Test
    fun parseChangelogReturnsEmptyForMalformedJson() {
        val entries = AboutJson.parseChangelog("{ this is not json", TestAboutJsonCodec())

        assertTrue(entries.isEmpty())
    }

    @Test
    fun parseChangelogReturnsEmptyWhenEntriesMissing() {
        val entries = AboutJson.parseChangelog("""{"schema":1}""", TestAboutJsonCodec())

        assertTrue(entries.isEmpty())
    }

    @Test
    fun parseChangelogDropsEntriesWithWrongFieldTypes() {
        val json = """
            {"entries":[
              {"version":12,"date":"2026-01-01"},
              {"version":"ok","highlights":"不是数组"},
              {"version":"ok2","highlights":["保留",3,"  "]}
            ]}
        """.trimIndent()

        val entries = AboutJson.parseChangelog(json, TestAboutJsonCodec())

        assertEquals(listOf("ok", "ok2"), entries.map { it.version })
        assertTrue(entries[0].highlights.isEmpty())
        assertEquals(listOf("保留"), entries[1].highlights)
    }

    @Test
    fun parseChangelogWithoutDateKeepsEmptyDate() {
        val entries = AboutJson.parseChangelog("""{"entries":[{"version":"1.0"}]}""", TestAboutJsonCodec())

        assertEquals(1, entries.size)
        assertEquals("", entries[0].date)
        assertTrue(entries[0].highlights.isEmpty())
    }

    @Test
    fun productionCodecNeverThrowsOnJvm() {
        // 生产 codec 在 JVM 上是 "not mocked" 失败、在真机上是 JSON 解析失败；
        // 两条路都必须收敛成空表，而不是把异常抛给界面。
        val result = runCatching { AboutJson.parseChangelog("{ oops") }

        assertTrue("parseChangelog 不该抛异常", result.isSuccess)
        assertEquals(emptyList<ChangelogEntry>(), result.getOrNull())
    }

    // ------------------------------------------------------------ 许可清单解析

    @Test
    fun parseLicensesKeepsFields() {
        val json = """
            {"entries":[
              {"name":"androidx.compose.material3:material3","version":"1.5.0","license":"Apache-2.0","url":"http://www.apache.org/licenses/LICENSE-2.0.txt"}
            ]}
        """.trimIndent()

        val entries = AboutJson.parseLicenses(json, TestAboutJsonCodec())

        assertEquals(1, entries.size)
        assertEquals("androidx.compose.material3:material3", entries[0].name)
        assertEquals("1.5.0", entries[0].version)
        assertEquals("Apache-2.0", entries[0].license)
        assertEquals("http://www.apache.org/licenses/LICENSE-2.0.txt", entries[0].url)
    }

    @Test
    fun parseLicensesFallsBackForMissingLicenseAndUrl() {
        val json = """{"entries":[{"name":"com.google.guava:listenablefuture","version":"1.0"}]}"""

        val entries = AboutJson.parseLicenses(json, TestAboutJsonCodec())

        assertEquals(1, entries.size)
        assertEquals(LICENSE_UNKNOWN, entries[0].license)
        assertEquals("", entries[0].url)
    }

    @Test
    fun parseLicensesDropsEntryWithoutName() {
        val json = """{"entries":[{"version":"1.0","license":"MIT"},{"name":"a:b"}]}"""

        val entries = AboutJson.parseLicenses(json, TestAboutJsonCodec())

        assertEquals(listOf("a:b"), entries.map { it.name })
    }

    @Test
    fun parseLicensesReturnsEmptyForMalformedJson() {
        assertTrue(AboutJson.parseLicenses("[]", TestAboutJsonCodec()).isEmpty())
        assertTrue(AboutJson.parseLicenses("""{"entries":"nope"}""", TestAboutJsonCodec()).isEmpty())
    }

    @Test
    fun productionLicenseCodecNeverThrowsOnJvm() {
        val result = runCatching { AboutJson.parseLicenses("{ oops") }

        assertTrue("parseLicenses 不该抛异常", result.isSuccess)
        assertEquals(emptyList<LicenseEntry>(), result.getOrNull())
    }

    // ------------------------------------------------------------ 分组

    @Test
    fun groupLicensesSortsGroupsBySizeThenName() {
        val groups = groupLicenses(
            listOf(
                license("zzz:one", "1.0", "MIT"),
                license("aaa:two", "1.0", "Apache-2.0"),
                license("aaa:three", "1.0", "Apache-2.0"),
            ),
        )

        assertEquals(listOf("Apache-2.0", "MIT"), groups.map { it.license })
        assertEquals(listOf("aaa:three", "aaa:two"), groups[0].entries.map { it.name })
    }

    @Test
    fun groupLicensesSortsEntriesByNameIgnoringCase() {
        val groups = groupLicenses(
            listOf(
                license("zeta:lib", "1.0", "Apache-2.0"),
                license("Alpha:lib", "1.0", "Apache-2.0"),
                license("beta:lib", "1.0", "Apache-2.0"),
            ),
        )

        assertEquals(
            listOf("Alpha:lib", "beta:lib", "zeta:lib"),
            groups.single().entries.map { it.name },
        )
    }

    @Test
    fun groupLicensesBreaksTiesByLicenseName() {
        val groups = groupLicenses(
            listOf(
                license("b:one", "1.0", "MIT"),
                license("a:two", "1.0", "Apache-2.0"),
            ),
        )

        assertEquals(listOf("Apache-2.0", "MIT"), groups.map { it.license })
    }

    @Test
    fun groupLicensesTreatsBlankLicenseAsUnknown() {
        val groups = groupLicenses(
            listOf(
                license("a:one", "1.0", ""),
                license("a:two", "1.0", "   "),
            ),
        )

        assertEquals(LICENSE_UNKNOWN, groups.single().license)
        assertEquals(2, groups.single().entries.size)
    }

    @Test
    fun groupLicensesOfEmptyListIsEmpty() {
        assertTrue(groupLicenses(emptyList()).isEmpty())
    }

    // ------------------------------------------------------------ 过滤

    @Test
    fun filterLicensesMatchesNameIgnoringCase() {
        val entries = listOf(
            license("com.squareup.okio:okio", "3.9.0", "Apache-2.0"),
            license("androidx.core:core-ktx", "1.19.1", "Apache-2.0"),
        )

        assertEquals(listOf("com.squareup.okio:okio"), filterLicenses(entries, "OKIO").map { it.name })
    }

    @Test
    fun filterLicensesMatchesVersionAndLicense() {
        val entries = listOf(
            license("androidx.core:core-ktx", "1.19.1", "Apache-2.0"),
            license("com.google.guava:listenablefuture", "1.0", LICENSE_UNKNOWN),
        )

        assertEquals(listOf("androidx.core:core-ktx"), filterLicenses(entries, "1.19").map { it.name })
        assertEquals(listOf("com.google.guava:listenablefuture"), filterLicenses(entries, "未标注").map { it.name })
    }

    @Test
    fun filterLicensesWithBlankQueryReturnsSameEntries() {
        val entries = listOf(license("a:b", "1.0", "MIT"))

        assertTrue(filterLicenses(entries, "   ") === entries)
    }

    @Test
    fun filterLicensesReturnsEmptyWhenNothingMatches() {
        val entries = listOf(license("a:b", "1.0", "MIT"))

        assertTrue(filterLicenses(entries, "没有这个东西").isEmpty())
    }

    // ------------------------------------------------------------ 当前版本标记

    @Test
    fun isCurrentVersionIgnoresCaseAndSurroundingSpaces() {
        val entry = ChangelogEntry(version = "2.2.1-p0", date = "2026-11-01", highlights = emptyList())

        assertTrue(isCurrentVersion(entry, " 2.2.1-P0 "))
        assertFalse(isCurrentVersion(entry, "2.2.1"))
    }

    @Test
    fun isCurrentVersionIsFalseForBlankCurrentVersion() {
        val entry = ChangelogEntry(version = "2.2.1-p0", date = "", highlights = emptyList())

        assertFalse(isCurrentVersion(entry, ""))
        assertFalse(isCurrentVersion(entry, "   "))
    }

    private fun license(name: String, version: String, license: String) =
        LicenseEntry(name = name, version = version, license = license, url = "")
}
