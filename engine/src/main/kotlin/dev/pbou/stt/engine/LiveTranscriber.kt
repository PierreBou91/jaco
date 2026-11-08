package dev.pbou.stt.engine

import android.annotation.SuppressLint
import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Live dictation with an offline model ("simulated streaming"):
 * - Silero VAD (voice activity detection) splits the microphone audio into utterances at each pause;
 * - while you speak, the current utterance is re-transcribed about twice a second as a preview ([State.partial]);
 * - when you pause, the utterance is transcribed once more and appended to [State.segments].
 *
 * Listening starts at once, even if the model is still loading: utterances queue up and are transcribed
 * as soon as it is ready. Utterances are transcribed one at a time, in order; a preview is skipped
 * whenever a finished utterance is waiting, and dropped if it comes back after its utterance ended.
 */
class LiveTranscriber(private val assets: AssetManager, private val host: EngineHost) {

    data class Config(
        val spec: ModelSpec,
        val options: EngineOptions,
        /** Silence that ends an utterance. */
        val pauseSeconds: Float = 0.6f,
        /** Show the words while speaking (costs a decode about every 0.5 s). */
        val partials: Boolean = true,
        /** Free the model's memory once the session ends. */
        val releaseAfter: Boolean = false,
    )

    enum class Phase { IDLE, STARTING, LISTENING, FINISHING }

    data class Segment(val text: String, val audioMs: Long, val decodeMs: Long)

    sealed interface Problem {
        /** The microphone could not be opened (permission, or another app holds it). */
        data object Microphone : Problem

        data class Engine(val message: String) : Problem
    }

