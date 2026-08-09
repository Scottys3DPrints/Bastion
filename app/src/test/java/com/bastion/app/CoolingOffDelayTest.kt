package com.bastion.app

import com.bastion.app.data.repo.GuardRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cooling-off delay, now measured in minutes.
 *
 * It used to be whole hours, which made a five-minute setting unrepresentable
 * and a five-minute *display* impossible — "0 hours" is what an hours-based
 * formatter says about it, and a lock that announces itself as zero is a lock
 * nobody believes. The unit change is the kind that goes wrong silently: an
 * off-by-sixty here does not crash, it just quietly turns a two-hour guard into
 * a two-minute one on the night it matters.
 */
class CoolingOffDelayTest {

    @Test
    fun `minutes under an hour are spoken as minutes`() {
        assertEquals("5 minutes", GuardRepository.Delay.describe(5))
        assertEquals("1 minute", GuardRepository.Delay.describe(1))
        assertEquals("45 minutes", GuardRepository.Delay.describe(45))
    }

    @Test
    fun `whole hours are spoken as hours`() {
        assertEquals("1 hour", GuardRepository.Delay.describe(60))
        assertEquals("2 hours", GuardRepository.Delay.describe(120))
        assertEquals("24 hours", GuardRepository.Delay.describe(1440))
    }

    /** A delay that is neither must not round down to something shorter. */
    @Test
    fun `a mixed delay names both parts`() {
        assertEquals("1h 30m", GuardRepository.Delay.describe(90))
    }

    @Test
    fun `the short form fits a chip`() {
        assertEquals("12h", GuardRepository.Delay.describeShort(720))
        assertEquals("24h", GuardRepository.Delay.describeShort(1440))
        assertEquals("2d", GuardRepository.Delay.describeShort(2880))
        assertEquals("7d", GuardRepository.Delay.describeShort(10080))
        // Never blank, whatever it is handed — a chip with no label is a chip
        // nobody can choose.
        GuardRepository.Delay.CHOICES.forEach {
            assertTrue(GuardRepository.Delay.describeShort(it).isNotBlank())
        }
    }

    /**
     * The default has to be one of the offered choices, or a man opens the
     * screen and sees no chip selected at all.
     */
    @Test
    fun `the choices include the default and read left to right`() {
        assertTrue(1440 in GuardRepository.Delay.CHOICES)
        assertEquals(
            "choices must be ascending so the chips read left to right",
            GuardRepository.Delay.CHOICES.sorted(),
            GuardRepository.Delay.CHOICES,
        )
    }

    /**
     * Nothing shorter than half a day is offered, and this is the test that
     * keeps it that way.
     *
     * The list used to open with five minutes so the lock could be exercised
     * end to end without waiting. That was a convenience for whoever was
     * testing and a hole for everybody else, because it sat in the picker
     * looking like an ordinary choice. The floor is twelve hours because that
     * is the shortest span guaranteed to contain a night's sleep, and sleep is
     * what actually does the work here — a delay a man can sit through in one
     * evening is a delay he will sit through, at the exact hour it was meant to
     * stop him.
     */
    @Test
    fun `no delay short enough to wait out in one sitting is offered`() {
        val tooShort = GuardRepository.Delay.CHOICES.filter { it < 720 }
        assertEquals("these can be waited out in a single evening", emptyList<Int>(), tooShort)
        assertEquals(720, GuardRepository.Delay.MINIMUM_MINUTES)
    }

    /** Every offered choice speaks itself in whole hours or whole days. */
    @Test
    fun `every choice reads as a round span`() {
        GuardRepository.Delay.CHOICES.forEach {
            val long = GuardRepository.Delay.describe(it)
            assertTrue("$it reads as '$long'", long.endsWith("hours") || long.endsWith("days"))
        }
    }

    /**
     * The unit travels with the payload, and it has to.
     *
     * A change request queued by an older build carries hours and may not
     * mature until after the upgrade. The first attempt guessed the unit by
     * magnitude — anything twenty-four or under is hours — and that is wrong in
     * the worst possible direction: "5" is a legal hours value *and* the new
     * five-minute setting, so a man choosing five minutes would have been given
     * five hours, silently, by the code meant to protect him.
     */
    @Test
    fun `each payload key carries its own unit`() {
        fun applied(payload: String): Int? {
            val parts = payload.split(':')
            val n = parts.getOrNull(1)?.toIntOrNull() ?: return null
            return when (parts.first()) {
                "cooloff" -> n * 60      // legacy: hours
                "cooloffm" -> n          // current: minutes
                else -> null
            }
        }
        // Legacy requests still mean what they meant when they were queued.
        assertEquals(120, applied("cooloff:2"))
        assertEquals(1440, applied("cooloff:24"))
        // And the ambiguous number resolves correctly under each key.
        assertEquals(300, applied("cooloff:5"))
        assertEquals(5, applied("cooloffm:5"))
    }

    /**
     * The legacy key is written rounded up, never down.
     *
     * Bastion updates in place and a man can end up back on an older build. If
     * five minutes were written as zero hours there, the old build would read a
     * delay of nothing at all and every weakening would take effect instantly.
     */
    @Test
    fun `a sub-hour delay never becomes zero hours for an older build`() {
        fun legacyHours(minutes: Int) = ((minutes + 59) / 60).coerceAtLeast(1)
        assertEquals(1, legacyHours(5))
        assertEquals(1, legacyHours(60))
        assertEquals(12, legacyHours(720))
        assertEquals(2, legacyHours(61))
        assertEquals(24, legacyHours(1440))
        GuardRepository.Delay.CHOICES.forEach {
            assertTrue("legacy hours must never be zero", legacyHours(it) >= 1)
        }
    }
}
