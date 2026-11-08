package dev.pbou.stt.engine

import android.content.res.AssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Records one spoken sentence: listens until the speaker pauses (or [stop] is called), then returns the
 * speech with a little margin, the silence around it trimmed. Used to build the benchmark's recordings.
 */
class ClipRecorder(private val assets: AssetManager) {

    private val _level = MutableStateFlow(0f)
    /** Microphone loudness in [0, 1] while recording. */
    val level: StateFlow<Float> = _level.asStateFlow()

    @Volatile private var stopRequested = false

    fun stop() {
        stopRequested = true
    }

    /**
     * Returns the trimmed speech, or null if no speech was heard before [stop] or [maxSeconds].
     * Throws [MicrophoneException] (or SecurityException) if the microphone cannot be used.
     */
    suspend fun record(pauseSeconds: Float = 1.2f, maxSeconds: Float = 30f): FloatArray? = withContext(Dispatchers.IO) {
        stopRequested = false
        val vad = Mic.vad(assets, pauseSeconds, maxSeconds)
        val rec = try {
            Mic.open()
        } catch (t: Throwable) {
            vad.release(); throw t
        }
        val audio = ArrayList<FloatArray>()
        var total = 0
        var first = -1L // first and last speech sample, as the VAD reports them
        var last = -1L
        try {
            val chunk = ShortArray(Mic.CHUNK)
            var level = 0f
            while (!stopRequested && isActive && total < maxSeconds * SAMPLE_RATE) {
                val samples = Mic.read(rec, chunk) ?: continue
                audio += samples
                total += samples.size
                level = max(Mic.loudness(samples), level * 0.85f)
                _level.value = level
                vad.acceptWaveform(samples)
                if (!vad.empty()) {
                    val seg = vad.front()
                    first = seg.start
                    last = seg.start + seg.samples.size
                    vad.pop()
                    break // the speaker paused: the sentence is done
                }
            }
            if (first < 0) {
                vad.flush()
                if (!vad.empty()) {
                    val seg = vad.front()
                    first = seg.start
                    last = seg.start + seg.samples.size
                }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
            vad.release()
            _level.value = 0f
        }
        if (first < 0) return@withContext null
        val all = FloatArray(total).also { out ->
            var i = 0
            for (a in audio) { a.copyInto(out, i); i += a.size }
        }
        val from = (first - LEAD).coerceIn(0, total.toLong()).toInt()
        val to = (last + TAIL).coerceIn(from.toLong(), total.toLong()).toInt()
        all.copyOfRange(from, to)
    }

    private companion object {
        const val LEAD = SAMPLE_RATE * 3 / 10 // 0.3 s before the speech, like live dictation's pre-roll
        const val TAIL = SAMPLE_RATE / 5      // 0.2 s after it
    }
}
