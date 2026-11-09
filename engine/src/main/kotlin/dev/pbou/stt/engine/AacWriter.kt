package dev.pbou.stt.engine

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodes 16 kHz mono audio to AAC-LC as it is recorded, in ADTS frames: each frame stands alone, so a
 * recording cut short (crash, empty battery) is still readable up to its last frame. [toM4a] puts the
 * frames in an M4A file, the format every player and phone opens, once the recording is over.
 *
 * Not thread-safe: call [write] and [close] from one thread. With [append], new frames go after the
 * existing ones (continuing a recording: ADTS frames simply follow each other).
 */
class AacWriter(file: File, bitrate: Int = BITRATE, append: Boolean = false) : Closeable {
    private val out = BufferedOutputStream(FileOutputStream(file, append))
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val info = MediaCodec.BufferInfo()
    private var samplesIn = 0L
    private var closed = false

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT)
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (t: Throwable) {
            codec.release()
            out.close()
            throw t
        }
    }

    /** Samples in [-1, 1], 16 kHz mono. */
    fun write(samples: FloatArray) {
        var i = 0
        while (i < samples.size) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) {
                drain(endOfStream = false)
                continue
            }
            val buffer = codec.getInputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
            buffer.clear()
            val n = minOf(samples.size - i, buffer.capacity() / 2)
            for (k in 0 until n) {
                buffer.putShort((samples[i + k].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
            }
            codec.queueInputBuffer(index, 0, n * 2, samplesIn * 1_000_000 / SAMPLE_RATE, 0)
            samplesIn += n
            i += n
            drain(endOfStream = false)
        }
    }

    /** Finishes the stream: everything written is in the file afterwards. */
    override fun close() {
        if (closed) return
        closed = true
        try {
            while (true) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    codec.queueInputBuffer(index, 0, 0, samplesIn * 1_000_000 / SAMPLE_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
                drain(endOfStream = false)
            }
            drain(endOfStream = true)
        } finally {
            runCatching { codec.stop() }
            codec.release()
            out.close()
        }
    }

    private fun drain(endOfStream: Boolean) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                index >= 0 -> {
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!config && info.size > 0) {
                        val data = codec.getOutputBuffer(index)!!
                        data.position(info.offset)
                        data.limit(info.offset + info.size)
                        val frame = ByteArray(info.size)
                        data.get(frame)
                        out.write(adtsHeader(frame.size))
                        out.write(frame)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        out.flush()
                        return
                    }
                }
                // INFO_OUTPUT_FORMAT_CHANGED: ADTS headers carry the format, nothing to keep.
            }
        }
    }

    companion object {
        const val BITRATE = 48_000 // speech at 16 kHz: transparent for recognition, about 21 MB per hour
        private const val TIMEOUT_US = 10_000L
        private const val MAX_INPUT = 16_384

        /** The 7-byte ADTS header of an AAC-LC frame of [frameSize] bytes, 16 kHz, mono, no CRC. */
        fun adtsHeader(frameSize: Int): ByteArray {
            val length = frameSize + 7
            val profile = 2 // AAC LC
            val rateIndex = 8 // 16000 Hz
            val channels = 1
            return byteArrayOf(
                0xFF.toByte(),
                0xF1.toByte(), // MPEG-4, no CRC
                (((profile - 1) shl 6) or (rateIndex shl 2) or (channels shr 2)).toByte(),
                (((channels and 3) shl 6) or (length shr 11)).toByte(),
                ((length and 0x7FF) shr 3).toByte(),
                (((length and 7) shl 5) or 0x1F).toByte(),
                0xFC.toByte(),
            )
        }

        /**
         * The reverse of [toM4a], to continue a finished recording: copies the frames of an M4A written by
         * [toM4a] back into an ADTS file. Fails if the audio is not 16 kHz mono AAC (not one of ours).
         */
        fun toAdts(m4a: File, aac: File) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(m4a.path)
                require(extractor.trackCount > 0) { "no audio in ${m4a.name}" }
                val format = extractor.getTrackFormat(0)
                require(format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC) { "not AAC" }
                require(format.getInteger(MediaFormat.KEY_SAMPLE_RATE) == SAMPLE_RATE) { "not 16 kHz" }
                require(format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1) { "not mono" }
                extractor.selectTrack(0)
                val buffer = ByteBuffer.allocate(64 * 1024)
                BufferedOutputStream(FileOutputStream(aac)).use { out ->
                    while (true) {
                        val n = extractor.readSampleData(buffer, 0)
                        if (n < 0) break
                        val frame = ByteArray(n)
                        buffer.position(0)
                        buffer.get(frame, 0, n)
                        out.write(adtsHeader(n))
                        out.write(frame)
                        extractor.advance()
                    }
                }
            } finally {
                extractor.release()
            }
        }

        /**
         * Copies the frames of an ADTS file into an M4A file (no re-encoding: about a second per hour).
         * Returns the duration in milliseconds.
         */
        fun toM4a(aac: File, m4a: File): Long {
            val extractor = MediaExtractor()
            var muxer: MediaMuxer? = null
            try {
                extractor.setDataSource(aac.path)
                require(extractor.trackCount > 0) { "no audio in ${aac.name}" }
                extractor.selectTrack(0)
                val format = extractor.getTrackFormat(0)
                val mux = MediaMuxer(m4a.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                muxer = mux
                val track = mux.addTrack(format)
                mux.start()
                val buffer = ByteBuffer.allocate(format.takeIf { it.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) }?.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) ?: 64 * 1024)
                val info = MediaCodec.BufferInfo()
                var lastUs = 0L
                while (true) {
                    val n = extractor.readSampleData(buffer, 0)
                    if (n < 0) break
                    info.set(0, n, extractor.sampleTime, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    mux.writeSampleData(track, buffer, info)
                    lastUs = extractor.sampleTime
                    extractor.advance()
                }
                mux.stop()
                return lastUs / 1000
            } finally {
                runCatching { muxer?.release() }
                extractor.release()
            }
        }
    }
}
