package dev.pbou.stt.data

import dev.pbou.stt.engine.FileTranscriber.Segment
import java.text.Normalizer

/** Text made from transcripts: export and search. No Android here, so it is unit-tested. */
object LibraryText {

    /** A pause this long starts a new paragraph in plain text. */
    private const val PARAGRAPH_GAP_MS = 2_500L

    /** The text, in paragraphs at long pauses. */
    fun plain(segments: List<Segment>): String = buildString {
        var previousEnd: Long? = null
        for (s in segments) {
            when {
                previousEnd == null -> {}
                s.startMs - previousEnd >= PARAGRAPH_GAP_MS -> append("\n\n")
                else -> append(' ')
            }
            append(s.text.trim())
            previousEnd = s.endMs
        }
    }

    /** One line per sentence: "[12:04] text", for meeting notes. */
    fun timestamped(title: String, segments: List<Segment>): String = buildString {
        append(title).append("\n\n")
        val hours = (segments.lastOrNull()?.startMs ?: 0) >= 3_600_000
        segments.forEach { append('[').append(clock(it.startMs, hours)).append("] ").append(it.text.trim()).append('\n') }
    }

    /** "4:05", "1:02:09" (with [hours], also "0:04:05" so that lines align). */
    fun clock(ms: Long, hours: Boolean = ms >= 3_600_000): String {
        val total = ms / 1000
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (hours) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** Lower case, without accents: "Élève" and "eleve" match. */
    fun fold(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(DIACRITICS, "")

    private val DIACRITICS = "\\p{Mn}+".toRegex()

    data class Hit(val item: LibraryItem, val snippet: String?, val atMs: Long?)

    /** Items whose title or shown transcript contains every word of [query], in the library's order. */
    fun search(items: List<LibraryItem>, query: String, titleOf: (LibraryItem) -> String): List<Hit> {
        val words = fold(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return items.map { Hit(it, null, null) }
        return items.mapNotNull { item ->
            val segments = item.transcript?.segments.orEmpty()
            val title = fold(titleOf(item))
            val body = segments.map { fold(it.text) }
            val all = title + " " + body.joinToString(" ")
            if (!words.all { it in all }) return@mapNotNull null
            // Show where the first word is said, if it is in the text.
            val at = body.indexOfFirst { words[0] in it }
            if (at < 0) Hit(item, null, null)
            else Hit(item, snippet(segments[at].text, words[0]), segments[at].startMs)
        }
    }

    /** About [width] characters of [text] around [word]. */
    fun snippet(text: String, word: String, width: Int = 90): String {
        val i = fold(text).indexOf(word).coerceAtLeast(0) // folding keeps lengths for Latin text
        if (text.length <= width) return text
        val start = (i - width / 3).coerceIn(0, text.length - width)
        val end = start + width
        return (if (start > 0) "…" else "") + text.substring(start, end).trim() + (if (end < text.length) "…" else "")
    }
}
