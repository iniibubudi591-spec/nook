package com.nook.reader.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

enum class ProviderType(val label: String, val defaultBase: String, val defaultModel: String) {
    OPENAI("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
    GEMINI("Google Gemini", "https://generativelanguage.googleapis.com", "gemini-2.0-flash"),
    ANTHROPIC("Anthropic Claude", "https://api.anthropic.com", "claude-haiku-5-5"),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com", "deepseek-chat"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-4o-mini"),
    GROQ("Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
    CUSTOM("Custom (kompatibel OpenAI)", "", ""),
}

enum class AiTask(val label: String) {
    CHAT("Chat AI"),
    TRANSLATE("Terjemahan sesuai konteks"),
    EXPLAIN("Penjelasan dan pemahaman buku"),
    CATEGORIZE("Pengelompokan kategori"),
    METADATA("Pencarian metadata dan cover"),
}

data class ProviderConfig(
    val apiKey: String = "",
    val model: String = "",
    val baseUrl: String = "",
)

data class ReaderPrefs(
    val fontSize: Int = 20,
    val lineHeight: Float = 1.6f,
    val margin: Int = 8,
    val font: String = "serif",
    val theme: String = "sepia",
    val justify: Boolean = true,
    val pdfTwoPage: Boolean = true,
    val hlColor: String = "#FFE066",
)

class AppSettings(ctx: Context) {
    private val sp = ctx.getSharedPreferences("nook_settings", Context.MODE_PRIVATE)

    /** Naik setiap ada perubahan, supaya UI bisa membaca ulang nilai. */
    val rev = MutableStateFlow(0)
    private fun bump() {
        rev.value = rev.value + 1
    }

    fun provider(t: ProviderType): ProviderConfig = ProviderConfig(
        apiKey = sp.getString("p_${t.name}_key", "") ?: "",
        model = sp.getString("p_${t.name}_model", t.defaultModel) ?: t.defaultModel,
        baseUrl = sp.getString("p_${t.name}_base", t.defaultBase) ?: t.defaultBase,
    )

    fun setProvider(t: ProviderType, c: ProviderConfig) {
        sp.edit()
            .putString("p_${t.name}_key", c.apiKey)
            .putString("p_${t.name}_model", c.model)
            .putString("p_${t.name}_base", c.baseUrl)
            .apply()
        bump()
    }

    fun savedTaskProvider(task: AiTask): ProviderType? {
        val name = sp.getString("task_${task.name}", null) ?: return null
        return ProviderType.values().firstOrNull { it.name == name }
    }

    fun setTaskProvider(task: AiTask, t: ProviderType?) {
        sp.edit().putString("task_${task.name}", t?.name).apply()
        bump()
    }

    /** Provider yang dipakai untuk satu tugas. Jika belum dipilih, ambil provider pertama yang punya API key. */
    fun taskProvider(task: AiTask): ProviderType? {
        val saved = savedTaskProvider(task)
        if (saved != null && provider(saved).apiKey.isNotBlank()) return saved
        return ProviderType.values().firstOrNull { provider(it).apiKey.isNotBlank() }
    }

    fun answerLanguage(): String = sp.getString("answer_lang", "id") ?: "id"
    fun setAnswerLanguage(v: String) {
        sp.edit().putString("answer_lang", v).apply()
        bump()
    }

    fun appTheme(): String = sp.getString("app_theme", "system") ?: "system"
    fun setAppTheme(v: String) {
        sp.edit().putString("app_theme", v).apply()
        bump()
    }

    fun autoMeta(): Boolean = sp.getBoolean("auto_meta", false)
    fun setAutoMeta(v: Boolean) {
        sp.edit().putBoolean("auto_meta", v).apply()
        bump()
    }

    val readerPrefs = MutableStateFlow(loadPrefs())

    private fun loadPrefs(): ReaderPrefs {
        val d = ReaderPrefs()
        return ReaderPrefs(
            fontSize = sp.getInt("r_font_size", d.fontSize),
            lineHeight = sp.getFloat("r_line_height", d.lineHeight),
            margin = sp.getInt("r_margin", d.margin),
            font = sp.getString("r_font", d.font) ?: d.font,
            theme = sp.getString("r_theme", d.theme) ?: d.theme,
            justify = sp.getBoolean("r_justify", d.justify),
            pdfTwoPage = sp.getBoolean("r_pdf_two", d.pdfTwoPage),
            hlColor = sp.getString("r_hl", d.hlColor) ?: d.hlColor,
        )
    }

    fun updatePrefs(change: (ReaderPrefs) -> ReaderPrefs) {
        val n = change(readerPrefs.value)
        readerPrefs.value = n
        sp.edit()
            .putInt("r_font_size", n.fontSize)
            .putFloat("r_line_height", n.lineHeight)
            .putInt("r_margin", n.margin)
            .putString("r_font", n.font)
            .putString("r_theme", n.theme)
            .putBoolean("r_justify", n.justify)
            .putBoolean("r_pdf_two", n.pdfTwoPage)
            .putString("r_hl", n.hlColor)
            .apply()
    }

    fun getManualOrder(): List<String> {
        val s = sp.getString("manual_book_order", "") ?: ""
        return if (s.isBlank()) emptyList() else s.split(",")
    }

    fun setManualOrder(order: List<String>) {
        sp.edit().putString("manual_book_order", order.joinToString(",")).apply()
        bump()
    }
}
