package dev.pbou.stt.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import dev.pbou.stt.R
import dev.pbou.stt.data.AppSettings
import dev.pbou.stt.data.Format
import dev.pbou.stt.engine.Bench
import dev.pbou.stt.engine.Languages
import dev.pbou.stt.engine.ModelCatalog
import dev.pbou.stt.engine.ModelSpec
import java.util.Locale
import kotlin.math.roundToLong

/** Turns benchmark numbers and catalog facts into words people can compare. */
object ModelInfo {

    @StringRes
    fun description(spec: ModelSpec): Int = when (spec.id) {
        ModelCatalog.PARAKEET.id -> R.string.model_desc_parakeet
        ModelCatalog.CANARY.id -> R.string.model_desc_canary
        ModelCatalog.WHISPER_TURBO.id -> R.string.model_desc_whisper_turbo
        ModelCatalog.WHISPER_SMALL.id -> R.string.model_desc_whisper_small
        ModelCatalog.QWEN3.id -> R.string.model_desc_qwen3
        ModelCatalog.OMNILINGUAL.id -> R.string.model_desc_omnilingual
        ModelCatalog.COHERE.id -> R.string.model_desc_cohere
        else -> R.string.model_desc_generic
    }

    /** 1 to 5, from the word error rate in French. */
    fun accuracy(b: Bench): Int = when {
        b.werPercent <= 6 -> 5
        b.werPercent <= 8 -> 4
        b.werPercent <= 12 -> 3
        b.werPercent <= 16 -> 2
        else -> 1
    }

    /** 1 to 5, from the real-time factor on the reference phone. */
    fun speed(b: Bench): Int = when {
        b.phoneRtf <= 0.12 -> 5
        b.phoneRtf <= 0.25 -> 4
        b.phoneRtf <= 0.6 -> 3
        b.phoneRtf <= 1.0 -> 2
        else -> 1
    }

    @StringRes
    fun accuracyLabel(level: Int) = when (level) {
        5 -> R.string.accuracy_5
        4 -> R.string.accuracy_4
        3 -> R.string.accuracy_3
        2 -> R.string.accuracy_2
        else -> R.string.accuracy_1
    }

    @StringRes
    fun speedLabel(level: Int) = when (level) {
        5 -> R.string.speed_5
        4 -> R.string.speed_4
        3 -> R.string.speed_3
        2 -> R.string.speed_2
        else -> R.string.speed_1
    }

    /** Length of a typical dictated sentence, for the wait estimate. */
    private const val TYPICAL_SENTENCE_MS = 6_000

    /**
     * When you pause, live dictation transcribes the whole sentence once more: the text appears after
     * that decode. Estimated from the benchmark's real-time factor for a typical sentence.
     */
    fun estimatedWaitMs(b: Bench): Long = (b.phoneRtf * TYPICAL_SENTENCE_MS).roundToLong()

    /** Beyond this wait, dictation feels sluggish: the estimate is shown as a warning. */
    const val LONG_WAIT_MS = 2_000L
}

/** "11" or "1.4" (times faster than real time), or null when slower than real time. */
fun speedFactor(rtf: Double): String? {
    if (rtf <= 0 || rtf >= 1.0) return null
    val factor = 1 / rtf
    // "11×", but "1.4×" rather than a misleading "1×" for slow models.
    return if (factor >= 10) factor.roundToLong().toString() else Format.decimal(factor)
}

/** Milliseconds as seconds with one decimal ("0.4"). */
fun seconds(ms: Long): String = Format.decimal(ms / 1000.0)

@Composable
fun languagesText(spec: ModelSpec): String {
    val count = Format.integer(spec.languages.count.toLong())
    return when (spec.languages) {
        is Languages.Auto -> stringResource(R.string.languages_auto, count)
        is Languages.Choose, is Languages.ChooseOrAuto -> stringResource(R.string.languages_count, count)
    }
}

/**
 * The languages the app lets you give [spec]: none for models that detect it by themselves, else
 * those of the app's list the model knows, plus "auto" for models that can also detect it.
 */
fun choosableLanguages(spec: ModelSpec): List<String> = when (val l = spec.languages) {
    is Languages.Auto -> emptyList()
    is Languages.Choose -> AppSettings.LANGUAGES.filter { it in l.codes }
    is Languages.ChooseOrAuto -> listOf(ModelSpec.AUTO) + AppSettings.LANGUAGES
}

/** "Detect automatically" (or "Auto" when [short]), else the language's own name ("Français"). */
@Composable
fun languageLabel(code: String, short: Boolean = false): String = when {
    code != ModelSpec.AUTO -> endonym(code)
    short -> stringResource(R.string.language_auto_short)
    else -> stringResource(R.string.language_auto)
}

fun endonym(code: String): String {
    val locale = Locale.forLanguageTag(code)
    return locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
}

@Composable
fun benchDetail(b: Bench): String {
    val wer = Format.decimal(b.werPercent)
    val factor = speedFactor(b.phoneRtf)
    return if (factor == null) stringResource(R.string.bench_detail_slow, wer) else stringResource(R.string.bench_detail, wer, factor)
}

@Composable
fun bytes(n: Long): String = Format.bytes(LocalContext.current, n)

/** Five small segments, [level] of them lit. Read by screen readers through the label next to it. */
@Composable
fun Meter(level: Int, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Row(modifier.clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(5) { i ->
            Box(
                Modifier
                    .size(width = 14.dp, height = 6.dp)
                    .background(if (i < level) color else MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(3.dp)),
            )
        }
    }
}
