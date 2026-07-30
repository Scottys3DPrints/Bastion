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
}
