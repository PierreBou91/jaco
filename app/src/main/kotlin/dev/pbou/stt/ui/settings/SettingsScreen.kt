@file:OptIn(ExperimentalMaterial3Api::class)

package dev.pbou.stt.ui.settings

import android.app.StatusBarManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.pbou.stt.AppGraph
import dev.pbou.stt.BuildConfig
import dev.pbou.stt.R
import dev.pbou.stt.data.AppSettings
import dev.pbou.stt.data.Format
import dev.pbou.stt.data.Voice
import dev.pbou.stt.engine.SpeakerModels
import androidx.compose.material3.OutlinedTextField
import dev.pbou.stt.engine.ModelCatalog
import dev.pbou.stt.engine.ModelSpec
import dev.pbou.stt.keyboard.KeyboardSwitch
import dev.pbou.stt.keyboard.KeyboardTile
import dev.pbou.stt.ui.components.SectionHeader
import dev.pbou.stt.ui.endonym
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

class SettingsViewModel(private val graph: AppGraph) : ViewModel() {
    val settings = graph.settings
    val dictationModel = graph.activeModel
    val installed = graph.store.installed
    val voices = graph.voices.all

    fun renameVoice(id: String, name: String) = viewModelScope.launch { if (name.isNotBlank()) graph.voices.rename(id, name) }
    fun deleteVoice(id: String) = viewModelScope.launch { graph.voices.delete(id) }

    fun update(transform: (AppSettings) -> AppSettings) {
        val before = graph.settings.value ?: return
        val after = transform(before)
        viewModelScope.launch {
            graph.settingsRepo.update(transform)
            // Turning "keep loaded" off frees the memory right away.
            if (before.keepLoaded && !after.keepLoaded && !graph.engineBusy()) graph.host.release()
        }
    }
}

