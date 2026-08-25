package com.bastion.app

import com.bastion.app.data.db.MatchType
import com.bastion.app.data.db.SignalEntity
import com.bastion.app.guard.policy.SignalHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Noticing a rule has died, before the man does.
 *
 * A view id belongs to another company's app. Instagram renames one, the signal
 * matches nothing from that moment, and nothing throws, nothing is logged and
 * no screen changes — the guard reports itself fully armed and quietly does
 * less every month. That is the decay this detects.
 *
 * The other half of the job is not crying wolf. A warning system that fires on
 * apps a man has already uninstalled is one he learns to scroll past, and then
 * it is worth nothing on the day it is right.
 */
class SignalHealthTest {

    private val ig = "com.instagram.android"
    private val now = 1_700_000_000_000L
    private val windowStart = now - 14L * 24 * 60 * 60 * 1000

    private fun signal(
        id: String = "s1",
        scope: String? = ig,
        matchType: MatchType = MatchType.VIEW_ID,
        lastMatchedAt: Long = 0L,
        enabled: Boolean = true,
    ) = SignalEntity(
        id = id,
        surfaceId = "ig_reels",
        matchType = matchType,
        matchValue = "clips_viewer",
        scopePackage = scope,
        lastMatchedAt = lastMatchedAt,
        enabled = enabled,
    )

    @Test
    fun `a silent signal in a heavily used app is reported`() {
        val found = SignalHealth.drifted(
            signals = listOf(signal()),
            opensInWindow = mapOf(ig to 41),
            installed = setOf(ig),
            windowStart = windowStart,
        )
        assertEquals(1, found.size)
        assertEquals(ig, found.first().packageName)
        assertEquals(
            "the count travels with the finding so the card can state a fact",
            41,
            found.first().opensInWindow,
        )
    }

    @Test
    fun `light use is not enough to accuse a rule`() {
        val found = SignalHealth.drifted(
            signals = listOf(signal()),
            opensInWindow = mapOf(ig to SignalHealth.MIN_USES - 1),
            installed = setOf(ig),
            windowStart = windowStart,
        )
        assertTrue("under the threshold there is no evidence yet", found.isEmpty())
    }

    @Test
    fun `a signal that has matched recently is healthy`() {
        val found = SignalHealth.drifted(
            signals = listOf(signal(lastMatchedAt = now - 1000)),
            opensInWindow = mapOf(ig to 100),
            installed = setOf(ig),
            windowStart = windowStart,
        )
        assertTrue(found.isEmpty())
    }

    @Test
    fun `a match older than the window counts as silence`() {
        val found = SignalHealth.drifted(
            signals = listOf(signal(lastMatchedAt = windowStart - 1)),
            opensInWindow = mapOf(ig to 100),
            installed = setOf(ig),
            windowStart = windowStart,
        )
        assertEquals(1, found.size)
    }

    /**
     * An uninstalled app is silence, not drift.
     *
     * This is the noise rule, and it matters more than it looks: without it the
     * screen fills with warnings about apps he already dealt with, and the one
     * real warning arrives in a list nobody reads any more.
     */
    @Test
    fun `an uninstalled app is never accused`() {
        val found = SignalHealth.drifted(
            signals = listOf(signal()),
            opensInWindow = mapOf(ig to 100),
            installed = emptySet(),
            windowStart = windowStart,
        )
        assertTrue(found.isEmpty())
    }

    /**
     * An address is a fact about a destination and does not rot when somebody
     * reshuffles a layout. Only app-scoped evidence can drift this way.
     */
    @Test
    fun `an address signal is never reported as drifted`() {
        val found = SignalHealth.drifted(
            signals = listOf(signal(scope = null, matchType = MatchType.URL)),
            opensInWindow = mapOf(ig to 100),
            installed = setOf(ig),
            windowStart = windowStart,
        )
        assertTrue(found.isEmpty())
    }

    @Test
    fun `a switched-off signal is not broken, it is off`() {
        val found = SignalHealth.drifted(
            signals = listOf(signal(enabled = false)),
            opensInWindow = mapOf(ig to 100),
            installed = setOf(ig),
            windowStart = windowStart,
        )
        assertTrue(found.isEmpty())
    }

    @Test
    fun `several silent signals in one app are all reported`() {
        val found = SignalHealth.drifted(
            signals = listOf(signal("a"), signal("b"), signal("c", lastMatchedAt = now)),
            opensInWindow = mapOf(ig to 60),
            installed = setOf(ig),
            windowStart = windowStart,
        )
        assertEquals(setOf("a", "b"), found.map { it.signal.id }.toSet())
    }
}
