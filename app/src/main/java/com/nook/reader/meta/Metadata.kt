package com.nook.reader.meta

import com.nook.reader.ai.Net
import com.nook.reader.util.saveCoverBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

data class Candidate(
    val source: String,
    val title: String,
    val authors: String,
    val description: String,
    val language: String,
    val coverUrl: String,
    val year: String,
)

/**
 * Pencarian metadata dan cover dari Google Books, Open Library, iTunes Store, dan Pencarian Web (DuckDuckGo).
 */
object MetadataFetcher {

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun fetch(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        Net.client.newCall(req).execute().use { r ->
            return if (r.isSuccessful) r.body?.string() else null
        }
    }

    suspend fun search(title: String, author: String): List<Candidate> = withContext(Dispatchers.IO) {
        val out = mutableListOf<Candidate>()
        try {
            out += googleBooks(title, author)
        } catch (_: Exception) {
        }
        try {
            out += itunesBooks(title, author)
        } catch (_: Exception) {
        }
        try {
            out += openLibrary(title, author)
        } catch (_: Exception) {
        }

        // Jika belum ada hasil ber-cover, cari cover langsung dari Web Search (DuckDuckGo Image / Open Web)
        if (out.none { it.coverUrl.isNotBlank() }) {
            try {
                val webCover = searchWebCover(title, author)
                if (webCover.isNotBlank() && out.isNotEmpty()) {
                    val first = out[0]
                    out[0] = first.copy(coverUrl = webCover)
                } else if (webCover.isNotBlank()) {
                    out.add(
                        Candidate(
                            source = "Google / Web Search",
                            title = title,
                            authors = author,
                            description = "Hasil pencarian web untuk $title",
                            language = "",
                            coverUrl = webCover,
                            year = "",
                        )
                    )
                }
            } catch (_: Exception) {
            }
        }
        out
    }

    private fun googleBooks(title: String, author: String): List<Candidate> {
        val q = buildString {
            append("intitle:").append(title)
            if (author.isNotBlank()) append(" inauthor:").append(author)
        }
        val text = fetch("https://www.googleapis.com/books/v1/volumes?q=${enc(q)}&maxResults=6&printType=books")
            ?: return emptyList()
        val items = JSONObject(text).optJSONArray("items") ?: return emptyList()
        val list = mutableListOf<Candidate>()
        for (i in 0 until items.length()) {
            val info = items.getJSONObject(i).optJSONObject("volumeInfo") ?: continue
            val authors = info.optJSONArray("authors")?.let { a ->
                (0 until a.length()).joinToString(", ") { a.getString(it) }
            }.orEmpty()
            val thumb = info.optJSONObject("imageLinks")?.let {
                it.optString("thumbnail").ifBlank { it.optString("smallThumbnail") }
            }.orEmpty()
            val t = info.optString("title") + info.optString("subtitle").let { if (it.isBlank()) "" else ": $it" }
            list.add(
                Candidate(
                    source = "Google Books",
                    title = t,
                    authors = authors,
                    description = info.optString("description"),
                    language = info.optString("language"),
                    coverUrl = thumb.replace("http://", "https://").replace("&edge=curl", ""),
                    year = info.optString("publishedDate").take(4),
                ),
            )
        }
        return list
    }

    private fun itunesBooks(title: String, author: String): List<Candidate> {
        val q = "$title $author".trim()
        val text = fetch("https://itunes.apple.com/search?media=ebook&entity=ebook&term=${enc(q)}&limit=5")
            ?: return emptyList()
        val results = JSONObject(text).optJSONArray("results") ?: return emptyList()
        val list = mutableListOf<Candidate>()
        for (i in 0 until results.length()) {
            val item = results.getJSONObject(i)
            val t = item.optString("trackName")
            val a = item.optString("artistName")
            val rawCover = item.optString("artworkUrl100")
            val highResCover = rawCover.replace("100x100bb", "600x600bb").replace("100x100", "600x600")
            list.add(
                Candidate(
                    source = "Apple / iTunes Store",
                    title = t,
                    authors = a,
                    description = item.optString("description"),
                    language = "",
                    coverUrl = highResCover,
                    year = item.optString("releaseDate").take(4),
                )
            )
        }
        return list
    }

    private fun openLibrary(title: String, author: String): List<Candidate> {
        var url = "https://openlibrary.org/search.json?title=${enc(title)}&limit=5" +
            "&fields=title,author_name,cover_i,first_publish_year,language"
        if (author.isNotBlank()) url += "&author=${enc(author)}"
        val text = fetch(url) ?: return emptyList()
        val docs = JSONObject(text).optJSONArray("docs") ?: return emptyList()
        val list = mutableListOf<Candidate>()
        for (i in 0 until docs.length()) {
            val d = docs.getJSONObject(i)
            val authors = d.optJSONArray("author_name")?.let { a ->
                (0 until a.length()).joinToString(", ") { a.getString(it) }
            }.orEmpty()
            val coverId = d.optLong("cover_i", 0)
            list.add(
                Candidate(
                    source = "Open Library",
                    title = d.optString("title"),
                    authors = authors,
                    description = "",
                    language = "",
                    coverUrl = if (coverId > 0) "https://covers.openlibrary.org/b/id/$coverId-L.jpg" else "",
                    year = d.optInt("first_publish_year", 0).let { if (it > 0) it.toString() else "" },
                ),
            )
        }
        return list
    }

    /**
     * Pencarian cover dari Web Images (DuckDuckGo / Open Web).
     */
    private fun searchWebCover(title: String, author: String): String {
        val q = "$title $author book cover".trim()
        val html = fetch("https://html.duckduckgo.com/html/?q=${enc(q)}") ?: return ""
        val regex = Regex("i\\.duckduckgo\\.com/iu/\\?u=([^&\"']+)")
        val match = regex.find(html)
        if (match != null) {
            val rawUrl = java.net.URLDecoder.decode(match.groupValues[1], "UTF-8")
            if (rawUrl.startsWith("http")) return rawUrl
        }
        return ""
    }

    /** Unduh gambar cover. Gambar placeholder yang terlalu kecil ditolak. */
    suspend fun downloadCover(url: String, dest: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
            Net.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext false
                val bytes = r.body?.bytes() ?: return@withContext false
                if (bytes.size < 3000) return@withContext false
                saveCoverBytes(bytes, dest)
            }
        } catch (_: Exception) {
            false
        }
    }
}
