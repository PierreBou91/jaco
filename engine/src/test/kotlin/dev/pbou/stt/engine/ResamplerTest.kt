package dev.pbou.stt.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class ResamplerTest {

    private fun tone(rate: Int, hz: Double, seconds: Double) =
        FloatArray((rate * seconds).toInt()) { (0.5 * sin(2 * PI * hz * it / rate)).toFloat() }

    private fun resample(r: Resampler, input: FloatArray, chunk: Int): FloatArray {
        val parts = ArrayList<FloatArray>()
        var i = 0
        while (i < input.size) {
            parts += r.process(input.copyOfRange(i, minOf(i + chunk, input.size)))
            i += chunk
        }
        parts += r.flush()
        return FloatArray(parts.sumOf { it.size }).also { out ->
            var o = 0
            for (p in parts) { p.copyInto(out, o); o += p.size }
        }
    }

    private fun rms(a: FloatArray, from: Int, to: Int) = sqrt((from until to).sumOf { a[it].toDouble() * a[it] } / (to - from))

    @Test fun `speech-band tones keep their shape and the length is right`() {
        for (rate in listOf(48_000, 44_100, 22_050, 8_000)) {
            val out = resample(Resampler(rate), tone(rate, 440.0, 1.0), 1_000)
            assertEquals("length from $rate Hz", 16_000.0, out.size.toDouble(), 2.0)
            val expected = tone(16_000, 440.0, 1.0)
            var worst = 0.0
            for (i in 200 until out.size - 200) worst = maxOf(worst, abs(out[i] - expected[i]).toDouble())
            assertTrue("max error ${"%.4f".format(worst)} from $rate Hz", worst < 0.01)
        }
    }

    @Test fun `frequencies above the new Nyquist limit are removed, not folded back`() {
        val out = resample(Resampler(48_000), tone(48_000, 11_000.0, 1.0), 4_096)
        assertTrue(rms(out, 500, out.size - 500) < 0.005) // 0.35 before: at least 37 dB down
    }

    @Test fun `chunk size does not change the result`() {
        val input = tone(44_100, 1_000.0, 0.5)
        assertArrayEquals(resample(Resampler(44_100), input, 100_000), resample(Resampler(44_100), input, 333), 1e-6f)
    }

    @Test fun `16 kHz passes through unchanged`() {
        val input = tone(16_000, 300.0, 0.1)
        assertArrayEquals(input, resample(Resampler(16_000), input, 512), 0f)
    }
}
