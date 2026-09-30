package com.itantra.codec

/**
 * Global feature flags for [BLUEPRINT-EXPERIMENTAL] components, §1/§23.
 * Nothing behind these flags may be assumed enabled by any other module;
 * the production/demo path (Tier 3, §42) must be fully unaffected by
 * their state.
 */
object FeatureFlags {
    /**
     * §23: the neural residual is EXPERIMENTAL and, as of this session,
     * UNIMPLEMENTED — there is no trained model. This flag exists so the
     * rest of the pipeline (SemanticCodec, ChannelController,
     * RetransmissionQueue) can be built and tested against the
     * `NeuralResidualEncoder` interface now, without pretending a model
     * exists. Do not flip this to `true` until Gate 12's ablation
     * (§16/§18/§23) has an actual trained/distilled residual model AND a
     * measured with-vs-without benefit at matched bitrate. Flipping it
     * on with no model behind it would silently no-op via
     * [NoOpNeuralResidual] — harmless, but also proves nothing, so don't
     * present that as "neural residual enabled."
     */
    var neuralResidualEnabled: Boolean = false

    /** §22's "optional entropy coding stage" — cheap to enable, measure the CPU cost per §31 before assuming it's free. */
    var entropyCodingEnabled: Boolean = true
}

/**
 * §23's precise definition: the neural residual is an ADDITIONAL,
 * OPTIONAL learned enhancement on top of an already-real neural pipeline
 * (neural STT -> semantic representation -> ... -> neural TTS). It is
 * never the sole basis for calling the system "neural," and it must
 * never be allowed to degrade the semantic base's delivery guarantee
 * (§12, Division B failure condition) — see how [RetransmissionQueue]
 * drops RESIDUAL priority items first, never ALERT_BASE/NORMAL_BASE.
 */
interface NeuralResidualEncoder {
    /** Returns null if no residual is produced (e.g. flag disabled, or the model declines to enhance this utterance). */
    fun encode(pcm16: ShortArray, semanticTokenIds: IntArray): ByteArray?
    fun decode(residualPayload: ByteArray, semanticTokenIds: IntArray): ShortArray?
}

/**
 * The only implementation shipped this session. Always returns null:
 * there is no trained model to run. Wiring the interface through the
 * rest of the system now (rather than skipping it entirely) means
 * Gate 12's real implementation is a drop-in replacement, not a
 * refactor — but this class itself makes zero neural claim and produces
 * zero output.
 */
class NoOpNeuralResidual : NeuralResidualEncoder {
    override fun encode(pcm16: ShortArray, semanticTokenIds: IntArray): ByteArray? = null
    override fun decode(residualPayload: ByteArray, semanticTokenIds: IntArray): ShortArray? = null
}
