package dev.pbou.stt.ui.library

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pbou.stt.AppGraph
import dev.pbou.stt.R
import dev.pbou.stt.data.DownloadState
import dev.pbou.stt.data.LibraryItem
import dev.pbou.stt.data.SpeakerText
import dev.pbou.stt.data.Speakers
import dev.pbou.stt.data.Voice
import dev.pbou.stt.engine.SpeakerModels
import dev.pbou.stt.engine.ModelCatalog
import dev.pbou.stt.engine.ModelSpec
import dev.pbou.stt.engine.RecordingSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ItemUi(
    val item: LibraryItem? = null,
    /** Set while this item is being recorded. */
    val recording: RecordingSession.State? = null,
    val recordingProblem: RecordingSession.Problem? = null,
    val improving: Float? = null,
    /** Progress of the speaker search running now. */
    val findingSpeakers: Float? = null,
    /** The speaker models are being fetched (or wait for Wi-Fi) before the search can start. */
    val speakerModels: DownloadState? = null,
    val voices: List<Voice> = emptyList(),
    /** Models that can transcribe it again (installed). */
    val installed: List<ModelSpec> = emptyList(),
    val deleted: Boolean = false,
)

data class PlayerUi(val ready: Boolean = false, val playing: Boolean = false, val positionMs: Long = 0, val durationMs: Long = 0)

class ItemViewModel(private val graph: AppGraph, private val id: String) : ViewModel() {
    private val deleted = MutableStateFlow(false)

    private val speakerState = combine(graph.speakerFinder.progress, graph.downloads.states, graph.voices.all, ::Triple)

