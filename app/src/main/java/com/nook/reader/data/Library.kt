package com.nook.reader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.nook.reader.book.EpubBook
import com.nook.reader.book.PdfBook
import com.nook.reader.util.cleanTitle
import com.nook.reader.util.now
import com.nook.reader.util.saveCoverBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

sealed class ImportResult {
    data class Added(val book: Book) : ImportResult()
    data class Exists(val book: Book) : ImportResult()
    data class Failed(val reason: String) : ImportResult()
}

class Library(private val ctx: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val booksDir = File(ctx.filesDir, "books").apply { mkdirs() }
    val coversDir = File(ctx.filesDir, "covers").apply { mkdirs() }
    private val stateFile = File(ctx.filesDir, "library.json")

    private val _books = MutableStateFlow<List<Book>>(emptyList())
    val books: StateFlow<List<Book>> = _books

    val localRev = MutableStateFlow(0)
    fun notifyLocalChanged() {
        localRev.value = localRev.value + 1
    }

    private val lock = Any()

    init {
        load()
    }

    private fun load() {
        try {
            if (stateFile.exists()) {
                _books.value = json.decodeFromString(LibraryState.serializer(), stateFile.readText()).books
            }
        } catch (e: Exception) {
            _books.value = emptyList()
        }
    }

    private fun persist() {
        try {
            val tmp = File(stateFile.parentFile, "library.json.tmp")
            tmp.writeText(json.encodeToString(LibraryState.serializer(), LibraryState(books = _books.value)))
            tmp.renameTo(stateFile)
        } catch (e: Exception) {
        }
    }

    fun fileOf(b: Book): File = File(booksDir, "${b.id}.${b.format}")
    fun coverOf(b: Book): File = File(coversDir, "${b.id}.jpg")
    fun isLocal(b: Book): Boolean = fileOf(b).exists()
    fun get(id: String): Book? = _books.value.firstOrNull { it.id == id }

    fun update(id: String, change: (Book) -> Book) {
        synchronized(lock) {
            _books.update { list -> list.map { if (it.id == id) change(it) else it } }
            persist()
        }
    }

    fun upsert(book: Book) {
        synchronized(lock) {
            _books.update { list ->
                if (list.any { it.id == book.id }) list.map { if (it.id == book.id) book else it } else list + book
            }
            persist()
        }
    }

    fun exportState(): String = json.encodeToString(LibraryState.serializer(), LibraryState(books = _books.value))

    fun parseState(text: String): List<Book> = json.decodeFromString(LibraryState.serializer(), text).books

    /** Gabungkan data dari Drive ke data lokal. Mengembalikan daftar hasil gabungan. */
    fun merge(remote: List<Book>): List<Book> {
        synchronized(lock) {
            val map = LinkedHashMap<String, Book>()
            for (b in _books.value) map[b.id] = b
            for (r in remote) {
                val merged = mergeBook(map[r.id], r)
                if (merged != null) map[r.id] = merged
            }
            _books.value = map.values.toList()
            persist()
            return _books.value
        }
    }

    private fun mergeBook(a: Book?, b: Book?): Book? {
        if (a == null) return b
        if (b == null) return a
        val meta = if (a.updatedAt >= b.updatedAt) a else b
        val prog = if (a.progressUpdatedAt >= b.progressUpdatedAt) a else b
        return meta.copy(
            chapter = prog.chapter,
            fraction = prog.fraction,
            percent = prog.percent,
            progressUpdatedAt = prog.progressUpdatedAt,
            highlights = mergeById(a.highlights, b.highlights, { it.id }, { it.updatedAt }),
            bookmarks = mergeById(a.bookmarks, b.bookmarks, { it.id }, { it.updatedAt }),
            remoteFileId = a.remoteFileId.ifBlank { b.remoteFileId },
            addedAt = minOf(a.addedAt, b.addedAt).let { if (it == 0L) maxOf(a.addedAt, b.addedAt) else it },
        )
    }

    private fun <T> mergeById(x: List<T>, y: List<T>, id: (T) -> String, ts: (T) -> Long): List<T> {
        val m = LinkedHashMap<String, T>()
        for (i in x + y) {
            val k = id(i)
            val cur = m[k]
            if (cur == null || ts(i) > ts(cur)) m[k] = i
        }
        return m.values.toList()
    }

    fun deleteFiles(b: Book) {
        try {
            fileOf(b).delete()
            coverOf(b).delete()
        } catch (e: Exception) {
        }
    }

    private fun displayName(uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    suspend fun importUri(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        try {
            val name = displayName(uri) ?: uri.lastPathSegment ?: "buku"
            val ext = name.substringAfterLast('.', "").lowercase()
            val mime = ctx.contentResolver.getType(uri).orEmpty()
            val format = when {
                ext == "epub" || mime == "application/epub+zip" -> "epub"
                ext == "pdf" || mime == "application/pdf" -> "pdf"
                else -> return@withContext ImportResult.Failed("Format tidak didukung: $name")
            }

            val tmp = File(ctx.cacheDir, "import_${System.nanoTime()}")
            val md = MessageDigest.getInstance("SHA-256")
            val input = ctx.contentResolver.openInputStream(uri)
                ?: return@withContext ImportResult.Failed("Tidak bisa membuka $name")
            input.use { ins ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                    }
                }
            }
            val id = md.digest().joinToString("") { "%02x".format(it) }.take(20)
            val dest = File(booksDir, "$id.$format")
            val size = tmp.length()
            if (!dest.exists()) {
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
            } else {
                tmp.delete()
            }

            val existing = get(id)
            if (existing != null && !existing.deleted) {
                ensureCover(existing)
                return@withContext ImportResult.Exists(existing)
            }

            var title = cleanTitle(name)
            var author = ""
            var desc = ""
            var lang = ""
            val coverFile = File(coversDir, "$id.jpg")
            if (format == "epub") {
                val e = EpubBook.open(dest)
                if (e != null) {
                    try {
                        if (e.title.isNotBlank()) title = e.title
                        author = e.author
                        desc = e.description
                        lang = e.language
                        e.coverBytes()?.let { saveCoverBytes(it, coverFile) }
                    } finally {
                        e.close()
                    }
                }
            } else {
                val (t, a) = PdfBook.readInfo(ctx, dest)
                if (t.isNotBlank()) title = t
                author = a
                PdfBook.renderCover(dest, coverFile)
            }

            val t = now()
            val book = Book(
                id = id,
                fileName = name,
                format = format,
                title = title,
                author = author,
                description = desc,
                language = lang,
                sizeBytes = size,
                addedAt = t,
                updatedAt = t,
                progressUpdatedAt = t,
            )
            upsert(book)
            ImportResult.Added(book)
        } catch (e: Exception) {
            ImportResult.Failed(e.message ?: "Gagal mengimpor")
        }
    }

    /** Buat file cover jika belum ada (misalnya setelah buku diunduh dari Drive). */
    suspend fun ensureCover(b: Book) = withContext(Dispatchers.IO) {
        val cover = coverOf(b)
        val file = fileOf(b)
        if (cover.exists() || !file.exists()) return@withContext
        try {
            if (b.format == "epub") {
                val e = EpubBook.open(file)
                if (e != null) {
                    try {
                        e.coverBytes()?.let { saveCoverBytes(it, cover) }
                    } finally {
                        e.close()
                    }
                }
            } else {
                PdfBook.renderCover(file, cover)
            }
        } catch (e: Exception) {
        }
    }
}
