package com.bastion.app

import com.bastion.app.domain.Rank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rank is the emotional load-bearing wall of the whole product: it must be
 * monotonic, and nothing anywhere may ever subtract from it.
 */
class RankTest {

    @Test
    fun `starts at recruit`() {
        assertEquals(Rank.RECRUIT, Rank.forPoints(0))
    }

    @Test
    fun `rank never decreases as points rise`() {
        var previous = Rank.RECRUIT.tier
        for (points in 0..8_000 step 25) {
            val tier = Rank.forPoints(points).tier
            assertTrue("rank went backwards at $points points", tier >= previous)
            previous = tier
        }
    }

    @Test
    fun `every threshold lands exactly on its own rank`() {
        Rank.entries.forEach { rank ->
            assertEquals(rank, Rank.forPoints(rank.threshold))
        }
    }

    @Test
    fun `progress stays within bounds and completes at the final rank`() {
        for (points in 0..8_000 step 25) {
            val progress = Rank.progress(points)
            assertTrue("progress out of range at $points: $progress", progress in 0f..1f)
        }
        assertEquals(1f, Rank.progress(Rank.OVERCOMER.threshold + 5_000), 0.0001f)
    }

    @Test
    fun `points to next rank runs out only at the top`() {
        assertNull(Rank.pointsToNext(Rank.OVERCOMER.threshold))
        assertEquals(Rank.WATCHMAN.threshold, Rank.pointsToNext(0))
    }

    @Test
    fun `both vocabularies name every rank`() {
        Rank.entries.forEach { rank ->
            assertTrue(rank.displayName(faithMode = true).isNotBlank())
            assertTrue(rank.displayName(faithMode = false).isNotBlank())
        }
    }
}
