package dev.pbou.stt.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import dev.pbou.stt.MainActivity
import dev.pbou.stt.R
import dev.pbou.stt.SttApplication
import dev.pbou.stt.engine.RecordingSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps the app alive while recording, with the notification Android requires to use the microphone in
 * the background: elapsed time, Pause/Resume and Stop. Stops itself when the recording is saved.
 */
class RecorderService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val recorder get() = (application as SttApplication).graph.recorder
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> recorder.pause()
            ACTION_RESUME -> recorder.resume()
            ACTION_STOP -> recorder.stop()
        }
        if (!started) {
            started = true
            ensureChannel(this)
            // Must be called within seconds of the start, even if the recording already ended.
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(recorder.session.state.value, recorder.itemId.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            scope.launch {
                combine(recorder.session.state, recorder.itemId) { s, id -> s.phase to id } // the chronometer counts by itself
                    .distinctUntilChanged()
                    .collect { (_, id) ->
                        if (id == null) {
                            ServiceCompat.stopForeground(this@RecorderService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        } else {
                            runCatching { NotificationManagerCompat.from(this@RecorderService).notify(NOTIFICATION_ID, notification(recorder.session.state.value, id)) }
                        }
                    }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(s: RecordingSession.State, itemId: String?): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_fiber_manual_record_fill)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // no text from the recording in it
            .setContentIntent(openItem(this, itemId))
        when (s.phase) {
            RecordingSession.Phase.RECORDING, RecordingSession.Phase.IDLE -> b
                .setContentTitle(getString(R.string.rec_notif_recording))
                .setUsesChronometer(true)
                .setWhen(System.currentTimeMillis() - s.recordedMs)
                .addAction(0, getString(R.string.action_pause), action(ACTION_PAUSE))
                .addAction(0, getString(R.string.action_stop), action(ACTION_STOP))
            RecordingSession.Phase.PAUSED -> b
                .setContentTitle(getString(R.string.rec_notif_paused))
                .setContentText(LibraryText.clock(s.recordedMs))
                .setShowWhen(false)
                .addAction(0, getString(R.string.action_resume), action(ACTION_RESUME))
                .addAction(0, getString(R.string.action_stop), action(ACTION_STOP))
            RecordingSession.Phase.FINISHING -> b
                .setContentTitle(getString(R.string.rec_notif_finishing))
                .setShowWhen(false)
                .setProgress(0, 0, true)
        }
        return b.build()
    }

    private fun action(action: String): PendingIntent = PendingIntent.getService(
        this, action.hashCode(), Intent(this, RecorderService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val CHANNEL = "recording"
        const val NOTIFICATION_ID = 42
        private const val ACTION_PAUSE = "dev.pbou.stt.PAUSE"
        private const val ACTION_RESUME = "dev.pbou.stt.RESUME"
        private const val ACTION_STOP = "dev.pbou.stt.STOP"

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.rec_notif_channel), NotificationManager.IMPORTANCE_LOW))
            }
        }

        /** Opens the app on [itemId] (the library item), or on the app if null. */
        fun openItem(ctx: Context, itemId: String?): PendingIntent = PendingIntent.getActivity(
            ctx, itemId.hashCode(),
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_ITEM, itemId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
