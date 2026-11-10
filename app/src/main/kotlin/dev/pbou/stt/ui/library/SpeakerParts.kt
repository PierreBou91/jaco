@file:OptIn(ExperimentalLayoutApi::class)

package dev.pbou.stt.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pbou.stt.R
import dev.pbou.stt.data.DownloadState
import dev.pbou.stt.data.Format
import dev.pbou.stt.data.Speakers
import dev.pbou.stt.engine.SpeakerModels
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role

/** One color per speaker, readable on the dark theme and apart from the orange accent. */
private val SPEAKER_COLORS = listOf(
    Color(0xFF64B5F6), Color(0xFF81C784), Color(0xFFF48FB1), Color(0xFFFFD54F),
    Color(0xFFB39DDB), Color(0xFF4DD0E1), Color(0xFFDCE775), Color(0xFFBCAAA4),
)

/** The color of the speaker numbered [number] (from 1). */
fun speakerColor(number: Int): Color = SPEAKER_COLORS[(number - 1).mod(SPEAKER_COLORS.size)]

@Composable
private fun Dot(number: Int) {
    Box(Modifier.size(10.dp).background(speakerColor(number), CircleShape))
}

/** The speaker's name above the first sentence of each of their turns. Tap: name them. */
@Composable
fun SpeakerLabel(name: String, number: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 24.dp + 56.dp, end = 24.dp, top = 14.dp, bottom = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(number)
        Spacer(Modifier.width(8.dp))
        Text(name, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = speakerColor(number))
    }
}

/** Which sentences start a new turn (a label goes above them): the speaker differs from the last one named. */
fun turnStarts(who: List<Int?>): BooleanArray {
    val out = BooleanArray(who.size)
    var last: Int? = null
    who.forEachIndexed { i, w ->
        if (w != null && w != last) {
            out[i] = true
            last = w
        }
    }
    return out
}

/** Everyone who speaks, with their share of the talk. Tap one to name them. */
@Composable
fun SpeakerSummary(speakers: Speakers, names: List<String>, who: List<Int?>, onName: (Int) -> Unit) {
    val total = speakers.people.sumOf { it.seconds.toDouble() }.coerceAtLeast(1.0)
    val present = who.filterNotNull().toSet()
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        speakers.people.forEachIndexed { i, p ->
            if (i !in present) return@forEachIndexed
            AssistChip(
                onClick = { onName(i) },
                label = { Text(stringResource(R.string.speakers_share, names[i], (p.seconds / total * 100).toInt())) },
                leadingIcon = { Dot(p.number) },
            )
        }
    }
}

/** "Finding speakers" with its progress, or why it waits (the speaker models are on their way). */
@Composable
fun FindingSpeakers(progress: Float?, models: DownloadState?, splitOnly: Boolean, onCancel: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_groups), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    progress != null && splitOnly -> stringResource(R.string.speakers_splitting, (progress * 100).toInt())
                    progress != null -> stringResource(R.string.speakers_finding, (progress * 100).toInt())
                    models is DownloadState.Waiting && models.forWifi -> stringResource(R.string.speakers_wait_wifi, Format.bytes(context, SpeakerModels.downloadBytes))
                    models is DownloadState.Running -> stringResource(R.string.speakers_downloading, (models.done * 100 / models.total.coerceAtLeast(1)).toInt())
                    models is DownloadState.Failed -> stringResource(R.string.speakers_download_failed)
                    else -> stringResource(R.string.speakers_soon)
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        }
        if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    }
}

/** Find the speakers: let Jaco count them, or say how many there are (more reliable when known). */
@Composable
fun FindSpeakersDialog(current: Int?, onDismiss: () -> Unit, onStart: (Int?) -> Unit) {
    var count by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.speakers_find)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.speakers_find_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                Text(stringResource(R.string.speakers_count), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = count == null, onClick = { count = null }, label = { Text(stringResource(R.string.speakers_count_auto)) })
                    (2..6).forEach { n -> FilterChip(selected = count == n, onClick = { count = n }, label = { Text("$n") }) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onStart(count) }) { Text(stringResource(R.string.speakers_start)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Name a speaker. A name already given to another speaker here merges the two (Jaco found the same
 * person twice). "Remember this voice" saves it to recognise them in later recordings.
 */
@Composable
fun NameSpeakerDialog(
    index: Int,
    number: Int,
    current: String?,
    fallback: String,
    suggestions: List<String>,
    others: List<String>,
    onDismiss: () -> Unit,
    onSave: (name: String, remember: Boolean) -> Unit,
) {
    var text by rememberSaveable(index) { mutableStateOf(current.orEmpty()) }
    var remember by rememberSaveable(index) { mutableStateOf(true) }
    val merging = text.isNotBlank() && others.any { it.equals(text.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.speakers_name_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text(fallback) },
                    leadingIcon = { Dot(number) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (suggestions.isNotEmpty()) {
                    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        suggestions.forEach { name -> SuggestionChip(onClick = { text = name }, label = { Text(name) }) }
                    }
                }
                if (merging) {
                    Text(
                        stringResource(R.string.speakers_merge_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp).toggleable(value = remember, role = Role.Checkbox, onValueChange = { remember = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = remember, onCheckedChange = null)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(stringResource(R.string.speakers_remember), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.speakers_remember_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text, remember && text.isNotBlank()) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
