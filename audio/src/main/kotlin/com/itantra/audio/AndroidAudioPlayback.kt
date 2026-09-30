package com.itantra.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.itantra.core.SynthesizedAudio
import java.util.concurrent.atomic.AtomicReference

/**
 * Real [AudioPlayback] implementation (P1.2 in docs/PENDING_WORK_MASTER.md), backed by
 * [android.media.AudioTrack]. Same HONEST SCOPE caveat as [AndroidAudioCapture]: real,
 * standard API usage, never compiled against the real Android SDK or run on a physical
 * device in this integration session — verify with `./gradlew :audio:compileDebugKotlin`
 * and a real device before relying on it.
 *
 * [SynthesizedAudio.sampleRateHz] is used directly as the `AudioTrack` sample rate (unlike
 * capture, which is pinned to 16kHz by [com.itantra.core.AudioFrame]'s contract, synthesized
 * speech has no such fixed-rate requirement in `core.Contracts` — `AudioTrack` accepts
 * arbitrary supported output rates natively, so no resampling step belongs here).
 *
 * Alert vs. normal audio-attribute policy (§28's "alert-priority audio-focus behavior" — see
 * `alert-engine`'s [com.itantra.alert.AlertEngine] for the actual DND/focus *policy*
 * decision; this class is the audio *I/O*, one layer below that): [play] itself always
 * requests appropriate [AudioAttributes] for one-shot playback ([AudioAttributes.USAGE_ALARM]
 * when [SynthesizedAudio.isAlert], [AudioAttributes.USAGE_VOICE_COMMUNICATION] otherwise,
 * reflecting that a normal iTantra message is conceptually the far end of a voice call). It
 * does **not** itself call [android.media.AudioManager.requestAudioFocus] — focus arbitration
 * against other apps (music, calls) is exactly the alert-priority *policy* decision
 * `alert-engine`'s `AlertEngine` interface exists to own (see its KDoc); duplicating that
 * decision here would create two sources of truth for "should this audio interrupt what's
 * currently playing."
 */
class AndroidAudioPlayback : AudioPlayback {

    private val activeTrack = AtomicReference<AudioTrack?>(null)

    override fun play(audio: SynthesizedAudio, isAlert: Boolean) {
        stop() // never let two playbacks overlap on this instance

        val channelConfig = AudioFormat.CHANNEL_OUT_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBufferBytes = AudioTrack.getMinBufferSize(audio.sampleRateHz, channelConfig, encoding)
        require(minBufferBytes > 0) {
            "AudioTrack.getMinBufferSize returned $minBufferBytes for ${audio.sampleRateHz}Hz — " +
                "this device does not support that output rate for mono 16-bit PCM playback."
        }
        val bufferSizeBytes = maxOf(minBufferBytes, audio.pcm16.size * 2)

        val attributes = AudioAttributes.Builder()
            .setUsage(if (isAlert) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(audio.sampleRateHz)
            .setChannelMask(channelConfig)
            .setEncoding(encoding)
            .build()

        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSizeBytes)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            error("AudioTrack failed to initialize (state=${track.state}) for ${audio.sampleRateHz}Hz playback.")
        }

        activeTrack.set(track)
        track.write(audio.pcm16, 0, audio.pcm16.size)
        track.setNotificationMarkerPosition(audio.pcm16.size)
        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(t: AudioTrack) {
                // Playback of this exact clip finished naturally — release rather than leaving
                // a STATE_INITIALIZED-but-idle AudioTrack (and its underlying audio resources)
                // held until the next play()/stop() call.
                if (activeTrack.compareAndSet(t, null)) {
                    t.release()
                }
            }
            override fun onPeriodicNotification(t: AudioTrack) = Unit
        })
        track.play()
    }

    override fun stop() {
        val track = activeTrack.getAndSet(null) ?: return
        try {
            track.pause()
            track.flush()
            track.stop()
        } catch (_: IllegalStateException) {
            // Already stopped/uninitialized — nothing further to clean up.
        } finally {
            track.release()
        }
    }
}
