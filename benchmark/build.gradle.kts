// benchmark — on-device telemetry event schema + JSONL writer (Division:
// Integration/QC owned, §32, §34, §40). Pure Kotlin/JVM: the event schema
// and serialization have no Android dependency, so this module is
// unit-testable in plain CI and reusable by tools/ (offline exporter,
// Python side) without duplicating the schema.
//
// Deliberately no JSON library dependency: this sandbox authoring
// environment cannot reach Maven Central, so serialization here is
// hand-rolled (see TelemetryEvent.kt). Swapping in kotlinx.serialization
// later is a fine, low-risk change once network access allows verifying it.
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
