package dev.pbou.stt.data

import dev.pbou.stt.engine.FileTranscriber.Segment
import dev.pbou.stt.engine.VoiceMath

/** Speakers on a transcript: who said each sentence, names, merging. No Android here, so it is unit-tested. */
object SpeakerText {

    /** A sentence this far from any turn gets no speaker. */
    private const val NEAR_MS = 1_500L

    /**
     * The speaker of each sentence (an index into [Speakers.people]), or null if none spoke then: the one
     * who speaks the most during it, else the nearest turn. Sentences are cut at pauses, so one sentence
     * rarely holds two people; when it does, it goes to the main one.
     */
    fun speakerOf(segments: List<Segment>, speakers: Speakers): List<Int?> {
        val turns = speakers.turns.sortedBy { it.startMs }
        if (turns.isEmpty()) return segments.map { null }
        val n = speakers.people.size
        var first = 0
        return segments.map { s ->
            while (first < turns.size && turns[first].endMs < s.startMs - NEAR_MS) first++
            val talk = LongArray(n)
            var nearest: Int? = null
            var nearestGap = Long.MAX_VALUE
            var k = first
            while (k < turns.size && turns[k].startMs <= s.endMs + NEAR_MS) {
                val t = turns[k]
                if (t.speaker in 0 until n) {
                    val overlap = minOf(t.endMs, s.endMs) - maxOf(t.startMs, s.startMs)
                    if (overlap > 0) talk[t.speaker] += overlap
                    else if (-overlap <= NEAR_MS && -overlap < nearestGap) {
                        nearestGap = -overlap
                        nearest = t.speaker
                    }
                }
                k++
            }
            val main = talk.indices.maxByOrNull { talk[it] }?.takeIf { talk[it] > 0 }
            main ?: nearest
        }
    }

    /**
     * The name shown for each person: their saved voice's name, else the one given here, else
     * [fallback] with their number.
     */
    fun names(speakers: Speakers, voiceName: (String) -> String?, fallback: (Int) -> String): List<String> =
        speakers.people.map { p -> p.voiceId?.let(voiceName) ?: p.name ?: fallback(p.number) }

    /** Whether to show who speaks: at least two people actually speak in [segments]. */
    fun worthShowing(who: List<Int?>): Boolean = who.filterNotNull().toSet().size >= 2

    /** [speakers] with person [from] merged into person [into] (the same person, found twice). */
    fun merge(speakers: Speakers, from: Int, into: Int): Speakers {
        if (from == into) return speakers
        val a = speakers.people[from]
        val b = speakers.people[into]
        val merged = b.copy(
            voice = VoiceMath.mean(listOf(b.voice, a.voice), listOf(b.seconds.coerceAtLeast(1f), a.seconds.coerceAtLeast(1f))),
            seconds = a.seconds + b.seconds,
            name = b.name ?: a.name,
            voiceId = b.voiceId ?: a.voiceId,
        )
        val people = speakers.people.toMutableList().apply { this[into] = merged; removeAt(from) }
        fun renumber(i: Int) = when {
            i == from -> if (into > from) into - 1 else into
            i > from -> i - 1
            else -> i
        }
        return speakers.copy(people = people, turns = speakers.turns.map { it.copy(speaker = renumber(it.speaker)) })
    }

    /** A sentence piece shorter than this is not cut out (a "yes" stays in the other person's sentence). */
    const val MIN_PIECE_MS = 1_000L
    private const val FRAME_MS = 100L

