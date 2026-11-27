package com.nook.reader.book

import android.webkit.MimeTypeMap
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.Closeable
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

data class TocEntry(val title: String, val spine: Int, val anchor: String?, val level: Int)

data class SearchHit(val chapter: Int, val snippet: String)

class EpubBook private constructor(private val zip: ZipFile) : Closeable {

    var title = ""
    var author = ""
    var language = ""
    var description = ""

    /** Path file XHTML dalam zip, berurutan sesuai spine. */
    val spine = mutableListOf<String>()
    val toc = mutableListOf<TocEntry>()

    private val mimeByPath = mutableMapOf<String, String>()
    private var coverPath: String? = null
    private val entryIndex = HashMap<String, String>()

    /** Ukuran tiap bab (byte tak terkompresi), dipakai untuk menghitung persen progress. */
    var sizes: LongArray = LongArray(0)
        private set
    var totalSize: Long = 1
        private set

    init {
        val en = zip.entries()
        while (en.hasMoreElements()) {
            val e = en.nextElement()
            entryIndex[e.name.lowercase()] = e.name
        }
    }

    private fun entryName(path: String): String? =
        if (zip.getEntry(path) != null) path else entryIndex[path.lowercase()]

    fun readBytes(path: String): ByteArray? {
        val name = entryName(path) ?: return null
        val entry = zip.getEntry(name) ?: return null
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    private fun readText(path: String): String? = readBytes(path)?.toString(Charsets.UTF_8)

    fun mimeOf(path: String): String {
        mimeByPath[path]?.let { return it }
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "xhtml", "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js" -> "application/javascript"
            "svg" -> "image/svg+xml"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    fun spineIndexOf(path: String): Int {
        val i = spine.indexOf(path)
        if (i >= 0) return i
        return spine.indexOfFirst { it.equals(path, ignoreCase = true) }
    }

    fun coverBytes(): ByteArray? = coverPath?.let { readBytes(it) }

    fun chapterHtml(index: Int): String? = spine.getOrNull(index)?.let { readText(it) }

    fun chapterText(index: Int): String {
        val html = chapterHtml(index) ?: return ""
        return try {
            Jsoup.parse(html).body().text()
        } catch (e: Exception) {
            ""
        }
    }

    fun search(query: String, limit: Int = 200): List<SearchHit> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val hits = mutableListOf<SearchHit>()
        for (i in spine.indices) {
            val text = chapterText(i)
            var from = 0
            while (hits.size < limit) {
                val at = text.indexOf(q, from, ignoreCase = true)
                if (at < 0) break
                val a = (at - 60).coerceAtLeast(0)
                val b = (at + q.length + 60).coerceAtMost(text.length)
                hits.add(SearchHit(i, text.substring(a, b).trim()))
                from = at + q.length
            }
            if (hits.size >= limit) break
        }
        return hits
    }

    override fun close() {
        try {
            zip.close()
        } catch (e: Exception) {
        }
    }

    companion object {
        fun open(file: File): EpubBook? {
            return try {
                val zip = ZipFile(file)
                val book = EpubBook(zip)
                if (book.parse()) book else {
                    zip.close()
                    null
                }
            } catch (e: Exception) {
                null
            }
        }

        private fun resolve(base: String, href: String): String {
            val clean = href.substringBefore('#')
            val decoded = try {
                URLDecoder.decode(clean.replace("+", "%2B"), "UTF-8")
            } catch (e: Exception) {
                clean
            }
            val full = when {
                decoded.startsWith("/") -> decoded.removePrefix("/")
                base.isEmpty() -> decoded
                else -> "$base/$decoded"
            }
            val parts = mutableListOf<String>()
            for (p in full.split('/')) {
                when (p) {
                    "", "." -> {}
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                    else -> parts.add(p)
                }
            }
            return parts.joinToString("/")
        }
    }

    private fun parse(): Boolean {
        val container = readText("META-INF/container.xml") ?: return false
        val cdoc = Jsoup.parse(container, "", Parser.xmlParser())
        val opfPath = cdoc.select("rootfile").first()?.attr("full-path") ?: return false
        val opfText = readText(opfPath) ?: return false
        val opf = Jsoup.parse(opfText, "", Parser.xmlParser())
        val dir = opfPath.substringBeforeLast('/', "")

        title = opf.getElementsByTag("dc:title").first()?.text()?.trim().orEmpty()
        author = opf.getElementsByTag("dc:creator").joinToString(", ") { it.text().trim() }
        language = opf.getElementsByTag("dc:language").first()?.text()?.trim().orEmpty()
        description = opf.getElementsByTag("dc:description").first()?.text()?.let {
            try {
                Jsoup.parse(it).text()
            } catch (e: Exception) {
                it
            }
        }?.trim().orEmpty()

        // manifest
        val hrefById = HashMap<String, String>()
        var navPath: String? = null
        var coverFromProps: String? = null
        for (item in opf.select("manifest > item")) {
            val id = item.attr("id")
            val path = resolve(dir, item.attr("href"))
            hrefById[id] = path
            mimeByPath[path] = item.attr("media-type")
            val props = item.attr("properties")
            if (props.contains("nav")) navPath = path
            if (props.contains("cover-image")) coverFromProps = path
        }

        // spine
        for (ref in opf.select("spine > itemref")) {
            val path = hrefById[ref.attr("idref")] ?: continue
            val mt = mimeByPath[path].orEmpty()
            if (mt.isEmpty() || mt.contains("html") || mt.contains("xml")) spine.add(path)
        }
        if (spine.isEmpty()) return false

        // cover
        coverPath = coverFromProps
            ?: opf.select("metadata > meta[name=cover]").first()?.attr("content")?.let { hrefById[it] }
            ?: hrefById.entries.firstOrNull { (id, p) ->
                id.contains("cover", ignoreCase = true) && (mimeByPath[p].orEmpty().startsWith("image/"))
            }?.value

        // ukuran bab
        sizes = LongArray(spine.size) { i ->
            val n = entryName(spine[i])
            val sz = if (n != null) zip.getEntry(n)?.size ?: 1L else 1L
            if (sz <= 0) 1L else sz
        }
        totalSize = sizes.sum().coerceAtLeast(1)

        // daftar isi
        try {
            if (navPath != null) parseNav(navPath)
            if (toc.isEmpty()) {
                val ncxId = opf.select("spine").first()?.attr("toc").orEmpty()
                val ncxPath = hrefById[ncxId]
                    ?: hrefById.values.firstOrNull { it.endsWith(".ncx", ignoreCase = true) }
                if (ncxPath != null) parseNcx(ncxPath)
            }
        } catch (e: Exception) {
            toc.clear()
        }
        if (toc.isEmpty()) {
            for (i in spine.indices) toc.add(TocEntry("Bagian ${i + 1}", i, null, 0))
        }
        return true
    }

    private fun addToc(title: String, href: String, base: String, level: Int) {
        val path = resolve(base, href)
        val idx = spineIndexOf(path)
        if (idx < 0) return
        val anchor = if (href.contains('#')) href.substringAfter('#').ifBlank { null } else null
        toc.add(TocEntry(title.ifBlank { "Bagian ${idx + 1}" }, idx, anchor, level))
    }

    private fun parseNav(path: String) {
        val text = readText(path) ?: return
        val base = path.substringBeforeLast('/', "")
        val doc = Jsoup.parse(text)
        val nav = doc.select("nav").firstOrNull { it.attr("epub:type").contains("toc") }
            ?: doc.select("nav").firstOrNull() ?: return
        val ol = nav.select("ol").first() ?: return
        walkNav(ol, base, 0)
    }

    private fun walkNav(ol: Element, base: String, level: Int) {
        for (li in ol.children()) {
            if (li.tagName() != "li") continue
            val a = li.children().firstOrNull { it.tagName() == "a" }
            if (a != null) addToc(a.text().trim(), a.attr("href"), base, level)
            val sub = li.children().firstOrNull { it.tagName() == "ol" }
            if (sub != null) walkNav(sub, base, level + 1)
        }
    }

    private fun parseNcx(path: String) {
        val text = readText(path) ?: return
        val base = path.substringBeforeLast('/', "")
        val doc = Jsoup.parse(text, "", Parser.xmlParser())
        val map = doc.select("navMap").first() ?: return
        walkNcx(map, base, 0)
    }

    private fun walkNcx(parent: Element, base: String, level: Int) {
        for (np in parent.children()) {
            if (np.tagName() != "navPoint") continue
            val label = np.select("> navLabel > text").first()?.text()?.trim().orEmpty()
            val src = np.select("> content").first()?.attr("src").orEmpty()
            if (src.isNotEmpty()) addToc(label, src, base, level)
            walkNcx(np, base, level + 1)
        }
    }
}
