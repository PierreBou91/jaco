package dev.pbou.stt.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WerTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `case, punctuation and apostrophes do not count`() {
        assertEquals(Wer.Score(0, 6), Wer.score("L'hôpital ouvre demain, à midi.", "l hôpital ouvre demain à midi"))
        assertEquals(listOf("aujourd", "hui", "c", "est", "peut", "être"), Wer.words("Aujourd’hui, c'est peut-être…"))
    }

    @Test fun `counts substitutions, deletions and insertions`() {
        assertEquals(1, Wer.score("le chat dort", "le chien dort").errors)
        assertEquals(1, Wer.score("le chat dort", "le dort").errors)
        assertEquals(1, Wer.score("le chat dort", "le gros chat dort").errors)
        assertEquals(3, Wer.score("le chat dort", "").errors)
        assertEquals(50.0, (Wer.score("a b", "a c") + Wer.score("c d", "c d")).percent * 2, 1e-9)
    }

    @Test fun `alignment marks the words that differ`() {
        val ref = Wer.words("Claire still needs to confirm the room")
        val hyp = Wer.words("Clary still needs to to confirm room")
        val a = Wer.align(ref, hyp)
        assertEquals(listOf(false, true, true, true, true, false, true), a.refOk.toList()) // "claire" replaced, "the" missed
        assertEquals(listOf(false, true, true), a.hypOk.toList().take(3))                 // "clary" wrong
        assertEquals(2, a.hypOk.count { !it })                                            // and one of the "to" added
        assertTrue(a.hypOk[5] && a.hypOk[6])                                              // "confirm room" kept
        assertEquals(3, Wer.distance(ref, hyp))
        assertEquals(listOf(0..0, 2..8), Wer.wordRanges("l'hôpital"))
        assertEquals(Wer.words("Aujourd’hui, c'est peut-être…").size, Wer.wordRanges("Aujourd’hui, c'est peut-être…").size)
    }

    @Test fun `a wav file round-trips`() {
        val samples = FloatArray(16_000) { (it % 200 - 100) / 100f }
        val f = tmp.root.resolve("a/clip.wav")
        Wav.write(f, samples)
        val back = Wav.read(f)
        assertEquals(samples.size, back.size)
        assertArrayEquals(samples, back, 1f / 16_000)
    }
}
