package com.itantra.stt

import com.itantra.core.AudioFrame
import com.itantra.core.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DomainLexiconTest {

    @Test
    fun `English seed set is populated but other languages start empty`() {
        assertTrue(DomainLexicon.isPopulated(Language.EN))
        assertTrue(DomainLexicon.keywordsFor(Language.EN).contains("evacuate"))
        assertFalse(DomainLexicon.isPopulated(Language.TA), "unreviewed language must not silently appear populated")
    }

    @Test
    fun `containsAnyKeyword is case-insensitive substring match`() {
        assertTrue(DomainLexicon.containsAnyKeyword("please send an AMBULANCE now", Language.EN))
        assertFalse(DomainLexicon.containsAnyKeyword("nothing relevant here", Language.EN))
    }

    @Test
    fun `register overwrites a language's keyword set`() {
        DomainLexicon.register(Language.HI, setOf("madad", "aag"))
        assertTrue(DomainLexicon.isPopulated(Language.HI))
        assertTrue(DomainLexicon.containsAnyKeyword("turant madad chahiye", Language.HI))
    }
}

class SimulationBackedSttEngineTest {

    @Test
    fun `never returns a fabricated transcript from the default placeholder recognizer`() {
        val engine = SimulationBackedSttEngine(activeLanguage = Language.EN)
        val loudFrame = AudioFrame(
            pcm16 = ShortArray(480) { if (it % 2 == 0) 12000 else -12000 },
            captureTsNanos = 0
        )
        // Drive enough loud frames to trigger SpeechStarted + a partial, then silence to finalize.
        var lastNonNull: com.itantra.core.RecognizedUtterance? = null
        repeat(15) {
            engine.onFrame(loudFrame.copy(captureTsNanos = it * 30_000_000L))?.let { r -> lastNonNull = r }
        }
        val silentFrame = AudioFrame(pcm16 = ShortArray(480), captureTsNanos = 500_000_000L)
        repeat(5) {
            engine.onFrame(silentFrame.copy(captureTsNanos = (500 + it * 30) * 1_000_000L))?.let { r -> lastNonNull = r }
        }

        val result = lastNonNull
        if (result != null) {
            assertTrue(
                result.text.startsWith("[simulated-stt:") || result.text.isEmpty(),
                "SimulationBackedSttEngine must never emit a plausible-looking fabricated transcript"
            )
            assertEquals(0f, result.confidence, "simulation must report zero confidence, never a fabricated score")
        }
    }

    @Test
    fun `loadLanguage then unloadLanguage then onFrame throws rather than silently continuing`() {
        val engine = SimulationBackedSttEngine(activeLanguage = Language.EN)
        engine.unloadLanguage()
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            engine.onFrame(AudioFrame(pcm16 = ShortArray(480), captureTsNanos = 0))
        }
    }
}
