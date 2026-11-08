@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.pbou.stt.ui.dictate

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pbou.stt.R
import dev.pbou.stt.data.AppSettings
import dev.pbou.stt.data.Format
import dev.pbou.stt.engine.EngineHost
import dev.pbou.stt.engine.Languages
import dev.pbou.stt.engine.LiveTranscriber
import dev.pbou.stt.engine.ModelCatalog
import dev.pbou.stt.engine.ModelSpec
import dev.pbou.stt.ui.Meter
import dev.pbou.stt.ui.ModelInfo
import dev.pbou.stt.ui.bytes
import dev.pbou.stt.ui.components.Badge
import dev.pbou.stt.ui.components.DownloadStatus
import dev.pbou.stt.ui.components.IconTile
import dev.pbou.stt.ui.endonym
import dev.pbou.stt.ui.rememberNotificationPermission
import dev.pbou.stt.ui.theme.TranscriptStyle
import kotlinx.coroutines.launch

@Composable
fun DictateScreen(vm: DictateViewModel, onManageModels: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val level by vm.level.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }

    // Microphone permission, asked on the first tap.
    val micDenied = stringResource(R.string.mic_permission_denied)
    val openSettings = stringResource(R.string.action_settings)
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            vm.start()
        } else {
            scope.launch {
                val r = snackbar.showSnackbar(micDenied, actionLabel = openSettings, duration = SnackbarDuration.Long)
                if (r == SnackbarResult.ActionPerformed) openAppSettings(context)
            }
        }
    }
    val onMic = {
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        when {
            ui.listening -> vm.stop()
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> vm.start()
            else -> askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Keep the model warm while this screen is visible; never listen in the background.
    LaunchedEffect(ui.model, ui.options, ui.settings.keepLoaded) { vm.warmUp() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.stop() }
    // Leaving the tab stops listening too: the microphone is never on without the screen that shows it.
    DisposableEffect(Unit) { onDispose { vm.stop() } }

    // Problems reported by the transcriber.
    val micBusy = stringResource(R.string.mic_unavailable)
    val engineError = stringResource(R.string.engine_error)
    LaunchedEffect(ui.live.problem) {
        val p = ui.live.problem ?: return@LaunchedEffect
        val message = when (p) {
            LiveTranscriber.Problem.Microphone -> micBusy
            is LiveTranscriber.Problem.Engine -> "$engineError ${p.message}"
        }
        vm.dismissProblem()
        snackbar.showSnackbar(message, duration = SnackbarDuration.Long)
    }

    val cleared = stringResource(R.string.transcript_cleared)
    val undo = stringResource(R.string.action_undo)
    val copied = stringResource(R.string.copied)
    val askNotifications = rememberNotificationPermission()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val model = ui.model
                    if (model != null) ModelSelector(ui, model, onClick = { sheetOpen = true })
                    else Text(stringResource(R.string.app_name))
                },
                actions = {
                    if (ui.model != null) {
                        IconButton(
                            enabled = ui.hasText,
                            onClick = {
                                val removed = vm.clear()
                                scope.launch {
                                    val r = snackbar.showSnackbar(cleared, actionLabel = undo, duration = SnackbarDuration.Short)
                                    if (r == SnackbarResult.ActionPerformed) vm.undoClear(removed)
                                }
                            },
                        ) {
                            Icon(painterResource(R.drawable.ic_delete_sweep), contentDescription = stringResource(R.string.action_clear))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                !ui.loaded -> {}
                ui.model == null -> FirstRun(
                    ui = ui,
                    onDownload = { mobile -> askNotifications { vm.downloadRecommended(mobile) } },
                    onPause = vm::pauseRecommended,
                    onBrowse = onManageModels,
                )
                else -> Column(Modifier.fillMaxSize()) {
                    Transcript(ui, Modifier.weight(1f).fillMaxWidth())
                    Controls(
                        ui = ui,
                        level = { level },
                        onMic = onMic,
                        onCopy = {
                            copy(context, ui.live.text)
                            // Android 13+ shows its own confirmation for the clipboard.
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) scope.launch { snackbar.showSnackbar(copied) }
                        },
                        onShare = { share(context, ui.live.text) },
                    )
                }
            }
        }
    }

    if (sheetOpen) {
        ModelSheet(
            ui = ui,
            onDismiss = { sheetOpen = false },
            onSelectModel = vm::selectModel,
            onSelectLanguage = vm::selectLanguage,
            onManageModels = { sheetOpen = false; onManageModels() },
        )
    }
}

