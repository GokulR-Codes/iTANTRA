package com.itantra.app.stt

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.itantra.core.Language

/**
 * Real, on-device speech recognition using Android's platform [SpeechRecognizer]
 * (P1.3 in docs/PENDING_WORK_MASTER.md — see this file's own "why not `stt.SttEngine`"
 * section below for the one significant, deliberate scope decision this makes).
 *
 * MODEL IDENTITY, per Module B's requirement to document exactly what is being used: this is
 * **not** a model this repository bundles, trains, or ships. It delegates entirely to
 * whichever speech-recognition service is installed and configured as the device's default
 * (on stock Android/Google Play devices, that is Google's on-device speech recognizer,
 * downloaded and updated by the user/OS independently of this app). Concretely:
 *  - Name/version/license/storage footprint: unknown and out of this app's control — owned by
 *    whichever recognition service the device has installed. Nothing here can pin or verify
 *    those.
 *  - Supported languages: whatever that installed service supports; queried at runtime via
 *    [SpeechRecognizer.isOnDeviceRecognitionAvailable] (API 33+) and, on 26-32, the coarser
 *    [SpeechRecognizer.isRecognitionAvailable] (which does not distinguish on-device from
 *    network recognition — see [isAvailable]'s KDoc for exactly what that means for older
 *    OS versions).
 *  - Acquisition: nothing to acquire — no model file is downloaded or bundled by this app.
 *  - Runtime dependency: a device with a recognition service installed (present by default on
 *    essentially all Google Play-certified Android devices, absent on some AOSP/China-market
 *    ROMs — this is a real device-dependent gap, not glossed over).
 *
 * WHY THIS DOES NOT IMPLEMENT `stt.SttEngine` (an explicit architecture decision, recorded
 * here and in docs/DECISIONS.md, not an oversight): [SttEngine][com.itantra.stt.SttEngine]'s
 * contract is `onFrame(frame: AudioFrame): RecognizedUtterance?` — an engine that *consumes*
 * externally-captured 16kHz PCM frames, coexisting downstream of `AudioCapture`/`EnergyGateVad`/
 * `Endpointer`. Android's [SpeechRecognizer] does the opposite: it owns microphone capture
 * internally via [SpeechRecognizer.startListening] and only ever hands back whole-utterance
 * text through [RecognitionListener] callbacks. There is no public API to feed it
 * externally-captured PCM. Implementing `SttEngine` on top of it would require either (a)
 * silently ignoring every frame passed to `onFrame` and secretly capturing the mic a second
 * time internally — misrepresenting the interface's contract to any caller, or (b) opening a
 * second concurrent microphone input while [AndroidAudioCapture][com.itantra.audio.AndroidAudioCapture]
 * (if also running) holds the first — which fails or produces silent/garbage audio on most
 * Android microphone-input implementations. Neither is acceptable per this project's
 * "no fake implementations"/"no unnecessary rewrites that misrepresent behavior" rules.
 * `SessionService` therefore treats this as an alternative, self-contained capture-and-
 * recognize unit, mutually exclusive with the `AudioCapture`-driven frame-fed path — see its
 * KDoc for exactly how the two are kept from running concurrently.
 *
 * THREADING: [SpeechRecognizer] must be created and driven from a thread with a
 * [Looper] — in practice, the main thread. [start]/[stop]/[cancel]/[destroy] below all hop
 * onto the main thread via [mainHandler] if called from elsewhere, so `SessionService` (which
 * may drive this from a background coroutine) does not need to remember to do that itself.
 * Result/error callbacks are correspondingly delivered on the main thread.
 */
class AndroidOnDeviceSpeechRecognizer(private val context: Context) {

