package com.nook.reader.ai

import com.nook.reader.data.AiTask
import com.nook.reader.data.AppSettings
import com.nook.reader.data.Book
import com.nook.reader.meta.Candidate
import com.nook.reader.util.extractJson
import org.json.JSONArray
import org.json.JSONObject

data class DictionaryResult(
    val word: String,
    val phonetic: String = "",
    val partOfSpeech: String = "",
    val indonesianMeaning: String = "",
    val englishDefinition: String = "",
    val synonyms: List<String> = emptyList(),
    val antonyms: List<String> = emptyList(),
    val exampleSentence: String = "",
)

class AiService(private val settings: AppSettings) {

    fun configured(task: AiTask): Boolean = settings.taskProvider(task) != null

    suspend fun run(
        task: AiTask,
        system: String,
        messages: List<ChatMsg>,
        maxTokens: Int = 2048,
        temperature: Double = 0.3,
    ): String {
        val type = settings.taskProvider(task)
            ?: throw AiException("Belum ada AI provider untuk ${task.name}. Isi API key & pilih provider di Pengaturan.")
        val cfg = settings.provider(type)
        if (cfg.apiKey.isBlank()) {
            throw AiException("API key untuk ${type.label} masih kosong. Isi di Pengaturan.")
        }
        return AiClient.complete(type, cfg, system, messages, maxTokens, temperature)
    }

    private fun answerLang(): String =
        if (settings.answerLanguage() == "en") "English" else "Indonesian (Bahasa Indonesia)"

    /** target: "id", "en", atau null untuk otomatis (Indonesia ke Inggris, selain itu ke Indonesia). */
    suspend fun translate(text: String, context: String, title: String, target: String? = null): String {
        val direction = when (target) {
            "id" -> "Translate the passage into Indonesian."
            "en" -> "Translate the passage into English."
            else -> "If the passage is mainly Indonesian, translate it into English. Otherwise translate it into Indonesian."
        }
        val system = """
            You are a careful translator inside an e-book reader.
            $direction
            Use the surrounding context only to decide the right meaning of ambiguous words, idioms, names and technical terms.
            Keep the tone and register of the author. Do not summarize and do not leave anything out.
            Output the translation first, with no preface.
            Then, only if some word or phrase depends on context, add a short section that starts with "Catatan:" and explains at most 3 such choices in Indonesian.
        """.trimIndent()
        val user = buildString {
            if (title.isNotBlank()) append("Book: ").append(title).append("\n\n")
            if (context.isNotBlank()) append("Surrounding context:\n").append(context).append("\n\n")
            append("Passage to translate:\n").append(text)
        }
        return run(AiTask.TRANSLATE, system, listOf(ChatMsg("user", user)), 2048, 0.2)
    }

    suspend fun explain(text: String, context: String, title: String): String {
        val system = """
            You help a reader understand a book.
            Explain the selected passage in ${answerLang()}.
            First say what it means in plain words. Then explain any difficult term or concept in it.
            If the surrounding context matters, say how it connects.
            Use short sentences. Stay under 200 words unless the passage needs more.
            If you are not sure about something, say so.
        """.trimIndent()
        val user = buildString {
            if (title.isNotBlank()) append("Book: ").append(title).append("\n\n")
            if (context.isNotBlank()) append("Surrounding context:\n").append(context).append("\n\n")
            append("Selected passage:\n").append(text)
        }
        return run(AiTask.EXPLAIN, system, listOf(ChatMsg("user", user)), 1500, 0.3)
    }

    suspend fun lookupDictionary(word: String, context: String = ""): DictionaryResult {
        val cleanWord = word.trim().trim('.', ',', '"', '\'', '!', '?', ';', ':')
        val system = """
            You are a comprehensive bilingual dictionary and thesaurus for Indonesian and English readers.
            Provide accurate dictionary details for the requested word.
            Return ONLY a single valid JSON object with the following keys:
            {
              "word": "$cleanWord",
              "phonetic": "/pronunciation/",
              "partOfSpeech": "noun/verb/adjective/etc",
              "indonesianMeaning": "Arti dan definisi dalam Bahasa Indonesia",
              "englishDefinition": "English dictionary definition",
              "synonyms": ["synonym1", "synonym2", "synonym3"],
              "antonyms": ["antonym1", "antonym2"],
              "exampleSentence": "Example sentence."
            }
        """.trimIndent()
        val user = buildString {
            append("Word: ").append(cleanWord)
            if (context.isNotBlank()) append("\nSurrounding context: ").append(context)
        }
        val text = run(AiTask.EXPLAIN, system, listOf(ChatMsg("user", user)), 1000, 0.1)
        return try {
            val obj = JSONObject(extractJson(text))
            val synArr = obj.optJSONArray("synonyms")
            val synList = if (synArr != null) (0 until synArr.length()).map { synArr.getString(it) } else emptyList()
            val antArr = obj.optJSONArray("antonyms")
            val antList = if (antArr != null) (0 until antArr.length()).map { antArr.getString(it) } else emptyList()
            DictionaryResult(
                word = obj.optString("word", cleanWord),
                phonetic = obj.optString("phonetic", ""),
                partOfSpeech = obj.optString("partOfSpeech", ""),
                indonesianMeaning = obj.optString("indonesianMeaning", "Definisi untuk $cleanWord"),
                englishDefinition = obj.optString("englishDefinition", ""),
                synonyms = synList,
                antonyms = antList,
                exampleSentence = obj.optString("exampleSentence", ""),
            )
        } catch (_: Exception) {
            DictionaryResult(
                word = cleanWord,
                indonesianMeaning = "Arti kata: $cleanWord",
                englishDefinition = "Definition for $cleanWord",
            )
        }
    }

