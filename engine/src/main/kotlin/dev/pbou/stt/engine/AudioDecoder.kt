package dev.pbou.stt.engine

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder

/** Decodes audio files with Android's codecs (Opus, AAC, MP3, WAV, the sound of a video...). */
object AudioDecoder {

    /** Streams the first audio track of [file] as 16 kHz mono chunks. */
    suspend fun decode(file: File, onOpened: (durationMs: Long?) -> Unit = {}, onChunk: suspend (FloatArray) -> Unit) = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            try {
                extractor.setDataSource(file.path)
            } catch (e: Exception) {
                throw UnsupportedAudioException("cannot open: ${e.message}")
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw UnsupportedAudioException("no audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            onOpened(if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else null)

            val decoder = try {
                MediaCodec.createDecoderByType(mime).also { it.configure(format, null, null, 0); it.start() }
            } catch (e: Exception) {
                throw UnsupportedAudioException("no decoder for $mime")
            }
            codec = decoder
            var channels = format.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 1
            var rate = format.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: SAMPLE_RATE
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var resampler: Resampler? = null
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            val ctx = currentCoroutineContext()
            while (true) {
                ctx.ensureActive()
                if (!inputDone) {
                    val i = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val n = extractor.readSampleData(decoder.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = decoder.outputFormat
                        channels = f.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: channels
                        rate = f.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: rate
                        encoding = f.getIntegerOrNull(MediaFormat.KEY_PCM_ENCODING) ?: AudioFormat.ENCODING_PCM_16BIT
                        resampler = null
                    }
                    o >= 0 -> {
                        val out = decoder.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        val mono = toMono(out, channels, encoding)
                        decoder.releaseOutputBuffer(o, false)
                        if (mono.isNotEmpty()) {
                            val r = resampler ?: Resampler(rate).also { resampler = it }
                            onChunk(r.process(mono))
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            resampler?.flush()?.let { if (it.isNotEmpty()) onChunk(it) }
                            break
                        }
                    }
                }
            }
        } finally {
            runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }

    private fun toMono(buf: java.nio.ByteBuffer, channels: Int, encoding: Int): FloatArray {
        val c = channels.coerceAtLeast(1)
        return when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val f = buf.asFloatBuffer()
                FloatArray(f.remaining() / c) { i -> var s = 0f; for (k in 0 until c) s += f.get(i * c + k); s / c }
            }
            else -> {
                val sh = buf.asShortBuffer()
                FloatArray(sh.remaining() / c) { i -> var s = 0f; for (k in 0 until c) s += sh.get(i * c + k); s / c / 32768f }
            }
        }
    }

    private fun MediaFormat.getIntegerOrNull(key: String) = if (containsKey(key)) getInteger(key) else null

    private const val TIMEOUT_US = 10_000L
}

class UnsupportedAudioException(message: String) : Exception(message)