    val ui: StateFlow<ItemUi> = combine(
        graph.library.item(id),
        combine(graph.recorder.itemId, graph.recorder.session.state, graph.recorder.problem, ::Triple),
        combine(graph.improver.progress, speakerState, ::Pair),
        graph.store.installed,
        deleted,
    ) { item, (recId, rec, problem), (progress, speakers), installed, gone ->
        val (finding, downloads, voices) = speakers
        ItemUi(
            item = item,
            recording = rec.takeIf { recId == id },
            recordingProblem = problem?.takeIf { it.first == id }?.second,
            improving = progress[id],
            findingSpeakers = finding[id],
            speakerModels = downloads[SpeakerModels.id],
            voices = voices,
            installed = ModelCatalog.all.filter { it.id in installed },
            deleted = gone,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ItemUi(item = graph.library.get(id)))

    val level: StateFlow<Float> = graph.recorder.session.level

    // Recording controls.
    fun pause() = graph.recorder.pause()
    fun resume() = graph.recorder.resume()
    fun stop() = graph.recorder.stop()
    fun dismissProblem() = graph.recorder.dismissProblem()

    fun rename(title: String) = viewModelScope.launch {
        graph.library.update(id) { it.copy(title = title.trim().ifBlank { null }) }
    }

    fun show(index: Int) = viewModelScope.launch {
        graph.library.update(id) { it.copy(shown = index.coerceIn(0, it.transcripts.lastIndex)) }
    }

    fun delete(context: Context) = viewModelScope.launch {
        releasePlayer()
        graph.improver.cancel(id)
        graph.library.delete(id)
        // A shared copy of its audio may still be in the cache.
        withContext(Dispatchers.IO) { File(context.cacheDir, EXPORT_DIR).deleteRecursively() }
        deleted.value = true
    }

    /** Transcribe again from the audio, now, in [language] (a code or [ModelSpec.AUTO]; null: the settings'). */
    fun transcribeAgain(spec: ModelSpec, language: String?) = viewModelScope.launch {
        graph.improver.request(id, spec.id, language, whileCharging = false)
    }

    fun improveNow() = viewModelScope.launch {
        val r = graph.library.get(id)?.improve ?: return@launch
        graph.improver.request(id, r.modelId, r.language, whileCharging = false)
    }

    /** The language "Transcribe again" starts with for [spec]: the settings' one. */
    fun defaultLanguage(spec: ModelSpec): String? = graph.settings.value?.languageFor(spec)

    /** Frees the space of the audio; the text stays. No playback or better transcription afterwards. */
    fun deleteAudio(context: Context) = viewModelScope.launch {
        releasePlayer()
        graph.improver.cancel(id)
        val item = graph.library.get(id) ?: return@launch
        withContext(Dispatchers.IO) {
            graph.library.audioFile(item)?.delete()
            File(context.cacheDir, EXPORT_DIR).deleteRecursively()
        }
        graph.library.update(id) { it.copy(audio = null) }
    }

    /** Records more at the end of this recording. False if it cannot be continued (no model, recording busy). */
    suspend fun continueRecording(): Boolean {
        releasePlayer()
        return graph.continueRecording(id)
    }

    /** The word-by-word preview of a recording runs only while a screen showing it is visible. */
    fun watching(on: Boolean) = graph.recorder.watching(on)

    fun cancelImprove() = viewModelScope.launch { graph.improver.cancel(id) }

    // Speakers.
    /** Finds who spoke when; [count]: how many people, null to find out. */
    fun findSpeakers(count: Int?) = viewModelScope.launch {
        graph.speakerFinder.request(id, count)
        graph.ensureSpeakerModels()
    }

    fun cancelSpeakers() = viewModelScope.launch { graph.speakerFinder.cancel(id) }

    /**
     * Names person [index] [name] (blank: back to "Speaker N"). Another person here with that name is the
     * same one: they are merged. With [remember], the voice is saved under that name, to be recognised in
     * later recordings (or teaches the saved voice of that name).
     */
    fun nameSpeaker(index: Int, name: String, remember: Boolean) = viewModelScope.launch {
        val speakers = graph.library.get(id)?.speakers ?: return@launch
        val person = speakers.people.getOrNull(index) ?: return@launch
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            graph.library.update(id) { it.copy(speakers = it.speakers?.withPerson(index, person.copy(name = null, voiceId = null))) }
            return@launch
        }
        val voiceId = if (remember) graph.voices.remember(trimmed, person.voice, person.seconds) else null
        val names = SpeakerText.names(speakers, { graph.voices.get(it)?.name }, { "" })
        val twin = names.indices.firstOrNull { it != index && names[it].equals(trimmed, ignoreCase = true) }
        graph.library.update(id) { item ->
            var s = item.speakers ?: return@update item
            s = s.withPerson(index, person.copy(name = trimmed, voiceId = voiceId))
            if (twin != null) s = SpeakerText.merge(s, from = index, into = twin).let { m ->
                // The merged person keeps the name just given (and the voice, if remembered).
                val into = if (twin > index) twin - 1 else twin
                m.withPerson(into, m.people[into].copy(name = trimmed, voiceId = voiceId ?: m.people[into].voiceId))
            }
            item.copy(speakers = s)
        }
    }

    private fun Speakers.withPerson(index: Int, p: Speakers.Person) = copy(people = people.mapIndexed { i, old -> if (i == index) p else old })

    /** Who said each sentence of the shown transcript, and the names, for export. */
    private fun speakerLines(context: Context, item: LibraryItem): Pair<List<Int?>, List<String>> {
        val speakers = item.speakers ?: return item.transcript?.segments.orEmpty().map { null } to emptyList()
        val who = SpeakerText.speakerOf(item.transcript?.segments.orEmpty(), speakers)
        val names = SpeakerText.names(speakers, { graph.voices.get(it)?.name }, { context.getString(R.string.speaker_n, it) })
        return who to names
    }

    // Playback.
    private val _player = MutableStateFlow(PlayerUi())
    val player: StateFlow<PlayerUi> = _player.asStateFlow()
    private var mp: MediaPlayer? = null
    private var ticker: Job? = null

