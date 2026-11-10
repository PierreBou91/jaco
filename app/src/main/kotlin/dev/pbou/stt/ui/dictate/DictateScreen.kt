@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.pbou.stt.ui.dictate

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pbou.stt.R
import dev.pbou.stt.engine.EngineHost
import dev.pbou.stt.engine.Languages
import dev.pbou.stt.engine.RecordingSession
import dev.pbou.stt.data.LibraryText
import dev.pbou.stt.engine.ModelCatalog
import dev.pbou.stt.engine.ModelSpec
import dev.pbou.stt.ui.Meter
import dev.pbou.stt.ui.ModelInfo
import dev.pbou.stt.ui.bytes
import dev.pbou.stt.ui.components.Badge
import dev.pbou.stt.ui.components.DownloadStatus
import dev.pbou.stt.ui.components.IconTile
import dev.pbou.stt.ui.components.MicButton
import dev.pbou.stt.ui.choosableLanguages
import dev.pbou.stt.ui.endonym
import dev.pbou.stt.ui.languageLabel
import dev.pbou.stt.ui.rememberNotificationPermission
import kotlinx.coroutines.launch

@Composable
fun DictateScreen(vm: DictateViewModel, onManageModels: () -> Unit, onOpenFile: () -> Unit, onOpenItem: (String) -> Unit) {
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
    val noModel = stringResource(R.string.rec_no_model)
    // A new recording opens on its own screen (times, pause, stop) and is kept in the Library.
    fun start() = scope.launch {
        val id = vm.start()
        if (id == null) snackbar.showSnackbar(noModel) else onOpenItem(id)
    }
    // The notification shows the session's time and its buttons: ask for it with the microphone.
    val askNotifications = rememberNotificationPermission()
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            askNotifications { start() }
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
            ui.busy -> { ui.item?.let { onOpenItem(it.id) }; Unit }
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> askNotifications { start() }
            else -> askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Keep the model warm while this screen is visible, so a recording starts instantly.
    LaunchedEffect(ui.model, ui.options, ui.settings.keepLoaded) { vm.warmUp() }

    // Problems reported by the recorder.
    val micBusy = stringResource(R.string.mic_unavailable)
    val engineError = stringResource(R.string.engine_error)
    val audioError = stringResource(R.string.rec_audio_failed)
    LaunchedEffect(ui.problem) {
        val p = ui.problem ?: return@LaunchedEffect
        val message = when (p) {
            RecordingSession.Problem.Microphone -> micBusy
            is RecordingSession.Problem.Engine -> "$engineError ${p.message}"
            is RecordingSession.Problem.Audio -> audioError
        }
        vm.dismissProblem()
        snackbar.showSnackbar(message, duration = SnackbarDuration.Long)
    }

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
                        IconButton(onClick = onOpenFile) {
                            Icon(painterResource(R.drawable.ic_audio_file), contentDescription = stringResource(R.string.file_title))
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
                    EmptyTranscript(Modifier.weight(1f).fillMaxWidth())
                    Controls(ui = ui, level = { level }, onMic = onMic)
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

/** Record, or (while a recording runs) the way back to it. */
@Composable
private fun Controls(ui: DictateUi, level: () -> Float, onMic: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 20.dp, top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val time = LibraryText.clock(ui.session?.recordedMs ?: 0)
        val status = when {
            ui.finishing -> stringResource(R.string.status_finishing)
            ui.paused -> stringResource(R.string.home_paused, time)
            ui.busy -> stringResource(R.string.home_recording, time)
            ui.engine is EngineHost.State.Failed -> stringResource(R.string.status_engine_failed)
            ui.engineLoading -> stringResource(R.string.status_loading, ui.model?.name ?: "")
            else -> stringResource(R.string.status_idle)
        }
        AnimatedContent(status, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "status") { text ->
            Text(
                text,
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        if (ui.busy) {
            // A recording runs: its screen has the controls; this opens it.
            Button(onClick = onMic, modifier = Modifier.height(56.dp)) {
                Icon(painterResource(R.drawable.ic_fiber_manual_record_fill), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.home_open_recording))
            }
        } else {
            MicButton(listening = false, finishing = false, level = level, onClick = onMic)
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
            val choosable = ui.model?.let(::choosableLanguages).orEmpty()
            if (choosable.isNotEmpty()) {
                FlowRow(
                    Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    choosable.forEach { code ->
                        FilterChip(
                            selected = code == ui.model?.let(ui.settings::languageFor),
                            onClick = { onSelectLanguage(code) },
                            label = { Text(languageLabel(code, short = true)) },
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
        is Languages.Choose, is Languages.ChooseOrAuto -> if (used != null && used != ui.settings.language) {
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

internal fun copyText(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(context.getString(R.string.clip_label), text))
}

internal fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null))
}

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
