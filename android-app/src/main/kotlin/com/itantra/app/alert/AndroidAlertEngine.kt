package com.itantra.app.alert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.itantra.alert.AlertEngine
import com.itantra.audio.AudioPlayback
import com.itantra.core.SynthesizedAudio

/**
 * Real [AlertEngine] implementation (part of Module F/M's "alert-priority audio-focus
 * behavior" and "dedicated high-priority notification channel" requirements). HONEST SCOPE,
 * same caveat as every other class added this session: real `AudioManager`/
 * `NotificationManager` API usage, never compiled against the Android SDK or run on a device
 * here.
 *
 * DELIBERATELY NOT IMPLEMENTED, stated plainly rather than guessed at (§28's full DND/call
 * behavior matrix, referenced by `docs/PENDING_WORK_MASTER.md`'s original P1.2 scope, is
 * broader than this):
 *  - **Active-call detection.** Reading real call state needs `READ_PHONE_STATE` (a sensitive,
 *    dangerous permission) or `TelephonyManager`'s call-state listener — this app requests
 *    neither, per Module M's "avoid unnecessary permissions" instruction and because no
 *    calling feature exists to justify it. [wouldSuppressNormalPriority] therefore cannot
 *    detect an active call and does not claim to.
 *  - **Do Not Disturb interruption-filter nuance beyond the coarse on/off check.** Android
 *    exposes `NotificationManager.getCurrentInterruptionFilter()`, which this class does use,
 *    but the exact rule (which specific priority levels DND's current mode would actually
 *    suppress) depends on the user's own configured DND policy, which is not fully queryable.
 *    Treat [wouldSuppressNormalPriority]'s result as a conservative signal, not a precise
 *    simulation of Android's own DND decision.
 *
 * MODULE PLACEMENT: lives in `android-app`, not `alert-engine` (which already is an Android
 * library module and could technically host this), specifically to avoid adding a new
 * `alert-engine -> audio` module dependency edge for a single class — `android-app` already
 * depends on both. `alert-engine`'s own `AlertEngine` interface and `UnimplementedAlertEngine`
 * are unchanged; only the real implementation lives one module up.
 *
 * [deliverAlert] itself does no audio-focus *arbitration* logic of its own beyond requesting
 * and abandoning focus around playback — it delegates actual PCM output to the injected
 * [playback] ([com.itantra.audio.AndroidAudioPlayback] in production), keeping this class
 * about policy (focus + notification channel), not audio I/O, matching
 * [AlertEngine]'s own KDoc ("policy, not audio I/O").
 */
class AndroidAlertEngine(
    context: Context,
    private val playback: AudioPlayback
) : AlertEngine {

    companion object {
        const val ALERT_CHANNEL_ID = "itantra_emergency_alerts"
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var focusRequest: AudioFocusRequest? = null

    init {
        ensureNotificationChannel()
    }

    override fun deliverAlert(audio: SynthesizedAudio) {
        requestAlertFocus()
        try {
            playback.play(audio, isAlert = true)
        } finally {
            // Focus is abandoned here rather than after playback genuinely finishes because
            // AudioPlayback.play() is fire-and-forget by its own interface contract (no
            // completion callback) — a future AudioPlayback revision that exposes one should
            // move this into that completion callback instead of releasing focus eagerly.
            abandonAlertFocus()
        }
    }

    override fun wouldSuppressNormalPriority(): Boolean {
        val filter = notificationManager.currentInterruptionFilter
        // INTERRUPTION_FILTER_ALL is "everything allowed"; anything else (PRIORITY/NONE/ALARMS/
        // UNKNOWN) is treated conservatively as "may suppress a normal-priority utterance" —
        // see class KDoc for why this cannot be more precise than that without more permissions.
        return filter != NotificationManager.INTERRUPTION_FILTER_ALL
    }

    private fun requestAlertFocus() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(false)
            .build()
        focusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private fun abandonAlertFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val existing = notificationManager.getNotificationChannel(ALERT_CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "iTantra emergency alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "High-priority emergency messages received over iTantra"
            enableVibration(true)
            setBypassDnd(false) // do not silently override the user's own DND policy (§28)
        }
        notificationManager.createNotificationChannel(channel)
    }
}
