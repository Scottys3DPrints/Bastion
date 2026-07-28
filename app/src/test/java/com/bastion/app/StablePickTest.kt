package com.bastion.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors the index arithmetic the Mentor uses to pick a reply variant.
 *
 * `hashCode().absoluteValue % size` is wrong because `Int.MIN_VALUE.absoluteValue`
 * is still `Int.MIN_VALUE` — negative — so a rare input threw
 * IndexOutOfBounds on the one screen that must never crash.
 */
class StablePickTest {

    private fun index(seed: Int, size: Int) = Math.floorMod(seed, size)

    @Test
    fun `the pathological hash stays in range`() {
        assertTrue(index(Int.MIN_VALUE, 4) in 0..3)
        @Suppress("DEPRECATION")
        val brokenOldBehaviour = Math.abs(Int.MIN_VALUE)
        assertTrue("precondition: abs(MIN_VALUE) is still negative", brokenOldBehaviour < 0)
    }

    @Test
    fun `every int maps into range`() {
        val size = 5
        listOf(Int.MIN_VALUE, -1, 0, 1, Int.MAX_VALUE, -999_983, 12_345)
            .forEach { assertTrue("$it fell out of range", index(it, size) in 0 until size) }
    }

    @Test
    fun `the same seed always picks the same variant`() {
        val seed = "I can't sleep".hashCode()
        assertEquals(index(seed, 4), index(seed, 4))
    }
}
