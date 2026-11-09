package dev.pbou.stt.data

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import dev.pbou.stt.engine.EngineOptions
import dev.pbou.stt.engine.FileTranscriber
import dev.pbou.stt.engine.ModelSpec
import dev.pbou.stt.engine.UnsupportedAudioException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The file being transcribed: one at a time, kept until another file is opened. */
data class FileState(
    val name: String,
    val phase: Phase = Phase.COPYING,
    val model: ModelSpec? = null,
    /** The language chosen for this file, "auto" or a code (starts as the settings' one). */
    val language: String? = null,
    /** The language the model was told, null if it detects it (or is not running yet). */
    val usedLanguage: String? = null,
    val durationMs: Long? = null,
    val doneMs: Long = 0,
    val segments: List<FileTranscriber.Segment> = emptyList(),
    val elapsedMs: Long = 0,
    val problem: Problem? = null,
    /** Its entry in the library. */
    val itemId: String? = null,
    /** Waits for the recording in progress to end (one model in memory at a time). */
    val waitingForRecording: Boolean = false,
) {
    enum class Phase { COPYING, RUNNING, DONE, STOPPED, FAILED }
    enum class Problem { UNREADABLE, NO_MODEL, ENGINE }

    val busy get() = phase == Phase.COPYING || phase == Phase.RUNNING
    val text get() = segments.joinToString(" ") { it.text }
}

/**
 * Receives audio files (shared from another app, or picked), copies them into the app's cache (a
 * shared link may stop working once the sharing app is gone) and transcribes them in the background.
 * Runs in the app's scope, so leaving the screen does not stop it. Each file becomes a [Library] item,
 * with a copy of the audio if the settings keep it; transcribing it again adds a transcript to it.
 */
