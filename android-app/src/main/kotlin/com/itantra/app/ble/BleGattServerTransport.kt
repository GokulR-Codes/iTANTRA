package com.itantra.app.ble

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import androidx.annotation.RequiresPermission
import com.itantra.bluetooth.BleGattTransport
import com.itantra.bluetooth.BleTransportConfig
import com.itantra.core.TransportKind
import com.itantra.protocol.PacketTransport
import com.itantra.protocol.TransportLinkStats

/**
 * §11.1/§11.2/§11.4 — the **peripheral/server** role: advertises the iTantra service, hosts a
 * [BluetoothGattServer], accepts writes from a connected central on
 * [ItantraGattProfile.INBOUND_CHARACTERISTIC_UUID], and notifies outbound traffic on
 * [ItantraGattProfile.OUTBOUND_CHARACTERISTIC_UUID]. See [BleGattClientTransport] for the
 * complementary central/client role and [ItantraGattProfile] for why both roles present the
 * same [PacketTransport] contract despite GATT's inherently asymmetric write/notify directions.
 *
 * Delegates all MTU-aware fragment sizing, MTU negotiation bookkeeping, and (currently unused
 * pending a real windowed-ACK wire-up — see [BleGattTransport]'s own KDoc) ack tracking to the
 * existing pure-logic [BleGattTransport] from the `:bluetooth` module, rather than
 * re-implementing any of that math here — this class's job is strictly the `android.bluetooth.*`
 * plumbing [BleGattTransport] was explicitly written without (see its "HONEST SCOPE" KDoc).
 *
 * HONEST SCOPE (written 2026-09-26, applies to this whole `ble` package): this is real,
 * standard `BluetoothGattServer`/`BluetoothGattServerCallback` API usage — not a stub, not a
 * simulation — but it has never been compiled against the real Android SDK (this sandbox has
 * none, see `docs/KNOWN_ISSUES.md`) or run against real BLE hardware. GATT callback *ordering*
 * and MTU-negotiation edge cases are known to vary by OEM/Android version (the exact concern
 * `bluetooth.BleGattTransport`'s own KDoc raised for not writing this blind) — what follows
 * uses the documented, stable parts of the public API (not any undocumented/OEM-specific
 * behavior), but it must be exercised on at least two physical devices before being trusted.
 * `TODO(needs a physical device)` marks the specific spots most likely to need adjustment.
 *
 * Only ONE central is served at a time (`connectedDevice`) — iTantra's two-phone use case does
 * not need this class to be a multi-central hub; a third device attempting to connect while one
 * is already active is rejected in [BluetoothGattServerCallback.onConnectionStateChange].
 */
