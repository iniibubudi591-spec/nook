package com.nook.reader.sync

import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.nook.reader.ai.Net
import com.nook.reader.data.Library
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder

const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"

/** Klien Google Drive REST v3 yang tipis. Hanya memakai scope drive.file (file yang dibuat aplikasi ini). */
class DriveSync(private val ctx: Context) {

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun token(): String {
        val account = GoogleSignIn.getLastSignedInAccount(ctx)?.account
            ?: throw IOException("Belum login Google.")
        return GoogleAuthUtil.getToken(ctx, account, "oauth2:$DRIVE_SCOPE")
    }

    private fun exec(build: (String) -> Request): Response {
        var tok = token()
        var resp = Net.client.newCall(build(tok)).execute()
        if (resp.code == 401) {
            resp.close()
            GoogleAuthUtil.clearToken(ctx, tok)
            tok = token()
            resp = Net.client.newCall(build(tok)).execute()
        }
        return resp
    }

    private fun driveError(code: Int, body: String): String {
        val msg = try {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        } catch (e: Exception) {
            ""
        }
        return "Drive error $code ${msg.ifBlank { body.take(200) }}"
    }

    private fun json(build: (String) -> Request): JSONObject {
        exec(build).use { r ->
            val t = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException(driveError(r.code, t))
            return if (t.isBlank()) JSONObject() else JSONObject(t)
        }
    }

    private fun list(q: String): List<JSONObject> {
        val url = "https://www.googleapis.com/drive/v3/files?q=${enc(q)}" +
            "&fields=${enc("files(id,name,size)")}&pageSize=100&spaces=drive"
        val obj = json { tok -> Request.Builder().url(url).header("Authorization", "Bearer $tok").build() }
        val arr = obj.optJSONArray("files") ?: JSONArray()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

    fun ensureFolder(): String {
        val q = "name='Nook' and mimeType='application/vnd.google-apps.folder' and trashed=false"
        list(q).firstOrNull()?.let { return it.getString("id") }
        val meta = JSONObject().put("name", "Nook").put("mimeType", "application/vnd.google-apps.folder")
        val obj = json { tok ->
            Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files?fields=id")
                .header("Authorization", "Bearer $tok")
                .post(meta.toString().toRequestBody(jsonType))
                .build()
        }
        return obj.getString("id")
    }

    fun findFile(name: String, folder: String): String? {
        val q = "name='${esc(name)}' and '$folder' in parents and trashed=false"
        return list(q).firstOrNull()?.getString("id")
    }

    fun downloadText(fileId: String): String {
        exec { tok ->
            Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
                .header("Authorization", "Bearer $tok")
                .build()
        }.use { r ->
            val t = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException(driveError(r.code, t))
            return t
        }
    }

    fun downloadTo(fileId: String, dest: File) {
        val tmp = File(dest.parentFile, dest.name + ".part")
        exec { tok ->
            Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
                .header("Authorization", "Bearer $tok")
                .build()
        }.use { r ->
            if (!r.isSuccessful) throw IOException(driveError(r.code, r.body?.string().orEmpty()))
            val body = r.body ?: throw IOException("Respons kosong")
            body.byteStream().use { ins -> tmp.outputStream().use { out -> ins.copyTo(out) } }
        }
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }

    private fun uploadBody(name: String, folder: String, body: RequestBody, existingId: String?): String {
        val obj = json { tok ->
            val meta = JSONObject().put("name", name)
            if (existingId == null) meta.put("parents", JSONArray().put(folder))
            val mp = MultipartBody.Builder()
                .setType("multipart/related".toMediaType())
                .addPart(meta.toString().toRequestBody(jsonType))
                .addPart(body)
                .build()
            val url = if (existingId == null) {
                "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id"
            } else {
                "https://www.googleapis.com/upload/drive/v3/files/$existingId?uploadType=multipart&fields=id"
            }
            val b = Request.Builder().url(url).header("Authorization", "Bearer $tok")
            (if (existingId == null) b.post(mp) else b.patch(mp)).build()
        }
        return obj.getString("id")
    }

    fun uploadFile(name: String, folder: String, file: File, mime: String): String {
        return uploadBody(name, folder, file.asRequestBody(mime.toMediaType()), null)
    }

    fun writeState(folder: String, existingId: String?, text: String): String {
        return uploadBody("state.json", folder, text.toRequestBody(jsonType), existingId)
    }

    fun delete(fileId: String) {
        exec { tok ->
            Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files/$fileId")
                .header("Authorization", "Bearer $tok")
                .delete()
                .build()
        }.use { r ->
            if (!r.isSuccessful && r.code != 404) throw IOException(driveError(r.code, r.body?.string().orEmpty()))
        }
    }
}

/** Mengatur alur sinkronisasi: gabungkan state, unggah buku baru, hapus buku yang dihapus, tulis state. */
class SyncManager(private val ctx: Context, private val library: Library) {

    suspend fun sync(onStatus: (String) -> Unit): String {
        val drive = DriveSync(ctx)
        onStatus("Menghubungi Google Drive")
        val folder = drive.ensureFolder()
        val stateId = drive.findFile("state.json", folder)

        onStatus("Menggabungkan data")
        val remote = if (stateId != null) {
            try {
                library.parseState(drive.downloadText(stateId))
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
        val merged = library.merge(remote)

        // Hapus file lokal untuk buku yang sudah dihapus di perangkat lain.
        for (b in merged) if (b.deleted && library.isLocal(b)) library.deleteFiles(b)

        // Unggah buku yang belum ada di Drive.
        var uploaded = 0
        val toUpload = merged.filter { !it.deleted && it.remoteFileId.isBlank() && library.isLocal(it) }
        for ((i, b) in toUpload.withIndex()) {
            onStatus("Mengunggah ${i + 1} dari ${toUpload.size}: ${b.title.take(30)}")
            val name = "${b.id}.${b.format}"
            val existing = drive.findFile(name, folder)
            val fileId = existing ?: drive.uploadFile(
                name,
                folder,
                library.fileOf(b),
                if (b.format == "pdf") "application/pdf" else "application/epub+zip",
            )
            library.update(b.id) { it.copy(remoteFileId = fileId) }
            uploaded++
        }

        // Hapus file di Drive untuk buku yang dihapus.
        for (b in library.books.value.filter { it.deleted && it.remoteFileId.isNotBlank() }) {
            try {
                drive.delete(b.remoteFileId)
                library.update(b.id) { it.copy(remoteFileId = "") }
            } catch (e: Exception) {
            }
        }

        onStatus("Menyimpan state")
        drive.writeState(folder, stateId, library.exportState())
        return if (uploaded > 0) "Sinkron selesai, $uploaded buku diunggah" else "Sinkron selesai"
    }

    fun download(remoteFileId: String, dest: File) {
        DriveSync(ctx).downloadTo(remoteFileId, dest)
    }
}
