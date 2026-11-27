package com.nook.reader.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nook.reader.data.Book
import com.nook.reader.util.formatSize

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(vm: AppViewModel) {
    val all by vm.books.collectAsState()
    val busy by vm.busy.collectAsState()
    val sync by vm.syncUi.collectAsState()
    val localRev by vm.localRev.collectAsState()

    val books = remember(all) { all.filter { !it.deleted } }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }
    var sort by remember { mutableStateOf("recent") }
    var detail by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    var showAiCategorizeDialog by remember { mutableStateOf(false) }

    var manualOrderList by remember(books) {
        mutableStateOf(
            vm.settings.getManualOrder().let { saved ->
                if (saved.isNotEmpty()) {
                    val savedSet = saved.toSet()
                    val missing = books.map { it.id }.filter { it !in savedSet }
                    saved.filter { id -> books.any { it.id == id } } + missing
                } else {
                    books.map { it.id }
                }
            }
        )
    }

    var draggingBookId by remember { mutableStateOf<String?>(null) }
    var currentDragOffset by remember { mutableStateOf(Offset.Zero) }
    val itemBounds = remember { mutableStateMapOf<String, Rect>() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.importBooks(uris)
    }

    val categories = remember(books) {
        books.map { it.category }.filter { it.isNotBlank() }
            .groupingBy { it }.eachCount().toList().sortedBy { it.first.lowercase() }
    }

    val shown = remember(books, query, filter, sort, manualOrderList) {
        var l = books
        val q = query.trim().lowercase()
        if (q.isNotEmpty()) {
            l = l.filter {
                it.title.lowercase().contains(q) || it.author.lowercase().contains(q) ||
                    it.category.lowercase().contains(q)
            }
        }
        l = when {
            filter == "reading" -> l.filter { it.percent > 0.005f && it.percent < 0.97f }
            filter == "finished" -> l.filter { it.percent >= 0.97f }
            filter == "none" -> l.filter { it.category.isBlank() }
            filter.startsWith("cat:") -> l.filter { it.category == filter.removePrefix("cat:") }
            else -> l
        }
        when (sort) {
            "title" -> l.sortedBy { it.title.lowercase() }
            "author" -> l.sortedBy { it.author.lowercase() }
            "added" -> l.sortedByDescending { it.addedAt }
            "manual" -> {
                l.sortedBy { b ->
                    val i = manualOrderList.indexOf(b.id)
                    if (i >= 0) i else 999999
                }
            }
            else -> l.sortedByDescending { it.progressUpdatedAt }
        }
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(280.dp)) {
                Sidebar(
                    vm = vm,
                    total = books.size,
                    categories = categories,
                    uncategorized = books.count { it.category.isBlank() },
                    filter = filter,
                    onFilter = {
                        filter = it
                        scope.launch { drawerState.close() }
                    },
                    syncText = if (sync.email == null) "Belum login Google" else if (sync.running) sync.status else sync.status.ifBlank { sync.email ?: "" },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            val screenWidth = maxWidth
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(onClick = { scope.launch { if (drawerState.isClosed) drawerState.open() else drawerState.close() } }) {
                        Icon(Icons.Filled.Menu, contentDescription = "Menu Navigasi")
                    }
                    Text("Nook", style = MaterialTheme.typography.headlineSmall)
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Cari judul, penulis, kategori") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Box {
                        IconButton(onClick = { sortOpen = true }) {
                            Icon(Icons.Filled.Sort, contentDescription = "Urutkan")
                        }
                        DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                            listOf(
                                "recent" to "Terakhir dibaca",
                                "added" to "Terbaru ditambahkan",
                                "title" to "Judul",
                                "author" to "Penulis",
                                "manual" to "Urutan Manual",
                            ).forEach { (k, label) ->
                                DropdownMenuItem(
                                    text = { Text(if (sort == k) "✓ $label" else label) },
                                    onClick = {
                                        sort = k
                                        sortOpen = false
                                    },
                                )
                            }
                        }
                    }
                    FilledTonalButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Impor")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Atur & Kategorikan dengan AI...") },
                                onClick = { menuOpen = false; showAiCategorizeDialog = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Hapus buku duplikat otomatis") },
                                onClick = { menuOpen = false; vm.removeDuplicates() },
                            )
                            DropdownMenuItem(
                                text = { Text("Bersihkan referensi file hilang") },
                                onClick = { menuOpen = false; vm.cleanupMissingFiles() },
                            )
                            DropdownMenuItem(
                                text = { Text("Sinkronkan sekarang") },
                                onClick = { menuOpen = false; vm.syncNow(silent = false) },
                            )
                            DropdownMenuItem(
                                text = { Text("Lengkapi metadata dan cover (AI)") },
                                onClick = { menuOpen = false; vm.enrichMetadata() },
                            )
                            DropdownMenuItem(
                                text = { Text("Unduh semua buku dari Drive") },
                                onClick = { menuOpen = false; vm.syncNow(silent = false) },
                            )
                            DropdownMenuItem(
                                text = { Text("Chat AI") },
                                onClick = { menuOpen = false; vm.goChat() },
                            )
                            DropdownMenuItem(
                                text = { Text("Pengaturan") },
                                onClick = { menuOpen = false; vm.goSettings() },
                            )
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                        FilterChip(selected = filter == "all", onClick = { filter = "all" }, label = { Text("Semua") })
                        FilterChip(selected = filter == "reading", onClick = { filter = "reading" }, label = { Text("Sedang dibaca") })
                        FilterChip(selected = filter == "finished", onClick = { filter = "finished" }, label = { Text("Selesai") })
                        for ((name, count) in categories) {
                            FilterChip(
                                selected = filter == "cat:$name",
                                onClick = { filter = "cat:$name" },
                                label = { Text("$name ($count)") },
                            )
                        }
                    }

                if (busy != null) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(busy ?: "", style = MaterialTheme.typography.labelMedium)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }

                if (shown.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (books.isEmpty()) "Perpustakaan masih kosong.\nKetuk Impor untuk menambah buku EPUB atau PDF."
                            else "Tidak ada buku yang cocok.",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    val minGridSize = if (screenWidth < 400.dp) 130.dp else if (screenWidth < 700.dp) 150.dp else 168.dp
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minGridSize),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(shown, key = { it.id }) { b ->
                            BookCard(
                                vm = vm,
                                book = b,
                                localRev = localRev,
                                sort = sort,
                                isDragging = draggingBookId == b.id,
                                onPositioned = { bounds -> itemBounds[b.id] = bounds },
                                onDragStart = {
                                    sort = "manual"
                                    draggingBookId = b.id
                                    currentDragOffset = Offset.Zero
                                },
                                onDrag = { delta ->
                                    currentDragOffset += delta
                                    val startRect = itemBounds[b.id]
                                    if (startRect != null) {
                                        val fingerPos = startRect.center + currentDragOffset
                                        val targetId = itemBounds.entries.firstOrNull { (id, bounds) ->
                                            id != b.id && bounds.contains(fingerPos)
                                        }?.key

                                        if (targetId != null) {
                                            val fromIdx = manualOrderList.indexOf(b.id)
                                            val toIdx = manualOrderList.indexOf(targetId)
                                            if (fromIdx >= 0 && toIdx >= 0 && fromIdx != toIdx) {
                                                val next = manualOrderList.toMutableList()
                                                next.removeAt(fromIdx)
                                                next.add(toIdx, b.id)
                                                manualOrderList = next
                                                vm.settings.setManualOrder(next)
                                            }
                                        }
                                    }
                                },
                                onDragEnd = {
                                    vm.settings.setManualOrder(manualOrderList)
                                    vm.library.notifyLocalChanged()
                                    draggingBookId = null
                                    currentDragOffset = Offset.Zero
                                },
                                onOpen = { vm.open(b.id) },
                                onMore = { detail = b.id },
                            )
                        }
                    }
                }
            }
        }
    }

    val draggingId = draggingBookId
    val draggingBook = remember(draggingId, books) { books.firstOrNull { it.id == draggingId } }
    if (draggingId != null && draggingBook != null) {
        val startRect = itemBounds[draggingId]
        if (startRect != null) {
            val coverFile = remember(draggingId) { vm.library.coverOf(draggingBook) }
            val model: Any? = if (coverFile.exists()) coverFile else draggingBook.coverUrl.ifBlank { null }
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 1.0f }
            ) {
                Box(
                    Modifier
                        .offset {
                            IntOffset(
                                (startRect.left + currentDragOffset.x).roundToInt(),
                                (startRect.top + currentDragOffset.y).roundToInt()
                            )
                        }
                        .size(with(LocalDensity.current) { startRect.width.toDp() }, with(LocalDensity.current) { startRect.height.toDp() })
                        .graphicsLayer {
                            alpha = 0.5f
                            scaleX = 1.06f
                            scaleY = 1.06f
                            shadowElevation = 24.dp.toPx()
                        }
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    if (model != null) {
                        AsyncImage(
                            model = model,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text(
                            draggingBook.title,
                            modifier = Modifier.align(Alignment.Center).padding(8.dp),
                            maxLines = 4,
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                }
            }
        }
    }

    val d = detail
    if (d != null) {
        BookDialog(vm = vm, bookId = d, categories = categories.map { it.first }, onClose = { detail = null })
    }
    if (showAiCategorizeDialog) {
        AiCategorizeDialog(vm = vm, onDismiss = { showAiCategorizeDialog = false })
    }
}

