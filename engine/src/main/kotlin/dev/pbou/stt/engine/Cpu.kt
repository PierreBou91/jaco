package dev.pbou.stt.engine

import java.io.File

/**
 * The phone's processor. Most phones mix fast cores with slower, frugal ones; a model splits each step
 * evenly across its threads and waits for the slowest, so threads on the slow cores hold the others
 * back, and past a few threads they also compete for memory.
 *
 * Measured with ThreadsBenchmark (Parakeet and Cohere, 8 threads offered):
 * - Honor 90 (4 cores at 1.8 GHz, 3 at 2.4, 1 at 2.5): 4 threads is the fastest, 8 is as slow as 1;
 * - Galaxy Z Flip6 (2 at 2.3, 2 at 3.0, 3 at 3.1, 1 at 3.4): 4 or 5 is the fastest, 6 is already
 *   25 to 40% slower, 8 slower than 1 for Parakeet.
 */
object Cpu {
    val cores: Int = Runtime.getRuntime().availableProcessors()

    /** Likely the fastest number of threads on this phone (see [bestThreads]). */
    val bestThreads: Int by lazy {
        bestThreads((0 until cores).map { readMaxFreq(it) }) ?: minOf(cores, MAX_BEST)
    }

    /**
     * One thread per core nearly as fast as the fastest one, at most [MAX_BEST]: both phones measured
     * peak around 4. Null if a core's speed cannot be read.
     */
    fun bestThreads(maxFreqs: List<Long?>): Int? {
        if (maxFreqs.isEmpty() || maxFreqs.any { it == null || it <= 0 }) return null
        val fastest = maxFreqs.maxOf { it!! }
        val fast = maxFreqs.count { it!! >= fastest * FAST_RATIO }
        return fast.coerceAtMost(MAX_BEST)
    }

    private fun readMaxFreq(core: Int): Long? = runCatching {
        File("/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
    }.getOrNull()

    private const val MAX_BEST = 4
    private const val FAST_RATIO = 0.75
}
