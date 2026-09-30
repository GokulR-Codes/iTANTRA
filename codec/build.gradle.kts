// codec — tokenizer, semantic codec, optional neural residual (Division B, §22, §35).
// Pure Kotlin/JVM. Ships TWO real codecs behind the same SemanticCodec interface:
// Utf8BaselineCodec (the mandatory §35 baseline every "low-bitrate" claim is measured
// against) and BpeSemanticCodec (a real, trainable BPE subword codec — see
// validation/RESULTS.md for a measured ~40% of baseline size on a small placeholder
// corpus; NOT yet trained on a real per-language corpus per §21, NOT yet benchmarked
// on-device). NeuralResidual stays NoOp — see FeatureFlags.neuralResidualEnabled.
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
