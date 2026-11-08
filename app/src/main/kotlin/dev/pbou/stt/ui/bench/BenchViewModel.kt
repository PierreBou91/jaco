package dev.pbou.stt.ui.bench

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pbou.stt.AppGraph
import dev.pbou.stt.data.AppSettings
import dev.pbou.stt.data.BenchTexts
import dev.pbou.stt.engine.Benchmark
import dev.pbou.stt.engine.EngineOptions
import dev.pbou.stt.engine.MicrophoneException
import dev.pbou.stt.engine.ModelCatalog
import dev.pbou.stt.engine.ModelSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BenchRun(
    val model: ModelSpec,
    val modelIndex: Int,
    val modelCount: Int,
    /** Sentences done, or -1 while the model loads. */
    val done: Int,
    val total: Int,
)

sealed interface BenchProblem {
    data object Microphone : BenchProblem
    data object NothingHeard : BenchProblem
    data class ModelFailed(val model: ModelSpec, val message: String) : BenchProblem
}

data class BenchUi(
    val language: String = "en",
    val sentences: List<String> = emptyList(),
    /** Sentence index -> duration of its recording, in ms. */
    val recorded: Map<Int, Long> = emptyMap(),
    val recording: Int? = null,
    val installed: List<ModelSpec> = emptyList(),
    val selected: Set<String> = emptySet(),
    val run: BenchRun? = null,
    /** Results for these sentences, best accuracy first. */
    val results: List<Benchmark.Result> = emptyList(),
    val problem: BenchProblem? = null,
) {
    val canRun get() = recorded.isNotEmpty() && installed.any { it.id in selected } && run == null && recording == null
}

class BenchViewModel(private val graph: AppGraph) : ViewModel() {

    private val language = MutableStateFlow(graph.deviceLanguages().firstOrNull { it in BenchTexts.LANGUAGES } ?: "en")
    private val recording = MutableStateFlow<Int?>(null)
    private val run = MutableStateFlow<BenchRun?>(null)
    private val problem = MutableStateFlow<BenchProblem?>(null)
    /** Models the user unticked (everything installed is ticked by default). */
    private val unticked = MutableStateFlow<Set<String>>(emptySet())

    private var recordJob: Job? = null
    private var runJob: Job? = null
    private var stoppedByUser = false

    val level: StateFlow<Float> = graph.clipRecorder.level

    val ui: StateFlow<BenchUi> = combine(
        combine(language, recording, run, problem, unticked) { l, rec, r, p, u -> Local(l, rec, r, p, u) },
        graph.bench.recorded, graph.bench.results, graph.store.installed,
    ) { local, recorded, results, installed ->
        val lang = local.language
        val installedSpecs = ModelCatalog.all.filter { it.id in installed }
        BenchUi(
            language = lang,
            sentences = BenchTexts.sentences(lang),
            recorded = recorded.mapNotNull { (k, ms) ->
                val (l, i) = k.split('/')
                if (l == lang) i.toInt() to ms else null
            }.toMap(),
            recording = local.recording,
            installed = installedSpecs,
            selected = installedSpecs.map { it.id }.toSet() - local.unticked,
            run = local.run,
            results = results.filter { it.textLanguage == lang }.sortedBy { it.score.percent },
            problem = local.problem,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BenchUi())

    private data class Local(
        val language: String, val recording: Int?, val run: BenchRun?, val problem: BenchProblem?, val unticked: Set<String>,
    )

    fun selectLanguage(code: String) {
        if (recording.value != null || run.value != null) return
        language.value = code
    }

    fun toggleModel(id: String) = unticked.update { if (id in it) it - id else it + id }

    /** Records sentence [index], then the next ones not yet recorded, until one is left unheard or [stopRecording]. */
    fun record(index: Int) {
        if (run.value != null || recordJob?.isActive == true) return
        graph.live.stop()
        stoppedByUser = false
        recordJob = viewModelScope.launch {
            var i: Int? = index
            while (i != null) {
                recording.value = i
                val samples = try {
                    graph.clipRecorder.record()
                } catch (e: MicrophoneException) {
                    problem.value = BenchProblem.Microphone; null
                } catch (e: SecurityException) {
                    problem.value = BenchProblem.Microphone; null
                }
                if (samples == null) {
                    if (!stoppedByUser && problem.value == null) problem.value = BenchProblem.NothingHeard
                    break
                }
                graph.bench.saveClip(language.value, i, samples)
                if (stoppedByUser) break
                val recorded = ui.value.recorded.keys + i
                i = (i + 1 until ui.value.sentences.size).firstOrNull { it !in recorded }
                if (i != null) {
                    recording.value = i
                    delay(NEXT_SENTENCE_DELAY_MS) // a breath, and time to read the next sentence
                    if (stoppedByUser) break
                }
            }
            recording.value = null
        }
    }

    fun stopRecording() {
        stoppedByUser = true
        graph.clipRecorder.stop()
    }

    fun startRun() {
        if (!ui.value.canRun) return
        graph.live.stop()
        val lang = language.value
        val models = ui.value.installed.filter { it.id in ui.value.selected }
        runJob = viewModelScope.launch {
            try {
                val clips = graph.bench.clips(lang)
                val threads = graph.settings.value?.effectiveThreads ?: AppSettings.recommendedThreads
                for ((m, spec) in models.withIndex()) {
                    val options = EngineOptions(spec.effectiveLanguage(lang, graph.deviceLanguages()), threads)
                    try {
                        val result = graph.benchmark.run(spec, options, lang, clips) { done, total ->
                            run.value = BenchRun(spec, m, models.size, done, total)
                        }
                        graph.bench.saveResult(result)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        problem.value = BenchProblem.ModelFailed(spec, t.message ?: t.javaClass.simpleName)
                    }
                }
            } finally {
                run.value = null
                // Free the last model tested; dictation loads its own model when it opens.
                graph.host.release()
            }
        }
    }

    fun cancelRun() {
        runJob?.cancel()
    }

    fun dismissProblem() {
        problem.value = null
    }

    /** Leaving the screen or the app: never keep the microphone open. */
    fun onHidden() = stopRecording()

    private companion object {
        const val NEXT_SENTENCE_DELAY_MS = 700L
    }
}
