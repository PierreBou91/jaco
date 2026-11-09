package dev.pbou.stt.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.pbou.stt.R
import dev.pbou.stt.SttApplication
import dev.pbou.stt.engine.Diarizer
import dev.pbou.stt.engine.SpeakerModels
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
 * Finds who spoke when in items with audio, in the background (WorkManager), and recognises saved voices.
 * Like [Improver]: the request lives in the item ([LibraryItem.findSpeakers]), one runs at a time, and it
 * steps aside while the microphone is in use (it would slow live transcription down).
 */
class SpeakerFinder(private val context: Context, private val library: Library) {
    private val work = WorkManager.getInstance(context)

    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    /** Item id to progress in [0, 1], for the searches running now. */
    val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()

    internal val lock = Mutex()

    /** Finds the speakers of [itemId]; [count]: how many there are, null to find out. */
    suspend fun request(itemId: String, count: Int? = null) {
        library.update(itemId) { it.copy(findSpeakers = SpeakersRequest(count)) } ?: return
        enqueue(itemId, delayMinutes = 0)
    }

    suspend fun cancel(itemId: String) {
        work.cancelUniqueWork(name(itemId))
        library.update(itemId) { it.copy(findSpeakers = null) }
        _progress.update { it - itemId }
    }

    /** Queues every pending request (at start, or once the speaker models are installed). */
    fun schedule() {
        library.items.value.forEach { if (it.findSpeakers != null) enqueue(it.id, delayMinutes = 0, replace = false) }
    }

    /** The microphone is needed: stop, and try again a little later. */
    fun yieldEngine() {
        val running = _progress.value.keys
        if (running.isEmpty()) return
        running.forEach { enqueue(it, delayMinutes = YIELD_MINUTES) }
        _progress.value = emptyMap()
    }

    internal fun report(itemId: String, fraction: Float?) =
        _progress.update { if (fraction == null) it - itemId else it + (itemId to fraction) }

    private fun enqueue(itemId: String, delayMinutes: Long, replace: Boolean = true) {
        val request = OneTimeWorkRequestBuilder<SpeakerWorker>()
            .setInputData(workDataOf(SpeakerWorker.KEY_ITEM to itemId))
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
            .addTag(TAG)
            .build()
        work.enqueueUniqueWork(name(itemId), if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    private fun name(itemId: String) = "speakers-$itemId"

    companion object {
        const val TAG = "speakers"
        private const val YIELD_MINUTES = 2L

        /** A person is recognised as a saved voice from this similarity (see [SpeakerModels]). */
        const val MATCH_SIMILARITY = SpeakerModels.MATCH_SIMILARITY
    }
}

class SpeakerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val graph get() = (applicationContext as SttApplication).graph

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ITEM) ?: return Result.failure()
        graph.library.load()
        graph.voices.load()
        val item = graph.library.get(id) ?: return Result.failure()
        val request = item.findSpeakers ?: return Result.success()
        val audio = graph.library.audioFile(item)
        if (audio == null) {
            graph.library.update(id) { it.copy(findSpeakers = null) }
            return Result.failure()
        }
        // The models are fetched on first use; the request waits for them (rescheduled once installed).
        if (!graph.store.isInstalled(SpeakerModels)) {
            graph.ensureSpeakerModels()
            return Result.success()
        }
        if (graph.engineBusy()) return Result.retry()
        val finder = graph.speakerFinder
        return finder.lock.withLock {
            if (graph.engineBusy()) return@withLock Result.retry()
            runCatching { setForeground(foregroundInfo(item, null)) }
            val settings = graph.awaitSettings()
            var lastPercent = -1
            finder.report(id, 0f)
            try {
                val found = graph.diarizer.run(audio, request.count, settings.effectiveThreads) { doneMs ->
                    val total = item.durationMs.takeIf { it > 0 } ?: return@run
                    val fraction = (doneMs.toFloat() / total).coerceIn(0f, 1f)
                    finder.report(id, fraction)
                    val percent = (fraction * 100).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        runCatching { setForegroundAsync(foregroundInfo(item, percent)) }
                    }
                }
                val result = toSpeakers(found, request.count, previous = graph.library.get(id)?.speakers)
                graph.library.update(id) {
                    // Asked again meanwhile (another count): keep that request for later.
                    it.copy(speakers = result, findSpeakers = it.findSpeakers?.takeIf { r -> r != request })
                }
                Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnsupportedAudioException) {
                graph.library.update(id) { it.copy(findSpeakers = null) }
                Result.failure()
            } catch (t: Throwable) {
                if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else {
                    graph.library.update(id) { it.copy(findSpeakers = null) }
                    Result.failure()
                }
            } finally {
                finder.report(id, null)
            }
        }
    }

    /**
     * The people found, recognised among the saved voices; the others keep a name given in [previous]
     * (an earlier search of the same item, before it was continued or searched with another count).
     */
    private fun toSpeakers(found: Diarizer.Result, count: Int?, previous: Speakers?): Speakers {
        val voices = found.speakers.map { it.voice }
        val voiceIds = graph.voices.match(voices, SpeakerFinder.MATCH_SIMILARITY)
        val named = previous?.people?.filter { it.name != null && it.voiceId == null }.orEmpty()
        val names = VoiceMatch.assign(voices, named.map { it.voice }, SpeakerFinder.MATCH_SIMILARITY)
        return Speakers(
            turns = found.turns.map { Speakers.Turn(it.startMs, it.endMs, it.speaker) },
            people = found.speakers.mapIndexed { i, s ->
                Speakers.Person(name = names[i]?.let { named[it].name }, voiceId = voiceIds[i], voice = s.voice, seconds = s.seconds, number = i + 1)
            },
            count = count,
            createdAt = System.currentTimeMillis(),
        )
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(inputData.getString(KEY_ITEM)?.let { graph.library.get(it) }, null)

    private fun foregroundInfo(item: LibraryItem?, percent: Int?): ForegroundInfo {
        val ctx = applicationContext
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.speakers_notif_channel), NotificationManager.IMPORTANCE_MIN))
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_groups)
            .setContentTitle(ctx.getString(R.string.speakers_notif_title))
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
        private const val CHANNEL = "speakers"
        private const val NOTIFICATION_ID = 44
        private const val MAX_ATTEMPTS = 3
    }
}
