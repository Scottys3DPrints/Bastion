package com.bastion.app

import com.bastion.app.data.db.DayStatus
import com.bastion.app.data.repo.JourneyMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The numbers a man checks every day.
 *
 * Worth testing directly because the rules are not obvious and the failures are
 * quiet: the app does not crash when it tells someone he has been clean for one
 * day instead of eleven, it just stops being worth believing.
 *
 * ## What changed here, and why these assertions moved
 *
 * This file used to pin the opposite rule. Every day absent from the log counted
 * as clean, so `a fresh install with nothing logged` asserted a streak of one on
 * a day nobody had said anything about, and an install left alone for a fortnight
 * read seventeen days clean and three hundred points. The tests were not wrong
 * about the code; they were faithful to a model that was wrong, which is the
 * more expensive kind of green.
 *
 * Absence is now absence.
 */
class JourneyMathTest {

    private val today = 20_000L
    private fun daysAgo(n: Int) = today - n

    private fun derive(
        installed: Long,
        slips: List<Long> = emptyList(),
        cleanLogs: List<Long> = emptyList(),
        unlogged: List<Long> = emptyList(),
        earliestUrge: Long? = null,
    ) = JourneyMath.derive(
        today = today,
        installedEpochDay = installed,
        dayLogs = slips.map { it to DayStatus.SLIP } +
            cleanLogs.map { it to DayStatus.CLEAN } +
            unlogged.map { it to DayStatus.UNLOGGED },
        earliestUrgeDay = earliestUrge,
    )

    // --- absence is absence ----------------------------------------------

    @Test
    fun `a fresh install with nothing logged has nothing to show`() {
        val state = derive(installed = today)
        assertEquals("he has not marked today", 0, state.currentStreak)
        assertEquals("and has claimed no clean days", 0, state.totalCleanDays)
        assertEquals("but the journey has begun", 1, state.totalDays)
        assertEquals(today, state.startEpochDay)
    }

    /**
     * The report that started this: an app left alone for a fortnight said
     * seventeen days clean and three hundred and thirty-six points, for a man
     * who had not opened it once.
     */
    @Test
    fun `an app left alone earns nothing`() {
        val state = derive(installed = daysAgo(16))
        assertEquals("seventeen days passed; none were claimed", 0, state.currentStreak)
        assertEquals(0, state.totalCleanDays)
        assertEquals(0, state.points)
        assertEquals("the days still happened", 17, state.totalDays)
    }

    @Test
    fun `only marked days count`() {
        val state = derive(installed = daysAgo(5), cleanLogs = listOf(daysAgo(1), today))
        assertEquals(2, state.currentStreak)
        assertEquals(2, state.totalCleanDays)
    }

    /** UNLOGGED is a real answer, not a gap the arithmetic fills in. */
    @Test
    fun `an explicitly unlogged day is not a clean day`() {
        val state = derive(
            installed = daysAgo(3),
            cleanLogs = listOf(daysAgo(3)),
            unlogged = listOf(daysAgo(2), daysAgo(1), today),
        )
        assertEquals(0, state.currentStreak)
        assertEquals(1, state.totalCleanDays)
    }

    // --- the streak -------------------------------------------------------

    /**
     * The one softness kept on purpose: an unmarked *today* leaves yesterday's
     * streak standing, because the day is not over yet. A gap in the past is a
     * different thing.
     */
    @Test
    fun `today being unmarked does not break the streak`() {
        val state = derive(
            installed = daysAgo(5),
            cleanLogs = listOf(daysAgo(3), daysAgo(2), daysAgo(1)),
        )
        assertEquals(3, state.currentStreak)
    }

    @Test
    fun `a gap in the past does break it`() {
        val state = derive(
            installed = daysAgo(5),
            cleanLogs = listOf(daysAgo(5), daysAgo(4), daysAgo(1), today),
        )
        assertEquals("only the run ending today counts", 2, state.currentStreak)
        assertEquals(4, state.totalCleanDays)
    }

