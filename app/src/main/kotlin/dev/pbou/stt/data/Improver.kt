package dev.pbou.stt.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.pbou.stt.R
import dev.pbou.stt.SttApplication
import dev.pbou.stt.engine.FileTranscriber
import dev.pbou.stt.engine.Languages
import dev.pbou.stt.engine.ModelCatalog
import dev.pbou.stt.engine.ModelSpec
import dev.pbou.stt.engine.UnsupportedAudioException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * Better transcriptions of recordings with their audio, by a bigger model, in the background (WorkManager:
 * survives leaving the app). Automatic ones wait for the charger. One at a time, and never while the
 * microphone is in use: the phone has one engine, and dictation must stay instant.
 *
 * What to do is stored in each item ([LibraryItem.improve]), so it survives restarts; [schedule] turns it
 * into work.
 */
class Improver(private val context: Context, private val library: Library) {
    private val work = WorkManager.getInstance(context)

    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    /** Item id to progress in [0, 1], for the improvements running now. */
    val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()

    /** One improvement at a time (WorkManager may start several). */
    internal val lock = Mutex()

    /** Asks for a better transcription of [itemId] with [modelId] in [language] (null: the settings'): at once, or when charging. */
    suspend fun request(itemId: String, modelId: String, language: String?, whileCharging: Boolean) {
        library.update(itemId) { it.copy(improve = ImproveRequest(modelId, whileCharging, language)) } ?: return
        enqueue(itemId, whileCharging, delayMinutes = 0)
    }

    suspend fun cancel(itemId: String) {
        work.cancelUniqueWork(name(itemId))
        library.update(itemId) { it.copy(improve = null) }
        _progress.update { it - itemId }
    }

    /** Queues the work of every item that asked for an improvement (at start: work may have been lost). */
    fun schedule() {
        library.items.value.forEach { item -> item.improve?.let { enqueue(item.id, it.whileCharging, delayMinutes = 0, replace = false) } }
    }

    /** The microphone is needed: stop improving, and try again a little later. */
    fun yieldEngine() {
        val running = _progress.value.keys
        if (running.isEmpty()) return
        running.forEach { id -> library.get(id)?.improve?.let { enqueue(id, it.whileCharging, delayMinutes = YIELD_MINUTES) } }
        _progress.value = emptyMap()
    }

    internal fun report(itemId: String, fraction: Float?) =
        _progress.update { if (fraction == null) it - itemId else it + (itemId to fraction) }

    private fun enqueue(itemId: String, whileCharging: Boolean, delayMinutes: Long, replace: Boolean = true) {
        val request = OneTimeWorkRequestBuilder<ImproveWorker>()
            .setInputData(workDataOf(ImproveWorker.KEY_ITEM to itemId))
            .setConstraints(Constraints.Builder().setRequiresCharging(whileCharging).build())
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
            .addTag(TAG)
            .build()
        work.enqueueUniqueWork(name(itemId), if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    private fun name(itemId: String) = "improve-$itemId"

    companion object {
        const val TAG = "improve"
        private const val YIELD_MINUTES = 2L
    }
}

class ImproveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val graph get() = (applicationContext as SttApplication).graph

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ITEM) ?: return Result.failure()
        graph.library.load()
        val item = graph.library.get(id) ?: return Result.failure()
        val request = item.improve ?: return Result.success()
        val audio = graph.library.audioFile(item)
        val spec = ModelCatalog.byId(request.modelId)?.takeIf { it.id in graph.store.installed.value }
        if (audio == null || spec == null) {
            graph.library.update(id) { it.copy(improve = null) } // nothing to do it with any more
            return Result.failure()
        }
        if (graph.engineBusy()) return Result.retry()
        val improver = graph.improver
        return improver.lock.withLock {
            if (graph.engineBusy()) return@withLock Result.retry()
            runCatching { setForeground(foregroundInfo(item, spec.name, null)) }
            val settings = graph.awaitSettings()
            // "Detect" asked for a model that cannot detect: it gets the spoken language.
            val wanted = request.language?.takeIf { it != ModelSpec.AUTO || spec.languages is Languages.ChooseOrAuto } ?: settings.languageFor(spec)
            val options = graph.options(spec, settings, wanted)
            val segments = ArrayList<FileTranscriber.Segment>()
            var lastPercent = -1
            improver.report(id, 0f)
            try {
                graph.fileTranscriber.transcribe(audio, spec, options, object : FileTranscriber.Listener {
                    override fun onOpened(durationMs: Long?) {}
                    override fun onProgress(doneMs: Long) {
                        val total = item.durationMs.takeIf { it > 0 } ?: return
                        val fraction = (doneMs.toFloat() / total).coerceIn(0f, 1f)
                        improver.report(id, fraction)
                        val percent = (fraction * 100).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            runCatching { setForegroundAsync(foregroundInfo(item, spec.name, percent)) }
                        }
                    }
                    override fun onSegment(segment: FileTranscriber.Segment) {
                        synchronized(segments) { segments += segment }
                    }
                })
                val result = Transcript(spec.id, options.language, live = false, System.currentTimeMillis(), synchronized(segments) { segments.toList() })
                graph.library.update(id) {
                    // A newer request (another model) replaced this one meanwhile: keep it for later.
                    val keep = it.improve?.takeIf { r -> r != request }
                    val others = it.transcripts.withoutOlder(spec.id)
                    it.copy(transcripts = others + result, shown = others.size, improve = keep)
                }
                Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnsupportedAudioException) {
                graph.library.update(id) { it.copy(improve = null) }
                Result.failure()
            } catch (t: Throwable) {
                if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else {
                    graph.library.update(id) { it.copy(improve = null) }
                    Result.failure()
                }
            } finally {
                improver.report(id, null)
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val item = inputData.getString(KEY_ITEM)?.let { graph.library.get(it) }
        return foregroundInfo(item, item?.improve?.modelId?.let { ModelCatalog.byId(it)?.name } ?: "", null)
    }

    private fun foregroundInfo(item: LibraryItem?, model: String, percent: Int?): ForegroundInfo {
        val ctx = applicationContext
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.improve_notif_channel), NotificationManager.IMPORTANCE_MIN))
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_auto_awesome)
            .setContentTitle(ctx.getString(R.string.improve_notif_title, model))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent ?: 0, percent == null)
            .setContentIntent(RecorderService.openItem(ctx, item?.id))
            .build()
        return ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val KEY_ITEM = "item"
        private const val CHANNEL = "improve"
        private const val NOTIFICATION_ID = 43
        private const val MAX_ATTEMPTS = 3
    }
}
