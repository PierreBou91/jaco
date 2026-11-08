package dev.pbou.stt.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class AacWriterTest {
    private fun ByteArray.u(i: Int) = this[i].toInt() and 0xFF

    @Test fun `adts header describes a 16 kHz mono AAC-LC frame`() {
        val h = AacWriter.adtsHeader(100)
        assertEquals(7, h.size)
        assertEquals(0xFFF, (h.u(0) shl 4) or (h.u(1) shr 4)) // sync word
        assertEquals(1, h.u(1) and 1) // no CRC
        assertEquals(1, h.u(2) shr 6) // profile - 1 = LC
        assertEquals(8, (h.u(2) shr 2) and 0xF) // 16000 Hz
        assertEquals(1, ((h.u(2) and 1) shl 2) or (h.u(3) shr 6)) // mono
        assertEquals(107, ((h.u(3) and 3) shl 11) or (h.u(4) shl 3) or (h.u(5) shr 5)) // frame + header
    }

    @Test fun `long frames keep their length`() {
        val h = AacWriter.adtsHeader(5000)
        assertEquals(5007, ((h.u(3) and 3) shl 11) or (h.u(4) shl 3) or (h.u(5) shr 5))
    }
}
