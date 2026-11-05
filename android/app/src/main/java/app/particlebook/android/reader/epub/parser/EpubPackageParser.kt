package app.particlebook.android.reader.epub.parser

import app.particlebook.android.reader.epub.EpubManifestItem
import app.particlebook.android.reader.epub.EpubMetadata
import app.particlebook.android.reader.epub.EpubSpineItem
import app.particlebook.android.reader.epub.EpubTocEntry

internal data class ParsedPackage(
    val metadata: EpubMetadata,
    val manifest: List<EpubManifestItem>,
    val spine: List<EpubSpineItem>,
    val toc: List<EpubTocEntry>,
    val opfPath: String,
    val opfDir: String,
    val manifestById: Map<String, EpubManifestItem>,
    val navHref: String? = null,
    val ncxHref: String? = null,
) {
    /** 章节解析需要的所有外部 CSS（manifest 顺序），供 XhtmlBlockParser 使用。 */
    fun cssHrefs(): List<String> =
        manifest.filter { it.mediaType.equals("text/css", ignoreCase = true) }.map { it.href }
}

internal object EpubPackageParser {

    fun parse(archive: EpubArchive, warnings: MutableList<String>): ParsedPackage {
        val containerBytes = archive.read("META-INF/container.xml")
            ?: throw EpubParseException("MISSING_CONTAINER: META-INF/container.xml")
        val container = try {
            EpubXml.parse(containerBytes, "META-INF/container.xml")
        } catch (e: EpubParseException) {
            throw EpubParseException("MISSING_CONTAINER: bad container.xml", e)
        }
        val rootfile = container.firstDescendant("rootfile")
            ?: throw EpubParseException("MISSING_OPF: container.xml has no rootfile")
        val opfRaw = rootfile.attr("full-path")?.trim().orEmpty()
        if (opfRaw.isEmpty()) throw EpubParseException("MISSING_OPF: rootfile has no full-path")
        val opfPath = EpubPaths.normalizePath(opfRaw)
        val opfBytes = archive.read(opfPath)
            ?: throw EpubParseException("MISSING_OPF: $opfPath")
        val opf = try {
            EpubXml.parse(opfBytes, opfPath)
        } catch (e: EpubParseException) {
            throw EpubParseException("MISSING_OPF: bad opf $opfPath", e)
        }
        val packageElement = if (opf.name == "package") opf else opf.firstDescendant("package")
            ?: throw EpubParseException("MISSING_OPF: no <package> in $opfPath")
        val metadataElement = packageElement.firstElement("metadata")
            ?: throw EpubParseException("MISSING_OPF: no <metadata> in $opfPath")
        val manifestElement = packageElement.firstElement("manifest")
            ?: throw EpubParseException("MISSING_OPF: no <manifest> in $opfPath")
        val spineElement = packageElement.firstElement("spine")
            ?: throw EpubParseException("EMPTY_SPINE: no <spine> in $opfPath")

        val metadata = parseMetadata(metadataElement, packageElement.attr("unique-identifier"))
        val manifest = ArrayList<EpubManifestItem>()
        val byId = LinkedHashMap<String, EpubManifestItem>()
        for (item in manifestElement.elements()) {
            if (item.name != "item") continue
            val id = item.attr("id")?.trim().orEmpty()
            val hrefRaw = item.attr("href")?.trim().orEmpty()
            if (id.isEmpty() || hrefRaw.isEmpty()) {
                warnings += "manifest item missing id/href"
                continue
            }
            if (EpubPaths.isExternal(hrefRaw)) {
                warnings += "skip external manifest item: $hrefRaw"
                continue
            }
            val href = try {
                EpubPaths.resolve(opfPath, hrefRaw)
            } catch (e: EpubParseException) {
                warnings += "bad manifest href: $hrefRaw"
                continue
            }
            val properties = item.attr("properties")
                ?.split(Regex("\\s+"))
                ?.filter { it.isNotBlank() }
                ?.toSet()
                ?: emptySet()
            val manifestItem = EpubManifestItem(
                id = id,
                href = href,
                mediaType = item.attr("media-type")?.trim().orEmpty(),
                properties = properties,
            )
            manifest += manifestItem
            if (!byId.containsKey(id)) byId[id] = manifestItem
        }
        val spine = ArrayList<EpubSpineItem>()
        for (itemref in spineElement.elements()) {
            if (itemref.name != "itemref") continue
            val idref = itemref.attr("idref")?.trim().orEmpty()
            if (idref.isEmpty()) continue
            val item = byId[idref]
            if (item == null) {
                warnings += "spine idref not found: $idref"
                continue
            }
            spine += EpubSpineItem(
                idref = idref,
                href = item.href,
                linear = !itemref.attr("linear").equals("no", ignoreCase = true),
            )
        }
        if (spine.isEmpty()) throw EpubParseException("EMPTY_SPINE: $opfPath")

        val navHref = manifest.firstOrNull { "nav" in it.properties }?.href
        val ncxHref = run {
            val tocId = spineElement.attr("toc")
            if (!tocId.isNullOrBlank()) byId[tocId]?.href
            else manifest.firstOrNull { it.mediaType.contains("dtbncx", ignoreCase = true) }?.href
        }
        val toc = parseToc(archive, navHref, ncxHref, warnings)

        return ParsedPackage(
            metadata = metadata,
            manifest = manifest,
            spine = spine,
            toc = toc,
            opfPath = opfPath,
            opfDir = opfPath.substringBeforeLast('/', ""),
            manifestById = byId,
            navHref = navHref,
            ncxHref = ncxHref,
        )
    }