@Composable
fun SettingsScreen(vm: SettingsViewModel, onLicenses: () -> Unit, onBenchmark: () -> Unit) {
    val s = vm.settings.collectAsStateWithLifecycle().value ?: return
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    var languageDialog by rememberSaveable { mutableStateOf(false) }
    var fileModelDialog by rememberSaveable { mutableStateOf(false) }
    var improveDialog by rememberSaveable { mutableStateOf(false) }
    var voicesDialog by rememberSaveable { mutableStateOf(false) }
    val voices = vm.voices.collectAsStateWithLifecycle().value
    val dictationModel = vm.dictationModel.collectAsStateWithLifecycle().value
    val installed = vm.installed.collectAsStateWithLifecycle().value.let { ids -> ModelCatalog.all.filter { it.id in ids } }
    val fileModel = installed.firstOrNull { it.id == s.fileModelId }
    // Whether the keyboard is turned on in the phone's settings: checked again on coming back from them.
    val context = LocalContext.current
    var keyboardEnabled by remember { mutableStateOf(isKeyboardEnabled(context)) }
    var oneTap by remember { mutableStateOf(KeyboardSwitch.canSwitchDirectly(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        keyboardEnabled = isKeyboardEnabled(context)
        oneTap = KeyboardSwitch.canSwitchDirectly(context)
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_settings)) },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 24.dp)) {
            item { SectionHeader(stringResource(R.string.settings_keyboard)) }
            item { KeyboardSetting(enabled = keyboardEnabled) }
            item { TileSetting() }
            item { OneTapSetting(granted = oneTap) }
            item {
                SwitchItem(
                    title = stringResource(R.string.setting_kb_autostart),
                    summary = stringResource(R.string.setting_kb_autostart_summary),
                    icon = R.drawable.ic_mic,
                    checked = s.keyboardAutoStart,
                ) { v -> vm.update { it.copy(keyboardAutoStart = v) } }
            }

            item { SectionHeader(stringResource(R.string.settings_transcription)) }
            item {
                Item(
                    title = stringResource(R.string.setting_language),
                    summary = endonym(s.language),
                    icon = R.drawable.ic_language,
                    onClick = { languageDialog = true },
                )
            }
            if (installed.size > 1 || fileModel != null) item {
                Item(
                    title = stringResource(R.string.setting_file_model),
                    summary = fileModel?.name ?: stringResource(R.string.setting_file_model_same, dictationModel?.name.orEmpty()),
                    icon = R.drawable.ic_audio_file,
                    onClick = { fileModelDialog = true },
                )
            }
            item { PauseSetting(s.pauseSeconds) { v -> vm.update { it.copy(pauseSeconds = v) } } }
            item {
                SwitchItem(
                    title = stringResource(R.string.setting_preview),
                    summary = stringResource(R.string.setting_preview_summary),
                    icon = R.drawable.ic_graphic_eq,
                    checked = s.livePreview,
                ) { v -> vm.update { it.copy(livePreview = v) } }
            }

            item { SectionHeader(stringResource(R.string.settings_recordings)) }
            item {
                SwitchItem(
                    title = stringResource(R.string.setting_keep_audio),
                    summary = stringResource(R.string.setting_keep_audio_summary),
                    icon = R.drawable.ic_audio_file,
                    checked = s.keepAudio,
                ) { v -> vm.update { it.copy(keepAudio = v) } }
            }
            if (s.keepAudio) item {
                val improve = installed.firstOrNull { it.id == s.improveModelId }
                Item(
                    title = stringResource(R.string.setting_improve),
                    summary = improve?.let { stringResource(R.string.setting_improve_on, it.name) } ?: stringResource(R.string.setting_improve_off),
                    icon = R.drawable.ic_auto_awesome,
                    onClick = { improveDialog = true },
                )
            }
            if (s.keepAudio) item {
                SwitchItem(
                    title = stringResource(R.string.setting_speakers),
                    summary = stringResource(R.string.setting_speakers_summary, Format.bytes(context, SpeakerModels.installBytes)),
                    icon = R.drawable.ic_groups,
                    checked = s.findSpeakers,
                ) { v -> vm.update { it.copy(findSpeakers = v) } }
            }
            if (s.keepAudio && s.findSpeakers) item {
                Item(
                    title = stringResource(R.string.setting_voices),
                    summary = if (voices.isEmpty()) stringResource(R.string.setting_voices_none)
                    else voices.sortedBy { it.name.lowercase() }.joinToString(", ") { it.name },
                    icon = R.drawable.ic_record_voice_over,
                    onClick = { voicesDialog = true },
                )
            }

            item { SectionHeader(stringResource(R.string.settings_performance)) }
            item { ThreadsSetting(s.effectiveThreads) { v -> vm.update { it.copy(threads = v) } } }
            item {
                SwitchItem(
                    title = stringResource(R.string.setting_keep_loaded),
                    summary = stringResource(R.string.setting_keep_loaded_summary),
                    icon = R.drawable.ic_memory,
                    checked = s.keepLoaded,
                ) { v -> vm.update { it.copy(keepLoaded = v) } }
            }

            item { SectionHeader(stringResource(R.string.settings_downloads)) }
            item {
                SwitchItem(
                    title = stringResource(R.string.setting_wifi_only),
                    summary = stringResource(R.string.setting_wifi_only_summary),
                    icon = R.drawable.ic_wifi,
                    checked = s.wifiOnly,
                ) { v -> vm.update { it.copy(wifiOnly = v) } }
            }

            item { SectionHeader(stringResource(R.string.settings_lab)) }
            item {
                Item(
                    title = stringResource(R.string.bench_open),
                    summary = stringResource(R.string.bench_open_summary),
                    icon = R.drawable.ic_speed,
                    onClick = onBenchmark,
                )
            }
            item {
                SwitchItem(
                    title = stringResource(R.string.setting_timings),
                    summary = stringResource(R.string.setting_timings_summary),
                    icon = R.drawable.ic_timer,
                    checked = s.showTimings,
                ) { v -> vm.update { it.copy(showTimings = v) } }
            }

            item { SectionHeader(stringResource(R.string.settings_about)) }
            item {
                Item(
                    title = stringResource(R.string.setting_licenses),
                    summary = stringResource(R.string.setting_licenses_summary),
                    icon = R.drawable.ic_info,
                    onClick = onLicenses,
                )
            }
            item {
                Item(
                    title = stringResource(R.string.setting_version),
                    summary = BuildConfig.VERSION_NAME,
                    icon = R.drawable.ic_verified,
                    onClick = null,
                )
            }
        }
    }

    if (fileModelDialog) {
        FileModelDialog(s, installed, current = fileModel, onDismiss = { fileModelDialog = false }) { spec ->
            vm.update { it.copy(fileModelId = spec?.id) }
            fileModelDialog = false
        }
    }
    if (improveDialog) {
        ImproveDialog(installed, current = installed.firstOrNull { it.id == s.improveModelId }, onDismiss = { improveDialog = false }) { spec ->
            vm.update { it.copy(improveModelId = spec?.id) }
            improveDialog = false
        }
    }
    if (voicesDialog) {
        VoicesDialog(voices, onDismiss = { voicesDialog = false }, onRename = vm::renameVoice, onDelete = vm::deleteVoice)
    }
    if (languageDialog) {
        LanguageDialog(current = s.language, onDismiss = { languageDialog = false }) { code ->
            vm.update { it.copy(language = code) }
            languageDialog = false
        }
    }
}

