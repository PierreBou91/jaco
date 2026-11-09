package dev.pbou.stt.data

import dev.pbou.stt.engine.FileTranscriber.Segment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One transcription of an item: the live one made while recording, or a later one with another model. */
data class Transcript(
    val modelId: String,
    /** The language the model was told, null if it detected it. */
    val language: String?,
    /** Made while recording (live), rather than from the whole audio afterwards. */
    val live: Boolean,
    val createdAt: Long,
    val segments: List<Segment>,
) {
    val text: String get() = segments.joinToString(" ") { it.text }
}

/**
 * Without the earlier full-audio versions by [modelId]: a new one replaces them (after a recording was
 * continued, they miss its end). The live version always stays.
 */
fun List<Transcript>.withoutOlder(modelId: String): List<Transcript> = filterNot { !it.live && it.modelId == modelId }

/** A recording made in the app, or a file that was transcribed. */
data class LibraryItem(
    val id: String,
    val kind: Kind,
    /** Null for recordings until renamed: they show their date. Files start with their file name. */
    val title: String?,
    val createdAt: Long,
    val durationMs: Long,
    /** The audio's file name in the item's folder, null if it was not kept. */
    val audio: String?,
    val transcripts: List<Transcript>,
    /** Which transcript is shown (an improved one replaces the live one, which stays available). */
    val shown: Int,
    val status: Status,
    /** A better transcription to make in the background, see [Improver]. */
    val improve: ImproveRequest? = null,
    /** Who spoke when, found from the audio (see [SpeakerFinder]); null until found. */
    val speakers: Speakers? = null,
    /** Speakers to find in the background; null when there is nothing to do. */
    val findSpeakers: SpeakersRequest? = null,
) {
    enum class Kind { RECORDING, FILE }
    enum class Status { RECORDING, TRANSCRIBING, DONE }

    val transcript: Transcript? get() = transcripts.getOrNull(shown) ?: transcripts.lastOrNull()
}

data class ImproveRequest(
    val modelId: String,
    val whileCharging: Boolean,
    /** The language to tell the model ([dev.pbou.stt.engine.ModelSpec.AUTO] or a code); null: the settings' one. */
    val language: String? = null,
)

/** A request to find the speakers of an item. [count]: how many there are, null to let Jaco find out. */
data class SpeakersRequest(val count: Int? = null)

/** The speakers of an item: when each one spoke ([turns], indexes into [people]), and who they are. */
data class Speakers(val turns: List<Turn>, val people: List<Person>, val count: Int?, val createdAt: Long) {
    data class Turn(val startMs: Long, val endMs: Long, val speaker: Int)

    /**
     * One speaker. [name]: given in this item, null for "Speaker N". [voiceId]: the saved voice they were
     * recognised as (its name wins, so renaming a saved voice renames it everywhere). [voice]: their voice
     * embedding, kept to remember them later. [number]: "Speaker N" and their color, kept when others are
     * merged so nobody changes number.
     */
    class Person(val name: String?, val voiceId: String?, val voice: FloatArray, val seconds: Float, val number: Int) {
        fun copy(name: String? = this.name, voiceId: String? = this.voiceId, voice: FloatArray = this.voice, seconds: Float = this.seconds) =
            Person(name, voiceId, voice, seconds, number)
    }
}

/**
 * The recordings and transcribed files, one folder each under [root]: `item.json` and the audio if kept.
 * All of it is held in memory (text is small: an hour is about 60 KB) and written back on each change.
 */
class Library(private val root: File) {
    private val _items = MutableStateFlow<List<LibraryItem>>(emptyList())
    /** Newest first. */
    val items: StateFlow<List<LibraryItem>> = _items.asStateFlow()

    private val lock = Mutex()
    @Volatile private var loaded = false

    fun dir(id: String) = File(root, id)

    fun audioFile(item: LibraryItem): File? = item.audio?.let { File(dir(item.id), it) }?.takeIf { it.exists() }

    fun get(id: String): LibraryItem? = _items.value.firstOrNull { it.id == id }

    fun item(id: String): Flow<LibraryItem?> = items.map { list -> list.firstOrNull { it.id == id } }.distinctUntilChanged()

    suspend fun load() = lock.withLock {
        if (loaded) return@withLock
        val list = withContext(Dispatchers.IO) {
            root.listFiles()?.mapNotNull { d -> runCatching { fromJson(JSONObject(File(d, ITEM).readText())) }.getOrNull() }.orEmpty()
        }
        _items.value = list.sortedByDescending { it.createdAt }
        loaded = true
    }

    fun newId(): String = UUID.randomUUID().toString().take(12)

    suspend fun add(item: LibraryItem) = lock.withLock {
        withContext(Dispatchers.IO) { save(item) }
        _items.value = (_items.value.filterNot { it.id == item.id } + item).sortedByDescending { it.createdAt }
    }

    /** Changes an item and saves it; returns the new item, or null if there is none with this id. */
    suspend fun update(id: String, transform: (LibraryItem) -> LibraryItem): LibraryItem? = lock.withLock {
        val old = _items.value.firstOrNull { it.id == id } ?: return@withLock null
        val new = transform(old)
        if (new == old) return@withLock old
        withContext(Dispatchers.IO) { save(new) }
        _items.value = _items.value.map { if (it.id == id) new else it }
        new
    }

