@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.pbou.stt.ui.library

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pbou.stt.R
import dev.pbou.stt.data.LibraryItem
import dev.pbou.stt.data.LibraryText
import dev.pbou.stt.data.Transcript
import dev.pbou.stt.engine.FileTranscriber
import dev.pbou.stt.engine.ModelSpec
import dev.pbou.stt.engine.RecordingSession
import dev.pbou.stt.ui.components.MicButton
import kotlinx.coroutines.launch

@Composable
fun ItemScreen(vm: ItemViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val copied = stringResource(R.string.copied)
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var again by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(ui.deleted) { if (ui.deleted) onBack() }
    val item = ui.item
    // The audio appears when a recording ends: get the player ready then.
    LaunchedEffect(item?.status, item?.audio) { vm.preparePlayer() }
    DisposableEffect(Unit) { onDispose { vm.pausePlayer() } }

    val recording = ui.recording
    val segments = item?.transcript?.segments.orEmpty()
    val list = rememberLazyListState()
    // While recording, follow the new sentences.
    LaunchedEffect(segments.size) {
        if (recording != null && segments.isNotEmpty()) list.animateScrollToItem(list.layoutInfo.totalItemsCount - 1)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(item?.let { itemTitle(context, it) }.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (item != null && recording == null && item.status == LibraryItem.Status.DONE) {
                        ShareMenu(
                            hasText = segments.isNotEmpty(),
                            hasAudio = player.ready,
                            onText = { vm.shareText(context, timestamps = false) },
                            onTimestamps = { vm.shareText(context, timestamps = true) },
                            onAudio = { vm.shareAudio(context) },
                        )
                        MoreMenu(
                            hasText = segments.isNotEmpty(),
                            canTranscribeAgain = player.ready && ui.installed.isNotEmpty(),
                            onCopy = {
                                vm.copyText(context)
                                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) scope.launch { snackbar.showSnackbar(copied) }
                            },
                            onRename = { renaming = true },
                            onAgain = { again = true },
                            onDelete = { deleting = true },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            if (player.ready && recording == null) {
                PlayerBar(player, onToggle = vm::togglePlay, onSeek = vm::seek, onSkip = vm::skip)
            }
        },
    ) { padding ->
        if (item == null) return@Scaffold
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "header") {
                if (recording != null) {
                    RecordingHeader(recording, level = { vm.level.value }, onPause = vm::pause, onResume = vm::resume, onStop = vm::stop)
                } else {
                    Header(
                        item = item,
                        improving = ui.improving,
                        onShow = vm::show,
                        onImproveNow = vm::improveNow,
                        onCancelImprove = vm::cancelImprove,
                    )
                }
                ui.recordingProblem?.let { problem ->
                    Problem(problem, onDismiss = vm::dismissProblem)
                }
                HorizontalDivider(Modifier.padding(top = 8.dp))
            }
            if (segments.isEmpty()) item(key = "empty") {
                Text(
                    stringResource(
                        when {
                            recording != null -> R.string.rec_hint
                            item.status != LibraryItem.Status.DONE -> R.string.lib_transcribing
                            else -> R.string.file_no_speech
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
            }
            itemsIndexed(segments, key = { i, s -> "${item.shown}-$i-${s.startMs}" }) { i, s ->
                val next = segments.getOrNull(i + 1)?.startMs ?: Long.MAX_VALUE
                val active = player.ready && (player.playing || player.positionMs > 0) && player.positionMs >= s.startMs && player.positionMs < next
                SegmentRow(s, active = active, onClick = if (player.ready) ({ vm.playFrom(s.startMs) }) else null)
            }
        }
    }

    if (renaming && item != null) {
        RenameDialog(itemTitle(context, item), onDismiss = { renaming = false }) { vm.rename(it); renaming = false }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.lib_delete_title)) },
            text = { Text(stringResource(R.string.lib_delete_body)) },
            confirmButton = { TextButton(onClick = { deleting = false; vm.delete(context) }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (again && item != null) {
        AgainDialog(ui.installed, current = item.transcript?.modelId, onDismiss = { again = false }) { spec ->
            again = false
            vm.transcribeAgain(spec)
        }
    }
}

@Composable
private fun RecordingHeader(s: RecordingSession.State, level: () -> Float, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit) {
    val finishing = s.phase == RecordingSession.Phase.FINISHING
    val paused = s.phase == RecordingSession.Phase.PAUSED
    Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(LibraryText.clock(s.recordedMs), style = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = "tnum"))
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                finishing -> stringResource(R.string.rec_finishing)
                paused -> stringResource(R.string.rec_paused)
                s.waiting > 1 -> stringResource(R.string.rec_waiting, s.waiting)
                else -> stringResource(R.string.rec_recording)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FilledTonalIconButton(
                onClick = if (paused) onResume else onPause,
                enabled = !finishing,
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    painterResource(if (paused) R.drawable.ic_fiber_manual_record_fill else R.drawable.ic_pause_fill),
                    contentDescription = stringResource(if (paused) R.string.action_resume else R.string.action_pause),
                )
            }
            MicButton(listening = !paused && !finishing, finishing = finishing, level = level, onClick = onStop, stopLabel = stringResource(R.string.rec_stop))
            Spacer(Modifier.size(56.dp)) // balances the pause button, so the stop button stays centered
        }
    }
}

