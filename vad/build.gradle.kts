// vad — two-stage VAD (Division A, §19). Stage 1 (this module, today) is a
// pure energy/zero-crossing gate: cheap, no model, no Android dependency,
// so it is unit-testable in plain CI. Stage 2 (Silero-class neural VAD,
// §18) is a stub behind the same interface until Gate 9/model integration
// work brings in ONNX Runtime — see NeuralVadGate.kt.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
}

tasks.test {
    useJUnitPlatform()
}