/** The remembered voices: rename one (everywhere it was recognised) or forget it. */
@Composable
private fun VoicesDialog(voices: List<Voice>, onDismiss: () -> Unit, onRename: (String, String) -> Unit, onDelete: (String) -> Unit) {
    var editing by remember { mutableStateOf<Voice?>(null) }
    var forgetting by remember { mutableStateOf<Voice?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_voices)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.voices_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (voices.isEmpty()) Text(stringResource(R.string.setting_voices_none), style = MaterialTheme.typography.bodyLarge)
                voices.sortedBy { it.name.lowercase() }.forEach { v ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_person), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(16.dp))
                        Text(v.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = { editing = v }) {
                            Icon(painterResource(R.drawable.ic_edit), contentDescription = stringResource(R.string.lib_rename))
                        }
                        IconButton(onClick = { forgetting = v }) {
                            Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.voices_forget))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
    editing?.let { v ->
        var text by remember(v.id) { mutableStateOf(v.name) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.lib_rename)) },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { onRename(v.id, text); editing = null }, enabled = text.isNotBlank()) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    forgetting?.let { v ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text(stringResource(R.string.voices_forget_title, v.name)) },
            text = { Text(stringResource(R.string.voices_forget_body)) },
            confirmButton = { TextButton(onClick = { onDelete(v.id); forgetting = null }) { Text(stringResource(R.string.voices_forget)) } },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun Item(title: String, summary: String, icon: Int, onClick: (() -> Unit)?) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}

@Composable
private fun SwitchItem(title: String, summary: String, icon: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}

