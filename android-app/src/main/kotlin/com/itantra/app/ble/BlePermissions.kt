package com.itantra.app.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * §20 — permission handling for the BLE transport specifically (mic permission is `audio`
 * module's concern, not this one). Split by exact API-level requirement rather than one
 * "give me all Bluetooth permissions" bundle, since `BLUETOOTH_SCAN`/`CONNECT`/`ADVERTISE`
 * (API 31+, runtime-requested) replaced the old `BLUETOOTH`/`BLUETOOTH_ADMIN`
 * (API ≤30, install-time-granted, still declared for those OS versions to actually get
 * Bluetooth access) — requesting the wrong set for a given OS version either does nothing
 * (asking for a runtime grant of an install-time permission) or crashes at the API call site
 * (using a BLE API gated on a runtime permission the app never requested).
 *
 * This class only answers "is X currently granted" — it deliberately does NOT itself launch
 * `ActivityResultContracts.RequestMultiplePermissions` or any other request flow, because that
 * needs a real `Activity`/`Fragment` to attach the launcher to, and §5's Device Connection
 * screen (the natural place to trigger this) does not exist yet — see `docs/PENDING_WORK_MASTER.md`.
 * A future screen calls [missingCentralPermissions]/[missingPeripheralPermissions] to decide
 * what to ask for, then launches its own request flow and re-checks before calling
 * [BleGattClientTransport.startScan] / [BleGattServerTransport.start].
 */
object BlePermissions {
    /** [BleGattClientTransport] (central/scan role) needs these. */
    fun missingCentralPermissions(context: Context): List<String> =
        requiredFor(context, scanning = true, advertising = false)

    /** [BleGattServerTransport] (peripheral/advertise role) needs these. */
    fun missingPeripheralPermissions(context: Context): List<String> =
        requiredFor(context, scanning = false, advertising = true)

    private fun requiredFor(context: Context, scanning: Boolean, advertising: Boolean): List<String> {
        val required = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // API 31+: runtime-requested permissions (AndroidManifest.xml already declares all
            // three — see that file's "BLE transport (Division D, §26)" section).
            if (scanning) required += Manifest.permission.BLUETOOTH_SCAN
            required += Manifest.permission.BLUETOOTH_CONNECT // both roles read/write characteristics
            if (advertising) required += Manifest.permission.BLUETOOTH_ADVERTISE
        }
        // API ≤30: BLUETOOTH/BLUETOOTH_ADMIN are install-time "normal" permissions (no runtime
        // prompt exists for them; declaring them in the manifest is sufficient) — nothing to
        // check here for those OS versions.
        return required.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }
}
