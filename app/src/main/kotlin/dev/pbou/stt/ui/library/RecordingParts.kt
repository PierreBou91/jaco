package dev.pbou.stt.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.pbou.stt.R
import dev.pbou.stt.data.LibraryText
import dev.pbou.stt.engine.RecordingSession
import dev.pbou.stt.ui.components.MicButton

/**
 * The controls of a recording in progress, pinned at the bottom of the screen so they stay at hand
 * however long the transcript grows: time, what it is doing, pause or resume, stop.
 */
@Composable
fun RecordingBar(s: RecordingSession.State, level: () -> Float, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit) {
    val finishing = s.phase == RecordingSession.Phase.FINISHING
    val paused = s.phase == RecordingSession.Phase.PAUSED
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 24.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(LibraryText.clock(s.recordedMs), style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"))
                Text(
                    when {
                        finishing -> stringResource(R.string.rec_finishing)
                        paused -> stringResource(R.string.rec_paused)
                        s.waiting > 1 -> stringResource(R.string.rec_waiting, s.waiting)
                        else -> stringResource(R.string.rec_recording)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalIconButton(onClick = if (paused) onResume else onPause, enabled = !finishing, modifier = Modifier.size(56.dp)) {
                Icon(
                    painterResource(if (paused) R.drawable.ic_fiber_manual_record_fill else R.drawable.ic_pause_fill),
                    contentDescription = stringResource(if (paused) R.string.action_resume else R.string.action_pause),
                )
            }
            MicButton(
                listening = !paused && !finishing,
                finishing = finishing,
                level = level,
                onClick = onStop,
                stopLabel = stringResource(R.string.rec_stop),
            )
        }
    }
}

/** The sentence being spoken, before it is fixed at the pause. */
@Composable
fun PartialRow(text: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text("", modifier = Modifier.width(56.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Tells the recorder while this screen shows a recording in progress and is visible, so the sentence
 * being spoken is transcribed only for someone looking (not with the screen off or in another app).
 */
@Composable
fun WatchRecording(active: Boolean, onWatching: (Boolean) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val callback by rememberUpdatedState(onWatching)
    DisposableEffect(active, lifecycle) {
        var on = false
        fun set(v: Boolean) {
            if (v != on) {
                on = v
                callback(v)
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> set(active)
                Lifecycle.Event.ON_STOP -> set(false)
                else -> {}
            }
        }
        lifecycle.addObserver(observer) // replays ON_START if already started
        onDispose {
            lifecycle.removeObserver(observer)
            set(false)
        }
    }
}
