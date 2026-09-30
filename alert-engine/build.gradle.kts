// alert-engine — HEMIM (Hybrid Emergency Message Intelligence Module): emergency
// classification, priority policy, and the AlertEngine interface (Division C, §28).
//
// Pure Kotlin/JVM. Every source file in src/main/kotlin/com/itantra/alert/ imports only
// Kotlin stdlib and :core (no android.*, no androidx.*, no R, no BuildConfig), and the module
// has no res/ directory. It was previously declared com.android.library, which made it
// unconsumable by :orchestrator (a JVM module, and deliberately so — see
// orchestrator/build.gradle.kts): Gradle cannot select an androidJvm variant
// (debugApiElements/releaseApiElements) for a plain-JVM compileClasspath.
//
// Android-specific AlertEngine behavior (AudioManager focus, NotificationManager channel)
// lives in android-app (com.itantra.app.alert.AndroidAlertEngine), which implements the
// AlertEngine interface defined here. android-app -> alert-engine still works unchanged:
// an Android application module can consume a JVM library module.
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
