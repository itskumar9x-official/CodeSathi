package com.inyogeshwar.codesathi.agent

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Phase 2, Step 5 — voice. No new dependencies: Android framework APIs
 * only (SpeechRecognizer for input, TextToSpeech for output).
 *
 * The spoken wording here is our own — no announcement text is copied
 * from any other app.
 */

/** Short announcement spoken when a Build-mode turn finishes cleanly. */
const val VOICE_TASK_DONE_ANNOUNCEMENT = "Task completed."

/** Replies are spoken in plain words, never Markdown. */
const val VOICE_MAX_REPLY_CHARS = 500

/**
 * Strip Markdown formatting so a reply can be spoken as plain words:
 * code blocks are dropped (code is not speakable), links keep their text,
 * and markers like "#", "**", ">" and list bullets are removed. Very long
 * replies are cut to [VOICE_MAX_REPLY_CHARS] characters.
 */
fun speakableText(markdown: String): String {
    var text = markdown
    // Fenced code blocks first (they can span lines).
    text = text.replace(Regex("```.*?```", RegexOption.DOT_MATCHES_ALL), " ")
    // Images keep their alt text; links keep their text.
    text = text.replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "$1")
    text = text.replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1")
    // Inline code keeps its words, without the backticks.
    text = text.replace("`", "")
    // Headings, quotes, bold / italic / strikethrough markers.
    text = text.replace(Regex("(?m)^\\s*#{1,6}\\s*"), "")
    text = text.replace(Regex("(?m)^\\s*>\\s*"), "")
    text = text.replace(Regex("[*_~]{1,3}"), "")
    // List bullets and numbered items at the start of a line.
    text = text.replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
    text = text.replace(Regex("(?m)^\\s*\\d+[.)]\\s+"), "")
    // Horizontal rules and any stray HTML tags.
    text = text.replace(Regex("(?m)^\\s*([-*_])\\1{2,}\\s*$"), "")
    text = text.replace(Regex("<[^>]+>"), "")
    text = text.replace(Regex("\\s+"), " ").trim()
    return text.take(VOICE_MAX_REPLY_CHARS)
}

/**
 * One TextToSpeech instance for the app, owned by the ViewModel.
 *
 * Created once; initialised with the device default locale, falling back
 * to English when that locale has no voice data. If initialisation fails
 * the speaker just stays silent — speaking is skipped, never a crash —
 * and the voice-output toggle keeps working for later turns.
 */
class VoiceSpeaker(context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    /** One reply that arrived before TTS finished starting up. */
    private var pending: String? = null
    private var pendingAnnouncement = false

    init {
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status != TextToSpeech.SUCCESS) {
                    ready = false
                    tts = null
                    return@TextToSpeech
                }
                val engine = tts ?: return@TextToSpeech
                val defaultResult = engine.setLanguage(Locale.getDefault())
                if (defaultResult == TextToSpeech.LANG_MISSING_DATA ||
                    defaultResult == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    engine.setLanguage(Locale.ENGLISH)
                }
                ready = true
                // Speak anything that arrived while TTS was starting up.
                pending?.let { text ->
                    pending = null
                    speakNow(text, TextToSpeech.QUEUE_FLUSH)
                }
                if (pendingAnnouncement) {
                    pendingAnnouncement = false
                    speakNow(VOICE_TASK_DONE_ANNOUNCEMENT, TextToSpeech.QUEUE_ADD)
                }
            }
        } catch (_: Exception) {
            // TTS is unavailable on this device — stay silent.
            tts = null
            ready = false
        }
    }

    /** Speak an assistant reply (Markdown is stripped first). */
    fun speakReply(markdown: String) {
        val text = speakableText(markdown)
        if (text.isBlank()) return
        speak(text)
    }

    /** Speak the short "task completed" announcement after the reply. */
    fun speakAnnouncement() {
        if (ready) {
            speakNow(VOICE_TASK_DONE_ANNOUNCEMENT, TextToSpeech.QUEUE_ADD)
        } else if (tts != null) {
            // TTS is still starting up — queue it behind the pending reply.
            pendingAnnouncement = true
        }
    }

    private fun speak(text: String) {
        if (ready) {
            speakNow(text, TextToSpeech.QUEUE_FLUSH)
        } else if (tts != null) {
            // TTS is still starting up — hold this one reply for onInit.
            pending = text
        }
        // tts == null means initialisation failed: silently skip.
    }

    private fun speakNow(text: String, queueMode: Int) {
        try {
            tts?.speak(text, queueMode, null, "codesathi-${System.currentTimeMillis()}")
        } catch (_: Exception) {
            // Speaking must never crash the chat.
        }
    }

    /** Stop anything being spoken right now (e.g. voice output turned off). */
    fun stop() {
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        pending = null
        pendingAnnouncement = false
    }

    /** Shut TTS down cleanly. Called from the ViewModel's onCleared(). */
    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        ready = false
        pending = null
        pendingAnnouncement = false
    }
}

/**
 * Speech-to-text for the chat input row, wrapping Android's
 * SpeechRecognizer. One recognizer is created per listen and destroyed
 * after each use (on result, on error, or on stop), so it never leaks.
 *
 * The RECORD_AUDIO permission is the caller's job — [start] is only
 * called once the permission is granted (see ChatScreen).
 */
class VoiceInput(context: Context) {

    /** Recognised text, ready to append to the chat input field. */
    var onResult: ((String) -> Unit)? = null

    /** Short, user-facing message when recognition fails or hears nothing. */
    var onErrorMessage: ((String) -> Unit)? = null

    /** True while the microphone is listening. */
    var onListeningChanged: ((Boolean) -> Unit)? = null

    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    /** Start listening. Does nothing when already listening. */
    fun start() {
        if (listening) return
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            onErrorMessage?.invoke("Voice input isn't available on this device.")
            return
        }
        destroyRecognizer()
        try {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    listening = true
                    onListeningChanged?.invoke(true)
                }

                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull { it.isNotBlank() }
                    finishListening()
                    if (text != null) onResult?.invoke(text)
                    else onErrorMessage?.invoke("Nothing heard — try again.")
                }

                override fun onError(error: Int) {
                    finishListening()
                    onErrorMessage?.invoke(
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                "No speech heard — try again."
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                                "Microphone permission is needed for voice input."
                            SpeechRecognizer.ERROR_NETWORK,
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                                "Voice input needs a network connection — try again."
                            SpeechRecognizer.ERROR_CLIENT ->
                                "Voice input was stopped."
                            else -> "Voice input didn't work — try again."
                        }
                    )
                }
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            this.recognizer = recognizer
            recognizer.startListening(intent)
        } catch (_: Exception) {
            finishListening()
            onErrorMessage?.invoke("Voice input didn't work — try again.")
        }
    }

    /** Stop listening early (mic button tapped while listening). */
    fun stop() {
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {
        }
        finishListening()
    }

    /** Destroy the recognizer. Called after each use and on screen dispose. */
    fun destroy() {
        finishListening()
    }

    private fun finishListening() {
        destroyRecognizer()
        if (listening) {
            listening = false
            onListeningChanged?.invoke(false)
        }
    }

    private fun destroyRecognizer() {
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
    }
}
