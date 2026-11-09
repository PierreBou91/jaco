package dev.pbou.stt.engine

import android.content.res.AssetManager
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
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
import java.io.File
import kotlin.math.max

/**
 * A dictation or recording session, a few seconds or hours long: the microphone is cut into sentences at
 * pauses (Silero VAD), each sentence is transcribed as soon as it ends, with its time in the recording.
 *
 * While [preview] is on (someone is looking), the sentence being spoken is also transcribed about twice
 * a second ([State.partial]); off (screen off, another app), only whole sentences are: over hours, the
 * preview would double the work for nobody. Sentences longer than 25 s are cut at their quietest moment
 * ([SentenceSplitter]).
 *
 * The audio can be written at the same time ([Config.audio], AAC frames, see [AacWriter]). Transcription
 * runs behind the microphone and catches up at pauses; if it falls behind (a slow model), sentences wait
 * in memory and [stop] finishes them.
 */
class RecordingSession(private val assets: AssetManager, private val host: EngineHost) {

    data class Config(
        val spec: ModelSpec,
        val options: EngineOptions,
        /** Where to write the audio (ADTS AAC), null to keep only the text. */
        val audio: File?,
        /** Add to the end of [audio] instead of replacing it (continuing a recording). */
        val appendAudio: Boolean = false,
        /** Where this session starts in the recording (continuing one): added to every time. */
        val offsetMs: Long = 0,
        val pauseSeconds: Float = PAUSE_S,
    )

    enum class Phase { IDLE, RECORDING, PAUSED, FINISHING }

    data class State(
        val phase: Phase = Phase.IDLE,
        /** Length of the recording so far (pauses excluded, earlier sessions included). */
        val recordedMs: Long = 0,
        /** Sentences recorded but not transcribed yet. */
        val waiting: Int = 0,
        /** The sentence being spoken, while [preview] is on. */
        val partial: String = "",
    )

    sealed interface Problem {
        data object Microphone : Problem
        data class Engine(val message: String) : Problem
        data class Audio(val message: String) : Problem
    }

    interface Listener {
        /** A sentence, in order. Called from a background coroutine. */
        suspend fun onSegment(segment: FileTranscriber.Segment)
        /** Something failed; the session ends (what was transcribed is kept). */
        suspend fun onProblem(problem: Problem)
        /** The session is over: every sentence is transcribed and the audio file is complete. */
        suspend fun onFinished(recordedMs: Long)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _level = MutableStateFlow(0f)
    /** Microphone loudness in [0, 1] while recording. */
    val level: StateFlow<Float> = _level.asStateFlow()

    /** Show the sentence being spoken (costs a decode about twice a second): on while someone looks. */
    @Volatile var preview: Boolean = true
        set(value) {
            field = value
            if (!value) _state.update { it.copy(partial = "") }
        }

    @Volatile private var recording = false
    @Volatile private var paused = false
    private var session: Job? = null

    val isActive: Boolean get() = session?.isActive == true

    fun start(scope: CoroutineScope, config: Config, listener: Listener) {
        if (isActive) return
        recording = true
        paused = false
        _state.value = State(Phase.RECORDING, recordedMs = config.offsetMs)
        session = scope.launch(Dispatchers.Default) {
            val queue = DecodeQueue(config, listener)
            val decoding = launch { queue.run() }
            var recordedSamples = 0L
            try {
                recordedSamples = capture(config, queue)
            } catch (e: MicrophoneException) {
                listener.onProblem(Problem.Microphone)
            } catch (e: SecurityException) {
                listener.onProblem(Problem.Microphone)
            } catch (e: AudioException) {
                listener.onProblem(Problem.Audio(e.message ?: ""))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                listener.onProblem(Problem.Engine(t.message ?: t.javaClass.simpleName))
            } finally {
                recording = false
                _level.value = 0f
                _state.update { it.copy(phase = Phase.FINISHING, partial = "") }
                queue.close()
            }
            decoding.join()
            _state.value = State()
            listener.onFinished(config.offsetMs + recordedSamples * 1000 / SAMPLE_RATE)
        }
    }

    fun pause() {
        if (!recording) return
        paused = true
        _state.update { it.copy(phase = Phase.PAUSED) }
    }

    fun resume() {
        if (!recording) return
        paused = false
        _state.update { it.copy(phase = Phase.RECORDING) }
    }

    /** Stops the microphone; the sentences already recorded are still transcribed (phase FINISHING). */
    fun stop() {
        recording = false
        paused = false
    }

