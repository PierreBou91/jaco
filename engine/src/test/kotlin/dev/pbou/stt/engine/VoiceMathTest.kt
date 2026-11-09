package dev.pbou.stt.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class VoiceMathTest {
    private val rnd = Random(7)

    /** A voice near [base]: the same person in another stretch of audio. */
    private fun near(base: FloatArray, noise: Float = 0.3f) = FloatArray(base.size) { base[it] + noise * (rnd.nextFloat() - 0.5f) }
    private fun voice() = FloatArray(64) { rnd.nextFloat() - 0.5f }

    @Test fun `similarity of a voice with itself and with an unrelated one`() {
        val a = voice()
        assertEquals(1f, VoiceMath.similarity(a, a), 1e-5f)
        assertEquals(0f, VoiceMath.similarity(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-6f)
        assertEquals(0f, VoiceMath.similarity(floatArrayOf(0f, 0f), a.copyOf(2)), 0f)
    }

    @Test fun `groups pieces by person, numbered by first appearance`() {
        val anna = voice()
        val ben = voice()
        val pieces = listOf(near(ben), near(anna), near(ben), near(anna), near(ben))
        assertArrayEquals(intArrayOf(0, 1, 0, 1, 0), VoiceMath.cluster(pieces, maxDistance = 0.5f))
    }

    @Test fun `a known count overrides the distance`() {
        val anna = voice()
        val ben = voice()
        val pieces = listOf(near(anna), near(ben), near(anna))
        assertArrayEquals(intArrayOf(0, 0, 0), VoiceMath.cluster(pieces, maxDistance = 0.5f, count = 1))
        assertArrayEquals(intArrayOf(0, 1, 2), VoiceMath.cluster(pieces, maxDistance = 0.5f, count = 3))
        assertArrayEquals(intArrayOf(0, 1, 0), VoiceMath.cluster(pieces, maxDistance = 0f, count = 2))
    }

    @Test fun `nothing is merged under a zero distance, everything under a large one`() {
        val pieces = listOf(voice(), voice(), voice())
        assertArrayEquals(intArrayOf(0, 1, 2), VoiceMath.cluster(pieces, maxDistance = 0f))
        assertArrayEquals(intArrayOf(0, 0, 0), VoiceMath.cluster(pieces, maxDistance = 2f))
        assertEquals(0, VoiceMath.cluster(emptyList(), 0.5f).size)
    }

    @Test fun `weighted mean leans to the heavier voice`() {
        val m = VoiceMath.mean(listOf(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), listOf(3f, 1f))
        assertEquals(1f, m[0] * m[0] + m[1] * m[1], 1e-5f)
        assertEquals(3f, m[0] / m[1], 1e-4f)
    }
}
