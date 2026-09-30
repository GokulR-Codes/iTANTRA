package com.itantra.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.itantra.alert.AlertEngine
import com.itantra.alert.EmergencyAnalysis
import com.itantra.app.alert.AndroidAlertEngine
import com.itantra.app.ble.BleGattClientTransport
import com.itantra.app.ble.BleGattServerTransport
import com.itantra.app.ble.BlePermissions
import com.itantra.app.stt.AndroidOnDeviceSpeechRecognizer
import com.itantra.app.tts.AndroidTextToSpeechEngine
import com.itantra.audio.AndroidAudioPlayback
import com.itantra.core.ReceivedUtterance
import com.itantra.orchestrator.MessageOrchestrator
import com.itantra.orchestrator.PriorityMapping
import com.itantra.protocol.PacketTransport
import com.itantra.wifi.WifiPacketTransportAdapter
import com.itantra.wifi.WifiTransportConfig
import com.itantra.wifi.WifiUdpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress

/**
 * The central session-orchestration service (Module A). Foreground service so Android does
 * not tear the session down mid-conversation when the app backgrounds (`AndroidManifest.xml`'s
 * `foregroundServiceType="microphone|connectedDevice"`).
 *
 * Wires together, all real components (see each class's own KDoc for its individual honest
 * scope/verification-status caveat — this class does not repeat them all, only the pipeline
 * decisions specific to wiring them together):
 *  - [AndroidOnDeviceSpeechRecognizer] (`stt`) -> recognized text
 *  - [MessageOrchestrator] (`orchestrator`) -> HEMIM classify, semantic-encode, fragment,
 *    transmit (outbound) / reassemble, decode, re-classify (inbound) — this repository already
 *    did all of that real wiring in a prior session; `SessionService`'s job is only to give it
 *    a real [PacketTransport] and real text to send.
 *  - [WifiPacketTransportAdapter]/[BleGattClientTransport]/[BleGattServerTransport] -> the
 *    `PacketTransport` the orchestrator drives.
 *  - [AndroidTextToSpeechEngine] (`tts`) + [AndroidAudioPlayback]/[AndroidAlertEngine] (`audio`)
 *    -> speaking a received message.
 *
 * ARCHITECTURE DECISION, recorded here and in docs/DECISIONS.md: [AndroidOnDeviceSpeechRecognizer]
 * captures the microphone itself (see its own KDoc for exactly why it cannot be wired behind
 * the frame-fed `stt.SttEngine` interface). This class therefore does NOT also run
 * `AndroidAudioCapture` for the recognition path — running both at once would fight over the
 * one microphone input. `AndroidAudioCapture` remains available, real, and correct (see its
 * own KDoc) for a future frame-fed `SttEngine`, wired in as an alternative path the moment one
 * exists — not built here because none does yet (`stt.UnimplementedSttEngine`/
 * `SimulationBackedSttEngine` are explicitly not real recognition, per their own KDoc, and
 * must not be wired into a path presented to the user as "speech recognition").
 *
 * NO TRANSLATION (P3.1, still NOT IMPLEMENTED anywhere in this repository — see
 * docs/PENDING_WORK_MASTER.md): the language a message is spoken back in, on the receiving
 * device, is whatever language it was sent in. There is no cross-language step. The UI must
 * make this explicit rather than implying Device B can be configured for a different language
 * than Device A and still understand it.
 *
 * CONCURRENCY: [stateMachine] ([SessionStateMachine]) is the single source of truth for what
 * the session is currently doing; every method below transitions through it rather than
 * mutating flags directly, so two overlapping operations (e.g. a second `startListening()`
 * while already listening, or an inbound message arriving mid-transmit) fail predictably
 * instead of corrupting shared state. Inbound messages that arrive while the session is busy
 * transmitting/listening are queued ([pendingInbound]) and drained once the session returns to
 * [SessionPhase.READY], rather than being processed concurrently with an active PTT capture
 * (which would also mean playing TTS output back while the mic is live — a real
 * feedback/self-transcription hazard, not merely a state-machine nicety).
 *
 * HONEST SCOPE: real wiring of real, previously-independent components — not a stub, not
 * simulated. Never compiled against the real Android SDK (no Android SDK/network access in
 * this integration session's sandbox — see docs/KNOWN_ISSUES.md's entry for this session) or
 * run on a device. Verify with `./gradlew :android-app:compileDebugKotlin` and a real
 * two-device test before relying on any part of this pipeline.
 */
