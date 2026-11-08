@file:OptIn(ExperimentalMaterial3Api::class)

package dev.pbou.stt.ui.bench

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pbou.stt.R
import dev.pbou.stt.data.BenchTexts
import dev.pbou.stt.data.Format
import dev.pbou.stt.engine.Benchmark
import dev.pbou.stt.engine.ModelCatalog
import dev.pbou.stt.ui.components.SectionHeader
import dev.pbou.stt.ui.endonym
import dev.pbou.stt.ui.seconds
import dev.pbou.stt.ui.speedFactor
import java.text.DateFormat
import java.util.Date

@Composable
fun BenchScreen(vm: BenchViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val level by vm.level.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val snackbar = remember { SnackbarHostState() }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()

    // The microphone is never on without this screen in front of you.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.onHidden() }
    DisposableEffect(Unit) { onDispose { vm.onHidden() } }
    // A run takes minutes: keep the screen (and so the app) awake meanwhile.
    val busy = ui.run != null || ui.recording != null
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }

    var pending by rememberSaveable { mutableIntStateOf(-1) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && pending >= 0) vm.record(pending)
        pending = -1
    }
    val onRecord = { i: Int ->
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            vm.record(i)
        } else {
            pending = i
            askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val micBusy = stringResource(R.string.mic_unavailable)
    val nothingHeard = stringResource(R.string.bench_nothing_heard)
    val failedTemplate = stringResource(R.string.bench_failed)
    LaunchedEffect(ui.problem) {
        val p = ui.problem ?: return@LaunchedEffect
        val message = when (p) {
            BenchProblem.Microphone -> micBusy
            BenchProblem.NothingHeard -> nothingHeard
            is BenchProblem.ModelFailed -> String.format(failedTemplate, p.model.name, p.message)
        }
        vm.dismissProblem()
        snackbar.showSnackbar(message, duration = SnackbarDuration.Long)
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bench_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 32.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.bench_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }
            item { LanguagePicker(ui.language, enabled = !busy, onSelect = vm::selectLanguage) }

            item { SectionHeader(stringResource(R.string.bench_step_record)) }
            itemsIndexed(ui.sentences) { i, text ->
                SentenceRow(
                    number = i + 1,
                    text = text,
                    recordedMs = ui.recorded[i],
                    recording = ui.recording == i,
                    level = { if (ui.recording == i) level else 0f },
                    enabled = ui.run == null && (ui.recording == null || ui.recording == i),
                    onRecord = { onRecord(i) },
                    onStop = vm::stopRecording,
                )
            }

            item { SectionHeader(stringResource(R.string.bench_step_compare)) }
            items(ui.installed, key = { it.id }) { spec ->
                val checked = spec.id in ui.selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(checked, enabled = ui.run == null, role = Role.Checkbox, onValueChange = { vm.toggleModel(spec.id) })
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = null, enabled = ui.run == null)
                    Spacer(Modifier.width(12.dp))
                    Text(spec.name, style = MaterialTheme.typography.bodyLarge)
                }
            }
            item { RunControls(ui, onRun = vm::startRun, onCancel = vm::cancelRun) }

            item { SectionHeader(stringResource(R.string.bench_results)) }
            if (ui.results.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.bench_no_results),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }
            items(ui.results, key = { it.modelId }) { ResultCard(it) }
        }
    }
}

@Composable
private fun LanguagePicker(language: String, enabled: Boolean, onSelect: (String) -> Unit) {
    Row(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.bench_sentences_in), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 16.dp))
        SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
            BenchTexts.LANGUAGES.forEachIndexed { i, code ->
                SegmentedButton(
                    selected = code == language,
                    onClick = { onSelect(code) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(i, BenchTexts.LANGUAGES.size),
                ) { Text(endonym(code)) }
            }
        }
    }
}

