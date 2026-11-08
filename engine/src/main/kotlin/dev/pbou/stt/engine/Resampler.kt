package dev.pbou.stt.engine

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Streaming sample-rate converter (polyphase windowed-sinc), for audio files: 48 kHz, 44.1 kHz,
 * 24 kHz, 8 kHz... to [outRate]. Low-passes before downsampling, so high frequencies do not fold
 * back into the speech band. Feed chunks to [process], then call [flush] once at the end.
 */
class Resampler(inRate: Int, outRate: Int = SAMPLE_RATE) {

    private val up: Int        // L: output samples per "period"
    private val down: Int      // M: input samples per "period"
    private val half: Int      // filter half-length, in input samples
    private val filters: Array<FloatArray>

    init {
        require(inRate > 0 && outRate > 0)
        val g = gcd(inRate, outRate)
        up = outRate / g
        down = inRate / g
        require(up <= MAX_PHASES) { "unsupported rate conversion $inRate -> $outRate" }
        val cutoff = min(1.0, up.toDouble() / down) * 0.92 // fraction of the input Nyquist frequency
        half = ceil(ZERO_CROSSINGS / cutoff).toInt()
        filters = Array(up) { p ->
            val f = FloatArray(2 * half) { k ->
                // Tap k reads input n - half + 1 + k for an output at input position n + p / up.
                val x = (k - half + 1) - p.toDouble() / up
                val w = if (x <= -half || x >= half) 0.0 else 0.5 + 0.5 * cos(PI * x / half) // Hann window
                (cutoff * sinc(cutoff * x) * w).toFloat()
            }
            val sum = f.sum()
            for (i in f.indices) f[i] /= sum // unity gain at DC
            f
        }
    }

    private val passthrough = up == 1 && down == 1

    // Input not yet consumed; buf[0] is input sample number bufStart (negative: the zero padding before the start).
    private var buf = FloatArray(4096)
    private var bufLen = 0
    private var bufStart = -half.toLong()
    private var inputCount = 0L
    private var nextOut = 0L
    private var flushed = false

    init {
        bufLen = half // zeros before the first sample
    }

    fun process(input: FloatArray): FloatArray {
        check(!flushed) { "process() after flush()" }
        if (passthrough) return input.copyOf()
        append(input, input.size)
        inputCount += input.size
        return produce(bufStart + bufLen)
    }

    /** The last outputs, which need the zeros after the end of the input. */
    fun flush(): FloatArray {
        if (flushed || passthrough) return FloatArray(0).also { flushed = true }
        flushed = true
        append(FloatArray(half), half)
        return produce(bufStart + bufLen, limitToInput = true)
    }

    private fun produce(available: Long, limitToInput: Boolean = false): FloatArray {
        val result = FloatArrayBuilder()
        while (true) {
            val pos = nextOut * down
            val n = pos / up
            val p = (pos % up).toInt()
            if (n + half > available - 1) break      // needs input not received yet
            if (limitToInput && n >= inputCount) break
            val f = filters[p]
            val first = (n - half + 1 - bufStart).toInt()
            var acc = 0f
            for (k in f.indices) acc += f[k] * buf[first + k]
            result.add(acc)
            nextOut++
        }
        // Drop the input no output will need again.
        val keepFrom = (nextOut * down / up) - half + 1
        val drop = (keepFrom - bufStart).toInt().coerceIn(0, bufLen)
        if (drop > 0) {
            System.arraycopy(buf, drop, buf, 0, bufLen - drop)
            bufLen -= drop
            bufStart += drop
        }
        return result.toArray()
    }

    private fun append(src: FloatArray, n: Int) {
        if (bufLen + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, bufLen + n))
        System.arraycopy(src, 0, buf, bufLen, n)
        bufLen += n
    }

    private class FloatArrayBuilder {
        private var a = FloatArray(1024)
        private var n = 0
        fun add(v: Float) {
            if (n == a.size) a = a.copyOf(a.size * 2)
            a[n++] = v
        }
        fun toArray() = a.copyOf(n)
    }

    private companion object {
        const val ZERO_CROSSINGS = 16
        const val MAX_PHASES = 4000

        fun sinc(x: Double) = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
        tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    }
}
