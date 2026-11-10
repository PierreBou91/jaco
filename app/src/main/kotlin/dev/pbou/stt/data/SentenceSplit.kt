package dev.pbou.stt.data

import dev.pbou.stt.AppGraph
import dev.pbou.stt.engine.AudioDecoder
import dev.pbou.stt.engine.FileTranscriber.Segment
import dev.pbou.stt.engine.ModelCatalog
import dev.pbou.stt.engine.ModelSpec
import java.io.File

/**
 * Splits the sentences of an item that several people speak in, once its speakers are known.
 *
 * Sentences are cut at pauses while recording; in a quick exchange there is often none, so one "sentence"
 * can hold two or three people (a quarter to a third of the speech in Pierre's calls). Each such sentence
 * is cut where the voice changes ([SpeakerText.cuts]) and every piece is transcribed again, with the
 * model and language of its version. Versions whose model is not installed stay as they are.
 */
object SentenceSplit {

    /** Splits every version of [itemId], the one shown first. [onProgress] gets a fraction in [0, 1]. */
    suspend fun run(graph: AppGraph, itemId: String, audio: File, onProgress: (Float) -> Unit) {
        val item = graph.library.get(itemId) ?: return
        val turns = item.speakers?.turns ?: return
        val settings = graph.awaitSettings()
        val versions = item.transcripts.indices.sortedBy { if (it == item.shown) 0 else 1 }
        // What there is to do, to report progress over all versions.
        val plans = versions.associateWith { v ->
            val t = item.transcripts[v]
            ModelCatalog.byId(t.modelId)?.takeIf { it.id in graph.store.installed.value }?.let { spec ->
                spec to t.segments.map { SpeakerText.cuts(it, turns) }
            }
        }.filterValues { it != null && it.second.any { c -> c != null } }
        val totalMs = plans.values.sumOf { p -> p!!.second.filterNotNull().sumOf { c -> c.sumOf { it.second - it.first } } }.coerceAtLeast(1)
        var doneMs = 0L
        for ((v, plan) in plans) {
            val (spec, cuts) = plan!!
            val before = item.transcripts[v]
            val pieces = cuts.flatMapIndexed { i, c -> c.orEmpty().map { i to it } }
            // The model is told the language it was told the first time (none: it detects it again).
            val options = graph.options(spec, settings, before.language ?: ModelSpec.AUTO)
            val texts = arrayOfNulls<String>(pieces.size)
            AudioDecoder.slices(audio, pieces.map { it.second }) { k, samples ->
                texts[k] = if (samples.isEmpty()) "" else graph.host.use(spec, options) { it.transcribe(samples) }
                doneMs += pieces[k].second.let { it.second - it.first }
                onProgress((doneMs.toFloat() / totalMs).coerceIn(0f, 1f))
            }
            val byIndex = pieces.indices.groupBy { pieces[it].first }
            val segments = before.segments.flatMapIndexed { i, seg ->
                val mine = byIndex[i] ?: return@flatMapIndexed listOf(seg)
                val split = mine.mapNotNull { k ->
                    texts[k]?.trim()?.takeIf { it.isNotEmpty() }?.let { Segment(pieces[k].second.first, pieces[k].second.second, it) }
                }
                // Nothing understood in the pieces (noise): keep the sentence whole.
                split.ifEmpty { listOf(seg) }
            }
            graph.library.update(itemId) { now ->
                // Changed meanwhile (continued, transcribed again): leave it, the next search splits it.
                if (now.transcripts.getOrNull(v) != before) now
                else now.copy(transcripts = now.transcripts.mapIndexed { j, t -> if (j == v) t.copy(segments = segments) else t })
            }
        }
    }
}
