package dev.pbou.stt.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {

    @Test fun `ids are unique and safe as folder names`() {
        val ids = ModelCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { assertTrue(it, it.matches(Regex("[a-z0-9.-]+"))) }
    }

    @Test fun `every file has a size and a sha256`() {
        for (spec in ModelCatalog.all) {
            assertTrue(spec.id, spec.files.isNotEmpty())
            for (f in spec.files) {
                assertTrue("${spec.id}/${f.path}", f.size > 0)
                assertTrue("${spec.id}/${f.path}", f.sha256.matches(Regex("[0-9a-f]{64}")))
            }
        }
    }

    @Test fun `exactly one model is recommended, and it is Parakeet`() {
        assertEquals(listOf(ModelCatalog.PARAKEET), ModelCatalog.all.filter { it.recommended })
    }

    @Test fun `models that detect the language ignore the setting`() {
        assertNull(ModelCatalog.PARAKEET.effectiveLanguage("fr", listOf("en")))
    }

    @Test fun `whisper uses the chosen language, or detects it on auto`() {
        assertEquals("fr", ModelCatalog.WHISPER_SMALL.effectiveLanguage("fr", emptyList()))
        assertNull(ModelCatalog.WHISPER_SMALL.effectiveLanguage(ModelSpec.AUTO, listOf("fr")))
    }

    @Test fun `models that need a language fall back to the device languages, then English`() {
        val canary = ModelCatalog.CANARY
        assertEquals("de", canary.effectiveLanguage("de", listOf("fr")))
        assertEquals("fr", canary.effectiveLanguage(ModelSpec.AUTO, listOf("ja", "fr")))
        assertEquals("fr", canary.effectiveLanguage("it", listOf("fr"))) // Italian is not supported by Canary
        assertEquals("en", canary.effectiveLanguage(ModelSpec.AUTO, listOf("ja")))
    }
}
