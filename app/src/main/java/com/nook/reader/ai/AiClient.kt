package com.nook.reader.ai

import com.nook.reader.data.ProviderConfig
import com.nook.reader.data.ProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object Net {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()
}

class AiException(message: String) : Exception(message)

data class ChatMsg(val role: String, val content: String)

/**
 * Adapter untuk tiga format API:
 * 1. OpenAI chat completions (OpenAI, DeepSeek, OpenRouter, Groq, Custom)
 * 2. Google Gemini generateContent
 * 3. Anthropic Messages
 */
object AiClient {
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    suspend fun complete(
        type: ProviderType,
        cfg: ProviderConfig,
        system: String,
        messages: List<ChatMsg>,
        maxTokens: Int = 2048,
        temperature: Double = 0.3,
    ): String = withContext(Dispatchers.IO) {
        val apiKey = cfg.apiKey.trim()
        if (apiKey.isBlank()) throw AiException("API key ${type.label} belum diisi.")
        val model = cfg.model.trim().ifBlank { type.defaultModel }.removePrefix("models/")
        if (model.isBlank()) throw AiException("Model untuk ${type.label} belum diisi.")
        val base = cfg.baseUrl.trim().ifBlank { type.defaultBase }.trimEnd('/')
        if (base.isBlank()) throw AiException("Base URL untuk ${type.label} belum diisi.")
        val msgs = normalize(messages)
        if (msgs.isEmpty()) throw AiException("Tidak ada pesan untuk dikirim.")
        when (type) {
            ProviderType.GEMINI -> gemini(base, apiKey, model, system, msgs, maxTokens, temperature)
            ProviderType.ANTHROPIC -> anthropic(base, apiKey, model, system, msgs, maxTokens)
            else -> openAi(type, base, apiKey, model, system, msgs, maxTokens, temperature)
        }
    }

    /** Gabungkan pesan berurutan dengan peran sama dan pastikan pesan pertama dari user. */
    private fun normalize(messages: List<ChatMsg>): List<ChatMsg> {
        val out = mutableListOf<ChatMsg>()
        for (m in messages) {
            val role = when (m.role) {
                "assistant", "model" -> "assistant"
                else -> "user"
            }
            if (m.content.isBlank()) continue
            val last = out.lastOrNull()
            if (last != null && last.role == role) {
                out[out.lastIndex] = ChatMsg(role, last.content + "\n\n" + m.content)
            } else {
                out.add(ChatMsg(role, m.content))
            }
        }
        while (out.isNotEmpty() && out.first().role != "user") out.removeAt(0)
        return out
    }

    private fun post(url: String, headers: Map<String, String>, body: JSONObject): String {
        val rb = Request.Builder().url(url)
        headers.forEach { (k, v) -> rb.header(k, v) }
        rb.post(body.toString().toRequestBody(JSON_TYPE))
        Net.client.newCall(rb.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw AiException("Error ${resp.code}: ${errorText(text)}")
            return text
        }
    }

    private fun get(url: String, headers: Map<String, String>): String {
        val rb = Request.Builder().url(url)
        headers.forEach { (k, v) -> rb.header(k, v) }
        Net.client.newCall(rb.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw AiException("Error ${resp.code}: ${errorText(text)}")
            return text
        }
    }

    private fun errorText(body: String): String {
        if (body.isBlank()) return "Respons kosong dari server."
        return try {
            val o = JSONObject(body)
            val err = o.opt("error")
            when {
                err is JSONObject -> err.optString("message", body)
                err is String -> err
                o.has("message") -> o.optString("message")
                else -> body
            }.take(300)
        } catch (_: Exception) {
            body.take(300)
        }
    }

