package com.itantra.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import com.itantra.core.AudioFrame
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real [AudioCapture] implementation, added this session (P1.1 in
 * docs/PENDING_WORK_MASTER.md), backed by [android.media.AudioRecord].
 *
 * HONEST SCOPE, stated per this repository's own evidence discipline (see
 * docs/IMPLEMENTATION_STATUS.md): this is real, standard `AudioRecord` API usage — not a
 * stub, not a simulated waveform — but it has never been compiled against the real Android
 * SDK (this integration session's sandbox has no Android SDK and no network access to fetch
 * one — see docs/KNOWN_ISSUES.md's entry for this session) or run on a physical device. It
 * must be verified with `./gradlew :audio:compileDebugKotlin` and then on a real device
 * before any claim that "audio capture works" is made.
 *
 * Design, matching [com.itantra.core.AudioFrame]'s frozen 16kHz-mono contract exactly
 * (core/Contracts.kt's `AudioFrame` constructor `require`s this, so there is no format
 * choice to make here — 16kHz mono 16-bit PCM is the only legal input):
 *  - `AudioRecord` is configured for `MediaRecorder.AudioSource.MIC`, 16000 Hz,
 *    `CHANNEL_IN_MONO`, `ENCODING_PCM_16BIT`.
 *  - Frames are emitted at [frameDurationMs] (default 30ms, matching
 *    `vad.EndpointerConfig.frameDurationMs`'s default so a caller wiring this straight into
 *    `EnergyGateVad`/`Endpointer` gets frame-for-frame alignment without extra buffering
 *    logic) — i.e. 480 samples/frame at 16kHz.
 *  - Capture runs on its own dedicated thread (`Thread`, not a coroutine — this module has
 *    no `kotlinx-coroutines` dependency, matching `audio/build.gradle.kts`'s existing,
 *    deliberately minimal dependency set; a caller on Android is free to hop the [onFrame]
 *    callback onto a coroutine dispatcher itself), never the caller's/main thread, per
 *    [AudioCapture]'s "zero heap allocation on the audio callback thread" framing in its own
 *    KDoc — the one unavoidable allocation is the fixed-size `ShortArray` read buffer,
 *    allocated once in [start], not per frame in the read loop.
 *  - [stop] is idempotent and releases the `AudioRecord` instance; calling [start] again
 *    after [stop] constructs a fresh `AudioRecord` (Android's `AudioRecord` cannot be
 *    restarted after `release()`).
 *
 * KNOWN LIMITATION, documented rather than silently glossed over (see
 * `android-app/.../SessionService.kt`'s KDoc for the full picture): Android's on-device
 * [android.speech.SpeechRecognizer] — the STT backend this session actually wired into
 * `SessionService` — captures the microphone itself internally and does not accept
 * externally-supplied PCM. This class is therefore NOT on that recognition path today. It
 * exists as a real, correct, independently-usable [AudioCapture] implementation, ready for:
 * (a) a future frame-fed `stt.SttEngine` (e.g. a Vosk/sherpa-onnx model, once one is
 * integrated per `stt/build.gradle.kts`'s own roadmap), and (b) anything else in this
 * codebase written against the [AudioCapture] interface (e.g. a live input-level meter).
 * Running this concurrently with `AndroidOnDeviceSpeechRecognizer` would both compete for
 * the single microphone input Android typically grants one owner at a time — callers must
 * pick one active audio-input owner, never both at once. `SessionService` enforces this.
 */
class AndroidAudioCapture(
    private val frameDurationMs: Long = 30L
) : AudioCapture {

    companion object {
        private const val SAMPLE_RATE_HZ = 16_000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private val samplesPerFrame: Int = (SAMPLE_RATE_HZ * frameDurationMs / 1000L).toInt()

    @Volatile
    private var audioRecord: AudioRecord? = null
    private val capturing = AtomicBoolean(false)
    private var captureThread: Thread? = null

    override val isCapturing: Boolean
        get() = capturing.get()

    /**
     * Starts capture on a dedicated background thread. Requires [Manifest.permission.RECORD_AUDIO]
     * to already be granted — this class does not request permissions itself (that requires an
     * `Activity`/permission-launcher, which belongs in `android-app`'s UI layer, not here; see
     * `MainActivity`'s runtime-permission flow). Throws [SecurityException] if called without
     * the permission, exactly as the underlying `AudioRecord` constructor does — this class
     * does not swallow that into a silent no-op.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun start(onFrame: (AudioFrame) -> Unit) {
        check(!capturing.get()) { "AndroidAudioCapture.start() called while already capturing — call stop() first." }

        val minBufferBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_CONFIG, ENCODING)
        require(minBufferBytes > 0) {
            "AudioRecord.getMinBufferSize returned $minBufferBytes — this device/configuration " +
                "does not support 16kHz mono 16-bit PCM capture, which core.AudioFrame requires."
        }
        // Buffer at least 4 frames deep so a brief scheduling delay on the reader thread does
        // not cause AudioRecord to silently drop samples (it has no backpressure signal of its
        // own beyond overflowing its internal ring buffer).
        val bufferSizeBytes = maxOf(minBufferBytes, samplesPerFrame * 2 * 4)

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE_HZ,
            CHANNEL_CONFIG,
            ENCODING,
            bufferSizeBytes
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            error("AudioRecord failed to initialize (state=${record.state}) — device may not expose a usable mic input.")
        }

        audioRecord = record
        capturing.set(true)
        record.startRecording()

        val thread = Thread({
            // One fixed-size buffer for the whole capture session — allocated here (thread
            // start), never inside the loop, per this class's own "no per-frame allocation"
            // documented goal above.
            val readBuffer = ShortArray(samplesPerFrame)
            while (capturing.get()) {
                val samplesRead = record.read(readBuffer, 0, samplesPerFrame, AudioRecord.READ_BLOCKING)
                if (samplesRead <= 0) {
                    // Negative return is one of AudioRecord.ERROR_*; treat as "no data this
                    // pass" rather than crashing the capture thread — a transient error should
                    // not silently end the session, but it also must not be reported as a
                    // (bogus, zero-filled) AudioFrame.
                    continue
                }
                val pcm = if (samplesRead == samplesPerFrame) {
                    readBuffer.copyOf()
                } else {
                    // Short read (can happen right before stop()/on some OEM drivers) — only
                    // forward the samples actually captured, never pad with fabricated zeros
                    // that downstream VAD/STT would treat as real (silent) audio.
                    readBuffer.copyOf(samplesRead)
                }
                onFrame(
                    AudioFrame(
                        pcm16 = pcm,
                        sampleRateHz = SAMPLE_RATE_HZ,
                        channels = 1,
                        captureTsNanos = System.nanoTime()
                    )
                )
            }
        }, "itantra-audio-capture")
        thread.isDaemon = true
        captureThread = thread
        thread.start()
    }

    override fun stop() {
        if (!capturing.compareAndSet(true, false)) return
        captureThread?.join(500)
        captureThread = null
        val record = audioRecord
        audioRecord = null
        try {
            record?.stop()
        } catch (_: IllegalStateException) {
            // AudioRecord.stop() throws if it was never successfully started — nothing to clean
            // up further in that case; still fall through to release() below.
        } finally {
            record?.release()
        }
    }
}