    suspend fun delete(id: String) = lock.withLock {
        withContext(Dispatchers.IO) { dir(id).deleteRecursively() }
        _items.value = _items.value.filterNot { it.id == id }
    }

    private fun save(item: LibraryItem) {
        val d = dir(item.id).apply { mkdirs() }
        val tmp = File(d, "$ITEM.tmp")
        tmp.writeText(toJson(item).toString())
        if (!tmp.renameTo(File(d, ITEM))) error("cannot save ${item.id}")
    }

    private companion object {
        const val ITEM = "item.json"

        fun toJson(i: LibraryItem): JSONObject = JSONObject()
            .put("id", i.id).put("kind", i.kind.name).put("title", i.title ?: JSONObject.NULL)
            .put("createdAt", i.createdAt).put("durationMs", i.durationMs).put("audio", i.audio ?: JSONObject.NULL)
            .put("shown", i.shown).put("status", i.status.name)
            .put("improve", i.improve?.let { JSONObject().put("model", it.modelId).put("whileCharging", it.whileCharging).put("language", it.language ?: JSONObject.NULL) } ?: JSONObject.NULL)
            .put("speakers", i.speakers?.let(::speakersJson) ?: JSONObject.NULL)
            .put("findSpeakers", i.findSpeakers?.let { JSONObject().put("count", it.count ?: JSONObject.NULL) } ?: JSONObject.NULL)
            .put("transcripts", JSONArray(i.transcripts.map { t ->
                JSONObject().put("model", t.modelId).put("language", t.language ?: JSONObject.NULL).put("live", t.live)
                    .put("createdAt", t.createdAt)
                    .put("segments", JSONArray(t.segments.map { JSONArray().put(it.startMs).put(it.endMs).put(it.text) }))
            }))

        fun fromJson(o: JSONObject): LibraryItem {
            val ts = o.getJSONArray("transcripts")
            return LibraryItem(
                id = o.getString("id"),
                kind = LibraryItem.Kind.valueOf(o.getString("kind")),
                title = o.optStringOrNull("title"),
                createdAt = o.getLong("createdAt"),
                durationMs = o.optLong("durationMs"),
                audio = o.optStringOrNull("audio"),
                transcripts = (0 until ts.length()).map { k ->
                    val t = ts.getJSONObject(k)
                    val segs = t.getJSONArray("segments")
                    Transcript(
                        modelId = t.getString("model"),
                        language = t.optStringOrNull("language"),
                        live = t.optBoolean("live"),
                        createdAt = t.optLong("createdAt"),
                        segments = (0 until segs.length()).map { s ->
                            val a = segs.getJSONArray(s)
                            Segment(a.getLong(0), a.getLong(1), a.getString(2))
                        },
                    )
                },
                shown = o.optInt("shown"),
                status = LibraryItem.Status.valueOf(o.optString("status", "DONE")),
                improve = o.optJSONObject("improve")?.let { ImproveRequest(it.getString("model"), it.optBoolean("whileCharging", true), it.optStringOrNull("language")) },
                speakers = o.optJSONObject("speakers")?.let(::speakersFromJson),
                findSpeakers = o.optJSONObject("findSpeakers")?.let { SpeakersRequest(it.optIntOrNull("count")) },
            )
        }

        fun speakersJson(s: Speakers): JSONObject = JSONObject()
            .put("count", s.count ?: JSONObject.NULL).put("createdAt", s.createdAt)
            .put("turns", JSONArray(s.turns.map { JSONArray().put(it.startMs).put(it.endMs).put(it.speaker) }))
            .put("people", JSONArray(s.people.map { p ->
                JSONObject().put("name", p.name ?: JSONObject.NULL).put("voiceId", p.voiceId ?: JSONObject.NULL)
                    .put("seconds", p.seconds.toDouble()).put("number", p.number).put("voice", Voices.vectorJson(p.voice))
            }))

        fun speakersFromJson(o: JSONObject): Speakers {
            val turns = o.getJSONArray("turns")
            val people = o.getJSONArray("people")
            return Speakers(
                turns = (0 until turns.length()).map { k -> turns.getJSONArray(k).let { Speakers.Turn(it.getLong(0), it.getLong(1), it.getInt(2)) } },
                people = (0 until people.length()).map { k ->
                    val p = people.getJSONObject(k)
                    Speakers.Person(
                        p.optStringOrNull("name"), p.optStringOrNull("voiceId"), Voices.vectorFromJson(p.getJSONArray("voice")),
                        p.optDouble("seconds", 0.0).toFloat(), p.optInt("number", k + 1),
                    )
                },
                count = o.optIntOrNull("count"),
                createdAt = o.optLong("createdAt"),
            )
        }

        fun JSONObject.optIntOrNull(key: String): Int? = if (!has(key) || isNull(key)) null else optInt(key)

        fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else optString(key).takeIf { has(key) }
    }
}