    /**
     * Whether this device can plausibly do speech recognition **without** a network
     * connection, to the extent Android's public API can answer that:
     *  - API 33+: [SpeechRecognizer.isOnDeviceRecognitionAvailable] is authoritative — it is
     *    the platform's own answer to exactly this question.
     *  - API 26-32: no such API exists. [SpeechRecognizer.isRecognitionAvailable] only
     *    confirms *some* recognition service is present; it may still require network access
     *    for a given language even if a service exists. This method returns that weaker
     *    signal on those OS versions and callers must not treat it as an offline guarantee —
     *    exactly the caution Module E/B's own instructions call for ("do not assume that all
     *    ... installations are offline-capable").
     */
    fun isAvailable(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        } else {
            SpeechRecognizer.isRecognitionAvailable(context)
        }
    }

    /** True only on API 33+, when the platform itself distinguishes on-device from
     *  network recognition — see [isAvailable]'s KDoc. Exposed so `SessionService`/the UI can
     *  show an honest "offline support not verifiable on this Android version" notice instead
     *  of a false "offline" claim on API 26-32 devices. */
    val canVerifyOfflineCapability: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    val isListening: Boolean get() = listening

    /**
     * Starts one recognition session for [language]. [onPartial] may fire multiple times with
     * growing/changing hypotheses; exactly one of [onFinal] or [onError] fires once, ending the
     * session (matching [SttEngine]'s own "mark exactly one final hypothesis per utterance"
     * framing, even though this class does not implement that interface directly).
     *
     * [onFinal]'s confidence is reported honestly: Android's `EXTRA_CONFIDENCE_SCORES` is
     * optional and frequently absent depending on the recognition service, in which case this
     * passes `null` rather than fabricating a number — callers (`SessionService`) must treat a
     * null confidence as "unknown", never silently substitute 1.0.
     */
    fun start(
        language: Language,
        onPartial: (String) -> Unit,
        onFinal: (text: String, confidence: Float?) -> Unit,
        onError: (String) -> Unit
    ) {
        runOnMain {
            if (listening) {
                onError("AndroidOnDeviceSpeechRecognizer.start() called while a session is already active.")
                return@runOnMain
            }
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                onError("No speech recognition service is available on this device.")
                return@runOnMain
            }

            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            ) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                // Falls back to the device's default recognizer, which MAY use the network
                // depending on the installed service and language — this is the honest
                // fallback path, not a silent "offline" claim (see isAvailable()'s KDoc).
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            recognizer = r

            val intent = RecognizerIntent.getVoiceDetailsIntent(context).let {
                android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            }.apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.code)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, true)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            }

            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit

                override fun onError(error: Int) {
                    listening = false
                    onError(describeError(error))
                }

                override fun onResults(results: Bundle) {
                    listening = false
                    val texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val scores = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                    val text = texts?.firstOrNull()
                    if (text.isNullOrEmpty()) {
                        onError("Recognizer returned no result.")
                        return
                    }
                    val confidence = scores?.firstOrNull()
                    onFinal(text, confidence)
                }

                override fun onPartialResults(partialResults: Bundle) {
                    val texts = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = texts?.firstOrNull()
                    if (!text.isNullOrEmpty()) onPartial(text)
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })

            listening = true
            r.startListening(intent)
        }
    }

    /** Stops listening; the in-flight utterance is still finalized normally (matches
     *  [SpeechRecognizer.stopListening]'s own semantics — this is not [cancel]). */
    fun stop() {
        runOnMain { recognizer?.stopListening() }
    }

    /** Aborts the current session with no final result delivered. */
    fun cancel() {
        runOnMain {
            recognizer?.cancel()
            listening = false
        }
    }

    /** Releases the underlying recognizer. Call when the session/service is torn down. */
    fun destroy() {
        runOnMain {
            recognizer?.destroy()
            recognizer = null
            listening = false
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun describeError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
        SpeechRecognizer.ERROR_CLIENT -> "Client-side error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Missing RECORD_AUDIO permission"
        SpeechRecognizer.ERROR_NETWORK -> "Network error (this recognition attempt required network access)"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "No recognizable speech detected"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
        SpeechRecognizer.ERROR_SERVER -> "Server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Language not supported by the installed recognizer"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Language temporarily unavailable"
        else -> "Recognizer error (code=$code)"
    }
}
