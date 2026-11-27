package com.nook.reader.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nook.reader.book.EpubBook
import com.nook.reader.book.SearchHit
import com.nook.reader.data.Book
import com.nook.reader.data.Bookmark
import com.nook.reader.data.Highlight
import com.nook.reader.data.ReaderPrefs
import com.nook.reader.util.newId
import com.nook.reader.util.now
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private const val HOST = "nook.local"

data class Sel(val text: String, val start: Int, val end: Int, val context: String)

/** WebView untuk tampilan EPUB. */
class NookWebView(context: Context) : WebView(context) {
    var onMenuAction: ((Int) -> Unit)? = null

    companion object {
        const val ID_HL = 1001
        const val ID_NOTE = 1002
        const val ID_DICT = 1006
        const val ID_TR = 1003
        const val ID_EXP = 1004
        const val ID_ASK = 1005
    }
}

@Stable
class EpubState(val epub: EpubBook, startChapter: Int, startFraction: Float) {
    var chapter by mutableIntStateOf(startChapter.coerceIn(0, epub.spine.lastIndex))
    var fraction by mutableFloatStateOf(startFraction)
    var loadKey by mutableIntStateOf(0)
    var showBars by mutableStateOf(true)
    var selection by mutableStateOf<Sel?>(null)

    var pendingFraction: Float = startFraction
    var pendingAnchor: String? = null
    var pendingFind: String? = null
    var webView: NookWebView? = null

    fun goTo(ch: Int, frac: Float = 0f, anchor: String? = null, find: String? = null) {
        chapter = ch.coerceIn(0, epub.spine.lastIndex)
        pendingFraction = frac
        pendingAnchor = anchor
        pendingFind = find
        fraction = frac
        selection = null
        loadKey++
    }

    val percent: Float
        get() {
            var before = 0L
            for (i in 0 until chapter) before += epub.sizes[i]
            return ((before + fraction * epub.sizes[chapter]) / epub.totalSize).coerceIn(0f, 1f)
        }

    fun seek(p: Float) {
        val target = p.coerceIn(0f, 1f) * epub.totalSize
        var before = 0L
        for (i in epub.spine.indices) {
            val size = epub.sizes[i]
            if (target <= before + size || i == epub.spine.lastIndex) {
                goTo(i, ((target - before) / size).coerceIn(0f, 1f))
                return
            }
            before += size
        }
    }
}

class EpubCallbacks {
    var onTap: (Float) -> Unit = {}
    var onHighlightTap: (String) -> Unit = {}
    var onEdge: (Int) -> Unit = {}
    var onMenu: (Int) -> Unit = {}
}

@Suppress("unused")
class NookBridge(private val st: EpubState, private val cb: EpubCallbacks) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onSelection(json: String) {
        main.post {
            if (json.isEmpty()) {
                st.selection = null
            } else {
                try {
                    val o = JSONObject(json)
                    st.selection = Sel(o.getString("text"), o.getInt("start"), o.getInt("end"), o.optString("context"))
                } catch (_: Exception) {
                }
            }
        }
    }

    @JavascriptInterface
    fun onTap(x: Double) {
        main.post { cb.onTap(x.toFloat()) }
    }

    @JavascriptInterface
    fun onHighlightTap(id: String) {
        main.post { cb.onHighlightTap(id) }
    }

    @JavascriptInterface
    fun onScroll(f: Double) {
        main.post { st.fraction = f.toFloat() }
    }

    @JavascriptInterface
    fun onEdge(dir: Int) {
        main.post { cb.onEdge(dir) }
    }

    @JavascriptInterface
    fun onReady() {
    }
}

private val selfClosing = Regex(
    "<(div|span|a|p|script|title|style|h[1-6]|i|b|em|strong|section|article|ul|ol|li|table|tr|td|th|textarea|iframe|label|button)(\\s[^<>]*?|)/>",
)

