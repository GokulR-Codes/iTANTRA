package com.itantra.core

/**
 * Cross-division data contracts (master prompt §14).
 *
 * INTEGRATION NOTE (iTantra-Master merge, see docs/FILE_CONFLICT_MATRIX.md
 * row "Contracts.kt"): four independent copies of this file existed across
 * the source archives (Gate1 skeleton, Gate1 git implementation, Part III
 * speech/language stack, Part IV communication stack) with small but real
 * divergences — different `ThermalState` enum values, `ChannelStats` field
 * sets, whether `equals`/`hashCode` were overridden for array-bearing data
 * classes, and a `Language` vs `LanguageCode` naming split. This file is
 * the single, reconciled result. Every prior copy is superseded; do not
 * resurrect one.
 *
 * These field sets are frozen: a division must not drop or rename a field
 * without an Integration/QC-reviewed contract change (§13, §14). Adding a
 * new *optional* field is fine; changing the meaning of an existing one is
 * not.
 */

/** Produced by Division C (audio capture), consumed by Division A (VAD/STT). */
data class AudioFrame(
    val pcm16: ShortArray,
    val sampleRateHz: Int = 16_000,
    val channels: Int = 1,
    val captureTsNanos: Long
) {
    init {
        require(sampleRateHz == 16_000) {
            "AudioFrame is defined at 16kHz mono per §14; got ${sampleRateHz}Hz. " +
                "If a resample path is ever needed, it belongs in Division C's audio " +
                "pipeline, upstream of this contract, and must be logged in DECISIONS.md."
        }
        require(channels == 1) { "AudioFrame is mono per §14; got $channels channels." }
    }

    // ShortArray has reference equality by default; every archive that skipped
    // this override carried a latent equality bug (silently reference-equality
    // in tests and any dedup logic). Fixed here, once, for good.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioFrame) return false
        return pcm16.contentEquals(other.pcm16) &&
            sampleRateHz == other.sampleRateHz &&
            channels == other.channels &&
            captureTsNanos == other.captureTsNanos
    }

    override fun hashCode(): Int {
        var result = pcm16.contentHashCode()
        result = 31 * result + sampleRateHz
        result = 31 * result + channels
        result = 31 * result + captureTsNanos.hashCode()
        return result
    }
}

/** Produced by Division A (streaming STT), consumed by Division B (semantic codec). */
data class RecognizedUtterance(
    val text: String,
    val lang: String,
    val isFinal: Boolean,
    val confidence: Float,
    val startTsMs: Long,
    val endTsMs: Long
) {
    init {
        require(confidence in 0.0f..1.0f) { "confidence must be in [0,1], got $confidence" }
        require(endTsMs >= startTsMs) { "endTsMs ($endTsMs) must be >= startTsMs ($startTsMs)" }
    }
}

/** Transmission priority. Alert-semantic-base is never de-prioritized (§24). Ordinal order is
 *  meaningful (ALERT_BASE < NORMAL_BASE < RESIDUAL) and relied on by protocol/wifi/bluetooth
 *  retransmission-queue ordering — do not reorder without checking every such usage. */
enum class PacketPriority { ALERT_BASE, NORMAL_BASE, RESIDUAL }

/** Produced by Division B (semantic codec), consumed by Division D (protocol/transport). */
data class SemanticPacket(
    val tokenIds: IntArray,
    val lang: String,
    val priority: PacketPriority,
    val residualPayload: ByteArray? = null,
    val ttlMs: Int,
    val sentenceId: Long
) {
    init {
        require(ttlMs > 0) { "ttlMs must be positive, got $ttlMs" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SemanticPacket) return false
        return tokenIds.contentEquals(other.tokenIds) &&
            lang == other.lang &&
            priority == other.priority &&
            (residualPayload?.contentEquals(other.residualPayload ?: ByteArray(0)) ?: (other.residualPayload == null)) &&
            ttlMs == other.ttlMs &&
            sentenceId == other.sentenceId
    }

    override fun hashCode(): Int {
        var result = tokenIds.contentHashCode()
        result = 31 * result + lang.hashCode()
        result = 31 * result + priority.hashCode()
        result = 31 * result + (residualPayload?.contentHashCode() ?: 0)
        result = 31 * result + ttlMs
        result = 31 * result + sentenceId.hashCode()
        return result
    }
}

