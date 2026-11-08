@file:OptIn(ExperimentalMaterial3Api::class)

package dev.pbou.stt.ui.file

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pbou.stt.R
import dev.pbou.stt.data.FileState
import dev.pbou.stt.engine.FileTranscriber
import dev.pbou.stt.engine.ModelSpec
import dev.pbou.stt.ui.components.IconTile
import dev.pbou.stt.ui.dictate.copyText
import dev.pbou.stt.ui.dictate.shareText
import kotlinx.coroutines.launch

/** Picker types: audio, plus videos (their sound is transcribed). */
private val PICK_TYPES = arrayOf("audio/*", "video/*", "application/ogg")

@Composable
fun FileScreen(vm: FileViewModel, onBack: () -> Unit, onManageModels: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val copied = stringResource(R.string.copied)
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.open(uri) }

    // Long files take minutes: keep the screen on while it works and you are looking at it.
    val busy = ui.file?.busy == true
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.file_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (ui.file != null) {
                        IconButton(onClick = { pick.launch(PICK_TYPES) }) {
                            Icon(painterResource(R.drawable.ic_folder_open), contentDescription = stringResource(R.string.file_action_choose))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            val file = ui.file
            if (file != null && file.segments.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedButton(onClick = {
                            copyText(context, file.text)
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) scope.launch { snackbar.showSnackbar(copied) }
                        }, modifier = Modifier.weight(1f)) {
                            Icon(painterResource(R.drawable.ic_content_copy), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.action_copy))
                        }
                        Button(onClick = { shareText(context, file.text) }, modifier = Modifier.weight(1f)) {
                            Icon(painterResource(R.drawable.ic_share), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.action_share))
                        }
                    }
                }
            }
        },
    ) { padding ->
        val file = ui.file
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (file == null) {
                Empty(onPick = { pick.launch(PICK_TYPES) })
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        Header(
                            file = file,
                            installed = ui.installed,
                            onModel = vm::rerun,
                            onStop = vm::stop,
                            onRestart = { file.model?.let(vm::rerun) },
                            onManageModels = onManageModels,
                        )
                    }
                    items(file.segments) { SegmentRow(it) }
                }
            }
        }
    }
}

@Composable
private fun Empty(onPick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconTile(R.drawable.ic_audio_file)
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.file_empty_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.file_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onPick) {
            Icon(painterResource(R.drawable.ic_folder_open), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.file_action_choose))
        }
    }
}

@Composable
private fun Header(
    file: FileState,
    installed: List<ModelSpec>,
    onModel: (ModelSpec) -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onManageModels: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(file.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            file.durationMs?.let {
                Text(clock(it), style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        file.model?.let { ModelChip(it, installed, enabled = file.phase != FileState.Phase.COPYING, onModel = onModel) }
        Status(file, onStop, onRestart, onManageModels)
        if (file.segments.isNotEmpty()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** The model used; picking another one transcribes the file again with it. */
@Composable
private fun ModelChip(model: ModelSpec, installed: List<ModelSpec>, enabled: Boolean, onModel: (ModelSpec) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val description = stringResource(R.string.cd_change_model, model.name)
    Box {
        AssistChip(
            onClick = { open = true },
            enabled = enabled && installed.size > 1,
            label = { Text(model.name) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_graphic_eq), contentDescription = null, modifier = Modifier.size(18.dp)) },
            trailingIcon = if (installed.size > 1) {
                { Icon(painterResource(R.drawable.ic_expand_more), contentDescription = null, modifier = Modifier.size(18.dp)) }
            } else null,
            modifier = Modifier.semantics { contentDescription = description },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            installed.forEach { spec ->
                DropdownMenuItem(
                    text = { Text(spec.name) },
                    onClick = { open = false; if (spec != model) onModel(spec) },
                    trailingIcon = if (spec == model) {
                        { Icon(painterResource(R.drawable.ic_check), contentDescription = null) }
                    } else null,
                )
            }
        }
    }
}

@Composable
private fun Status(file: FileState, onStop: () -> Unit, onRestart: () -> Unit, onManageModels: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val body = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum")
    when (file.phase) {
        FileState.Phase.COPYING -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.file_opening), style = body, color = muted)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        FileState.Phase.RUNNING -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val total = file.durationMs
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (total != null) stringResource(R.string.file_progress, clock(file.doneMs), clock(total))
                    else stringResource(R.string.file_progress_unknown, clock(file.doneMs)),
                    style = body,
                    color = muted,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onStop) { Text(stringResource(R.string.file_stop)) }
            }
            if (total != null && total > 0) {
                LinearProgressIndicator(progress = { (file.doneMs.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        FileState.Phase.DONE -> Text(
            if (file.segments.isEmpty()) stringResource(R.string.file_no_speech) else elapsed(file.elapsedMs),
            style = body,
            color = muted,
        )
        FileState.Phase.STOPPED -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.file_stopped, clock(file.doneMs)), style = body, color = muted, modifier = Modifier.weight(1f))
            TextButton(onClick = onRestart) { Text(stringResource(R.string.file_restart)) }
        }
        FileState.Phase.FAILED -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val message = when (file.problem) {
                FileState.Problem.NO_MODEL -> R.string.file_no_model
                FileState.Problem.UNREADABLE -> R.string.file_unreadable
                else -> R.string.file_engine_error
            }
            Text(stringResource(message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            when (file.problem) {
                FileState.Problem.NO_MODEL -> FilledTonalButton(onClick = onManageModels) { Text(stringResource(R.string.action_manage_models)) }
                FileState.Problem.ENGINE -> FilledTonalButton(onClick = onRestart) { Text(stringResource(R.string.action_retry)) }
                else -> {}
            }
        }
    }
}

@Composable
private fun SegmentRow(s: FileTranscriber.Segment) {
    Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(
            clock(s.startMs),
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp).padding(top = 3.dp),
        )
        SelectionContainer(Modifier.weight(1f)) {
            Text(s.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun elapsed(ms: Long): String {
    val s = (ms + 500) / 1000
    return if (s < 60) stringResource(R.string.file_done_seconds, s.toInt())
    else stringResource(R.string.file_done_minutes, (s / 60).toInt(), (s % 60).toInt())
}

/** "0:42", "12:05", "1:02:09". */
private fun clock(ms: Long): String {
    val t = ms / 1000
    val h = t / 3600
    val m = t % 3600 / 60
    val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
