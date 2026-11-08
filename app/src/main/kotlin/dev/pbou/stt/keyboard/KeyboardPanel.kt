@file:OptIn(ExperimentalLayoutApi::class)

package dev.pbou.stt.keyboard

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pbou.stt.AppGraph
import dev.pbou.stt.R
import dev.pbou.stt.engine.EngineHost
import dev.pbou.stt.engine.LiveTranscriber
import dev.pbou.stt.engine.ModelSpec
import dev.pbou.stt.ui.choosableLanguages
import dev.pbou.stt.ui.components.MicButton
import dev.pbou.stt.ui.endonym
import dev.pbou.stt.ui.languageLabel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The keyboard's panel: which model and language, what it is doing, and one row of keys around the
 * microphone. The text itself appears in the field, not here.
 */
@Composable
fun KeyboardPanel(graph: AppGraph, field: StateFlow<JacoKeyboard.Field>, actions: KeyboardActions) {
    val f by field.collectAsState()
    val settings by graph.settings.collectAsState()
    val model by graph.activeModel.collectAsState()
    val engine by graph.host.state.collectAsState()
    val live by graph.keyboardLive.state.collectAsState()
    val level by graph.keyboardLive.level.collectAsState()
    var picking by remember { mutableStateOf(false) }

    val listening = live.phase == LiveTranscriber.Phase.LISTENING || live.phase == LiveTranscriber.Phase.STARTING
    val finishing = live.phase == LiveTranscriber.Phase.FINISHING
    val language = model?.let { m -> settings?.languageFor(m) }
    val choosable = model?.let(::choosableLanguages).orEmpty()

    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 10.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            model?.let { m ->
                ModelLine(m, language, canChoose = choosable.isNotEmpty(), picking = picking, onClick = { picking = !picking })
            }
            Box(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 16.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                if (picking && choosable.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                        choosable.forEach { code ->
                            FilterChip(
                                selected = code == language,
                                onClick = { picking = false; if (code != language) actions.selectLanguage(code) },
                                label = { Text(languageLabel(code, short = true)) },
                            )
                        }
                    }
                } else {
                    val status = when {
                        model == null -> stringResource(R.string.kb_no_model)
                        !f.micGranted -> stringResource(R.string.kb_no_mic)
                        live.problem == LiveTranscriber.Problem.Microphone -> stringResource(R.string.mic_unavailable)
                        live.problem is LiveTranscriber.Problem.Engine -> stringResource(R.string.status_engine_failed)
                        f.sending || finishing -> stringResource(R.string.status_finishing)
                        listening && engine is EngineHost.State.Loading -> stringResource(R.string.status_listening_loading)
                        listening -> stringResource(R.string.status_listening)
                        engine is EngineHost.State.Loading -> stringResource(R.string.status_loading, model?.name ?: "")
                        else -> stringResource(R.string.kb_idle)
                    }
                    AnimatedContent(status, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "status") { text ->
                        Text(
                            text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Key(
                    R.drawable.ic_keyboard,
                    stringResource(R.string.kb_switch),
                    onClick = actions::switchKeyboard,
                    onLongClick = actions::pickKeyboard,
                )
                RepeatKey(R.drawable.ic_backspace, stringResource(R.string.kb_delete), onPress = actions::delete)
                MicButton(listening = listening, finishing = finishing || f.sending, level = { level }, onClick = { picking = false; actions.mic() })
                Key(R.drawable.ic_space_bar, stringResource(R.string.kb_space), onClick = actions::space)
                Key(f.enter.icon, stringResource(f.enter.label), onClick = actions::enter, emphasized = f.enter != EnterKey.NEWLINE)
            }
        }
    }
}

/** "Parakeet TDT 0.6B v3 · Detects the language", or the language to tap and change. */
@Composable
private fun ModelLine(model: ModelSpec, language: String?, canChoose: Boolean, picking: Boolean, onClick: () -> Unit) {
    val lang = if (canChoose && language != ModelSpec.AUTO) language?.let { endonym(it) }.orEmpty() else stringResource(R.string.language_detected)
    val text = "${model.name} · $lang"
    val description = if (canChoose) stringResource(R.string.cd_change_language, lang) else text
    Row(
        Modifier
            .clip(MaterialTheme.shapes.small)
            .then(if (canChoose) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics { contentDescription = description }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (canChoose) {
            Spacer(Modifier.width(2.dp))
            Icon(
                painterResource(R.drawable.ic_expand_more),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp).rotate(if (picking) 180f else 0f),
            )
        }
    }
}

private val KeyShape = RoundedCornerShape(16.dp)

@Composable
private fun keyColors(emphasized: Boolean) =
    if (emphasized) MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    else MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurface

@Composable
private fun Key(icon: Int, description: String, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, emphasized: Boolean = false) {
    val view = LocalView.current
    val (container, content) = keyColors(emphasized)
    Surface(color = container, contentColor = content, shape = KeyShape, modifier = Modifier.size(width = 56.dp, height = 48.dp)) {
        Box(
            Modifier
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = null,
                    onLongClick = onLongClick?.let { l -> { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); l() } },
                    onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onClick() },
                )
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), contentDescription = null)
        }
    }
}

/** A key that repeats while held, like delete on any keyboard. */
@Composable
private fun RepeatKey(icon: Int, description: String, onPress: () -> Unit) {
    val view = LocalView.current
    val (container, content) = keyColors(false)
    Surface(color = container, contentColor = content, shape = KeyShape, modifier = Modifier.size(width = 56.dp, height = 48.dp)) {
        Box(
            Modifier
                .semantics {
                    contentDescription = description
                    role = Role.Button
                    onClick { onPress(); true }
                }
                .pointerInput(Unit) {
                    coroutineScope {
                        awaitEachGesture {
                            awaitFirstDown()
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            onPress()
                            val repeat = launch {
                                delay(REPEAT_START_MS)
                                while (isActive) {
                                    onPress()
                                    delay(REPEAT_EVERY_MS)
                                }
                            }
                            waitForUpOrCancellation()
                            repeat.cancel()
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), contentDescription = null)
        }
    }
}

private const val REPEAT_START_MS = 450L
private const val REPEAT_EVERY_MS = 60L

private val EnterKey.icon
    get() = when (this) {
        EnterKey.NEWLINE -> R.drawable.ic_keyboard_return
        EnterKey.SEND -> R.drawable.ic_send
        EnterKey.SEARCH -> R.drawable.ic_search
        EnterKey.GO, EnterKey.NEXT -> R.drawable.ic_arrow_forward
        EnterKey.DONE -> R.drawable.ic_check
    }

private val EnterKey.label
    get() = when (this) {
        EnterKey.NEWLINE -> R.string.kb_enter
        EnterKey.SEND -> R.string.kb_send
        EnterKey.SEARCH -> R.string.kb_search
        EnterKey.GO -> R.string.kb_go
        EnterKey.NEXT -> R.string.kb_next
        EnterKey.DONE -> R.string.kb_done
    }