    private fun parseMetadata(metadata: XmlElement, uniqueId: String?): EpubMetadata {
        val titles = metadata.descendants().filter { it.name == "title" }.map { cleanText(it.text()) }
        val creators = metadata.descendants().filter { it.name == "creator" }.map { cleanText(it.text()) }
        val languages = metadata.descendants().filter { it.name == "language" }.map { cleanText(it.text()) }
        val identifiers = metadata.descendants().filter { it.name == "identifier" }.toList()
        val identifier = identifiers.firstOrNull { it.attr("id") == uniqueId && cleanText(it.text()).isNotEmpty() }
            ?: identifiers.firstOrNull { cleanText(it.text()).isNotEmpty() }
        return EpubMetadata(
            title = titles.firstOrNull { it.isNotEmpty() } ?: "未命名",
            author = creators.filter { it.isNotEmpty() }.joinToString("、").ifEmpty { null },
            language = languages.firstOrNull { it.isNotEmpty() },
            identifier = identifier?.let { cleanText(it.text()) }?.ifEmpty { null },
        )
    }

    private fun parseToc(
        archive: EpubArchive,
        navHref: String?,
        ncxHref: String?,
        warnings: MutableList<String>,
    ): List<EpubTocEntry> {
        if (navHref != null) {
            try {
                val entries = parseNav(archive, navHref)
                if (entries.isNotEmpty()) return entries
            } catch (e: EpubParseException) {
                warnings += "nav toc parse failed: ${e.message}"
            }
        }
        if (ncxHref != null) {
            try {
                return parseNcx(archive, ncxHref)
            } catch (e: EpubParseException) {
                warnings += "ncx toc parse failed: ${e.message}"
            }
        }
        return emptyList()
    }

    private fun parseNav(archive: EpubArchive, navHref: String): List<EpubTocEntry> {
        val bytes = archive.read(navHref) ?: return emptyList()
        val root = EpubXml.parse(bytes, navHref)
        val navs = root.descendants().filter { it.name == "nav" }.toList()
        val tocNav = navs.firstOrNull {
            val type = it.attr("epub:type", "type")?.lowercase().orEmpty()
            type.split(Regex("\\s+")).contains("toc")
        } ?: navs.firstOrNull() ?: return emptyList()
        val list = tocNav.elements().firstOrNull { it.name == "ol" || it.name == "ul" } ?: return emptyList()
        return parseNavList(list, navHref)
    }

    private fun parseNavList(list: XmlElement, baseDoc: String): List<EpubTocEntry> {
        val out = ArrayList<EpubTocEntry>()
        for (li in list.elements()) {
            if (li.name != "li") continue
            val direct = li.elements()
            val anchor = direct.firstOrNull { it.name == "a" }
            val labelNode = anchor ?: direct.firstOrNull { it.name == "span" }
            val title = cleanText(labelNode?.text().orEmpty()).ifEmpty { "未命名" }
            val hrefRaw = anchor?.attr("href")?.trim().orEmpty()
            val href = if (hrefRaw.isEmpty()) {
                ""
            } else if (EpubPaths.isExternal(hrefRaw)) {
                hrefRaw
            } else {
                runCatching { EpubPaths.resolveWithFragment(baseDoc, hrefRaw) }.getOrDefault("")
            }
            val nestedList = direct.firstOrNull { it.name == "ol" || it.name == "ul" }
            val children = nestedList?.let { parseNavList(it, baseDoc) } ?: emptyList()
            if (href.isEmpty() && children.isEmpty()) continue
            out += EpubTocEntry(title = title, href = href, children = children)
        }
        return out
    }

    private fun parseNcx(archive: EpubArchive, ncxHref: String): List<EpubTocEntry> {
        val bytes = archive.read(ncxHref) ?: return emptyList()
        val root = EpubXml.parse(bytes, ncxHref)
        val navMap = root.descendants().firstOrNull { it.name.equals("navMap", ignoreCase = true) }
            ?: return emptyList()
        return parseNavPoints(navMap, ncxHref)
    }

    private fun parseNavPoints(parent: XmlElement, baseDoc: String): List<EpubTocEntry> {
        val out = ArrayList<EpubTocEntry>()
        for (point in parent.elements()) {
            if (!point.name.equals("navPoint", ignoreCase = true)) continue
            val titleNode = point.firstDescendant("text")
            val title = cleanText(titleNode?.text().orEmpty()).ifEmpty { "未命名" }
            val content = point.firstDescendant("content")
            val src = content?.attr("src")?.trim().orEmpty()
            val href = if (src.isEmpty()) "" else runCatching {
                EpubPaths.resolveWithFragment(baseDoc, src)
            }.getOrDefault("")
            val children = parseNavPoints(point, baseDoc)
            if (href.isEmpty() && children.isEmpty()) continue
            out += EpubTocEntry(title = title, href = href, children = children)
        }
        return out
    }
}

private fun XmlElement.descendants(): Sequence<XmlElement> = sequence {
    for (element in elements()) {
        yield(element)
        yieldAll(element.descendants())
    }
}

private fun XmlElement.firstDescendant(name: String): XmlElement? =
    descendants().firstOrNull { it.name == name }

private fun cleanText(text: String): String =
    text.replace('\u00A0', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
