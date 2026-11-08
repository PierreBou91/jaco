package dev.pbou.stt.ui.dictate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pbou.stt.AppGraph
import dev.pbou.stt.data.AppSettings
import dev.pbou.stt.data.DownloadState
import dev.pbou.stt.engine.EngineHost
import dev.pbou.stt.engine.EngineOptions
import dev.pbou.stt.engine.LiveTranscriber
import dev.pbou.stt.engine.ModelCatalog
import dev.pbou.stt.engine.ModelSpec
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DictateUi(
    val loaded: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val model: ModelSpec? = null,
    val options: EngineOptions? = null,
    val installed: List<ModelSpec> = emptyList(),
    val engine: EngineHost.State = EngineHost.State.Empty,
    val live: LiveTranscriber.State = LiveTranscriber.State(),
    /** Download state of the recommended model, for the first-run card. */
    val recommendedDownload: DownloadState? = null,
    val onWifi: Boolean = true,
) {
    val listening get() = live.phase == LiveTranscriber.Phase.LISTENING || live.phase == LiveTranscriber.Phase.STARTING
    val busy get() = live.phase != LiveTranscriber.Phase.IDLE
    val hasText get() = live.segments.isNotEmpty()
    val engineLoading get() = engine is EngineHost.State.Loading
}

class DictateViewModel(private val graph: AppGraph) : ViewModel() {

    private val inputs = combine(graph.settings, graph.activeModel, graph.store.installed, graph.downloads.states, graph.downloads.onWifi) { s, m, i, d, w ->
        Inputs(s, m, i, d, w)
    }

    val ui: StateFlow<DictateUi> = combine(inputs, graph.host.state, graph.live.state) { input, engine, live ->
        val s = input.settings
        DictateUi(
            loaded = s != null,
            settings = s ?: AppSettings(),
            model = input.model,
            options = if (input.model != null && s != null) graph.options(input.model, s) else null,
            installed = ModelCatalog.all.filter { it.id in input.installed },
            engine = engine,
            live = live,
            recommendedDownload = input.downloads[ModelCatalog.PARAKEET.id],
            onWifi = input.onWifi,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DictateUi())

    val level: StateFlow<Float> = graph.live.level

    private data class Inputs(
        val settings: AppSettings?, val model: ModelSpec?, val installed: Set<String>,
        val downloads: Map<String, DownloadState>, val onWifi: Boolean,
    )

    /** Loads the model ahead of time so the first dictation starts instantly. */
    fun warmUp() {
        val u = ui.value
        val model = u.model ?: return
        val options = u.options ?: return
        if (u.settings.keepLoaded) graph.host.preload(model, options)
    }

    fun start() {
        val u = ui.value
        val model = u.model ?: return
        val options = u.options ?: return
        graph.live.start(
            viewModelScope,
            LiveTranscriber.Config(
                spec = model, options = options, pauseSeconds = u.settings.pauseSeconds,
                partials = u.settings.livePreview, releaseAfter = !u.settings.keepLoaded,
            ),
        )
    }

    fun stop() = graph.live.stop()

    fun selectModel(spec: ModelSpec) {
        graph.live.stop()
        viewModelScope.launch { graph.settingsRepo.update { it.copy(defaultModelId = spec.id) } }
    }

    fun selectLanguage(code: String) {
        graph.live.stop()
        val model = ui.value.model
        viewModelScope.launch { graph.settingsRepo.update { it.withLanguage(code, model) } }
    }

    /** Clears the transcript and returns what was removed, for undo. */
    fun clear(): List<LiveTranscriber.Segment> {
        val removed = ui.value.live.segments
        graph.live.clear()
        return removed
    }

    fun undoClear(segments: List<LiveTranscriber.Segment>) = graph.live.restore(segments)

    fun dismissProblem() = graph.live.dismissProblem()

    fun downloadRecommended(useMobileData: Boolean = false) {
        val wifiOnly = (ui.value.settings.wifiOnly) && !useMobileData
        graph.downloads.start(ModelCatalog.PARAKEET, wifiOnly)
    }

    fun pauseRecommended() = graph.downloads.pause(ModelCatalog.PARAKEET)
}
