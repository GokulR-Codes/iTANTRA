package com.itantra.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.itantra.core.Language
import kotlinx.coroutines.launch

/**
 * Main user interface for iTantra.
 *
 * This Activity provides:
 * - Language selection
 * - Wi-Fi UDP transport configuration
 * - Bluetooth LE central/peripheral selection
 * - Session start/stop controls
 * - Push-to-talk interaction
 * - Speech-recognition status
 * - Sent/received message information
 * - Delivery and diagnostic information
 *
 * The UI is intentionally created programmatically so that it does not depend
 * on XML layout resources.
 */
class MainActivity : AppCompatActivity() {

    // -------------------------------------------------------------------------
    // Service connection
    // -------------------------------------------------------------------------

    private var sessionService: SessionService? = null
    private var bound = false

    private val connection = object : ServiceConnection {

        override fun onServiceConnected(
            name: ComponentName?,
            service: IBinder?
        ) {
            val binder = service as? SessionService.LocalBinder ?: return

            sessionService = binder.service()
            bound = true

            observeSessionState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            sessionService = null
            bound = false
        }
    }

    // -------------------------------------------------------------------------
    // Views
    // -------------------------------------------------------------------------

    private lateinit var languageSpinner: Spinner

    private lateinit var transportGroup: RadioGroup

    private lateinit var wifiFields: LinearLayout
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText

    private lateinit var startStopButton: Button

    private lateinit var statusText: TextView
    private lateinit var sttStatusText: TextView

    private lateinit var bleDeviceList: ListView

    private lateinit var pttButton: Button

    private lateinit var partialText: TextView
    private lateinit var sentText: TextView
    private lateinit var receivedText: TextView
    private lateinit var errorText: TextView

    private var bleDeviceAddresses: List<String> = emptyList()

