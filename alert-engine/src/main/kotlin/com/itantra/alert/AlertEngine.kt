package com.itantra.alert

import com.itantra.core.SynthesizedAudio

/**
 * Alert-priority policy: audio focus (interrupts music, ducks/pauses calls
 * per the DND/call matrix), a dedicated high-priority notification channel,
 * and never gets silently dropped by battery/Doze policy (§28). This is
 * policy, not audio I/O — it decides *how* an alert should be played and
 * surfaced, then hands the actual playback to `audio`'s AudioPlayback.
 *
 * Not implemented in Gate 1: requires AudioManager/NotificationManager
 * (Android SDK), Gate 6 work once the alert-priority path is exercised
 * end-to-end. See docs/KNOWN_ISSUES.md.
 */
interface AlertEngine {
    /** Requests audio focus appropriate for [audio]'s priority and plays it, per the §28 DND/call matrix. */
    fun deliverAlert(audio: SynthesizedAudio)

    /** True if the device's current state (DND, active call, Doze) would suppress or defer a normal-priority utterance. */
    fun wouldSuppressNormalPriority(): Boolean
}

class UnimplementedAlertEngine : AlertEngine {
    override fun deliverAlert(audio: SynthesizedAudio) {
        throw NotImplementedError(
            "UnimplementedAlertEngine.deliverAlert: AudioManager/NotificationManager wiring " +
                "and the §28 DND/call decision matrix are Gate 6 work."
        )
    }

    override fun wouldSuppressNormalPriority(): Boolean {
        throw NotImplementedError("UnimplementedAlertEngine.wouldSuppressNormalPriority: not implemented (Gate 6+).")
    }
}
