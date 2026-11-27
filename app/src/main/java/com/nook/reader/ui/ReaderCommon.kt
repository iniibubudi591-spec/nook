package com.nook.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.foundation.border
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.toArgb
import com.nook.reader.ai.ChatMsg
import com.nook.reader.data.Book
import com.nook.reader.data.ReaderPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Percakapan AI untuk satu buku yang sedang dibuka. */
class ReaderAi(
    private val vm: AppViewModel,
    private val title: String,
    private val author: String,
    private val excerpt: suspend () -> String,
) {
    val messages = mutableStateListOf<UiMsg>()
    var isOpen by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var draft by mutableStateOf("")

    private fun launchCall(block: suspend () -> String) {
        loading = true
        vm.viewModelScope.launch {
            try {
                messages.add(UiMsg("assistant", block()))
            } catch (e: Exception) {
                messages.add(UiMsg("error", e.message ?: "Gagal menghubungi AI"))
            } finally {
                loading = false
            }
        }
    }

    private fun shorten(t: String) = if (t.length > 600) t.take(600) + "..." else t

    fun translate(text: String, context: String, target: String? = null) {
        if (loading) return
        isOpen = true
        messages.add(UiMsg("user", shorten(text), "Terjemahkan"))
        launchCall { vm.ai.translate(text, context, title, target) }
    }

    fun explain(text: String, context: String) {
        if (loading) return
        isOpen = true
        messages.add(UiMsg("user", shorten(text), "Jelaskan"))
        launchCall { vm.ai.explain(text, context, title) }
    }

    fun ask(question: String) {
        if (question.isBlank() || loading) return
        isOpen = true
        messages.add(UiMsg("user", question))
        val hist = messages.filter { it.role == "user" || it.role == "assistant" }
            .map { ChatMsg(it.role, if (it.label != null) "${it.label}: ${it.text}" else it.text) }
        launchCall { vm.ai.bookChat(hist, title, author, excerpt()) }
    }

    fun openSettings() = vm.goSettings()
}

@Composable
fun AiPanel(ai: ReaderAi, extra: List<Pair<String, () -> Unit>>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(ai.messages.size, ai.loading) {
        val n = ai.messages.size + if (ai.loading) 1 else 0
        if (n > 0) listState.animateScrollToItem(n - 1)
    }
    Surface(modifier, tonalElevation = 3.dp, shadowElevation = 12.dp) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Asisten AI", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { ai.openSettings() }) { Icon(Icons.Filled.Settings, contentDescription = "Pengaturan") }
                IconButton(onClick = { ai.messages.clear() }) { Icon(Icons.Filled.Delete, contentDescription = "Bersihkan") }
                IconButton(onClick = { ai.isOpen = false }) { Icon(Icons.Filled.Close, contentDescription = "Tutup") }
            }
            HorizontalDivider()
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (ai.messages.isEmpty()) {
                    item {
                        Text(
                            "Pilih teks di buku lalu ketuk Terjemahkan, Jelaskan, atau Tanya AI. Kamu juga bisa bertanya langsung di bawah.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(ai.messages) { m -> MsgBubble(m) }
                if (ai.loading) item { CircularProgressIndicator(Modifier.padding(8.dp).size(24.dp)) }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                extra.forEach { (label, action) -> AssistChip(onClick = action, label = { Text(label) }) }
                AssistChip(onClick = { ai.ask("Rangkum bagian yang sedang saya baca.") }, label = { Text("Rangkum bagian ini") })
                AssistChip(onClick = { ai.ask("Apa konsep atau istilah penting di bagian ini?") }, label = { Text("Konsep penting") })
            }
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedTextField(
                    value = ai.draft,
                    onValueChange = { ai.draft = it },
                    placeholder = { Text("Tanya tentang buku ini") },
                    maxLines = 4,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    enabled = ai.draft.isNotBlank() && !ai.loading,
                    onClick = {
                        val q = ai.draft
                        ai.draft = ""
                        ai.ask(q)
                    },
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Kirim") }
            }
        }
    }
}

@Composable
fun ReaderTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), tonalElevation = 3.dp, shadowElevation = 4.dp) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali") }
            Text(title, modifier = Modifier.weight(1f).padding(horizontal = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            actions()
        }
    }
}

