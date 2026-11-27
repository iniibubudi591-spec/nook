package com.nook.reader.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.security.MessageDigest
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

fun now(): Long = System.currentTimeMillis()

fun cleanTitle(fileName: String): String {
    var s = fileName.substringBeforeLast('.')
    s = s.replace(
        Regex("\\(z-lib\\.org\\)|\\(Z-Library\\)|\\(libgen[^)]*\\)|\\[[^\\]]*\\]", RegexOption.IGNORE_CASE),
        " ",
    )
    s = s.replace('_', ' ')
    s = s.replace(Regex("\\s+"), " ").trim()
    return s
}

fun formatSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val mb = bytes / 1048576.0
    return if (mb >= 1) String.format("%.1f MB", mb) else String.format("%d KB", bytes / 1024)
}

fun saveCoverBitmap(bmp: Bitmap, dest: File): Boolean = try {
    dest.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    true
} catch (e: Exception) {
    false
}

fun saveCoverBytes(bytes: ByteArray, dest: File, maxWidth: Int = 600): Boolean {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return false
    return saveCoverBitmap(bmp, dest)
}

/** Ambil blok JSON pertama dari jawaban AI (yang kadang dibungkus ``` atau teks tambahan). */
fun extractJson(text: String): String {
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    return if (start >= 0 && end > start) text.substring(start, end + 1) else text
}

fun signingSha1(ctx: Context): String {
    return try {
        val pm = ctx.packageManager
        val pkg = ctx.packageName
        val sigs = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            info.signatures
        }
        val cert = sigs?.firstOrNull()?.toByteArray() ?: return "Sertifikat tidak ditemukan"
        val md = MessageDigest.getInstance("SHA-1")
        val digest = md.digest(cert)
        digest.joinToString(":") { String.format("%02X", it) }
    } catch (e: Exception) {
        "Error: ${e.message}"
    }
}
