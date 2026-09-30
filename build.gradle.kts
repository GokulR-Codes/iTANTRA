// iTantra — SIH26173. Root build file: shared plugin versions only.
// No task logic lives here; each module owns its own build.gradle.kts.

plugins {
    id("com.android.application") version "8.5.2" apply false
    id("com.android.library") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.20" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
}

// ktlint is applied at the root only, to every subproject, so each module's
// build.gradle.kts doesn't need to remember to add it individually — a
// module added later without editing this file still gets checked (§39).
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
}

// Class L (§29 of the master prompt) is the design floor for every module —
// see docs/ARCHITECTURE.md. This file intentionally stays minimal: it is not
// where module-specific dependency or resource decisions are made.

tasks.register("printModules") {
    doLast {
        println(subprojects.joinToString("\n") { "  - ${it.path}" })
    }
}
