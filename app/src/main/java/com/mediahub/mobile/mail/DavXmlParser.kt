package com.mediahub.mobile.mail

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLEncoder
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

data class DavEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long?
)

/** Prevent sending requests to hosts supplied by untrusted WebDAV XML. */
object DavPath {
    const val BASE_URL = "https://webdav.cloud.mail.ru"

    fun normalize(path: String, directory: Boolean = true): String {
        val uri = URI(path)
        require(!uri.isAbsolute || (uri.scheme == "https" &&
            uri.host.equals("webdav.cloud.mail.ru", ignoreCase = true))) {
            "Недопустимый адрес WebDAV."
        }
        val raw = uri.rawPath.orEmpty()
        require(raw.startsWith("/") || raw.isEmpty()) { "Недопустимый путь." }
        require(!raw.split('/').any { it == ".." || it == "." || it.equals("%2e%2e", true) }) {
            "Недопустимый путь."
        }
        val safe = if (raw.isEmpty()) "/" else raw
        return if (directory) safe.trimEnd('/') + "/" else safe.trimEnd('/')
    }

    fun child(parent: String, name: String, isDirectory: Boolean): String {
        require(name.isNotBlank() && name != "." && name != ".." && !name.contains('/') &&
            !name.contains('\\')) { "Недопустимое имя файла." }
        val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        return normalize(parent) + encoded + if (isDirectory) "/" else ""
    }

    fun parent(path: String): String {
        val trimmed = normalize(path).trimEnd('/')
        if (trimmed.isEmpty()) return "/"
        val result = trimmed.substringBeforeLast('/', "")
        return if (result.isEmpty()) "/" else "$result/"
    }

    fun name(path: String): String = URI(BASE_URL + normalize(path)).path
        .trimEnd('/').substringAfterLast('/').ifBlank { "Корень" }
}

/**
 * Only parse the DAV standard namespace. External entities and DTDs are disabled
 * to prevent XML external entity requests from untrusted servers.
 */
object DavXmlParser {
    fun parse(xml: String, currentPath: String): List<DavEntry> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            isXIncludeAware = false
            setExpandEntityReferences(false)
        }
        val doc = factory.newDocumentBuilder().parse(
            ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))
        )
        val result = ArrayList<DavEntry>()
        val current = DavPath.normalize(currentPath)
        val responses = doc.getElementsByTagNameNS("DAV:", "response")
        for (i in 0 until responses.length) {
            val response = responses.item(i) as? Element ?: continue
            val href = response.getElementsByTagNameNS("DAV:", "href")
                .item(0)?.textContent?.trim().orEmpty()
            if (href.isEmpty()) continue
            val isDirectory = response.getElementsByTagNameNS("DAV:", "collection").length > 0
            val path = runCatching { DavPath.normalize(href, isDirectory) }.getOrNull() ?: continue
            if (DavPath.normalize(path) == current) continue
            val name = response.getElementsByTagNameNS("DAV:", "displayname")
                .item(0)?.textContent?.trim().orEmpty()
                .ifBlank { DavPath.name(path) }
            val length = response.getElementsByTagNameNS("DAV:", "getcontentlength")
                .item(0)?.textContent?.trim()?.toLongOrNull()
            result.add(DavEntry(path, name, isDirectory, if (isDirectory) null else length))
        }
        return result.distinctBy { it.path }
            .sortedWith(compareBy<DavEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
    }
}
