package com.bastion.app

import com.bastion.app.data.prefs.Settings
import com.bastion.app.guard.lockdown.Lockdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A lockdown that quietly expires early, or reads as running when it is not,
 * would be worse than not having one — the whole value is that the man trusts
 * it to hold for exactly as long as he asked.
 */
class LockdownTest {

    private fun endingIn(millis: Long) =
        Settings(lockdownUntil = System.currentTimeMillis() + millis)

    @Test
    fun `no lockdown by default`() {
        assertFalse(Lockdown.isActive(Settings()))
        assertEquals(0L, Lockdown.remainingMinutes(Settings()))
    }

    @Test
    fun `active while the clock is still running`() {
        assertTrue(Lockdown.isActive(endingIn(60 * 60 * 1000)))
    }

    @Test
    fun `inactive the moment it expires`() {
        assertFalse(Lockdown.isActive(endingIn(-1)))
        assertFalse(Lockdown.isActive(Settings(lockdownUntil = 1L)))
    }

    @Test
    fun `remaining time never goes negative`() {
        assertEquals(0L, Lockdown.remainingMinutes(endingIn(-500_000)))
    }

    @Test
    fun `remaining time is reported in whole minutes`() {
        val minutes = Lockdown.remainingMinutes(endingIn(90 * 60 * 1000))
        assertTrue("expected about 90, got $minutes", minutes in 89..90)
    }

    // --- how the length is spoken about ----------------------------------
    //
    // One formatter feeds the confirmation dialog, the plan summary and the
    // message sent to a partner. If they ever disagreed, the app would be
    // asking a man to accept one thing and telling someone else another.

    @Test
    fun `a rehearsal length is described in seconds`() {
        assertEquals("30 seconds", Lockdown.describe(30))
        assertEquals("30s", Lockdown.describeShort(30))
    }

    @Test
    fun `real lengths read naturally and singular`() {
        assertEquals("1 hour", Lockdown.describe(3600))
        assertEquals("6 hours", Lockdown.describe(6 * 3600))
        assertEquals("1 day", Lockdown.describe(24 * 3600))
        assertEquals("3 days", Lockdown.describe(72 * 3600))
    }

    @Test
    fun `the short form fits a chip`() {
        assertEquals("1h", Lockdown.describeShort(3600))
        assertEquals("6h", Lockdown.describeShort(6 * 3600))
        assertEquals("1d", Lockdown.describeShort(24 * 3600))
        assertEquals("3d", Lockdown.describeShort(72 * 3600))
    }

    /** A 30-second lockdown must not report "0m" for its whole life. */
    @Test
    fun `remaining time is available in seconds`() {
        val settings = Settings(
            lockdownSeconds = 30,
            lockdownUntil = System.currentTimeMillis() + 25_000,
        )
        assertEquals(0L, Lockdown.remainingMinutes(settings))
        assertTrue(Lockdown.remainingSeconds(settings) in 20..25)
    }
}
