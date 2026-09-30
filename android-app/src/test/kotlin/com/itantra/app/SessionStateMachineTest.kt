package com.itantra.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Written this session alongside [SessionStateMachine] itself. Like every other JUnit source
 * in this repository added under a sandbox with no Android SDK/Maven access, these have been
 * read and reasoned through carefully but NOT executed by `./gradlew :android-app:testDebugUnitTest`
 * — see docs/IMPLEMENTATION_STATUS.md's entry for this session for the exact, honest
 * verification status. [SessionStateMachine] itself has zero `android.*` imports, so once a
 * real Android Gradle Plugin/SDK is available this specific test class needs nothing beyond
 * the ordinary `testDebugUnitTest` task to run for real.
 */
class SessionStateMachineTest {

    @Test
    fun `starts IDLE`() {
        val sm = SessionStateMachine()
        assertEquals(SessionPhase.IDLE, sm.phase)
    }

    @Test
    fun `full happy-path send round trip`() {
        val sm = SessionStateMachine()
        sm.transition(SessionPhase.INITIALIZING)
        sm.transition(SessionPhase.READY)
        sm.transition(SessionPhase.LISTENING)
        sm.transition(SessionPhase.SENDING)
        sm.transition(SessionPhase.READY)
        assertEquals(SessionPhase.READY, sm.phase)
    }

    @Test
    fun `full happy-path receive round trip`() {
        val sm = SessionStateMachine()
        sm.transition(SessionPhase.INITIALIZING)
        sm.transition(SessionPhase.READY)
        sm.transition(SessionPhase.RECEIVING)
        sm.transition(SessionPhase.SPEAKING)
        sm.transition(SessionPhase.READY)
        assertEquals(SessionPhase.READY, sm.phase)
    }

    @Test
    fun `cannot listen before the session is initialized`() {
        val sm = SessionStateMachine()
        assertFalse(sm.canTransitionTo(SessionPhase.LISTENING))
        assertThrows(IllegalSessionTransitionException::class.java) {
            sm.transition(SessionPhase.LISTENING)
        }
    }

    @Test
    fun `cannot start a second listening session while already listening (no duplicate session)`() {
        val sm = SessionStateMachine()
        sm.transition(SessionPhase.INITIALIZING)
        sm.transition(SessionPhase.READY)
        sm.transition(SessionPhase.LISTENING)
        assertFalse(sm.canTransitionTo(SessionPhase.LISTENING))
        assertThrows(IllegalSessionTransitionException::class.java) {
            sm.transition(SessionPhase.LISTENING)
        }
    }

    @Test
    fun `error is recoverable back to ready, not terminal`() {
        val sm = SessionStateMachine()
        sm.transition(SessionPhase.INITIALIZING)
        sm.transition(SessionPhase.ERROR)
        assertTrue(sm.canTransitionTo(SessionPhase.READY))
        sm.transition(SessionPhase.READY)
        assertEquals(SessionPhase.READY, sm.phase)
    }

    @Test
    fun `stopping is reachable from any live state (force stop)`() {
        listOf(
            SessionPhase.INITIALIZING, SessionPhase.READY, SessionPhase.LISTENING,
            SessionPhase.SENDING, SessionPhase.RECEIVING, SessionPhase.SPEAKING, SessionPhase.ERROR
        ).forEach { midState ->
            val sm = SessionStateMachine(initialPhase = midState)
            assertTrue(sm.canTransitionTo(SessionPhase.STOPPING), "expected STOPPING reachable from $midState")
            sm.transition(SessionPhase.STOPPING)
            sm.transition(SessionPhase.STOPPED)
            assertEquals(SessionPhase.STOPPED, sm.phase)
        }
    }

    @Test
    fun `a stopped session cannot be resurrected — a new session must be created instead`() {
        val sm = SessionStateMachine(initialPhase = SessionPhase.STOPPING)
        sm.transition(SessionPhase.STOPPED)
        assertFalse(sm.canTransitionTo(SessionPhase.IDLE))
        assertFalse(sm.canTransitionTo(SessionPhase.INITIALIZING))
        assertThrows(IllegalSessionTransitionException::class.java) {
            sm.transition(SessionPhase.INITIALIZING)
        }
    }

    @Test
    fun `listeners are notified with correct from-to pairs`() {
        val sm = SessionStateMachine()
        val transitions = mutableListOf<Pair<SessionPhase, SessionPhase>>()
        sm.addListener { from, to -> transitions.add(from to to) }
        sm.transition(SessionPhase.INITIALIZING)
        sm.transition(SessionPhase.READY)
        assertEquals(listOf(SessionPhase.IDLE to SessionPhase.INITIALIZING, SessionPhase.INITIALIZING to SessionPhase.READY), transitions)
    }

    @Test
    fun `a failed illegal transition does not change phase or notify listeners`() {
        val sm = SessionStateMachine()
        var notifyCount = 0
        sm.addListener { _, _ -> notifyCount++ }
        assertThrows(IllegalSessionTransitionException::class.java) {
            sm.transition(SessionPhase.SPEAKING)
        }
        assertEquals(SessionPhase.IDLE, sm.phase)
        assertEquals(0, notifyCount)
    }
}
