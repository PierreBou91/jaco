package dev.pbou.stt.data

import dev.pbou.stt.engine.Benchmark
import dev.pbou.stt.engine.Wav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The sentences people read aloud for the benchmark: everyday speech, numbers spelled out nowhere. */
object BenchTexts {
    val LANGUAGES = listOf("fr", "en")

    fun sentences(language: String): List<String> = when (language) {
        "fr" -> listOf(
            "Je passerai à la boulangerie en rentrant, tu veux du pain complet ou une baguette ?",
            "La réunion de jeudi est déplacée à la semaine prochaine, Claire doit encore confirmer la salle.",
            "Il a plu toute la matinée, mais le ciel s'est dégagé juste avant le départ du train.",
            "N'oublie pas d'arroser les plantes du balcon et de fermer les volets si tu pars samedi.",
            "Le musée propose une visite guidée en anglais tous les samedis, il vaut mieux réserver à l'avance.",
            "Après des années à Lyon, mes parents se sont installés dans un petit village près de la mer.",
        )
        else -> listOf(
            "I'll stop by the bakery on the way home, do you want brown bread or a baguette?",
            "Thursday's meeting has been moved to next week, and Claire still needs to confirm the room.",
            "It rained all morning, but the sky cleared up just before the train left the station.",
            "Don't forget to water the plants on the balcony and close the shutters if you leave on Saturday.",
            "The museum runs a guided tour in French every Saturday, so it's best to book in advance.",
            "After years in Lyon, my parents settled in a small village by the sea.",
        )
    }
}

/**
 * The benchmark's recordings (WAV files, one per sentence and language) and the latest result of
 * each model for each language. Everything stays in the app's private storage.
 */
class BenchStore(root: File) {
    private val clips = File(root, "clips")
    private val resultsFile = File(root, "results.json")

    private val _recorded = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** "fr/0" -> duration in ms, for every recorded sentence. */
    val recorded: StateFlow<Map<String, Long>> = _recorded.asStateFlow()

    private val _results = MutableStateFlow<List<Benchmark.Result>>(emptyList())
    /** The latest result per model and language, newest first. */
    val results: StateFlow<List<Benchmark.Result>> = _results.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        _recorded.value = scanClips()
        _results.value = runCatching { parse(JSONArray(resultsFile.readText())) }.getOrDefault(emptyList())
    }

    fun key(language: String, index: Int) = "$language/$index"

    private fun clipFile(language: String, index: Int) = File(clips, "$language-$index.wav")

    suspend fun saveClip(language: String, index: Int, samples: FloatArray) = withContext(Dispatchers.IO) {
        Wav.write(clipFile(language, index), samples)
        _recorded.value = scanClips()
    }

    suspend fun deleteClips(language: String) = withContext(Dispatchers.IO) {
        clips.listFiles { f -> f.name.startsWith("$language-") }?.forEach { it.delete() }
        _recorded.value = scanClips()
    }

    /** The recorded sentences of [language], in order, with their reference text. */
    suspend fun clips(language: String): List<Benchmark.Clip> = withContext(Dispatchers.IO) {
        BenchTexts.sentences(language).mapIndexedNotNull { i, text ->
            clipFile(language, i).takeIf { it.exists() }?.let { Benchmark.Clip(text, Wav.read(it)) }
        }
    }

    suspend fun saveResult(result: Benchmark.Result) = withContext(Dispatchers.IO) {
        val list = listOf(result) + _results.value.filterNot { it.modelId == result.modelId && it.textLanguage == result.textLanguage }
        resultsFile.parentFile?.mkdirs()
        val tmp = File(resultsFile.path + ".tmp")
        tmp.writeText(JSONArray(list.map(::toJson)).toString())
        tmp.renameTo(resultsFile)
        _results.value = list
    }

    private fun scanClips(): Map<String, Long> =
        clips.listFiles { f -> f.name.endsWith(".wav") }.orEmpty().associate { f ->
            f.nameWithoutExtension.replace('-', '/') to (f.length() - 44) * 1000 / (2L * 16_000)
        }

    private fun toJson(r: Benchmark.Result) = JSONObject()
        .put("model", r.modelId).put("textLanguage", r.textLanguage).put("modelLanguage", r.modelLanguage ?: "").put("threads", r.threads)
        .put("loadMs", r.loadMs).put("finishedAt", r.finishedAt)
        .put("sentences", JSONArray(r.sentences.map {
            JSONObject().put("reference", it.reference).put("text", it.text).put("audioMs", it.audioMs).put("decodeMs", it.decodeMs)
        }))

    private fun parse(a: JSONArray): List<Benchmark.Result> = (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        val s = o.getJSONArray("sentences")
        Benchmark.Result(
            modelId = o.getString("model"),
            textLanguage = o.getString("textLanguage"),
            modelLanguage = o.optString("modelLanguage").takeIf { it.isNotEmpty() },
            threads = o.getInt("threads"),
            loadMs = o.getLong("loadMs"),
            finishedAt = o.getLong("finishedAt"),
            sentences = (0 until s.length()).map { j ->
                val x = s.getJSONObject(j)
                Benchmark.Sentence(x.getString("reference"), x.getString("text"), x.getLong("audioMs"), x.getLong("decodeMs"))
            },
        )
    }
}
