package dev.pbou.stt.data

import android.content.Context
import android.os.LocaleList
import dev.pbou.stt.engine.Cpu
import dev.pbou.stt.engine.Languages
import dev.pbou.stt.engine.ModelSpec
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class AppSettings(
    /** The model chosen by the user; if it is not installed, the first installed model is used. */
    val defaultModelId: String? = null,
    /**
     * The language spoken (ISO 639-1), for the models that must be told it. Models that detect the
     * language by themselves ignore it. There is no "detect" choice: it would only mean "English" for
     * the models that cannot detect.
     */
    val language: String = "en",
    /**
     * Models that can either detect the language or be told it (Whisper) detect it. Models that must be
     * told it always use [language]; models that only detect ignore both.
     */
    val autoDetect: Boolean = true,
    /** Silence that ends a sentence. */
    val pauseSeconds: Float = 0.6f,
    val livePreview: Boolean = true,
    /** Threads the model uses; null until chosen, meaning [recommendedThreads]. */
    val threads: Int? = null,
    /** Keep the model in memory between dictations, so the next one starts instantly. */
    val keepLoaded: Boolean = true,
    val wifiOnly: Boolean = true,
    val showTimings: Boolean = false,
    /** The keyboard starts listening as soon as it opens. */
    val keyboardAutoStart: Boolean = true,
) {
    /** The threads to use: the chosen number, else the phone's fast cores. */
    val effectiveThreads: Int get() = threads ?: recommendedThreads

    /** The language to ask [spec] for: [ModelSpec.AUTO] or a code. */
    fun languageFor(spec: ModelSpec): String =
        if (autoDetect && spec.languages is Languages.ChooseOrAuto) ModelSpec.AUTO else language

    /** The settings after choosing [code] ([ModelSpec.AUTO] or a language) while [spec] is selected. */
    fun withLanguage(code: String, spec: ModelSpec?): AppSettings = when {
        code == ModelSpec.AUTO -> copy(autoDetect = true)
        // Picking a language for a model that could detect means "do not detect".
        spec?.languages is Languages.ChooseOrAuto -> copy(language = code, autoDetect = false)
        else -> copy(language = code)
    }

    companion object {
        val LANGUAGES = listOf("fr", "en", "de", "es", "it", "pt", "nl")

        /** The phone's first language the app offers, else English. */
        fun defaultLanguage(): String {
            val list = LocaleList.getDefault()
            return (0 until list.size()).map { list[it].language }.firstOrNull { it in LANGUAGES } ?: "en"
        }
        /** Every core can be chosen. */
        val THREADS: List<Int> get() = (1..Cpu.cores).toList()

        /** Likely the fastest: one thread per fast core (see [Cpu]). */
        val recommendedThreads: Int get() = Cpu.fastCores
        const val PAUSE_MIN = 0.3f
        const val PAUSE_MAX = 1.5f
    }
}

private val Context.dataStore by preferencesDataStore("settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val model = stringPreferencesKey("default_model")
        val language = stringPreferencesKey("language")
        val autoDetect = booleanPreferencesKey("auto_detect")
        val pause = floatPreferencesKey("pause_seconds")
        val preview = booleanPreferencesKey("live_preview")
        val threads = intPreferencesKey("threads")
        val keepLoaded = booleanPreferencesKey("keep_loaded")
        val wifiOnly = booleanPreferencesKey("wifi_only")
        val timings = booleanPreferencesKey("show_timings")
        val keyboardAutoStart = booleanPreferencesKey("keyboard_auto_start")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map(::read)

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val s = transform(read(prefs))
            if (s.defaultModelId == null) prefs.remove(Keys.model) else prefs[Keys.model] = s.defaultModelId
            prefs[Keys.language] = s.language
            prefs[Keys.autoDetect] = s.autoDetect
            prefs[Keys.pause] = s.pauseSeconds
            prefs[Keys.preview] = s.livePreview
            if (s.threads == null) prefs.remove(Keys.threads) else prefs[Keys.threads] = s.threads
            prefs[Keys.keepLoaded] = s.keepLoaded
            prefs[Keys.wifiOnly] = s.wifiOnly
            prefs[Keys.timings] = s.showTimings
            prefs[Keys.keyboardAutoStart] = s.keyboardAutoStart
        }
    }

    private fun read(p: Preferences): AppSettings {
        val d = AppSettings(language = AppSettings.defaultLanguage())
        return AppSettings(
            defaultModelId = p[Keys.model],
            // A former "auto" becomes the default too.
            language = p[Keys.language]?.takeIf { it in AppSettings.LANGUAGES } ?: d.language,
            autoDetect = p[Keys.autoDetect] ?: d.autoDetect,
            pauseSeconds = (p[Keys.pause] ?: d.pauseSeconds).coerceIn(AppSettings.PAUSE_MIN, AppSettings.PAUSE_MAX),
            livePreview = p[Keys.preview] ?: d.livePreview,
            threads = p[Keys.threads]?.takeIf { it in AppSettings.THREADS } ?: d.threads,
            keepLoaded = p[Keys.keepLoaded] ?: d.keepLoaded,
            wifiOnly = p[Keys.wifiOnly] ?: d.wifiOnly,
            showTimings = p[Keys.timings] ?: d.showTimings,
            keyboardAutoStart = p[Keys.keyboardAutoStart] ?: d.keyboardAutoStart,
        )
    }
}
