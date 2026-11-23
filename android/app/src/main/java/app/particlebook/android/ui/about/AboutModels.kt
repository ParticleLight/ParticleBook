package app.particlebook.android.ui.about

import org.json.JSONArray
import org.json.JSONObject

/**
 * 「关于」组的数据模型与纯函数。
 *
 * 这一层刻意不出现任何 Android / Compose 类型：解析、分组、过滤都能在 JVM 单测里直接跑。
 * JSON 由 `assets/about/{changelog,licenses}.json` 提供，字段与 `scripts/android/gen-licenses.mjs` 的输出对齐。
 */

/** 一条更新日志：版本 + 日期 + 该版本做了什么。版本「未发布」表示还没发版的累积改动。 */
data class ChangelogEntry(
    val version: String,
    val date: String,
    val highlights: List<String>,
)

/** 一条第三方依赖：坐标（group:artifact）+ 版本 + 许可 + 许可/主页链接（可能为空）。 */
data class LicenseEntry(
    val name: String,
    val version: String,
    val license: String,
    val url: String,
)

/** 同一许可下的一组依赖：组头显示「许可证 · N 个」。 */
data class LicenseGroup(
    val license: String,
    val entries: List<LicenseEntry>,
)

/**
 * 依赖的 POM 没声明许可时的取值（gen-licenses.mjs 写出的同名字段）。
 * 中文界面里直接显示这个词，不做二次推断——推断出来的许可证等于编造。
 */
internal const val LICENSE_UNKNOWN = "未标注"

/**
 * JSON 读取的最小接口。
 *
 * 生产实现是 [OrgJsonAboutCodec]（Android 的 org.json）。本地 JVM 单测里 android.jar 的 org.json
 * 是 "not mocked"：调用即抛 RuntimeException，因此单测注入同接口的最小实现
 * （见 src/test 的 `TestAboutJsonCodec`），与被测代码共用同一条解析管线。
 */
internal interface AboutJsonCodec {
    /**
     * 读出根对象 [arrayKey] 字段里的对象数组。
     *
     * 每个对象展开成 key → 值，值只可能是字符串、字符串数组或 null；
     * 解析失败直接抛异常，由 [AboutJson] 统一兜成空表。
     */
    fun readObjectArray(json: String, arrayKey: String): List<Map<String, Any?>>
}

/** 生产实现：Android 的 org.json。 */
internal object OrgJsonAboutCodec : AboutJsonCodec {
    override fun readObjectArray(json: String, arrayKey: String): List<Map<String, Any?>> {
        val array = JSONObject(json).optJSONArray(arrayKey) ?: return emptyList()
        return (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let(::flatten) }
    }

    private fun flatten(source: JSONObject): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for (key in source.keys()) out[key] = normalize(source.opt(key))
        return out
    }

    private fun normalize(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> flatten(value)
        is JSONArray -> (0 until value.length()).map { normalize(value.opt(it)) }
        else -> value
    }
}

/**
 * `assets/about/{changelog,licenses}.json` 的解析器。
 *
 * 契约：任何输入都不抛异常——坏 JSON、字段类型不对、根对象缺 `entries` 一律返回空表，
 * 由界面显示空态。宁可少显示一页，也不让「关于」页把整个设置页带崩。
 *
 * 返回顺序即文件顺序（更新日志按新→旧写盘），这里不重排。
 */
object AboutJson {

    /** 更新日志：`{ "entries": [ { "version": "", "date": "", "highlights": [] } ] }`。 */
    fun parseChangelog(json: String): List<ChangelogEntry> = parseChangelog(json, OrgJsonAboutCodec)

    /** 许可清单：`{ "entries": [ { "name": "", "version": "", "license": "", "url": "" } ] }`。 */
    fun parseLicenses(json: String): List<LicenseEntry> = parseLicenses(json, OrgJsonAboutCodec)

    internal fun parseChangelog(json: String, codec: AboutJsonCodec): List<ChangelogEntry> =
        runCatching {
            codec.readObjectArray(json, "entries").mapNotNull { row ->
                val version = row.text("version") ?: return@mapNotNull null
                ChangelogEntry(
                    version = version,
                    date = row.text("date").orEmpty(),
                    highlights = row.textList("highlights"),
                )
            }
        }.getOrDefault(emptyList())

    internal fun parseLicenses(json: String, codec: AboutJsonCodec): List<LicenseEntry> =
        runCatching {
            codec.readObjectArray(json, "entries").mapNotNull { row ->
                val name = row.text("name") ?: return@mapNotNull null
                LicenseEntry(
                    name = name,
                    version = row.text("version").orEmpty(),
                    // 缺许可字段与「未标注」是同一种事实：POM 没声明，界面文案也一致。
                    license = row.text("license") ?: LICENSE_UNKNOWN,
                    url = row.text("url").orEmpty(),
                )
            }
        }.getOrDefault(emptyList())
}

/** 取非空白字符串；类型不对或全空白都当缺省。 */
private fun Map<String, Any?>.text(key: String): String? =
    (this[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }

/** 取字符串数组，丢掉空白项；不是数组就当空表。 */
private fun Map<String, Any?>.textList(key: String): List<String> =
    (this[key] as? List<*>).orEmpty().mapNotNull { value ->
        (value as? String)?.trim()?.takeIf { it.isNotEmpty() }
    }

/**
 * 按许可归组：组内按名称排序（同名再按版本），组间按条目数降序、同数量再按许可名字典序。
 *
 * 排序是确定的：同一份 licenses.json 在任何设备上渲染出的顺序完全一致，
 * 也让「组头顺序变了」这种回归在单测里可比较。空白许可归到 [LICENSE_UNKNOWN]。
 */
fun groupLicenses(entries: List<LicenseEntry>): List<LicenseGroup> =
    entries
        .groupBy { it.license.trim().ifEmpty { LICENSE_UNKNOWN } }
        .map { (license, items) ->
            LicenseGroup(
                license = license,
                entries = items.sortedWith(LICENSE_ENTRY_ORDER),
            )
        }
        .sortedWith(
            compareByDescending<LicenseGroup> { it.entries.size }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.license },
        )

/**
 * 本地过滤：在 name / license / version 上做大小写无关的子串匹配（与界面文案一致）。
 * 空白查询原样返回——调用方不需要判断「空搜索」这一支。
 */
fun filterLicenses(entries: List<LicenseEntry>, query: String): List<LicenseEntry> {
    val needle = query.trim()
    if (needle.isEmpty()) return entries
    return entries.filter { entry ->
        entry.name.contains(needle, ignoreCase = true) ||
            entry.license.contains(needle, ignoreCase = true) ||
            entry.version.contains(needle, ignoreCase = true)
    }
}

/** 当前安装版本标记：大小写与首尾空白无关；[currentVersion] 为空时一律不标记。 */
fun isCurrentVersion(entry: ChangelogEntry, currentVersion: String): Boolean {
    val target = currentVersion.trim()
    return target.isNotEmpty() && entry.version.trim().equals(target, ignoreCase = true)
}

private val LICENSE_ENTRY_ORDER: Comparator<LicenseEntry> =
    compareBy<LicenseEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.version }
