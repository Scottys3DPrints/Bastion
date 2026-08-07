package com.bastion.app

import com.bastion.app.domain.splitValues
import com.bastion.app.feature.track.LogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Several answers per question, and the counting that has to follow them.
 *
 * Triggers, places, devices and what-helped were single-select, which forced a
 * man to rank things that arrived together: a late-night slip on the sofa after
 * a drink is late night *and* boredom *and* alcohol, and making him pick the
 * main one throws away two facts and files a guess as the third.
 *
 * Storing several in one column is the easy half. The half that fails silently
 * is the counting: every chart over these columns groups them, and grouping the
 * raw text would make "Late night,Boredom" its own trigger — a bucket of one
 * that can never win, while the two real answers inside it go uncounted. The
 * chart would still draw. It would just be wrong, and wrong in the direction of
 * telling a man he has no pattern.
 */
class LogMultiSelectTest {

    // --- storing several -----------------------------------------------------

    private fun List<String>.joined(): String? =
        filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString(",")

    @Test
    fun `several answers survive a round trip`() {
        val chosen = listOf("Late night", "Boredom", "Alcohol")
        assertEquals(chosen, chosen.joined().splitValues())
    }

    @Test
    fun `one answer still stores and reads as one`() {
        assertEquals(listOf("Stress"), listOf("Stress").joined().splitValues())
    }

    /**
     * Nothing chosen must be null rather than an empty string. Every reader
     * treats null as "not asked", and "" would be counted as an answer — a
     * blank bar on a chart of triggers.
     */
    @Test
    fun `nothing chosen stores as nothing`() {
        assertNull(emptyList<String>().joined())
        assertNull(listOf("", "   ").joined())
        assertEquals(emptyList<String>(), null.splitValues())
    }

    /** A log written before this change is a list of one, not a parse failure. */
    @Test
    fun `an old single-value log still reads`() {
        assertEquals(listOf("Late night"), "Late night".splitValues())
    }

    @Test
    fun `stray spacing and empty segments are dropped`() {
        assertEquals(listOf("Bored", "Tired"), " Bored , , Tired ".splitValues())
        assertEquals(emptyList<String>(), ",,,".splitValues())
    }

    // --- counting across several ---------------------------------------------

    /**
     * The failure this guards. Two logs sharing one trigger have to count as
     * two for it, not as one each for two different composite strings.
     */
    @Test
    fun `counting splits before it groups`() {
        val stored = listOf(
            "Late night,Boredom",
            "Late night,Stress",
            "Boredom",
        )
        val counts = stored.flatMap { it.splitValues() }.groupingBy { it }.eachCount()
        assertEquals(2, counts["Late night"])
        assertEquals(2, counts["Boredom"])
        assertEquals(1, counts["Stress"])
        // And no composite ever becomes a category of its own.
        assertTrue(counts.keys.none { it.contains(',') })
    }

    // --- the toggle behind the chips -----------------------------------------

    @Test
    fun `an entry starts with nothing chosen`() {
        val entry = LogEntry(resisted = false)
        assertEquals(emptyList<String>(), entry.triggers)
        assertEquals(emptyList<String>(), entry.places)
        assertEquals(emptyList<String>(), entry.devices)
        assertEquals(emptyList<String>(), entry.helped)
    }

    /**
     * Tapping a chosen chip again clears it. There has to be a way back to "I
     * don't know", because a guessed trigger poisons the pattern it feeds.
     */
    @Test
    fun `choices add and clear`() {
        var entry = LogEntry(resisted = false)
        entry = entry.copy(triggers = entry.triggers + "Late night")
        entry = entry.copy(triggers = entry.triggers + "Boredom")
        assertEquals(listOf("Late night", "Boredom"), entry.triggers)

        entry = entry.copy(triggers = entry.triggers - "Late night")
        assertEquals(listOf("Boredom"), entry.triggers)

        entry = entry.copy(triggers = entry.triggers - "Boredom")
        assertEquals(emptyList<String>(), entry.triggers)
        assertNull(entry.triggers.joined())
    }
}
