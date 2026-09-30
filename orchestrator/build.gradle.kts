// orchestrator — cross-cutting message orchestration (new 2026-09-24). Outgoing: HEMIM
// classify -> priority map -> semantic encode -> wire serialize -> fragment -> reliability
// queue -> transport.send(). Incoming: transport.onReceive -> replay/dup check -> reassemble
// -> wire deserialize -> semantic decode -> (re-)classify for the receiving device's own UI.
// Pure Kotlin/JVM: :core, :alert-engine, :codec, and :protocol are all themselves pure
// Kotlin/JVM (none import android.*), so this module compiles and unit-tests fully in a
// plain JVM sandbox with no Android SDK — see docs/ORCHESTRATOR.md. android-app is meant to
// wire this module to real AudioCapture/STT/TTS/transport implementations, per
// docs/ARCHITECTURE.md's "android-app... must stay thin — logic belongs in the owning
// module" principle; this module is that owner for the pipeline that didn't have one.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":alert-engine"))
    implementation(project(":codec"))
    implementation(project(":protocol"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
}

tasks.test {
    useJUnitPlatform()
}
