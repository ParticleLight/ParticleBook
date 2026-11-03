package app.particlebook.android.zlib

import java.net.URI

/**
 * 下载层 URL 安全策略（task-51：D5 SSRF / D6 相对 Location / D9 签名判定 / D10 //host 绕过）。
 *
 * 全部是纯函数（no IO、no Android），便于 JVM 单测：
 * - [resolveLocation]：RFC 3986 相对解析（//host/x、../x、?q=1、带空格）；
 * - [isAllowedDownloadUrl]：下载链路只允许 http(s) + 当前镜像 / 镜像域名族 / CDN 白名单，
 *   拒绝 loopback / 私网 / IP 字面量（debug mock 的当前镜像 host 例外）；
 * - [sitePathOf]：分类页参数只取 path+query，吃掉 //evil.com/x 这类协议相对绕过；
 * - [isSignedDownloadUrl]：按 query key 判定一次性签名（token/sig/X-Amz-* 等）。
 */
internal object ZlibUrlPolicy {

    /** 登录后实测的下载 CDN（/dl 302 → dln1.ncdn.ec；封面 cdn-zlib.sk）。 */
    private val CDN_SUFFIXES = listOf("ncdn.ec", "cdn-zlib.sk")

    /** 强签名参数：出现任意一个就视为一次性 URL。 */
    private val STRONG_SIGNED_KEYS = setOf(
        "token", "sig", "signature", "key", "auth_key", "apikey", "api_key",
        "x-amz-signature", "x-amz-credential", "x-amz-security-token", "x-amz-expires",
        "x-goog-signature", "x-goog-credential", "policy", "expires",
    )

    /** 弱签名参数：filename/md5 需两个同时出现才算（避免把普通参数误判成签名）。 */
    private val WEAK_SIGNED_KEYS = setOf("filename", "md5")

    /**
     * RFC 3986 相对引用解析（D6）：统一走 URI.resolve，
     * 处理 //host/x（补 base scheme）、../、?q=1（只换 query）与空格编码。
     */
    fun resolveLocation(base: String, location: String): String {
        val raw = location.trim().replace(" ", "%20")
        if (raw.isEmpty()) return base
        if (raw.startsWith("//")) {
            val scheme = runCatching { URI(base).scheme }.getOrNull() ?: "https"
            return scheme + ":" + raw
        }
        // Java URI.resolve 对「仅 query」引用按 RFC 2396 会丢掉最后一段路径（实测 /dl/ABC + ?x=1 → /dl/?x=1），
        // RFC 3986 要求保留原路径：直接替换 query。
        if (raw.startsWith("?")) {
            val withoutQuery = base.substringBefore('?').substringBefore('#')
            return withoutQuery + raw
        }
        val absolute = runCatching { URI(raw) }.getOrNull()
        if (absolute != null && absolute.isAbsolute) return raw
        val baseUri = runCatching { URI(base.trim().replace(" ", "%20")) }.getOrNull() ?: return raw
        return runCatching { baseUri.resolve(raw).toString() }.getOrDefault(raw)
    }

    /**
     * 分类页/详情页参数 → 站点内 path+query（D10）：绝对 URL 与协议相对 URL
     * 都只保留 path+query，host 由调用方的镜像决定，避免 //evil.com/x 绕过。
     */
    fun sitePathOf(raw: String): String {
        val clean = raw.trim()
        if (clean.isEmpty()) return "/"
        val normalized = when {
            clean.startsWith("//") -> "https:" + clean
            clean.startsWith("http://", ignoreCase = true) ||
                clean.startsWith("https://", ignoreCase = true) -> clean
            else -> return clean
        }
        val parsed = runCatching { URI(normalized) }.getOrNull() ?: return "/"
        val path = parsed.rawPath?.takeIf { it.isNotEmpty() && it.startsWith("/") } ?: "/"
        val query = parsed.rawQuery
        return if (query.isNullOrEmpty()) path else path + "?" + query
    }

