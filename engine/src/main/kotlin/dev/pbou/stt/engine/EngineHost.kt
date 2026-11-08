package dev.pbou.stt.engine

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Keeps at most one model loaded ("warm"), so dictation starts instantly, and runs every call into
 * the native engine on a single dedicated thread. Loading a different model or options closes the
 * previous one first, so two large models are never in memory together.
 *
 * Shared by everything that transcribes (the app today, the keyboard later).
 */
class EngineHost(private val store: ModelStore) {

    sealed interface State {
        data object Empty : State
        data class Loading(val spec: ModelSpec) : State
        data class Ready(val spec: ModelSpec, val options: EngineOptions, val loadMs: Long) : State
        data class Failed(val spec: ModelSpec, val message: String) : State
    }

    private val thread: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "stt-engine") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + thread)

    private var engine: SpeechEngine? = null
    private val _state = MutableStateFlow<State>(State.Empty)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Runs [block] with [spec] loaded with [options], loading it first if needed. */
    suspend fun <T> use(spec: ModelSpec, options: EngineOptions, block: (SpeechEngine) -> T): T =
        withContext(thread) { block(load(spec, options)) }

    /** Starts loading in the background, e.g. when the dictation screen opens. */
    fun preload(spec: ModelSpec, options: EngineOptions): Job = scope.launch {
        runCatching { load(spec, options) }
    }

    /** Frees the model's memory. */
    fun release(): Job = scope.launch {
        engine?.close()
        engine = null
        _state.value = State.Empty
    }

    /** Must run on [thread]. */
    private fun load(spec: ModelSpec, options: EngineOptions): SpeechEngine {
        engine?.let { if (it.spec == spec && it.options == options) return it }
        engine?.close()
        engine = null
        _state.value = State.Loading(spec)
        val t0 = SystemClock.elapsedRealtime()
        return try {
            if (!store.isInstalled(spec)) throw IllegalStateException("${spec.name} is not installed")
            SherpaEngine(spec, store.dir(spec), options).also {
                engine = it
                _state.value = State.Ready(spec, options, SystemClock.elapsedRealtime() - t0)
            }
        } catch (t: Throwable) {
            _state.value = State.Failed(spec, t.message ?: t.javaClass.simpleName)
            throw t
        }
    }
}