class SessionService : Service() {

    companion object {
        private const val SESSION_CHANNEL_ID = "itantra_session"
        private const val SESSION_NOTIFICATION_ID = 1001
    }

    inner class LocalBinder : Binder() {
        fun service(): SessionService = this@SessionService
    }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Default)

    val stateMachine = SessionStateMachine()

    private val _uiState = MutableStateFlow(SessionUiState())
    val uiState: StateFlow<SessionUiState> = _uiState

    private var config: SessionConfig? = null
    private var orchestrator: MessageOrchestrator? = null
    private var transport: PacketTransport? = null
    private var bleClient: BleGattClientTransport? = null
    private var bleServer: BleGattServerTransport? = null

    private var speechRecognizer: AndroidOnDeviceSpeechRecognizer? = null
    private var ttsEngine: AndroidTextToSpeechEngine? = null
    private val audioPlayback = AndroidAudioPlayback()
    private var alertEngine: AlertEngine? = null

    private var retryJob: Job? = null
    private val pendingInbound = mutableListOf<Pair<ReceivedUtterance, EmergencyAnalysis>>()

    override fun onCreate() {
        super.onCreate()
        ensureSessionNotificationChannel()
    }

    override fun onDestroy() {
        stopSession()
        serviceJob.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------------------------
    // Session lifecycle
    // ------------------------------------------------------------------------------------

    /**
     * Starts one session. Rejects (sets [SessionUiState.lastError], no state change) rather
     * than silently creating a second concurrent session if one is already active — the
     * "prevent duplicate session creation" requirement, enforced via [SessionStateMachine]
     * itself refusing the `IDLE -> INITIALIZING` transition from any other phase.
     */
    fun startSession(newConfig: SessionConfig) {
        if (!stateMachine.canTransitionTo(SessionPhase.INITIALIZING)) {
            publishError("A session is already active (phase=${stateMachine.phase}) — stop it before starting a new one.")
            return
        }
        stateMachine.transition(SessionPhase.INITIALIZING)
        _uiState.update { SessionUiState(phase = SessionPhase.INITIALIZING, language = newConfig.language) }
        config = newConfig

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            failInit("RECORD_AUDIO permission is not granted — grant it and try again.")
            return
        }

        try {
            startForegroundNotification()
            transport = buildTransport(newConfig)
            orchestrator = MessageOrchestrator(
                sessionId = newConfig.sessionId,
                senderShortId = newConfig.senderShortId,
                transport = transport!!
            ).also { orch ->
                orch.onMessageReceived = { utterance, analysis ->
                    serviceScope.launch { handleIncomingMessage(utterance, analysis) }
                }
                orch.onDecodeError = { e ->
                    publishError("Failed to decode an inbound packet: ${e.message}")
                }
            }

            val recognizer = AndroidOnDeviceSpeechRecognizer(applicationContext)
            speechRecognizer = recognizer
            val tts = AndroidTextToSpeechEngine(applicationContext, newConfig.language)
            ttsEngine = tts
            alertEngine = AndroidAlertEngine(applicationContext, audioPlayback)

            _uiState.update {
                it.copy(
                    sttAvailable = recognizer.isAvailable(),
                    sttOfflineVerifiable = recognizer.canVerifyOfflineCapability,
                    transportKindLabel = transport?.kind?.name
                )
            }

            stateMachine.transition(SessionPhase.READY)
            _uiState.update { it.copy(phase = SessionPhase.READY) }
            startRetryLoop()
        } catch (e: Exception) {
            failInit("Failed to start session: ${e.message}")
        }
    }

    private fun failInit(message: String) {
        if (stateMachine.canTransitionTo(SessionPhase.ERROR)) stateMachine.transition(SessionPhase.ERROR)
        _uiState.update { it.copy(phase = SessionPhase.ERROR, lastError = message) }
    }

    fun stopSession() {
        if (stateMachine.phase == SessionPhase.STOPPED || stateMachine.phase == SessionPhase.STOPPING) return
        stateMachine.transition(SessionPhase.STOPPING)
        _uiState.update { it.copy(phase = SessionPhase.STOPPING) }

        retryJob?.cancel()
        retryJob = null

        speechRecognizer?.destroy()
        speechRecognizer = null
        ttsEngine?.unloadLanguage()
        ttsEngine = null
        audioPlayback.stop()
        alertEngine = null

        try {
            bleClient?.close()
        } catch (_: Exception) { /* best-effort teardown */ }
        try {
            bleServer?.close()
        } catch (_: Exception) { /* best-effort teardown */ }
        bleClient = null
        bleServer = null
        try {
            orchestrator?.close()
        } catch (_: Exception) { /* best-effort teardown */ }
        orchestrator = null
        transport = null
        pendingInbound.clear()

        stateMachine.transition(SessionPhase.STOPPED)
        _uiState.update { it.copy(phase = SessionPhase.STOPPED) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)

        // A fresh SessionStateMachine is required for the next session (it refuses to leave
        // STOPPED — see its own KDoc). This Service instance stays alive so the UI can still
        // observe the final STOPPED state, but will not accept another startSession() call;
        // the caller (MainActivity) restarts/rebinds the service for a new session rather than
        // this method silently "resetting" a terminal state machine in place, which would
        // undermine the whole point of STOPPED being terminal.
    }

    // ------------------------------------------------------------------------------------
    // Outbound (push-to-talk)
    // ------------------------------------------------------------------------------------

    fun startListening() {
        val cfg = config
        val recognizer = speechRecognizer
        if (cfg == null || recognizer == null || !stateMachine.canTransitionTo(SessionPhase.LISTENING)) {
            publishError("Cannot start listening in phase ${stateMachine.phase}.")
            return
        }
        if (!recognizer.isAvailable()) {
            publishError("No speech recognition service is available on this device.")
            return
        }
        stateMachine.transition(SessionPhase.LISTENING)
        _uiState.update { it.copy(phase = SessionPhase.LISTENING, partialTranscript = "", lastError = null) }

        recognizer.start(
            language = cfg.language,
            onPartial = { text -> _uiState.update { it.copy(partialTranscript = text) } },
            onFinal = { text, _ -> handleRecognizedFinal(text) },
            onError = { message -> handleRecognitionError(message) }
        )
    }

    /** Signals end-of-utterance (user released the push-to-talk control); the recognizer
     *  finalizes the in-flight utterance normally — see [AndroidOnDeviceSpeechRecognizer.stop]. */
    fun releasePushToTalk() {
        speechRecognizer?.stop()
    }

    /** Aborts the current listening attempt with no message sent. */
    fun cancelListening() {
        speechRecognizer?.cancel()
        if (stateMachine.phase == SessionPhase.LISTENING) {
            stateMachine.transition(SessionPhase.READY)
            _uiState.update { it.copy(phase = SessionPhase.READY, partialTranscript = "") }
            drainPendingInboundIfAny()
        }
    }

    private fun handleRecognizedFinal(text: String) {
        if (text.isBlank()) {
            handleRecognitionError("Empty transcript — nothing sent.")
            return
        }
        val orch = orchestrator
        val cfg = config
        if (orch == null || cfg == null || !stateMachine.canTransitionTo(SessionPhase.SENDING)) {
            publishError("Cannot send: session not ready (phase=${stateMachine.phase}).")
            return
        }
        stateMachine.transition(SessionPhase.SENDING)
        _uiState.update { it.copy(phase = SessionPhase.SENDING, partialTranscript = "") }

        serviceScope.launch {
            try {
                val envelope = orch.sendText(text, cfg.language.code)
                _uiState.update {
                    it.copy(
                        lastSentText = envelope.emergencyAnalysis.originalText,
                        lastSentPriority = envelope.emergencyAnalysis.priority,
                        lastSentCategory = envelope.emergencyAnalysis.category,
                        lastDeliveryStatus = orch.deliveryStatus(envelope.messageId)
                    )
                }
                stateMachine.transition(SessionPhase.READY)
                _uiState.update { it.copy(phase = SessionPhase.READY) }
            } catch (e: Exception) {
                publishErrorAndRecover("Failed to send message: ${e.message}")
            }
            drainPendingInboundIfAny()
        }
    }

    private fun handleRecognitionError(message: String) {
        if (stateMachine.phase != SessionPhase.LISTENING) return
        stateMachine.transition(SessionPhase.ERROR)
        _uiState.update { it.copy(phase = SessionPhase.ERROR, lastError = message, partialTranscript = "") }
        stateMachine.transition(SessionPhase.READY)
        _uiState.update { it.copy(phase = SessionPhase.READY) }
        drainPendingInboundIfAny()
    }

    // ------------------------------------------------------------------------------------
    // Inbound (receive -> speak)
    // ------------------------------------------------------------------------------------

    private suspend fun handleIncomingMessage(utterance: ReceivedUtterance, analysis: EmergencyAnalysis) {
        if (stateMachine.phase == SessionPhase.LISTENING || stateMachine.phase == SessionPhase.SENDING) {
            pendingInbound.add(utterance to analysis)
            return
        }
        if (!stateMachine.canTransitionTo(SessionPhase.RECEIVING)) {
            // Session not in a state that can receive right now (initializing/stopping/etc) —
            // drop safely rather than crash; mirrors the orchestrator's own "never guess, never
            // crash on malformed input" posture, applied to session-lifecycle timing instead.
            return
        }
        stateMachine.transition(SessionPhase.RECEIVING)
        _uiState.update {
            it.copy(
                phase = SessionPhase.RECEIVING,
                lastReceivedText = utterance.text,
                lastReceivedPriority = analysis.priority,
                lastReceivedRequiresReview = analysis.requiresReview
            )
        }

        val isAlert = PriorityMapping.isAlertFlag(analysis.priority)
        try {
            val tts = ttsEngine ?: error("TTS engine not initialized.")
            stateMachine.transition(SessionPhase.SPEAKING)
            _uiState.update { it.copy(phase = SessionPhase.SPEAKING) }
            val synthesized = withContext(Dispatchers.IO) { tts.synthesize(utterance.text, isAlert) }
            val engine = alertEngine
            if (isAlert && engine != null) {
                engine.deliverAlert(synthesized)
            } else {
                audioPlayback.play(synthesized, isAlert = isAlert)
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(lastError = "Playback failed: ${e.message}") }
        } finally {
            stateMachine.transition(SessionPhase.READY)
            _uiState.update { it.copy(phase = SessionPhase.READY) }
            drainPendingInboundIfAny()
        }
    }

    private fun drainPendingInboundIfAny() {
        if (pendingInbound.isEmpty()) return
        if (stateMachine.phase != SessionPhase.READY) return
        val (utterance, analysis) = pendingInbound.removeAt(0)
        serviceScope.launch { handleIncomingMessage(utterance, analysis) }
    }

    // ------------------------------------------------------------------------------------
    // Transport construction / BLE device selection
    // ------------------------------------------------------------------------------------

    // Suppresses Android Lint's MissingPermission check on the BleGattClientTransport/
    // BleGattServerTransport calls below: this function already performs the equivalent
    // runtime check via BlePermissions.missing*Permissions() and throws before reaching them
    // if anything is missing, which lint (a static analysis over call sites) cannot see.
    @Suppress("MissingPermission")
    private fun buildTransport(cfg: SessionConfig): PacketTransport = when (val sel = cfg.transport) {
        is TransportSelection.Wifi -> {
            val udp = WifiUdpTransport(WifiTransportConfig(), cfg.sessionId, cfg.senderShortId)
            udp.open(sel.localPort)
            udp.connectTo(InetAddress.getByName(sel.remoteHost), sel.remotePort)
            WifiPacketTransportAdapter(udp)
        }
        TransportSelection.BleCentral -> {
            val missing = BlePermissions.missingCentralPermissions(applicationContext)
            if (missing.isNotEmpty()) error("Missing BLE permissions: ${missing.joinToString()}")
            val client = BleGattClientTransport(applicationContext)
            client.onDeviceDiscovered = { device, rssi ->
                val discovered = DiscoveredBleDevice(
                    device = device,
                    address = device.address,
                    name = deviceNameOrNull(device),
                    rssiDbm = rssi
                )
                _uiState.update { state ->
                    val withoutDup = state.discoveredBleDevices.filterNot { it.address == discovered.address }
                    state.copy(discoveredBleDevices = (withoutDup + discovered).sortedByDescending { it.rssiDbm })
                }
            }
            bleClient = client
            client.startScan()
            client
        }
        TransportSelection.BlePeripheral -> {
            val missing = BlePermissions.missingPeripheralPermissions(applicationContext)
            if (missing.isNotEmpty()) error("Missing BLE permissions: ${missing.joinToString()}")
            val server = BleGattServerTransport(applicationContext)
            bleServer = server
            server.start()
            server
        }
    }

    /** Called by the UI (Connection screen) once the user picks a discovered peer. */
    @Suppress("MissingPermission") // BLUETOOTH_CONNECT already verified in buildTransport() before scanning ever started
    fun connectToBleDevice(address: String) {
        val device = _uiState.value.discoveredBleDevices.find { it.address == address }?.device
        if (device == null) {
            publishError("No discovered BLE device with address $address.")
            return
        }
        try {
            bleClient?.connectTo(device)
        } catch (e: Exception) {
            publishError("Failed to connect to $address: ${e.message}")
        }
    }

    @Suppress("MissingPermission") // caught as SecurityException below if BLUETOOTH_CONNECT is somehow missing at call time
    private fun deviceNameOrNull(device: BluetoothDevice): String? = try {
        device.name
    } catch (_: SecurityException) {
        null // BLUETOOTH_CONNECT not granted for .name on some OS versions — fail soft, not loud
    }

    // ------------------------------------------------------------------------------------
    // Reliability: periodic retry sweep (Module A / Module I — retries/backoff live on the
    // caller's own IO loop per MessageOrchestrator's own KDoc; this is that loop)
    // ------------------------------------------------------------------------------------

    private fun startRetryLoop() {
        retryJob?.cancel()
        retryJob = serviceScope.launch {
            while (true) {
                delay(2_000L)
                try {
                    orchestrator?.retryPending()
                    transport?.let { t ->
                        _uiState.update { it.copy(transportConnected = t.currentStats().connected) }
                    }
                } catch (e: Exception) {
                    publishError("Retry sweep failed: ${e.message}")
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------
    // Foreground notification / misc
    // ------------------------------------------------------------------------------------

    private fun ensureSessionNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(SESSION_CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                SESSION_CHANNEL_ID,
                "iTantra session",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shown while an iTantra communication session is active." }
        )
    }

    private fun startForegroundNotification() {
        val notification: Notification = NotificationCompat.Builder(this, SESSION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.session_notification_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this,
            SESSION_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
    }

    private fun publishError(message: String) {
        _uiState.update { it.copy(lastError = message) }
    }

    /** Same as [publishError] but also recovers the state machine back to READY via ERROR,
     *  for failures that happen mid-operation (as opposed to failures that reject a request
     *  before any transition was made, which [publishError] alone covers). */
    private fun publishErrorAndRecover(message: String) {
        if (stateMachine.canTransitionTo(SessionPhase.ERROR)) {
            stateMachine.transition(SessionPhase.ERROR)
            _uiState.update { it.copy(phase = SessionPhase.ERROR, lastError = message) }
            stateMachine.transition(SessionPhase.READY)
            _uiState.update { it.copy(phase = SessionPhase.READY) }
        } else {
            publishError(message)
        }
    }
}