@Composable
private fun Header(item: LibraryItem, improving: Float?, onShow: (Int) -> Unit, onImproveNow: () -> Unit, onCancelImprove: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            listOfNotNull(itemDate(context, item), item.durationMs.takeIf { it > 0 }?.let { LibraryText.clock(it) }).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (item.transcripts.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item.transcripts.forEachIndexed { i, t ->
                    FilterChip(selected = i == item.shown, onClick = { onShow(i) }, label = { Text(transcriptLabel(t)) })
                }
            }
        } else {
            item.transcript?.let {
                Text(transcriptLabel(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val status = status(item, improving)
        if (status != null && item.status == LibraryItem.Status.DONE) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_auto_awesome), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(status, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (improving == null && item.improve?.whileCharging == true) {
                    TextButton(onClick = onImproveNow) { Text(stringResource(R.string.improve_now)) }
                }
                TextButton(onClick = onCancelImprove) { Text(stringResource(R.string.action_cancel)) }
            }
            if (improving != null) LinearProgressIndicator(progress = { improving }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun transcriptLabel(t: Transcript): String =
    if (t.live) stringResource(R.string.transcript_live, modelName(t.modelId)) else modelName(t.modelId)

@Composable
private fun Problem(problem: RecordingSession.Problem, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(
                    when (problem) {
                        RecordingSession.Problem.Microphone -> R.string.mic_unavailable
                        is RecordingSession.Problem.Audio -> R.string.rec_audio_failed
                        is RecordingSession.Problem.Engine -> R.string.status_engine_failed
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.action_close)) }
        }
    }
}

@Composable
private fun SegmentRow(s: FileTranscriber.Segment, active: Boolean, onClick: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface)
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        Text(
            LibraryText.clock(s.startMs),
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp).padding(top = 3.dp),
        )
        Text(s.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PlayerBar(p: PlayerUi, onToggle: () -> Unit, onSeek: (Long) -> Unit, onSkip: (Long) -> Unit) {
    // Local while dragging, so the slider does not jump back with the position ticks.
    var dragging by remember { mutableStateOf<Float?>(null) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Slider(
                value = dragging ?: p.positionMs.toFloat(),
                onValueChange = { dragging = it },
                onValueChangeFinished = { dragging?.let { onSeek(it.toLong()) }; dragging = null },
                valueRange = 0f..p.durationMs.coerceAtLeast(1).toFloat(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    LibraryText.clock((dragging?.toLong() ?: p.positionMs)) + " / " + LibraryText.clock(p.durationMs),
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onSkip(-10_000) }) {
                    Icon(painterResource(R.drawable.ic_replay_10), contentDescription = stringResource(R.string.player_back))
                }
                FilledTonalIconButton(onClick = onToggle, modifier = Modifier.size(48.dp), colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
                )) {
                    Icon(
                        painterResource(if (p.playing) R.drawable.ic_pause_fill else R.drawable.ic_play_arrow_fill),
                        contentDescription = stringResource(if (p.playing) R.string.action_pause else R.string.player_play),
                    )
                }
                IconButton(onClick = { onSkip(10_000) }) {
                    Icon(painterResource(R.drawable.ic_forward_10), contentDescription = stringResource(R.string.player_forward))
                }
            }
        }
    }
}

@Composable
private fun ShareMenu(hasText: Boolean, hasAudio: Boolean, onText: () -> Unit, onTimestamps: () -> Unit, onAudio: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = hasText || hasAudio) {
            Icon(painterResource(R.drawable.ic_share), contentDescription = stringResource(R.string.action_share))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (hasText) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.export_text)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_notes), contentDescription = null) },
                    onClick = { open = false; onText() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.export_timestamps)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_schedule), contentDescription = null) },
                    onClick = { open = false; onTimestamps() },
                )
            }
            if (hasAudio) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.export_audio)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_audio_file), contentDescription = null) },
                    onClick = { open = false; onAudio() },
                )
            }
        }
    }
}

@Composable
private fun MoreMenu(hasText: Boolean, canTranscribeAgain: Boolean, onCopy: () -> Unit, onRename: () -> Unit, onAgain: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (hasText) DropdownMenuItem(
                text = { Text(stringResource(R.string.action_copy)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_content_copy), contentDescription = null) },
                onClick = { open = false; onCopy() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.lib_rename)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_edit), contentDescription = null) },
                onClick = { open = false; onRename() },
            )
            if (canTranscribeAgain) DropdownMenuItem(
                text = { Text(stringResource(R.string.lib_again)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_auto_awesome), contentDescription = null) },
                onClick = { open = false; onAgain() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_delete)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
                onClick = { open = false; onDelete() },
            )
        }
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lib_rename)) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Choose a model to transcribe the audio again; the current text stays available. */
@Composable
private fun AgainDialog(installed: List<ModelSpec>, current: String?, onDismiss: () -> Unit, onStart: (ModelSpec) -> Unit) {
    var chosen by remember { mutableStateOf(installed.firstOrNull { it.id != current } ?: installed.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lib_again)) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.lib_again_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                installed.forEach { spec ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = spec == chosen, role = Role.RadioButton, onClick = { chosen = spec }).padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = spec == chosen, onClick = null)
                        Spacer(Modifier.width(16.dp))
                        Text(spec.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onStart(chosen) }) { Text(stringResource(R.string.lib_again_start)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