class FileJob(
    private val context: Context,
    private val scope: CoroutineScope,
    private val transcriber: FileTranscriber,
    /** The model to use for a new file: the one set for files, else the dictation model (null if none is installed). */
    private val defaultModel: suspend () -> ModelSpec?,
    /** The language of a new file for this model: the one in the settings ("auto" or a code). */
    private val defaultLanguage: suspend (ModelSpec?) -> String,
    private val optionsFor: suspend (ModelSpec, language: String) -> EngineOptions,
    private val library: Library,
    private val keepAudio: suspend () -> Boolean,
    /** Returns once no recording is running (it holds its model in memory). */
    private val awaitNoRecording: suspend (onWait: () -> Unit) -> Unit,
    /** A file was transcribed to the end (its library item is saved). */
    private val onDone: suspend (itemId: String) -> Unit = {},
) {
    private val _state = MutableStateFlow<FileState?>(null)
    val state: StateFlow<FileState?> = _state.asStateFlow()

    private var job: Job? = null
    private val dir = File(context.cacheDir, "shared")
    private val input = File(dir, "input")

    /** Copies [uri] into the cache, then transcribes it with the model for files. */
    fun open(uri: Uri) {
        val previous = job
        previous?.cancel()
        val name = displayName(uri)
        val itemId = library.newId()
        _state.value = FileState(name, itemId = itemId)
        job = scope.launch {
            previous?.join() // it may still be reading the previous copy
            val model = defaultModel()
            val language = defaultLanguage(model)
            _state.update { it?.copy(model = model, language = language) }
            val copied = withContext(Dispatchers.IO) {
                runCatching {
                    dir.mkdirs()
                    context.contentResolver.openInputStream(uri)!!.use { src -> input.outputStream().use { src.copyTo(it) } }
                }.isSuccess
            }
            if (!copied) {
                _state.update { it?.copy(phase = FileState.Phase.FAILED, problem = FileState.Problem.UNREADABLE) }
                return@launch
            }
            val audio = if (keepAudio()) withContext(Dispatchers.IO) {
                val file = "audio." + (name.substringAfterLast('.', "").lowercase().takeIf { it.length in 2..4 } ?: "m4a")
                runCatching { input.copyTo(File(library.dir(itemId).apply { mkdirs() }, file), overwrite = true) }.map { file }.getOrNull()
            } else null
            library.add(
                LibraryItem(
                    id = itemId, kind = LibraryItem.Kind.FILE, title = name.substringBeforeLast('.').ifBlank { name },
                    createdAt = System.currentTimeMillis(), durationMs = 0, audio = audio, transcripts = emptyList(),
                    shown = 0, status = LibraryItem.Status.TRANSCRIBING,
                ),
            )
            run(model, language)
        }
    }

    /** Transcribes the current file again, with another model or language. */
    fun rerun(model: ModelSpec, language: String? = null) {
        val s = _state.value ?: return
        if (s.phase == FileState.Phase.COPYING || !input.exists()) return
        val previous = job
        previous?.cancel()
        _state.value = FileState(s.name, model = model, language = language ?: s.language, durationMs = s.durationMs, itemId = s.itemId)
        job = scope.launch {
            previous?.join()
            run(model, language ?: s.language ?: defaultLanguage(model))
        }
    }

    fun stop() {
        job?.cancel()
        _state.update { if (it?.busy == true) it.copy(phase = FileState.Phase.STOPPED) else it }
    }

    private suspend fun run(model: ModelSpec?, language: String) {
        if (model == null) {
            _state.update { it?.copy(phase = FileState.Phase.FAILED, problem = FileState.Problem.NO_MODEL) }
            return
        }
        awaitNoRecording { _state.update { it?.copy(waitingForRecording = true) } }
        _state.update { it?.copy(waitingForRecording = false) }
        val t0 = SystemClock.elapsedRealtime()
        val options = optionsFor(model, language)
        _state.update { it?.copy(phase = FileState.Phase.RUNNING, model = model, language = language, usedLanguage = options.language) }
        val itemId = _state.value?.itemId
        // A new transcript in the library item, filled as sentences come.
        var index = -1
        if (itemId != null) library.update(itemId) {
            val others = it.transcripts.withoutOlder(model.id)
            index = others.size
            it.copy(
                transcripts = others + Transcript(model.id, options.language, live = false, System.currentTimeMillis(), emptyList()),
                shown = index, status = LibraryItem.Status.TRANSCRIBING,
            )
        }
        suspend fun save(done: Boolean) {
            if (itemId == null || index < 0) return
            val s = _state.value ?: return
            library.update(itemId) { item ->
                item.copy(
                    transcripts = item.transcripts.mapIndexed { i, t -> if (i == index) t.copy(segments = s.segments) else t },
                    durationMs = s.durationMs ?: item.durationMs,
                    status = if (done) LibraryItem.Status.DONE else item.status,
                )
            }
        }
        // Saved every few seconds, so a long file is not lost if the app is closed.
        val saver = scope.launch {
            while (true) {
                delay(SAVE_EVERY_MS)
                save(done = false)
            }
        }
        try {
            transcriber.transcribe(input, model, options, object : FileTranscriber.Listener {
                override fun onOpened(durationMs: Long?) = _state.update { it?.copy(durationMs = durationMs) }
                override fun onProgress(doneMs: Long) = _state.update {
                    it?.copy(doneMs = maxOf(it.doneMs, doneMs), elapsedMs = SystemClock.elapsedRealtime() - t0)
                }
                override fun onSegment(segment: FileTranscriber.Segment) = _state.update { it?.copy(segments = it.segments + segment) }
            })
            _state.update {
                it?.copy(phase = FileState.Phase.DONE, doneMs = it.durationMs ?: it.doneMs, elapsedMs = SystemClock.elapsedRealtime() - t0)
            }
            save(done = true)
            itemId?.let { onDone(it) }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { save(done = true) }
            throw e
        } catch (e: UnsupportedAudioException) {
            _state.update { it?.copy(phase = FileState.Phase.FAILED, problem = FileState.Problem.UNREADABLE) }
            // Nothing could be read: the file has no place in the library.
            if (itemId != null && _state.value?.segments.isNullOrEmpty()) library.delete(itemId) else save(done = true)
        } catch (t: Throwable) {
            _state.update { it?.copy(phase = FileState.Phase.FAILED, problem = FileState.Problem.ENGINE) }
            save(done = true)
        } finally {
            saver.cancel()
        }
    }

    private companion object {
        const val SAVE_EVERY_MS = 5_000L
    }

    private fun displayName(uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "audio"
}
