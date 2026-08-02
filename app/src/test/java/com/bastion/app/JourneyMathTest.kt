package com.bastion.app

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
 */
class JourneyMathTest {

    private val today = 20_000L
    private fun daysAgo(n: Int) = today - n

    private fun derive(
        installed: Long,
        slips: List<Long> = emptyList(),
        cleanLogs: List<Long> = emptyList(),
        earliestUrge: Long? = null,
    ) = JourneyMath.derive(
        today = today,
        installedEpochDay = installed,
        dayLogs = slips.map { it to true } + cleanLogs.map { it to false },
        earliestUrgeDay = earliestUrge,
    )

    @Test
    fun `a fresh install with nothing logged is day one`() {
        val state = derive(installed = today)
        assertEquals(1, state.currentStreak)
        assertEquals(1, state.totalDays)
        assertEquals(1, state.totalCleanDays)
        assertEquals(today, state.startEpochDay)
    }

    /**
     * The case this whole thing exists for. Installed today, logs a slip from
     * three days ago: the streak, the totals and the calendar must all agree
     * that the journey began three days ago.
     */
    @Test
    fun `a slip logged before the install date moves the start back`() {
        val state = derive(installed = today, slips = listOf(daysAgo(3)))

        assertEquals("streak runs from the slip", 3, state.currentStreak)
        assertEquals("the journey covers the backfilled day", 4, state.totalDays)
        assertEquals("the backfilled slip is counted", 1, state.slipCount)
        assertEquals("three clean days sit after it", 3, state.totalCleanDays)
        assertEquals(daysAgo(3), state.startEpochDay)
    }

    /** Before the fix these disagreed: streak said 3, best and clean said 1. */
    @Test
    fun `backfilled totals never contradict the streak`() {
        val state = derive(installed = today, slips = listOf(daysAgo(5)))
        assertTrue(
            "streak ${state.currentStreak} cannot exceed the ${state.totalDays} days counted",
            state.currentStreak <= state.totalDays,
        )
        assertTrue(
            "longest ${state.longestStreak} cannot be shorter than the current ${state.currentStreak}",
            state.longestStreak >= state.currentStreak,
        )
    }

    @Test
    fun `an urge held on a past day also moves the start back`() {
        val state = derive(installed = today, earliestUrge = daysAgo(9))
        assertEquals(daysAgo(9), state.startEpochDay)
        assertEquals(10, state.totalDays)
    }

    /**
     * The guard on the generosity. Backfilling is meant to make the history
     * honest, not to sell rank — one tap on a date two years back would
     * otherwise be worth several ranks instantly.
     */
    @Test
    fun `backfilled history cannot buy rank`() {
        val fresh = derive(installed = today)
        val backfilled = derive(installed = today, slips = listOf(daysAgo(700)))

        assertTrue(
            "700 days of inferred history added ${backfilled.points - fresh.points} points",
            // Only the honesty bonus for the slip itself may differ.
            backfilled.points <= fresh.points + com.bastion.app.domain.RankPoints.SLIP_LOGGED_HONESTLY,
        )
        assertEquals("but the history itself is real", 701, backfilled.totalDays)
    }

    @Test
    fun `a future-dated log is ignored`() {
        val state = derive(installed = today, slips = listOf(today + 5))
        assertEquals(0, state.slipCount)
        assertEquals(today, state.startEpochDay)
        assertEquals(1, state.currentStreak)
    }

    @Test
    fun `an established user is unaffected by the inferred start`() {
        val state = derive(installed = daysAgo(30), slips = listOf(daysAgo(10)))
        assertEquals(daysAgo(30), state.startEpochDay)
        assertEquals(31, state.totalDays)
        assertEquals(10, state.currentStreak)
        assertEquals("longest run is the 19 days before the slip", 20, state.longestStreak)
    }
}
