package com.bastion.app

import com.bastion.app.domain.Rank
import com.bastion.app.domain.RankPoints
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

    /**
     * The arc on the home screen and the number printed under it have to agree.
     *
     * They come from two functions — progress() drives the ring, pointsToNext()
     * prints the caption — and for the whole life of the app only one of them
     * was ever rendered. Now that both are on screen at once, a man can see them
     * disagree, and a ring at 90% beside "600 points to go" is the kind of
     * detail that makes someone stop believing the rest of the numbers.
     */
    @Test
    fun `the ring and the caption tell the same story`() {
        Rank.entries.forEach { rank ->
            val next = Rank.next(rank) ?: return@forEach
            val span = next.threshold - rank.threshold
            listOf(0, span / 4, span / 2, span - 1).forEach { into ->
                val points = rank.threshold + into
                val fraction = Rank.progress(points)
                val remaining = Rank.pointsToNext(points)!!
                // Both describe the same position in the same span.
                val impliedRemaining = Math.round(span * (1f - fraction))
                assertEquals(
                    "at $points the ring says $fraction and the caption says $remaining",
                    remaining,
                    impliedRemaining,
                )
            }
        }
    }

    /**
     * The caption must never promise a rank that is not the one it names.
     *
     * "N to Guardian" is read as an instruction. If adding N points landed a man
     * anywhere other than Guardian, the app would have told him a specific
     * falsehood about his own effort.
     */
    @Test
    fun `paying the advertised points lands exactly on the advertised rank`() {
        listOf(0, 1, 149, 150, 999, 2_001, 6_499).forEach { points ->
            val next = Rank.next(Rank.forPoints(points)) ?: return@forEach
            val toGo = Rank.pointsToNext(points)!!
            assertEquals(next, Rank.forPoints(points + toGo))
            assertTrue("a rank you have reached cannot still be ahead of you", toGo > 0)
        }
    }

    /**
     * Rank never falls. This is the single design decision the app rests on —
     * a man who slips on day 61 keeps everything he has become — and it lives in
     * arithmetic that is easy to break with a well-meaning tweak.
     */
    @Test
    fun `points only ever move rank upward`() {
        var seen = Rank.RECRUIT
        (0..7_000 step 7).forEach { points ->
            val rank = Rank.forPoints(points)
            assertTrue("rank fell at $points", rank.ordinal >= seen.ordinal)
            seen = rank
        }
    }

    /**
     * Negative points cannot happen — nothing in RankPoints is negative — but
     * a corrupt row or a future subtraction must not crash the home screen.
     */
    @Test
    fun `nonsense input still names a rank`() {
        assertEquals(Rank.RECRUIT, Rank.forPoints(-1))
        assertEquals(Rank.RECRUIT, Rank.forPoints(Int.MIN_VALUE))
        assertEquals(Rank.OVERCOMER, Rank.forPoints(Int.MAX_VALUE))
        assertEquals(1f, Rank.progress(Int.MAX_VALUE), 0.001f)
    }

    /**
     * Every way to earn is worth something, and nothing is worth more than a
     * clean day.
     *
     * The second half is the values statement: this app rewards building, but
     * the day itself has to stay the biggest single thing, or the scoreboard
     * starts arguing with the point of the app.
     */
    @Test
    fun `every earning is positive and none outweighs a clean day`() {
        val earnings = mapOf(
            "clean day" to RankPoints.CLEAN_DAY,
            "habit" to RankPoints.HABIT_COMPLETED,
            "challenge day" to RankPoints.CHALLENGE_DAY,
            "check-in" to RankPoints.CHECK_IN,
            "urge resisted" to RankPoints.URGE_RESISTED,
            "lesson" to RankPoints.LESSON_READ,
            "panic session" to RankPoints.PANIC_SESSION_COMPLETED,
            "slip logged" to RankPoints.SLIP_LOGGED_HONESTLY,
        )
        earnings.forEach { (name, value) ->
            assertTrue("$name is worth $value; nothing here may punish", value > 0)
            assertTrue("$name outweighs a clean day", value <= RankPoints.CLEAN_DAY)
        }
    }
}
