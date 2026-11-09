package dev.pbou.stt.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.pbou.stt.MainActivity
import dev.pbou.stt.R
import dev.pbou.stt.SttApplication
import dev.pbou.stt.engine.CorruptDownloadException
import dev.pbou.stt.engine.Installable
import dev.pbou.stt.engine.SpeakerModels
import dev.pbou.stt.engine.ModelCatalog
import dev.pbou.stt.engine.ModelInstaller
import dev.pbou.stt.engine.ModelSpec
import dev.pbou.stt.engine.ModelStore
import dev.pbou.stt.engine.NotEnoughSpaceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What the UI shows for a model that is being installed. Installed and not-installed models have none. */
sealed interface DownloadState {
    /** Enqueued or starting. [forWifi]: Wi-Fi only and not on Wi-Fi. [retrying]: an attempt already failed. */
    data class Waiting(val forWifi: Boolean, val retrying: Boolean) : DownloadState
    data class Running(val phase: ModelInstaller.Phase, val done: Long, val total: Long) : DownloadState
    data class Failed(val reason: FailReason) : DownloadState
}

enum class FailReason { NETWORK, CORRUPT, NO_SPACE }

/** Model downloads, run by WorkManager so they survive leaving the app and resume after a failure. */
class Downloads(private val context: Context, private val store: ModelStore) {

    private val work = WorkManager.getInstance(context)

    private val _onWifi = MutableStateFlow(isUnmetered())
    /** True when the current network is not metered (Wi-Fi, usually). */
    val onWifi: StateFlow<Boolean> = _onWifi

    init {
        context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    _onWifi.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                }
                override fun onLost(network: Network) {
                    _onWifi.value = false
                }
            },
        )
    }

    /** Download state per model id, for models with an install in progress or failed. */
    val states: Flow<Map<String, DownloadState>> =
        combine(work.getWorkInfosByTagFlow(TAG), onWifi) { infos, _ -> infos.mapNotNull(::toState).toMap() }

    fun start(spec: Installable, wifiOnly: Boolean) {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(KEY_MODEL to spec.id, KEY_WIFI_ONLY to wifiOnly))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag(TAG)
            .addTag(modelTag(spec))
            .apply { if (wifiOnly) addTag(WIFI_TAG) }
            .build()
        work.enqueueUniqueWork(name(spec), ExistingWorkPolicy.REPLACE, request)
    }

    /** Stops the download and keeps what was downloaded, so [start] resumes it. */
    fun pause(spec: Installable) {
        work.cancelUniqueWork(name(spec))
    }

    /** Stops the download and deletes what was downloaded. */
    suspend fun discard(spec: Installable) {
        work.cancelUniqueWork(name(spec))
        work.getWorkInfosForUniqueWorkFlow(name(spec)).first { infos -> infos.all { it.state.isFinished } }
        work.pruneWork()
        store.discardStaging(spec)
    }

    private fun toState(info: WorkInfo): Pair<String, DownloadState>? {
        val id = info.tags.firstOrNull { it.startsWith(MODEL_TAG) }?.removePrefix(MODEL_TAG) ?: return null
        val state = when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                DownloadState.Waiting(forWifi = WIFI_TAG in info.tags && !onWifi.value, retrying = info.runAttemptCount > 0)
            WorkInfo.State.RUNNING -> {
                val p = info.progress
                val total = p.getLong(KEY_TOTAL, 0)
                if (total == 0L) DownloadState.Waiting(forWifi = false, retrying = false)
                else DownloadState.Running(ModelInstaller.Phase.valueOf(p.getString(KEY_PHASE) ?: "DOWNLOADING"), p.getLong(KEY_DONE, 0), total)
            }
            WorkInfo.State.FAILED ->
                DownloadState.Failed(FailReason.valueOf(info.outputData.getString(KEY_REASON) ?: FailReason.NETWORK.name))
            WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED -> return null
        }
        return id to state
    }

    private fun isUnmetered(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    companion object {
        const val TAG = "model-download"
        private const val MODEL_TAG = "model:"
        private const val WIFI_TAG = "wifi-only"
        const val KEY_MODEL = "model"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_PHASE = "phase"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_REASON = "reason"

        fun name(spec: Installable) = "download-${spec.id}"
        fun modelTag(spec: Installable) = MODEL_TAG + spec.id
    }
}

