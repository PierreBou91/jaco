package dev.pbou.stt

import android.app.Application
import android.net.Uri
import android.os.LocaleList
import dev.pbou.stt.data.AppSettings
import dev.pbou.stt.data.BenchStore
import dev.pbou.stt.data.DownloadState
import dev.pbou.stt.data.Downloads
import dev.pbou.stt.data.FileJob
import dev.pbou.stt.data.Improver
import dev.pbou.stt.data.Library
import dev.pbou.stt.data.LibraryItem
import dev.pbou.stt.data.Recorder
import dev.pbou.stt.data.SettingsRepository
import dev.pbou.stt.data.SpeakerFinder
import dev.pbou.stt.data.Voices
import dev.pbou.stt.engine.Benchmark
import dev.pbou.stt.engine.ClipRecorder
import dev.pbou.stt.engine.Diarizer
import dev.pbou.stt.engine.EngineHost
import dev.pbou.stt.engine.EngineOptions
import dev.pbou.stt.engine.FileTranscriber
import dev.pbou.stt.engine.HttpFetcher
import dev.pbou.stt.engine.Languages
import dev.pbou.stt.engine.LiveTranscriber
import dev.pbou.stt.engine.ModelCatalog
import dev.pbou.stt.engine.ModelInstaller
import dev.pbou.stt.engine.ModelSpec
import dev.pbou.stt.engine.ModelStore
import dev.pbou.stt.engine.RecordingSession
import dev.pbou.stt.engine.SpeakerModels
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
    /** The keyboard's own dictation, so what is typed elsewhere never lands in the app's transcript. */
    val keyboardLive = LiveTranscriber(app.assets, host)
    val settingsRepo = SettingsRepository(app)
    val downloads = Downloads(app, store)
    val bench = BenchStore(File(app.filesDir, "bench"))
    val clipRecorder = ClipRecorder(app.assets)
    val benchmark = Benchmark(host)
    val fileTranscriber = FileTranscriber(app.assets, host)
    val library = Library(File(app.filesDir, "library"))
    val improver = Improver(app, library)
    val voices = Voices(File(app.filesDir, "voices.json"))
    val diarizer = Diarizer(store)
    val speakerFinder = SpeakerFinder(app, library)
    val recorder = Recorder(app, scope, library, RecordingSession(app.assets, host), onSaved = { autoImprove(it); autoSpeakers(it) })
    val files = FileJob(
        app, scope, fileTranscriber,
        defaultModel = { fileModel(settings.filterNotNull().first()) },
        defaultLanguage = { spec -> settings.filterNotNull().first().let { s -> spec?.let(s::languageFor) ?: s.language } },
        optionsFor = { spec, language ->
            val s = settings.filterNotNull().first()
            // "Detect" chosen for a file, then a model that cannot detect: use the spoken language.
            val wanted = if (language == ModelSpec.AUTO && spec.languages !is Languages.ChooseOrAuto) s.language else language
            options(spec, s, wanted)
        },
        library = library,
        keepAudio = { awaitSettings().keepAudio },
        awaitNoRecording = { onWait ->
            if (recorder.isActive) {
                onWait()
                recorder.itemId.first { it == null }
            }
        },
        onDone = { id -> library.get(id)?.takeIf { it.speakers == null }?.let { autoSpeakers(it) } },
    )

    /** Asks the UI to show a library item (from the recording notification). */
    val showItem = Channel<String>(Channel.CONFLATED)

    suspend fun awaitSettings(): AppSettings = settings.filterNotNull().first()

    /** Something is using the engine for the user right now: background work must wait. */
    fun engineBusy(): Boolean =
        recorder.isActive || keyboardLive.isRunning || files.state.value?.busy == true

    /** Starts a recording with the dictation model; returns its library id, or null if no model is installed. */
    suspend fun startRecording(): String? {
        val s = awaitSettings()
        val spec = dictationModel(s) ?: return null
        return recorder.start(spec, options(spec, s), s.keepAudio, s.pauseSeconds)
    }

    /** Records more at the end of recording [id], with the dictation model. False if it cannot be continued. */
    suspend fun continueRecording(id: String): Boolean {
        val s = awaitSettings()
        val spec = dictationModel(s) ?: return false
        return recorder.continueRecording(id, spec, options(spec, s), s.pauseSeconds)
    }

    /** A recording was saved with its audio: queue a better transcription if the settings ask for one. */
    private suspend fun autoImprove(item: LibraryItem) {
        val s = awaitSettings()
        val model = s.improveModelId?.takeIf { it in store.installed.value } ?: return
        if (!s.keepAudio || item.audio == null || item.transcript?.modelId == model) return
        improver.request(item.id, model, language = null, whileCharging = true)
    }

    /** An item was saved with its audio (a recording, continued or not, or a file): find its speakers if the settings ask. */
    private suspend fun autoSpeakers(item: LibraryItem) {
        val s = awaitSettings()
        if (!s.findSpeakers || item.audio == null) return
        speakerFinder.request(item.id, item.speakers?.count)
        ensureSpeakerModels()
    }

    /** Fetches the speaker models if they are missing and not on their way (over Wi-Fi if the settings say so). */
    suspend fun ensureSpeakerModels() {
        if (store.isInstalled(SpeakerModels)) return
        val state = downloads.states.first()[SpeakerModels.id]
        if (state != null && state !is DownloadState.Failed) return // already on its way
        downloads.start(SpeakerModels, awaitSettings().wifiOnly)
    }

    /** The speaker models are in: run the searches that waited for them. */
    fun onSpeakerModelsInstalled() = speakerFinder.schedule()

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
        scope.launch {
            library.load()
            recorder.recover()
            improver.schedule()
            voices.load()
            speakerFinder.schedule()
        }
        scope.launch { settings.filterNotNull().collect { recorder.allowPreview(it.livePreview) } }
        // Dictation, the keyboard or a recording starts: a background improvement steps aside.
        scope.launch {
            combine(keyboardLive.state, recorder.itemId, files.state) { k, r, f ->
                k.phase != LiveTranscriber.Phase.IDLE || r != null || f?.busy == true
            }.collect { busy ->
                if (busy) {
                    improver.yieldEngine()
                    speakerFinder.yieldEngine()
                }
            }
        }
    }

    val activeModel: StateFlow<ModelSpec?> = combine(settings, store.installed) { s, installed ->
        val chosen = ModelCatalog.byId(s?.defaultModelId)?.takeIf { it.id in installed }
        chosen ?: ModelCatalog.all.firstOrNull { it.id in installed }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private fun dictationModel(s: AppSettings): ModelSpec? {
        val installed = store.installed.value
        return ModelCatalog.byId(s.defaultModelId)?.takeIf { it.id in installed } ?: ModelCatalog.all.firstOrNull { it.id in installed }
    }

    /** The model for files: the one chosen for files if installed, else the dictation model. */
    fun fileModel(s: AppSettings): ModelSpec? =
        ModelCatalog.byId(s.fileModelId)?.takeIf { it.id in store.installed.value } ?: dictationModel(s)

    fun options(spec: ModelSpec, s: AppSettings, language: String = s.languageFor(spec)) = EngineOptions(
        language = spec.effectiveLanguage(language, deviceLanguages()),
        threads = s.effectiveThreads,
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