    /**
     * Where to cut [segment] when several people speak in it: the pieces (start, end), one per stretch of
     * a speaker, each at least [minPieceMs], cut in the middle of the gap between two voices. Null if it
     * is one person's. Sentences are cut at pauses while recording, and a quick reply often has none.
     */
    fun cuts(segment: Segment, turns: List<Speakers.Turn>, minPieceMs: Long = MIN_PIECE_MS): List<Pair<Long, Long>>? {
        val s = segment.startMs
        val e = segment.endMs
        val inside = turns.filter { it.endMs > s && it.startMs < e }
        if (inside.map { it.speaker }.toSet().size < 2) return null
        // Who speaks in each 100 ms frame: the one already speaking if several, else the newest; -1: nobody.
        val frames = IntArray(((e - s + FRAME_MS - 1) / FRAME_MS).toInt())
        var last = -1
        for (i in frames.indices) {
            val t = s + i * FRAME_MS + FRAME_MS / 2
            val here = inside.filter { t >= it.startMs && t < it.endMs }
            val who = when {
                here.isEmpty() -> -1
                here.any { it.speaker == last } -> last
                else -> here.maxBy { it.startMs }.speaker
            }
            frames[i] = who
            if (who >= 0) last = who
        }
        // Stretches of one speaker: [speaker, first frame, last frame], silences inside them included.
        val runs = ArrayList<IntArray>()
        for (i in frames.indices) {
            val w = frames[i]
            if (w < 0) continue
            val r = runs.lastOrNull()
            if (r != null && r[0] == w) r[2] = i else runs += intArrayOf(w, i, i)
        }
        fun length(r: IntArray) = (r[2] - r[1] + 1) * FRAME_MS
        // Too short to stand alone: joins the longer neighbour (and neighbours of one speaker join up).
        while (runs.size > 1) {
            val k = runs.indices.filter { length(runs[it]) < minPieceMs }.minByOrNull { length(runs[it]) } ?: break
            val prev = runs.getOrNull(k - 1)
            val next = runs.getOrNull(k + 1)
            val into = when {
                prev == null -> next!!
                next == null -> prev
                length(prev) >= length(next) -> prev
                else -> next
            }
            into[1] = minOf(into[1], runs[k][1])
            into[2] = maxOf(into[2], runs[k][2])
            runs.removeAt(k)
            var j = 1
            while (j < runs.size) {
                if (runs[j][0] == runs[j - 1][0]) {
                    runs[j - 1][2] = runs[j][2]
                    runs.removeAt(j)
                } else j++
            }
        }
        if (runs.size < 2) return null
        val bounds = (0 until runs.size - 1).map { k ->
            val gapStart = s + (runs[k][2] + 1) * FRAME_MS
            val gapEnd = s + runs[k + 1][1] * FRAME_MS
            (gapStart + gapEnd) / 2
        }
        val edges = listOf(s) + bounds + listOf(e)
        val pieces = edges.zipWithNext()
        // The first and last pieces also take the silence at the sentence's ends: check them once more.
        return pieces.takeIf { p -> p.all { it.second - it.first >= minPieceMs } }
    }

    /** "[12:04] Anna: text" lines, or "[12:04] text" without speakers to show. */
    fun timestamped(title: String, segments: List<Segment>, who: List<Int?>, names: List<String>): String = buildString {
        append(title).append("\n\n")
        val hours = (segments.lastOrNull()?.startMs ?: 0) >= 3_600_000
        val show = worthShowing(who)
        segments.forEachIndexed { i, s ->
            append('[').append(LibraryText.clock(s.startMs, hours)).append("] ")
            if (show) who[i]?.let { append(names[it]).append(": ") }
            append(s.text.trim()).append('\n')
        }
    }

    /** The text in paragraphs, a new one at each change of speaker, starting "Anna: ". */
    fun plain(segments: List<Segment>, who: List<Int?>, names: List<String>): String {
        if (!worthShowing(who)) return LibraryText.plain(segments)
        return buildString {
            var current: Int? = null
            segments.forEachIndexed { i, s ->
                val w = who[i] ?: current
                if (i == 0 || w != current) {
                    if (i > 0) append("\n\n")
                    w?.let { append(names[it]).append(": ") }
                    current = w
                } else append(' ')
                append(s.text.trim())
            }
        }
    }
}
