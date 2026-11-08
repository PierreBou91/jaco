package dev.pbou.stt.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class SpacingTest {

    @Test
    fun `a space only between words`() {
        assertEquals("", Spacing.before(null, "Hello."))       // nothing before the cursor
        assertEquals("", Spacing.before("", "Hello."))
        assertEquals(" ", Spacing.before("o", "Hello."))       // after a word
        assertEquals(" ", Spacing.before(".", "Hello."))       // after a sentence
        assertEquals("", Spacing.before(" ", "Hello."))        // a space is already there
        assertEquals("", Spacing.before("\n", "Hello."))       // start of a line
        assertEquals("", Spacing.before("(", "Hello."))        // just opened a bracket
    }

    @Test
    fun `no space before punctuation`() {
        assertEquals("", Spacing.before("o", ", then"))
        assertEquals("", Spacing.before("o", "?"))
        assertEquals("", Spacing.before("o", ""))
    }
}
