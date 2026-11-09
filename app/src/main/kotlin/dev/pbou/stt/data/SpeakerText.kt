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
