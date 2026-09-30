package com.itantra.vad

import com.itantra.core.EndpointState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * JUnit5 port of Part III's hand-rolled Endpointer checks (all 14 passed under the
 * original kotlinc runner). See docs/DEVELOPMENT_HANDOFF.md for provenance.
 */
class EndpointerTest {

    @Test
    fun `strong pause finalizes an utterance`() {
        val cfg = EndpointerConfig(hangoverMs = 90L, speechConfirmFrames = 2, frameDurationMs = 30L)
        val ep = Endpointer(cfg)

        assertEquals(EndpointState.IDLE, ep.state)

        assertNull(ep.onVadFrame(true), "1st speech frame -> MAYBE_SPEECH, no event")
        assertEquals(EndpointState.MAYBE_SPEECH, ep.state)

        val started = ep.onVadFrame(true)
        assertTrue(started is EndpointEvent.SpeechStarted, "2nd consecutive speech frame emits SpeechStarted")
        assertEquals(EndpointState.SPEAKING, ep.state)

        assertNull(ep.onVadFrame(true), "more speech stays SPEAKING with no event")

        // hangoverMs=90, frameDurationMs=30 -> 3 consecutive silence frames trip it.
        assertNull(ep.onVadFrame(false), "1st silence frame -> MAYBE_END, no event yet")
        assertEquals(EndpointState.MAYBE_END, ep.state)
        assertNull(ep.onVadFrame(false), "2nd silence frame still no event (60ms < 90ms hangover)")

        val finalized = ep.onVadFrame(false)
        assertTrue(finalized is EndpointEvent.Finalized && finalized.reason == FinalizeReason.STRONG_PAUSE)
        assertEquals(EndpointState.FINALIZING, ep.state)

        ep.reset()
        assertEquals(EndpointState.IDLE, ep.state)
    }

    @Test
    fun `false start returns to IDLE`() {
        val ep = Endpointer(EndpointerConfig(speechConfirmFrames = 3))
        ep.onVadFrame(true)
        assertEquals(EndpointState.MAYBE_SPEECH, ep.state)
        ep.onVadFrame(false)
        assertEquals(EndpointState.IDLE, ep.state, "a silence frame during MAYBE_SPEECH resets to IDLE")
    }

    @Test
    fun `PTT release forces finalization mid-utterance but is a no-op in IDLE`() {
        val ep = Endpointer(EndpointerConfig(speechConfirmFrames = 1))
        ep.onVadFrame(true) // -> SPEAKING immediately (confirm=1)
        assertEquals(EndpointState.SPEAKING, ep.state)

        val ignoredInIdle = Endpointer().onExternalSignal(ExternalSignal.PTT_RELEASED)
        assertNull(ignoredInIdle, "PTT release in IDLE state is a no-op")

        val finalized = ep.onExternalSignal(ExternalSignal.PTT_RELEASED)
        assertTrue(finalized is EndpointEvent.Finalized && finalized.reason == FinalizeReason.PTT_RELEASE)
    }
}
