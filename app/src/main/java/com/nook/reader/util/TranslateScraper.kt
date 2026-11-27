package com.nook.reader.util

import com.nook.reader.ai.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder

object TranslateScraper {
    suspend fun translate(text: String, targetLang: String = "id"): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext ""
        try {
            val encoded = URLEncoder.encode(text.take(2000), "UTF-8")
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$targetLang&dt=t&q=$encoded"
            val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
            Net.client.newCall(req).execute().use { r ->
                if (r.isSuccessful) {
                    val body = r.body?.string().orEmpty()
                    val jsonArr = JSONArray(body)
                    val sentences = jsonArr.getJSONArray(0)
                    val sb = StringBuilder()
                    for (i in 0 until sentences.length()) {
                        sb.append(sentences.getJSONArray(i).getString(0))
                    }
                    return@withContext sb.toString()
                }
            }
        } catch (_: Exception) {
        }
        return@withContext "Gagal menerjemahkan."
    }
}
