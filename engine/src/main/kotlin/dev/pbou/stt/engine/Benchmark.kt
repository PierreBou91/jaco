package dev.pbou.stt.engine

import android.os.SystemClock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Runs recorded sentences through a model the way live dictation does when you pause: one decode of
 * the whole sentence. The time that takes is the wait before the text appears.
 */
class Benchmark(private val host: EngineHost) {

    class Clip(val reference: String, val samples: FloatArray)

    data class Sentence(val reference: String, val text: String, val audioMs: Long, val decodeMs: Long) {
        val score: Wer.Score get() = Wer.score(reference, text)
    }

    data class Result(
        val modelId: String,
        /** The language of the sentences that were read. */
        val textLanguage: String,
        /** The language the model was told to use, null if it detected it. */
        val modelLanguage: String?,
        val threads: Int,
        val loadMs: Long,
        val sentences: List<Sentence>,
        val finishedAt: Long,
    ) {
        val score: Wer.Score get() = sentences.fold(Wer.Score(0, 0)) { acc, s -> acc + s.score }
        val audioMs: Long get() = sentences.sumOf { it.audioMs }

        /** Decode time over audio time, the lower the faster. */
        val rtf: Double get() = if (audioMs == 0L) 0.0 else sentences.sumOf { it.decodeMs }.toDouble() / audioMs

        /** The typical wait after a sentence: the median decode time. */
        val waitMs: Long get() = sentences.map { it.decodeMs }.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] }
    }

    fun interface Progress {
        /** [done] sentences of [total] transcribed; -1 while the model loads. */
        fun onProgress(done: Int, total: Int)
    }

    suspend fun run(spec: ModelSpec, options: EngineOptions, textLanguage: String, clips: List<Clip>, progress: Progress): Result {
        // Load from cold, so the load time is the real one.
        host.release().join()
        progress.onProgress(-1, clips.size)
        host.use(spec, options) { }
        val loadMs = (host.state.value as? EngineHost.State.Ready)?.loadMs ?: 0L
        val sentences = ArrayList<Sentence>()
        for ((i, clip) in clips.withIndex()) {
            progress.onProgress(i, clips.size)
            currentCoroutineContext().ensureActive()
            val (text, ms) = host.use(spec, options) { engine ->
                val t0 = SystemClock.elapsedRealtime()
                engine.transcribe(clip.samples) to SystemClock.elapsedRealtime() - t0
            }
            sentences += Sentence(clip.reference, text, clip.samples.size * 1000L / SAMPLE_RATE, ms)
        }
        progress.onProgress(clips.size, clips.size)
        return Result(spec.id, textLanguage, options.language, options.threads, loadMs, sentences, System.currentTimeMillis())
    }
}
