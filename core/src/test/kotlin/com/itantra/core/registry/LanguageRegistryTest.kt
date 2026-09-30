package com.itantra.core.registry

import com.itantra.core.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * JUnit5 port of Part III's hand-rolled LanguageRegistry checks (originally run via a
 * scratch kotlinc `main()` runner because that authoring sandbox had no Maven Central
 * access either — see docs/DEVELOPMENT_HANDOFF.md — where all 9 assertions passed).
 * Assertions are unchanged in intent; only the harness changed to JUnit5, matching the
 * rest of this repository's test convention.
 */
class LanguageRegistryTest {

    @Test
    fun `TenLanguageMatrix seeds all ten languages, none validated yet`() {
        val registry = LanguageRegistry()
        TenLanguageMatrix.seedRegistry(registry)

        assertEquals(10, registry.allManifests().size)
        assertTrue(registry.allManifests().all { it.validationStatus == ValidationStatus.NOT_STARTED })
        assertFalse(registry.isSupported(Language.HI))
    }

    @Test
    fun `loading a language unloads the previous resident one`() {
        val registry = LanguageRegistry()
        TenLanguageMatrix.seedRegistry(registry)

        var unloadedFrom: Language? = null
        var loadedTo: Language? = null
        registry.addUnloadListener { unloadedFrom = it }
        registry.addLoadListener { loadedTo = it }

        registry.loadLanguage(Language.HI)
        assertEquals(Language.HI, registry.residentLanguage)
        assertNull(unloadedFrom, "no unload should fire on first load — nothing was resident before")
        assertEquals(Language.HI, loadedTo)

        registry.loadLanguage(Language.TA)
        assertEquals(Language.HI, unloadedFrom, "previous language must be unloaded before the next loads")
        assertEquals(Language.TA, registry.residentLanguage, "never two resident languages at once (§29)")
    }

    @Test
    fun `requireSupported throws instead of silently substituting an unvalidated language`() {
        val registry = LanguageRegistry()
        TenLanguageMatrix.seedRegistry(registry)

        assertThrows(IllegalStateException::class.java) {
            registry.requireSupported(Language.BN)
        }
    }

    @Test
    fun `requireSupported throws for a language with no manifest at all`() {
        val registry = LanguageRegistry()
        assertThrows(IllegalStateException::class.java) {
            registry.requireSupported(Language.HI)
        }
    }
}
