// audio — AudioRecord/AAudio capture, ring buffer, playback plumbing
// (Division C, §11, §28). Requires the Android SDK/AGP; cannot be verified
// in a plain-JVM sandbox (see docs/KNOWN_ISSUES.md) — CI (§39) verifies it
// with full SDK access.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.itantra.audio"
    compileSdk = 35

    defaultConfig {
        // [ASSUMPTION, ENGINEERING]: API 26 floor, per docs/ARCHITECTURE.md — validate
        // against the final Class L reference device before Gate 10 closes.
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core"))
    // Added this session for @RequiresPermission on AndroidAudioCapture.start() (P1.1, see
    // docs/PENDING_WORK_MASTER.md) — same version android-app/build.gradle.kts already pins
    // explicitly for the BLE GATT classes, kept consistent rather than left to resolve
    // transitively to a possibly-different version.
    implementation("androidx.annotation:annotation:1.8.2")
}
