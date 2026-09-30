// language-id — Division A (§18, §19-21). Interface-only in Gate 1: real model
// integration (IndicConformer / Indic-TTS / LID per §18's candidate matrix)
// is Gate 2/9 work and requires a Model Scorecard (§18) before adoption.
// Pure Kotlin/JVM so the interface can be built against by other divisions
// without pulling in ONNX Runtime / Android yet.
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
