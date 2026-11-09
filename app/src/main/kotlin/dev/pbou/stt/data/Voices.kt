package dev.pbou.stt.data

import dev.pbou.stt.engine.VoiceMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * A remembered person: their name and voice (an embedding averaged over the recordings they were named
 * in). Stored on the phone only. [seconds]: speech it was learnt from, to weigh new samples.
 */
class Voice(val id: String, val name: String, val voice: FloatArray, val seconds: Float)

/** The saved voices, in `voices.json`: recognised in new recordings, see [Voices.match]. */
class Voices(private val file: File) {
    private val _all = MutableStateFlow<List<Voice>>(emptyList())
    val all: StateFlow<List<Voice>> = _all.asStateFlow()

    private val lock = Mutex()
    @Volatile private var loaded = false

    fun get(id: String?): Voice? = id?.let { i -> _all.value.firstOrNull { it.id == i } }

    fun byName(name: String): Voice? = _all.value.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    suspend fun load() = lock.withLock {
        if (loaded) return@withLock
        _all.value = withContext(Dispatchers.IO) {
            runCatching {
                val a = JSONArray(file.readText())
                (0 until a.length()).map { k ->
                    val o = a.getJSONObject(k)
                    Voice(o.getString("id"), o.getString("name"), vectorFromJson(o.getJSONArray("voice")), o.optDouble("seconds", 0.0).toFloat())
                }
            }.getOrDefault(emptyList())
        }
        loaded = true
    }

    /**
     * Remembers [voice] as [name]: teaches it to the saved voice of that name (the more it hears someone,
     * the better it knows them), or saves a new one. Returns the saved voice's id.
     */
    suspend fun remember(name: String, voice: FloatArray, seconds: Float): String = lock.withLock {
        val existing = _all.value.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
        val saved = if (existing == null) {
            Voice(UUID.randomUUID().toString().take(12), name.trim(), VoiceMath.normalize(voice), seconds)
        } else {
            // Weigh by speech heard, capped so that one long call does not drown the others.
            val old = existing.seconds.coerceIn(1f, MAX_WEIGHT_S)
            val new = seconds.coerceIn(1f, MAX_WEIGHT_S)
            Voice(existing.id, existing.name, VoiceMath.mean(listOf(existing.voice, voice), listOf(old, new)), existing.seconds + seconds)
        }
        write(_all.value.filterNot { it.id == saved.id } + saved)
        saved.id
    }

    suspend fun rename(id: String, name: String) = lock.withLock {
        write(_all.value.map { if (it.id == id) Voice(it.id, name.trim(), it.voice, it.seconds) else it })
    }

    suspend fun delete(id: String) = lock.withLock {
        write(_all.value.filterNot { it.id == id })
    }

    /**
     * Which saved voice each of [people] is, if any: the most similar above [minSimilarity], each voice
     * given to one person at most (the closest first).
     */
    fun match(people: List<FloatArray>, minSimilarity: Float): List<String?> {
        val voices = _all.value
        return VoiceMatch.assign(people, voices.map { it.voice }, minSimilarity).map { it?.let { k -> voices[k].id } }
    }

    private suspend fun write(list: List<Voice>) {
        withContext(Dispatchers.IO) {
            val a = JSONArray(list.map { JSONObject().put("id", it.id).put("name", it.name).put("seconds", it.seconds.toDouble()).put("voice", vectorJson(it.voice)) })
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(a.toString())
            if (!tmp.renameTo(file)) error("cannot save voices")
        }
        _all.value = list
    }

    companion object {
        private const val MAX_WEIGHT_S = 300f

        fun vectorJson(v: FloatArray): JSONArray = JSONArray().apply { v.forEach { put(it.toDouble()) } }
        fun vectorFromJson(a: JSONArray): FloatArray = FloatArray(a.length()) { a.getDouble(it).toFloat() }
    }
}

object VoiceMatch {
    /**
     * For each of [people], the index of the most similar of [known] above [minSimilarity], each known
     * voice given to one person at most (the closest pairs first); null if none is close enough.
     */
    fun assign(people: List<FloatArray>, known: List<FloatArray>, minSimilarity: Float): List<Int?> {
        val pairs = people.indices.flatMap { p -> known.indices.map { k -> Triple(p, k, VoiceMath.similarity(people[p], known[k])) } }
            .filter { it.third >= minSimilarity }
            .sortedByDescending { it.third }
        val out = arrayOfNulls<Int>(people.size)
        val used = HashSet<Int>()
        for ((p, k, _) in pairs) {
            if (out[p] != null || k in used) continue
            out[p] = k
            used += k
        }
        return out.toList()
    }
}
