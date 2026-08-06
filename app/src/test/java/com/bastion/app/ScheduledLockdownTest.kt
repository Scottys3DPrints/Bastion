package com.bastion.app

import com.bastion.app.core.alarm.ScheduledLockdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * The clock arithmetic behind the nightly lockout.
 *
 * Everything else about a scheduled lockdown is the button's code path, already
 * covered by [LockdownTest]. What is new here is exactly two questions — when
 * the next one is due, and whether one was slept through — and both are the kind
 * of thing that is wrong by an hour at the boundaries and correct all the way
 * through a casual read.
 *
 * Getting either wrong is not a cosmetic bug. Too eager, and the phone locks
 * itself in the middle of being configured for a stretch that cannot be called
 * off; too lax, and a reboot at 10:01 is the whole bypass.
 */
class ScheduledLockdownTest {

    private fun at(day: Int, hour: Int, minute: Int) =
        LocalDateTime.of(2026, 3, 14, hour, minute).plusDays((day - 14).toLong())

    // --- when the next one is due ------------------------------------------

    @Test
    fun `later today when the hour has not come round yet`() {
        val next = ScheduledLockdown.nextRunAfter(at(14, 9, 30), 22, 0)
        assertEquals(at(14, 22, 0), next)
    }

    @Test
    fun `tomorrow once it has passed`() {
        val next = ScheduledLockdown.nextRunAfter(at(14, 23, 30), 22, 0)
        assertEquals(at(15, 22, 0), next)
    }

    /**
     * The minute it fires, the next one is tomorrow — never a second run now.
     *
     * The receiver re-arms from inside its own callback, so an alarm that
     * resolved to "right now" would fire, re-arm itself for the same instant and
     * keep going. Strictly-after is what makes the series daily.
     */
    @Test
    fun `never schedules for the instant it is asked about`() {
        val next = ScheduledLockdown.nextRunAfter(at(14, 22, 0), 22, 0)
        assertEquals(at(15, 22, 0), next)
    }

    @Test
    fun `a start after midnight is still the next one round`() {
        assertEquals(at(14, 1, 15), ScheduledLockdown.nextRunAfter(at(14, 0, 5), 1, 15))
        assertEquals(at(15, 1, 15), ScheduledLockdown.nextRunAfter(at(14, 1, 16), 1, 15))
    }

    // --- what a missed window still owes -----------------------------------

    @Test
    fun `nothing owed before the window opens`() {
        assertEquals(0, ScheduledLockdown.catchUpSeconds(at(14, 21, 59), 22, 0, 3600))
    }

    @Test
    fun `nothing owed after it has closed`() {
        assertEquals(0, ScheduledLockdown.catchUpSeconds(at(14, 23, 1), 22, 0, 3600))
    }

    /** A phone switched back on at 10:20 owes the forty minutes still on the clock. */
    @Test
    fun `the remainder of a window already open`() {
        assertEquals(40 * 60, ScheduledLockdown.catchUpSeconds(at(14, 22, 20), 22, 0, 3600))
    }

    /**
     * Never more than was left, however long the phone was off.
     *
     * The promise is a time of night, not a length owed no matter when it is
     * noticed. Serving a full hour at 10:59 would end at midnight — an hour the
     * man never agreed to, imposed by a reboot.
     */
    @Test
    fun `catching up never extends past the window`() {
        val owed = ScheduledLockdown.catchUpSeconds(at(14, 22, 59), 22, 0, 3600)
        assertEquals(60, owed)
        assertTrue(owed <= 3600)
    }

    /** The boundary itself: a window that has just closed owes nothing at all. */
    @Test
    fun `the closing second owes nothing`() {
        assertEquals(0, ScheduledLockdown.catchUpSeconds(at(14, 23, 0), 22, 0, 3600))
    }

    /**
     * A window that crosses midnight is still running in the small hours.
     *
     * Ten o'clock plus four hours ends at two. Half past midnight is two and a
     * half hours in, so ninety minutes are still owed — the date having changed
     * in between must not turn this into "no window is open".
     */
    @Test
    fun `a window from last night is still open after midnight`() {
        val owed = ScheduledLockdown.catchUpSeconds(at(15, 0, 30), 22, 0, 4 * 3600)
        assertEquals(90 * 60, owed)
    }

    @Test
    fun `a rehearsal length is over in seconds, not caught up an hour later`() {
        assertEquals(20, ScheduledLockdown.catchUpSeconds(at(14, 22, 0).plusSeconds(10), 22, 0, 30))
        assertEquals(0, ScheduledLockdown.catchUpSeconds(at(14, 22, 1), 22, 0, 30))
    }

    /** A window identifies itself by its start, which is what "already served" compares. */
    @Test
    fun `the open window is yesterdays until the hour comes round`() {
        assertEquals(at(13, 22, 0), ScheduledLockdown.windowStartAtOrBefore(at(14, 3, 0), 22, 0))
        assertEquals(at(14, 22, 0), ScheduledLockdown.windowStartAtOrBefore(at(14, 22, 30), 22, 0))
    }
}
