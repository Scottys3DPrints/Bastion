package com.bastion.app

import com.bastion.app.guard.accessibility.FeedSurface
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shapes that were getting it wrong.
 *
 * Feed-only blocking is the app's central promise, and the way it failed was
 * the worst possible one: Instagram's ordinary home feed embeds inline reel
 * units whose view-ids are the same ones the rules name, so scrolling a feed
 * the user was allowed to see threw him out of the app entirely. Every case
 * below is a real surface, sized as it actually appears on a 1080x2400 phone.
 */
class FeedSurfaceTest {

    private val screenW = 1080
    private val screenH = 2400

    private fun nearFullscreen(w: Int, h: Int) =
        FeedSurface.isNearFullscreen(w, h, screenW, screenH)

    // --- the id itself -----------------------------------------------------

    @Test
    fun `a rule matches its own id`() {
        assertTrue(FeedSurface.idMatches("clips_viewer", "clips_viewer"))
        assertTrue(FeedSurface.idMatches("CLIPS_VIEWER", "clips_viewer"))
    }

    /** The substring bug: these are the inline previews on the home feed. */
    @Test
    fun `a rule does not match ids that merely start with it`() {
        assertFalse(FeedSurface.idMatches("reel_viewer_thumbnail", "reel_viewer"))
        assertFalse(FeedSurface.idMatches("clips_viewer_preview", "clips_viewer"))
        assertFalse(FeedSurface.idMatches("clips_viewer_container_small", "clips_viewer"))
    }

    @Test
    fun `a missing id never matches`() {
        assertFalse(FeedSurface.idMatches(null, "clips_viewer"))
    }

    // --- how much of the screen it covers ----------------------------------

    @Test
    fun `the real viewer fills the screen`() {
        assertTrue(nearFullscreen(w = 1080, h = 2400))
        // A viewer with the status and navigation bars still showing.
        assertTrue(nearFullscreen(w = 1080, h = 2100))
    }

    @Test
    fun `an inline reel tile on the home feed does not`() {
        // A single reel unit in the middle of the feed: full width, short.
        assertFalse(nearFullscreen(w = 1080, h = 700))
        // A tile in the reels tray: neither.
        assertFalse(nearFullscreen(w = 320, h = 560))
    }

    @Test
    fun `a half-height viewer is rejected at the boundary`() {
        assertFalse(nearFullscreen(w = 1080, h = 1200))
        // 60% of 2400 is 1440 — just under must fail, just over must pass.
        assertFalse(nearFullscreen(w = 1080, h = 1440))
        assertTrue(nearFullscreen(w = 1080, h = 1441))
    }

    @Test
    fun `a narrow full-height column is rejected`() {
        // 85% of 1080 is 918. A side panel is tall but not wide.
        assertFalse(nearFullscreen(w = 900, h = 2400))
        assertTrue(nearFullscreen(w = 919, h = 2400))
    }

    // --- which way it scrolls ---------------------------------------------

    @Test
    fun `the player is a vertical pager`() {
        assertTrue(FeedSurface.isVerticalScroller(scrollable = true, width = 1080, height = 2400))
    }

    /** The home feed's reel tray. Wide, short, and scrolls sideways. */
    @Test
    fun `a horizontal tray is not a player`() {
        assertFalse(FeedSurface.isVerticalScroller(scrollable = true, width = 1080, height = 620))
    }

    @Test
    fun `something that does not scroll at all is not a player`() {
        assertFalse(FeedSurface.isVerticalScroller(scrollable = false, width = 1080, height = 2400))
    }

    // --- the combination, which is the actual guard ------------------------

    @Test
    fun `the home feed fails at least one gate on every embedded shape`() {
        data class Surface(val name: String, val id: String, val w: Int, val h: Int, val scrolls: Boolean)

        val homeFeedSurfaces = listOf(
            Surface("inline reel unit", "clips_viewer_preview", 1080, 700, false),
            Surface("reels tray", "clips_viewer", 1080, 620, true),
            Surface("reel thumbnail", "reel_viewer_thumbnail", 340, 600, false),
        )

        homeFeedSurfaces.forEach { surface ->
            val passes = FeedSurface.idMatches(surface.id, "clips_viewer") &&
                nearFullscreen(surface.w, surface.h) &&
                FeedSurface.isVerticalScroller(surface.scrolls, surface.w, surface.h)
            assertFalse("${surface.name} would have blocked the home feed", passes)
        }
    }

    @Test
    fun `the actual reels player passes every gate`() {
        val passes = FeedSurface.idMatches("clips_viewer", "clips_viewer") &&
            nearFullscreen(1080, 2400) &&
            FeedSurface.isVerticalScroller(scrollable = true, width = 1080, height = 2400)
        assertTrue(passes)
    }
}