    /** Returns the number of samples recorded in this session. */
    private suspend fun capture(config: Config, queue: DecodeQueue): Long = withContext(Dispatchers.IO) {
        val writer = config.audio?.let { file ->
            try {
                AacWriter(file, append = config.appendAudio)
            } catch (t: Throwable) {
                throw AudioException("cannot encode: ${t.message}")
            }
        }
        val vad = Mic.vad(assets, config.pauseSeconds, MAX_SENTENCE_S)
        val offset = config.offsetMs * SAMPLE_RATE / 1000
        var samplesIn = 0L
        val rec = try {
            Mic.open()
        } catch (t: Throwable) {
            vad.release(); writer?.close(); throw t
        }
        fun drain() {
            while (!vad.empty()) {
                val seg = vad.front()
                for ((at, piece) in SentenceSplitter.split(seg.samples)) queue.offerFinal(offset + seg.start + at, piece)
                vad.pop()
            }
        }
        try {
            val chunk = ShortArray(Mic.CHUNK)
            val preRoll = ArrayDeque<FloatArray>() // audio just before speech starts, so first syllables are kept
            val current = ArrayList<FloatArray>()  // the sentence being spoken, for the preview
            var level = 0f
            var lastTick = 0L
            var lastPartial = 0L
            while (recording && isActive) {
                val samples = Mic.read(rec, chunk) ?: continue
                if (paused) {
                    _level.value = 0f
                    continue
                }
                level = max(Mic.loudness(samples), level * 0.85f)
                _level.value = level
                try {
                    writer?.write(samples)
                } catch (t: Throwable) {
                    throw AudioException("cannot encode: ${t.message}")
                }
                vad.acceptWaveform(samples)
                samplesIn += samples.size
                if (vad.isSpeechDetected()) {
                    if (current.isEmpty()) current.addAll(preRoll)
                    current.add(samples)
                    // The last 20 s at most: a sentence that runs on is cut there anyway.
                    while (current.size > PREVIEW_CHUNKS) current.removeAt(0)
                    val now = SystemClock.elapsedRealtime()
                    if (preview && now - lastPartial >= PARTIAL_INTERVAL_MS) {
                        lastPartial = now
                        queue.offerPartial(concat(current))
                    }
                } else {
                    preRoll.addLast(samples)
                    while (preRoll.size > PRE_ROLL_CHUNKS) preRoll.removeFirst()
                }
                if (!vad.empty()) {
                    drain()
                    current.clear()
                }
                if (samplesIn - lastTick >= SAMPLE_RATE / 4) {
                    lastTick = samplesIn
                    _state.update { it.copy(recordedMs = config.offsetMs + samplesIn * 1000 / SAMPLE_RATE) }
                }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
            vad.flush()
            drain()
            vad.release()
            _state.update { it.copy(recordedMs = config.offsetMs + samplesIn * 1000 / SAMPLE_RATE) }
            try {
                writer?.close()
            } catch (t: Throwable) {
                throw AudioException("cannot finish the audio: ${t.message}")
            }
        }
        samplesIn
    }

    /** Finished sentences first, in order; at most one pending preview, always the latest. */
    private inner class DecodeQueue(private val config: Config, private val listener: Listener) {
        private val lock = Any()
        private val finals = ArrayDeque<Pair<Long, FloatArray>>()
        private var partial: FloatArray? = null
        private var closed = false
        private var generation = 0 // bumped by each finished sentence; older previews are stale
        private val wake = Channel<Unit>(Channel.CONFLATED)
        private var failed = false

        fun offerFinal(start: Long, samples: FloatArray) {
            val n = synchronized(lock) {
                finals.addLast(start to samples); partial = null; generation++
                finals.size
            }
            _state.update { it.copy(waiting = n) }
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
                var final: Pair<Long, FloatArray>? = null
                var preview: FloatArray? = null
                var gen = 0
                var done = false
                synchronized(lock) {
                    gen = generation
                    when {
                        finals.isNotEmpty() -> final = finals.first()
                        partial != null -> { preview = partial; partial = null }
                        closed -> done = true
                    }
                }
                if (done) return
                val f = final
                val p = preview
                when {
                    f != null -> {
                        decodeFinal(f.first, f.second)
                        val n = synchronized(lock) { finals.removeFirst(); finals.size }
                        _state.update { it.copy(waiting = n, partial = if (n == 0) it.partial else "") }
                    }
                    p != null -> if (!failed && this@RecordingSession.preview) {
                        val text = decode(p) ?: continue
                        val fresh = synchronized(lock) { gen == generation }
                        if (fresh) _state.update { it.copy(partial = text) }
                    }
                    else -> wake.receive()
                }
            }
        }

        private suspend fun decodeFinal(start: Long, samples: FloatArray) {
            if (failed) return
            val text = decode(samples) ?: return
            _state.update { it.copy(partial = "") }
            if (text.isNotBlank()) {
                listener.onSegment(FileTranscriber.Segment(start * 1000 / SAMPLE_RATE, (start + samples.size) * 1000 / SAMPLE_RATE, text))
            }
        }

        /** Null if the engine failed (reported once; the session then ends). */
        private suspend fun decode(samples: FloatArray): String? = try {
            host.use(config.spec, config.options) { it.transcribe(samples) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            if (!failed) {
                failed = true
                recording = false
                listener.onProblem(Problem.Engine(t.message ?: t.javaClass.simpleName))
            }
            null
        }
    }

    private class AudioException(message: String) : Exception(message)

    private companion object {
        const val PAUSE_S = 0.8f // whole sentences read better and give the model context
        const val MAX_SENTENCE_S = 20f
        const val PRE_ROLL_CHUNKS = 10 // about 0.3 s
        const val PARTIAL_INTERVAL_MS = 500L
        const val PREVIEW_CHUNKS = 20 * SAMPLE_RATE / Mic.CHUNK

        fun concat(parts: List<FloatArray>): FloatArray {
            val out = FloatArray(parts.sumOf { it.size })
            var i = 0
            for (p in parts) { p.copyInto(out, i); i += p.size }
            return out
        }
    }
}
