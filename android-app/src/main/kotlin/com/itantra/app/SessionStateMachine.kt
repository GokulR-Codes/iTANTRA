package com.itantra.app

/**
 * Session lifecycle phases (Module A: "use explicit lifecycle states"). One [SessionService]
 * instance owns exactly one conversation session's worth of this at a time — see
 * [SessionStateMachine]'s KDoc for how duplicate-session-creation and illegal transitions are
 * both prevented at this layer rather than left to ad hoc boolean flags scattered through
 * `SessionService`.
 */
enum class SessionPhase {
    /** No session exists yet (fresh `SessionService`, or after a full stop). */
    IDLE,

    /** Session creation in progress: permission checks, engine/transport startup. */
    INITIALIZING,

    /** Session is live and idle — neither transmitting nor receiving right now. */
    READY,

    /** Push-to-talk held: capturing/recognizing the user's speech. */
    LISTENING,

    /** A recognized utterance is being classified (HEMIM), encoded, and transmitted. */
    SENDING,

    /** An inbound message was just decoded and is about to be spoken. */
    RECEIVING,

    /** Synthesized speech for an inbound message is playing. */
    SPEAKING,

    /** The last operation failed. The session itself is still alive and recoverable — this is
     *  not a terminal state — see [SessionStateMachine]'s transition table. */
    ERROR,

    /** Session teardown in progress: releasing mic/transport/engine resources. */
    STOPPING,

    /** Session fully torn down. A new session requires a new [SessionStateMachine]/[SessionPhase.IDLE]. */
    STOPPED
}

/** Thrown by [SessionStateMachine.transition] when a transition is not in the allowed table —
 *  deliberately a distinct type from a bare [IllegalStateException] so callers can catch it
 *  specifically if they want to surface "you can't do that right now" to the UI. */
class IllegalSessionTransitionException(message: String) : IllegalStateException(message)

/**
 * Pure, Android-independent (zero `android.*` imports) session lifecycle state machine. This
 * is what makes Module A's "explicit lifecycle states" and "prevent duplicate session
 * creation" requirements independently unit-testable in plain JVM/JUnit — see
 * `src/test/kotlin/com/itantra/app/SessionStateMachineTest.kt` — despite the rest of
 * `android-app` requiring the real Android Gradle Plugin/SDK to compile at all.
 *
 * Every transition goes through [transition]; there is no public mutator for [phase]. An
 * illegal transition throws [IllegalSessionTransitionException] rather than silently
 * clamping/ignoring it — a caller that doesn't check `canTransitionTo` first and gets this
 * exception has a real bug to fix, not a state machine that quietly disagreed with it.
 */
class SessionStateMachine(initialPhase: SessionPhase = SessionPhase.IDLE) {

    @Volatile
    var phase: SessionPhase = initialPhase
        private set

    private val listeners = mutableListOf<(from: SessionPhase, to: SessionPhase) -> Unit>()

    fun addListener(listener: (from: SessionPhase, to: SessionPhase) -> Unit) {
        listeners += listener
    }

    fun canTransitionTo(to: SessionPhase): Boolean = isAllowed(phase, to)

    @Synchronized
    fun transition(to: SessionPhase) {
        val from = phase
        if (!isAllowed(from, to)) {
            throw IllegalSessionTransitionException("Illegal session transition: $from -> $to")
        }
        phase = to
        listeners.forEach { it(from, to) }
    }

    companion object {
        // Ordinary forward-progress transitions. STOPPING is handled separately below (allowed
        // from almost anywhere, matching "a session can always be force-stopped").
        private val allowed: Map<SessionPhase, Set<SessionPhase>> = mapOf(
            SessionPhase.IDLE to setOf(SessionPhase.INITIALIZING),
            SessionPhase.INITIALIZING to setOf(SessionPhase.READY, SessionPhase.ERROR),
            SessionPhase.READY to setOf(SessionPhase.LISTENING, SessionPhase.RECEIVING, SessionPhase.ERROR),
            SessionPhase.LISTENING to setOf(SessionPhase.SENDING, SessionPhase.READY, SessionPhase.ERROR),
            SessionPhase.SENDING to setOf(SessionPhase.READY, SessionPhase.ERROR),
            SessionPhase.RECEIVING to setOf(SessionPhase.SPEAKING, SessionPhase.READY, SessionPhase.ERROR),
            SessionPhase.SPEAKING to setOf(SessionPhase.READY, SessionPhase.ERROR),
            SessionPhase.ERROR to setOf(SessionPhase.READY),
            SessionPhase.STOPPING to setOf(SessionPhase.STOPPED),
            SessionPhase.STOPPED to emptySet()
        )

        private fun isAllowed(from: SessionPhase, to: SessionPhase): Boolean {
            if (from == SessionPhase.STOPPED || from == SessionPhase.STOPPING) {
                // A stopped/stopping session cannot be resurrected by a transition call — a
                // fresh SessionStateMachine (new session) is required, matching the "prevent
                // duplicate/zombie session creation" requirement from the other direction.
                return allowed[from]?.contains(to) == true
            }
            if (to == SessionPhase.STOPPING) return true // force-stop is always reachable
            return allowed[from]?.contains(to) == true
        }
    }
}