/** Reconstructed on the receive side by Division D/B, consumed by Division A (TTS).
 *  [reconstructedFromResidual] must never be forced true for demo effect (§23) — it reflects
 *  whether the neural residual genuinely contributed to this utterance. */
data class ReceivedUtterance(
    val text: String,
    val lang: String,
    val confidence: Float,
    val reconstructedFromResidual: Boolean
)

/** Produced by Division A (TTS), consumed by Division C (playback / alert engine).
 *  [firstAudioReadyTsMs] is the timestamp used to compute first-audio latency (§20, §34) —
 *  implementations must record it honestly, never guess it after the fact. [durationMs]
 *  (total synthesized-audio length) was an `tts`-module-local addition folded in here during
 *  the merge — a pure additive field, no existing field's meaning changed. */
data class SynthesizedAudio(
    val pcm16: ShortArray,
    val sampleRateHz: Int,
    val isAlert: Boolean,
    val firstAudioReadyTsMs: Long,
    val durationMs: Long = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SynthesizedAudio) return false
        return pcm16.contentEquals(other.pcm16) &&
            sampleRateHz == other.sampleRateHz &&
            isAlert == other.isAlert &&
            firstAudioReadyTsMs == other.firstAudioReadyTsMs
    }

    override fun hashCode(): Int {
        var result = pcm16.contentHashCode()
        result = 31 * result + sampleRateHz
        result = 31 * result + isAlert.hashCode()
        result = 31 * result + firstAudioReadyTsMs.hashCode()
        return result
    }
}

/** Coarse thermal bucket. Merge decision: the Gate1-skeleton archive's 6-value version is kept
 *  as canonical because it most closely mirrors Android's real `PowerManager` thermal-status
 *  granularity (NONE/LIGHT/MODERATE/SEVERE/CRITICAL/EMERGENCY/SHUTDOWN) and includes an
 *  explicit UNKNOWN fallback; the Part III and Part IV copies (5- and 4-value respectively,
 *  the latter with non-Android-matching names WARM/THROTTLING) are superseded. */
enum class ThermalState { NOMINAL, LIGHT, MODERATE, SEVERE, CRITICAL, UNKNOWN }

enum class TransportKind { WIFI, BLE }

/** Wire-level channel telemetry, produced by Division D, consumed by Division B's adaptive
 *  channel controller (§27) and Integration/QC telemetry (§32).
 *
 *  MERGE NOTE: three of the four source archives (Gate1 skeleton, Part III, Part IV) agreed on
 *  a `Float`-based design with `lossPct` as a 0-100 percentage plus `queueDepth`/`thermalState`/
 *  `batteryPct`; only the Gate1 git archive used `Double` + a 0-1 `lossFraction` and additionally
 *  carried `transport`/`selectedMode` fields. The majority design is canonical here, and it is
 *  also the architecturally correct one: `selectedMode` is the *output* of
 *  `codec.ChannelController.update(stats): ChannelMode` (see codec module), so storing it as an
 *  input field on the stats themselves was circular. `transport` is dropped for the same
 *  reason every producer (`WifiUdpTransport.currentChannelStats()`, the future BLE equivalent)
 *  is already transport-scoped by construction — the caller knows which transport it is. This
 *  shape matches what the already-validated `wifi` module code constructs. */
data class ChannelStats(
    val rttMs: Float,
    val lossPct: Float,
    val goodputBps: Float,
    val rssiDbm: Int?,
    val queueDepth: Int,
    val thermalState: ThermalState,
    val batteryPct: Int?
) {
    init {
        require(lossPct in 0f..100f) { "lossPct must be a percentage in [0,100], got $lossPct" }
    }
}

