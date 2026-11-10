@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.pbou.stt.ui.library

import android.os.Build
import dev.pbou.stt.ui.languageLabel
import dev.pbou.stt.ui.choosableLanguages
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.content.pm.PackageManager
import android.Manifest
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
import dev.pbou.stt.data.SpeakerText
import dev.pbou.stt.data.Transcript
import dev.pbou.stt.engine.FileTranscriber
import dev.pbou.stt.engine.ModelSpec
import dev.pbou.stt.engine.RecordingSession
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
    var deletingAudio by rememberSaveable { mutableStateOf(false) }
    var findingDialog by rememberSaveable { mutableStateOf(false) }
    var naming by rememberSaveable { mutableStateOf<Int?>(null) }
    val cannotContinue = stringResource(R.string.rec_cannot_continue)
    val micDenied = stringResource(R.string.mic_permission_denied)
    fun continueNow() = scope.launch { if (!vm.continueRecording()) snackbar.showSnackbar(cannotContinue) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) continueNow() else scope.launch { snackbar.showSnackbar(micDenied) }
    }

    LaunchedEffect(ui.deleted) { if (ui.deleted) onBack() }
    val item = ui.item
    // The audio appears when a recording ends: get the player ready then.
    LaunchedEffect(item?.status, item?.audio) { vm.preparePlayer() }
    DisposableEffect(Unit) { onDispose { vm.pausePlayer() } }

    val recording = ui.recording
    WatchRecording(active = recording != null, onWatching = vm::watching)
    val segments = item?.transcript?.segments.orEmpty()
    val partial = recording?.partial.orEmpty()
    // Who said each sentence, once the speakers are found.
    val speakers = item?.speakers
    val who = remember(speakers, segments) { speakers?.let { SpeakerText.speakerOf(segments, it) } ?: segments.map { null } }
    val names = remember(speakers, ui.voices) {
        speakers?.let { sp -> SpeakerText.names(sp, { id -> ui.voices.firstOrNull { it.id == id }?.name }, { context.getString(R.string.speaker_n, it) }) }.orEmpty()
    }
    val showSpeakers = SpeakerText.worthShowing(who)
    val starts = remember(who) { turnStarts(who) }
    val list = rememberLazyListState()
    // While recording, follow the new sentences.
    LaunchedEffect(segments.size, partial.isNotEmpty()) {
        if (recording != null && list.layoutInfo.totalItemsCount > 0) list.animateScrollToItem(list.layoutInfo.totalItemsCount - 1)
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
                            canContinue = item.kind == LibraryItem.Kind.RECORDING && ui.installed.isNotEmpty(),
                            hasAudio = item.audio != null,
                            canFindSpeakers = player.ready && item.findSpeakers == null,
                            hasSpeakers = speakers != null,
                            onFindSpeakers = { findingDialog = true },
                            onCopy = { timestamps ->
                                vm.copyText(context, timestamps)
                                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) scope.launch { snackbar.showSnackbar(copied) }
                            },
                            onRename = { renaming = true },
                            onAgain = { again = true },
                            onContinue = {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) continueNow()
                                else askMic.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            onDeleteAudio = { deletingAudio = true },
                            onDelete = { deleting = true },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            when {
                recording != null -> RecordingBar(recording, level = { vm.level.value }, onPause = vm::pause, onResume = vm::resume, onStop = vm::stop)
                player.ready -> PlayerBar(player, onToggle = vm::togglePlay, onSeek = vm::seek, onSkip = vm::skip)
            }
        },
    ) { padding ->
        if (item == null) return@Scaffold
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "header") {
                Header(
                    item = item,
                    improving = ui.improving,
                    onShow = vm::show,
                    onImproveNow = vm::improveNow,
                    onCancelImprove = vm::cancelImprove,
                )
                if (item.findSpeakers != null && recording == null) {
                    FindingSpeakers(ui.findingSpeakers, ui.speakerModels, onCancel = vm::cancelSpeakers)
                }
                if (showSpeakers && speakers != null) {
                    SpeakerSummary(speakers, names, who, onName = { naming = it })
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
                            item.kind == LibraryItem.Kind.RECORDING -> R.string.rec_no_speech
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
                val speaker = who.getOrNull(i)
                if (showSpeakers && speaker != null && starts.getOrElse(i) { false }) {
                    SpeakerLabel(names[speaker], speakers!!.people[speaker].number, onClick = { naming = speaker })
                }
                SegmentRow(s, active = active, onClick = if (player.ready) ({ vm.playFrom(s.startMs) }) else null)
            }
            if (partial.isNotEmpty()) item(key = "partial") { PartialRow(partial) }
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
    if (deletingAudio) {
        AlertDialog(
            onDismissRequest = { deletingAudio = false },
            title = { Text(stringResource(R.string.lib_delete_audio_title)) },
            text = { Text(stringResource(R.string.lib_delete_audio_body)) },
            confirmButton = { TextButton(onClick = { deletingAudio = false; vm.deleteAudio(context) }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { deletingAudio = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (findingDialog) {
        FindSpeakersDialog(current = speakers?.count, onDismiss = { findingDialog = false }) { count ->
            findingDialog = false
            vm.findSpeakers(count)
        }
    }
    naming?.let { index ->
        if (speakers != null && index in speakers.people.indices) {
            NameSpeakerDialog(
                index = index,
                number = speakers.people[index].number,
                current = speakers.people[index].let { p -> p.voiceId?.let { id -> ui.voices.firstOrNull { it.id == id }?.name } ?: p.name },
                fallback = names.getOrElse(index) { "" },
                suggestions = (ui.voices.map { it.name } + names.filterIndexed { i, _ -> i != index && speakers.people[i].let { it.name != null || it.voiceId != null } })
                    .distinctBy { it.lowercase() }.sortedBy { it.lowercase() },
                others = names.filterIndexed { i, _ -> i != index },
                onDismiss = { naming = null },
            ) { name, remember ->
                naming = null
                vm.nameSpeaker(index, name, remember)
            }
        } else naming = null
    }
    if (again && item != null) {
        AgainDialog(ui.installed, current = item.transcript?.modelId, defaultLanguage = vm::defaultLanguage, onDismiss = { again = false }) { spec, language ->
            again = false
            vm.transcribeAgain(spec, language)
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
private fun MoreMenu(
    hasText: Boolean,
    canTranscribeAgain: Boolean,
    canContinue: Boolean,
    hasAudio: Boolean,
    canFindSpeakers: Boolean,
    hasSpeakers: Boolean,
    onFindSpeakers: () -> Unit,
    onCopy: (timestamps: Boolean) -> Unit,
    onRename: () -> Unit,
    onAgain: () -> Unit,
    onContinue: () -> Unit,
    onDeleteAudio: () -> Unit,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (hasText) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_copy)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_content_copy), contentDescription = null) },
                    onClick = { open = false; onCopy(true) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.lib_copy_plain)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_notes), contentDescription = null) },
                    onClick = { open = false; onCopy(false) },
                )
            }
            if (canContinue) DropdownMenuItem(
                text = { Text(stringResource(R.string.rec_continue)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_fiber_manual_record), contentDescription = null) },
                onClick = { open = false; onContinue() },
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
            if (canFindSpeakers) DropdownMenuItem(
                text = { Text(stringResource(if (hasSpeakers) R.string.speakers_find_again else R.string.speakers_find)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_groups), contentDescription = null) },
                onClick = { open = false; onFindSpeakers() },
            )
            if (hasAudio) DropdownMenuItem(
                text = { Text(stringResource(R.string.lib_delete_audio)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_delete_sweep), contentDescription = null) },
                onClick = { open = false; onDeleteAudio() },
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

/** Choose a model (and its language, if it needs or accepts one) to transcribe the audio again. */
@Composable
private fun AgainDialog(
    installed: List<ModelSpec>,
    current: String?,
    defaultLanguage: (ModelSpec) -> String?,
    onDismiss: () -> Unit,
    onStart: (ModelSpec, String?) -> Unit,
) {
    var chosen by remember { mutableStateOf(installed.firstOrNull { it.id != current } ?: installed.first()) }
    var language by remember(chosen) { mutableStateOf(defaultLanguage(chosen)) }
    val languages = choosableLanguages(chosen)
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
                if (languages.isNotEmpty()) {
                    Text(
                        stringResource(R.string.lib_again_language),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        languages.forEach { code ->
                            FilterChip(selected = code == language, onClick = { language = code }, label = { Text(languageLabel(code, short = true)) })
                        }
                    }
                } else {
                    Text(
                        stringResource(R.string.language_detected),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onStart(chosen, language.takeIf { languages.isNotEmpty() }) }) { Text(stringResource(R.string.lib_again_start)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
