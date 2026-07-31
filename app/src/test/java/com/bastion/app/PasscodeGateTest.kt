package com.bastion.app

import com.bastion.app.core.security.formatWait
import com.bastion.app.core.security.penaltyMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The escalation is the lock. If it stops growing, or grows so fast that a
 * mis-typed code costs the evening, the feature has failed in one direction or
 * the other — so both ends are pinned here.
 */
class PasscodeGateTest {

    @Test
    fun `honest mis-types cost nothing`() {
        for (failures in 1..3) {
            assertEquals("failure $failures should be free", 0L, penaltyMillis(failures))
        }
    }

    @Test
    fun `the fourth wrong code starts the clock`() {
        assertEquals(30_000L, penaltyMillis(4))
    }

    @Test
    fun `each further failure doubles the wait`() {
        assertEquals(60_000L, penaltyMillis(5))
        assertEquals(120_000L, penaltyMillis(6))
        assertEquals(240_000L, penaltyMillis(7))
    }

    @Test
    fun `the wait is capped at an hour`() {
        assertEquals(3_600_000L, penaltyMillis(12))
        assertEquals(3_600_000L, penaltyMillis(40))
    }

    /**
     * The bug this guards: `shl` wraps its shift count at 64, so without the
     * early return a high failure count produced a *shorter* delay than a lower
     * one — the lock loosening exactly as the attack got more determined.
     */
    @Test
    fun `the wait never shrinks as failures pile up`() {
        var previous = 0L
        for (failures in 1..200) {
            val current = penaltyMillis(failures)
            assertTrue("failure $failures went backwards: $previous then $current", current >= previous)
            previous = current
        }
    }

    @Test
    fun `waits read in seconds below a minute and whole minutes above`() {
        assertEquals("30s", formatWait(30_000L))
        assertEquals("1 min", formatWait(60_000L))
        assertEquals("2 min", formatWait(61_000L))
        assertEquals("60 min", formatWait(3_600_000L))
    }
}
