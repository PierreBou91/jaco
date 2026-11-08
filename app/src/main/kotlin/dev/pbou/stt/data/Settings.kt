package dev.pbou.stt.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.pbou.stt.engine.ModelSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class AppSettings(
    /** The model chosen by the user; if it is not installed, the first installed model is used. */
    val defaultModelId: String? = null,
    /** "auto" or an ISO 639-1 code. */
    val language: String = ModelSpec.AUTO,
    /** Silence that ends a sentence. */
    val pauseSeconds: Float = 0.6f,
    val livePreview: Boolean = true,
    val threads: Int = 4,
    /** Keep the model in memory between dictations, so the next one starts instantly. */
    val keepLoaded: Boolean = true,
    val wifiOnly: Boolean = true,
    val showTimings: Boolean = false,
) {
    companion object {
        val LANGUAGES = listOf(ModelSpec.AUTO, "fr", "en", "de", "es", "it", "pt", "nl")
        val THREADS = listOf(2, 4, 6, 8)
        const val PAUSE_MIN = 0.3f
        const val PAUSE_MAX = 1.5f
    }
}

private val Context.dataStore by preferencesDataStore("settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val model = stringPreferencesKey("default_model")
        val language = stringPreferencesKey("language")
        val pause = floatPreferencesKey("pause_seconds")
        val preview = booleanPreferencesKey("live_preview")
        val threads = intPreferencesKey("threads")
        val keepLoaded = booleanPreferencesKey("keep_loaded")
        val wifiOnly = booleanPreferencesKey("wifi_only")
        val timings = booleanPreferencesKey("show_timings")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map(::read)

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val s = transform(read(prefs))
            if (s.defaultModelId == null) prefs.remove(Keys.model) else prefs[Keys.model] = s.defaultModelId
            prefs[Keys.language] = s.language
            prefs[Keys.pause] = s.pauseSeconds
            prefs[Keys.preview] = s.livePreview
            prefs[Keys.threads] = s.threads
            prefs[Keys.keepLoaded] = s.keepLoaded
            prefs[Keys.wifiOnly] = s.wifiOnly
            prefs[Keys.timings] = s.showTimings
        }
    }

    private fun read(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            defaultModelId = p[Keys.model],
            language = p[Keys.language]?.takeIf { it in AppSettings.LANGUAGES } ?: d.language,
            pauseSeconds = (p[Keys.pause] ?: d.pauseSeconds).coerceIn(AppSettings.PAUSE_MIN, AppSettings.PAUSE_MAX),
            livePreview = p[Keys.preview] ?: d.livePreview,
            threads = p[Keys.threads]?.takeIf { it in AppSettings.THREADS } ?: d.threads,
            keepLoaded = p[Keys.keepLoaded] ?: d.keepLoaded,
            wifiOnly = p[Keys.wifiOnly] ?: d.wifiOnly,
            showTimings = p[Keys.timings] ?: d.showTimings,
        )
    }
}