    /** Prepares the player once the item has audio (after a recording ends, for example). */
    fun preparePlayer() {
        if (mp != null) return
        val item = graph.library.get(id) ?: return
        if (item.status != LibraryItem.Status.DONE) return
        val file = graph.library.audioFile(item) ?: return
        val p = MediaPlayer()
        try {
            p.setDataSource(file.path)
            p.prepare()
        } catch (t: Throwable) {
            p.release()
            return
        }
        p.setOnCompletionListener { _player.update { it.copy(playing = false, positionMs = it.durationMs) }; ticker?.cancel() }
        mp = p
        _player.value = PlayerUi(ready = true, durationMs = p.duration.toLong())
    }

    fun togglePlay() {
        val p = mp ?: return
        if (p.isPlaying) pausePlayer() else {
            if (p.currentPosition >= p.duration - 50) p.seekTo(0)
            p.start()
            _player.update { it.copy(playing = true) }
            tick()
        }
    }

    fun pausePlayer() {
        val p = mp ?: return
        if (p.isPlaying) p.pause()
        ticker?.cancel()
        _player.update { it.copy(playing = false, positionMs = p.currentPosition.toLong()) }
    }

    /** Jumps to [ms] and plays from there. */
    fun playFrom(ms: Long) {
        val p = mp ?: return
        p.seekTo(ms.coerceIn(0, p.duration.toLong()).toInt())
        _player.update { it.copy(positionMs = ms) }
        if (!p.isPlaying) togglePlay()
    }

    fun seek(ms: Long) {
        val p = mp ?: return
        p.seekTo(ms.coerceIn(0, p.duration.toLong()).toInt())
        _player.update { it.copy(positionMs = ms.coerceIn(0, it.durationMs)) }
    }

    fun skip(deltaMs: Long) = seek((mp?.currentPosition?.toLong() ?: 0) + deltaMs)

    private fun tick() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (true) {
                mp?.let { p -> _player.update { it.copy(positionMs = p.currentPosition.toLong()) } }
                delay(TICK_MS)
            }
        }
    }

    private fun releasePlayer() {
        ticker?.cancel()
        mp?.release()
        mp = null
        _player.value = PlayerUi()
    }

    override fun onCleared() {
        releasePlayer()
    }

    // Export.
    fun shareText(context: Context, timestamps: Boolean) {
        val item = graph.library.get(id) ?: return
        dev.pbou.stt.ui.dictate.shareText(context, exportText(context, item, timestamps))
    }

    /** With [timestamps] (the default in a recording): "[12:04] Anna: ..." lines, under the title. */
    fun copyText(context: Context, timestamps: Boolean) {
        val item = graph.library.get(id) ?: return
        dev.pbou.stt.ui.dictate.copyText(context, exportText(context, item, timestamps))
    }

    private fun exportText(context: Context, item: LibraryItem, timestamps: Boolean): String {
        val segments = item.transcript?.segments.orEmpty()
        val (who, names) = speakerLines(context, item)
        return if (timestamps) SpeakerText.timestamped(itemTitle(context, item), segments, who, names) else SpeakerText.plain(segments, who, names)
    }

    /** Shares the audio under the item's title (copied to the cache: other apps show the file name). */
    fun shareAudio(context: Context) = viewModelScope.launch {
        val item = graph.library.get(id) ?: return@launch
        val source = graph.library.audioFile(item) ?: return@launch
        val name = itemTitle(context, item).replace(UNSAFE, "_").take(80) + "." + source.extension
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, EXPORT_DIR).apply { deleteRecursively(); mkdirs() }
            source.copyTo(File(dir, name), overwrite = true)
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(if (source.extension == "m4a") "audio/mp4" else "audio/*")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, null))
    }

    private companion object {
        const val TICK_MS = 200L
        const val EXPORT_DIR = "export"
        val UNSAFE = "[\\\\/:*?\"<>|\\n\\r]".toRegex()
    }
}
