// protocol — packet framing, CRC, ACK/FEC policy, crypto (Division D, §24, §30).
// Pure Kotlin/JVM: framing/CRC math has no Android dependency and must be
// unit-testable in plain CI without a device (§38).
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
