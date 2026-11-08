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

    /**
     * Which words match, after the cheapest alignment: [refOk] is false for reference words that were
     * missed or replaced, [hypOk] false for transcript words that were wrong or added.
     */
    class Alignment(val refOk: BooleanArray, val hypOk: BooleanArray)

    fun align(ref: List<String>, hyp: List<String>): Alignment {
        val d = Array(ref.size + 1) { i -> IntArray(hyp.size + 1) { j -> if (i == 0) j else if (j == 0) i else 0 } }
        for (i in 1..ref.size) for (j in 1..hyp.size) {
            d[i][j] = minOf(d[i - 1][j - 1] + if (ref[i - 1] == hyp[j - 1]) 0 else 1, d[i - 1][j] + 1, d[i][j - 1] + 1)
        }
        val refOk = BooleanArray(ref.size)
        val hypOk = BooleanArray(hyp.size)
        var i = ref.size
        var j = hyp.size
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && ref[i - 1] == hyp[j - 1] && d[i][j] == d[i - 1][j - 1] -> {
                    refOk[i - 1] = true; hypOk[j - 1] = true; i--; j--
                }
                // On a tie, prefer a missed or added word to a substitution: it keeps real matches paired.
                i > 0 && d[i][j] == d[i - 1][j] + 1 -> i--                       // missed word
                j > 0 && d[i][j] == d[i][j - 1] + 1 -> j--                       // added word
                else -> { i--; j-- }                                              // substitution
            }
        }
        return Alignment(refOk, hypOk)
    }

    /**
     * Where each word of [words] sits in `nfc(text)`, for highlighting: the n-th range is the n-th word.
     * Display `nfc(text)`, not [text], so the ranges line up.
     */
    fun wordRanges(text: String): List<IntRange> =
        WORD.findAll(nfc(text)).map { it.range }.toList()

    fun nfc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)

    private val WORD = Regex("[\\p{L}\\p{N}]+")

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