    /** Ringkasan terstruktur dari bagian/bab buku yang sedang dibaca. */
    suspend fun summarizePassage(passage: String, title: String, author: String = ""): String {
        val system = """
            You are a reading study assistant.
            Summarize the given book passage clearly in ${answerLang()}.
            Structure the response with:
            1. Ringkasan Inti (2-3 sentences)
            2. Poin Utama (bullet list of key arguments/facts)
            3. Kesimpulan Singkat
            Stay faithful to the text and clearly note if something is implied rather than explicitly stated.
        """.trimIndent()
        val user = buildString {
            append("Book: ").append(title)
            if (author.isNotBlank()) append(" by ").append(author)
            append("\n\nPassage:\n").append(passage)
        }
        return run(AiTask.EXPLAIN, system, listOf(ChatMsg("user", user)), 1800, 0.3)
    }

    /** Ekstrak konsep dan istilah penting beserta penjelasan singkatnya. */
    suspend fun extractKeyConcepts(passage: String, title: String): String {
        val system = """
            You extract key concepts, terminology, and frameworks from a book excerpt in ${answerLang()}.
            List each important concept/term with a concise definition and how it functions in this passage.
            Keep it clear, scannable, and grounded in the text.
        """.trimIndent()
        val user = buildString {
            if (title.isNotBlank()) append("Book: ").append(title).append("\n\n")
            append("Excerpt:\n").append(passage)
        }
        return run(AiTask.EXPLAIN, system, listOf(ChatMsg("user", user)), 1600, 0.3)
    }

    /** Buat kartu flashcard / tanya-jawab untuk latihan ingatan (active recall). */
    suspend fun generateFlashcards(passage: String, title: String, count: Int = 5): String {
        val system = """
            You create study flashcards (Question & Answer pairs) for active recall in ${answerLang()}.
            Create up to $count high-value flashcards based strictly on the provided passage.
            Format each item cleanly as:
            Q: [question]
            A: [concise answer]
        """.trimIndent()
        val user = buildString {
            if (title.isNotBlank()) append("Book: ").append(title).append("\n\n")
            append("Passage:\n").append(passage)
        }
        return run(AiTask.EXPLAIN, system, listOf(ChatMsg("user", user)), 1800, 0.4)
    }

    /** Sintesis kumpulan catatan dan stabilo pembaca menjadi ringkasan terstruktur. */
    suspend fun synthesizeNotes(title: String, notesAndHighlights: List<String>): String {
        val system = """
            You synthesize a reader's highlights and personal notes into a coherent study digest in ${answerLang()}.
            Group related ideas, highlight recurring themes, and preserve key insights without inventing facts outside the notes.
        """.trimIndent()
        val joined = notesAndHighlights.mapIndexed { i, item -> "${i + 1}. $item" }.joinToString("\n\n")
        val user = "Book: $title\n\nHighlights & Notes:\n$joined"
        return run(AiTask.CHAT, system, listOf(ChatMsg("user", user)), 2048, 0.3)
    }

    /** Analisis kritis terhadap argumen atau asumsi dalam kutipan/bagian buku. */
    suspend fun criticalAnalysis(passage: String, title: String, author: String = ""): String {
        val system = """
            You provide a balanced critical reading analysis in ${answerLang()}.
            Identify:
            - Core claim or premise
            - Strengths / supporting logic in the text
            - Potential limitations, assumptions, or alternative perspectives to consider
            Be objective, constructive, and clear.
        """.trimIndent()
        val user = buildString {
            append("Book: ").append(title)
            if (author.isNotBlank()) append(" by ").append(author)
            append("\n\nPassage:\n").append(passage)
        }
        return run(AiTask.EXPLAIN, system, listOf(ChatMsg("user", user)), 1800, 0.4)
    }

    /** Chat tentang buku. excerpt adalah teks di sekitar posisi baca. */
    suspend fun bookChat(history: List<ChatMsg>, title: String, author: String, excerpt: String): String {
        val system = buildString {
            append("You are a reading assistant inside an e-book reader. ")
            append("Answer in ${answerLang()} unless the user writes in another language. ")
            append("Be accurate and concise. ")
            append("When you rely on the excerpt below, say so. ")
            append("If the excerpt does not contain the answer, say that, and then answer from general knowledge while making clear which part is general knowledge.\n\n")
            append("Book: ").append(title)
            if (author.isNotBlank()) append(" by ").append(author)
            if (excerpt.isNotBlank()) append("\n\nExcerpt from the current reading position:\n").append(excerpt)
        }
        return run(AiTask.EXPLAIN, system, history, 2048, 0.4)
    }

