package dev.pbou.stt.data

import dev.pbou.stt.engine.ModelCatalog
import dev.pbou.stt.engine.ModelSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppSettingsTest {
    private val whisper = ModelCatalog.WHISPER_SMALL // can detect or be told
    private val cohere = ModelCatalog.COHERE         // must be told
    private val parakeet = ModelCatalog.PARAKEET     // only detects

    @Test
    fun `auto only reaches the models that can detect`() {
        val s = AppSettings(language = "fr", autoDetect = true)
        assertEquals(ModelSpec.AUTO, s.languageFor(whisper))
        assertEquals("fr", s.languageFor(cohere)) // never "English by default" again
        assertNull(parakeet.effectiveLanguage(s.languageFor(parakeet), listOf("en")))
        assertNull(whisper.effectiveLanguage(s.languageFor(whisper), listOf("en")))
        assertEquals("fr", cohere.effectiveLanguage(s.languageFor(cohere), listOf("en")))
    }

    @Test
    fun `picking a language for Whisper turns detection off, picking for Cohere does not`() {
        val s = AppSettings(language = "fr", autoDetect = true)
        assertEquals(AppSettings(language = "de", autoDetect = false), s.withLanguage("de", whisper))
        assertEquals(AppSettings(language = "de", autoDetect = true), s.withLanguage("de", cohere))
        assertEquals(AppSettings(language = "de", autoDetect = true), s.withLanguage("de", cohere).withLanguage(ModelSpec.AUTO, whisper))
    }
}