    /**
     * The rule stated in the user's own words: many unreported days are allowed,
     * and reporting again simply starts a new streak from there. The gap is not
     * a punishment that follows him — it is just days he did not speak about.
     */
    @Test
    fun `a long silence costs nothing once he reports again`() {
        val state = derive(
            installed = daysAgo(40),
            cleanLogs = listOf(daysAgo(39), daysAgo(38), today),
        )
        assertEquals("the new run starts at the day he reported", 1, state.currentStreak)
        assertEquals("nothing earlier was lost", 3, state.totalCleanDays)
        assertEquals("and his best run still stands", 2, state.longestStreak)
    }

    @Test
    fun `reporting after a silence builds a fresh run`() {
        val state = derive(
            installed = daysAgo(40),
            cleanLogs = listOf(daysAgo(30), daysAgo(2), daysAgo(1), today),
        )
        assertEquals(3, state.currentStreak)
        assertEquals(4, state.totalCleanDays)
    }

    @Test
    fun `a slip ends the run even with clean days before it`() {
        val state = derive(
            installed = daysAgo(5),
            slips = listOf(daysAgo(1)),
            cleanLogs = listOf(daysAgo(3), daysAgo(2)),
        )
        assertEquals(0, state.currentStreak)
        assertEquals(1, state.slipCount)
    }

    @Test
    fun `longest streak is the longest marked run`() {
        val state = derive(
            installed = daysAgo(10),
            cleanLogs = listOf(daysAgo(10), daysAgo(9), daysAgo(8), daysAgo(3), daysAgo(2)),
        )
        assertEquals(3, state.longestStreak)
    }

    @Test
    fun `totals never contradict the streak`() {
        val state = derive(
            installed = daysAgo(9),
            cleanLogs = (0..5).map { daysAgo(it) },
        )
        assertTrue(
            "streak ${state.currentStreak} cannot exceed the ${state.totalDays} days counted",
            state.currentStreak <= state.totalDays,
        )
        assertTrue(
            "longest ${state.longestStreak} cannot be shorter than the current " +
                "${state.currentStreak}",
            state.longestStreak >= state.currentStreak,
        )
    }

    // --- the journey's start, which is unchanged --------------------------

    @Test
    fun `a slip logged before the install date moves the start back`() {
        val state = derive(installed = today, slips = listOf(daysAgo(3)))
        assertEquals("the journey covers the backfilled day", 4, state.totalDays)
        assertEquals("the backfilled slip is counted", 1, state.slipCount)
        assertEquals(daysAgo(3), state.startEpochDay)
    }

    @Test
    fun `an urge held on a past day also moves the start back`() {
        val state = derive(installed = today, earliestUrge = daysAgo(9))
        assertEquals(daysAgo(9), state.startEpochDay)
        assertEquals(10, state.totalDays)
    }

    /**
     * The guard on the generosity, now stronger than it was. Backfilling makes
     * the history honest; it was never meant to sell rank.
     */
    @Test
    fun `backfilled history cannot buy rank`() {
        val fresh = derive(installed = today)
        val backfilled = derive(installed = today, slips = listOf(daysAgo(700)))

        assertTrue(
            "700 days of inferred history added ${backfilled.points - fresh.points} points",
            backfilled.points <= fresh.points + com.bastion.app.domain.RankPoints.SLIP_LOGGED_HONESTLY,
        )
        assertEquals("but the history itself is real", 701, backfilled.totalDays)
    }

    @Test
    fun `a future-dated log is ignored`() {
        val state = derive(installed = today, slips = listOf(today + 5), cleanLogs = listOf(today + 2))
        assertEquals(0, state.slipCount)
        assertEquals(0, state.totalCleanDays)
        assertEquals(today, state.startEpochDay)
    }

    @Test
    fun `an established user counts the days he marked`() {
        val state = derive(
            installed = daysAgo(30),
            slips = listOf(daysAgo(10)),
            cleanLogs = (0..9).map { daysAgo(it) },
        )
        assertEquals(daysAgo(30), state.startEpochDay)
        assertEquals(31, state.totalDays)
        assertEquals(10, state.currentStreak)
        assertEquals(10, state.totalCleanDays)
    }
}