@Composable
private fun ModelSelector(ui: DictateUi, model: ModelSpec, onClick: () -> Unit) {
    val subtitle = when {
        ui.engine is EngineHost.State.Loading -> stringResource(R.string.engine_loading_short)
        model.languages is Languages.Auto -> stringResource(R.string.language_detected)
        else -> ui.options?.language?.let { endonym(it) } ?: stringResource(R.string.language_detected)
    }
    val label = stringResource(R.string.cd_change_model, model.name)
    Row(
        Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f, fill = false)) {
            Text(model.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Spacer(Modifier.width(4.dp))
        Icon(painterResource(R.drawable.ic_expand_more), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Transcript(ui: DictateUi, modifier: Modifier) {
    val live = ui.live
    if (!ui.hasText && live.partial.isEmpty() && !ui.busy) {
        EmptyTranscript(modifier)
        return
    }
    val scroll = rememberScrollState()
    // Follow the text as it grows.
    LaunchedEffect(scroll) { snapshotFlow { scroll.maxValue }.collect { scroll.animateScrollTo(it) } }
    val partialColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.verticalScroll(scroll).padding(horizontal = 24.dp, vertical = 8.dp)) {
        if (!ui.hasText && live.partial.isEmpty()) {
            Text(stringResource(R.string.start_speaking), style = TranscriptStyle, color = partialColor)
        } else {
            SelectionContainer {
                Text(
                    buildAnnotatedString {
                        append(live.text)
                        if (live.partial.isNotEmpty()) {
                            if (live.segments.isNotEmpty()) append(" ")
                            withStyle(SpanStyle(color = partialColor)) { append(live.partial) }
                        }
                    },
                    style = TranscriptStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (ui.settings.showTimings) Timings(ui)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Timings(ui: DictateUi) {
    val style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum")
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ui.live.segments.lastOrNull()?.let { s ->
            Text(stringResource(R.string.timing_last, Format.decimal(s.audioMs / 1000.0), Format.integer(s.decodeMs)), style = style, color = color)
        }
        (ui.engine as? EngineHost.State.Ready)?.let { e ->
            Text(stringResource(R.string.timing_load, Format.decimal(e.loadMs / 1000.0), e.options.threads), style = style, color = color)
        }
    }
}

@Composable
private fun EmptyTranscript(modifier: Modifier) {
    Column(
        modifier.padding(horizontal = 40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconTile(R.drawable.ic_graphic_eq)
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Controls(ui: DictateUi, level: () -> Float, onMic: () -> Unit, onCopy: () -> Unit, onShare: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 20.dp, top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val status = when {
            ui.live.phase == LiveTranscriber.Phase.FINISHING -> stringResource(R.string.status_finishing)
            ui.listening && ui.engineLoading -> stringResource(R.string.status_listening_loading)
            ui.listening -> stringResource(R.string.status_listening)
            ui.engine is EngineHost.State.Failed -> stringResource(R.string.status_engine_failed)
            ui.engineLoading -> stringResource(R.string.status_loading, ui.model?.name ?: "")
            ui.hasText -> stringResource(R.string.status_continue)
            else -> stringResource(R.string.status_idle)
        }
        AnimatedContent(status, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "status") { text ->
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            FilledTonalIconButton(onClick = onCopy, enabled = ui.hasText, modifier = Modifier.size(56.dp)) {
                Icon(painterResource(R.drawable.ic_content_copy), contentDescription = stringResource(R.string.action_copy))
            }
            MicButton(
                listening = ui.listening,
                finishing = ui.live.phase == LiveTranscriber.Phase.FINISHING,
                level = level,
                onClick = onMic,
            )
            FilledTonalIconButton(onClick = onShare, enabled = ui.hasText, modifier = Modifier.size(56.dp)) {
                Icon(painterResource(R.drawable.ic_share), contentDescription = stringResource(R.string.action_share))
            }
        }
    }
}

@Composable
private fun MicButton(listening: Boolean, finishing: Boolean, level: () -> Float, onClick: () -> Unit) {
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
        if (finishing) CircularProgressIndicator(Modifier.size(96.dp), strokeWidth = 3.dp)
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
                    contentDescription = stringResource(if (listening) R.string.action_stop else R.string.action_dictate),
                    modifier = Modifier.size(34.dp),
                )
            }
        }
    }
}

@Composable
private fun FirstRun(ui: DictateUi, onDownload: (useMobileData: Boolean) -> Unit, onPause: () -> Unit, onBrowse: () -> Unit) {
    val spec = ModelCatalog.PARAKEET
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconTile(R.drawable.ic_download)
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.first_run_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.first_run_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(spec.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Badge(
                        stringResource(R.string.badge_recommended),
                        MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, R.drawable.ic_star_fill,
                    )
                }
                Text(stringResource(ModelInfo.description(spec)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                spec.bench?.let { b -> Ratings(ModelInfo.accuracy(b), ModelInfo.speed(b)) }
                val state = ui.recommendedDownload
                if (state == null) {
                    Button(onClick = { onDownload(false) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Icon(painterResource(R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_download_size, bytes(spec.downloadBytes)))
                    }
                    if (ui.settings.wifiOnly && !ui.onWifi) {
                        Text(stringResource(R.string.first_run_wifi_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    DownloadStatus(state, onPause = onPause, onUseMobileData = { onDownload(true) }, onRetry = { onDownload(false) })
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onBrowse) { Text(stringResource(R.string.action_browse_models)) }
    }
}

@Composable
fun Ratings(accuracy: Int, speed: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RatingRow(stringResource(R.string.label_accuracy), accuracy, stringResource(ModelInfo.accuracyLabel(accuracy)))
        RatingRow(stringResource(R.string.label_speed), speed, stringResource(ModelInfo.speedLabel(speed)))
    }
}

@Composable
private fun RatingRow(label: String, level: Int, value: String) {
    val description = "$label: $value"
    Row(Modifier.semantics(mergeDescendants = true) { contentDescription = description }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
        Meter(level)
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ModelSheet(
    ui: DictateUi,
    onDismiss: () -> Unit,
    onSelectModel: (ModelSpec) -> Unit,
    onSelectLanguage: (String) -> Unit,
    onManageModels: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.sheet_model_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            ui.installed.forEach { spec ->
                val selected = spec.id == ui.model?.id
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelectModel(spec) })
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(spec.name, style = MaterialTheme.typography.bodyLarge)
                        spec.bench?.let { b ->
                            Text(
                                stringResource(
                                    R.string.rating_summary,
                                    stringResource(ModelInfo.accuracyLabel(ModelInfo.accuracy(b))),
                                    stringResource(ModelInfo.speedLabel(ModelInfo.speed(b))),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            val more = ModelCatalog.all.size - ui.installed.size
            if (more > 0) {
                Text(
                    pluralStringResource(R.plurals.more_models, more, more),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                stringResource(R.string.setting_language),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            // Models that detect the language by themselves ignore the choice: only the note is shown for them.
            if (ui.model?.languages !is Languages.Auto) {
                FlowRow(
                    Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppSettings.LANGUAGES.forEach { code ->
                        FilterChip(
                            selected = code == ui.settings.language,
                            onClick = { onSelectLanguage(code) },
                            label = { Text(if (code == ModelSpec.AUTO) stringResource(R.string.language_auto_short) else endonym(code)) },
                        )
                    }
                }
            } else {
                Spacer(Modifier.height(6.dp))
            }
            ui.model?.let { LanguageNote(it, ui) }

            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onManageModels).padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_deployed_code), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Text(stringResource(R.string.action_manage_models), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Says how the selected model will actually treat the language setting. */
@Composable
private fun LanguageNote(model: ModelSpec, ui: DictateUi) {
    val used = ui.options?.language
    val text = when (model.languages) {
        is Languages.Auto -> stringResource(R.string.language_note_auto, model.name)
        is Languages.ChooseOrAuto -> if (used == null) stringResource(R.string.language_note_detects, model.name) else null
        is Languages.Choose -> if (used != null && used != ui.settings.language) {
            stringResource(R.string.language_note_fallback, model.name, endonym(used))
        } else null
    } ?: return
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(context.getString(R.string.clip_label), text))
}

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null))
}

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
