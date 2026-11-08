package dev.pbou.stt

import android.app.Application
import android.net.Uri
import android.os.LocaleList
import dev.pbou.stt.data.AppSettings
import dev.pbou.stt.data.BenchStore
import dev.pbou.stt.data.Downloads
import dev.pbou.stt.data.FileJob
import dev.pbou.stt.data.SettingsRepository
import dev.pbou.stt.engine.Benchmark
import dev.pbou.stt.engine.ClipRecorder
import dev.pbou.stt.engine.EngineHost
import dev.pbou.stt.engine.EngineOptions
import dev.pbou.stt.engine.FileTranscriber
import dev.pbou.stt.engine.HttpFetcher
import dev.pbou.stt.engine.LiveTranscriber
import dev.pbou.stt.engine.ModelCatalog
import dev.pbou.stt.engine.ModelInstaller
import dev.pbou.stt.engine.ModelSpec
import dev.pbou.stt.engine.ModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class SttApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

/** The app's singletons, built once (no DI framework: the graph is small). */
class AppGraph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // External app storage, so models can also be copied in with adb (tools/push_models.sh).
    val store = ModelStore(File(app.getExternalFilesDir(null) ?: app.filesDir, "models"))
    val installer = ModelInstaller(HttpFetcher("stt_app/${BuildConfig.VERSION_NAME} (Android)"))
    val host = EngineHost(store)
    val live = LiveTranscriber(app.assets, host)
    /** The keyboard's own dictation, so what is typed elsewhere never lands in the app's transcript. */
    val keyboardLive = LiveTranscriber(app.assets, host)
    val settingsRepo = SettingsRepository(app)
    val downloads = Downloads(app, store)
    val bench = BenchStore(File(app.filesDir, "bench"))
    val clipRecorder = ClipRecorder(app.assets)
    val benchmark = Benchmark(host)
    val files = FileJob(
        app, scope, FileTranscriber(app.assets, host),
        defaultModel = { dictationModel(settings.filterNotNull().first()) },
        defaultLanguage = { settings.filterNotNull().first().language },
        optionsFor = { spec, language -> options(spec, settings.filterNotNull().first(), language) },
        stopDictation = { live.stop() },
    )

    /** Asks the UI to show the file screen (a file was shared to the app). */
    val showFile = Channel<Unit>(Channel.CONFLATED)

    fun openFile(uri: Uri) {
        files.open(uri)
        showFile.trySend(Unit)
    }

    /** Null until the settings are read from disk (a few milliseconds after start). */
    val settings: StateFlow<AppSettings?> = settingsRepo.settings.stateIn(scope, SharingStarted.Eagerly, null)

    /** The model dictation uses: the chosen one if installed, else the first installed one. */
    init {
        scope.launch { bench.load() } // the Models screen shows measured results too
    }

    val activeModel: StateFlow<ModelSpec?> = combine(settings, store.installed) { s, installed ->
        val chosen = ModelCatalog.byId(s?.defaultModelId)?.takeIf { it.id in installed }
        chosen ?: ModelCatalog.all.firstOrNull { it.id in installed }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private fun dictationModel(s: AppSettings): ModelSpec? {
        val installed = store.installed.value
        return ModelCatalog.byId(s.defaultModelId)?.takeIf { it.id in installed } ?: ModelCatalog.all.firstOrNull { it.id in installed }
    }

    fun options(spec: ModelSpec, s: AppSettings, language: String = s.language) = EngineOptions(
        language = spec.effectiveLanguage(language, deviceLanguages()),
        threads = s.threads,
    )

    fun deviceLanguages(): List<String> {
        val list = LocaleList.getDefault()
        return (0 until list.size()).map { list[it].language }
    }

    /** A freshly installed model becomes the default if there is no usable default yet. */
    suspend fun onModelInstalled(spec: ModelSpec) {
        store.refresh()
        settingsRepo.update { s ->
            val current = ModelCatalog.byId(s.defaultModelId)
            if (current != null && store.isInstalled(current)) s else s.copy(defaultModelId = spec.id)
        }
    }

    fun refreshStore() {
        scope.launch(Dispatchers.IO) { store.refresh() }
    }
}