@Composable
fun ReaderBottomBar(
    percent: Float,
    label: String,
    onSeek: (Float) -> Unit,
    onTtsToggle: (() -> Unit)? = null,
    isSpeaking: Boolean = false,
    etaText: String = "",
    modifier: Modifier = Modifier,
) {
    var drag by remember { mutableStateOf<Float?>(null) }
    Surface(modifier.fillMaxWidth(), tonalElevation = 3.dp, shadowElevation = 4.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Slider(
                value = (drag ?: percent).coerceIn(0f, 1f),
                onValueChange = { drag = it },
                onValueChangeFinished = {
                    drag?.let { onSeek(it) }
                    drag = null
                },
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (etaText.isNotBlank()) {
                    Text(etaText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
                }
                if (onTtsToggle != null) {
                    IconButton(onClick = onTtsToggle, modifier = Modifier.size(32.dp)) {
                        Icon(
                            if (isSpeaking) Icons.Filled.Close else Icons.Filled.Settings,
                            contentDescription = "Baca Bersuara (TTS)",
                            tint = if (isSpeaking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ReaderError(message: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(message)
            Button(onClick = onBack) { Text("Kembali") }
        }
    }
}

@Composable
fun ReaderPrefsDialog(
    prefs: ReaderPrefs,
    isPdf: Boolean,
    onChange: ((ReaderPrefs) -> ReaderPrefs) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tampilan") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tema")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReaderThemes.forEach { t ->
                        FilterChip(selected = prefs.theme == t.key, onClick = { onChange { it.copy(theme = t.key) } }, label = { Text(t.label) })
                    }
                }
                if (isPdf) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Dua halaman berdampingan saat layar landscape", modifier = Modifier.weight(1f))
                        Switch(checked = prefs.pdfTwoPage, onCheckedChange = { v -> onChange { it.copy(pdfTwoPage = v) } })
                    }
                    Text(
                        "Tema Gelap dan Hitam membalik warna halaman PDF.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text("Ukuran huruf: ${prefs.fontSize}")
                    Slider(
                        value = prefs.fontSize.toFloat(),
                        onValueChange = { v -> onChange { it.copy(fontSize = v.roundToInt()) } },
                        valueRange = 12f..40f,
                    )
                    Text("Jarak antar baris: ${String.format("%.1f", prefs.lineHeight)}")
                    Slider(
                        value = prefs.lineHeight,
                        onValueChange = { v -> onChange { it.copy(lineHeight = (v * 10).roundToInt() / 10f) } },
                        valueRange = 1.2f..2.4f,
                    )
                    Text("Margin samping: ${prefs.margin}%")
                    Slider(
                        value = prefs.margin.toFloat(),
                        onValueChange = { v -> onChange { it.copy(margin = v.roundToInt()) } },
                        valueRange = 2f..24f,
                    )
                    Text("Jenis huruf")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("serif" to "Serif", "sans" to "Sans", "mono" to "Mono", "book" to "Bawaan buku").forEach { (k, label) ->
                            FilterChip(selected = prefs.font == k, onClick = { onChange { it.copy(font = k) } }, label = { Text(label) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Teks rata kiri kanan", modifier = Modifier.weight(1f))
                        Switch(checked = prefs.justify, onCheckedChange = { v -> onChange { it.copy(justify = v) } })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Selesai") } },
    )
}

@Composable
fun ListPickDialog(
    title: String,
    labels: List<String>,
    indents: List<Int>? = null,
    selected: Int = -1,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(labels.size) { i ->
                    Text(
                        labels[i],
                        style = if (i == selected) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                        color = if (i == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(i) }
                            .padding(start = (indents?.getOrNull(i) ?: 0).times(16).dp, top = 10.dp, bottom = 10.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup") } },
    )
}

/** Dialog untuk membuat atau mengubah highlight dengan catatan. */
@Composable
fun HighlightEditDialog(
    quote: String,
    color: String,
    note: String,
    canDelete: Boolean,
    onSave: (String, String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var c by remember { mutableStateOf(color) }
    var n by remember { mutableStateOf(note) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Catatan") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "“" + quote.take(300) + "”",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HighlightColors.forEach { hex ->
                        val col = Color(android.graphics.Color.parseColor(hex))
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(col)
                                .border(
                                    if (c == hex) 3.dp else 1.dp,
                                    if (c == hex) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                                    CircleShape,
                                )
                                .clickable { c = hex },
                        )
                    }
                }
                OutlinedTextField(
                    value = n,
                    onValueChange = { n = it },
                    label = { Text("Catatan kamu") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (canDelete) {
                    TextButton(onClick = onDelete) { Text("Hapus stabilo", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(c, n.trim()) }) { Text("Simpan") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )
}

/** Daftar highlight dan bookmark. */
@Composable
fun NotesDialog(
    vm: AppViewModel,
    bookId: String,
    isPdf: Boolean,
    labelOf: (Int) -> String,
    onJumpHighlight: (com.nook.reader.data.Highlight) -> Unit,
    onJumpBookmark: (com.nook.reader.data.Bookmark) -> Unit,
    onDismiss: () -> Unit,
) {
    val all by vm.books.collectAsState()
    val book: Book? = all.firstOrNull { it.id == bookId }
    val hls = book?.highlights?.filter { !it.deleted }?.sortedWith(compareBy({ it.chapter }, { it.start })) ?: emptyList()
    val bms = book?.bookmarks?.filter { !it.deleted }?.sortedWith(compareBy({ it.chapter }, { it.fraction })) ?: emptyList()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bookmark dan catatan") },
        text = {
            LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (hls.isEmpty() && bms.isEmpty()) {
                    item { Text("Belum ada bookmark atau catatan.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (bms.isNotEmpty()) item { Text("Bookmark", style = MaterialTheme.typography.titleSmall) }
                items(bms) { b ->
                    Row(Modifier.fillMaxWidth().clickable { onJumpBookmark(b) }, verticalAlignment = Alignment.CenterVertically) {
                        Text(b.label.ifBlank { labelOf(b.chapter) }, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                        IconButton(onClick = { vm.removeBookmark(bookId, b.id) }) { Icon(Icons.Filled.Delete, contentDescription = "Hapus") }
                    }
                }
                if (hls.isNotEmpty() && !isPdf) {
                    item { Text("Stabilo dan catatan", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
                }
                items(hls) { h ->
                    Row(Modifier.fillMaxWidth().clickable { onJumpHighlight(h) }, verticalAlignment = Alignment.Top) {
                        Box(
                            Modifier.padding(top = 10.dp, end = 8.dp).size(12.dp).clip(CircleShape)
                                .background(Color(android.graphics.Color.parseColor(h.color))),
                        )
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text(h.text, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            if (h.note.isNotBlank()) {
                                Text(h.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                            Text(labelOf(h.chapter), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { vm.removeHighlight(bookId, h.id) }) { Icon(Icons.Filled.Delete, contentDescription = "Hapus") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup") } },
    )
}

fun hexColor(c: Long): String = String.format("#%06X", c and 0xFFFFFF)

fun themeBgInt(t: ReaderTheme): Int = Color(t.bg).toArgb()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionaryAndTranslateDialog(
    wordOrText: String,
    onDismiss: () -> Unit,
) {
    var thesaurusResult by remember { mutableStateOf<com.nook.reader.util.ThesaurusResult?>(null) }
    var translatedText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(true) }

    LaunchedEffect(wordOrText) {
        busy = true
        try {
            val tDeferred = async(Dispatchers.IO) {
                com.nook.reader.util.TranslateScraper.translate(wordOrText, "id")
            }
            val thDeferred = async(Dispatchers.IO) {
                if (wordOrText.trim().split(" ").size <= 2) {
                    com.nook.reader.util.ThesaurusScraper.lookup(wordOrText)
                } else {
                    null
                }
            }
            translatedText = tDeferred.await()
            thesaurusResult = thDeferred.await()
        } catch (_: Exception) {
            translatedText = wordOrText
        }
        busy = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column {
                    Text("Kamus Tesaurus & Terjemahan", style = MaterialTheme.typography.titleLarge)
                    Text("“${wordOrText.take(40)}”", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        text = {
            if (busy) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text("Menerjemahkan & mencari tesaurus...", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (translatedText.isNotBlank()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Terjemahan (Bahasa Indonesia):", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(translatedText, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }

                    val res = thesaurusResult
                    if (res != null && res.definition.isNotBlank()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Definisi & Kamus:", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text(res.definition, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (res.synonyms.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Sinonim (Synonyms):", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    res.synonyms.forEach { syn ->
                                        AssistChip(onClick = {}, label = { Text(syn) })
                                    }
                                }
                            }
                        }
                        if (res.antonyms.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Antonim (Antonyms):", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    res.antonyms.forEach { ant ->
                                        AssistChip(onClick = {}, label = { Text(ant) })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Tutup") }
        },
    )
}