    data class State(
        val phase: Phase = Phase.IDLE,
        val segments: List<Segment> = emptyList(),
        val partial: String = "",
        val problem: Problem? = null,
    ) {
        val text: String get() = segments.joinToString(" ") { it.text }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Microphone loudness in [0, 1], updated about 30 times a second while listening. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    @Volatile private var recording = false
    private var session: Job? = null

    val isRunning: Boolean get() = session?.isActive == true

    fun start(scope: CoroutineScope, config: Config) {
        if (isRunning) return
        recording = true
        _state.update { it.copy(phase = Phase.STARTING, partial = "", problem = null) }
        session = scope.launch(Dispatchers.Default) {
            val queue = DecodeQueue(config)
            val decoding = launch { queue.run() }
            try {
                capture(config, queue)
            } catch (e: MicrophoneException) {
                _state.update { it.copy(problem = Problem.Microphone) }
            } catch (e: SecurityException) {
                _state.update { it.copy(problem = Problem.Microphone) }
            } finally {
                recording = false
                _level.value = 0f
                _state.update { if (it.phase == Phase.IDLE) it else it.copy(phase = Phase.FINISHING) }
                queue.close()
            }
            decoding.join()
            if (config.releaseAfter) host.release()
            _state.update { it.copy(phase = Phase.IDLE, partial = "") }
        }
    }

    /** Stops listening; what was already said is still transcribed (phase FINISHING, then IDLE). */
    fun stop() {
        if (!recording) return
        recording = false
        _state.update { if (it.phase == Phase.IDLE) it else it.copy(phase = Phase.FINISHING) }
    }

    fun clear() = _state.update { it.copy(segments = emptyList(), partial = "") }

    /** Puts back segments removed by [clear] (undo). */
    fun restore(segments: List<Segment>) = _state.update { it.copy(segments = segments + it.segments) }

    fun dismissProblem() = _state.update { it.copy(problem = null) }

    @SuppressLint("MissingPermission") // the caller asks for RECORD_AUDIO; a SecurityException is handled
    private suspend fun capture(config: Config, queue: DecodeQueue) = withContext(Dispatchers.IO) {
        val vad = Vad(
            assets,
            VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = "vad/silero_vad.onnx", threshold = 0.5f, minSilenceDuration = config.pauseSeconds,
                    minSpeechDuration = 0.25f, windowSize = CHUNK, maxSpeechDuration = MAX_UTTERANCE_S,
                ),
                sampleRate = SAMPLE_RATE, numThreads = 1,
            ),
        )
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, max(minBuffer, SAMPLE_RATE * 2),
        )
        try {
            if (rec.state != AudioRecord.STATE_INITIALIZED) throw MicrophoneException()
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw MicrophoneException()
            _state.update { it.copy(phase = Phase.LISTENING) }

            val chunk = ShortArray(CHUNK)
            val preRoll = ArrayDeque<FloatArray>() // audio just before speech starts, so first syllables are kept
            val current = ArrayList<FloatArray>()  // the utterance being spoken, for previews
            var lastPartial = 0L
            var level = 0f
            while (recording && isActive) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n < 0) throw MicrophoneException()
                if (n == 0) continue
                val samples = FloatArray(n) { chunk[it] / 32768f }
                level = max(loudness(samples), level * 0.85f)
                _level.value = level

                vad.acceptWaveform(samples)
                if (vad.isSpeechDetected()) {
                    if (current.isEmpty()) current.addAll(preRoll)
                    current.add(samples)
                    val now = SystemClock.elapsedRealtime()
                    if (config.partials && now - lastPartial >= PARTIAL_INTERVAL_MS) {
                        lastPartial = now
                        queue.offerPartial(concat(current))
                    }
                } else {
                    preRoll.addLast(samples)
                    while (preRoll.size > PRE_ROLL_CHUNKS) preRoll.removeFirst()
                }
                while (!vad.empty()) {
                    queue.offerFinal(vad.front().samples)
                    vad.pop()
                    current.clear()
                }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
            vad.flush()
            while (!vad.empty()) {
                queue.offerFinal(vad.front().samples)
                vad.pop()
            }
            vad.release()
        }
    }

    /** Finished utterances first, in order; at most one pending preview, always the latest. */
    private inner class DecodeQueue(private val config: Config) {
        private val lock = Any()
        private val finals = ArrayDeque<FloatArray>()
        private var partial: FloatArray? = null
        private var closed = false
        private var generation = 0 // bumped by each finished utterance; older previews are stale
        private val wake = Channel<Unit>(Channel.CONFLATED)
        private var engineFailed = false

        fun offerFinal(samples: FloatArray) {
            synchronized(lock) { finals.addLast(samples); partial = null; generation++ }
            wake.trySend(Unit)
        }

        fun offerPartial(samples: FloatArray) {
            synchronized(lock) { if (finals.isEmpty()) partial = samples }
            wake.trySend(Unit)
        }

        fun close() {
            synchronized(lock) { closed = true }
            wake.trySend(Unit)
        }

        suspend fun run() {
            while (true) {
                var samples: FloatArray? = null
                var isFinal = false
                var gen = 0
                var done = false
                synchronized(lock) {
                    gen = generation
                    when {
                        finals.isNotEmpty() -> { samples = finals.removeFirst(); isFinal = true }
                        partial != null -> { samples = partial; partial = null }
                        closed -> done = true
                    }
                }
                if (done) return
                val audio = samples
                if (audio == null) { wake.receive(); continue }
                if (engineFailed) continue
                decode(audio, isFinal, gen)
            }
        }

        private suspend fun decode(audio: FloatArray, isFinal: Boolean, gen: Int) {
            val t0 = SystemClock.elapsedRealtime()
            val text = try {
                host.use(config.spec, config.options) { it.transcribe(audio) }
            } catch (t: Throwable) {
                engineFailed = true
                recording = false
                _state.update { it.copy(problem = Problem.Engine(t.message ?: t.javaClass.simpleName)) }
                return
            }
            val ms = SystemClock.elapsedRealtime() - t0
            if (isFinal) {
                _state.update { s ->
                    val segments = if (text.isBlank()) s.segments else s.segments + Segment(text, audio.size * 1000L / SAMPLE_RATE, ms)
                    s.copy(segments = segments, partial = "")
                }
            } else {
                val fresh = synchronized(lock) { gen == generation }
                if (fresh) _state.update { it.copy(partial = text) }
            }
        }
    }

    private class MicrophoneException : Exception()

    private companion object {
        const val CHUNK = 512                 // 32 ms, the Silero VAD window
        const val PRE_ROLL_CHUNKS = 10        // about 0.3 s
        const val PARTIAL_INTERVAL_MS = 500L
        const val MAX_UTTERANCE_S = 20f

        fun concat(parts: List<FloatArray>): FloatArray {
            val out = FloatArray(parts.sumOf { it.size })
            var i = 0
            for (p in parts) { p.copyInto(out, i); i += p.size }
            return out
        }

        /** RMS loudness mapped from about -55 dBFS (silence) to -10 dBFS (loud) onto [0, 1]. */
        fun loudness(samples: FloatArray): Float {
            var sum = 0.0
            for (s in samples) sum += s * s
            val rms = sqrt(sum / samples.size)
            val db = 20 * log10(rms + 1e-9)
            return ((db + 55) / 45).toFloat().coerceIn(0f, 1f)
        }
    }
}