@Composable
private fun SentenceRow(
    number: Int,
    text: String,
    recordedMs: Long?,
    recording: Boolean,
    level: () -> Float,
    enabled: Boolean,
    onRecord: () -> Unit,
    onStop: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = if (recording) scheme.surfaceContainerHigh else scheme.surfaceContainer,
        border = if (recording) BorderStroke(1.dp, scheme.primary) else null,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text, style = MaterialTheme.typography.bodyLarge)
                when {
                    recording -> {
                        val live = level()
                        Text(
                            stringResource(if (live > 0f) R.string.bench_hint_recording else R.string.bench_hint_next),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.primary,
                        )
                        LevelBar(level)
                    }
                    recordedMs != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_check_circle_fill), contentDescription = null, tint = scheme.tertiary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.bench_recorded, Format.decimal(recordedMs / 1000.0)),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    else -> Text(stringResource(R.string.bench_not_recorded), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(8.dp))
            if (recording) {
                FilledIconButton(onClick = onStop, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_stop_fill), contentDescription = stringResource(R.string.cd_stop_recording))
                }
            } else {
                FilledTonalIconButton(onClick = onRecord, enabled = enabled, modifier = Modifier.size(48.dp)) {
                    Icon(
                        painterResource(if (recordedMs != null) R.drawable.ic_refresh else R.drawable.ic_mic),
                        contentDescription = stringResource(R.string.cd_record_sentence, number),
                    )
                }
            }
        }
    }
}

/** A thin bar that follows the voice: shows the phone hears you. */
@Composable
private fun LevelBar(level: () -> Float) {
    val shown by animateFloatAsState(level(), label = "level")
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(2.dp)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(shown.coerceIn(0.02f, 1f))
                .height(4.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
        )
    }
}

@Composable
private fun RunControls(ui: BenchUi, onRun: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val run = ui.run
        if (run == null) {
            Text(
                stringResource(if (ui.recorded.isEmpty()) R.string.bench_need_recording else R.string.bench_models_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val count = ui.installed.count { it.id in ui.selected }
            Button(onClick = onRun, enabled = ui.canRun) {
                Icon(painterResource(R.drawable.ic_speed), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(pluralStringResource(R.plurals.bench_run, count, count))
            }
        } else {
            Text(stringResource(R.string.bench_progress_models, run.modelIndex + 1, run.modelCount), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (run.done < 0) stringResource(R.string.bench_progress_loading, run.model.name)
                else stringResource(R.string.bench_progress, run.model.name, minOf(run.done + 1, run.total), run.total),
                style = MaterialTheme.typography.bodyLarge,
            )
            val steps = run.modelCount * (run.total + 1)
            val done = run.modelIndex * (run.total + 1) + (run.done + 1)
            val progress by animateFloatAsState(done.toFloat() / steps, label = "progress")
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.bench_cancel)) }
        }
    }
}

@Composable
private fun ResultCard(r: Benchmark.Result) {
    val spec = ModelCatalog.byId(r.modelId)
    var open by rememberSaveable(r.modelId, r.finishedAt) { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(spec?.name ?: r.modelId, style = MaterialTheme.typography.titleMedium)
                val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(r.finishedAt))
                val meta = stringResource(R.string.bench_result_meta, date, r.threads) +
                    (r.modelLanguage?.let { " · " + stringResource(R.string.bench_language_forced, endonym(it)) } ?: "")
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row {
                Metric(stringResource(R.string.bench_word_errors), stringResource(R.string.bench_percent, Format.decimal(r.score.percent)), Modifier.weight(1f))
                Metric(stringResource(R.string.bench_wait), stringResource(R.string.bench_seconds, seconds(r.waitMs)), Modifier.weight(1f))
            }
            Row {
                val factor = speedFactor(r.rtf)
                Metric(
                    stringResource(R.string.bench_speed),
                    if (factor != null) stringResource(R.string.bench_factor, factor) else stringResource(R.string.bench_slower),
                    Modifier.weight(1f),
                )
                Metric(stringResource(R.string.bench_load), stringResource(R.string.bench_seconds, seconds(r.loadMs)), Modifier.weight(1f))
            }
            TextButton(onClick = { open = !open }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text(stringResource(if (open) R.string.bench_hide_transcripts else R.string.bench_show_transcripts))
                Icon(
                    painterResource(R.drawable.ic_expand_more),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp).rotate(if (open) 180f else 0f),
                )
            }
            AnimatedVisibility(open) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    r.sentences.forEach { s -> SentenceResult(s) }
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"))
    }
}

@Composable
private fun SentenceResult(s: Benchmark.Sentence) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(s.reference, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(s.text.ifBlank { stringResource(R.string.bench_empty_transcript) }, style = MaterialTheme.typography.bodyMedium)
        val errors = s.score.errors
        Text(
            pluralStringResource(R.plurals.bench_sentence_errors, errors, errors) + " · " + stringResource(R.string.bench_seconds, seconds(s.decodeMs)),
            style = MaterialTheme.typography.labelMedium,
            color = if (errors == 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
        )
    }
}
