package com.itantra.benchmark

/**
 * One measured event per utterance leg (send-side or receive-side), per
 * §32/§34's required metrics. Every field here must be a `[MEASURED]` value
 * when populated — this class does not compute anything, it only carries
 * numbers someone else measured. A field left null means "not measured for
 * this event," never "assume zero" (§1's evidence discipline, enforced in
 * code: nullable, not defaulted).
 */
data class TelemetryEvent(
    val eventId: String,
    val sentenceId: Long,
    val deviceClass: String, // "L" | "M" | "H" — kept as String, not core.DeviceClass, so this module has zero enum-drift risk with core if core's enum ever grows a value this schema doesn't know about yet
    val transport: String?, // "WIFI" | "BLE" | null if not yet on a transport leg
    val sttRtf: Double? = null,
    val ttsFirstAudioMs: Long? = null,
    val endToEndLatencyMs: Long? = null,
    val payloadBytes: Int? = null,
    val bitsPerSecond: Double? = null,
    val packetLossFraction: Double? = null,
    val rttMs: Double? = null,
    val batteryDrainMahPerHour: Double? = null,
    val monotonicTimestampMs: Long
) {
    /** Hand-rolled JSON Lines encoding — see build.gradle.kts for why there's no JSON library dependency. */
    fun toJsonLine(): String {
        val fields = linkedMapOf<String, String?>(
            "eventId" to jsonString(eventId),
            "sentenceId" to sentenceId.toString(),
            "deviceClass" to jsonString(deviceClass),
            "transport" to transport?.let { jsonString(it) },
            "sttRtf" to sttRtf?.toString(),
            "ttsFirstAudioMs" to ttsFirstAudioMs?.toString(),
            "endToEndLatencyMs" to endToEndLatencyMs?.toString(),
            "payloadBytes" to payloadBytes?.toString(),
            "bitsPerSecond" to bitsPerSecond?.toString(),
            "packetLossFraction" to packetLossFraction?.toString(),
            "rttMs" to rttMs?.toString(),
            "batteryDrainMahPerHour" to batteryDrainMahPerHour?.toString(),
            "monotonicTimestampMs" to monotonicTimestampMs.toString()
        )
        val body = fields.entries.joinToString(",") { (k, v) -> "\"$k\":${v ?: "null"}" }
        return "{$body}"
    }

    private fun jsonString(s: String): String {
        val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }
}

/** Appends events as newline-delimited JSON. No aggregation, no averaging — that is BENCHMARKS.md's job, done from raw events, not this writer's. */
class JsonlTelemetryWriter(private val sink: Appendable) {
    fun write(event: TelemetryEvent) {
        sink.append(event.toJsonLine())
        sink.append('\n')
    }
}
