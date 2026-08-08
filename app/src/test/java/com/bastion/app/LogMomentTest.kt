package com.bastion.app

import com.bastion.app.feature.track.LogMoment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * When it happened, and the moments that cannot have.
 *
 * The step this covers had a fault that produced bad data rather than a bad
 * layout: "Earlier today" set the time to ten at night whatever the hour, so
 * tapping it over lunch filed the urge at 10pm. The save clamps the timestamp
 * but keeps the hour, so the pattern chart quietly gained an evening that was
 * really a lunchtime — and the whole screen exists to answer "when does this
 * actually happen to me". A log a man cannot trust is worse than none.
 *
 * Fixed clocks throughout: a test that reads the real time cannot pin the
 * awkward hours, and the awkward hours are the entire point.
 */
class LogMomentTest {

    private val lunchtime = LocalDateTime.of(2026, 8, 5, 13, 20)
    private val justAfterMidnight = LocalDateTime.of(2026, 8, 5, 0, 30)

    // --- nothing may be in the future ---------------------------------------

    @Test
    fun `every shortcut is in the past at lunchtime`() {
        LogMoment.quickMoments(lunchtime).forEach {
            assertFalse("${it.label} is in the future", it.at.isAfter(lunchtime))
        }
    }

    /**
     * The half-past-midnight case, where most of the day has not happened.
     *
     * This is where the old shortcut was worst — nearly a full day of future to
     * land in — and where a naive fix that merely clamps would offer a chip that
     * silently means something other than its label.
     */
    @Test
    fun `every shortcut is in the past just after midnight`() {
        LogMoment.quickMoments(justAfterMidnight).forEach {
            assertFalse("${it.label} is in the future", it.at.isAfter(justAfterMidnight))
        }
    }

    /** "This morning" is withheld rather than offered as a future time. */
    @Test
    fun `this morning appears only once the morning has happened`() {
        assertTrue(LogMoment.quickMoments(lunchtime).any { it.label == "This morning" })
        assertFalse(LogMoment.quickMoments(justAfterMidnight).any { it.label == "This morning" })
    }

    /** Last night is always available, because yesterday always happened. */
    @Test
    fun `last night is offered whatever the hour`() {
        listOf(lunchtime, justAfterMidnight).forEach { now ->
            val last = LogMoment.quickMoments(now).firstOrNull { it.label == "Last night" }
            assertTrue("last night missing at $now", last != null)
            assertEquals(now.toLocalDate().minusDays(1), last!!.at.toLocalDate())
        }
    }

    @Test
    fun `a future moment is recognised as one`() {
        val today = lunchtime.toLocalDate()
        assertTrue(LogMoment.isFuture(today, LocalTime.of(22, 0), lunchtime))
        assertFalse(LogMoment.isFuture(today, LocalTime.of(10, 0), lunchtime))
        // Yesterday at ten at night is firmly in the past.
        assertFalse(LogMoment.isFuture(today.minusDays(1), LocalTime.of(22, 0), lunchtime))
    }

    // --- which hours can be chosen -------------------------------------------

    @Test
    fun `the hours are evenly spaced across the day`() {
        val hours = LogMoment.hourOptions()
        assertEquals(12, hours.size)
        assertEquals(0, hours.first())
        assertEquals(22, hours.last())
        // The old set stepped by three and then by two at the end, so the
        // spacing of the row said nothing about the spacing of the day.
        hours.zipWithNext().forEach { (a, b) -> assertEquals(2, b - a) }
    }

    @Test
    fun `an hour of today that has not arrived cannot be chosen`() {
        val today = lunchtime.toLocalDate()
        assertTrue(LogMoment.hourAvailable(12, today, lunchtime))
        assertFalse(LogMoment.hourAvailable(14, today, lunchtime))
        assertFalse(LogMoment.hourAvailable(22, today, lunchtime))
    }

    /** Every hour of a past day is fair game. */
    @Test
    fun `yesterday offers the whole day`() {
        val yesterday = lunchtime.toLocalDate().minusDays(1)
        LogMoment.hourOptions().forEach {
            assertTrue("$it should be available", LogMoment.hourAvailable(it, yesterday, lunchtime))
        }
    }

    /** A shortcut must light up an hour chip rather than leave the row blank. */
    @Test
    fun `the nearest hour rounds down to an offered one`() {
        assertEquals(12, LogMoment.nearestHour(LocalTime.of(13, 20)))
        assertEquals(22, LogMoment.nearestHour(LocalTime.of(23, 59)))
        assertEquals(0, LogMoment.nearestHour(LocalTime.of(0, 30)))
        // Never rounds up, which would put the mark on an hour not yet reached.
        LogMoment.hourOptions().forEach {
            assertTrue(LogMoment.nearestHour(LocalTime.of(it, 0)) <= it)
        }
    }

    // --- saying what is selected ---------------------------------------------

    @Test
    fun `today and yesterday are named rather than dated`() {
        val today = lunchtime.toLocalDate()
        assertTrue(LogMoment.describe(today, LocalTime.of(12, 0), lunchtime).startsWith("Today at"))
        assertTrue(
            LogMoment.describe(today.minusDays(1), LocalTime.of(22, 0), lunchtime)
                .startsWith("Yesterday at")
        )
        // Anything older gets a real date, because "5 days ago" alone is a
        // number a man has to count backwards from.
        assertTrue(
            LogMoment.describe(today.minusDays(5), LocalTime.of(22, 0), lunchtime)
                .contains("Jul")
        )
    }

    @Test
    fun `how long ago it was reads in the largest sensible unit`() {
        val now = lunchtime
        assertEquals("just now", LogMoment.ago(now, now))
        assertEquals("30 minutes ago", LogMoment.ago(now.minusMinutes(30), now))
        assertEquals("an hour ago", LogMoment.ago(now.minusHours(1), now))
        assertEquals("5 hours ago", LogMoment.ago(now.minusHours(5), now))
        assertEquals("a day ago", LogMoment.ago(now.minusDays(1), now))
        assertEquals("3 days ago", LogMoment.ago(now.minusDays(3), now))
    }

    /** The read-out says both the moment and its distance, in one line. */
    @Test
    fun `the description carries the time and the distance`() {
        val line = LogMoment.describe(
            lunchtime.toLocalDate().minusDays(1),
            LocalTime.of(22, 0),
            lunchtime,
        )
        assertTrue(line, line.contains("Yesterday"))
        assertTrue(line, line.contains("10 PM"))
        assertTrue(line, line.contains("ago"))
    }
}
