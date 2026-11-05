package app.particlebook.android.reader.epub.parser

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.IOException
import java.io.StringReader
import java.nio.charset.Charset
import javax.xml.parsers.SAXParserFactory

/** XML/HTML 共用的轻量文本解码 + 实体归一。 */
internal object EpubText {
    private val DECLARED_ENCODING = Regex("""encoding\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val ENTITY = Regex("""&(#x?[0-9A-Fa-f]+|[A-Za-z][A-Za-z0-9]+);""")
    private val BARE_AMP = Regex("""&(?!#?[A-Za-z0-9]+;)""")

    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val declared = declaredEncoding(bytes)
        val candidates = LinkedHashSet<String>()
        when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> candidates += "UTF-16LE"
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> candidates += "UTF-16BE"
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> candidates += "UTF-8"
        }
        if (declared != null) candidates += declared
        candidates += "UTF-8"
        candidates += "GB18030"

        var best: String? = null
        var bestBad = Int.MAX_VALUE
        for (name in candidates) {
            val charset = runCatching { Charset.forName(name) }.getOrNull() ?: continue
            val text = runCatching { String(bytes, charset) }.getOrNull() ?: continue
            val bad = text.count { it == '\uFFFD' }
            if (best == null || bad < bestBad) {
                best = text
                bestBad = bad
                if (bad == 0) break
            }
        }
        return (best ?: String(bytes, Charsets.UTF_8)).removePrefix("\uFEFF")
    }

    fun normalizeEntities(input: String): String {
        var text = input
        text = ENTITY.replace(text) { match ->
            val body = match.groupValues[1]
            when {
                body.startsWith("#x") || body.startsWith("#X") -> {
                    val code = body.substring(2).toIntOrNull(16)
                    numericEntity(code)
                }
                body.startsWith("#") -> numericEntity(body.substring(1).toIntOrNull())
                else -> {
                    val mapped = NAMED_ENTITIES[body] ?: NAMED_ENTITIES[body.lowercase()]
                    mapped ?: ("&amp;" + body + ";")
                }
            }
        }
        // 裸 & 统一转义；已经形成 &...; 的保留。
        text = BARE_AMP.replace(text, "&amp;")
        return text
    }

    private fun numericEntity(code: Int?): String {
        if (code == null || code <= 0 || code > 0x10FFFF || code in 0xD800..0xDFFF) return "&#xFFFD;"
        return "&#$code;"
    }

    private fun declaredEncoding(bytes: ByteArray): String? {
        val n = minOf(bytes.size, 512)
        val head = String(bytes, 0, n, Charsets.ISO_8859_1)
        return DECLARED_ENCODING.find(head)?.groupValues?.get(1)?.trim()
    }

    private val NAMED_ENTITIES: Map<String, String> = buildMap {
        put("amp", "&")
        put("lt", "<")
        put("gt", ">")
        put("quot", "\"")
        put("apos", "'")
        put("nbsp", "&#160;")
        put("ensp", "&#8194;")
        put("emsp", "&#8195;")
        put("thinsp", "&#8201;")
        put("shy", "&#173;")
        put("copy", "&#169;")
        put("reg", "&#174;")
        put("trade", "&#8482;")
        put("hellip", "&#8230;")
        put("mdash", "&#8212;")
        put("ndash", "&#8211;")
        put("lsquo", "&#8216;")
        put("rsquo", "&#8217;")
        put("ldquo", "&#8220;")
        put("rdquo", "&#8221;")
        put("laquo", "&#171;")
        put("raquo", "&#187;")
        put("times", "&#215;")
        put("divide", "&#247;")
        put("plusmn", "&#177;")
        put("deg", "&#176;")
        put("micro", "&#181;")
        put("middot", "&#183;")
        put("bull", "&#8226;")
        put("dagger", "&#8224;")
        put("Dagger", "&#8225;")
        put("permil", "&#8240;")
        put("euro", "&#8364;")
        put("pound", "&#163;")
        put("yen", "&#165;")
        put("cent", "&#162;")
        put("sect", "&#167;")
        put("para", "&#182;")
        put("frac12", "&#189;")
        put("frac14", "&#188;")
        put("frac34", "&#190;")
        put("sup2", "&#178;")
        put("sup3", "&#179;")
        put("alpha", "&#945;")
        put("beta", "&#946;")
        put("gamma", "&#947;")
        put("delta", "&#948;")
        put("pi", "&#960;")
        put("sigma", "&#963;")
        put("omega", "&#969;")
    }
}

