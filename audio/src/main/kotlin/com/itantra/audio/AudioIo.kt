package com.itantra.audio

import com.itantra.core.AudioFrame
import com.itantra.core.SynthesizedAudio

/**
 * Mic capture into a preallocated ring buffer, zero heap allocation on the
 * audio callback thread (§11, §28). Backed by AudioRecord/AAudio in the real
 * implementation — Gate 2 work. Division A (vad/stt) develops against this
 * interface using recorded/synthetic [AudioFrame]s, decoupled from a real
 * microphone existing yet (§14's dependency graph).
 */
interface AudioCapture {
    fun start(onFrame: (AudioFrame) -> Unit)
    fun stop()
    val isCapturing: Boolean
}

/** AudioTrack/Media3 playback of synthesized speech, under normal or alert audio-focus policy (§20, §28). */
interface AudioPlayback {
    fun play(audio: SynthesizedAudio, isAlert: Boolean)
    fun stop()
}

/**
 * Not implemented in Gate 1 — requires the Android SDK (AudioRecord) which
 * this module's build only gets from a real Android toolchain/CI, not this
 * repository's authoring environment. See docs/KNOWN_ISSUES.md.
 */
class UnimplementedAudioCapture : AudioCapture {
    override val isCapturing: Boolean = false
    override fun start(onFrame: (AudioFrame) -> Unit) {
        throw NotImplementedError("UnimplementedAudioCapture.start: AudioRecord/AAudio wiring is Gate 2 work.")
    }
    override fun stop() { /* no-op: never started */ }
}

class UnimplementedAudioPlayback : AudioPlayback {
    override fun play(audio: SynthesizedAudio, isAlert: Boolean) {
        throw NotImplementedError("UnimplementedAudioPlayback.play: AudioTrack wiring is Gate 2 work.")
    }
    override fun stop() { /* no-op: never started */ }
}
