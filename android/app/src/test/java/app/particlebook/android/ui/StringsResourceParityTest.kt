package app.particlebook.android.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * i18n key 一致性守卫：默认中文 values 与 values-en 必须一一对应，
 * 任一侧新增/删除 key 都会让本测试失败，避免运行时回退到默认语言。
 */
class StringsResourceParityTest {

    @Test
    fun defaultAndEnglishStringKeysMatch() {
        val default = readStrings("src/main/res/values/strings.xml")
        val english = readStrings("src/main/res/values-en/strings.xml")

        assertEquals(
            "values 与 values-en 的 string key 集合不一致",
            default.keys,
            english.keys,
        )
    }

    @Test
    fun stringKeysAreUniqueAndValuesNonBlank() {
        val default = readStrings("src/main/res/values/strings.xml")
        val english = readStrings("src/main/res/values-en/strings.xml")

        assertEquals("values 存在重复 key", default.size, default.keys.size)
        assertEquals("values-en 存在重复 key", english.size, english.keys.size)
        default.forEach { (key, value) ->
            assertTrue("values 的 $key 为空", value.isNotBlank())
        }
        english.forEach { (key, value) ->
            assertTrue("values-en 的 $key 为空", value.isNotBlank())
        }
    }

    private fun readStrings(relative: String): Map<String, String> {
        val file = findFile(relative)
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(file)
        val nodes = document.getElementsByTagName("string")
        val result = LinkedHashMap<String, String>()
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as Element
            val key = element.getAttribute("name")
            result[key] = element.textContent
        }
        return result
    }

    /** 与其它源码契约测试一致：从测试工作目录向上找 android/app 根。 */
    private fun findFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: "").absoluteFile
        while (dir != null) {
            val candidates = listOf(
                File(dir, relative),
                File(dir, "app/" + relative),
                File(dir, "android/app/" + relative),
            )
            candidates.firstOrNull { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        throw AssertionError(
            "找不到 $relative（工作目录=" + System.getProperty("user.dir") + "）",
        )
    }
}
