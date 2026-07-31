package com.bastion.app

import com.bastion.app.guard.GuardWatchdog
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The wording carries the weight here: a breach "since just now" reads as a
 * slip, "for 3 days" reads as a decision, and the partner receiving one of
 * these needs to be able to tell which it was.
 */
class BreachDurationTest {

    private fun minutes(n: Long) = n * 60_000L

    @Test
    fun `a fresh breach reads as immediate`() {
        assertEquals("since just now", GuardWatchdog.describeDuration(0L))
        assertEquals("since just now", GuardWatchdog.describeDuration(minutes(4)))
    }

    @Test
    fun `under an hour is counted in minutes`() {
        assertEquals("for 5 minutes", GuardWatchdog.describeDuration(minutes(5)))
        assertEquals("for 59 minutes", GuardWatchdog.describeDuration(minutes(59)))
    }

    @Test
    fun `hours and days round down to whole units`() {
        assertEquals("for an hour", GuardWatchdog.describeDuration(minutes(90)))
        assertEquals("for 5 hours", GuardWatchdog.describeDuration(minutes(5 * 60)))
        assertEquals("for a day", GuardWatchdog.describeDuration(minutes(30 * 60)))
        assertEquals("for 3 days", GuardWatchdog.describeDuration(minutes(3 * 24 * 60)))
    }
}
