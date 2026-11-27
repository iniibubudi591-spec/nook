package com.nook.reader.book

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

class PdfBook(context: Context, private val file: File) : Closeable {

    private val pfd: ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)
    private val renderLock = Mutex()
    private val textLock = Mutex()
    private var doc: PDDocument? = null

    val pageCount: Int = renderer.pageCount

    /** Tinggi dibagi lebar halaman pertama. Dipakai untuk ukuran sementara sebelum halaman selesai dirender. */
    val aspect: Float

    val cache: LruCache<String, Bitmap>

    init {
        PDFBoxResourceLoader.init(context.applicationContext)
        val p = renderer.openPage(0)
        aspect = try {
            p.height.toFloat() / p.width.toFloat()
        } finally {
            p.close()
        }
        val maxBytes = (Runtime.getRuntime().maxMemory() / 4).toInt().coerceAtLeast(32 * 1024 * 1024)
        cache = object : LruCache<String, Bitmap>(maxBytes) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }
    }

    suspend fun render(index: Int, widthPx: Int): Bitmap = renderLock.withLock {
        withContext(Dispatchers.Default) {
            val page = renderer.openPage(index)
            try {
                val h = (widthPx.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            } finally {
                page.close()
            }
        }
    }

    /** Teks satu halaman (0 based) untuk AI. Kosong jika halaman berupa gambar hasil scan. */
    suspend fun pageText(index: Int): String = textLock.withLock {
        withContext(Dispatchers.IO) {
            try {
                val d = doc ?: PDDocument.load(file).also { doc = it }
                val stripper = PDFTextStripper()
                stripper.startPage = index + 1
                stripper.endPage = index + 1
                stripper.getText(d).trim()
            } catch (e: Exception) {
                ""
            }
        }
    }

    override fun close() {
        try {
            doc?.close()
        } catch (e: Exception) {
        }
        try {
            renderer.close()
        } catch (e: Exception) {
        }
        try {
            pfd.close()
        } catch (e: Exception) {
        }
        cache.evictAll()
    }

    companion object {
        /** Render halaman pertama sebagai cover. */
        fun renderCover(file: File, dest: File, widthPx: Int = 500): Boolean {
            var pfd: ParcelFileDescriptor? = null
            var r: PdfRenderer? = null
            return try {
                pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                r = PdfRenderer(pfd)
                val page = r.openPage(0)
                try {
                    val h = (widthPx.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    com.nook.reader.util.saveCoverBitmap(bmp, dest)
                } finally {
                    page.close()
                }
            } catch (e: Exception) {
                false
            } finally {
                try {
                    r?.close()
                } catch (e: Exception) {
                }
                try {
                    pfd?.close()
                } catch (e: Exception) {
                }
            }
        }

        /** Judul dan penulis dari info dokumen PDF. Dilewati untuk file besar supaya cepat. */
        fun readInfo(context: Context, file: File): Pair<String, String> {
            if (file.length() > 60L * 1024 * 1024) return "" to ""
            return try {
                PDFBoxResourceLoader.init(context.applicationContext)
                val d = PDDocument.load(file)
                try {
                    val info = d.documentInformation
                    (info?.title ?: "").trim() to (info?.author ?: "").trim()
                } finally {
                    d.close()
                }
            } catch (e: Exception) {
                "" to ""
            }
        }
    }
}
