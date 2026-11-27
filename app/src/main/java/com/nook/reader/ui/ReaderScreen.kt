package com.nook.reader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView

@Composable
fun ReaderScreen(vm: AppViewModel, bookId: String) {
    val books by vm.books.collectAsState()
    val book = books.firstOrNull { it.id == bookId && !it.deleted }
    if (book == null) {
        LaunchedEffect(Unit) { vm.back() }
        return
    }
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val file = vm.library.fileOf(book)
    if (book.format == "pdf") PdfReader(vm, book, file) else EpubReader(vm, book, file)
}
