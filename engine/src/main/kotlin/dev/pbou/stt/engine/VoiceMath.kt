package dev.pbou.stt.engine

import kotlin.math.sqrt

/**
 * Comparing voices: a voice is an embedding (a few hundred numbers from the speaker model), and two
 * recordings of the same person give embeddings pointing the same way. Plain JVM code, unit-tested.
 */
object VoiceMath {

    /** Cosine similarity in [-1, 1]: about 1 for the same voice, near 0 for different ones. */
    fun similarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        return if (na == 0.0 || nb == 0.0) 0f else (dot / sqrt(na * nb)).toFloat()
    }

    /** [v] scaled to length 1. */
    fun normalize(v: FloatArray): FloatArray {
        var n = 0.0
        for (x in v) n += x * x
        val len = sqrt(n).toFloat()
        return if (len == 0f) v.copyOf() else FloatArray(v.size) { v[it] / len }
    }

    /** The weighted average direction of [vectors] (each normalized first), normalized. */
    fun mean(vectors: List<FloatArray>, weights: List<Float> = vectors.map { 1f }): FloatArray {
        require(vectors.isNotEmpty())
        val out = FloatArray(vectors[0].size)
        vectors.forEachIndexed { k, v ->
            val u = normalize(v)
            val w = weights[k]
            for (i in out.indices) out[i] += u[i] * w
        }
        return normalize(out)
    }

    /**
     * Groups [vectors] that sound like the same person: agglomerative clustering, average linkage on
     * cosine distance. Merging stops when the closest groups are further apart than [maxDistance], or
     * when [count] groups are left (then [maxDistance] is ignored). Returns a group index per vector,
     * numbered in the order the groups first appear in [vectors].
     */
    fun cluster(vectors: List<FloatArray>, maxDistance: Float, count: Int? = null): IntArray {
        val n = vectors.size
        if (n == 0) return IntArray(0)
        val unit = vectors.map(::normalize)
        // Pairwise distances, kept as sums so average linkage updates in O(n) per merge.
        val sim = Array(n) { i -> DoubleArray(n) { j -> dot(unit[i], unit[j]) } }
        val members = MutableList(n) { mutableListOf(it) }
        val alive = BooleanArray(n) { true }
        var groups = n
        val target = count?.coerceIn(1, n)
        while (groups > 1) {
            var best = -1
            var bestJ = -1
            var bestSim = Double.NEGATIVE_INFINITY
            for (i in 0 until n) {
                if (!alive[i]) continue
                for (j in i + 1 until n) {
                    if (!alive[j]) continue
                    val s = sim[i][j] / (members[i].size * members[j].size)
                    if (s > bestSim) {
                        bestSim = s; best = i; bestJ = j
                    }
                }
            }
            val distance = 1 - bestSim
            if (target != null) {
                if (groups <= target) break
            } else if (distance > maxDistance) break
            // Merge bestJ into best: sums of similarities add up.
            for (k in 0 until n) {
                if (!alive[k] || k == best || k == bestJ) continue
                sim[best][k] += sim[bestJ][k]
                sim[k][best] = sim[best][k]
            }
            members[best].addAll(members[bestJ])
            alive[bestJ] = false
            groups--
        }
        val label = IntArray(n)
        val order = (0 until n).filter { alive[it] }.sortedBy { members[it].min() }
        order.forEachIndexed { g, root -> members[root].forEach { label[it] = g } }
        return label
    }

    private fun dot(a: FloatArray, b: FloatArray): Double {
        var s = 0.0
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}
