// core — shared domain contracts and state-machine definitions (§14).
// Deliberately pure Kotlin/JVM: zero Android dependency, so every other
// module (and CI, without a device or emulator) can compile and unit-test
// against these contracts. Division: shared / Integration-QC owned.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
}

tasks.test {
    useJUnitPlatform()
}
