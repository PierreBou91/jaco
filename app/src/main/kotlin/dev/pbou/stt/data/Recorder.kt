package dev.pbou.stt.data

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.pbou.stt.engine.AacWriter
import dev.pbou.stt.engine.EngineOptions
import dev.pbou.stt.engine.FileTranscriber
import dev.pbou.stt.engine.ModelSpec
import dev.pbou.stt.engine.RecordingSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Long recordings: one at a time, in the app's scope, kept alive by [RecorderService] (a foreground
 * service with a notification) while the app is in the background. Each sentence is saved in the
 * [Library] as soon as it is transcribed, so a crash loses at most the sentence being spoken.
 */
class Recorder(
    private val context: Context,
    private val scope: CoroutineScope,
    private val library: Library,
    val session: RecordingSession,
    /** Called once a recording is saved, with its audio: may queue a better transcription. */
    private val onSaved: suspend (LibraryItem) -> Unit,
) {
    private val _itemId = MutableStateFlow<String?>(null)
    /** The item being recorded (or finishing), null when idle. */
    val itemId: StateFlow<String?> = _itemId.asStateFlow()

    private val _problem = MutableStateFlow<Pair<String, RecordingSession.Problem>?>(null)
    /** The last recording's problem, with its item id. */
    val problem: StateFlow<Pair<String, RecordingSession.Problem>?> = _problem.asStateFlow()

    val isActive: Boolean get() = _itemId.value != null

    /** Starts recording with [spec]; returns the new item's id (or the current one if already recording). */
    suspend fun start(spec: ModelSpec, options: EngineOptions, keepAudio: Boolean, pauseSeconds: Float): String {
        _itemId.value?.let { return it }
        val id = library.newId()
        library.add(
            LibraryItem(
                id = id, kind = LibraryItem.Kind.RECORDING, title = null, createdAt = System.currentTimeMillis(),
                durationMs = 0, audio = null, shown = 0, status = LibraryItem.Status.RECORDING,
                transcripts = listOf(Transcript(spec.id, options.language, live = true, System.currentTimeMillis(), emptyList())),
            ),
        )
        val aac = if (keepAudio) File(library.dir(id), AAC) else null
        begin(id, RecordingSession.Config(spec, options, aac, pauseSeconds = pauseSeconds))
        return id
    }

    /**
     * Records more at the end of a finished recording: the new sentences go after the live transcript's,
     * and the audio (if the recording has some) after its audio. Versions made from the whole audio
     * (improved ones) do not cover the new part, so the live one is shown. False if it cannot be continued.
     */
    suspend fun continueRecording(id: String, spec: ModelSpec, options: EngineOptions, pauseSeconds: Float): Boolean {
        if (_itemId.value != null) return false
        val item = library.get(id) ?: return false
        if (item.kind != LibraryItem.Kind.RECORDING || item.status != LibraryItem.Status.DONE) return false
        val dir = library.dir(id)
        // Back to raw AAC frames, which new ones can simply follow. Without audio, the rest stays text only.
        val aac = withContext(Dispatchers.IO) {
            when (item.audio) {
                AAC -> File(dir, AAC)
                M4A -> runCatching {
                    AacWriter.toAdts(File(dir, M4A), File(dir, AAC))
                    File(dir, M4A).delete()
                    File(dir, AAC)
                }.getOrNull()
                else -> null
            }
        }
        if (item.audio != null && aac == null) return false // its audio could not be read: do not lose it
        library.update(id) { it.copy(status = LibraryItem.Status.RECORDING, audio = null, shown = 0) }
        begin(id, RecordingSession.Config(spec, options, aac, appendAudio = aac != null, offsetMs = item.durationMs, pauseSeconds = pauseSeconds))
        return true
    }

    private val previewLock = Any()
    private var watchers = 0
    private var previewAllowed = true

    /**
     * A screen showing the recording appears ([on]) or goes away. The sentence being spoken is shown (a
     * decode twice a second) only while at least one is visible: not with the screen off or in another app.
     */
    fun watching(on: Boolean) = synchronized(previewLock) {
        watchers = (watchers + if (on) 1 else -1).coerceAtLeast(0)
        session.preview = previewAllowed && watchers > 0
    }

    /** The "Show words while speaking" setting. */
    fun allowPreview(allowed: Boolean) = synchronized(previewLock) {
        previewAllowed = allowed
        session.preview = previewAllowed && watchers > 0
    }

    private fun begin(id: String, config: RecordingSession.Config) {
        _problem.value = null
        _itemId.value = id
        ContextCompat.startForegroundService(context, Intent(context, RecorderService::class.java))
        session.start(scope, config, object : RecordingSession.Listener {
            override suspend fun onSegment(segment: FileTranscriber.Segment) {
                library.update(id) { item ->
                    val t = item.transcripts.first()
                    item.copy(transcripts = listOf(t.copy(segments = t.segments + segment)) + item.transcripts.drop(1))
                }
            }

            override suspend fun onProblem(problem: RecordingSession.Problem) {
                _problem.value = id to problem
            }

            override suspend fun onFinished(recordedMs: Long) {
                val saved = finish(id, recordedMs)
                _itemId.value = null
                saved?.let { onSaved(it) }
            }
        })
    }

    fun pause() = session.pause()
    fun resume() = session.resume()
    fun stop() = session.stop()

    fun dismissProblem() {
        _problem.value = null
    }

    /** Turns the AAC frames into an M4A file and marks the item done. */
    private suspend fun finish(id: String, recordedMs: Long): LibraryItem? {
        val (audio, _) = withContext(Dispatchers.IO) { packAudio(library.dir(id)) }
        return library.update(id) { it.copy(status = LibraryItem.Status.DONE, durationMs = recordedMs, audio = audio) }
    }

    /**
     * After a crash (or the app being killed) during a recording: keep what was saved, and make its audio
     * playable. Call once at start, before any recording.
     */
    suspend fun recover() {
        library.items.value.filter { it.kind == LibraryItem.Kind.RECORDING && it.status != LibraryItem.Status.DONE }.forEach { item ->
            val (audio, audioMs) = withContext(Dispatchers.IO) { packAudio(library.dir(item.id)) }
            val lastSentence = item.transcript?.segments?.lastOrNull()?.endMs ?: 0
            library.update(item.id) {
                it.copy(status = LibraryItem.Status.DONE, audio = audio, durationMs = maxOf(it.durationMs, lastSentence, audioMs ?: 0))
            }
        }
    }

    /** Returns the audio's file name (null if there is none) and its length if it was just packed. */
    private fun packAudio(dir: File): Pair<String?, Long?> {
        val aac = File(dir, AAC)
        val m4a = File(dir, M4A)
        if (!aac.exists()) return M4A.takeIf { m4a.exists() } to null
        return try {
            val ms = AacWriter.toM4a(aac, m4a)
            aac.delete()
            M4A to ms
        } catch (t: Throwable) {
            // Keep the raw frames: most players read them too.
            m4a.delete()
            AAC.takeIf { aac.length() > 0 } to null
        }
    }

    private companion object {
        const val AAC = "audio.aac"
        const val M4A = "audio.m4a"
    }
}
