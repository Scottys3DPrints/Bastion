package com.bastion.app

import com.bastion.app.data.repo.HabitMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The streak, pinned to a calendar.
 *
 * This is the number a habit tracker is judged on and the one a man will notice
 * being wrong. An off-by-one is invisible in a screenshot and obvious after a
 * week — by which point he has stopped believing the rest of the numbers too.
 *
 * Day 1000 stands in for "today" throughout; nothing here depends on a real
 * date, which is the point of epoch days.
 */
class HabitMathTest {

    private val today = 1000L

    // --- the current streak ------------------------------------------------

    @Test
    fun `an unbroken run ending today counts today`() {
        assertEquals(3, HabitMath.currentStreak(setOf(998L, 999L, 1000L), today))
    }

    /**
     * The rule the whole design rests on.
     *
     * A streak that breaks at midnight tells a man he has failed at 00:01 for
     * something he still has all day to do. That is both untrue and the exact
     * moment a tracker gets deleted, so today is optional: the run reads from
     * yesterday until the day is genuinely over.
     */
    @Test
    fun `today not yet done does not break a run`() {
        assertEquals(2, HabitMath.currentStreak(setOf(998L, 999L), today))
    }

    @Test
    fun `missing yesterday does break it`() {
        // Done the day before yesterday, and nothing since. That is over.
        assertEquals(0, HabitMath.currentStreak(setOf(998L), today))
    }

    @Test
    fun `a run that skips a day only counts back to the gap`() {
        assertEquals(2, HabitMath.currentStreak(setOf(990L, 991L, 999L, 1000L), today))
    }

    @Test
    fun `today alone is a streak of one`() {
        assertEquals(1, HabitMath.currentStreak(setOf(1000L), today))
    }

    @Test
    fun `nothing logged is no streak`() {
        assertEquals(0, HabitMath.currentStreak(emptySet(), today))
    }

    // --- the best ever ------------------------------------------------------

    @Test
    fun `the best run is the longest one anywhere in the record`() {
        // 10..14 is five; the recent one is two.
        val done = setOf(10L, 11L, 12L, 13L, 14L, 999L, 1000L)
        assertEquals(5, HabitMath.bestStreak(done))
    }

    @Test
    fun `a single day is a best of one, and nothing is zero`() {
        assertEquals(1, HabitMath.bestStreak(setOf(42L)))
        assertEquals(0, HabitMath.bestStreak(emptySet()))
    }

    @Test
    fun `the best run can be the current one`() {
        assertEquals(3, HabitMath.bestStreak(setOf(500L, 998L, 999L, 1000L)))
    }

    // --- how much of it was kept -------------------------------------------

    @Test
    fun `a rate is kept days over the window`() {
        // Five days in the window, three of them kept.
        val done = setOf(996L, 998L, 1000L)
        assertEquals(0.6f, HabitMath.completionRate(done, from = 996L, to = 1000L), 0.001f)
    }

    /**
     * Measured from adoption, never from the edge of the window.
     *
     * A habit started yesterday and kept both days is at 100%, not at 2% of a
     * quarter he did not have it for. The second number is technically
     * defensible and reads as failure for something he has not yet missed.
     */
    @Test
    fun `a habit started yesterday and kept is at full marks`() {
        assertEquals(1f, HabitMath.completionRate(setOf(999L, 1000L), from = 999L, to = today), 0.001f)
    }

    @Test
    fun `days outside the window are not counted`() {
        // The two old ones must not inflate a two-day window.
        val done = setOf(500L, 501L, 1000L)
        assertEquals(0.5f, HabitMath.completionRate(done, from = 999L, to = 1000L), 0.001f)
    }

    @Test
    fun `a backwards or empty window is zero rather than a crash`() {
        assertEquals(0f, HabitMath.completionRate(setOf(1000L), from = 1000L, to = 999L), 0.001f)
    }

    // --- the calendar -------------------------------------------------------

    @Test
    fun `the calendar is whole weeks ending today`() {
        val days = HabitMath.calendarDays(today, weeks = 12)
        assertEquals(84, days.size)
        assertEquals(today, days.last())
        assertEquals(today - 83, days.first())
        // Contiguous, so chunking into sevens gives real weeks.
        assertTrue(days.zipWithNext().all { (a, b) -> b == a + 1 })
    }

    // --- what counts as done ------------------------------------------------

    @Test
    fun `a tick habit is done on any count at all`() {
        assertTrue(HabitMath.isComplete(1, targetCount = 1))
        assertFalse(HabitMath.isComplete(0, targetCount = 1))
    }

    /**
     * A counting habit is not done until it reaches its number.
     *
     * One glass of water out of eight is not a streak day. A streak that counts
     * it is measuring nothing, and the man reading it knows that first.
     */
    @Test
    fun `a counting habit is only done at its target`() {
        assertFalse(HabitMath.isComplete(7, targetCount = 8))
        assertTrue(HabitMath.isComplete(8, targetCount = 8))
        // Over the target still counts — an extra glass is not a failure.
        assertTrue(HabitMath.isComplete(9, targetCount = 8))
    }

    /** A target of zero would make every day vacuously complete. */
    @Test
    fun `a nonsense target still needs something done`() {
        assertFalse(HabitMath.isComplete(0, targetCount = 0))
        assertTrue(HabitMath.isComplete(1, targetCount = 0))
    }

    @Test
    fun `partial days are dropped before a streak is counted`() {
        val counts = mapOf(998L to 8, 999L to 3, 1000L to 8)
        val complete = HabitMath.completeDays(counts, targetCount = 8)
        assertEquals(setOf(998L, 1000L), complete)
        // And the gap on 999 breaks the run, leaving only today.
        assertEquals(1, HabitMath.currentStreak(complete, today))
    }

    @Test
    fun `the same counts against a tick habit are all complete`() {
        val counts = mapOf(998L to 8, 999L to 3, 1000L to 8)
        assertEquals(3, HabitMath.currentStreak(HabitMath.completeDays(counts, 1), today))
    }
}