    /**
     * 下载链路 URL 白名单（D5）：scheme http(s) + 无 userinfo，host 必须是
     * 当前镜像 / 镜像域名族 / CDN 白名单；loopback/私网/IP 字面量只允许当前镜像
     * （debug mock 场景由调用方传 allowDebugLoopback）。
     */
    fun isAllowedDownloadUrl(url: String, mirrorUrl: String?): Boolean {
        val uri = runCatching { URI(url.trim().replace(" ", "%20")) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        if (!uri.userInfo.isNullOrBlank()) return false
        val host = uri.host?.lowercase() ?: return false
        if (host.isEmpty()) return false
        val mirrorUri = mirrorUrl?.let { runCatching { URI(it.trim()) }.getOrNull() }
        val mirrorHost = mirrorUri?.host?.lowercase().orEmpty()
        val targetPort = if (uri.port != -1) uri.port else defaultPort(scheme)
        val mirrorPort = mirrorUri?.let {
            if (it.port != -1) it.port else defaultPort(it.scheme?.lowercase() ?: "https")
        } ?: -1
        val isMirror = mirrorHost.isNotEmpty() && host == mirrorHost &&
            scheme == (mirrorUri?.scheme?.lowercase() ?: scheme) && targetPort == mirrorPort
        // 当前镜像自身永远放行（含 debug mock 的 127.0.0.1:随机端口）；其余目标必须是
        // 标准端口的镜像域名族或 CDN，且不能是 IP 字面量/私网。
        if (isMirror) return true
        val port = uri.port
        if (port != -1 && port != 80 && port != 443) return false
        if (isIpLiteral(host) || isLoopbackOrPrivate(host)) return false
        if (ZlibMirrorList.isTrustedHost(host)) return true
        return CDN_SUFFIXES.any { host == it || host.endsWith("." + it) }
    }

    /** 一次性签名 URL 判定（D9）：按 query key 解析，不再对整串 contains。 */
    fun isSignedDownloadUrl(raw: String?): Boolean {
        val value = raw?.trim() ?: return false
        val query = runCatching { URI(value).rawQuery }.getOrNull() ?: return false
        val keys = queryKeys(query)
        if (keys.any { it in STRONG_SIGNED_KEYS }) return true
        return keys.count { it in WEAK_SIGNED_KEYS } >= 2
    }

    fun queryKeys(rawQuery: String): Set<String> = rawQuery.split('&')
        .mapNotNull { pair ->
            val key = pair.substringBefore('=').trim().lowercase()
            key.takeIf { it.isNotEmpty() }
        }
        .toSet()

    /** 取 query 参数原始值（未解码）；同名取第一个。 */
    fun rawQueryParam(url: String, name: String): String? {
        val query = runCatching { URI(url.trim()).rawQuery }.getOrNull() ?: return null
        for (pair in query.split('&')) {
            val index = pair.indexOf('=')
            val key = if (index < 0) pair else pair.substring(0, index)
            if (!key.equals(name, ignoreCase = true)) continue
            return if (index < 0) "" else pair.substring(index + 1)
        }
        return null
    }

    /**
     * RFC 3986 百分号解码（D22）：'+' 保持原样（不按表单语义转空格）；
     * charset 只影响 filename* 的 ISO-8859-1 分支，默认 UTF-8。
     */
    fun percentDecode(raw: String, charsetName: String = "UTF-8"): String? = try {
        val out = java.io.ByteArrayOutputStream(raw.length)
        var index = 0
        while (index < raw.length) {
            val ch = raw[index]
            if (ch == '%' && index + 2 < raw.length) {
                val hex = raw.substring(index + 1, index + 3).toIntOrNull(16)
                if (hex != null) {
                    out.write(hex)
                    index += 3
                    continue
                }
            }
            val bytes = ch.toString().toByteArray(Charsets.UTF_8)
            out.write(bytes, 0, bytes.size)
            index++
        }
        val charset = if (charsetName.equals("ISO-8859-1", ignoreCase = true)) {
            Charsets.ISO_8859_1
        } else {
            Charsets.UTF_8
        }
        String(out.toByteArray(), charset)
    } catch (e: Exception) {
        null
    }

    private fun defaultPort(scheme: String): Int =
        if (scheme.equals("http", ignoreCase = true)) 80 else 443

    fun hostOf(url: String?): String =
        url?.let { runCatching { URI(it.trim()).host }.getOrNull() }?.lowercase().orEmpty()

    /** IPv4/IPv6 字面量（不做 DNS；域名不在此判定内）。 */
    fun isIpLiteral(host: String): Boolean {
        val h = host.trim().trim('[', ']')
        if (h.isEmpty()) return false
        if (h.contains(':')) {
            return h.all { it.isDigit() || it in "abcdefABCDEF:." }
        }
        val parts = h.split('.')
        if (parts.size != 4) return false
        return parts.all { part -> part.isNotEmpty() && part.length <= 3 && part.all { it.isDigit() } }
    }

    /** loopback / 私网 / link-local / localhost（只看字面量，不做 DNS）。 */
    fun isLoopbackOrPrivate(host: String): Boolean {
        val h = host.trim().lowercase().trim('[', ']')
        if (h.isEmpty()) return true
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local")) return true
        if (h == "::1" || h == "0.0.0.0" || h == "::") return true
        if (!isIpLiteral(h)) return false
        if (h.contains(':')) {
            // IPv6：fc00::/7（私网）、fe80::/10（link-local）、::1（上面已判）
            return h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe8") ||
                h.startsWith("fe9") || h.startsWith("fea") || h.startsWith("feb")
        }
        val parts = h.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return true
        val (a, b) = parts[0] to parts[1]
        return when {
            a == 10 || a == 127 -> true
            a == 172 && b in 16..31 -> true
            a == 192 && b == 168 -> true
            a == 169 && b == 254 -> true
            a == 0 -> true
            else -> false
        }
    }
}
