package com.itantra.app.ble

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import androidx.annotation.RequiresPermission
import com.itantra.bluetooth.BleGattTransport
import com.itantra.bluetooth.BleTransportConfig
import com.itantra.core.TransportKind
import com.itantra.protocol.PacketTransport
import com.itantra.protocol.TransportLinkStats

/**
 * §11.1/§11.2/§11.4 — the **central/client** role: scans for a peer advertising
 * [ItantraGattProfile.SERVICE_UUID], connects, negotiates MTU, subscribes to notifications on
 * [ItantraGattProfile.OUTBOUND_CHARACTERISTIC_UUID], and writes outbound traffic to
 * [ItantraGattProfile.INBOUND_CHARACTERISTIC_UUID]. See [BleGattServerTransport] for the
 * complementary peripheral/server role and [ItantraGattProfile] for the shared service
 * definition.
 *
 * Same honest-scope caveat as [BleGattServerTransport] applies in full here: real, standard
 * `BluetoothGatt`/`BluetoothGattCallback`/scan API usage, never compiled against the real
 * Android SDK or run against real BLE hardware in this project. `TODO(needs a physical
 * device)` marks the spots most likely to need adjustment once it is.
 *
 * §11.4 connection-role decision, stated plainly: whichever device the user directs to "scan
 * for a peer" (§5's Device Connection screen) becomes the central via this class; whichever
 * device the user directs to "wait to be found" becomes the peripheral via
 * [BleGattServerTransport]. This is a user-driven choice, not an automatic negotiation — an
 * automatic central/peripheral arbitration (e.g. both devices default to scanning and
 * deadlock, or both default to advertising and never connect) is exactly the kind of protocol
 * gap that must be decided by a UI affordance, not guessed at here; see §5's Device Connection
 * screen (not yet implemented — tracked honestly, not built silently).
 */
class BleGattClientTransport(
    private val context: Context,
    private val config: BleTransportConfig = BleTransportConfig()
) : PacketTransport {

    override val kind: TransportKind = TransportKind.BLE

    private val bleLogic = BleGattTransport(config)
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private var gatt: BluetoothGatt? = null
    private var inboundCharacteristic: BluetoothGattCharacteristic? = null
    private var receiveCallback: ((ByteArray) -> Unit)? = null
    private var connected = false
    private var lastRssiDbm: Int? = null

    /** Invoked once a peer advertising the iTantra service is found, before a connection
     *  attempt is made — the §5 Device Connection screen is expected to show these to the user
     *  and let them pick one, rather than this class auto-connecting to the first result. */
    var onDeviceDiscovered: ((BluetoothDevice, rssiDbm: Int) -> Unit)? = null

    val isReady: Boolean get() = connected

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN])
    fun startScan() {
        val scanner = bluetoothManager.adapter?.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(ItantraGattProfile.SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stopScan() {
        bluetoothManager.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            onDeviceDiscovered?.invoke(result.device, result.rssi)
            lastRssiDbm = result.rssi
        }

        override fun onScanFailed(errorCode: Int) {
            // TODO(needs a physical device): surface to session/UI layer once §5's Device
            // Connection screen exists — same deferral as BleGattServerTransport's
            // onStartFailure.
        }
    }

    /** Connects to a [device] found via [onDeviceDiscovered] (or supplied directly, e.g. from
     *  a previously-paired-device list). Stops scanning first — scanning and connecting
     *  simultaneously wastes radio time and is unnecessary once the target is chosen. */
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    fun connectTo(device: BluetoothDevice) {
        stopScan()
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT])
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connected = true
                    // §26: request the largest MTU this config wants; the OS callback below,
                    // not this request, is what actually determines negotiatedMtu.
                    g.requestMtu(config.requestedMtu)
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connected = false
                    inboundCharacteristic = null
                    // TODO(needs a physical device): reconnect state machine with backoff —
                    // deliberately not written blind, same reasoning BleGattTransport's own
                    // KDoc gives for not guessing at real GATT_ERROR/status-code reconnect
                    // timing without a device to observe it on. See PENDING_WORK_MASTER.md.
                }
            }
        }

        @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT])
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val service = g.getService(ItantraGattProfile.SERVICE_UUID) ?: return
            inboundCharacteristic = service.getCharacteristic(ItantraGattProfile.INBOUND_CHARACTERISTIC_UUID)
            val outbound = service.getCharacteristic(ItantraGattProfile.OUTBOUND_CHARACTERISTIC_UUID) ?: return
            g.setCharacteristicNotification(outbound, true)
            val cccd = outbound.getDescriptor(ItantraGattProfile.CLIENT_CHARACTERISTIC_CONFIG_UUID) ?: return
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Same "OS value, never the request, is the source of truth" rule as the
                // server side — see BleGattServerTransport.onMtuChanged.
                bleLogic.onMtuNegotiated(mtu)
            }
        }

        @Suppress("DEPRECATION") // pre-API-33 overload, kept for the minSdk=26 floor — see the newer-overload TODO below
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == ItantraGattProfile.OUTBOUND_CHARACTERISTIC_UUID) {
                // One notification = one already-encoded Packet fragment, same contract as the
                // server side's onCharacteristicWriteRequest — see ItantraGattProfile KDoc.
                receiveCallback?.invoke(characteristic.value)
            }
        }
        // TODO(needs API 33+ / a physical device running it): override the newer
        // onCharacteristicChanged(gatt, characteristic, value: ByteArray) three-arg overload
        // instead of reading the mutable characteristic.value field, which is not guaranteed
        // race-free under back-to-back rapid notifications on some OEM stacks. Both overloads
        // are implemented by real Android BLE stacks on API 33+; only the deprecated one exists
        // pre-33, hence keeping it here for the minSdk=26 floor rather than requiring 33+.
    }

    override fun send(frame: ByteArray) {
        val g = gatt ?: return
        val characteristic = inboundCharacteristic ?: return
        writeCharacteristic(g, characteristic, frame)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun writeCharacteristic(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, frame: ByteArray) {
        // Same pre-API-33 vs API-33+ split as the notify side (BleGattServerTransport.sendNotification's
        // TODO) — BluetoothGatt.writeCharacteristic(characteristic, value, writeType) is the API 33+
        // form; this is the minSdk=26-compatible form.
        @Suppress("DEPRECATION")
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        @Suppress("DEPRECATION")
        characteristic.value = frame
        @Suppress("DEPRECATION")
        g.writeCharacteristic(characteristic)
    }

    override fun onReceive(callback: (ByteArray) -> Unit) {
        receiveCallback = callback
    }

    override fun currentStats(): TransportLinkStats = TransportLinkStats(
        rttMs = null, // same gap as BleGattServerTransport — no real round-trip clock wired to BleGattTransport's ack tracker yet
        rssiDbm = lastRssiDbm,
        mtuBytes = bleLogic.negotiatedMtuOrDefault(),
        connected = connected
    )

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun close() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        connected = false
        inboundCharacteristic = null
    }
}
