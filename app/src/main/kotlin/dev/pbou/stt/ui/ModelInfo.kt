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

    /** Live dictation re-reads the sentence twice a second: beyond this it falls behind. */
    fun tooSlowForLive(spec: ModelSpec) = (spec.bench?.phoneRtf ?: 0.0) > 0.5
}

@Composable
fun languagesText(spec: ModelSpec): String {
    val count = Format.integer(spec.languages.count.toLong())
    return when (spec.languages) {
        is Languages.Auto -> stringResource(R.string.languages_auto, count)
        is Languages.Choose, is Languages.ChooseOrAuto -> stringResource(R.string.languages_count, count)
    }
}

/** "Detect automatically", or the language's own name ("Français", "Deutsch"). */
@Composable
fun languageName(code: String): String =
    if (code == ModelSpec.AUTO) stringResource(R.string.language_auto) else endonym(code)

fun endonym(code: String): String {
    val locale = Locale.forLanguageTag(code)
    return locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
}

@Composable
fun benchDetail(b: Bench): String {
    val wer = Format.decimal(b.werPercent)
    return if (b.phoneRtf >= 1.0) stringResource(R.string.bench_detail_slow, wer)
    else stringResource(R.string.bench_detail, wer, (1 / b.phoneRtf).roundToLong().toString())
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
