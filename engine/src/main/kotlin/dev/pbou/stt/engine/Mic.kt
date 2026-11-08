package dev.pbou.stt.engine

import android.annotation.SuppressLint
import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Microphone and voice activity detection shared by live dictation and the benchmark recorder. */
internal object Mic {
    const val CHUNK = 512 // 32 ms, the Silero VAD window

    /** Opens and starts the microphone, tuned for speech recognition. The caller stops and releases it. */
    @SuppressLint("MissingPermission") // callers ask for RECORD_AUDIO; a SecurityException is handled by them
    fun open(): AudioRecord {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, max(minBuffer, SAMPLE_RATE * 2),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release(); throw MicrophoneException()
        }
        rec.startRecording()
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release(); throw MicrophoneException()
        }
        return rec
    }

    /** Silero VAD: a speech segment ends after [silenceSeconds] of silence. */
    fun vad(assets: AssetManager, silenceSeconds: Float, maxSpeechSeconds: Float) = Vad(
        assets,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "vad/silero_vad.onnx", threshold = 0.5f, minSilenceDuration = silenceSeconds,
                minSpeechDuration = 0.25f, windowSize = CHUNK, maxSpeechDuration = maxSpeechSeconds,
            ),
            sampleRate = SAMPLE_RATE, numThreads = 1,
        ),
    )

    /** Reads one chunk as floats; null if nothing was read. */
    fun read(rec: AudioRecord, buf: ShortArray): FloatArray? {
        val n = rec.read(buf, 0, buf.size)
        if (n < 0) throw MicrophoneException()
        return if (n == 0) null else FloatArray(n) { buf[it] / 32768f }
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

/** The microphone could not be opened or stopped delivering audio (permission, or another app holds it). */
class MicrophoneException : Exception()
