package dev.pbou.stt.engine

import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Transcribes an audio file (a voice note, a recording, the sound of a video), streaming it so that
 * long files never sit in memory: decode, convert to 16 kHz mono, cut into sentences at pauses
 * (Silero VAD, as in live dictation), transcribe each sentence as soon as it is cut.
 */
class FileTranscriber(private val assets: AssetManager, private val host: EngineHost) {

    data class Segment(val startMs: Long, val endMs: Long, val text: String)

    /** Called from background threads; progress may arrive out of order (keep the largest). */
    interface Listener {
        /** Called once the file is opened. [durationMs] is null if the file does not say. */
        fun onOpened(durationMs: Long?)
        /** [doneMs] of the audio is transcribed. */
        fun onProgress(doneMs: Long)
        fun onSegment(segment: Segment)
    }

    /** Throws [UnsupportedAudioException] if the file has no audio Android can decode. */
    suspend fun transcribe(file: File, spec: ModelSpec, options: EngineOptions, listener: Listener) = coroutineScope {
        // Few sentences wait at a time: decoding is far faster than transcribing, and audio is big.
        val sentences = Channel<Pair<Long, FloatArray>>(capacity = 2)
        // Sentences sent but not transcribed yet: while there are none, progress follows the decoding
        // (so long silences or music still move the bar).
        val inFlight = AtomicInteger(0)
        val producer = launch(Dispatchers.IO) {
            val vad = Mic.vad(assets, silenceSeconds = PAUSE_S, maxSpeechSeconds = MAX_SENTENCE_S)
            try {
                val pending = FloatArray(Mic.CHUNK)
                var filled = 0
                suspend fun drain() {
                    while (!vad.empty()) {
                        val seg = vad.front()
                        inFlight.incrementAndGet()
                        sentences.send(seg.start to seg.samples)
                        vad.pop()
                    }
                }
                var decoded = 0L
                decode(file, listener) { chunk ->
                    decoded += chunk.size
                    // The VAD takes fixed windows of 512 samples.
                    var i = 0
                    while (i < chunk.size) {
                        val n = minOf(chunk.size - i, Mic.CHUNK - filled)
                        chunk.copyInto(pending, filled, i, i + n)
                        filled += n
                        i += n
                        if (filled == Mic.CHUNK) {
                            vad.acceptWaveform(pending.copyOf())
                            filled = 0
                            drain()
                        }
                    }
                    if (inFlight.get() == 0 && !vad.isSpeechDetected()) listener.onProgress(decoded * 1000 / SAMPLE_RATE)
                }
                if (filled > 0) vad.acceptWaveform(pending.copyOf(filled))
                vad.flush()
                drain()
            } finally {
                vad.release()
                sentences.close()
            }
        }
        for ((start, samples) in sentences) {
            val text = host.use(spec, options) { it.transcribe(samples) }
            val startMs = start * 1000 / SAMPLE_RATE
            val endMs = (start + samples.size) * 1000 / SAMPLE_RATE
            if (text.isNotBlank()) listener.onSegment(Segment(startMs, endMs, text))
            inFlight.decrementAndGet()
            listener.onProgress(endMs)
        }
        producer.join()
    }

    /** Streams the first audio track of [file] as 16 kHz mono chunks. */
    private suspend fun decode(file: File, listener: Listener, onChunk: suspend (FloatArray) -> Unit) = withContext(Dispatchers.IO) {
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
            listener.onOpened(if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else null)

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

    private companion object {
        const val PAUSE_S = 0.8f // longer than live dictation: whole sentences read better and give the model context
        const val MAX_SENTENCE_S = 20f
        const val TIMEOUT_US = 10_000L
    }
}

class UnsupportedAudioException(message: String) : Exception(message)
