package dev.pbou.stt.ui.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pbou.stt.AppGraph
import dev.pbou.stt.data.DownloadState
import dev.pbou.stt.engine.Benchmark
import dev.pbou.stt.engine.EngineHost
import dev.pbou.stt.engine.ModelCatalog
import dev.pbou.stt.engine.ModelSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ModelItem(
    val spec: ModelSpec,
    val installed: Boolean,
    val active: Boolean,
    val download: DownloadState?,
    /** Bytes kept from a paused download (resumable), when no download is running. */
    val pausedBytes: Long,
    /** The latest benchmark of this model on the user's voice, if any. */
    val measured: Benchmark.Result? = null,
)

data class Disk(val used: Long = 0, val free: Long = 0)

data class ModelsUi(
    val installed: List<ModelItem> = emptyList(),
    val available: List<ModelItem> = emptyList(),
    val disk: Disk = Disk(),
    val wifiOnly: Boolean = true,
    val onWifi: Boolean = true,
)

class ModelsViewModel(private val graph: AppGraph) : ViewModel() {

    private val disk = MutableStateFlow(Disk())
    private val paused = MutableStateFlow<Map<String, Long>>(emptyMap())

    val ui: StateFlow<ModelsUi> = combine(
        combine(graph.store.installed, graph.activeModel, graph.downloads.states, ::Triple),
        graph.settings, graph.downloads.onWifi, disk, combine(paused, graph.bench.results, ::Pair),
    ) { (installed, active, downloads), settings, onWifi, disk, (paused, measured) ->
        val items = ModelCatalog.all.map { spec ->
            ModelItem(
                spec = spec,
                installed = spec.id in installed,
                active = spec.id == active?.id,
                download = downloads[spec.id],
                pausedBytes = if (downloads[spec.id] == null) paused[spec.id] ?: 0 else 0,
                measured = measured.filter { it.modelId == spec.id }.maxByOrNull { it.finishedAt },
            )
        }
        ModelsUi(
            installed = items.filter { it.installed },
            available = items.filter { !it.installed },
            disk = disk,
            wifiOnly = settings?.wifiOnly ?: true,
            onWifi = onWifi,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ModelsUi())

    init {
        // Disk figures and paused downloads change when installs start, finish or are deleted.
        viewModelScope.launch {
            combine(graph.store.installed, graph.downloads.states) { _, d -> d.keys }.collect { refreshDisk() }
        }
    }

    fun refreshDisk() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val store = graph.store
                disk.value = Disk(used = store.usedBytes(), free = store.freeBytes())
                paused.value = ModelCatalog.all.associate { it.id to store.stagedBytes(it) }.filterValues { it > 0 }
            }
        }
    }

    fun download(spec: ModelSpec, useMobileData: Boolean = false) {
        graph.downloads.start(spec, wifiOnly = ui.value.wifiOnly && !useMobileData)
    }

    fun pause(spec: ModelSpec) {
        graph.downloads.pause(spec)
        refreshDisk()
    }

    fun discard(spec: ModelSpec) {
        viewModelScope.launch {
            graph.downloads.discard(spec)
            refreshDisk()
        }
    }

    fun use(spec: ModelSpec) {
        viewModelScope.launch { graph.settingsRepo.update { it.copy(defaultModelId = spec.id) } }
    }

    fun delete(spec: ModelSpec) {
        viewModelScope.launch {
            val loaded = graph.host.state.value
            val inMemory = (loaded as? EngineHost.State.Ready)?.spec ?: (loaded as? EngineHost.State.Loading)?.spec
            if (inMemory == spec) {
                // A recording or the keyboard using it ends first (what was said is kept).
                graph.recorder.stop()
                graph.keyboardLive.stop()
                graph.recorder.itemId.first { it == null }
                graph.host.release().join()
            }
            graph.store.delete(spec)
            refreshDisk()
        }
    }
}
