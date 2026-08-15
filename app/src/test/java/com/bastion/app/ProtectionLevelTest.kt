package com.bastion.app

import com.bastion.app.data.db.BlockMode
import com.bastion.app.data.db.GuardedAppEntity
import com.bastion.app.feature.guardui.OFFERED_LEVELS
import com.bastion.app.feature.guardui.ProtectionLevel
import com.bastion.app.feature.guardui.consequence
import com.bastion.app.feature.guardui.levelOf
import com.bastion.app.feature.guardui.title
import com.bastion.app.feature.guardui.toMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one question a card asks, and the storage it stands in front of.
 *
 * Blocking a feed used to need four things true at once, each set somewhere
 * else: Guard running, the app added to one list, its mode set to "feeds only",
 * and a rule switched on in a second list. Three out of four produced no error
 * and no block — an app that opened normally under a screen of switches that
 * all looked on.
 *
 * ProtectionLevel exists to make that unrepresentable: one choice, and setting
 * it sets everything it needs. These tests hold the translation honest, because
 * a level that maps to the wrong mode is the same silent failure in new
 * clothing.
 */
class ProtectionLevelTest {

    private fun app(mode: BlockMode) =
        GuardedAppEntity(packageName = "com.instagram.android", label = "Instagram", mode = mode)

    /** Every mode a man can be in has a level that describes it. */
    @Test
    fun `every stored mode reads back as a level`() {
        BlockMode.entries.forEach { mode ->
            val level = levelOf(app(mode))
            assertEquals("$mode did not survive the round trip", mode, level.toMode())
        }
    }

    /**
     * And an app that is not guarded is not a mode at all.
     *
     * This is the state the whole restructure turns on: "not blocked" is the
     * absence of a row, not a row saying nothing. Representing it as a mode is
     * how a service ends up guarded-but-inert, which is exactly the shape of
     * the bug that started this.
     */
    @Test
    fun `nothing guarded is the absence of a mode`() {
        assertEquals(ProtectionLevel.NOTHING, levelOf(null))
        assertNull(ProtectionLevel.NOTHING.toMode())
    }

    /**
     * Leaving is not offered as a level.
     *
     * It lives at the bottom of the sheet as its own action so that a thumb
     * aiming at "just the feed" cannot land on "stop blocking this entirely" —
     * the two are one row apart and about as far apart in consequence as
     * anything on the screen.
     */
    @Test
    fun `stopping is not one of the choices`() {
        assertTrue(ProtectionLevel.NOTHING !in OFFERED_LEVELS)
        assertEquals(
            "every other level should be offered",
            ProtectionLevel.entries.size - 1,
            OFFERED_LEVELS.size,
        )
    }

    /**
     * Each option says what happens, not what it is called.
     *
     * "Feeds only" is a setting name; "Reels, Shorts and For You close as they
     * open" is the thing a man is agreeing to. The screen this replaced named
     * modes and left the consequence to be inferred, which is most of why it
     * had to be explained in a paragraph above it.
     */
    @Test
    fun `every level states a consequence and a name`() {
        ProtectionLevel.entries.forEach {
            assertTrue("${it.name} has no title", it.title().isNotBlank())
            assertTrue("${it.name} has no consequence", it.consequence().isNotBlank())
            assertTrue(
                "${it.name}'s consequence just repeats its title",
                it.consequence() != it.title(),
            )
        }
    }

    /** The feed level has to promise the browser too, or it is the old one. */
    @Test
    fun `the feed level covers browsers as well as the app`() {
        val said = ProtectionLevel.FEED.consequence().lowercase()
        assertTrue("the feed level does not mention a browser: $said", "browser" in said)
        assertNotNull(ProtectionLevel.FEED.toMode())
    }
}