    // -------------------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------------------

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            // SessionService performs its own permission checks before
            // accessing microphone/Bluetooth functionality.
            //
            // The Activity intentionally does not duplicate permission-specific
            // branching here.
        }

    // -------------------------------------------------------------------------
    // Activity lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(buildUi())

        requestNeededPermissions()

        bindService(
            Intent(this, SessionService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(connection)
            bound = false
        }

        sessionService = null

        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------------------

    private fun requestNeededPermissions() {

        val toRequest = mutableListOf(
            Manifest.permission.RECORD_AUDIO
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            toRequest += Manifest.permission.POST_NOTIFICATIONS
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            toRequest += Manifest.permission.BLUETOOTH_SCAN
            toRequest += Manifest.permission.BLUETOOTH_CONNECT
            toRequest += Manifest.permission.BLUETOOTH_ADVERTISE
        }

        val missing = toRequest.filter { permission ->
            ContextCompat.checkSelfPermission(
                this,
                permission
            ) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    // -------------------------------------------------------------------------
    // Session state
    // -------------------------------------------------------------------------

    private fun observeSessionState() {

        lifecycleScope.launch {

            sessionService?.uiState?.collect { state ->
                render(state)
            }
        }
    }

    // -------------------------------------------------------------------------
    // UI construction
    // -------------------------------------------------------------------------

    private fun buildUi(): View {

        val pad = dp(16)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                pad,
                pad,
                pad,
                pad
            )
        }

        // ---------------------------------------------------------------------
        // Application title
        // ---------------------------------------------------------------------

        root.addView(
            sectionTitle("iTantra")
        )

        root.addView(
            TextView(this).apply {
                text =
                    "Offline-first emergency voice communication. " +
                            "No translation: the peer device hears your message " +
                            "in the same language you send it in."

                setPadding(
                    0,
                    0,
                    0,
                    dp(12)
                )
            }
        )

        // ---------------------------------------------------------------------
        // Language
        // ---------------------------------------------------------------------

        root.addView(
            sectionTitle("Language")
        )

        languageSpinner = Spinner(this).apply {

            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                Language.entries.map {
                    "${it.displayName} (${it.code})"
                }
            )

            val englishIndex =
                Language.entries.indexOf(Language.EN)

            if (englishIndex >= 0) {
                setSelection(englishIndex)
            }
        }

        root.addView(languageSpinner)

        // ---------------------------------------------------------------------
        // Connection / transport
        // ---------------------------------------------------------------------

        root.addView(
            sectionTitle("Connection")
        )

        transportGroup = RadioGroup(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val wifiButton = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Wi-Fi (UDP)"
            isChecked = true
        }

        val bleCentralButton = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Bluetooth LE — scan for peer (central)"
        }

        val blePeripheralButton = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Bluetooth LE — wait for peer (peripheral)"
        }

        transportGroup.addView(wifiButton)
        transportGroup.addView(bleCentralButton)
        transportGroup.addView(blePeripheralButton)

        root.addView(transportGroup)

        // ---------------------------------------------------------------------
        // Wi-Fi configuration
        // ---------------------------------------------------------------------

        wifiFields = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        hostInput = EditText(this).apply {

            hint = "Peer IP address"

            inputType =
                InputType.TYPE_CLASS_TEXT

            layoutParams =
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    2f
                )
        }

        portInput = EditText(this).apply {

            hint = "Port"

            inputType =
                InputType.TYPE_CLASS_NUMBER

            setText("40000")

            layoutParams =
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
        }

        wifiFields.addView(hostInput)
        wifiFields.addView(portInput)

        root.addView(wifiFields)

        // ---------------------------------------------------------------------
        // BLE device list
        // ---------------------------------------------------------------------

        bleDeviceList = ListView(this).apply {

            visibility = View.GONE

            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(120)
                )
        }

        bleDeviceList.onItemClickListener =
            AdapterView.OnItemClickListener { _, _, position, _ ->

                bleDeviceAddresses
                    .getOrNull(position)
                    ?.let { address ->

                        sessionService?.connectToBleDevice(
                            address
                        )
                    }
            }

        root.addView(bleDeviceList)

        // ---------------------------------------------------------------------
        // Transport selection behaviour
        // ---------------------------------------------------------------------

        transportGroup.setOnCheckedChangeListener { group, checkedId ->

            val selectedButton =
                group.findViewById<RadioButton>(checkedId)

            val wifiSelected =
                selectedButton == wifiButton

            wifiFields.visibility =
                if (wifiSelected) {
                    View.VISIBLE
                } else {
                    View.GONE
                }

            bleDeviceList.visibility =
                if (wifiSelected) {
                    View.GONE
                } else {
                    View.VISIBLE
                }
        }

        // ---------------------------------------------------------------------
        // Start / stop session
        // ---------------------------------------------------------------------

        startStopButton = Button(this).apply {
            text = "Start session"
        }

        startStopButton.setOnClickListener {
            onStartStopClicked()
        }

        root.addView(startStopButton)

        // ---------------------------------------------------------------------
        // Session status
        // ---------------------------------------------------------------------

        statusText = TextView(this).apply {
            setPadding(
                0,
                dp(8),
                0,
                0
            )
        }

        root.addView(statusText)

        sttStatusText = TextView(this)

        root.addView(sttStatusText)

        // ---------------------------------------------------------------------
        // Push-to-talk
        // ---------------------------------------------------------------------

        root.addView(
            sectionTitle("Push-to-talk")
        )

        pttButton = Button(this).apply {
            text = "Hold to talk"
            isEnabled = false
        }

        pttButton.setOnTouchListener { view, event ->

            when (event.actionMasked) {

                MotionEvent.ACTION_DOWN -> {
                    sessionService?.startListening()
                    true
                }

                MotionEvent.ACTION_UP -> {
                    sessionService?.releasePushToTalk()
                    view.performClick()
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    sessionService?.releasePushToTalk()
                    true
                }

                else -> false
            }
        }

        root.addView(pttButton)

        // ---------------------------------------------------------------------
        // Partial transcript
        // ---------------------------------------------------------------------

        partialText = TextView(this).apply {
            setPadding(
                0,
                dp(4),
                0,
                dp(8)
            )
        }

        root.addView(partialText)

        // ---------------------------------------------------------------------
        // Sent messages
        // ---------------------------------------------------------------------

        root.addView(
            sectionTitle("Sent")
        )

        sentText = TextView(this)

        root.addView(sentText)

        // ---------------------------------------------------------------------
        // Received messages
        // ---------------------------------------------------------------------

        root.addView(
            sectionTitle("Received")
        )

        receivedText = TextView(this)

        root.addView(receivedText)

        // ---------------------------------------------------------------------
        // Diagnostics
        // ---------------------------------------------------------------------

        root.addView(
            sectionTitle("Diagnostics")
        )

        errorText = TextView(this).apply {
            setTextColor(
                android.graphics.Color.RED
            )
        }

        root.addView(errorText)

        // ---------------------------------------------------------------------
        // Scroll container
        // ---------------------------------------------------------------------

        return ScrollView(this).apply {
            addView(root)
        }
    }

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------

    private fun sectionTitle(
        text: String
    ): TextView {

        return TextView(this).apply {

            this.text = text

            textSize = 16f

            setPadding(
                0,
                dp(12),
                0,
                dp(4)
            )

            setTypeface(
                typeface,
                android.graphics.Typeface.BOLD
            )

            gravity = Gravity.START
        }
    }

    private fun dp(value: Int): Int {

        return (
                value *
                        resources.displayMetrics.density
                ).toInt()
    }

    // -------------------------------------------------------------------------
    // Session actions
    // -------------------------------------------------------------------------

    private fun onStartStopClicked() {

        val service =
            sessionService ?: return

        val currentPhase =
            service.stateMachine.phase

        val canStart =
            currentPhase == SessionPhase.IDLE ||
                    currentPhase == SessionPhase.STOPPED

        if (canStart) {

            val language =
                Language.entries[
                    languageSpinner.selectedItemPosition
                ]

            val selectedRadioButton =
                transportGroup.findViewById<RadioButton>(
                    transportGroup.checkedRadioButtonId
                )

            val transportIndex =
                transportGroup.indexOfChild(
                    selectedRadioButton
                )

            val transportSelection =
                when (transportIndex) {

                    0 -> {

                        val host =
                            hostInput.text
                                .toString()
                                .trim()

                        val port =
                            portInput.text
                                .toString()
                                .trim()
                                .toIntOrNull()

                        if (
                            host.isEmpty() ||
                            port == null
                        ) {

                            errorText.text =
                                "Enter a valid peer IP address and port for Wi-Fi."

                            return
                        }

                        TransportSelection.Wifi(
                            remoteHost = host,
                            remotePort = port
                        )
                    }

                    1 -> {
                        TransportSelection.BleCentral
                    }

                    else -> {
                        TransportSelection.BlePeripheral
                    }
                }

            service.startSession(
                SessionConfig(
                    language = language,
                    transport = transportSelection
                )
            )

        } else {

            service.stopSession()
        }
    }

    // -------------------------------------------------------------------------
    // State rendering
    // -------------------------------------------------------------------------

    private fun render(
        state: SessionUiState
    ) {

        // ---------------------------------------------------------------------
        // Session status
        // ---------------------------------------------------------------------

        statusText.text =
            buildString {

                append("Session: ")
                append(state.phase)

                state.transportKindLabel?.let { transport ->

                    append(" · transport=")
                    append(transport)

                    append(
                        " connected="
                    )

                    append(
                        state.transportConnected
                    )
                }
            }

        // ---------------------------------------------------------------------
        // Speech recognition status
        // ---------------------------------------------------------------------

        sttStatusText.text =
            if (state.sttAvailable) {

                if (state.sttOfflineVerifiable) {

                    "On-device speech recognition: available"

                } else {

                    "Speech recognition available " +
                            "(offline capability not verifiable " +
                            "on this Android version)"
                }

            } else {

                "No speech recognition service available " +
                        "on this device"
            }

        // ---------------------------------------------------------------------
        // Session controls
        // ---------------------------------------------------------------------

        val sessionLive =
            state.phase != SessionPhase.IDLE &&
                    state.phase != SessionPhase.STOPPED

        startStopButton.text =
            if (sessionLive) {
                "Stop session"
            } else {
                "Start session"
            }

        pttButton.isEnabled =
            state.phase == SessionPhase.READY

        // ---------------------------------------------------------------------
        // Partial transcript
        // ---------------------------------------------------------------------

        partialText.text =
            state.partialTranscript

        // ---------------------------------------------------------------------
        // Last sent message
        // ---------------------------------------------------------------------

        sentText.text =
            if (state.lastSentText != null) {

                buildString {

                    append("\"")
                    append(state.lastSentText)
                    append("\"")
                    append("\n")

                    append("priority=")
                    append(state.lastSentPriority)

                    append(" category=")
                    append(state.lastSentCategory)

                    append(" delivery=")
                    append(state.lastDeliveryStatus)
                }

            } else {

                "(nothing sent yet)"
            }

        // ---------------------------------------------------------------------
        // Last received message
        // ---------------------------------------------------------------------

        receivedText.text =
            if (state.lastReceivedText != null) {

                buildString {

                    append("\"")
                    append(state.lastReceivedText)
                    append("\"")
                    append("\n")

                    append("priority=")
                    append(state.lastReceivedPriority)

                    if (
                        state.lastReceivedRequiresReview
                    ) {

                        append(
                            " — flagged for review"
                        )
                    }
                }

            } else {

                "(nothing received yet)"
            }

        // ---------------------------------------------------------------------
        // Diagnostics
        // ---------------------------------------------------------------------

        errorText.text =
            state.lastError ?: ""

        // ---------------------------------------------------------------------
        // BLE devices
        // ---------------------------------------------------------------------

        bleDeviceAddresses =
            state.discoveredBleDevices.map {
                it.address
            }

        bleDeviceList.adapter =
            ArrayAdapter(
                this,
                android.R.layout.simple_list_item_1,
                state.discoveredBleDevices.map {

                    "${it.name ?: "(unnamed)"} — " +
                            "${it.address} — " +
                            "${it.rssiDbm} dBm"
                }
            )
    }
}