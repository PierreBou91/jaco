package dev.pbou.stt.engine

import android.content.res.AssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.File
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
                        // Talk without pauses (a call) can make "sentences" of minutes: cut them (25 s at most).
                        for ((at, piece) in SentenceSplitter.split(seg.samples)) {
                            inFlight.incrementAndGet()
                            sentences.send(seg.start + at to piece)
                        }
                        vad.pop()
                    }
                }
                var decoded = 0L
                AudioDecoder.decode(file, listener::onOpened) { chunk ->
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

    private companion object {
        const val PAUSE_S = 0.8f // longer than live dictation: whole sentences read better and give the model context
        const val MAX_SENTENCE_S = 20f
    }
}

