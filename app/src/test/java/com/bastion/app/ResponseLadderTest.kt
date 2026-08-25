package com.bastion.app

import com.bastion.app.data.db.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ladder, and the law that runs up it.
 *
 * Raising is instant; lowering waits out the cooling-off. That is the app's
 * oldest promise — the man who set the guard up cannot be the man who undoes it
 * in a weak minute — and v2 moves it from `BlockMode` strictness onto a rung.
 * Moving a promise from one representation to another is exactly when it gets
 * dropped, so the ordering it depends on is pinned here.
 */
class ResponseLadderTest {

    @Test
    fun `the rungs are ordered, and the order is the whole mechanism`() {
        assertEquals(
            listOf(
                Response.WATCH,
                Response.PAUSE,
                Response.COST,
                Response.CLOSE,
                Response.SEALED,
            ),
            Response.entries.sortedBy { it.level },
        )
        assertEquals("levels must be 0..4 with no gaps", (0..4).toList(), Response.entries.map { it.level })
    }

    @Test
    fun `every level round-trips`() {
        Response.entries.forEach { assertEquals(it, Response.ofLevel(it.level)) }
    }

    /**
     * A rung that cannot be read must not disarm a guard.
     *
     * If a future version writes level 5 and this one reads it, the safe answer
     * is Close. Reading it as Watch would turn a downgrade into a silent
     * unblocking of everything — and nobody would find out until it mattered.
     */
    @Test
    fun `an unreadable rung falls back to closing, never to watching`() {
        assertEquals(Response.CLOSE, Response.ofLevel(99))
        assertEquals(Response.CLOSE, Response.ofLevel(-1))
    }

    @Test
    fun `only watch declines to act`() {
        assertFalse(Response.WATCH.level > Response.WATCH.level)
        listOf(Response.PAUSE, Response.COST, Response.CLOSE, Response.SEALED).forEach {
            assertTrue("$it must act", it.level > Response.WATCH.level)
        }
    }

    /** Sealed is the top of the ladder, so nothing can outrank a sealed policy. */
    @Test
    fun `sealed is the strongest rung there is`() {
        assertEquals(Response.SEALED, Response.entries.maxByOrNull { it.level })
    }
}
