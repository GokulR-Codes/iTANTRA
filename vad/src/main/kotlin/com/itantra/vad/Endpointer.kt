package com.itantra.vad

import com.itantra.core.EndpointState

/**
 * INTEGRATION NOTE (iTantra-Master merge, see docs/FILE_CONFLICT_MATRIX.md row
 * "Endpointer / EndpointStateMachine"): the Gate1 git archive had a competing, less
 * feature-complete `core.EndpointStateMachine` implementing the same §19 FSM. This class
 * (originally from the Part III speech/language stack, where it was built and unit-tested
 * together with `SimulatedStreamingSttAdapter`) is the canonical engine — it has pre-roll
 * tracking, a speech-confirm debounce against false starts, and external-signal-driven
 * finalization (PTT release, max duration, transport queue pressure), none of which the
 * Gate1 version had. `core.EndpointStateMachine` has been deleted; do not resurrect it.
 * The states themselves ([EndpointState]) now live in `core.Contracts` as shared
 * vocabulary per the original architecture note (a division observing endpoint transitions
 * should not need to depend on this module's implementation details) — this file imports
 * that shared enum instead of declaring its own copy, which the original Part III file did.
 */

/** External signals besides raw VAD frames that can force finalization (§19, PTT/duration/queue). */
enum class ExternalSignal { PTT_RELEASED, MAX_DURATION_EXCEEDED, TRANSPORT_QUEUE_PRESSURE }

enum class FinalizeReason { STRONG_PAUSE, PTT_RELEASE, MAX_DURATION, QUEUE_PRESSURE }

data class EndpointerConfig(
    val preRollMs: Long = 250L,        // [TARGET] §11: 200-300ms pre-roll envelope
    val hangoverMs: Long = 500L,       // [TARGET] §19: 350-700ms starting envelope, tune per §34
    val speechConfirmFrames: Int = 3,  // consecutive speech-positive frames to leave MAYBE_SPEECH
    val frameDurationMs: Long = 30L
)

sealed class EndpointEvent {
    data class SpeechStarted(val preRollMs: Long) : EndpointEvent()
    data class Finalized(val reason: FinalizeReason) : EndpointEvent()
}

/**
 * Pure, Android-free finite-state endpointer. Feed it VAD frame verdicts (from TwoStageVad)
 * plus external signals; it emits EndpointEvents that the STT adapter and, later, the
 * transport layer (§24 priority = ALERT_BASE/NORMAL_BASE) act on.
 *
 * isFinal on RecognizedUtterance must only ever be set true in direct response to a
 * Finalized event emitted here — no other code path should decide an utterance is complete.
 */
class Endpointer(private val config: EndpointerConfig = EndpointerConfig()) {

    var state: EndpointState = EndpointState.IDLE
        private set

    private var consecutiveSpeechFrames = 0
    private var silenceMsSinceLastSpeech = 0L

    fun onVadFrame(isSpeech: Boolean): EndpointEvent? {
        val frameMs = config.frameDurationMs
        return when (state) {
            EndpointState.IDLE -> {
                if (isSpeech) {
                    consecutiveSpeechFrames = 1
                    if (consecutiveSpeechFrames >= config.speechConfirmFrames) {
                        state = EndpointState.SPEAKING
                        return EndpointEvent.SpeechStarted(preRollMs = config.preRollMs)
                    }
                    state = EndpointState.MAYBE_SPEECH
                }
                null
            }

            EndpointState.MAYBE_SPEECH -> {
                if (isSpeech) {
                    consecutiveSpeechFrames++
                    if (consecutiveSpeechFrames >= config.speechConfirmFrames) {
                        state = EndpointState.SPEAKING
                        return EndpointEvent.SpeechStarted(preRollMs = config.preRollMs)
                    }
                    null
                } else {
                    consecutiveSpeechFrames = 0 // false start
                    state = EndpointState.IDLE
                    null
                }
            }

            EndpointState.SPEAKING -> {
                if (isSpeech) {
                    silenceMsSinceLastSpeech = 0
                } else {
                    silenceMsSinceLastSpeech += frameMs
                    state = EndpointState.MAYBE_END
                }
                null
            }

            EndpointState.MAYBE_END -> {
                if (isSpeech) {
                    silenceMsSinceLastSpeech = 0
                    state = EndpointState.SPEAKING
                    null
                } else {
                    silenceMsSinceLastSpeech += frameMs
                    if (silenceMsSinceLastSpeech >= config.hangoverMs) {
                        state = EndpointState.FINALIZING
                        return EndpointEvent.Finalized(FinalizeReason.STRONG_PAUSE)
                    }
                    null
                }
            }

            EndpointState.FINALIZING -> null // caller must reset() before the next frame
        }
    }

    /** PTT release / max duration / queue pressure can force finalization from SPEAKING or MAYBE_END. */
    fun onExternalSignal(signal: ExternalSignal): EndpointEvent? {
        if (state != EndpointState.SPEAKING && state != EndpointState.MAYBE_END) return null
        val reason = when (signal) {
            ExternalSignal.PTT_RELEASED -> FinalizeReason.PTT_RELEASE
            ExternalSignal.MAX_DURATION_EXCEEDED -> FinalizeReason.MAX_DURATION
            ExternalSignal.TRANSPORT_QUEUE_PRESSURE -> FinalizeReason.QUEUE_PRESSURE
        }
        state = EndpointState.FINALIZING
        return EndpointEvent.Finalized(reason)
    }

    /** Call after consuming a Finalized event, before starting the next utterance. */
    fun reset() {
        state = EndpointState.IDLE
        consecutiveSpeechFrames = 0
        silenceMsSinceLastSpeech = 0
    }
}
