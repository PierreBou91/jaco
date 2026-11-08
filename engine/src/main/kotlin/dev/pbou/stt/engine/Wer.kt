package dev.pbou.stt.engine

import java.text.Normalizer

/**
 * Word error rate: substitutions + deletions + insertions needed to turn the transcript into the
 * reference, over the number of reference words. Both texts are normalised first, so case,
 * punctuation and apostrophes do not count ("L'hôpital," and "l hôpital" are the same two words).
 */
object Wer {

    data class Score(val errors: Int, val words: Int) {
        val percent: Double get() = if (words == 0) 0.0 else 100.0 * errors / words
        operator fun plus(o: Score) = Score(errors + o.errors, words + o.words)
    }

    fun score(reference: String, hypothesis: String): Score {
        val ref = words(reference)
        return Score(distance(ref, words(hypothesis)), ref.size)
    }

    fun words(text: String): List<String> =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFC)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ") // punctuation, apostrophes and hyphens split words
            .trim()
            .split(' ')
            .filter { it.isNotEmpty() }

    /** Levenshtein distance over words. */
    fun distance(a: List<String>, b: List<String>): Int {
        var prev = IntArray(b.size + 1) { it }
        var cur = IntArray(b.size + 1)
        for (i in 1..a.size) {
            cur[0] = i
            for (j in 1..b.size) {
                val sub = prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(sub, prev[j] + 1, cur[j - 1] + 1)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.size]
    }
}
