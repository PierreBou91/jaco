@file:OptIn(ExperimentalMaterial3Api::class)

package dev.pbou.stt.ui.settings

import android.content.Intent
import android.net.Uri
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.pbou.stt.AppGraph
import dev.pbou.stt.BuildConfig
import dev.pbou.stt.R
import dev.pbou.stt.data.AppSettings
import dev.pbou.stt.data.Format
import dev.pbou.stt.engine.ModelCatalog
import dev.pbou.stt.ui.components.SectionHeader
import dev.pbou.stt.ui.languageName
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class SettingsViewModel(private val graph: AppGraph) : ViewModel() {
    val settings = graph.settings

    fun update(transform: (AppSettings) -> AppSettings) {
        val before = graph.settings.value ?: return
        val after = transform(before)
        viewModelScope.launch {
            graph.settingsRepo.update(transform)
            // Turning "keep loaded" off frees the memory right away.
            if (before.keepLoaded && !after.keepLoaded && !graph.live.isRunning) graph.host.release()
        }
    }
}

@Composable
fun SettingsScreen(vm: SettingsViewModel, onLicenses: () -> Unit) {
    val s = vm.settings.collectAsStateWithLifecycle().value ?: return
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    var languageDialog by rememberSaveable { mutableStateOf(false) }

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
            item { SectionHeader(stringResource(R.string.settings_transcription)) }
            item {
                Item(
                    title = stringResource(R.string.setting_language),
                    summary = languageName(s.language),
                    icon = R.drawable.ic_language,
                    onClick = { languageDialog = true },
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

            item { SectionHeader(stringResource(R.string.settings_performance)) }
            item { ThreadsSetting(s.threads) { v -> vm.update { it.copy(threads = v) } } }
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
                SwitchItem(
                    title = stringResource(R.string.setting_timings),
                    summary = stringResource(R.string.setting_timings_summary),
                    icon = R.drawable.ic_speed,
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

    if (languageDialog) {
        LanguageDialog(current = s.language, onDismiss = { languageDialog = false }) { code ->
            vm.update { it.copy(language = code) }
            languageDialog = false
        }
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
                Text(stringResource(R.string.setting_threads_summary))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    AppSettings.THREADS.forEachIndexed { i, n ->
                        SegmentedButton(
                            selected = n == value,
                            onClick = { onChange(n) },
                            shape = SegmentedButtonDefaults.itemShape(i, AppSettings.THREADS.size),
                        ) { Text(n.toString()) }
                    }
                }
            }
        },
        leadingContent = { Icon(painterResource(R.drawable.ic_bolt), contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
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
                        Text(languageName(code), style = MaterialTheme.typography.bodyLarge)
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
