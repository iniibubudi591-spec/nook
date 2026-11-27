package com.nook.reader.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.nook.reader.book.PdfBook
import com.nook.reader.data.Book
import com.nook.reader.data.Bookmark
import com.nook.reader.util.TtsManager
import com.nook.reader.util.newId
import com.nook.reader.util.now
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private val InvertMatrix = ColorMatrix(
    floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    ),
)

@Composable
fun PdfReader(vm: AppViewModel, book: Book, file: File) {
    val ctx = LocalContext.current
    val pdf = remember(book.id) {
        try {
            PdfBook(ctx.applicationContext, file)
        } catch (_: Exception) {
            null
        }
    }
    if (pdf == null) {
        ReaderError("PDF ini tidak bisa dibuka. File mungkin rusak atau diberi kata sandi.") { vm.back() }
        return
    }
    DisposableEffect(book.id) { onDispose { pdf.close() } }

    val prefs by vm.settings.readerPrefs.collectAsState()
    val theme = readerTheme(prefs.theme)
    val dark = theme.isDark()
    val live by vm.books.collectAsState()
    val cur = live.firstOrNull { it.id == book.id } ?: book
    val cfg = LocalConfiguration.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val landscape = cfg.screenWidthDp > cfg.screenHeightDp
    val perRow = if (prefs.pdfTwoPage && landscape) 2 else 1
    val rows = (pdf.pageCount + perRow - 1) / perRow
    var zoom by remember { mutableFloatStateOf(1f) }
    var showBars by remember { mutableStateOf(true) }
    var showPrefs by remember { mutableStateOf(false) }
    var showNotes by remember { mutableStateOf(false) }
    var dictWord by remember { mutableStateOf<String?>(null) }
    val tts = remember { TtsManager(ctx) }
    DisposableEffect(Unit) {
        onDispose { tts.shutdown() }
    }

    val startPage = book.chapter.coerceIn(0, pdf.pageCount - 1)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startPage / perRow)
    val currentPage by remember(perRow) {
        derivedStateOf { (listState.firstVisibleItemIndex * perRow).coerceIn(0, pdf.pageCount - 1) }
    }
    var lastPage by remember { mutableIntStateOf(startPage) }
    LaunchedEffect(perRow) { listState.scrollToItem((lastPage / perRow).coerceIn(0, rows - 1)) }
    LaunchedEffect(currentPage) { lastPage = currentPage }

    suspend fun pageTextNow(): String {
        val sb = StringBuilder()
        for (k in 0 until perRow) {
            val i = currentPage + k
            if (i < pdf.pageCount) sb.append(pdf.pageText(i)).append("\n\n")
        }
        return sb.toString().trim()
    }

    val ai = remember(book.id) {
        ReaderAi(vm, book.title, book.author) { pageTextNow().take(9000) }
    }

    LaunchedEffect(currentPage) {
        delay(1200L)
        vm.saveProgress(book.id, currentPage, 0f, (currentPage + 1f) / pdf.pageCount)
    }
    DisposableEffect(book.id) {
        onDispose { vm.saveProgress(book.id, lastPage, 0f, (lastPage + 1f) / pdf.pageCount) }
    }
    BackHandler(enabled = ai.isOpen) { ai.isOpen = false }

    val noText = "Halaman ini tidak punya teks yang bisa dibaca. Kemungkinan hasil scan."
    val extra = listOf<Pair<String, () -> Unit>>(
        "Terjemahkan halaman ini" to {
            scope.launch {
                val t = pageTextNow()
                if (t.isBlank()) vm.toast(noText) else ai.translate(t.take(6000), "")
            }
        },
        "Jelaskan halaman ini" to {
            scope.launch {
                val t = pageTextNow()
                if (t.isBlank()) vm.toast(noText) else ai.explain(t.take(6000), "")
            }
        },
        "Scan OCR Halaman" to {
            scope.launch {
                vm.toast("Memulai OCR pada halaman...")
                val bmp = pdf.render(currentPage, 1200)
                val text = com.nook.reader.util.OcrHelper.recognizeText(bmp)
                if (text.isNotBlank()) {
                    dictWord = text.take(300)
                } else {
                    vm.toast("OCR tidak mendeteksi teks pada halaman ini.")
                }
            }
        },
        "Cari Kamus & Tesaurus" to {
            scope.launch {
                val t = pageTextNow()
                if (t.isNotBlank()) dictWord = t.take(80) else vm.toast(noText)
            }
        },
    )

    val bookmark = cur.bookmarks.firstOrNull { !it.deleted && it.chapter == currentPage }

    Row(Modifier.fillMaxSize().background(Color(theme.bg))) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            val gap = 8.dp
            val listWidth = maxWidth * zoom
            val pageW = (listWidth - gap * (perRow + 1)) / perRow
            val targetPx = with(density) { pageW.toPx() }.toInt().coerceIn(200, 2000)

            Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.width(listWidth).fillMaxHeight()
                        .pointerInput(Unit) { detectTapGestures(onTap = { showBars = !showBars }) },
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    items(rows) { r ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = gap), horizontalArrangement = Arrangement.spacedBy(gap)) {
                            for (k in 0 until perRow) {
                                val idx = r * perRow + k
                                if (idx < pdf.pageCount) {
                                    PdfPage(pdf, idx, targetPx, dark, Modifier.weight(1f))
                                } else {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }

            androidx.compose.animation.AnimatedVisibility(visible = showBars, modifier = Modifier.align(Alignment.TopCenter)) {
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
                                        chapter = currentPage,
                                        fraction = 0f,
                                        label = "Halaman ${currentPage + 1}",
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
                        IconButton(onClick = { zoom = (zoom - 0.25f).coerceAtLeast(1f) }) {
                            Icon(Icons.Filled.ZoomOut, contentDescription = "Perkecil")
                        }
                        IconButton(onClick = { zoom = (zoom + 0.25f).coerceAtMost(3f) }) {
                            Icon(Icons.Filled.ZoomIn, contentDescription = "Perbesar")
                        }
                        IconButton(onClick = { showNotes = true }) { Icon(Icons.Filled.Description, contentDescription = "Bookmark") }
                        IconButton(onClick = { showPrefs = true }) { Icon(Icons.Filled.FormatSize, contentDescription = "Tampilan") }
                        IconButton(onClick = { vm.goSettings() }) { Icon(Icons.Filled.Settings, contentDescription = "Pengaturan") }
                        IconButton(onClick = { ai.isOpen = !ai.isOpen }) { Icon(Icons.Filled.AutoAwesome, contentDescription = "AI") }
                    },
                )
            }
            androidx.compose.animation.AnimatedVisibility(visible = showBars, modifier = Modifier.align(Alignment.BottomCenter)) {
                ReaderBottomBar(
                    percent = if (pdf.pageCount > 1) currentPage / (pdf.pageCount - 1f) else 0f,
                    label = "Halaman ${currentPage + 1} dari ${pdf.pageCount}",
                    onSeek = { p ->
                        val page = (p * (pdf.pageCount - 1)).roundToInt()
                        scope.launch { listState.scrollToItem((page / perRow).coerceIn(0, rows - 1)) }
                    },
                    onTtsToggle = {
                        if (tts.isSpeaking) {
                            tts.stop()
                        } else {
                            scope.launch {
                                val t = pageTextNow()
                                if (t.isNotBlank()) tts.speak(t) else vm.toast("Tidak ada teks di halaman ini.")
                            }
                        }
                    },
                    isSpeaking = tts.isSpeaking,
                )
            }
        }
        if (ai.isOpen) {
            BoxWithConstraints {
                val w = maxWidth
                val panelWidth = if (w >= 900.dp) 420.dp else if (w >= 600.dp) 360.dp else w
                AiPanel(ai, extra, Modifier.width(panelWidth).fillMaxHeight())
            }
        }
    }

    if (showPrefs) {
        ReaderPrefsDialog(prefs, true, { vm.settings.updatePrefs(it) }, { showPrefs = false })
    }
    if (showNotes) {
        NotesDialog(
            vm = vm,
            bookId = book.id,
            isPdf = true,
            labelOf = { "Halaman ${it + 1}" },
            onJumpHighlight = { showNotes = false },
            onJumpBookmark = { b ->
                scope.launch { listState.scrollToItem((b.chapter / perRow).coerceIn(0, rows - 1)) }
                showNotes = false
            },
            onDismiss = { showNotes = false },
        )
    }
    val dw = dictWord
    if (dw != null) {
        DictionaryAndTranslateDialog(
            wordOrText = dw,
            onDismiss = { dictWord = null },
        )
    }
}

@Composable
private fun PdfPage(pdf: PdfBook, index: Int, widthPx: Int, dark: Boolean, modifier: Modifier) {
    val key = "$index@$widthPx"
    val bmp by produceState<Bitmap?>(initialValue = pdf.cache[key], key1 = key) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                try {
                    pdf.render(index, widthPx).also { pdf.cache.put(key, it) }
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
    val b = bmp
    if (b != null) {
        Image(
            bitmap = b.asImageBitmap(),
            contentDescription = "Halaman ${index + 1}",
            modifier = modifier.fillMaxWidth(),
            contentScale = ContentScale.FillWidth,
            colorFilter = if (dark) ColorFilter.colorMatrix(InvertMatrix) else null,
        )
    } else {
        Box(
            modifier.fillMaxWidth().aspectRatio(1f / pdf.aspect).background(Color(0xFFEEEEEE)),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(Modifier.size(24.dp))
        }
    }
}
