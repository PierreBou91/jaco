@file:OptIn(ExperimentalMaterial3Api::class)

package dev.pbou.stt.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pbou.stt.R
import dev.pbou.stt.data.LibraryItem
import dev.pbou.stt.data.LibraryText
import dev.pbou.stt.engine.ModelCatalog
import dev.pbou.stt.engine.RecordingSession
import dev.pbou.stt.ui.bytes
import dev.pbou.stt.ui.components.IconTile

@Composable
fun LibraryScreen(vm: LibraryViewModel, onOpen: (String) -> Unit, onFile: () -> Unit, onManageModels: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_library)) },
                actions = {
                    IconButton(onClick = onFile) {
                        Icon(painterResource(R.drawable.ic_audio_file), contentDescription = stringResource(R.string.lib_transcribe_file))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (ui.empty) {
                Empty(hasModel = ui.hasModel, onFile = onFile, onManageModels = onManageModels)
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    ui.recording?.let { r ->
                        item(key = "recording") { RecordingCard(r, onClick = { onOpen(r.itemId) }) }
                    }
                    item(key = "search") {
                        OutlinedTextField(
                            value = query,
                            onValueChange = vm::search,
                            placeholder = { Text(stringResource(R.string.lib_search)) },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                            trailingIcon = {
                                if (query.isNotEmpty()) IconButton(onClick = { vm.search("") }) {
                                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.action_clear))
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(28.dp),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        // Audio is kept by default: show what it costs (an item's menu can delete it).
                        if (ui.audioBytes > 0) Text(
                            stringResource(R.string.lib_audio_space, bytes(ui.audioBytes)),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                    }
                    if (ui.rows.isEmpty()) item(key = "none") {
                        Text(
                            stringResource(R.string.lib_no_match, query),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                        )
                    }
                    items(ui.rows, key = { it.hit.item.id }) { row -> ItemRow(row, onClick = { onOpen(row.hit.item.id) }) }
                }
            }
        }
    }
}

@Composable
private fun RecordingCard(r: LibraryUi.Recording, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(MaterialTheme.shapes.large).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(
                        when (r.phase) {
                            RecordingSession.Phase.PAUSED -> R.string.rec_paused
                            RecordingSession.Phase.FINISHING -> R.string.rec_finishing
                            else -> R.string.rec_recording
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(stringResource(R.string.lib_recording_open), style = MaterialTheme.typography.bodySmall)
            }
            Text(LibraryText.clock(r.recordedMs), style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"))
        }
    }
}

@Composable
private fun ItemRow(row: LibraryUi.Row, onClick: () -> Unit) {
    val item = row.hit.item
    val context = LocalContext.current
    ListItem(
        headlineContent = { Text(itemTitle(context, item), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(
                    listOfNotNull(itemDate(context, item), item.durationMs.takeIf { it > 0 }?.let { LibraryText.clock(it) }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                )
                status(item, row.improving)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                row.hit.snippet?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } ?: item.transcript?.text?.takeIf { it.isNotBlank() }?.let {
                    Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
                }
            }
        },
        leadingContent = {
            Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(if (item.kind == LibraryItem.Kind.RECORDING) R.drawable.ic_mic else R.drawable.ic_audio_file),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** "Recording...", "Improving with Cohere, 40%", "Improves with Cohere when charging", or null. */
@Composable
internal fun status(item: LibraryItem, improving: Float?): String? = when {
    item.status == LibraryItem.Status.RECORDING -> stringResource(R.string.rec_recording)
    item.status == LibraryItem.Status.TRANSCRIBING -> stringResource(R.string.lib_transcribing)
    improving != null -> stringResource(R.string.improve_running, modelName(item.improve?.modelId), (improving * 100).toInt())
    item.improve != null && item.improve.whileCharging -> stringResource(R.string.improve_waiting_charge, modelName(item.improve.modelId))
    item.improve != null -> stringResource(R.string.improve_waiting, modelName(item.improve.modelId))
    else -> null
}

internal fun modelName(id: String?): String = ModelCatalog.byId(id)?.name ?: id.orEmpty()

@Composable
private fun Empty(hasModel: Boolean, onFile: () -> Unit, onManageModels: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconTile(R.drawable.ic_library_books)
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.lib_empty_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.lib_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        if (hasModel) {
            OutlinedButton(onClick = onFile) {
                Icon(painterResource(R.drawable.ic_audio_file), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.lib_transcribe_file))
            }
        } else {
            Button(onClick = onManageModels) { Text(stringResource(R.string.action_manage_models)) }
        }
    }
}