    suspend fun generalChat(history: List<ChatMsg>, libraryTitles: List<String>): String {
        val system = buildString {
            append("You are a helpful assistant inside a personal book reader app. ")
            append("Answer in ${answerLang()} unless the user writes in another language. Be concise and honest.")
            if (libraryTitles.isNotEmpty()) {
                append("\n\nBooks in the user's library (for recommendations and questions about their collection):\n")
                append(libraryTitles.take(80).joinToString("\n") { "- $it" })
            }
        }
        return run(AiTask.CHAT, system, history, 2048, 0.5)
    }

    /** Kelompokkan satu batch buku. Mengembalikan peta id ke nama kategori. */
    suspend fun categorizeChunk(chunk: List<Book>, known: List<String>): Map<String, String> {
        val system = """
            You sort books into shelves.
            Return only one JSON object that maps each book id to a category name. No other text.
            Reuse an existing category when it fits. Otherwise create a new one.
            Category names are short (1 to 3 words) and written in Indonesian, for example "Fiksi", "Administrasi Publik", "Statistika", "Sejarah", "Pengembangan Diri".
            Use only the ids given.
        """.trimIndent()
        val arr = JSONArray()
        chunk.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("title", it.title).put("author", it.author)
                    .put("about", it.description.take(200)),
            )
        }
        val user = "Existing categories: ${JSONArray(known)}\n\nBooks:\n$arr"
        val text = run(AiTask.CATEGORIZE, system, listOf(ChatMsg("user", user)), 2048, 0.1)
        val obj = try {
            JSONObject(extractJson(text))
        } catch (_: Exception) {
            throw AiException("AI tidak mengembalikan JSON yang valid.")
        }
        val out = LinkedHashMap<String, String>()
        for (b in chunk) {
            val c = obj.optString(b.id, "").trim()
            if (c.isNotBlank()) out[b.id] = c
        }
        return out
    }

    /**
     * Kelompokkan buku berdasarkan perintah bebas pengguna, misalnya:
     * "berdasarkan pengarang", "berdasarkan genre", atau "berdasarkan bahasa dan topik".
     */
    suspend fun categorizeByInstruction(
        chunk: List<Book>,
        instruction: String,
        known: List<String>,
    ): Map<String, String> {
        val cleanInstruction = instruction.trim().ifBlank {
            "Kelompokkan berdasarkan genre atau topik utama buku."
        }
        val system = """
                You organize books into categories based on the user's instruction.
                User instruction: "$cleanInstruction"

                Return ONLY a valid JSON object mapping each book "id" to its category name. No markdown, no explanation.
                Rules:
                - Follow the user's instruction strictly (for example, if asked by author, use the author's name; if asked by genre/topic, use concise genre/topic labels).
                - Reuse an existing category from the list when it fits well, unless the instruction explicitly asks for a new grouping scheme.
                - Keep category names clean, consistent, and concise (1 to 4 words).
                - Only use book IDs provided in the input.
            """.trimIndent()

        val arr = JSONArray()
        chunk.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("title", it.title)
                    .put("author", it.author)
                    .put("language", it.language)
                    .put("about", it.description.take(240)),
            )
        }

        val user = "Existing categories: ${JSONArray(known)}\n\nBooks:\n$arr"
        val text = run(AiTask.CATEGORIZE, system, listOf(ChatMsg("user", user)), 2048, 0.1)
        val obj = try {
            JSONObject(extractJson(text))
        } catch (_: Exception) {
            throw AiException("AI tidak mengembalikan format JSON kategori yang valid.")
        }

        val out = LinkedHashMap<String, String>()
        for (b in chunk) {
            val cat = obj.optString(b.id, "").trim()
            if (cat.isNotBlank()) out[b.id] = cat
        }
        return out
    }

    /** Pilih hasil pencarian yang paling cocok. Mengembalikan indeks, atau -1 jika tidak ada yang cocok. */
    suspend fun pickCandidate(title: String, author: String, fileName: String, cands: List<Candidate>): Int {
        val system = """
            You match a local e-book file to online search results.
            Return only JSON like {"index": 0}. Use -1 if no result is the same book.
            Prefer the original edition over study guides, summaries and box sets.
        """.trimIndent()
        val arr = JSONArray()
        cands.forEachIndexed { i, c ->
            arr.put(
                JSONObject().put("index", i).put("title", c.title).put("authors", c.authors)
                    .put("year", c.year).put("source", c.source).put("about", c.description.take(120)),
            )
        }
        val user = "File name: $fileName\nTitle in file: $title\nAuthor in file: $author\n\nResults:\n$arr"
        val text = run(AiTask.METADATA, system, listOf(ChatMsg("user", user)), 200, 0.0)
        return try {
            JSONObject(extractJson(text)).optInt("index", 0)
        } catch (_: Exception) {
            0
        }
    }
}