internal sealed interface XmlPart

internal data class XmlTextPart(val value: String) : XmlPart

internal class XmlElement(
    val name: String,
    val attributes: Map<String, String>,
) : XmlPart {
    val children: MutableList<XmlPart> = ArrayList()

    fun elements(): List<XmlElement> = children.filterIsInstance<XmlElement>()

    fun firstElement(vararg wanted: String): XmlElement? {
        val set = wanted.map { it.lowercase() }.toSet()
        return elements().firstOrNull { it.name in set }
    }

    fun allElements(vararg wanted: String): List<XmlElement> {
        val set = wanted.map { it.lowercase() }.toSet()
        return elements().filter { it.name in set }
    }

    fun attr(vararg names: String): String? {
        for (name in names) {
            attributes[name]?.let { return it }
            attributes[name.lowercase()]?.let { return it }
        }
        return null
    }

    fun text(): String {
        val out = StringBuilder()
        appendText(out)
        return out.toString()
    }

    private fun appendText(out: StringBuilder) {
        for (part in children) {
            when (part) {
                is XmlTextPart -> out.append(part.value)
                is XmlElement -> part.appendText(out)
            }
        }
    }
}

internal object EpubXml {
    fun parse(bytes: ByteArray, source: String): XmlElement {
        val raw = EpubText.decode(bytes)
        val xml = EpubText.normalizeEntities(raw)
        try {
            return parseString(xml)
        } catch (e: EpubParseException) {
            throw e
        } catch (e: Exception) {
            throw EpubParseException("BAD_XML: $source: ${e.message}", e)
        }
    }

    private fun parseString(xml: String): XmlElement {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = true
        setFactoryFeature(factory, "http://xml.org/sax/features/external-general-entities", false)
        setFactoryFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false)
        setFactoryFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        val parser = factory.newSAXParser()
        val reader = parser.xmlReader
        setReaderFeature(reader, "http://xml.org/sax/features/external-general-entities", false)
        setReaderFeature(reader, "http://xml.org/sax/features/external-parameter-entities", false)
        setReaderFeature(reader, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> InputSource(StringReader("")) }
        val handler = TreeHandler()
        reader.contentHandler = handler
        reader.errorHandler = handler
        reader.parse(InputSource(StringReader(xml)))
        return handler.root ?: throw EpubParseException("BAD_XML: no root element")
    }

    private fun setFactoryFeature(factory: SAXParserFactory, name: String, value: Boolean) {
        try {
            factory.setFeature(name, value)
        } catch (_: Exception) {
            // Android/Expat 不认识的 feature 忽略；安全兜底由 EntityResolver 完成。
        }
    }

    private fun setReaderFeature(reader: org.xml.sax.XMLReader, name: String, value: Boolean) {
        try {
            reader.setFeature(name, value)
        } catch (_: Exception) {
        }
    }
}

private class TreeHandler : DefaultHandler() {
    var root: XmlElement? = null
    private val stack = ArrayDeque<XmlElement>()

    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
        if (stack.size > 256) throw SAXException("xml nesting too deep")
        val name = when {
            !localName.isNullOrEmpty() -> localName.lowercase()
            !qName.isNullOrEmpty() -> qName.substringAfter(':').lowercase()
            else -> "unknown"
        }
        val map = LinkedHashMap<String, String>()
        for (i in 0 until attributes.length) {
            val q = attributes.getQName(i)
            val local = attributes.getLocalName(i)
            val value = attributes.getValue(i) ?: ""
            if (!q.isNullOrEmpty()) map[q] = value
            if (!local.isNullOrEmpty()) map.putIfAbsent(local, value)
            if (local.isNullOrEmpty() && !q.isNullOrEmpty()) map.putIfAbsent(q.substringAfter(':'), value)
        }
        val element = XmlElement(name, map)
        val parent = stack.lastOrNull()
        if (parent == null) {
            root = element
        } else {
            parent.children += element
        }
        stack.addLast(element)
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) {
        if (stack.isNotEmpty()) stack.removeLast()
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (length == 0) return
        stack.lastOrNull()?.children?.add(XmlTextPart(String(ch, start, length)))
    }

    override fun ignorableWhitespace(ch: CharArray, start: Int, length: Int) {
        characters(ch, start, length)
    }

    override fun error(e: SAXParseException) {
        throw e
    }

    override fun fatalError(e: SAXParseException) {
        throw e
    }

    override fun warning(e: SAXParseException) {
        // 忽略非致命 warning
    }
}
