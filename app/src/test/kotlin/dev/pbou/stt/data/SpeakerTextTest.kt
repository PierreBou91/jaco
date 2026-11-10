package dev.pbou.stt.data

import dev.pbou.stt.engine.FileTranscriber.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerTextTest {
    private val segs = listOf(
        Segment(0, 2_000, "Salut, ça va ?"),
        Segment(2_500, 4_000, "Oui, et toi ?"),
        Segment(4_500, 6_000, "Très bien."),
        Segment(6_200, 8_000, "Parfait, on commence."),
        Segment(30_000, 31_000, "Bruit."),
    )

    private fun person(name: String? = null, voiceId: String? = null, seconds: Float = 10f, voice: FloatArray = floatArrayOf(1f, 0f), number: Int = 0) =
        Speakers.Person(name, voiceId, voice, seconds, number)

    private val speakers = Speakers(
        turns = listOf(
            Speakers.Turn(100, 1_900, 0),
            Speakers.Turn(2_600, 3_900, 1),
            Speakers.Turn(4_400, 5_000, 0),
            Speakers.Turn(5_000, 6_100, 1), // mostly Ben in the third sentence
            Speakers.Turn(8_050, 9_500, 0), // just after the fourth, nearer than the previous turn
        ),
        people = listOf(person("Anna", number = 1), person(voiceId = "v1", voice = floatArrayOf(0f, 1f), number = 2)),
        count = null,
        createdAt = 0,
    )

    @Test fun `each sentence goes to who speaks most in it, else the nearest turn, else nobody`() =
        assertEquals(listOf(0, 1, 1, 0, null), SpeakerText.speakerOf(segs, speakers))

    @Test fun `names come from the saved voice, then the given name, then a number`() {
        val s = speakers.copy(people = speakers.people + person(number = 4)) // number 3 was merged away
        assertEquals(listOf("Anna", "Ben", "Speaker 4"), SpeakerText.names(s, { if (it == "v1") "Ben" else null }, { "Speaker $it" }))
        // A forgotten voice falls back to the number.
        assertEquals(listOf("Anna", "Speaker 2", "Speaker 4"), SpeakerText.names(s, { null }, { "Speaker $it" }))
    }

    @Test fun `speakers are shown only when two people speak`() {
        assertTrue(SpeakerText.worthShowing(listOf(0, 1, null)))
        assertFalse(SpeakerText.worthShowing(listOf(0, 0, null)))
    }

    @Test fun `merging renumbers the turns`() {
        val three = speakers.copy(
            people = speakers.people + person("Carl", seconds = 5f, number = 3),
            turns = speakers.turns + Speakers.Turn(10_000, 11_000, 2),
        )
        val m = SpeakerText.merge(three, from = 0, into = 2)
        assertEquals(2, m.people.size)
        assertEquals("Carl", m.people[1].name)
        assertEquals(15f, m.people[1].seconds, 0f)
        assertEquals(listOf(2, 3), m.people.map { it.number }) // nobody changes number
        assertEquals(listOf(1, 0, 1, 0, 1, 1), m.turns.map { it.speaker })
    }

    @Test fun `exports name the speaker`() {
        val who = listOf(0, 1, 1, 0, null)
        val names = listOf("Anna", "Ben")
        assertEquals(
            "Call\n\n[0:00] Anna: Salut, ça va ?\n[0:02] Ben: Oui, et toi ?\n[0:04] Ben: Très bien.\n[0:06] Anna: Parfait, on commence.\n[0:30] Bruit.\n",
            SpeakerText.timestamped("Call", segs, who, names),
        )
        assertEquals(
            "Anna: Salut, ça va ?\n\nBen: Oui, et toi ? Très bien.\n\nAnna: Parfait, on commence. Bruit.",
            SpeakerText.plain(segs, who, names),
        )
        // One speaker: the usual text.
        assertEquals(LibraryText.plain(segs), SpeakerText.plain(segs, listOf(0, 0, 0, 0, 0), names))
    }

    private fun turn(a: Long, b: Long, k: Int) = Speakers.Turn(a, b, k)

    @Test fun `a sentence of one person is not cut`() {
        assertEquals(null, SpeakerText.cuts(Segment(0, 10_000, "x"), listOf(turn(0, 4_000, 0), turn(4_500, 10_000, 0))))
        assertEquals(null, SpeakerText.cuts(Segment(0, 10_000, "x"), listOf(turn(0, 10_000, 1), turn(20_000, 30_000, 0))))
    }

    @Test fun `a reply inside the sentence is cut out, in the middle of the gaps`() = assertEquals(
        listOf(0L to 4_250L, 4_250L to 7_100L, 7_100L to 12_000L),
        SpeakerText.cuts(Segment(0, 12_000, "x"), listOf(turn(0, 4_000, 0), turn(4_500, 7_000, 1), turn(7_200, 12_000, 0))),
    )

    @Test fun `a short yes stays in the other person's piece`() = assertEquals(
        null,
        SpeakerText.cuts(Segment(0, 10_000, "x"), listOf(turn(0, 5_000, 0), turn(5_100, 5_600, 1), turn(5_700, 10_000, 0))),
    )

    @Test fun `overlapping voices keep the one already speaking`() = assertEquals(
        listOf(0L to 6_000L, 6_000L to 9_000L),
        SpeakerText.cuts(Segment(0, 9_000, "x"), listOf(turn(0, 6_000, 0), turn(5_000, 9_000, 1))),
    )
}
