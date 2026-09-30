// wifi — Wi-Fi Direct/hotspot discovery, UDP-primary/TCP-fallback transport (Division D, §25).
//
// INTEGRATION NOTE (see docs/FILE_CONFLICT_MATRIX.md row "WifiTransport"): the Gate1 git
// archive modeled this as an Android-library module (com.android.library) containing only
// an `UnimplementedWifiTransport` stub. The Part IV communication-stack archive's real,
// UDP-loopback-validated `WifiUdpTransport` uses ONLY plain java.net APIs (DatagramSocket) —
// no Android SDK dependency — so it is modeled here as a pure Kotlin/JVM module instead. This
// is a deliberate architecture change from Gate1: it means wifi's real send/receive/fragment
// logic can be unit-tested in plain CI (including this integration's own sandbox, which has
// no Android SDK), rather than requiring a device or emulator. A future Android-specific
// adapter (WifiManager RSSI, Wi-Fi Direct/hotspot discovery/pairing — genuinely Android-only
// concerns, see WifiUdpTransport's KDoc) can wrap this module from android-app without
// forcing the whole module to depend on the Android SDK.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":protocol"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

tasks.test {
    useJUnitPlatform()
}
