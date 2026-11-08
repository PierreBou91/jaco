package dev.pbou.stt.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.pbou.stt.R
import dev.pbou.stt.data.DownloadState
import dev.pbou.stt.data.FailReason
import dev.pbou.stt.engine.ModelInstaller
import dev.pbou.stt.ui.bytes
import kotlinx.coroutines.delay

/** Icon in a soft round tile, used at the top of empty states. */
@Composable
fun IconTile(icon: Int, tint: Color = MaterialTheme.colorScheme.primary, size: Dp = 88.dp) {
    Box(
        Modifier.size(size).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.45f))
    }
}

/** Small rounded label ("Recommended", "Default"). */
@Composable
fun Badge(text: String, container: Color, content: Color, icon: Int? = null) {
    Surface(color = container, contentColor = content, shape = CircleShape) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp),
    )
}

/** Progress of a model download, with its controls. Used on the first-run card and on the model list. */
@Composable
fun DownloadStatus(
    state: DownloadState,
    onPause: () -> Unit,
    onUseMobileData: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabular = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum")
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state) {
            is DownloadState.Running -> {
                LinearProgressIndicator(
                    progress = { if (state.total > 0) state.done.toFloat() / state.total else 0f },
                    modifier = Modifier.fillMaxWidth(),
                    strokeCap = StrokeCap.Round,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val text = when (state.phase) {
                        ModelInstaller.Phase.DOWNLOADING -> stringResource(R.string.download_progress, bytes(state.done), bytes(state.total))
                        ModelInstaller.Phase.VERIFYING -> stringResource(R.string.download_verifying)
                        ModelInstaller.Phase.UNPACKING -> stringResource(
                            R.string.download_unpacking_percent,
                            if (state.total > 0) (state.done * 100 / state.total).toInt() else 0,
                        )
                    }
                    Text(text, style = tabular, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    if (state.phase == ModelInstaller.Phase.DOWNLOADING) {
                        IconButton(onClick = onPause) {
                            Icon(painterResource(R.drawable.ic_pause), contentDescription = stringResource(R.string.action_pause))
                        }
                    }
                }
            }
            is DownloadState.Waiting -> {
                LinearProgressIndicator(Modifier.fillMaxWidth(), strokeCap = StrokeCap.Round)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val text = when {
                        state.forWifi -> stringResource(R.string.download_waiting_wifi)
                        state.retrying -> stringResource(R.string.download_retrying)
                        else -> stringResource(R.string.download_starting)
                    }
                    Text(text, style = tabular, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    if (state.forWifi) TextButton(onClick = onUseMobileData) { Text(stringResource(R.string.action_use_mobile_data)) }
                    IconButton(onClick = onPause) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.action_cancel))
                    }
                }
            }
            is DownloadState.Failed -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_error), contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    val text = when (state.reason) {
                        FailReason.NETWORK -> stringResource(R.string.download_failed_network)
                        FailReason.CORRUPT -> stringResource(R.string.download_failed_corrupt)
                        FailReason.NO_SPACE -> stringResource(R.string.download_failed_space)
                    }
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                }
            }
        }
    }
}

/** The big round microphone button; a soft disc behind it grows with the voice while listening. */
@Composable
fun MicButton(listening: Boolean, finishing: Boolean, level: () -> Float, onClick: () -> Unit, stopLabel: String? = null) {
    val primary = MaterialTheme.colorScheme.primary
    val halo by animateFloatAsState(if (listening) 1f else 0f, label = "halo")
    Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
        // A soft disc that grows with the voice: shows the microphone hears you.
        Box(
            Modifier
                .size(84.dp)
                .graphicsLayer {
                    val s = 1f + 0.32f * level() * halo
                    scaleX = s; scaleY = s
                    alpha = halo
                }
                .background(primary.copy(alpha = 0.22f), CircleShape),
        )
        // Most stops finish in a fraction of a second: a spinner flashing that briefly reads like a
        // "hold to confirm" ring. Show it only when the last sentence really takes a while.
        var slow by remember { mutableStateOf(false) }
        LaunchedEffect(finishing) {
            slow = false
            if (finishing) {
                delay(SLOW_FINISH_MS)
                slow = true
            }
        }
        if (finishing && slow) CircularProgressIndicator(Modifier.size(96.dp), strokeWidth = 3.dp)
        Surface(
            onClick = onClick,
            enabled = !finishing,
            shape = CircleShape,
            color = primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shadowElevation = 2.dp,
            modifier = Modifier.size(80.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(if (listening) R.drawable.ic_stop_fill else R.drawable.ic_mic_fill),
                    contentDescription = if (listening && stopLabel != null) stopLabel else stringResource(if (listening) R.string.action_stop else R.string.action_dictate),
                    modifier = Modifier.size(34.dp),
                )
            }
        }
    }
}

private const val SLOW_FINISH_MS = 600L