@Composable
private fun PauseSetting(value: Float, onChange: (Float) -> Unit) {
    // Local while dragging, saved when released, so the slider stays smooth.
    var dragging by remember(value) { mutableFloatStateOf(value) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.setting_pause)) },
        supportingContent = {
            Column {
                Text(stringResource(R.string.setting_pause_summary, Format.decimal(dragging.toDouble())))
                Slider(
                    value = dragging,
                    onValueChange = { dragging = (it * 10).roundToInt() / 10f },
                    onValueChangeFinished = { onChange(dragging) },
                    valueRange = AppSettings.PAUSE_MIN..AppSettings.PAUSE_MAX,
                    steps = ((AppSettings.PAUSE_MAX - AppSettings.PAUSE_MIN) * 10).roundToInt() - 1,
                )
            }
        },
        leadingContent = { Icon(painterResource(R.drawable.ic_timer), contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@Composable
private fun ThreadsSetting(value: Int, onChange: (Int) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.setting_threads)) },
        supportingContent = {
            Column {
                val choices = AppSettings.THREADS
                val best = AppSettings.recommendedThreads
                Text(stringResource(R.string.setting_threads_summary))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    choices.forEachIndexed { i, n ->
                        SegmentedButton(
                            selected = n == value,
                            onClick = { onChange(n) },
                            shape = SegmentedButtonDefaults.itemShape(i, choices.size),
                            icon = {}, // no check mark: with up to 8 choices, room is short
                        ) { Text(n.toString(), maxLines = 1, softWrap = false) }
                    }
                }
                // Why the default is not the biggest number.
                Text(
                    stringResource(R.string.setting_threads_best, best),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (value == best) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        leadingContent = { Icon(painterResource(R.drawable.ic_bolt), contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

private fun isKeyboardEnabled(context: Context): Boolean = KeyboardTile.isEnabled(context)

/** Offers to add the Quick Settings tile (Android 13+ asks with its own dialog); older phones add it by hand. */
@Composable
private fun TileSetting() {
    val context = LocalContext.current
    val canAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    Item(
        title = stringResource(R.string.setting_kb_tile),
        summary = stringResource(R.string.setting_kb_tile_summary) +
            if (canAsk) "" else " " + stringResource(R.string.setting_kb_tile_manual),
        icon = R.drawable.ic_tune,
        onClick = if (canAsk) {
            { requestTile(context) }
        } else null,
    )
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun requestTile(context: Context) {
    context.getSystemService(StatusBarManager::class.java).requestAddTileService(
        KeyboardTile.component(context),
        context.getString(R.string.kb_tile),
        Icon.createWithResource(context, R.drawable.ic_keyboard),
        context.mainExecutor,
    ) { }
}

/**
 * The tile can switch keyboards in one tap with a permission no app can get by itself (it can only be
 * given from a computer). Without it, the tile opens the keyboard list, which works for everyone.
 */
@Composable
private fun OneTapSetting(granted: Boolean) {
    val context = LocalContext.current
    Item(
        title = stringResource(R.string.setting_kb_one_tap),
        summary = stringResource(if (granted) R.string.setting_kb_one_tap_on else R.string.setting_kb_one_tap_off),
        icon = if (granted) R.drawable.ic_check_circle else R.drawable.ic_content_copy,
        onClick = if (granted) null else {
            {
                val command = "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("adb", command))
                // Android 13+ confirms copies itself.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
                }
            }
        },
    )
}

/** Off: opens the phone's keyboard list to turn it on. On: opens the keyboard picker, to try it. */
@Composable
private fun KeyboardSetting(enabled: Boolean) {
    val context = LocalContext.current
    Item(
        title = stringResource(R.string.setting_kb),
        summary = stringResource(if (enabled) R.string.setting_kb_on else R.string.setting_kb_off),
        icon = R.drawable.ic_keyboard,
        onClick = {
            if (enabled) {
                context.getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            } else {
                context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
        },
    )
}

/** "Same as dictation", then each installed model, with how it gets the language. */
@Composable
private fun FileModelDialog(s: AppSettings, installed: List<ModelSpec>, current: ModelSpec?, onDismiss: () -> Unit, onSelect: (ModelSpec?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_file_model)) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.setting_file_model_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                RadioRow(stringResource(R.string.setting_file_model_same_option), null, selected = current == null) { onSelect(null) }
                installed.forEach { spec ->
                    val language = s.languageFor(spec)
                    val note = if (spec.effectiveLanguage(language, listOf(s.language)) == null) stringResource(R.string.language_detected)
                        else stringResource(R.string.setting_file_model_told, endonym(s.language))
                    RadioRow(spec.name, note, selected = spec == current) { onSelect(spec) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

/** Off, or the model that transcribes recordings again while charging. */
@Composable
private fun ImproveDialog(installed: List<ModelSpec>, current: ModelSpec?, onDismiss: () -> Unit, onSelect: (ModelSpec?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_improve)) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.setting_improve_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                RadioRow(stringResource(R.string.setting_improve_off), null, selected = current == null) { onSelect(null) }
                installed.forEach { spec -> RadioRow(spec.name, null, selected = spec == current) { onSelect(spec) } }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun RadioRow(title: String, note: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LanguageDialog(current: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_language)) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.setting_language_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                AppSettings.LANGUAGES.forEach { code ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = code == current, role = Role.RadioButton, onClick = { onSelect(code) })
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = code == current, onClick = null)
                        Spacer(Modifier.width(16.dp))
                        Text(endonym(code), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

private data class Credit(val name: String, val detail: String, val url: String)

@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val models = ModelCatalog.all.map {
        Credit(it.name, "${it.maker} · ${it.license.label}", it.license.url)
    }
    val software = listOf(
        Credit("sherpa-onnx", "k2-fsa · Apache 2.0", "https://github.com/k2-fsa/sherpa-onnx"),
        Credit("ONNX Runtime", "Microsoft · MIT", "https://github.com/microsoft/onnxruntime"),
        Credit("Silero VAD", "Silero Team · MIT", "https://github.com/snakers4/silero-vad"),
        Credit("pyannote segmentation 3.0", "CNRS, pyannote · MIT", "https://huggingface.co/pyannote/segmentation-3.0"),
        Credit("TitaNet small", "NVIDIA · CC-BY-4.0", "https://catalog.ngc.nvidia.com/orgs/nvidia/teams/nemo/models/titanet_small"),
        Credit("Inter", "Rasmus Andersson · SIL Open Font License 1.1", "https://rsms.me/inter/"),
        Credit("Material Symbols", "Google · Apache 2.0", "https://fonts.google.com/icons"),
        Credit("Android Jetpack", "Google · Apache 2.0", "https://developer.android.com/jetpack"),
        Credit("Kotlin coroutines", "JetBrains · Apache 2.0", "https://github.com/Kotlin/kotlinx.coroutines"),
        Credit("Apache Commons Compress", "Apache Software Foundation · Apache 2.0", "https://commons.apache.org/proper/commons-compress/"),
    )
    val open = { url: String -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setting_licenses)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item {
                Text(
                    stringResource(R.string.licenses_models_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            item { SectionHeader(stringResource(R.string.licenses_models)) }
            models.forEach { c -> item { CreditItem(c) { open(c.url) } } }
            item { SectionHeader(stringResource(R.string.licenses_software)) }
            software.forEach { c -> item { CreditItem(c) { open(c.url) } } }
        }
    }
}

@Composable
private fun CreditItem(c: Credit, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(c.name) },
        supportingContent = { Text(c.detail) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
