package com.itantra.app.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.itantra.core.Language
import com.itantra.core.SynthesizedAudio
import com.itantra.tts.TtsEngine
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Real [TtsEngine] implementation (P1.4 in docs/PENDING_WORK_MASTER.md) backed by
 * [android.speech.tts.TextToSpeech], the platform text-to-speech service.
 *
 * MODEL IDENTITY (Module E's documentation requirement): as with
 * [com.itantra.app.stt.AndroidOnDeviceSpeechRecognizer], this delegates to whichever TTS
 * engine/voice is installed and selected as the device default (typically Google
 * Text-to-speech on Play-certified devices) — this app bundles no voice model itself.
 * Name/version/license/storage footprint are therefore owned by that installed engine, not
 * this repository, and are not knowable from here.
 *
 * OFFLINE CAPABILITY IS A HEURISTIC, NOT A GUARANTEE — stated exactly as Module E's own
 * instructions require ("do not assume that all Android TTS installations are
 * offline-capable"). Android has no public API that definitively answers "will synthesizing
 * this exact text, in this exact voice, right now, touch the network". [isLikelyOffline]
 * checks whether the selected [android.speech.tts.Voice] advertises the
 * [TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS] feature — its **absence** is a
 * reasonable signal the voice is locally installed, but engines are not required to report
 * this accurately. Treat [isLikelyOffline] as advisory for UI messaging, never as a gate that
 * silently changes behavior.
 *
 * BLOCKING CONTRACT: [TtsEngine.synthesize] is a synchronous, blocking call by its own
 * interface contract ("Synthesizes text to PCM" with no callback parameter). Android's
 * `TextToSpeech` API is asynchronous by nature, so [synthesize] bridges the two with
 * [android.speech.tts.TextToSpeech.synthesizeToFile] plus a [CountDownLatch] wait (bounded by
 * [synthesisTimeoutMs]). Callers (`SessionService`) MUST invoke this off the main thread —
 * calling it from the main thread would deadlock/ANR, since `TextToSpeech`'s own completion
 * callback is itself delivered on the main thread in the common case.
 *
 * FIRST-AUDIO LATENCY CAVEAT: [SynthesizedAudio.firstAudioReadyTsMs] is reported honestly per
 * that field's contract, but for this specific, file-based (non-streaming) synthesis path it
 * is mechanically equal to *total* synthesis completion time — `synthesizeToFile` does not
 * expose a real first-audio-chunk callback. A future streaming TTS backend (writing directly
 * to an `AudioTrack` via `TextToSpeech`'s `Engine#onAudioAvailable`-style callback path, or a
 * different engine entirely) could report a genuinely earlier timestamp; this implementation
 * does not fabricate one in the meantime.
 */
class AndroidTextToSpeechEngine(
    context: Context,
    initialLanguage: Language,
    private val synthesisTimeoutMs: Long = 15_000L
) : TtsEngine {

    override var activeLanguage: Language = initialLanguage
        private set

    private val appContext = context.applicationContext
    private val ready = AtomicBoolean(false)
    private val initLatch = CountDownLatch(1)
    private var tts: TextToSpeech? = null
    private val lastError = AtomicReference<String?>(null)

    init {
        val engine = TextToSpeech(appContext) { status ->
            ready.set(status == TextToSpeech.SUCCESS)
            if (status != TextToSpeech.SUCCESS) {
                lastError.set("TextToSpeech engine initialization failed (status=$status)")
            }
            initLatch.countDown()
        }
        tts = engine
        // Bounded wait so a caller's first synthesize() call does not hang forever if the
        // platform TTS service never calls back (observed, per Android's own documentation,
        // on devices with no TTS engine installed at all).
        initLatch.await(synthesisTimeoutMs, TimeUnit.MILLISECONDS)
        if (ready.get()) applyLanguage(initialLanguage)
    }

    /** Advisory-only offline signal for the currently-selected voice — see class KDoc. */
    fun isLikelyOffline(): Boolean {
        val engine = tts ?: return false
        val voice = engine.voice ?: return false
        val features = voice.features ?: return true // no reported features: cannot confirm network use either way
        return TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS !in features
    }

    override fun synthesize(text: String, isAlert: Boolean): SynthesizedAudio {
        val engine = tts ?: error("AndroidTextToSpeechEngine used after unloadLanguage()/close() — no engine instance.")
        check(ready.get()) { lastError.get() ?: "TextToSpeech engine is not ready." }
        require(text.isNotBlank()) { "AndroidTextToSpeechEngine.synthesize called with blank text." }

        val outFile = File.createTempFile("itantra_tts_", ".wav", appContext.cacheDir)
        val utteranceId = "itantra-${System.nanoTime()}"
        val doneLatch = CountDownLatch(1)
        val synthesisFailed = AtomicBoolean(false)

        engine.setSpeechRate(if (isAlert) 1.0f else 1.0f)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                doneLatch.countDown()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                synthesisFailed.set(true)
                doneLatch.countDown()
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                synthesisFailed.set(true)
                doneLatch.countDown()
            }
        })

        val startTsMs = System.currentTimeMillis()
        val params = android.os.Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        val queued = engine.synthesizeToFile(text, params, outFile, utteranceId)
        if (queued != TextToSpeech.SUCCESS) {
            outFile.delete()
            error("TextToSpeech.synthesizeToFile failed to queue (result=$queued).")
        }

        val completedInTime = doneLatch.await(synthesisTimeoutMs, TimeUnit.MILLISECONDS)
        val firstAudioReadyTsMs = System.currentTimeMillis()

        if (!completedInTime) {
            outFile.delete()
            error("TextToSpeech synthesis timed out after ${synthesisTimeoutMs}ms for utterance '$utteranceId'.")
        }
        if (synthesisFailed.get()) {
            outFile.delete()
            error("TextToSpeech synthesis failed for utterance '$utteranceId'.")
        }

        try {
            val wav = readWavPcm16(outFile)
            return SynthesizedAudio(
                pcm16 = wav.samples,
                sampleRateHz = wav.sampleRateHz,
                isAlert = isAlert,
                firstAudioReadyTsMs = firstAudioReadyTsMs - startTsMs,
                durationMs = if (wav.sampleRateHz > 0) {
                    (wav.samples.size.toLong() * 1000L) / wav.sampleRateHz
                } else {
                    0L
                }
            )
        } finally {
            outFile.delete()
        }
    }

    override fun loadLanguage(language: Language) {
        check(ready.get()) { lastError.get() ?: "TextToSpeech engine is not ready." }
        applyLanguage(language)
        activeLanguage = language
    }

    override fun unloadLanguage() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready.set(false)
    }

    private fun applyLanguage(language: Language) {
        val engine = tts ?: return
        // Language.code (e.g. "hi", "ta") is a bare ISO 639-1 code; Locale needs a
        // BCP-47/ISO-3166 region to reliably match an installed voice on some engines, but
        // Android's TextToSpeech also accepts a language-only Locale and resolves it against
        // whatever regional voice is installed — no fabricated region guess is made here.
        val locale = Locale.forLanguageTag(language.code)
        val result = engine.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            lastError.set(
                "TextToSpeech does not support language '${language.code}' (${language.displayName}) " +
                    "on this device/engine (setLanguage result=$result)."
            )
        }
    }

    /** Minimal, dependency-free PCM WAV reader — just enough to read back what
     *  [TextToSpeech.synthesizeToFile] itself wrote (canonical 44-byte-header PCM WAV; if a
     *  future engine writes extended-format WAV with extra chunks, this walks chunk IDs rather
     *  than assuming a fixed header size, so it does not silently misread audio as noise). */
    private data class WavPcm16(val samples: ShortArray, val sampleRateHz: Int)

    private fun readWavPcm16(file: File): WavPcm16 {
        RandomAccessFile(file, "r").use { raf ->
            val riff = ByteArray(4).also { raf.readFully(it) }
            require(String(riff) == "RIFF") { "Not a RIFF/WAV file: ${file.name}" }
            raf.skipBytes(4) // overall chunk size, unused
            val wave = ByteArray(4).also { raf.readFully(it) }
            require(String(wave) == "WAVE") { "Not a WAVE file: ${file.name}" }

            var sampleRateHz = 0
            var bitsPerSample = 16
            var dataBytes: ByteArray? = null

            while (raf.filePointer < raf.length()) {
                val idBytes = ByteArray(4)
                if (raf.read(idBytes) < 4) break
                val chunkId = String(idBytes)
                val chunkSize = readLeInt(raf)
                when (chunkId) {
                    "fmt " -> {
                        val fmtStart = raf.filePointer
                        raf.skipBytes(2) // audioFormat
                        raf.skipBytes(2) // numChannels
                        sampleRateHz = readLeInt(raf)
                        raf.skipBytes(4) // byteRate
                        raf.skipBytes(2) // blockAlign
                        bitsPerSample = readLeShort(raf)
                        raf.seek(fmtStart + chunkSize)
                    }
                    "data" -> {
                        val bytes = ByteArray(chunkSize)
                        raf.readFully(bytes)
                        dataBytes = bytes
                    }
                    else -> raf.skipBytes(chunkSize)
                }
                if (chunkSize % 2 == 1 && raf.filePointer < raf.length()) raf.skipBytes(1) // WAV chunks are word-aligned
            }

            val bytes = dataBytes ?: ByteArray(0)
            require(bitsPerSample == 16) {
                "Unsupported WAV bit depth $bitsPerSample from TextToSpeech output — expected 16-bit PCM."
            }
            val samples = ShortArray(bytes.size / 2)
            for (i in samples.indices) {
                val lo = bytes[i * 2].toInt() and 0xFF
                val hi = bytes[i * 2 + 1].toInt()
                samples[i] = ((hi shl 8) or lo).toShort()
            }
            return WavPcm16(samples, sampleRateHz)
        }
    }

    private fun readLeInt(raf: RandomAccessFile): Int {
        val b = ByteArray(4).also { raf.readFully(it) }
        return (b[0].toInt() and 0xFF) or
            ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or
            ((b[3].toInt() and 0xFF) shl 24)
    }

    private fun readLeShort(raf: RandomAccessFile): Int {
        val b = ByteArray(2).also { raf.readFully(it) }
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
    }
}
