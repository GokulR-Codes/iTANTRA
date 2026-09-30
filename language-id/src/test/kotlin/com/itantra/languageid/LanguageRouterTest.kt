package com.itantra.languageid

import com.itantra.core.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * JUnit5 port of Part III's hand-rolled LanguageRouter checks (all 6 passed under the
 * original kotlinc runner). See docs/DEVELOPMENT_HANDOFF.md for provenance.
 */
class LanguageRouterTest {

    @Test
    fun `explicit selection always wins over LID, and low-confidence LID is rejected`() {
        val router = LanguageRouter(initialLanguage = Language.HI, lidEnabled = true, lidConfidenceFloor = 0.7f)
        assertEquals(Language.HI, router.activeLanguage)

        val lowConfApplied = router.offerLidResult(LidResult(Language.TA, confidence = 0.4f))
        assertFalse(lowConfApplied)
        assertEquals(Language.HI, router.activeLanguage)

        val highConfApplied = router.offerLidResult(LidResult(Language.TA, confidence = 0.9f))
        assertTrue(highConfApplied)
        assertEquals(Language.TA, router.activeLanguage)

        router.setExplicitLanguage(Language.BN)
        assertEquals(Language.BN, router.activeLanguage)
    }

    @Test
    fun `LID is never applied when disabled, regardless of confidence`() {
        val disabledRouter = LanguageRouter(initialLanguage = Language.EN, lidEnabled = false)
        val applied = disabledRouter.offerLidResult(LidResult(Language.HI, confidence = 0.99f))
        assertFalse(applied)
        assertEquals(Language.EN, disabledRouter.activeLanguage)
    }

    @Test
    fun `UnimplementedLanguageIdentifier fails loudly rather than guessing`() {
        val lid = UnimplementedLanguageIdentifier()
        org.junit.jupiter.api.Assertions.assertThrows(NotImplementedError::class.java) {
            lid.identify(emptyList())
        }
    }
}
