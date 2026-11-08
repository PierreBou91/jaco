package dev.pbou.stt

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pbou.stt.engine.EngineOptions
import dev.pbou.stt.engine.ModelCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * How decode speed changes with the number of threads, on the sentences recorded in the benchmark
 * screen. Not a test of correctness: run it by hand on a phone, with the app installed.
 *
 *     adb shell am instrument -w -r -e class dev.pbou.stt.ThreadsBenchmark \
 *         [-e threads 1,2,4,8] [-e passes 2] [-e models parakeet-tdt-0.6b-v3] \
 *         dev.pbou.stt.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Results go to files/bench/threads.csv. Each pass goes through the thread counts in the other
 * direction, so a phone heating up does not favour one end.
 */
@RunWith(AndroidJUnit4::class)
class ThreadsBenchmark {

    @Test
    fun threads() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val graph = (instrumentation.targetContext.applicationContext as SttApplication).graph
        val counts = args.getString("threads")?.split(',')?.map(String::toInt)
            ?: (1..Runtime.getRuntime().availableProcessors()).toList()
        val passes = args.getString("passes")?.toInt() ?: 2
        val wanted = args.getString("models")?.split(',')
        val models = ModelCatalog.all.filter { it.id in graph.store.installed.value && (wanted == null || it.id in wanted) }
        val clips = listOf("fr", "en").associateWith { graph.bench.clips(it) }.filterValues { it.isNotEmpty() }

        val out = File(instrumentation.targetContext.filesDir, "bench/threads.csv")
        out.parentFile?.mkdirs()
        out.writeText("model,language,threads,pass,load_ms,audio_ms,decode_ms,median_ms,rtf,wer\n")
        for (pass in 0 until passes) {
            for (spec in models) {
                for (n in if (pass % 2 == 0) counts else counts.reversed()) {
                    for ((lang, list) in clips) {
                        val options = EngineOptions(spec.effectiveLanguage(lang, listOf(lang)), n)
                        val r = graph.benchmark.run(spec, options, lang, list) { _, _ -> }
                        val line = listOf(
                            spec.id, lang, n, pass, r.loadMs, r.audioMs, r.sentences.sumOf { it.decodeMs },
                            r.waitMs, "%.3f".format(r.rtf), "%.1f".format(r.score.percent),
                        ).joinToString(",")
                        out.appendText(line + "\n")
                        instrumentation.sendStatus(0, Bundle().apply { putString("stream", line + "\n") })
                    }
                }
            }
        }
        graph.host.release().join()
        Unit
    }
}
