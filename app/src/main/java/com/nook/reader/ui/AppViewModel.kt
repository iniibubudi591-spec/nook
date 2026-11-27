package com.nook.reader.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.nook.reader.ai.AiService
import com.nook.reader.ai.ChatMsg
import com.nook.reader.data.AiTask
import com.nook.reader.data.AppSettings
import com.nook.reader.data.Bookmark
import com.nook.reader.data.Highlight
import com.nook.reader.data.Library
import com.nook.reader.meta.MetadataFetcher
import com.nook.reader.sync.DRIVE_SCOPE
import com.nook.reader.sync.SyncManager
import com.nook.reader.util.now
import com.nook.reader.util.signingSha1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Screen {
    data object Library : Screen
    data class Reader(val bookId: String) : Screen
    data object Chat : Screen
    data object Settings : Screen
}

data class SyncUi(
    val email: String? = null,
    val running: Boolean = false,
    val status: String = "",
    val lastSync: Long = 0,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx = app.applicationContext
    val library = Library(ctx)
    val settings = AppSettings(ctx)
    val ai = AiService(settings)
    private val syncManager = SyncManager(ctx, library)
    private val syncMutex = Mutex()

    val books = library.books
    val localRev = library.localRev

    val appTheme = MutableStateFlow(settings.appTheme())
    fun setAppTheme(theme: String) {
        settings.setAppTheme(theme)
        appTheme.value = theme
    }

    private val _screen = MutableStateFlow<Screen>(Screen.Library)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    val chatMessages = mutableStateListOf<ChatMsg>()
    var chatLoading = MutableStateFlow(false)

    private val _sync = MutableStateFlow(SyncUi(email = GoogleSignIn.getLastSignedInAccount(ctx)?.email))
    val syncUi = _sync.asStateFlow()

    init {
        if (_sync.value.email != null) syncNow(silent = true)
    }

    fun toast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }
    }

    fun back() {
        val wasReader = _screen.value is Screen.Reader
        _screen.value = Screen.Library
        if (wasReader) syncNow(silent = true)
    }

    fun open(bookId: String) {
        val b = library.get(bookId)
        if (b == null || b.deleted) return
        if (!library.isLocal(b)) {
            if (b.remoteFileId.isBlank()) {
                toast("File buku ini belum ada di Drive. Sinkronkan dulu dari perangkat asalnya.")
                return
            }
            viewModelScope.launch {
                _busy.value = "Mengunduh ${b.title}"
                try {
                    withContext(Dispatchers.IO) { syncManager.download(b.remoteFileId, library.fileOf(b)) }
                    library.ensureCover(b)
                    library.notifyLocalChanged()
                    _screen.value = Screen.Reader(bookId)
                } catch (e: Exception) {
                    toast("Gagal mengunduh: ${e.message}")
                } finally {
                    _busy.value = null
                }
            }
        } else {
            viewModelScope.launch {
                library.ensureCover(b)
                _screen.value = Screen.Reader(bookId)
            }
        }
    }

    fun goChat() {
        _screen.value = Screen.Chat
    }

    fun goSettings() {
        _screen.value = Screen.Settings
    }

    fun importAndOpen(uri: Uri) {
        viewModelScope.launch {
            _busy.value = "Mengimpor buku..."
            val res = withContext(Dispatchers.IO) { library.importUri(uri) }
            _busy.value = null
            when (res) {
                is com.nook.reader.data.ImportResult.Added -> open(res.book.id)
                is com.nook.reader.data.ImportResult.Exists -> open(res.book.id)
                is com.nook.reader.data.ImportResult.Failed -> toast("Gagal mengimpor: ${res.reason}")
            }
        }
    }

    fun onAppStop() {
        syncNow(silent = true)
    }

    fun importBooks(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _busy.value = "Mengimpor buku..."
            val added = withContext(Dispatchers.IO) {
                var count = 0
                for (u in uris) {
                    val res = library.importUri(u)
                    if (res is com.nook.reader.data.ImportResult.Added) count++
                }
                count
            }
            _busy.value = null
            if (added > 0) {
                toast("$added buku berhasil ditambahkan.")
                if (settings.autoMeta() && ai.configured(AiTask.METADATA)) {
                    enrichMetadata(onlyEmpty = true)
                }
                syncNow(silent = true)
            } else {
                toast("Tidak ada buku baru yang bisa diimpor.")
            }
        }
    }

    fun saveProgress(bookId: String, chapter: Int, fraction: Float, percent: Float) {
        library.update(bookId) {
            it.copy(
                chapter = chapter,
                fraction = fraction,
                percent = percent,
                progressUpdatedAt = now(),
                updatedAt = now(),
            )
        }
        syncNow(silent = true)
    }

    fun addBookmark(bookId: String, bookmark: Bookmark) {
        library.update(bookId) {
            val list = it.bookmarks.filter { b -> b.id != bookmark.id } + bookmark
            it.copy(bookmarks = list, updatedAt = now())
        }
        syncNow(silent = true)
    }

    fun removeBookmark(bookId: String, bookmarkId: String) {
        library.update(bookId) {
            val list = it.bookmarks.map { b ->
                if (b.id == bookmarkId) b.copy(deleted = true, updatedAt = now()) else b
            }
            it.copy(bookmarks = list, updatedAt = now())
        }
        syncNow(silent = true)
    }

    fun addHighlight(bookId: String, highlight: Highlight) {
        library.update(bookId) {
            val list = it.highlights.filter { h -> h.id != highlight.id } + highlight
            it.copy(highlights = list, updatedAt = now())
        }
        syncNow(silent = true)
    }

    fun changeHighlight(bookId: String, highlightId: String, color: String, note: String) {
        library.update(bookId) {
            val list = it.highlights.map { h ->
                if (h.id == highlightId) h.copy(color = color, note = note, updatedAt = now()) else h
            }
            it.copy(highlights = list, updatedAt = now())
        }
        syncNow(silent = true)
    }

    fun removeHighlight(bookId: String, highlightId: String) {
        library.update(bookId) {
            val list = it.highlights.map { h ->
                if (h.id == highlightId) h.copy(deleted = true, updatedAt = now()) else h
            }
            it.copy(highlights = list, updatedAt = now())
        }
        syncNow(silent = true)
    }

    fun updateMeta(id: String, title: String, author: String, category: String, description: String) {
        library.update(id) {
            it.copy(
                title = title.trim(),
                author = author.trim(),
                category = category.trim(),
                description = description.trim(),
                updatedAt = now(),
            )
        }
        syncNow(silent = true)
    }

    fun updateCover(id: String, file: File) {
        val b = library.get(id) ?: return
        val dest = library.coverOf(b)
        file.copyTo(dest, overwrite = true)
        library.update(id) { it.copy(updatedAt = now()) }
        library.notifyLocalChanged()
        syncNow(silent = true)
    }

    fun fetchMetaForBook(id: String) {
        val b = library.get(id) ?: return
        viewModelScope.launch {
            _busy.value = "Mencari metadata untuk ${b.title}"
            try {
                val cands = MetadataFetcher.search(b.title, b.author)
                if (cands.isEmpty()) {
                    toast("Tidak ada metadata ditemukan di Google Books atau Open Library.")
                    return@launch
                }
                var pick = 0
                if (ai.configured(AiTask.METADATA)) {
                    try {
                        pick = ai.pickCandidate(b.title, b.author, library.fileOf(b).name, cands)
                    } catch (e: Exception) {
                    }
                }
                val c = cands.getOrNull(pick) ?: cands.first()
                val needCover = !library.coverOf(b).exists() || b.format == "pdf"
                var coverOk = false
                if (needCover && c.coverUrl.isNotBlank()) {
                    coverOk = MetadataFetcher.downloadCover(c.coverUrl, library.coverOf(b))
                }
                library.update(id) {
                    it.copy(
                        title = c.title.ifBlank { it.title },
                        author = c.authors.ifBlank { it.author },
                        description = c.description.ifBlank { it.description },
                        language = c.language.ifBlank { it.language },
                        coverUrl = if (coverOk) c.coverUrl else it.coverUrl,
                        updatedAt = now(),
                    )
                }
                library.notifyLocalChanged()
                toast("Metadata diperbarui dari ${c.source}.")
            } catch (e: Exception) {
                toast("Gagal mencari metadata: ${e.message}")
            } finally {
                _busy.value = null
            }
        }
    }

    fun enrichMetadata(onlyEmpty: Boolean = false) {
        viewModelScope.launch {
            val all = library.books.value.filter { !it.deleted }
            val target = if (onlyEmpty) {
                all.filter { !it.deleted && (it.description.isBlank() || !library.coverOf(it).exists()) }
            } else {
                all
            }
            if (target.isEmpty()) {
                toast("Semua buku sudah punya metadata dan cover.")
                return@launch
            }
            var done = 0
            for ((i, b) in target.withIndex()) {
                _busy.value = "Mencari metadata ${i + 1}/${target.size}: ${b.title}"
                try {
                    val cands = MetadataFetcher.search(b.title, b.author)
                    if (cands.isNotEmpty()) {
                        var pick = 0
                        if (ai.configured(AiTask.METADATA)) {
                            try {
                                pick = ai.pickCandidate(b.title, b.author, library.fileOf(b).name, cands)
                            } catch (e: Exception) {
                            }
                        }
                        val c = cands.getOrNull(pick) ?: cands.first()
                        val needCover = !library.coverOf(b).exists() || b.format == "pdf"
                        var coverOk = false
                        if (needCover && c.coverUrl.isNotBlank()) {
                            coverOk = MetadataFetcher.downloadCover(c.coverUrl, library.coverOf(b))
                        }
                        library.update(b.id) {
                            it.copy(
                                title = c.title.ifBlank { it.title },
                                author = c.authors.ifBlank { it.author },
                                description = c.description.ifBlank { it.description },
                                language = c.language.ifBlank { it.language },
                                coverUrl = if (coverOk) c.coverUrl else it.coverUrl,
                                updatedAt = now(),
                            )
                        }
                        done++
                    }
                } catch (e: Exception) {
                }
            }
            _busy.value = null
            library.notifyLocalChanged()
            toast("Metadata & cover untuk $done buku diperbarui.")
            syncNow(silent = true)
        }
    }

    fun categorizeWithAi(onlyEmpty: Boolean = false) {
        viewModelScope.launch {
            if (!ai.configured(AiTask.CATEGORIZE)) {
                toast("Isi API key AI di Pengaturan dulu.")
                return@launch
            }
            val all = library.books.value.filter { !it.deleted }
            val target = if (onlyEmpty) all.filter { it.category.isBlank() } else all
            if (target.isEmpty()) {
                toast("Tidak ada buku yang perlu dikelompokkan.")
                return@launch
            }
            val known = all.map { it.category }.filter { it.isNotBlank() }.distinct().toMutableList()
            var done = 0
            try {
                for ((i, chunk) in target.chunked(20).withIndex()) {
                    _busy.value = "Mengelompokkan dengan AI (${i * 20 + chunk.size}/${target.size})"
                    val map = ai.categorizeChunk(chunk, known)
                    for ((id, cat) in map) {
                        library.update(id) { it.copy(category = cat, updatedAt = now()) }
                        if (cat !in known) known.add(cat)
                        done++
                    }
                }
                toast("$done buku berhasil dikelompokkan dengan AI.")
            } catch (e: Exception) {
                toast("Berhenti di tengah jalan: ${e.message}. $done buku sudah dikelompokkan.")
            } finally {
                _busy.value = null
            }
            syncNow(silent = true)
        }
    }

    fun categorizeByMetadata(onlyEmpty: Boolean = false) {
        viewModelScope.launch {
            val all = library.books.value.filter { !it.deleted }
            val target = if (onlyEmpty) all.filter { it.category.isBlank() } else all
            if (target.isEmpty()) {
                toast("Tidak ada buku yang perlu dikelompokkan.")
                return@launch
            }
            var done = 0
            val genreKeywords = mapOf(
                "Fiksi" to listOf("novel", "story", "fiksi", "fiction", "tales", "dongeng", "cerita", "roman"),
                "Bisnis & Keuangan" to listOf("business", "finance", "money", "invest", "marketing", "management", "ekonomi", "bisnis", "keuangan", "wealth"),
                "Teknologi & Komputer" to listOf("programming", "python", "java", "code", "software", "computer", "ai", "data", "teknologi", "pemrograman", "web"),
                "Pengembangan Diri" to listOf("habit", "atomic", "mindset", "success", "motivation", "self", "diri", "motivasi", "sukses", "habits"),
                "Sejarah & Biografi" to listOf("history", "biography", "autobiography", "sejarah", "biografi", "kisah nyata", "memoir"),
                "Sains & Matematika" to listOf("science", "physics", "math", "biology", "chemistry", "sains", "fisika", "matematika"),
                "Agama & Filsafat" to listOf("islam", "bible", "philosophy", "religion", "quran", "filsafat", "agama", "spiritual", "faith"),
            )

            for (b in target) {
                var assignedCat = ""
                val lowerTitle = b.title.lowercase()
                val lowerDesc = b.description.lowercase()

                for ((genre, keywords) in genreKeywords) {
                    if (keywords.any { lowerTitle.contains(it) || lowerDesc.contains(it) }) {
                        assignedCat = genre
                        break
                    }
                }
                if (assignedCat.isBlank() && b.author.isNotBlank() && b.author.length < 35 && !b.author.contains("unknown", true)) {
                    assignedCat = b.author
                }
                if (assignedCat.isBlank()) {
                    assignedCat = "Umum"
                }

                library.update(b.id) { it.copy(category = assignedCat, updatedAt = now()) }
                done++
            }
            toast("$done buku berhasil dikelompokkan berdasarkan metadata (Tanpa AI).")
            library.notifyLocalChanged()
            syncNow(silent = true)
        }
    }

    fun categorizeWithInstruction(instruction: String, onlyEmpty: Boolean = false) {
        val cmd = instruction.trim()
        if (cmd.isEmpty()) {
            toast("Tuliskan perintah pengelompokan dulu, misalnya: berdasarkan pengarang atau genre.")
            return
        }
        viewModelScope.launch {
            if (!ai.configured(AiTask.CATEGORIZE)) {
                toast("Isi API key AI di Pengaturan dulu.")
                return@launch
            }
            val all = library.books.value.filter { !it.deleted }
            val target = if (onlyEmpty) all.filter { it.category.isBlank() } else all
            if (target.isEmpty()) {
                toast("Tidak ada buku yang perlu dikelompokkan.")
                return@launch
            }
            val known = mutableListOf<String>()
            var done = 0
            try {
                for ((i, chunk) in target.chunked(20).withIndex()) {
                    _busy.value = "Mengelompokkan (${cmd}) ${i * 20 + chunk.size} dari ${target.size}"
                    val map = ai.categorizeByInstruction(chunk, cmd, known)
                    for ((id, cat) in map) {
                        library.update(id) { it.copy(category = cat, updatedAt = now()) }
                        if (cat !in known) known.add(cat)
                        done++
                    }
                }
                toast("$done buku dikelompokkan ($cmd).")
            } catch (e: Exception) {
                toast("Berhenti di tengah jalan: ${e.message}. $done buku sudah dikelompokkan.")
            } finally {
                _busy.value = null
            }
            syncNow(silent = true)
        }
    }

    fun deleteBookLocally(id: String) {
        val b = library.get(id) ?: return
        library.deleteFiles(b)
        library.notifyLocalChanged()
        toast("File lokal buku dihapus.")
    }

    fun deleteBookCompletely(id: String) {
        library.update(id) { it.copy(deleted = true, updatedAt = now()) }
        val b = library.get(id)
        if (b != null) library.deleteFiles(b)
        toast("Buku dihapus.")
        syncNow(silent = true)
    }

    fun removeDuplicates() {
        viewModelScope.launch {
            val all = library.books.value.filter { !it.deleted }
            val seen = mutableSetOf<String>()
            var removed = 0
            for (b in all) {
                val key = b.title.lowercase().trim() + "|" + b.author.lowercase().trim()
                if (seen.contains(key)) {
                    library.update(b.id) { it.copy(deleted = true, updatedAt = now()) }
                    library.deleteFiles(b)
                    removed++
                } else {
                    seen.add(key)
                }
            }
            toast(if (removed > 0) "$removed buku duplikat berhasil dibersihkan." else "Tidak ada buku duplikat ditemukan.")
            library.notifyLocalChanged()
            syncNow(silent = true)
        }
    }

    fun cleanupMissingFiles() {
        viewModelScope.launch {
            val all = library.books.value.filter { !it.deleted }
            var cleaned = 0
            for (b in all) {
                if (!library.isLocal(b) && b.remoteFileId.isBlank()) {
                    library.update(b.id) { it.copy(deleted = true, updatedAt = now()) }
                    cleaned++
                }
            }
            toast(if (cleaned > 0) "$cleaned referensi file hilang dibersihkan." else "Semua file lengkap.")
            library.notifyLocalChanged()
            syncNow(silent = true)
        }
    }

    fun moveBook(bookId: String, delta: Int) {
        val all = library.books.value.filter { !it.deleted }
        val currentOrder = settings.getManualOrder().let { saved ->
            if (saved.isEmpty()) all.map { it.id } else saved
        }.toMutableList()

        val idx = currentOrder.indexOf(bookId)
        if (idx < 0) return
        val targetIdx = (idx + delta).coerceIn(0, currentOrder.lastIndex)
        if (idx == targetIdx) return

        currentOrder.removeAt(idx)
        currentOrder.add(targetIdx, bookId)
        settings.setManualOrder(currentOrder)
        library.notifyLocalChanged()
    }

    // Google Drive Sync
    fun signInIntent(): Intent {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DRIVE_SCOPE))
            .build()
        return GoogleSignIn.getClient(ctx, gso).signInIntent
    }

    fun onSignInResult(data: Intent?) {
        try {
            val acct = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
            _sync.value = _sync.value.copy(email = acct.email)
            toast("Login Google berhasil: ${acct.email}")
            syncNow(silent = false)
        } catch (e: ApiException) {
            val msg = "Gagal login Google (code ${e.statusCode})." +
                if (e.statusCode == 10) " Kode 10 berarti package name atau SHA-1 belum cocok dengan OAuth client di Google Cloud." else ""
            toast(msg)
        }
    }

    fun signOut() {
        viewModelScope.launch {
            try {
                val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
                GoogleSignIn.getClient(ctx, gso).signOut()
            } catch (e: Exception) {
            }
            _sync.value = SyncUi()
            toast("Logout dari Google.")
        }
    }

    fun syncNow(silent: Boolean) {
        if (_sync.value.email == null) {
            if (!silent) toast("Login Google dulu di Pengaturan.")
            return
        }
        viewModelScope.launch {
            if (!syncMutex.tryLock()) return@launch
            try {
                _sync.value = _sync.value.copy(running = true, status = "Memulai")
                val result = withContext(Dispatchers.IO) {
                    syncManager.sync { s -> _sync.value = _sync.value.copy(status = s) }
                }
                _sync.value = _sync.value.copy(running = false, status = result, lastSync = now())
                for (b in library.books.value.filter { !it.deleted }) library.ensureCover(b)
                library.notifyLocalChanged()
            } catch (e: Exception) {
                val msg = e.message ?: "Error"
                _sync.value = _sync.value.copy(running = false, status = "Gagal: $msg")
                if (!silent) toast("Gagal sinkron: $msg")
            } finally {
                syncMutex.unlock()
            }
        }
    }

    fun sendChat(text: String) {
        if (text.isBlank() || chatLoading.value) return
        chatMessages.add(ChatMsg("user", text))
        chatLoading.value = true
        viewModelScope.launch {
            try {
                if (!ai.configured(AiTask.CHAT)) {
                    chatMessages.add(ChatMsg("assistant", "Belum ada AI provider untuk Chat AI. Isi API key dan pilih provider di Pengaturan."))
                    return@launch
                }
                val titles = library.books.value.filter { !it.deleted }.map { "${it.title} oleh ${it.author}" }
                val reply = ai.generalChat(chatMessages, titles)
                chatMessages.add(ChatMsg("assistant", reply))
            } catch (e: Exception) {
                chatMessages.add(ChatMsg("assistant", "Gagal: ${e.message}"))
            } finally {
                chatLoading.value = false
            }
        }
    }

    fun signingSha1(): String = signingSha1(ctx)
}
