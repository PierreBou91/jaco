package dev.pbou.stt.keyboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.pbou.stt.MainActivity
import dev.pbou.stt.SttApplication
import dev.pbou.stt.engine.LiveTranscriber
import dev.pbou.stt.ui.theme.SttTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Jaco's keyboard: a voice-only keyboard. Open it from any text field (the keyboard switch button), speak,
 * and the text goes into the field: the sentence being spoken shows underlined (composing text) and is
 * fixed in place at each pause. It has no letter keys: switch back to your usual keyboard to type.
 *
 * Runs in the app's process and shares its engine, so a model loaded by the app is ready here too.
 */
class JacoKeyboard : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    private val graph get() = (application as SttApplication).graph
    private val live get() = graph.keyboardLive

    /** What the panel shows that depends on the text field and on this service, not on the engine. */
    data class Field(
        val enter: EnterKey = EnterKey.NEWLINE,
        val micGranted: Boolean = true,
        /** Enter was pressed while the last sentence was still being transcribed. */
        val sending: Boolean = false,
    )

    private val _field = MutableStateFlow(Field())

    // Text insertion state, main thread only.
    private var inputActive = false
    private var committed = 0     // segments of the live state already in the field
    private var composing = false // a preview is shown as composing text
    private var prefix = ""       // the space put before the sentence being previewed
    private var shown = ""        // the composing text as put in the field: prefix and preview
    // The app took the sentence being previewed out of the field (WhatsApp's send button sends it and
    // empties the field): the rest of that sentence, and its final text, must not be typed again.
    private var skipping = false
    // A new field opened while the last dictation was still finishing: its text belongs to the old one.
    private var dropUntilIdle = false
    private var startWhenGranted = false
    private var idleRelease: Job? = null

    override fun onCreate() {
        super.onCreate()
        savedState.performAttach()
        savedState.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        lifecycleScope.launch { live.state.collect(::sync) }
    }

    override fun onDestroy() {
        live.stop()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        // Compose finds its lifecycle and saved state through the view tree, from the window's root.
        window.window?.decorView?.let {
            it.setViewTreeLifecycleOwner(this)
            it.setViewTreeSavedStateRegistryOwner(this)
        }
        return ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@JacoKeyboard)
            setViewTreeSavedStateRegistryOwner(this@JacoKeyboard)
            setContent {
                SttTheme {
                    KeyboardPanel(
                        graph = graph,
                        field = _field.asStateFlow(),
                        actions = actions,
                    )
                }
            }
        }
    }

    /** A voice panel is short: never take the whole screen in landscape. */
    override fun onEvaluateFullscreenMode() = false

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        // Just chosen from the Control Centre button: open in the field without a second tap on it.
        if (attribute.inputType != InputType.TYPE_NULL && KeyboardSwitch.takeShowRequest()) requestShowSelf(0)
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        idleRelease?.cancel()
        inputActive = true
        committed = live.state.value.segments.size
        if (restarting) {
            // The app reset its field (some do after sending): the editor forgot our composing text.
            if (composing) reclaimPreview()
        } else {
            composing = false
            skipping = false
            dropUntilIdle = live.isRunning
        }
        val granted = micGranted()
        _field.value = Field(enter = EnterKey.of(info), micGranted = granted)
        val autoStart = graph.settings.value?.keyboardAutoStart ?: true
        if (granted && (startWhenGranted || (!restarting && autoStart && !isPassword(info)))) startListening()
        startWhenGranted = false
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // The microphone is never on while the keyboard is hidden. What was said is still typed in.
        live.stop()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        inputActive = false
        composing = false
        super.onFinishInput()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        idleRelease?.cancel()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        // A loaded model takes hundreds of megabytes, and a keyboard process lives all day: free it a
        // while after the keyboard was last used, unless the app is using it.
        idleRelease?.cancel()
        idleRelease = lifecycleScope.launch {
            delay(IDLE_RELEASE_MS)
            if (!graph.engineBusy() && graph.improver.progress.value.isEmpty()) graph.host.release()
        }
    }

    /** Types the new sentences and the preview of the current one into the field. */
    private fun sync(s: LiveTranscriber.State) {
        if (s.segments.size < committed) committed = s.segments.size
        val ic = currentInputConnection
        if (!inputActive || ic == null || dropUntilIdle) {
            committed = s.segments.size
            composing = false
            if (s.phase == LiveTranscriber.Phase.IDLE) dropUntilIdle = false
            return
        }
        ic.beginBatchEdit()
        // Before replacing the preview, make sure it is still there: the app may have sent it.
        if (composing && ic.getTextBeforeCursor(shown.length, 0)?.toString() != shown) {
            ic.finishComposingText()
            composing = false
            skipping = true
        }
        val newSegments = committed < s.segments.size
        while (committed < s.segments.size) {
            val text = s.segments[committed++].text
            if (skipping) {
                skipping = false // that was the sentence already sent
                continue
            }
            val space = if (composing) prefix else Spacing.before(ic.getTextBeforeCursor(1, 0), text)
            ic.commitText(space + text, 1)
            composing = false
        }
        // The sentence ended as silence, or the dictation is over: nothing left to hold back.
        if (skipping && ((s.partial.isBlank() && !newSegments) || s.phase == LiveTranscriber.Phase.IDLE)) skipping = false
        if (skipping) {
            // Still the sentence that was sent: do not show it again.
        } else if (s.partial.isNotBlank()) {
            if (!composing) {
                prefix = Spacing.before(ic.getTextBeforeCursor(1, 0), s.partial)
                composing = true
            }
            shown = prefix + s.partial
            ic.setComposingText(shown, 1)
        } else if (composing) {
            // The sentence turned out to be silence: remove its preview.
            ic.setComposingText("", 1)
            ic.finishComposingText()
            composing = false
        }
        ic.endBatchEdit()
    }

    /**
     * After the app restarted its field: if our preview is still in it, take it out (it is plain text now)
     * so the next update shows it again as a preview; if it is gone, the app sent it.
     */
    private fun reclaimPreview() {
        composing = false
        val ic = currentInputConnection ?: return
        if (ic.getTextBeforeCursor(shown.length, 0)?.toString() == shown) ic.deleteSurroundingText(shown.length, 0)
        else skipping = true
    }

    private fun startListening() {
        val s = graph.settings.value ?: return
        val model = graph.activeModel.value ?: return
        if (live.isRunning) return
        graph.live.stop() // the app's dictation, if any: one microphone
        live.dismissProblem()
        live.clear() // what earlier dictations typed is in the field already
        committed = 0
        skipping = false
        dropUntilIdle = false
        live.start(
            lifecycleScope,
            LiveTranscriber.Config(
                spec = model,
                options = graph.options(model, s),
                pauseSeconds = s.pauseSeconds,
                partials = s.livePreview,
                releaseAfter = !s.keepLoaded && !graph.recorder.isActive, // a recording uses the same model
            ),
        )
    }

    private fun micGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun isPassword(info: EditorInfo): Boolean {
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return when (info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    private fun openApp(intent: Intent) = startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private val actions = object : KeyboardActions {
        override fun mic() {
            when {
                live.isRunning -> live.stop()
                !micGranted() -> {
                    startWhenGranted = true
                    openApp(Intent(this@JacoKeyboard, MicPermissionActivity::class.java))
                }
                graph.activeModel.value == null -> openApp(Intent(this@JacoKeyboard, MainActivity::class.java))
                else -> startListening()
            }
        }

        override fun delete() {
            val ic = currentInputConnection ?: return
            if (composing) {
                // Delete during a sentence throws that sentence away.
                ic.setComposingText("", 1)
                ic.finishComposingText()
                composing = false
                skipping = true
                return
            }
            if (!ic.getSelectedText(0).isNullOrEmpty()) ic.commitText("", 1) else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }

        override fun space() {
            // During a sentence a space would replace its preview; the pause puts one anyway.
            if (!composing) currentInputConnection?.commitText(" ", 1)
        }

        override fun enter() {
            if (!live.isRunning) {
                sendKeyChar('\n') // the field's action (send, search...) or a new line
                return
            }
            // Speak, then tap send: wait for the last sentence to be typed before sending.
            _field.update { it.copy(sending = true) }
            live.stop()
            lifecycleScope.launch {
                withTimeoutOrNull(SEND_TIMEOUT_MS) { live.state.first { it.phase == LiveTranscriber.Phase.IDLE } }
                sync(live.state.value)
                _field.update { it.copy(sending = false) }
                if (inputActive) sendKeyChar('\n')
            }
        }

        override fun switchKeyboard() {
            // Back to the keyboard Jaco was opened from, else the system's idea of the previous one.
            val previous = KeyboardSwitch.previous(this@JacoKeyboard)
            if (previous != null) switchInputMethod(previous)
            else if (!switchToPreviousInputMethod()) pickKeyboard()
        }

        override fun pickKeyboard() {
            getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }

        override fun selectLanguage(code: String) {
            val wasRunning = live.isRunning
            live.stop()
            lifecycleScope.launch {
                val model = graph.activeModel.value
                graph.settingsRepo.update { it.withLanguage(code, model) }
                graph.settings.filterNotNull().first { s -> model == null || s.languageFor(model) == code }
                if (wasRunning) {
                    live.state.first { it.phase == LiveTranscriber.Phase.IDLE }
                    startListening()
                }
            }
        }
    }

    private companion object {
        const val IDLE_RELEASE_MS = 5 * 60_000L
        const val SEND_TIMEOUT_MS = 15_000L
    }
}

/** What the panel's keys do. */
interface KeyboardActions {
    fun mic()
    fun delete()
    fun space()
    fun enter()
    fun switchKeyboard()
    fun pickKeyboard()
    fun selectLanguage(code: String)
}

/** The enter key's look follows what the field does with it. */
enum class EnterKey {
    NEWLINE, SEND, SEARCH, GO, NEXT, DONE;

    companion object {
        fun of(info: EditorInfo): EnterKey {
            if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return NEWLINE
            return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_SEND -> SEND
                EditorInfo.IME_ACTION_SEARCH -> SEARCH
                EditorInfo.IME_ACTION_GO -> GO
                EditorInfo.IME_ACTION_NEXT -> NEXT
                EditorInfo.IME_ACTION_DONE -> DONE
                else -> NEWLINE
            }
        }
    }
}

/** Where to put a space between what is already in the field and dictated text. */
object Spacing {
    private const val NO_SPACE_AFTER = "([{\"'«“‘/-\n"
    private const val NO_SPACE_BEFORE = ".,;:!?)]}»”’…"

    fun before(textBefore: CharSequence?, text: String): String {
        val last = textBefore?.lastOrNull() ?: return ""
        val first = text.firstOrNull() ?: return ""
        return if (last.isWhitespace() || last in NO_SPACE_AFTER || first in NO_SPACE_BEFORE) "" else " "
    }
}
