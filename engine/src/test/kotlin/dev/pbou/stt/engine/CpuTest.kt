package dev.pbou.stt.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CpuTest {
    @Test fun `honor 90, four slow cores and four fast`() =
        assertEquals(4, Cpu.bestThreads(listOf(1804800L, 1804800, 1804800, 1804800, 2361600, 2361600, 2361600, 2515200)))

    @Test fun `galaxy z flip6, six fast cores but four is best`() =
        assertEquals(4, Cpu.bestThreads(listOf(2265600L, 2265600, 3148800, 3148800, 3148800, 2956800, 2956800, 3398400)))

    @Test fun `two fast cores and six slow`() =
        assertEquals(2, Cpu.bestThreads(listOf(2000000L, 2000000, 2000000, 2000000, 2000000, 2000000, 2800000, 2800000)))

    @Test fun `four alike cores`() = assertEquals(4, Cpu.bestThreads(List(4) { 2000000L }))

    @Test fun `unreadable means unknown`() = assertNull(Cpu.bestThreads(listOf(2000000L, null)))
}