@Composable
private fun Sidebar(
    vm: AppViewModel,
    total: Int,
    categories: List<Pair<String, Int>>,
    uncategorized: Int,
    filter: String,
    onFilter: (String) -> Unit,
    syncText: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(vertical = 16.dp)) {
        Text("Nook", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        LazyColumn(Modifier.weight(1f)) {
            item { SideItem("Semua buku", total, filter == "all") { onFilter("all") } }
            item { SideItem("Sedang dibaca", null, filter == "reading") { onFilter("reading") } }
            item { SideItem("Selesai", null, filter == "finished") { onFilter("finished") } }
            if (categories.isNotEmpty()) {
                item {
                    Text(
                        "Kategori",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(categories) { (name, count) ->
                    SideItem(name, count, filter == "cat:$name") { onFilter("cat:$name") }
                }
            }
            if (uncategorized > 0) {
                item { SideItem("Tanpa kategori", uncategorized, filter == "none") { onFilter("none") } }
            }
        }
        Text(
            syncText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        TextButton(onClick = { vm.syncNow(silent = false) }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Sinkronkan")
        }
        TextButton(onClick = { vm.goChat() }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Icon(Icons.Filled.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Chat AI")
        }
        TextButton(onClick = { vm.goSettings() }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Pengaturan")
        }
    }
}

@Composable
private fun SideItem(label: String, count: Int?, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 1.dp).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.bodyLarge)
            if (count != null) {
                Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(
    vm: AppViewModel,
    book: Book,
    localRev: Int,
    sort: String,
    isDragging: Boolean,
    onPositioned: (Rect) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onOpen: () -> Unit,
    onMore: () -> Unit,
) {
    val local = remember(book.id, localRev) { vm.library.isLocal(book) }
    val coverFile = remember(book.id) { vm.library.coverOf(book) }
    val model: Any? = if (coverFile.exists()) coverFile else book.coverUrl.ifBlank { null }

    Column(
        modifier = Modifier
            .onGloballyPositioned { coords -> onPositioned(coords.boundsInWindow()) }
            .graphicsLayer {
                alpha = if (isDragging) 0.25f else 1.0f
                scaleX = if (isDragging) 0.95f else 1.0f
                scaleY = if (isDragging) 0.95f else 1.0f
            }
            .pointerInput(book.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { onDragStart() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount)
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                )
            }
            .clickable(onClick = onOpen)
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (model != null) {
                AsyncImage(
                    model = model,
                    contentDescription = book.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    book.title,
                    modifier = Modifier.align(Alignment.Center).padding(12.dp),
                    maxLines = 6,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = Color(0xAA000000),
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
            ) {
                Text(
                    book.format.uppercase(),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            if (!local) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xAA000000),
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                ) {
                    Icon(
                        Icons.Filled.CloudDownload,
                        contentDescription = "Hanya di Drive",
                        tint = Color.White,
                        modifier = Modifier.padding(4.dp).size(16.dp),
                    )
                }
            }
            IconButton(onClick = onMore, modifier = Modifier.align(Alignment.BottomEnd).size(36.dp)) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Detail", tint = Color.White)
            }
        }
        Text(
            book.title,
            maxLines = 2,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (book.author.isNotBlank()) {
            Text(
                book.author,
                maxLines = 1,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (book.percent > 0.005f) {
            LinearProgressIndicator(
                progress = { book.percent.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookDialog(vm: AppViewModel, bookId: String, categories: List<String>, onClose: () -> Unit) {
    val all by vm.books.collectAsState()
    val book = all.firstOrNull { it.id == bookId && !it.deleted }
    if (book == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    var title by remember(book.id, book.updatedAt) { mutableStateOf(book.title) }
    var author by remember(book.id, book.updatedAt) { mutableStateOf(book.author) }
    var category by remember(book.id, book.updatedAt) { mutableStateOf(book.category) }
    var desc by remember(book.id, book.updatedAt) { mutableStateOf(book.description) }
    var confirmDelete by remember { mutableStateOf(false) }
    val local = vm.library.isLocal(book)

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Detail buku") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    listOf(
                        book.format.uppercase(),
                        formatSize(book.sizeBytes),
                        if (local) "ada di perangkat ini" else "hanya di Drive",
                    ).filter { it.isNotBlank() }.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(title, { title = it }, label = { Text("Judul") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(author, { author = it }, label = { Text("Penulis") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(category, { category = it }, label = { Text("Kategori") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (categories.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        categories.forEach { c ->
                            AssistChip(onClick = { category = c }, label = { Text(c) })
                        }
                    }
                }
                OutlinedTextField(
                    desc,
                    { desc = it },
                    label = { Text("Deskripsi") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = { vm.fetchMetaForBook(book.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Cari metadata dan cover")
                }
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Hapus buku ini", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                vm.updateMeta(book.id, title, author, category, desc)
                onClose()
            }) { Text("Simpan") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Tutup") } },
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Hapus buku?") },
            text = { Text("Buku akan dihapus dari perangkat ini dan, setelah sinkron, dari Google Drive dan perangkat lain. Highlight dan catatan ikut hilang.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteBookCompletely(book.id)
                    onClose()
                }) { Text("Hapus", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Batal") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiCategorizeDialog(
    vm: AppViewModel,
    onDismiss: () -> Unit,
) {
    val presets = listOf(
        "Berdasarkan Genre & Topik Utama" to "Kelompokkan berdasarkan genre dan topik utama isi buku.",
        "Berdasarkan Penulis / Author" to "Kelompokkan berdasarkan nama penulis utama buku.",
        "Berdasarkan Jenis Karya" to "Kelompokkan berdasarkan jenis karya (Fiksi, Non-Fiksi, Biografi, Novel, Sains, Sejarah, Agama, Komik, Panduan, dll).",
        "Berdasarkan Bahasa & Kebudayaan" to "Kelompokkan berdasarkan bahasa dan latar kebudayaan karya.",
    )

    var selectedPresetIndex by remember { mutableIntStateOf(0) }
    var customInstruction by remember { mutableStateOf(presets[0].second) }
    var onlyEmpty by remember { mutableStateOf(true) }
    var useAi by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pengelompokan Buku (Kategori & Rak)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Pilih metode pengelompokan perpustakaan buku:",
                    style = MaterialTheme.typography.bodyMedium,
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { useAi = !useAi }.padding(vertical = 4.dp),
                ) {
                    Checkbox(
                        checked = useAi,
                        onCheckedChange = { useAi = it },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (useAi) "Gunakan AI (Kecerdasan Buatan)" else "Gunakan Metadata Lokal (Tanpa AI / Hemat Token)",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                if (useAi) {
                    Text("Kriteria Pengelompokan AI:", style = MaterialTheme.typography.labelLarge)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        presets.forEachIndexed { i, pair ->
                            FilterChip(
                                selected = selectedPresetIndex == i,
                                onClick = {
                                    selectedPresetIndex = i
                                    customInstruction = pair.second
                                },
                                label = { Text(pair.first) },
                            )
                        }
                    }

                    OutlinedTextField(
                        value = customInstruction,
                        onValueChange = {
                            customInstruction = it
                            selectedPresetIndex = -1
                        },
                        label = { Text("Instruksi AI Kustom") },
                        placeholder = { Text("misal: Kelompokkan berdasarkan era sejarah atau topik utama") },
                        singleLine = false,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { onlyEmpty = !onlyEmpty }.padding(vertical = 4.dp),
                ) {
                    Checkbox(
                        checked = onlyEmpty,
                        onCheckedChange = { onlyEmpty = it },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (onlyEmpty) "Hanya buku yang belum punya kategori" else "Kelompokkan ulang SEMUA buku",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (useAi) {
                        vm.categorizeWithInstruction(customInstruction, onlyEmpty = onlyEmpty)
                    } else {
                        vm.categorizeByMetadata(onlyEmpty = onlyEmpty)
                    }
                    onDismiss()
                },
                enabled = !useAi || customInstruction.isNotBlank(),
            ) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (useAi) "Mulai Kategorisasi AI" else "Kategorikan via Metadata")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Batal") }
        },
    )
}
