package dev.pbou.stt.ui.dictate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pbou.stt.AppGraph
import dev.pbou.stt.data.AppSettings
import dev.pbou.stt.data.DownloadState
import dev.pbou.stt.data.LibraryItem
import dev.pbou.stt.engine.EngineHost
import dev.pbou.stt.engine.EngineOptions
import dev.pbou.stt.engine.ModelCatalog
import dev.pbou.stt.engine.ModelSpec
import dev.pbou.stt.engine.RecordingSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DictateUi(
    val loaded: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val model: ModelSpec? = null,
    val options: EngineOptions? = null,
    val installed: List<ModelSpec> = emptyList(),
    val engine: EngineHost.State = EngineHost.State.Empty,
    /** The recording in progress, if any (started here, from a notification, or continued). */
    val item: LibraryItem? = null,
    val session: RecordingSession.State? = null,
    val problem: RecordingSession.Problem? = null,
    /** Download state of the recommended model, for the first-run card. */
    val recommendedDownload: DownloadState? = null,
    val onWifi: Boolean = true,
) {
    val phase get() = session?.phase ?: RecordingSession.Phase.IDLE
    val listening get() = phase == RecordingSession.Phase.RECORDING
    val paused get() = phase == RecordingSession.Phase.PAUSED
    val finishing get() = phase == RecordingSession.Phase.FINISHING
    val busy get() = phase != RecordingSession.Phase.IDLE
    val engineLoading get() = engine is EngineHost.State.Loading
}

/**
 * The home screen: choose the model, start a recording. Every recording goes to the
 * [dev.pbou.stt.data.Library] and is followed on its own screen (times, pause, stop); it goes on in the
 * background with a notification.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DictateViewModel(private val graph: AppGraph) : ViewModel() {

    private val inputs = combine(graph.settings, graph.activeModel, graph.store.installed, graph.downloads.states, graph.downloads.onWifi) { s, m, i, d, w ->
        Inputs(s, m, i, d, w)
    }

    private val shown = graph.recorder.itemId.flatMapLatest { id -> if (id == null) flowOf(null) else graph.library.item(id) }

    val ui: StateFlow<DictateUi> = combine(
        inputs, graph.host.state, shown,
        combine(graph.recorder.itemId, graph.recorder.session.state, graph.recorder.problem, ::Triple),
    ) { input, engine, item, (recId, rec, problem) ->
        val s = input.settings
        DictateUi(
            loaded = s != null,
            settings = s ?: AppSettings(),
            model = input.model,
            options = if (input.model != null && s != null) graph.options(input.model, s) else null,
            installed = ModelCatalog.all.filter { it.id in input.installed },
            engine = engine,
            item = item,
            session = rec.takeIf { recId != null && recId == item?.id },
            problem = problem?.takeIf { it.first == item?.id }?.second,
            recommendedDownload = input.downloads[ModelCatalog.PARAKEET.id],
            onWifi = input.onWifi,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DictateUi())

    val level: StateFlow<Float> = graph.recorder.session.level

    private data class Inputs(
        val settings: AppSettings?, val model: ModelSpec?, val installed: Set<String>,
        val downloads: Map<String, DownloadState>, val onWifi: Boolean,
    )

    /** Loads the model ahead of time so the first session starts instantly. */
    fun warmUp() {
        val u = ui.value
        val model = u.model ?: return
        val options = u.options ?: return
        if (u.settings.keepLoaded && !graph.recorder.isActive) graph.host.preload(model, options)
    }

    /** Starts a new recording; returns its library id (to open it), or null if no model is installed. */
    suspend fun start(): String? = graph.startRecording()

    fun selectModel(spec: ModelSpec) {
        viewModelScope.launch { graph.settingsRepo.update { it.copy(defaultModelId = spec.id) } }
    }

    fun selectLanguage(code: String) {
        val model = ui.value.model
        viewModelScope.launch { graph.settingsRepo.update { it.withLanguage(code, model) } }
    }

    fun dismissProblem() = graph.recorder.dismissProblem()

    fun downloadRecommended(useMobileData: Boolean = false) {
        val wifiOnly = (ui.value.settings.wifiOnly) && !useMobileData
        graph.downloads.start(ModelCatalog.PARAKEET, wifiOnly)
    }

    fun pauseRecommended() = graph.downloads.pause(ModelCatalog.PARAKEET)
}