    private fun openAi(
        type: ProviderType,
        base: String,
        key: String,
        model: String,
        system: String,
        msgs: List<ChatMsg>,
        maxTokens: Int,
        temperature: Double,
    ): String {
        val arr = JSONArray()
        if (system.isNotBlank()) arr.put(JSONObject().put("role", "system").put("content", system))
        msgs.forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content)) }
        val body = JSONObject().put("model", model).put("messages", arr)
        if (type != ProviderType.OPENAI) {
            body.put("temperature", temperature)
            body.put("max_tokens", maxTokens)
        }
        val endpoint = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        val text = post(endpoint, mapOf("Authorization" to "Bearer $key"), body)
        val choices = JSONObject(text).optJSONArray("choices")
        if (choices == null || choices.length() == 0) throw AiException("Respons AI kosong.")
        val message = choices.getJSONObject(0).optJSONObject("message")
            ?: throw AiException("Respons AI tidak punya pesan.")
        return if (message.isNull("content")) "" else message.getString("content")
    }

    private fun gemini(
        base: String,
        key: String,
        model: String,
        system: String,
        msgs: List<ChatMsg>,
        maxTokens: Int,
        temperature: Double,
    ): String {
        val contents = JSONArray()
        msgs.forEach {
            contents.put(
                JSONObject()
                    .put("role", if (it.role == "assistant") "model" else "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", it.content))),
            )
        }
        val body = JSONObject()
            .put("contents", contents)
            .put(
                "generationConfig",
                JSONObject().put("temperature", temperature).put("maxOutputTokens", maxOf(maxTokens, 4096)),
            )
        if (system.isNotBlank()) {
            body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        }
        val cleanBase = base.removeSuffix("/v1beta")
        val text = post("$cleanBase/v1beta/models/$model:generateContent", mapOf("x-goog-api-key" to key), body)
        val root = JSONObject(text)
        val cands = root.optJSONArray("candidates")
        if (cands == null || cands.length() == 0) {
            val reason = root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            throw AiException(if (reason.isNotBlank()) "Diblokir oleh Gemini: $reason" else "Respons Gemini kosong.")
        }
        val parts = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            ?: throw AiException("Respons Gemini kosong.")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text", ""))
        return sb.toString()
    }

    private fun anthropic(
        base: String,
        key: String,
        model: String,
        system: String,
        msgs: List<ChatMsg>,
        maxTokens: Int,
    ): String {
        val arr = JSONArray()
        msgs.forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content)) }
        val body = JSONObject().put("model", model).put("max_tokens", maxTokens).put("messages", arr)
        if (system.isNotBlank()) body.put("system", system)
        val cleanBase = base.removeSuffix("/v1")
        val text = post(
            "$cleanBase/v1/messages",
            mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"),
            body,
        )
        val content = JSONObject(text).optJSONArray("content") ?: throw AiException("Respons Claude kosong.")
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val c = content.getJSONObject(i)
            if (c.optString("type") == "text") sb.append(c.optString("text", ""))
        }
        return sb.toString()
    }

    /** Ambil daftar model yang tersedia untuk akun ini. */
    suspend fun listModels(type: ProviderType, cfg: ProviderConfig): List<String> = withContext(Dispatchers.IO) {
        val apiKey = cfg.apiKey.trim()
        if (apiKey.isBlank()) throw AiException("Isi API key dulu.")
        val base = cfg.baseUrl.trim().ifBlank { type.defaultBase }.trimEnd('/')
        if (base.isBlank()) throw AiException("Isi Base URL dulu.")
        val out = mutableListOf<String>()
        when (type) {
            ProviderType.GEMINI -> {
                val cleanBase = base.removeSuffix("/v1beta")
                val text = get("$cleanBase/v1beta/models?pageSize=200", mapOf("x-goog-api-key" to apiKey))
                val arr = JSONObject(text).optJSONArray("models") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    val methods = m.optJSONArray("supportedGenerationMethods")
                    var ok = false
                    if (methods != null) {
                        for (j in 0 until methods.length()) {
                            if (methods.getString(j) == "generateContent") ok = true
                        }
                    }
                    if (ok) out.add(m.optString("name").removePrefix("models/"))
                }
            }
            ProviderType.ANTHROPIC -> {
                val cleanBase = base.removeSuffix("/v1")
                val text = get(
                    "$cleanBase/v1/models?limit=100",
                    mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01"),
                )
                val arr = JSONObject(text).optJSONArray("data") ?: JSONArray()
                for (i in 0 until arr.length()) out.add(arr.getJSONObject(i).optString("id"))
            }
            else -> {
                val endpoint = if (base.endsWith("/models")) base else "$base/models"
                val text = get(endpoint, mapOf("Authorization" to "Bearer $apiKey"))
                val arr = JSONObject(text).optJSONArray("data") ?: JSONArray()
                for (i in 0 until arr.length()) out.add(arr.getJSONObject(i).optString("id"))
            }
        }
        out.filter { it.isNotBlank() }.sorted()
    }
}