class BleGattServerTransport(
    private val context: Context,
    private val config: BleTransportConfig = BleTransportConfig()
) : PacketTransport {

    override val kind: TransportKind = TransportKind.BLE

    private val bleLogic = BleGattTransport(config)
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private var gattServer: BluetoothGattServer? = null
    private var connectedDevice: BluetoothDevice? = null
    private var outboundCharacteristic: BluetoothGattCharacteristic? = null
    private var notificationsEnabled = false
    private var receiveCallback: ((ByteArray) -> Unit)? = null
    private var lastRssiDbm: Int? = null

    /** True once a central has connected and enabled notifications — i.e. the link can
     *  actually carry traffic both ways, not merely that a GATT connection object exists. */
    val isReady: Boolean get() = connectedDevice != null && notificationsEnabled

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun start() {
        val service = BluetoothGattService(
            ItantraGattProfile.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val inbound = BluetoothGattCharacteristic(
            ItantraGattProfile.INBOUND_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val outbound = BluetoothGattCharacteristic(
            ItantraGattProfile.OUTBOUND_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        // Required so a central can enable/disable notifications (BLE spec, not ours — see
        // ItantraGattProfile.CLIENT_CHARACTERISTIC_CONFIG_UUID's KDoc).
        outbound.addDescriptor(
            BluetoothGattDescriptor(
                ItantraGattProfile.CLIENT_CHARACTERISTIC_CONFIG_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        outboundCharacteristic = outbound

        val status = BluetoothGattCharacteristic(
            ItantraGattProfile.STATUS_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        status.value = byteArrayOf(ItantraGattProfile.ROLE_PERIPHERAL)

        service.addCharacteristic(inbound)
        service.addCharacteristic(outbound)
        service.addCharacteristic(status)

        gattServer = bluetoothManager.openGattServer(context, serverCallback)
        gattServer?.addService(service)
        startAdvertising()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun startAdvertising() {
        val advertiser = bluetoothManager.adapter?.bluetoothLeAdvertiser
            ?: return // adapter off, or device has no advertiser radio — caller sees isReady stay false
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(ItantraGattProfile.SERVICE_UUID))
            .setIncludeDeviceName(false) // keep the advertisement small; device identity is §17's senderShortId, never a raw MAC/name
            .build()
        advertiser.startAdvertising(settings, data, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            // TODO(needs a physical device): surface this to the session/UI layer (e.g. via a
            // status callback) instead of only being visible in Logcat — deferred because the
            // UI layer this would report to (§5) does not exist yet either.
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    if (connectedDevice != null && connectedDevice?.address != device.address) {
                        // Single-central policy stated in the class KDoc: reject a second
                        // simultaneous central rather than silently multiplexing traffic
                        // between two peers under one PacketTransport instance.
                        gattServer?.cancelConnection(device)
                        return
                    }
                    connectedDevice = device
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    if (connectedDevice?.address == device.address) {
                        connectedDevice = null
                        notificationsEnabled = false
                    }
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid == ItantraGattProfile.INBOUND_CHARACTERISTIC_UUID) {
                // One GATT write = one already-encoded Packet fragment (see ItantraGattProfile
                // KDoc) — handed straight to the orchestrator's callback, exactly like
                // WifiPacketTransportAdapter.onReceive does for one UDP datagram. No reassembly
                // here; that is MessageOrchestrator's job, not this transport's.
                receiveCallback?.invoke(value)
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (descriptor.uuid == ItantraGattProfile.CLIENT_CHARACTERISTIC_CONFIG_UUID) {
                notificationsEnabled = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            // The OS-reported value is the only source of truth (§26) — never
            // config.requestedMtu. May throw BleMtuTooSmallException, which is intentionally
            // NOT caught here: a peripheral that cannot function at the negotiated MTU should
            // fail loudly (crash this transport's use of it) rather than silently drop traffic
            // forever — see BleGattTransport.onMtuNegotiated's KDoc.
            bleLogic.onMtuNegotiated(mtu)
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == ItantraGattProfile.STATUS_CHARACTERISTIC_UUID) {
                gattServer?.sendResponse(
                    device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                    byteArrayOf(ItantraGattProfile.ROLE_PERIPHERAL)
                )
            }
        }
    }

    override fun send(frame: ByteArray) {
        val device = connectedDevice ?: return // no central connected — nothing to send to, not an error (mirrors WifiUdpTransport requiring open()/connectTo() first, but non-fatal here since BLE connection is inherently transient)
        val characteristic = outboundCharacteristic ?: return
        if (!notificationsEnabled) return
        sendNotification(device, characteristic, frame)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun sendNotification(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic, frame: ByteArray) {
        // TODO(needs a physical device): API 33+ has BluetoothGattServer.notifyCharacteristicChanged(device, characteristic, confirm, value)
        // which avoids this shared-mutable-characteristic-value race under concurrent sends; kept
        // to the pre-33 characteristic.value + notifyCharacteristicChanged(device, char, confirm)
        // form here for the minSdk=26 floor (android-app/build.gradle.kts). A real
        // per-device outbound queue (one send in flight at a time, waiting for the prior
        // notification's ATT-layer ack before sending the next) belongs here before this is
        // trusted under load — not added yet, tracked honestly rather than silently assumed away.
        @Suppress("DEPRECATION")
        characteristic.value = frame
        @Suppress("DEPRECATION")
        gattServer?.notifyCharacteristicChanged(device, characteristic, false)
    }

    override fun onReceive(callback: (ByteArray) -> Unit) {
        receiveCallback = callback
    }

    override fun currentStats(): TransportLinkStats = TransportLinkStats(
        rttMs = null, // BLE RTT is not tracked at this layer yet — see BleGattTransport's ack tracker, not yet wired to a real round-trip clock
        rssiDbm = lastRssiDbm,
        mtuBytes = bleLogic.negotiatedMtuOrDefault(),
        connected = isReady
    )

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE])
    override fun close() {
        connectedDevice?.let { gattServer?.cancelConnection(it) }
        bluetoothManager.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        gattServer?.close()
        gattServer = null
        connectedDevice = null
        notificationsEnabled = false
    }
}
