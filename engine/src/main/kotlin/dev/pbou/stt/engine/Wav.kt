package dev.pbou.stt.engine

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16-bit PCM mono WAV at [SAMPLE_RATE], the format the benchmark keeps its recordings in. */
object Wav {

    fun write(file: File, samples: FloatArray) {
        val data = samples.size * 2
        val buf = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
        buf.put("data".toByteArray()).putInt(data)
        for (s in samples) buf.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(buf.array())
        if (!tmp.renameTo(file)) throw IOException("could not write $file")
    }

    /** Reads what [write] wrote (and any 16-bit mono 16 kHz WAV with a plain header layout). */
    fun read(file: File): FloatArray {
        val buf = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.remaining() < 12 || String(buf.array(), 0, 4) != "RIFF" || String(buf.array(), 8, 4) != "WAVE") {
            throw IOException("$file is not a WAV file")
        }
        buf.position(12)
        while (buf.remaining() >= 8) {
            val id = ByteArray(4).also { buf.get(it) }.let(::String)
            val size = buf.int
            if (id == "fmt ") {
                val format = buf.short.toInt(); val channels = buf.short.toInt(); val rate = buf.int
                buf.position(buf.position() + 6)
                val bits = buf.short.toInt()
                if (format != 1 || channels != 1 || rate != SAMPLE_RATE || bits != 16) throw IOException("$file: unsupported WAV format")
                buf.position(buf.position() + size - 16)
            } else if (id == "data") {
                val n = minOf(size, buf.remaining()) / 2
                return FloatArray(n) { buf.short / 32768f }
            } else {
                buf.position(buf.position() + size)
            }
        }
        throw IOException("$file has no audio")
    }
}
