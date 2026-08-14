package com.bastion.app

import com.bastion.app.data.content.MotivationItem
import com.bastion.app.data.repo.FeedMix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a day's feed is a mixture or a block of one thing.
 *
 * The reported symptom was "one day I only have bible quotes, another day
 * other things", and it was not a matter of taste — it was arithmetic. The
 * hour-of-day preference was expressed as a weight, weights sorted the pool,
 * and the portion was sliced off the top. Two points added to every scripture
 * puts all five hundred of them above all fifteen hundred quotes, so the hour
 * did not colour the feed, it chose the feed: scripture at midnight and
 * nothing else, quotes at breakfast and nothing else.
 *
 * These are the two properties that were missing, held separately because they
 * broke for different reasons.
 */
class FeedMixTest {

    private fun item(id: String, type: String) = MotivationItem(
        id = id,
        type = type,
        text = "line $id",
        attribution = "Someone",
        moments = listOf("daily"),
    )

    /** 500 scriptures and 1500 quotes, the real shape of the library. */
    private val pool =
        (1..500).map { item("s$it", "scripture") } +
            (1..1500).map { item("q$it", "quote") }

    /**
     * A portion has to contain more than one kind of thing.
     *
     * The interleave existed all along and ran *after* the portion was cut, so
     * when the top of the pool was five hundred scriptures it round-robined a
     * handful of identical items and achieved exactly nothing.
     */
    @Test
    fun `a portion is never all one type`() {
        listOf(1, 8, 14, 23).forEach { hour ->
            val portion = FeedMix.interleave(pool, hour).take(12)
            val types = portion.map { it.type }.toSet()
            assertTrue("at ${hour}:00 the portion was all ${types.first()}", types.size > 1)
        }
    }

    /**
     * The hour still colours it. Steadying leads late, further-looking leads
     * early — a lead, not a monopoly, which is the whole correction.
     */
    @Test
    fun `the hour decides what leads and not what wins`() {
        assertEquals("scripture", FeedMix.interleave(pool, 1).first().type)
        assertEquals("quote", FeedMix.interleave(pool, 8).first().type)
        // And the type that does not lead is still present, close behind.
        assertTrue("quote" in FeedMix.interleave(pool, 1).take(4).map { it.type })
        assertTrue("scripture" in FeedMix.interleave(pool, 8).take(4).map { it.type })
    }

    /**
     * Two days must not produce the same order.
     *
     * The old tiebreak was `id.hashCode().mod(7)` — seven buckets, and the same
     * seven every day this app will ever run. Within a band the order was fixed
     * forever, and the only reason a second day looked different at all was
     * that the first day's items had been marked as seen.
     */
    @Test
    fun `each day orders the library differently`() {
        val ids = pool.map { it.id }
        val monday = ids.sortedBy { FeedMix.shuffleKey(it, 20_000L) }
        val tuesday = ids.sortedBy { FeedMix.shuffleKey(it, 20_001L) }
        assertNotEquals("two days in a row came out identical", monday, tuesday)

        // Overlap at the top should be near chance, not near total.
        val shared = monday.take(30).intersect(tuesday.take(30).toSet()).size
        assertTrue("the first thirty barely moved: $shared of 30 shared", shared < 10)
    }

    /** And within one day it must not move, or the screen rearranges as it is read. */
    @Test
    fun `the same day always orders the same way`() {
        val ids = pool.map { it.id }
        assertEquals(
            ids.sortedBy { FeedMix.shuffleKey(it, 20_000L) },
            ids.sortedBy { FeedMix.shuffleKey(it, 20_000L) },
        )
    }

    /** Neighbouring ids must not stay neighbours, which a weak mix leaves them. */
    @Test
    fun `sequential ids do not land together`() {
        val keys = (1..200).map { FeedMix.shuffleKey("q$it", 20_000L) }
        val ascending = keys.zipWithNext().count { (a, b) -> b > a }
        // A poor mix leaves these almost fully ordered in one direction.
        assertTrue("keys track the id sequence: $ascending of 199 ascending", ascending in 60..140)
    }
}
