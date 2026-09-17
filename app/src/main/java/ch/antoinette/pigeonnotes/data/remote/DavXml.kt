package ch.antoinette.pigeonnotes.data.remote

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** One resource from a PROPFIND. [path] is relative to the notes root, `/`-separated, no leading slash; `""` is the root. */
data class DavEntry(
    val path: String,
    val isDir: Boolean,
    val etag: String?,
    val size: Long,
    val modified: Long,
    val contentType: String?,
) {
    val name: String get() = path.substringAfterLast('/')
}

/**
 * Parses a WebDAV `multistatus` body. Uses the JDK DOM parser because it exists both on Android
 * and in plain JVM unit tests (unlike XmlPullParser implementations).
 */
object DavXml {
    private const val DAV = "DAV:"

    /** @param rootHref the decoded href of the notes root (e.g. `/remote.php/dav/files/me/Notes`), no trailing slash. */
    fun parseMultistatus(xml: String, rootHref: String): List<DavEntry> {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))
        val responses = doc.getElementsByTagNameNS(DAV, "response")
        val out = ArrayList<DavEntry>(responses.length)
        for (i in 0 until responses.length) {
            val response = responses.item(i) as Element
            val href = response.firstText(DAV, "href") ?: continue
            val decoded = percentDecode(href).trimEnd('/')
            if (!decoded.startsWith(rootHref)) continue
            val rel = decoded.removePrefix(rootHref).trimStart('/')

            // Only the propstat with a 200 status carries real values.
            val propstats = response.getElementsByTagNameNS(DAV, "propstat")
            var prop: Element? = null
            for (j in 0 until propstats.length) {
                val ps = propstats.item(j) as Element
                val status = ps.firstText(DAV, "status") ?: ""
                if (status.contains(" 200")) prop = ps.firstChild(DAV, "prop")
            }
            if (prop == null) continue

            val isDir = prop.firstChild(DAV, "resourcetype")?.firstChild(DAV, "collection") != null
            out += DavEntry(
                path = rel,
                isDir = isDir,
                etag = prop.firstText(DAV, "getetag")?.let(::normalizeEtag),
                size = prop.firstText(DAV, "getcontentlength")?.toLongOrNull() ?: 0L,
                modified = prop.firstText(DAV, "getlastmodified")?.let(::parseHttpDate) ?: 0L,
                contentType = prop.firstText(DAV, "getcontenttype"),
            )
        }
        return out
    }

    /** ETags are compared as opaque strings; strip quotes and weak markers so server and header forms agree. */
    fun normalizeEtag(raw: String): String = raw.trim().removePrefix("W/").trim('"')

    private val httpDate = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss zzz", Locale.US)

    /** Lenient RFC 1123: the weekday name is ignored rather than validated. */
    fun parseHttpDate(s: String): Long = runCatching {
        val body = s.trim().substringAfter(", ", s.trim())
        ZonedDateTime.parse(body, httpDate).toInstant().toEpochMilli()
    }.getOrDefault(0L)

    /** Percent-decoding that leaves `+` alone (unlike URLDecoder). */
    fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val bytes = java.io.ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    bytes.write(hex); i += 3; continue
                }
            }
            val enc = c.toString().toByteArray()
            bytes.write(enc, 0, enc.size); i++
        }
        return bytes.toString("UTF-8")
    }

    private fun Element.firstChild(ns: String, local: String): Element? {
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n is Element && n.localName == local && n.namespaceURI == ns) return n
        }
        return null
    }

    private fun Element.firstText(ns: String, local: String): String? {
        val list = getElementsByTagNameNS(ns, local)
        if (list.length == 0) return null
        return list.item(0).textContent
    }
}
