package dev.pbou.stt.ui.library

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pbou.stt.AppGraph
import dev.pbou.stt.data.LibraryItem
import dev.pbou.stt.data.LibraryText
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
    /** Models that can transcribe it again (installed). */
    val installed: List<ModelSpec> = emptyList(),
    val deleted: Boolean = false,
)

data class PlayerUi(val ready: Boolean = false, val playing: Boolean = false, val positionMs: Long = 0, val durationMs: Long = 0)

class ItemViewModel(private val graph: AppGraph, private val id: String) : ViewModel() {
    private val deleted = MutableStateFlow(false)

    val ui: StateFlow<ItemUi> = combine(
        graph.library.item(id),
        combine(graph.recorder.itemId, graph.recorder.session.state, graph.recorder.problem, ::Triple),
        graph.improver.progress,
        graph.store.installed,
        deleted,
    ) { item, (recId, rec, problem), progress, installed, gone ->
        ItemUi(
            item = item,
            recording = rec.takeIf { recId == id },
            recordingProblem = problem?.takeIf { it.first == id }?.second,
            improving = progress[id],
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

    /** Transcribe again from the audio, now. */
    fun transcribeAgain(spec: ModelSpec) = viewModelScope.launch {
        graph.improver.request(id, spec.id, whileCharging = false)
    }

    fun improveNow() = viewModelScope.launch {
        val r = graph.library.get(id)?.improve ?: return@launch
        graph.improver.request(id, r.modelId, whileCharging = false)
    }

    fun cancelImprove() = viewModelScope.launch { graph.improver.cancel(id) }

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
        val segments = item.transcript?.segments.orEmpty()
        val text = if (timestamps) LibraryText.timestamped(itemTitle(context, item), segments) else LibraryText.plain(segments)
        dev.pbou.stt.ui.dictate.shareText(context, text)
    }

    fun copyText(context: Context) {
        val item = graph.library.get(id) ?: return
        dev.pbou.stt.ui.dictate.copyText(context, LibraryText.plain(item.transcript?.segments.orEmpty()))
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
