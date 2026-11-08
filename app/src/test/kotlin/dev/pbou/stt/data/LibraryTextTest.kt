package dev.pbou.stt.data

import dev.pbou.stt.engine.FileTranscriber.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryTextTest {
    private val segs = listOf(
        Segment(0, 2_000, "Bonjour à tous."),
        Segment(2_300, 5_000, "On commence la réunion."),
        Segment(9_000, 12_000, "Premier point, le budget."),
    )

    private fun item(id: String, title: String?, segments: List<Segment>) = LibraryItem(
        id, LibraryItem.Kind.RECORDING, title, 0, 0, null,
        listOf(Transcript("m", "fr", true, 0, segments)), 0, LibraryItem.Status.DONE,
    )

    @Test fun `plain text breaks paragraphs at long pauses`() =
        assertEquals("Bonjour à tous. On commence la réunion.\n\nPremier point, le budget.", LibraryText.plain(segs))

    @Test fun `timestamped lines`() = assertEquals(
        "Réunion\n\n[0:00] Bonjour à tous.\n[0:02] On commence la réunion.\n[0:09] Premier point, le budget.\n",
        LibraryText.timestamped("Réunion", segs),
    )

    @Test fun `clock switches to hours`() {
        assertEquals("4:05", LibraryText.clock(245_000))
        assertEquals("1:02:09", LibraryText.clock(3_729_000))
        assertEquals("0:04:05", LibraryText.clock(245_000, hours = true))
    }

    @Test fun `search ignores case and accents, and needs every word`() {
        val items = listOf(item("a", null, segs), item("b", "Courses", listOf(Segment(0, 1000, "Acheter du pain"))))
        val hits = LibraryText.search(items, "REUNION commence", titleOf = { it.title ?: "" })
        assertEquals(listOf("a"), hits.map { it.item.id })
        assertEquals("On commence la réunion.", hits[0].snippet)
        assertEquals(2_300L, hits[0].atMs)
        assertEquals(emptyList<String>(), LibraryText.search(items, "réunion pain", { it.title ?: "" }).map { it.item.id })
    }

    @Test fun `a title match has no snippet`() {
        val hits = LibraryText.search(listOf(item("b", "Courses", listOf(Segment(0, 1000, "Acheter du pain")))), "courses", { it.title ?: "" })
        assertNull(hits.single().snippet)
    }

    @Test fun `snippet is cut around the word`() {
        val text = "a".repeat(100) + " budget " + "b".repeat(100)
        val s = LibraryText.snippet(text, "budget", width = 40)
        assert(s.contains("budget")) { s }
        assert(s.startsWith("…") && s.endsWith("…")) { s }
    }
}
