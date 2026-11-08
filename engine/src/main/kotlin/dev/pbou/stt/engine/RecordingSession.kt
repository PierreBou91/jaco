package dev.pbou.stt.engine

import android.content.res.AssetManager
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * A long recording (a meeting, a lecture): the microphone is cut into sentences at pauses, as in live
 * dictation, and each sentence is transcribed as soon as it ends, with its time in the recording. There
 * is no preview of the sentence being spoken: over hours, that would double the work for little use.
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
        val pauseSeconds: Float = PAUSE_S,
    )

    enum class Phase { IDLE, RECORDING, PAUSED, FINISHING }

    data class State(
        val phase: Phase = Phase.IDLE,
        /** Audio recorded so far (pauses excluded). */
        val recordedMs: Long = 0,
        /** Sentences recorded but not transcribed yet. */
        val waiting: Int = 0,
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

    @Volatile private var recording = false
    @Volatile private var paused = false
    private var session: Job? = null

    val isActive: Boolean get() = session?.isActive == true

    fun start(scope: CoroutineScope, config: Config, listener: Listener) {
        if (isActive) return
        recording = true
        paused = false
        _state.value = State(Phase.RECORDING)
        session = scope.launch(Dispatchers.Default) {
            val sentences = Channel<Pair<Long, FloatArray>>(Channel.UNLIMITED)
            val waiting = AtomicInteger(0)
            var recordedSamples = 0L
            val transcribing = launch {
                var failed = false
                for ((start, samples) in sentences) {
                    if (!failed) {
                        try {
                            val text = host.use(config.spec, config.options) { it.transcribe(samples) }
                            if (text.isNotBlank()) {
                                listener.onSegment(
                                    FileTranscriber.Segment(start * 1000 / SAMPLE_RATE, (start + samples.size) * 1000 / SAMPLE_RATE, text),
                                )
                            }
                        } catch (t: Throwable) {
                            failed = true
                            recording = false
                            listener.onProblem(Problem.Engine(t.message ?: t.javaClass.simpleName))
                        }
                    }
                    _state.update { it.copy(waiting = waiting.decrementAndGet()) }
                }
            }
            try {
                recordedSamples = capture(config, sentences, waiting)
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
                _state.update { it.copy(phase = Phase.FINISHING) }
                sentences.close()
            }
            transcribing.join()
            _state.value = State()
            listener.onFinished(recordedSamples * 1000 / SAMPLE_RATE)
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

    /** Returns the number of samples recorded. */
    private suspend fun capture(config: Config, sentences: Channel<Pair<Long, FloatArray>>, waiting: AtomicInteger): Long = withContext(Dispatchers.IO) {
        val writer = config.audio?.let { file ->
            try {
                AacWriter(file)
            } catch (t: Throwable) {
                throw AudioException("cannot encode: ${t.message}")
            }
        }
        val vad = Mic.vad(assets, config.pauseSeconds, MAX_SENTENCE_S)
        var samplesIn = 0L
        val rec = try {
            Mic.open()
        } catch (t: Throwable) {
            vad.release(); writer?.close(); throw t
        }
        fun drain() {
            while (!vad.empty()) {
                val seg = vad.front()
                waiting.incrementAndGet()
                sentences.trySend(seg.start to seg.samples)
                vad.pop()
            }
            _state.update { it.copy(waiting = waiting.get()) }
        }
        try {
            val chunk = ShortArray(Mic.CHUNK)
            var level = 0f
            var lastTick = 0L
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
                drain()
                if (samplesIn - lastTick >= SAMPLE_RATE / 4) {
                    lastTick = samplesIn
                    _state.update { it.copy(recordedMs = samplesIn * 1000 / SAMPLE_RATE) }
                }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
            vad.flush()
            drain()
            vad.release()
            _state.update { it.copy(recordedMs = samplesIn * 1000 / SAMPLE_RATE) }
            try {
                writer?.close()
            } catch (t: Throwable) {
                throw AudioException("cannot finish the audio: ${t.message}")
            }
        }
        samplesIn
    }

    private class AudioException(message: String) : Exception(message)

    private companion object {
        const val PAUSE_S = 0.8f // as for files: whole sentences read better and give the model context
        const val MAX_SENTENCE_S = 20f
    }
}
