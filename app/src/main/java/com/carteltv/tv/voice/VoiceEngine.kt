package com.carteltv.tv.voice

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.carteltv.tv.AppScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener as VoskListener
import org.vosk.android.SpeechService

/**
 * Microphone input for Cartel Assistant. Prefers the fully-free local Vosk model when installed;
 * falls back to the system [SpeechRecognizer] otherwise. Main thread only for public API.
 */
object VoiceEngine {
    private const val REQ_AUDIO = 4711
    private const val LISTEN_TIMEOUT_MS = 12_000L
    private const val HARD_STOP_MS = 8_000L
    private const val VOSK_SAMPLE_RATE = 16000f

    private val main = Handler(Looper.getMainLooper())
    private var systemRecognizer: SpeechRecognizer? = null
    private var voskService: SpeechService? = null
    private var voskRecognizer: Recognizer? = null
    private var appCtx: Context? = null
    private var startJob: Job? = null
    @Volatile private var accepting = false
    @Volatile private var lastHeard: String = ""

    // If stopListening never produces a result or error, tear the recognizer down instead of leaving the mic open.
    private val hardStop = Runnable {
        if (systemRecognizer != null || voskService != null || startJob?.isActive == true) {
            destroy()
            VoicePipeline.fail("Didn't catch that")
        }
    }
    // Listening has gone quiet: stop the recognizers, then hard-stop if they never produce a result.
    private val onListenTimeout = Runnable {
        systemRecognizer?.stopListening()
        voskService?.stop()
        main.postDelayed(hardStop, HARD_STOP_MS)
    }

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Mic key handler: starts listening, or stops if already listening. Asks for the mic permission the first time. */
    fun toggle(activity: Activity) {
        if (VoicePipeline.phase == VoicePhase.LISTENING) { stop(); return }
        if (!VoskModelManager.isReady(activity) && !SpeechRecognizer.isRecognitionAvailable(activity)) {
            VoicePipeline.fail("Speech recognition isn't available on this device")
            return
        }
        if (!hasPermission(activity)) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return
        }
        start(activity)
    }

    fun onPermissionResult(activity: Activity, requestCode: Int, grantResults: IntArray) {
        if (requestCode != REQ_AUDIO) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) start(activity)
        else VoicePipeline.fail("Microphone permission denied")
    }

    private fun start(ctx: Context) {
        destroy()
        appCtx = ctx.applicationContext
        lastHeard = ""
        if (VoskModelManager.isReady(ctx)) {
            startVosk(ctx.applicationContext)
        } else {
            startSystem(ctx)
        }
    }

    private fun startSystem(ctx: Context) {
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        r.setRecognitionListener(systemListener)
        systemRecognizer = r
        accepting = true
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName),
        )
        VoicePipeline.listening()
        clearTimers()
        main.postDelayed(onListenTimeout, LISTEN_TIMEOUT_MS)
    }

    private fun startVosk(app: Context) {
        accepting = true
        VoicePipeline.listening()
        clearTimers()
        main.postDelayed(hardStop, LISTEN_TIMEOUT_MS + HARD_STOP_MS + 15_000L) // safety during model load/start
        startJob = AppScope.io.launch {
            val model = VoskModelManager.getModel(app)
            if (model == null) {
                main.post {
                    if (accepting && VoicePipeline.phase == VoicePhase.LISTENING) {
                        accepting = false
                        destroyVosk()
                        if (SpeechRecognizer.isRecognitionAvailable(app)) startSystem(app)
                        else VoicePipeline.fail("Offline voice model could not be loaded")
                    }
                }
                return@launch
            }
            val recognizer = runCatching { Recognizer(model, VOSK_SAMPLE_RATE) }.getOrNull()
            if (recognizer == null) {
                main.post {
                    if (accepting && VoicePipeline.phase == VoicePhase.LISTENING) {
                        accepting = false
                        destroyVosk()
                        VoicePipeline.fail("Offline voice recognizer could not start")
                    }
                }
                return@launch
            }
            main.post {
                if (!accepting || VoicePipeline.phase != VoicePhase.LISTENING) {
                    recognizer.close()
                    return@post
                }
                val service = runCatching { SpeechService(recognizer, VOSK_SAMPLE_RATE) }.getOrNull()
                if (service == null) {
                    recognizer.close()
                    accepting = false
                    destroyVosk()
                    VoicePipeline.fail("Offline voice recognizer could not start")
                    return@post
                }
                voskRecognizer = recognizer
                voskService = service
                val started = runCatching { service.startListening(voskListener, LISTEN_TIMEOUT_MS.toInt()) }.getOrDefault(false)
                if (!started) {
                    accepting = false
                    destroyVosk()
                    VoicePipeline.fail("Offline voice recognizer could not start")
                } else {
                    clearTimers()
                    main.postDelayed(onListenTimeout, LISTEN_TIMEOUT_MS)
                }
            }
        }
    }

    fun stop() {
        clearTimers()
        destroy()
        VoicePipeline.cancel()
    }

    private fun clearTimers() {
        main.removeCallbacks(onListenTimeout)
        main.removeCallbacks(hardStop)
    }

    private fun destroy() {
        accepting = false
        clearTimers()
        startJob?.cancel()
        startJob = null
        destroySystem()
        destroyVosk()
    }

    private fun destroySystem() {
        systemRecognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        systemRecognizer = null
    }

    private fun destroyVosk() {
        voskService?.let {
            runCatching { it.cancel() }
            runCatching { it.shutdown() }
        }
        voskService = null
        voskRecognizer?.let { runCatching { it.close() } }
        voskRecognizer = null
    }

    private fun submitText(text: String?) {
        if (!accepting) return
        val ctx = appCtx
        accepting = false
        clearTimers()
        destroySystem()
        destroyVosk()
        val clean = text?.trim().orEmpty()
        if (clean.isBlank() || ctx == null) { VoicePipeline.fail("Didn't catch that"); return }
        // Anything that isn't a command is a search: "open anything" semantics.
        AppScope.io.launch { VoicePipeline.submit(ctx, clean, searchFallback = true) }
    }

    private fun describeSystemError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't hear anything"
        SpeechRecognizer.ERROR_AUDIO -> "Microphone error"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech needs a network connection"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy, try again"
        else -> "Speech recognition failed ($error)"
    }

    private fun voskJsonText(json: String?, key: String): String? {
        if (json.isNullOrBlank()) return null
        val marker = "\"$key\""
        val keyIdx = json.indexOf(marker)
        if (keyIdx < 0) return null
        val colon = json.indexOf(':', keyIdx + marker.length)
        if (colon < 0) return null
        val firstQuote = json.indexOf('"', colon + 1)
        if (firstQuote < 0) return null
        val sb = StringBuilder()
        var i = firstQuote + 1
        while (i < json.length) {
            val c = json[i]
            when {
                c == '\\' && i + 1 < json.length -> {
                    val n = json[i + 1]
                    sb.append(
                        when (n) {
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            '"' -> '"'
                            '\\' -> '\\'
                            else -> n
                        },
                    )
                    i += 2
                    continue
                }
                c == '"' -> return sb.toString()
                else -> sb.append(c)
            }
            i++
        }
        return null
    }

    private val systemListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) { VoicePipeline.level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            clearTimers()
            main.postDelayed(hardStop, HARD_STOP_MS)
            VoicePipeline.thinking()
        }
        override fun onError(error: Int) {
            clearTimers()
            if (!accepting) return
            accepting = false
            destroySystem()
            destroyVosk()
            VoicePipeline.fail(describeSystemError(error))
        }
        override fun onResults(results: Bundle?) {
            clearTimers()
            if (!accepting) return
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty().ifBlank { lastHeard }
            submitText(text.ifBlank { null })
        }
        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }?.let {
                lastHeard = it
                VoicePipeline.heard = it
            }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private val voskListener = object : VoskListener {
        override fun onPartialResult(hypothesis: String?) {
            val text = voskJsonText(hypothesis, "partial")?.trim().orEmpty()
            if (text.isNotBlank() && accepting) {
                lastHeard = text
                main.post { if (accepting) VoicePipeline.heard = text }
            }
        }

        override fun onResult(hypothesis: String?) {
            val text = voskJsonText(hypothesis, "text")?.trim().orEmpty()
            if (text.isNotBlank()) {
                lastHeard = text
                main.post { submitText(text) }
            }
        }

        override fun onFinalResult(hypothesis: String?) {
            val text = voskJsonText(hypothesis, "text")?.trim().orEmpty().ifBlank { lastHeard }
            main.post { submitText(text.ifBlank { null }) }
        }

        override fun onError(exception: Exception?) {
            main.post {
                if (!accepting) return@post
                accepting = false
                clearTimers()
                destroySystem()
                destroyVosk()
                VoicePipeline.fail("Offline voice recognition failed${exception?.message?.let { ": $it" } ?: ""}")
            }
        }

        override fun onTimeout() {
            main.post {
                if (!accepting) return@post
                accepting = false
                clearTimers()
                destroySystem()
                destroyVosk()
                VoicePipeline.fail("Didn't hear anything")
            }
        }
    }
}