class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val graph get() = (applicationContext as SttApplication).graph
    private val spec: Installable? get() = inputData.getString(Downloads.KEY_MODEL).let { id ->
        ModelCatalog.byId(id) ?: SpeakerModels.takeIf { it.id == id }
    }

    override suspend fun doWork(): Result {
        val spec = spec ?: return Result.failure()
        ensureChannel(applicationContext)
        runCatching { setForeground(foregroundInfo(spec, null)) } // not allowed in some background cases: carry on
        var last = 0L
        return try {
            graph.installer.install(spec, graph.store) { phase, done, total ->
                val now = SystemClock.elapsedRealtime()
                if (now - last >= PROGRESS_EVERY_MS || done == total) {
                    last = now
                    setProgressAsync(workDataOf(Downloads.KEY_PHASE to phase.name, Downloads.KEY_DONE to done, Downloads.KEY_TOTAL to total))
                    runCatching { setForegroundAsync(foregroundInfo(spec, Triple(phase, done, total))) }
                }
            }
            when (spec) {
                is ModelSpec -> {
                    graph.onModelInstalled(spec)
                    notifyDone(spec)
                }
                else -> graph.onSpeakerModelsInstalled() // a helper download: no "ready" notification
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: NotEnoughSpaceException) {
            fail(FailReason.NO_SPACE)
        } catch (e: CorruptDownloadException) {
            // The bad file was deleted: one fresh attempt, then give up.
            if (runAttemptCount < 1) Result.retry() else fail(FailReason.CORRUPT)
        } catch (e: IOException) {
            if (runAttemptCount < MAX_RETRIES) Result.retry() else fail(FailReason.NETWORK)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(spec ?: ModelCatalog.PARAKEET, null)

    private fun fail(reason: FailReason) = Result.failure(workDataOf(Downloads.KEY_REASON to reason.name))

    private fun foregroundInfo(spec: Installable, progress: Triple<ModelInstaller.Phase, Long, Long>?): ForegroundInfo {
        val ctx = applicationContext
        val text = progress?.let { (phase, done, total) ->
            when (phase) {
                ModelInstaller.Phase.DOWNLOADING -> ctx.getString(R.string.notif_downloading, Format.bytes(ctx, done), Format.bytes(ctx, total))
                ModelInstaller.Phase.VERIFYING -> ctx.getString(R.string.download_verifying)
                ModelInstaller.Phase.UNPACKING -> ctx.getString(R.string.download_unpacking_percent, percent(done, total))
            }
        } ?: ctx.getString(R.string.download_starting)
        val notification = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(ctx.getString(R.string.notif_title, if (spec is ModelSpec) spec.name else ctx.getString(R.string.speakers_models_name)))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp(ctx))
            .setProgress(100, progress?.let { percent(it.second, it.third) } ?: 0, progress == null)
            .addAction(0, ctx.getString(R.string.action_pause), WorkManager.getInstance(ctx).createCancelPendingIntent(id))
            .build()
        return ForegroundInfo(notificationId(spec), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun notifyDone(spec: ModelSpec) {
        val ctx = applicationContext
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_check_circle_fill)
            .setContentTitle(ctx.getString(R.string.notif_ready_title, spec.name))
            .setContentText(ctx.getString(R.string.notif_ready_text))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(notificationId(spec) + 1, n)
    }

    private companion object {
        const val CHANNEL = "downloads"
        const val PROGRESS_EVERY_MS = 400L
        const val MAX_RETRIES = 5

        fun percent(done: Long, total: Long) = if (total <= 0) 0 else (done * 100 / total).toInt()
        fun notificationId(spec: Installable) = 1000 + (spec.id.hashCode() and 0xffff) * 2

        fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, ctx.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
    }
}
