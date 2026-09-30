// bluetooth — BLE GATT transport, runtime MTU negotiation, fragmentation (Division D, §26).
//
// INTEGRATION NOTE (see docs/FILE_CONFLICT_MATRIX.md row "BleTransport"): modeled as a pure
// Kotlin/JVM module for now, like `wifi` — everything currently in this module (MTU-aware
// fragmentation math, windowed-ACK bookkeeping) is plain logic with zero android.bluetooth.*
// dependency, ported from a validated pattern (bluetooth/validation/ble_logic_reference.py)
// and unit-testable without a device. THIS WILL NEED TO BECOME an Android library module
// (com.android.library) once real `BluetoothGatt`/`BluetoothGattCallback` wiring is added —
// see the TODOs in BleGattTransport.kt and PENDING_WORK_MASTER.md P4. Do not mistake "builds
// as a JVM module today" for "BLE works": no code in this module has ever run against real
// BLE hardware or an Android BLE stack.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":protocol"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
}

tasks.test {
    useJUnitPlatform()
}
