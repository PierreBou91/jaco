@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.pbou.stt.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pbou.stt.R
import dev.pbou.stt.engine.ModelSpec
import dev.pbou.stt.ui.ModelInfo
import dev.pbou.stt.data.Format
import dev.pbou.stt.ui.benchDetail
import dev.pbou.stt.ui.seconds
import dev.pbou.stt.ui.bytes
import dev.pbou.stt.ui.components.Badge
import dev.pbou.stt.ui.components.DownloadStatus
import dev.pbou.stt.ui.components.SectionHeader
import dev.pbou.stt.ui.dictate.Ratings
import dev.pbou.stt.ui.languagesText
import dev.pbou.stt.ui.rememberNotificationPermission
import dev.pbou.stt.ui.theme.LocalExtraColors

@Composable
fun ModelsScreen(vm: ModelsViewModel, onBenchmark: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    var toDelete by remember { mutableStateOf<ModelSpec?>(null) }
    val askNotifications = rememberNotificationPermission()
    LaunchedEffect(Unit) { vm.refreshDisk() }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_models)) },
                actions = {
                    if (ui.installed.isNotEmpty()) {
                        IconButton(onClick = onBenchmark) {
                            Icon(painterResource(R.drawable.ic_speed), contentDescription = stringResource(R.string.bench_open))
                        }
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
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 24.dp)) {
            item { StorageSummary(ui.disk) }
            val actions = CardActions(
                onDownload = { spec, mobile -> askNotifications { vm.download(spec, mobile) } },
                onPause = vm::pause,
                onDiscard = vm::discard,
                onUse = vm::use,
                onDelete = { toDelete = it },
            )
            if (ui.installed.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.models_installed)) }
                items(ui.installed, key = { it.spec.id }) { ModelCard(it, ui, actions) }
            }
            if (ui.available.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.models_available)) }
                items(ui.available, key = { it.spec.id }) { ModelCard(it, ui, actions) }
            }
            item {
                Text(
                    stringResource(R.string.models_bench_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
        }
    }

    toDelete?.let { spec ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            icon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
            title = { Text(stringResource(R.string.delete_title, spec.name)) },
            text = { Text(stringResource(R.string.delete_body, bytes(spec.installBytes))) },
            confirmButton = {
                TextButton(onClick = { vm.delete(spec); toDelete = null }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private class CardActions(
    val onDownload: (ModelSpec, Boolean) -> Unit,
    val onPause: (ModelSpec) -> Unit,
    val onDiscard: (ModelSpec) -> Unit,
    val onUse: (ModelSpec) -> Unit,
    val onDelete: (ModelSpec) -> Unit,
)

@Composable
private fun StorageSummary(disk: Disk) {
    Row(Modifier.padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_hard_drive), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(R.string.storage_summary, bytes(disk.used), bytes(disk.free)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ModelCard(item: ModelItem, ui: ModelsUi, actions: CardActions) {
    val spec = item.spec
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(spec.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${spec.maker} · ${spec.license.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (spec.recommended || item.active) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.active) {
                        Badge(stringResource(R.string.badge_in_use), MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer, R.drawable.ic_check)
                    }
                    if (spec.recommended) {
                        Badge(stringResource(R.string.badge_recommended), MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, R.drawable.ic_star_fill)
                    }
                }
            }
            Text(stringResource(ModelInfo.description(spec)), style = MaterialTheme.typography.bodyMedium)
            spec.bench?.let { b ->
                Ratings(ModelInfo.accuracy(b), ModelInfo.speed(b))
                Text(benchDetail(b), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            WaitLine(item)
            Text(
                stringResource(
                    R.string.model_facts,
                    languagesText(spec),
                    if (spec.punctuation) stringResource(R.string.fact_punctuation) else stringResource(R.string.fact_no_punctuation),
                    bytes(spec.installBytes),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            CardFooter(item, ui, actions)
        }
    }
}

/** How long the text takes to appear after each pause: measured on the user's voice if possible, else estimated. */
@Composable
private fun WaitLine(item: ModelItem) {
    val measured = item.measured
    val bench = item.spec.bench
    val waitMs = measured?.waitMs ?: bench?.let(ModelInfo::estimatedWaitMs) ?: return
    val text = if (measured != null) {
        stringResource(R.string.wait_measured, Format.decimal(measured.score.percent), seconds(measured.waitMs))
    } else {
        stringResource(R.string.wait_estimate, seconds(waitMs))
    }
    val long = waitMs > ModelInfo.LONG_WAIT_MS
    val color = if (long) LocalExtraColors.current.warning else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(if (measured != null) R.drawable.ic_speed else R.drawable.ic_timer), contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun CardFooter(item: ModelItem, ui: ModelsUi, actions: CardActions) {
    val spec = item.spec
    val download = item.download
    when {
        item.installed -> Row(verticalAlignment = Alignment.CenterVertically) {
            if (item.active) {
                Text(
                    stringResource(R.string.model_in_use_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            } else {
                OutlinedButton(onClick = { actions.onUse(spec) }) { Text(stringResource(R.string.action_use)) }
                Spacer(Modifier.weight(1f))
            }
            IconButton(onClick = { actions.onDelete(spec) }) {
                Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.cd_delete_model, spec.name))
            }
        }
        download != null -> DownloadStatus(
            download,
            onPause = { actions.onPause(spec) },
            onUseMobileData = { actions.onDownload(spec, true) },
            onRetry = { actions.onDownload(spec, false) },
        )
        item.pausedBytes > 0 -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.download_paused, bytes(item.pausedBytes), bytes(spec.downloadBytes)),
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { actions.onDownload(spec, false) }) {
                    Icon(painterResource(R.drawable.ic_play_arrow), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_resume))
                }
                TextButton(onClick = { actions.onDiscard(spec) }) { Text(stringResource(R.string.action_discard)) }
            }
        }
        else -> {
            val label = stringResource(R.string.action_download_size, bytes(spec.downloadBytes))
            val content: @Composable () -> Unit = {
                Icon(painterResource(R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(label)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (spec.recommended) Button(onClick = { actions.onDownload(spec, false) }) { content() }
                else FilledTonalButton(onClick = { actions.onDownload(spec, false) }) { content() }
                if (ui.wifiOnly && !ui.onWifi) {
                    Text(stringResource(R.string.first_run_wifi_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
