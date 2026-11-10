package dev.pbou.stt

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pbou.stt.engine.Diarizer
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * How long diarization takes on this phone with each voice model, on recordings copied to
 * files/bench/speakers/ (models as `<name>.onnx`, the segmentation model as `segmentation.onnx`, audio as
 * `*.wav`). Not a test of correctness (that is measured on a PC against labels, docs/speakers.md): run by hand.
 *
 *     adb shell am instrument -w -r -e class dev.pbou.stt.SpeakersBenchmark \
 *         -e models nemo_en_titanet_small:1.0:0.45,nemo_en_titanet_large:1.0:0.45 [-e threads 4] \
 *         dev.pbou.stt.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Each model is `file name:chunk threshold:link distance`. Results go to files/bench/speakers.csv and the
 * speaker turns to files/bench/speakers/<model>-<audio>.rttm (to compare with the PC's).
 */
@RunWith(AndroidJUnit4::class)
class SpeakersBenchmark {

    @Test
    fun speakers() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val graph = (instrumentation.targetContext.applicationContext as SttApplication).graph
        val dir = File(instrumentation.targetContext.filesDir, "bench/speakers")
        val threads = args.getString("threads")?.toInt() ?: 4
        val models = args.getString("models")!!.split(',').map { it.split(':') }
        val audios = dir.listFiles { f -> f.extension == "wav" }!!.sortedBy { it.name }

        val out = File(instrumentation.targetContext.filesDir, "bench/speakers.csv")
        if (!out.exists()) out.writeText("model,audio,threads,audio_s,elapsed_s,minutes_per_hour,speakers\n")
        for ((name, chunk, link) in models) {
            val setup = Diarizer.Setup(File(dir, "segmentation.onnx"), File(dir, "$name.onnx"), chunk.toFloat(), link.toFloat())
            for (audio in audios) {
                var audioMs = 0L
                val t0 = SystemClock.elapsedRealtime()
                val r = graph.diarizer.run(audio, count = null, threads = threads, setup = setup) { audioMs = it }
                val elapsed = (SystemClock.elapsedRealtime() - t0) / 1000.0
                File(dir, "$name-${audio.nameWithoutExtension}.rttm").writeText(
                    r.turns.joinToString("") { t ->
                        "SPEAKER ${audio.nameWithoutExtension} 1 %.3f %.3f <NA> <NA> S${t.speaker} <NA> <NA>\n".format(t.startMs / 1000.0, (t.endMs - t.startMs) / 1000.0)
                    },
                )
                val line = listOf(
                    name, audio.nameWithoutExtension, threads, audioMs / 1000, "%.1f".format(elapsed),
                    "%.1f".format(elapsed / (audioMs / 1000.0) * 60), r.speakers.size,
                ).joinToString(",")
                out.appendText(line + "\n")
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", line + "\n") })
            }
        }
    }
}