/** Canonical adaptive-channel-controller modes (§27). Ordinal order is meaningful: increasing
 *  fidelity/bandwidth use. The controller always protects the semantic base first and degrades
 *  the residual first when conditions worsen. */
enum class ChannelMode { SEMANTIC_SAFE, SEMANTIC_PLUS_RESIDUAL, HIGH_FIDELITY_RESIDUAL }

/** Session-level mode per §5/§28: PTT is push-to-talk walkie-talkie behavior; CONTINUOUS is
 *  PTT-off, full-duplex phone-call-like behavior. Owned by Division C's session orchestrator.
 *  (Carried over from the Gate1 skeleton archive; no other archive modeled this yet.) */
enum class SessionMode { PUSH_TO_TALK, CONTINUOUS }

/** The three reference device tiers from §29. H is a development/training reference only and
 *  must never be used to paper over a Class L failure. */
enum class DeviceClass { L, M, H }

/** Consumed by Integration/QC's benchmark engine (§32). [extra] is deliberately loosely typed
 *  (stage-specific numeric/string fields) so adding a new metric never requires an
 *  Integration/QC-reviewed contract change to this shared class. */
data class BenchmarkEvent(
    val stage: String,
    val tsMs: Long,
    val deviceClass: DeviceClass,
    val extra: Map<String, Any> = emptyMap()
)

/**
 * The ten languages required by the project's problem statement (§5, §21): nine Indic +
 * English. Merge decision: canonicalized under the name `Language` (matching every
 * architecture doc's prose, which refers to "the Language enum" throughout) but using the
 * Part III speech/language stack's short ISO-style entry names (HI, GU, MR, ...) since that
 * is the design the larger, already-tested registry/VAD/STT/language-id body of code
 * (LanguageRegistry, TenLanguageMatrix, LanguageRouter, DomainLexicon — see core.registry and
 * language-id) was actually built and unit-tested against. The Gate1 archives' long-form
 * names (HINDI, GUJARATI, ...) are superseded; every reference to `LanguageCode` in the
 * originating archives has been mechanically renamed to `Language` with no logic change.
 */
enum class Language(val code: String, val displayName: String) {
    HI("hi", "Hindi"),
    GU("gu", "Gujarati"),
    MR("mr", "Marathi"),
    KN("kn", "Kannada"),
    ML("ml", "Malayalam"),
    TA("ta", "Tamil"),
    TE("te", "Telugu"),
    OR("or", "Odia"),
    BN("bn", "Bengali"),
    EN("en", "English");

    companion object {
        fun fromCode(code: String): Language? = entries.find { it.code == code }

        /** All 10 required codes, for validation-record and CI completeness checks (Gate 9). */
        val REQUIRED_CODES: Set<String> = entries.map { it.code }.toSet()
    }
}

/**
 * The endpoint detector's states (master prompt §19): `IDLE -> MAYBE_SPEECH -> SPEAKING ->
 * MAYBE_END -> FINALIZING`. This enum is shared vocabulary only — Division C's session
 * orchestrator and Division B's packetizer observe these transitions without depending on the
 * `vad` module's implementation details. The actual transition logic (thresholds, hangover
 * timing, pre-roll, external-signal handling) lives in `vad.Endpointer`, not here — see
 * docs/FILE_CONFLICT_MATRIX.md for why the Gate1 git archive's `core.EndpointStateMachine`
 * (which put full transition logic in `core`) was superseded by that class.
 */
enum class EndpointState { IDLE, MAYBE_SPEECH, SPEAKING, MAYBE_END, FINALIZING }

/** [TARGET] starting timing envelope from §19 — not [MEASURED]. §34's noise-condition
 *  benchmark is required before shipping these unchanged. */
object EndpointTiming {
    const val PRE_ROLL_MS_TARGET = 250
    const val HANGOVER_MS_TARGET_MIN = 350
    const val HANGOVER_MS_TARGET_MAX = 700
}
