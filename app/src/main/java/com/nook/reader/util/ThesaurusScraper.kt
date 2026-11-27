package com.nook.reader.util

import com.nook.reader.ai.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

data class ThesaurusResult(
    val word: String,
    val definition: String = "",
    val partOfSpeech: String = "",
    val synonyms: List<String> = emptyList(),
    val antonyms: List<String> = emptyList(),
)

/**
 * Scraper & API client untuk Kamus & Thesaurus.com secara instan tanpa memotong token AI.
 */
object ThesaurusScraper {
    suspend fun lookup(word: String): ThesaurusResult = withContext(Dispatchers.IO) {
        val cleanWord = word.trim().lowercase().replace(Regex("[^a-z0-9-]"), "")
        if (cleanWord.isBlank()) {
            return@withContext ThesaurusResult(word = word, definition = "Kata tidak valid.")
        }
        try {
            // 1. Ambil dari Free Dictionary API (sangat cepat, lengkap dengan definisi, fonetik, sinonim, antonim)
            val freeApiUrl = "https://api.dictionaryapi.dev/api/v2/entries/en/$cleanWord"
            val req = Request.Builder().url(freeApiUrl).header("User-Agent", "Nook/1.0").build()
            Net.client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val arr = org.json.JSONArray(body)
                    if (arr.length() > 0) {
                        val entry = arr.getJSONObject(0)
                        val phonetic = entry.optString("phonetic", entry.optJSONArray("phonetics")?.optJSONObject(0)?.optString("text").orEmpty())
                        val meanings = entry.optJSONArray("meanings")
                        var def = ""
                        var pos = ""
                        val synList = mutableListOf<String>()
                        val antList = mutableListOf<String>()

                        if (meanings != null && meanings.length() > 0) {
                            val m = meanings.getJSONObject(0)
                            pos = m.optString("partOfSpeech", "")
                            val defs = m.optJSONArray("definitions")
                            if (defs != null && defs.length() > 0) {
                                def = defs.getJSONObject(0).optString("definition", "")
                            }
                            val syns = m.optJSONArray("synonyms")
                            if (syns != null) {
                                for (i in 0 until minOf(syns.length(), 12)) synList.add(syns.getString(i))
                            }
                            val ants = m.optJSONArray("antonyms")
                            if (ants != null) {
                                for (i in 0 until minOf(ants.length(), 12)) antList.add(ants.getString(i))
                            }
                        }

                        return@withContext ThesaurusResult(
                            word = cleanWord,
                            definition = "$def\n[Phonetic: $phonetic]",
                            partOfSpeech = pos,
                            synonyms = synList,
                            antonyms = antList,
                        )
                    }
                }
            }

            // 2. Fallback ke scraping Thesaurus.com
            val url = "https://www.thesaurus.com/browse/$cleanWord"
            val htmlReq = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
            Net.client.newCall(htmlReq).execute().use { r ->
                if (r.isSuccessful) {
                    val html = r.body?.string().orEmpty()
                    val syns = mutableListOf<String>()
                    val regex = Regex("class=\"[^\"]*css-[^\"]*etfi2kk[^\"]*\"[^>]*>([^<]+)</a>")
                    for (match in regex.findAll(html)) {
                        val syn = match.groupValues[1].trim()
                        if (syn.isNotBlank() && syn !in syns) syns.add(syn)
                    }
                    return@withContext ThesaurusResult(
                        word = cleanWord,
                        definition = "Definisi Thesaurus untuk $cleanWord",
                        synonyms = syns.take(12),
                    )
                }
            }
        } catch (_: Exception) {
        }

        ThesaurusResult(
            word = word,
            definition = "Definisi untuk $word",
        )
    }
}
