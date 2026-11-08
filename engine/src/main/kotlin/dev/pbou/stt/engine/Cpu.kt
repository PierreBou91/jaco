package dev.pbou.stt.engine

import java.io.File

/**
 * The phone's processor. Most phones mix fast cores with slower, frugal ones; a model splits each step
 * evenly across its threads and waits for the slowest, so threads on the slow cores hold the others
 * back. Measured on a Honor 90 (4 fast cores, 4 slow): 4 threads is the fastest, 8 is as slow as 1.
 */
object Cpu {
    val cores: Int = Runtime.getRuntime().availableProcessors()

    /** The cores faster than the slowest group, or every core if they are all alike. */
    val fastCores: Int by lazy {
        fastCores((0 until cores).map { readMaxFreq(it) }) ?: minOf(cores, FALLBACK)
    }

    /** Null if a core's speed cannot be read. */
    fun fastCores(maxFreqs: List<Long?>): Int? {
        if (maxFreqs.isEmpty() || maxFreqs.any { it == null || it <= 0 }) return null
        val slowest = maxFreqs.minOf { it!! }
        val fast = maxFreqs.count { it!! > slowest }
        return if (fast == 0) maxFreqs.size else fast
    }

    private fun readMaxFreq(core: Int): Long? = runCatching {
        File("/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
    }.getOrNull()

    private const val FALLBACK = 4
}
