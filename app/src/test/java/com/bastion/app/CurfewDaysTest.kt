package com.bastion.app

import com.bastion.app.core.alarm.ScheduledLockdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * A curfew that does not have to run every night.
 *
 * It was a nightly lockdown and it only ever was one — the same hour, seven days
 * a week — so a man whose hard nights are Friday and Saturday had to choose
 * between a curfew he did not want on a Tuesday and no curfew at all.
 *
 * The catch-up path is where this gets dangerous rather than merely wrong. It
 * walks back to the window that most recently began and serves whatever is left
 * of it, so without a day check a Friday-only curfew would lock the phone on
 * Saturday morning, from a window that began on a night it was never meant to
 * run. Nothing about that failure looks like a bug from inside the app; it looks
 * like the app doing exactly what it was told, on the wrong day.
 */
class CurfewDaysTest {

    /** A Wednesday at nine in the evening, so "tomorrow" is a Thursday. */
    private val wednesdayEvening: LocalDateTime = LocalDateTime.of(2026, 8, 5, 21, 0)

    @Test
    fun `wednesday is the day this test thinks it is`() {
        assertEquals(DayOfWeek.WEDNESDAY, wednesdayEvening.dayOfWeek)
    }

    /** No days chosen is what this always was, and must stay that. */
    @Test
    fun `an empty day list still means every day`() {
        val next = ScheduledLockdown.nextRunAfter(wednesdayEvening, 22, 0, emptyList())
        assertEquals(LocalDateTime.of(2026, 8, 5, 22, 0), next)
        assertTrue(ScheduledLockdown.runsOn(wednesdayEvening, emptyList()))
    }

    @Test
    fun `a curfew due tonight runs tonight`() {
        // Wednesday selected, and ten o'clock has not arrived.
        val next = ScheduledLockdown.nextRunAfter(wednesdayEvening, 22, 0, listOf(3))
        assertEquals(LocalDateTime.of(2026, 8, 5, 22, 0), next)
    }

    @Test
    fun `a curfew not due tonight waits for the next chosen day`() {
        // Friday and Saturday only, asked on a Wednesday evening.
        val next = ScheduledLockdown.nextRunAfter(wednesdayEvening, 22, 0, listOf(5, 6))
        assertEquals(DayOfWeek.FRIDAY, next.dayOfWeek)
        assertEquals(LocalDateTime.of(2026, 8, 7, 22, 0), next)
    }

    /**
     * The wrap. Asked on a Wednesday for a Tuesday-only curfew, the answer is
     * six days away — and a walk that stopped at seven candidates could land one
     * short, because the first candidate may already be tomorrow.
     */
    @Test
    fun `a curfew earlier in the week waits for next week`() {
        val next = ScheduledLockdown.nextRunAfter(wednesdayEvening, 22, 0, listOf(2))
        assertEquals(DayOfWeek.TUESDAY, next.dayOfWeek)
        assertEquals(LocalDateTime.of(2026, 8, 11, 22, 0), next)
    }

    /** The same day, after the hour has passed, is next week rather than today. */
    @Test
    fun `today after the hour rolls to the next occurrence`() {
        val lateWednesday = LocalDateTime.of(2026, 8, 5, 23, 30)
        val next = ScheduledLockdown.nextRunAfter(lateWednesday, 22, 0, listOf(3))
        assertEquals(DayOfWeek.WEDNESDAY, next.dayOfWeek)
        assertEquals(LocalDateTime.of(2026, 8, 12, 22, 0), next)
    }

    // --- the catch-up, which is where a missing day check does damage --------

    @Test
    fun `a window that began on a chosen night is owed`() {
        val fridayNight = LocalDateTime.of(2026, 8, 7, 22, 0)
        assertEquals(DayOfWeek.FRIDAY, fridayNight.dayOfWeek)
        assertTrue(ScheduledLockdown.runsOn(fridayNight, listOf(5, 6)))
    }

    /**
     * And one that began on a night it does not run is not.
     *
     * Saturday morning after a Friday-only curfew: the window the catch-up walks
     * back to began on Friday night, which is fine — but a Thursday-only curfew
     * asked the same question must answer no, or the phone locks on a morning
     * nobody asked for.
     */
    @Test
    fun `a window from an unchosen night is not owed`() {
        val fridayNight = LocalDateTime.of(2026, 8, 7, 22, 0)
        assertFalse(ScheduledLockdown.runsOn(fridayNight, listOf(4)))
        assertFalse(ScheduledLockdown.runsOn(fridayNight, listOf(1, 2, 3)))
    }

    /** Every chosen day is reachable, whatever day it is asked on. */
    @Test
    fun `every weekday can be scheduled from any starting day`() {
        (1..7).forEach { startOffset ->
            val from = wednesdayEvening.plusDays(startOffset.toLong())
            (1..7).forEach { day ->
                val next = ScheduledLockdown.nextRunAfter(from, 22, 0, listOf(day))
                assertEquals(
                    "asked on ${from.dayOfWeek} for day $day",
                    day,
                    next.dayOfWeek.value,
                )
                assertTrue("the next run must be in the future", next.isAfter(from))
            }
        }
    }
}
