package dev.pbou.stt.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class SentenceSplitterTest {
    private val rate = 1000 // small numbers: 1 "second" = 1000 samples

    /** Loud audio of [seconds], with quiet moments at [quietAt] (seconds). */
    private fun speech(seconds: Int, vararg quietAt: Double): FloatArray = FloatArray(seconds * rate) { i ->
        val t = i.toDouble() / rate
        if (quietAt.any { abs(t - it) < 0.15 }) 0.001f else (0.5 * sin(i * 0.3)).toFloat()
    }

    @Test fun `short speech stays whole`() {
        val s = speech(10)
        val pieces = SentenceSplitter.split(s, max = 25 * rate, window = rate / 10)
        assertEquals(1, pieces.size)
        assertEquals(0, pieces[0].first)
    }

    @Test fun `long speech is cut at its quiet moments`() {
        val s = speech(60, 18.0, 40.0)
        val pieces = SentenceSplitter.split(s, max = 25 * rate, window = rate / 10)
        assertEquals(3, pieces.size)
        assertEquals(18.0, pieces[1].first.toDouble() / rate, 0.15)
        assertEquals(40.0, pieces[2].first.toDouble() / rate, 0.15)
    }

    @Test fun `pieces cover everything, in order, none too long`() {
        val s = speech(130) // no quiet moment at all
        val pieces = SentenceSplitter.split(s, max = 25 * rate, window = rate / 10)
        var expected = 0
        for ((offset, piece) in pieces) {
            assertEquals(expected, offset)
            assertTrue(piece.size <= 25 * rate)
            expected += piece.size
        }
        assertEquals(s.size, expected)
    }
}
