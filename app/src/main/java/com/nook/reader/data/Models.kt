package com.nook.reader.data

import kotlinx.serialization.Serializable

@Serializable
data class Highlight(
    val id: String,
    val chapter: Int,
    val start: Int = 0,
    val end: Int = 0,
    val text: String = "",
    val color: String = "#FFE066",
    val note: String = "",
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
)

@Serializable
data class Bookmark(
    val id: String,
    val chapter: Int,
    val fraction: Float = 0f,
    val label: String = "",
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
)

/**
 * Satu record buku. Dipakai untuk penyimpanan lokal dan file state.json di Google Drive.
 * chapter: indeks bab (EPUB) atau indeks halaman (PDF).
 * updatedAt hanya berubah saat metadata diubah. Progress punya timestamp sendiri.
 */
@Serializable
data class Book(
    val id: String,
    val fileName: String = "",
    val format: String = "epub",
    val title: String = "",
    val author: String = "",
    val description: String = "",
    val language: String = "",
    val category: String = "",
    val coverUrl: String = "",
    val sizeBytes: Long = 0,
    val addedAt: Long = 0,
    val updatedAt: Long = 0,
    val chapter: Int = 0,
    val fraction: Float = 0f,
    val percent: Float = 0f,
    val progressUpdatedAt: Long = 0,
    val highlights: List<Highlight> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    val deleted: Boolean = false,
    val remoteFileId: String = "",
)

@Serializable
data class LibraryState(
    val version: Int = 1,
    val books: List<Book> = emptyList(),
)
