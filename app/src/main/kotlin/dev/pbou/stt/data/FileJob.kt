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
    /** The language chosen for this file (starts as the settings' one). */
    val language: String? = null,
    /** The language the model was told, null if it detects it (or is not running yet). */
    val usedLanguage: String? = null,
    val durationMs: Long? = null,
    val doneMs: Long = 0,
    val segments: List<FileTranscriber.Segment> = emptyList(),
    val elapsedMs: Long = 0,
    val problem: Problem? = null,
) {
    enum class Phase { COPYING, RUNNING, DONE, STOPPED, FAILED }
    enum class Problem { UNREADABLE, NO_MODEL, ENGINE }

    val busy get() = phase == Phase.COPYING || phase == Phase.RUNNING
    val text get() = segments.joinToString(" ") { it.text }
}

/**
 * Receives audio files (shared from another app, or picked), copies them into the app's cache (a
 * shared link may stop working once the sharing app is gone) and transcribes them in the background.
 * Runs in the app's scope, so leaving the screen does not stop it.
 */
class FileJob(
    private val context: Context,
    private val scope: CoroutineScope,
    private val transcriber: FileTranscriber,
    /** The model to use for a new file: the dictation model (null if none is installed). */
    private val defaultModel: suspend () -> ModelSpec?,
    /** The language of a new file: the one in the settings. */
    private val defaultLanguage: suspend () -> String,
    private val optionsFor: suspend (ModelSpec, language: String) -> EngineOptions,
    private val stopDictation: () -> Unit,
) {
    private val _state = MutableStateFlow<FileState?>(null)
    val state: StateFlow<FileState?> = _state.asStateFlow()

    private var job: Job? = null
    private val dir = File(context.cacheDir, "shared")
    private val input = File(dir, "input")

    /** Copies [uri] into the cache, then transcribes it with the dictation model. */
    fun open(uri: Uri) {
        val previous = job
        previous?.cancel()
        val name = displayName(uri)
        _state.value = FileState(name)
        job = scope.launch {
            previous?.join() // it may still be reading the previous copy
            val model = defaultModel()
            val language = defaultLanguage()
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
            run(model, language)
        }
    }

    /** Transcribes the current file again, with another model or language. */
    fun rerun(model: ModelSpec, language: String? = null) {
        val s = _state.value ?: return
        if (s.phase == FileState.Phase.COPYING || !input.exists()) return
        val previous = job
        previous?.cancel()
        _state.value = FileState(s.name, model = model, language = language ?: s.language, durationMs = s.durationMs)
        job = scope.launch {
            previous?.join()
            run(model, language ?: s.language ?: defaultLanguage())
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
        stopDictation() // one user of the engine at a time
        val t0 = SystemClock.elapsedRealtime()
        val options = optionsFor(model, language)
        _state.update { it?.copy(phase = FileState.Phase.RUNNING, model = model, language = language, usedLanguage = options.language) }
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnsupportedAudioException) {
            _state.update { it?.copy(phase = FileState.Phase.FAILED, problem = FileState.Problem.UNREADABLE) }
        } catch (t: Throwable) {
            _state.update { it?.copy(phase = FileState.Phase.FAILED, problem = FileState.Problem.ENGINE) }
        }
    }

    private fun displayName(uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "audio"
}
