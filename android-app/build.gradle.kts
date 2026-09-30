// android-app — UI, lifecycle, permissions, foreground session service
// (Division C, §11, §28). Application module; wires all other modules
// together but must stay thin — logic belongs in the owning module, not here.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.itantra.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.itantra.app"
        // [ASSUMPTION, ENGINEERING]: API 26 floor — see docs/ARCHITECTURE.md §Device Classes.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        // Bumped from "0.1.0-gate1": this session wired a real (unverified, see
        // docs/IMPLEMENTATION_STATUS.md) SessionService/UI pipeline on top of the previously
        // Gate-1-only android-app module — no longer just a shell, though still not built or
        // device-tested. "gate2-unverified" names both facts honestly.
        versionName = "0.2.0-gate2-unverified"
    }

    buildTypes {
        release {
            isMinifyEnabled = false // revisit once real models/native libs are in place (R8 + native + reflection interplay)
        }
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
    implementation(project(":audio"))
    implementation(project(":alert-engine"))
    implementation(project(":vad"))
    implementation(project(":language-id"))
    implementation(project(":stt"))
    implementation(project(":tts"))
    implementation(project(":codec"))
    implementation(project(":orchestrator"))
    implementation(project(":protocol"))
    implementation(project(":wifi"))
    implementation(project(":bluetooth"))
    implementation(project(":benchmark"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // Added 2026-09-26 for @RequiresPermission on the new BLE GATT classes (android-app/ble/) —
    // was already present transitively via core-ktx, but declared explicitly rather than relying
    // on an undeclared transitive version. See docs/KNOWN_ISSUES.md for this session's BLE entry.
    implementation("androidx.annotation:annotation:1.8.2")

    // Added this session for SessionService's structured-concurrency session orchestration
    // (Module A requirement) — version pinned to match wifi/build.gradle.kts's existing
    // kotlinx-coroutines-core:1.8.1 rather than left to resolve independently, so both modules
    // agree on one coroutines version at the android-app level where they meet.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // MainActivity's lifecycleScope.launch { ... } collector for SessionService.uiState.
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
