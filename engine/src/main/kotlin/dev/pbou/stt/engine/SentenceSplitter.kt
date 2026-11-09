package dev.pbou.stt.engine

/**
 * Cuts speech that runs on too long into pieces. The pause detector usually ends a sentence within
 * 20 s, but in a lively conversation (a call, two people talking over each other) it may find no pause
 * for minutes: one "sentence" then covers minutes, with one timestamp, and models made for short audio
 * (Whisper, Cohere: 30 s windows) may drop words. Each cut is made at the quietest moment available.
 */
object SentenceSplitter {
    const val MAX_SAMPLES = 25 * SAMPLE_RATE
    private const val WINDOW = SAMPLE_RATE / 10 // loudness measured over 100 ms

    /** Pieces of at most [max] samples, with their offset in [samples]. One piece if it is short enough. */
    fun split(samples: FloatArray, max: Int = MAX_SAMPLES, window: Int = WINDOW): List<Pair<Int, FloatArray>> {
        if (samples.size <= max) return listOf(0 to samples)
        val pieces = ArrayList<Pair<Int, FloatArray>>()
        var pos = 0
        while (samples.size - pos > max) {
            // Cut in the second half of the allowed span: pieces stay long enough to give context.
            var best = pos + max - window
            var bestEnergy = Double.MAX_VALUE
            var i = pos + max / 2
            while (i + window <= pos + max) {
                var e = 0.0
                for (k in i until i + window) e += samples[k] * samples[k]
                if (e < bestEnergy) {
                    bestEnergy = e
                    best = i
                }
                i += window / 2
            }
            val cut = best + window / 2
            pieces += pos to samples.copyOfRange(pos, cut)
            pos = cut
        }
        pieces += pos to samples.copyOfRange(pos, samples.size)
        return pieces
    }
}
