package com.nook.reader.util

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

class TtsManager(context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)

    var isSpeaking by mutableStateOf(false)
        private set

    var isReady by mutableStateOf(false)
        private set

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isReady = true
            tts?.language = Locale("id", "ID")
        }
    }

    fun speak(text: String, lang: String = "id") {
        if (!isReady || text.isBlank()) return
        val loc = if (lang == "en") Locale.ENGLISH else Locale("id", "ID")
        tts?.language = loc
        tts?.speak(text.take(4000), TextToSpeech.QUEUE_FLUSH, null, "nook_tts")
        isSpeaking = true
    }

    fun stop() {
        tts?.stop()
        isSpeaking = false
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        isSpeaking = false
    }
}
