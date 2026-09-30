// iTantra — SIH26173. Root settings.
// Module layout is normative per docs/ARCHITECTURE.md §15 of the master prompt
// (mirrored there, not duplicated here — see ARCHITECTURE.md).
pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "itantra"

// --- Division C: Android Edge Application ---
include(":android-app")
include(":audio")
include(":alert-engine")

// --- Division A: Speech Intelligence ---
include(":vad")
include(":language-id")
include(":stt")
include(":tts")

// --- Division B: Semantic & Neural Communication ---
include(":codec")

// --- Division D: Networking, Reliability, Security & Infrastructure ---
include(":protocol")
include(":wifi")
include(":bluetooth")

// --- Cross-cutting: message orchestration (owns the outgoing/incoming pipeline that
// connects HEMIM classification, semantic encoding, wire framing, and transport — see
// docs/ARCHITECTURE.md's "android-app must stay thin" principle; this is the layer that
// principle implies but that didn't have an owning module until this session) ---
include(":orchestrator")

// --- Shared / cross-cutting ---
include(":core")
include(":benchmark")