private fun urlFor(path: String): String =
    "https://$HOST/" + path.split('/').joinToString("/") { Uri.encode(it) }

private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

private fun cfgJson(p: ReaderPrefs): JSONObject {
    val t = readerTheme(p.theme)
    val ff = when (p.font) {
        "serif" -> "Georgia, 'Noto Serif', serif"
        "sans" -> "Roboto, 'Noto Sans', sans-serif"
        "mono" -> "monospace"
        else -> ""
    }
    return JSONObject()
        .put("fontSize", p.fontSize)
        .put("lineHeight", p.lineHeight.toDouble())
        .put("margin", p.margin)
        .put("maxWidth", 880)
        .put("fontFamily", ff)
        .put("justify", p.justify)
        .put("theme", JSONObject().put("bg", hexColor(t.bg)).put("fg", hexColor(t.fg)).put("link", hexColor(t.link)))
}

private fun hlJson(h: Highlight): JSONObject =
    JSONObject().put("id", h.id).put("start", h.start).put("end", h.end).put("color", h.color)

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpubReader(vm: AppViewModel, book: Book, file: File) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val epub = remember(book.id) { EpubBook.open(file) }
    if (epub == null) {
        ReaderError("EPUB ini tidak bisa dibuka. File mungkin rusak.") { vm.back() }
        return
    }
    DisposableEffect(book.id) { onDispose { epub.close() } }

    val st = remember(book.id) { EpubState(epub, book.chapter, book.fraction) }
    val prefs by vm.settings.readerPrefs.collectAsState()
    val theme = readerTheme(prefs.theme)
    val live by vm.books.collectAsState()
    val cur = live.firstOrNull { it.id == book.id } ?: book
    val cb = remember { EpubCallbacks() }
    val libJs = remember { ctx.assets.open("nook.js").bufferedReader().use { it.readText() } }

    val ai = remember(book.id) {
        ReaderAi(vm, book.title, book.author) {
            withContext(Dispatchers.IO) {
                val t = epub.chapterText(st.chapter)
                if (t.length <= 9000) {
                    t
                } else {
                    val center = (t.length * st.fraction).toInt()
                    val s = (center - 4500).coerceAtLeast(0)
                    t.substring(s, min(t.length, s + 9000))
                }
            }
        }
    }

    var showToc by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showPrefs by remember { mutableStateOf(false) }
    var showNotes by remember { mutableStateOf(false) }
    var noteSel by remember { mutableStateOf<Sel?>(null) }
    var dictSel by remember { mutableStateOf<Sel?>(null) }
    var editHl by remember { mutableStateOf<Highlight?>(null) }

    fun chapterTitle(i: Int): String =
        epub.toc.lastOrNull { it.spine <= i }?.title ?: "Bagian ${i + 1}"

    fun js(code: String) {
        st.webView?.evaluateJavascript(code, null)
    }

    fun addHighlight(sel: Sel, note: String, color: String) {
        val h = Highlight(
            id = newId(),
            chapter = st.chapter,
            start = sel.start,
            end = sel.end,
            text = sel.text.take(500),
            color = color,
            note = note,
            updatedAt = now(),
        )
        vm.addHighlight(book.id, h)
        js("nookAddHighlight(${hlJson(h)})")
        vm.settings.updatePrefs { it.copy(hlColor = color) }
    }

    cb.onTap = { x ->
        when {
            x < 0.22f -> js("nookPage(-1)")
            x > 0.78f -> js("nookPage(1)")
            else -> st.showBars = !st.showBars
        }
    }
    cb.onEdge = { dir ->
        if (dir > 0 && st.chapter < epub.spine.lastIndex) st.goTo(st.chapter + 1, 0f)
        else if (dir < 0 && st.chapter > 0) st.goTo(st.chapter - 1, 1f)
    }
    cb.onHighlightTap = { id ->
        editHl = vm.library.get(book.id)?.highlights?.firstOrNull { it.id == id && !it.deleted }
    }
    cb.onMenu = { id ->
        val sel = st.selection
        if (sel != null) {
            when (id) {
                NookWebView.ID_HL -> addHighlight(sel, "", prefs.hlColor)
                NookWebView.ID_NOTE -> noteSel = sel
                NookWebView.ID_DICT -> dictSel = sel
                NookWebView.ID_TR -> ai.translate(sel.text, sel.context)
                NookWebView.ID_EXP -> ai.explain(sel.text, sel.context)
                NookWebView.ID_ASK -> {
                    ai.isOpen = true
                    ai.draft = "“" + sel.text.take(400) + "”\n"
                }
            }
        }
    }

    // Muat bab saat berpindah.
    LaunchedEffect(st.loadKey) {
        st.webView?.loadUrl(urlFor(epub.spine[st.chapter]))
    }
    // Terapkan perubahan tampilan.
    LaunchedEffect(prefs) {
        st.webView?.setBackgroundColor(themeBgInt(theme))
        js("nookApplyStyle(${cfgJson(prefs)})")
    }
    // Simpan progress.
    LaunchedEffect(st.chapter, st.fraction) {
        delay(1500L)
        vm.saveProgress(book.id, st.chapter, st.fraction, st.percent)
    }
    DisposableEffect(book.id) {
        onDispose { vm.saveProgress(book.id, st.chapter, st.fraction, st.percent) }
    }
    BackHandler(enabled = ai.isOpen) { ai.isOpen = false }

    val bookmark = cur.bookmarks.firstOrNull { !it.deleted && it.chapter == st.chapter && abs(it.fraction - st.fraction) < 0.03f }

    Row(Modifier.fillMaxSize().background(Color(theme.bg))) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    NookWebView(c).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.setSupportZoom(false)
                        settings.builtInZoomControls = false
                        settings.useWideViewPort = false
                        settings.loadWithOverviewMode = false
                        settings.textZoom = 100
                        setBackgroundColor(themeBgInt(theme))
                        overScrollMode = View.OVER_SCROLL_NEVER
                        isVerticalScrollBarEnabled = false
                        addJavascriptInterface(NookBridge(st, cb), "Nook")
                        onMenuAction = { cb.onMenu(it) }
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                                val url = request.url
                                if (url.host != HOST) return notFound()
                                val path = Uri.decode(url.path ?: "").removePrefix("/")
                                val bytes = epub.readBytes(path) ?: return notFound()
                                if (epub.spineIndexOf(path) >= 0) {
                                    var html = bytes.toString(Charsets.UTF_8)
                                    html = selfClosing.replace(html) { m -> "<${m.groupValues[1]}${m.groupValues[2]}></${m.groupValues[1]}>" }
                                    val inject = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><script>$libJs</script>"
                                    val idx = html.indexOf("<head", ignoreCase = true)
                                    val close = if (idx >= 0) html.indexOf('>', idx) else -1
                                    html = if (close >= 0) {
                                        html.substring(0, close + 1) + inject + html.substring(close + 1)
                                    } else {
                                        "<head>$inject</head>$html"
                                    }
                                    return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
                                }
                                return WebResourceResponse(epub.mimeOf(path), null, ByteArrayInputStream(bytes))
                            }

                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val u = request.url
                                if (u.host == HOST) {
                                    val path = Uri.decode(u.path ?: "").removePrefix("/")
                                    val idx = epub.spineIndexOf(path)
                                    if (idx >= 0) st.goTo(idx, 0f, u.fragment)
                                }
                                return true
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                val anchor = st.pendingAnchor
                                val find = st.pendingFind
                                st.pendingAnchor = null
                                st.pendingFind = null
                                val hl = JSONArray()
                                vm.library.get(book.id)?.highlights
                                    ?.filter { !it.deleted && it.chapter == st.chapter }
                                    ?.forEach { hl.put(hlJson(it)) }
                                val a = if (anchor == null) "null" else JSONObject.quote(anchor)
                                val f = if (find == null) "null" else JSONObject.quote(find)
                                view.evaluateJavascript(
                                    "nookInit(${cfgJson(vm.settings.readerPrefs.value)}, ${st.pendingFraction}, $hl, $a, $f)",
                                    null,
                                )
                            }
                        }
                        st.webView = this
                    }
                },
                onRelease = { wv ->
                    st.webView = null
                    wv.destroy()
                },
            )

            val sel = st.selection
            if (sel != null) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (st.showBars) 64.dp else 16.dp).padding(horizontal = 16.dp),
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "“${sel.text.take(60)}”",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.primary,
                            )
                            IconButton(onClick = { st.selection = null }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Filled.Close, contentDescription = "Tutup")
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            AssistChip(
                                onClick = { addHighlight(sel, "", prefs.hlColor); st.selection = null },
                                label = { Text("Stabilo") },
                            )
                            AssistChip(
                                onClick = { noteSel = sel; st.selection = null },
                                label = { Text("Catatan") },
                            )
                            AssistChip(
                                onClick = { dictSel = sel; st.selection = null },
                                label = { Text("📖 Kamus") },
                            )
                            AssistChip(
                                onClick = { ai.translate(sel.text, sel.context); st.selection = null },
                                label = { Text("Terjemahan") },
                            )
                            AssistChip(
                                onClick = { ai.explain(sel.text, sel.context); st.selection = null },
                                label = { Text("Jelaskan") },
                            )
                            AssistChip(
                                onClick = {
                                    ai.isOpen = true
                                    ai.draft = "“" + sel.text.take(400) + "”\n"
                                    st.selection = null
                                },
                                label = { Text("Tanya AI") },
                            )
                        }
                    }
                }
            }

            androidx.compose.animation.AnimatedVisibility(visible = st.showBars, modifier = Modifier.align(Alignment.TopCenter)) {
                ReaderTopBar(
                    title = book.title,
                    onBack = { vm.back() },
                    actions = {
                        IconButton(onClick = {
                            if (bookmark != null) {
                                vm.removeBookmark(book.id, bookmark.id)
                            } else {
                                vm.addBookmark(
                                    book.id,
                                    Bookmark(
                                        id = newId(),
                                        chapter = st.chapter,
                                        fraction = st.fraction,
                                        label = "${chapterTitle(st.chapter)} (${(st.percent * 100).roundToInt()}%)",
                                        updatedAt = now(),
                                    ),
                                )
                            }
                        }) {
                            Icon(
                                if (bookmark != null) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                                contentDescription = "Bookmark",
                            )
                        }
                        IconButton(onClick = { showSearch = true }) { Icon(Icons.Filled.Search, contentDescription = "Cari") }
                        IconButton(onClick = { showToc = true }) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Daftar isi") }
                        IconButton(onClick = { showNotes = true }) { Icon(Icons.Filled.Description, contentDescription = "Catatan") }
                        IconButton(onClick = { showPrefs = true }) { Icon(Icons.Filled.FormatSize, contentDescription = "Tampilan") }
                        IconButton(onClick = { vm.goSettings() }) { Icon(Icons.Filled.Settings, contentDescription = "Pengaturan") }
                        IconButton(onClick = { ai.isOpen = !ai.isOpen }) { Icon(Icons.Filled.AutoAwesome, contentDescription = "AI") }
                    },
                )
            }
            androidx.compose.animation.AnimatedVisibility(visible = st.showBars, modifier = Modifier.align(Alignment.BottomCenter)) {
                ReaderBottomBar(
                    percent = st.percent,
                    label = "${chapterTitle(st.chapter)}    ${(st.percent * 100).roundToInt()}%",
                    onSeek = { st.seek(it) },
                )
            }
        }
        if (ai.isOpen) {
            BoxWithConstraints {
                val w = maxWidth
                val panelWidth = if (w >= 900.dp) 420.dp else if (w >= 600.dp) 360.dp else w
                AiPanel(ai, emptyList(), Modifier.width(panelWidth).fillMaxHeight())
            }
        }
    }

    if (showToc) {
        val currentToc = epub.toc.indexOfLast { it.spine <= st.chapter }
        ListPickDialog(
            title = "Daftar isi",
            labels = epub.toc.map { it.title },
            indents = epub.toc.map { it.level },
            selected = currentToc,
            onPick = { i ->
                val e = epub.toc[i]
                st.goTo(e.spine, 0f, e.anchor)
                showToc = false
            },
            onDismiss = { showToc = false },
        )
    }
    if (showSearch) {
        EpubSearchDialog(
            epub = epub,
            chapterTitle = { chapterTitle(it) },
            onPick = { hit, q ->
                st.goTo(hit.chapter, 0f, null, q)
                showSearch = false
            },
            onDismiss = { showSearch = false },
        )
    }
    if (showPrefs) {
        ReaderPrefsDialog(prefs, false, { vm.settings.updatePrefs(it) }, { showPrefs = false })
    }
    if (showNotes) {
        NotesDialog(
            vm = vm,
            bookId = book.id,
            isPdf = false,
            labelOf = { chapterTitle(it) },
            onJumpHighlight = {
                st.goTo(it.chapter, 0f, "hl:${it.id}")
                showNotes = false
            },
            onJumpBookmark = {
                st.goTo(it.chapter, it.fraction)
                showNotes = false
            },
            onDismiss = { showNotes = false },
        )
    }
    val ns = noteSel
    if (ns != null) {
        HighlightEditDialog(
            quote = ns.text,
            color = prefs.hlColor,
            note = "",
            canDelete = false,
            onSave = { color, note ->
                addHighlight(ns, note, color)
                noteSel = null
            },
            onDelete = {},
            onDismiss = { noteSel = null },
        )
    }
    val ds = dictSel
    if (ds != null) {
        DictionaryAndTranslateDialog(
            wordOrText = ds.text,
            onDismiss = { dictSel = null },
        )
    }
    val eh = editHl
    if (eh != null) {
        HighlightEditDialog(
            quote = eh.text,
            color = eh.color,
            note = eh.note,
            canDelete = true,
            onSave = { color, note ->
                vm.changeHighlight(book.id, eh.id, color = color, note = note)
                js("nookRecolor(${JSONObject.quote(eh.id)}, ${JSONObject.quote(color)})")
                editHl = null
            },
            onDelete = {
                vm.removeHighlight(book.id, eh.id)
                js("nookRemoveHighlight(${JSONObject.quote(eh.id)})")
                editHl = null
            },
            onDismiss = { editHl = null },
        )
    }
}

@Composable
private fun EpubSearchDialog(
    epub: EpubBook,
    chapterTitle: (Int) -> String,
    onPick: (SearchHit, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var q by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var lastQuery by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun run() {
        val query = q.trim()
        if (query.length < 2 || busy) return
        busy = true
        lastQuery = query
        scope.launch {
            hits = withContext(Dispatchers.Default) { epub.search(query) }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cari di buku") },
        text = {
            Column {
                OutlinedTextField(
                    value = q,
                    onValueChange = { q = it },
                    singleLine = true,
                    placeholder = { Text("Kata atau frasa") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { run() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (busy) CircularProgressIndicator(Modifier.padding(12.dp))
                val list = hits
                if (list != null && !busy) {
                    if (list.isEmpty()) {
                        Text("Tidak ditemukan.", modifier = Modifier.padding(top = 12.dp))
                    } else {
                        LazyColumn(Modifier.padding(top = 8.dp)) {
                            items(list) { h ->
                                Column(Modifier.fillMaxWidth().clickable { onPick(h, lastQuery) }.padding(vertical = 8.dp)) {
                                    Text(chapterTitle(h.chapter), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    Text(h.snippet, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { run() }) { Text("Cari") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Tutup") } },
    )
}
