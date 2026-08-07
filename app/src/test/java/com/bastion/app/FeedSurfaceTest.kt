package com.bastion.app

import com.bastion.app.guard.accessibility.FeedSurface
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shapes that were getting it wrong.
 *
 * Feed-only blocking is the app's central promise, and the way it failed was the
 * worst possible one: Instagram's ordinary home feed embeds inline reel units
 * whose view-ids are close cousins of the ones the rules name, so scrolling a
 * feed the user was allowed to see threw him out of the app entirely.
 *
 * Every case below is a real surface, positioned and sized as it actually
 * appears on a 1080x2400 phone. Positioned matters as much as sized, and that
 * was the bug: the old gate asked only how big a node was, and a full-width 9:16
 * reel unit in the middle of the home feed is genuinely 1920px tall. It passed
 * every size test there was, because the question was never where it sat.
 */
class FeedSurfaceTest {

    private val windowW = 1080
    private val windowH = 2400

    /** A node at [top], [height] tall and [width] wide, in a full-screen window. */
    private fun covers(top: Int, height: Int, width: Int = 1080) =
        FeedSurface.coversWindow(
            top = top,
            bottom = top + height,
            width = width,
            windowTop = 0,
            windowBottom = windowH,
            windowWidth = windowW,
        )

    // --- the id itself -----------------------------------------------------

    @Test
    fun `a rule matches its own id`() {
        assertTrue(FeedSurface.idMatches("clips_viewer", "clips_viewer"))
        assertTrue(FeedSurface.idMatches("CLIPS_VIEWER", "clips_viewer"))
    }

    /**
     * A rule follows an id that grew a suffix.
     *
     * These ids belong to other companies' apps and get renamed without notice,
     * usually by gaining a descriptive tail — clips_viewer becomes
     * clips_viewer_view_pager — and when that happens exact equality stops the
     * rule firing with no symptom beyond the block quietly not happening.
     *
     * This was deliberately forbidden once, and the reason has since moved
     * somewhere better: see the test below, which is the one that actually
     * protects the home feed.
     */
    @Test
    fun `a rule follows an id that gained a suffix`() {
        assertTrue(FeedSurface.idMatches("clips_viewer_view_pager", "clips_viewer"))
        assertTrue(FeedSurface.idMatches("reel_recycler_view", "reel_recycler"))
    }

    /** But only at a name boundary, never mid-word. */
    @Test
    fun `a rule does not match a different id that merely shares a prefix`() {
        assertFalse(FeedSurface.idMatches("reel_recycler2", "reel_recycler"))
        assertFalse(FeedSurface.idMatches("clips_viewerx", "clips_viewer"))
        assertFalse(FeedSurface.idMatches("reel_shelf_recycler", "reel_recycler"))
        assertFalse(FeedSurface.idMatches(null, "clips_viewer"))
        assertFalse(FeedSurface.idMatches("clips_viewer", ""))
    }

    /**
     * The inline previews still match by id — and are still not blocked.
     *
     * This is the trade being made explicit. `clips_viewer_preview` now passes
     * the id test, exactly as it did under the original `contains` bug. What
     * stops it is geometry: a preview is a tile in a scrolling feed and does not
     * cover the window, which the home-feed tests further down check directly.
     * Geometry is a better place for this to be decided, because it describes
     * what the surface *is* rather than what somebody happened to name it.
     */
    @Test
    fun `an inline preview matches by id and is rejected by geometry`() {
        assertTrue(FeedSurface.idMatches("clips_viewer_preview", "clips_viewer"))
        assertFalse(covers(top = 420, height = 1920))
    }

    @Test
    fun `a missing id never matches`() {
        assertFalse(FeedSurface.idMatches(null, "clips_viewer"))
    }

    // --- covering the window, which is not the same as being big -----------

    @Test
    fun `the real viewer covers the window`() {
        assertTrue(covers(top = 0, height = 2400))
        // A player inset by the status bar and the gesture bar.
        assertTrue(covers(top = 96, height = 2400 - 96 - 130))
    }

    /**
     * The regression this whole gate exists for.
     *
     * A 9:16 reel unit at full width is 1920px tall on this phone — 80% of the
     * screen, past any size threshold worth setting — and it sits in the middle
     * of the home feed with posts above and below. Scrolling past it must not be
     * mistaken for being in Reels.
     */
    @Test
    fun `a full-width inline reel unit in the home feed does not`() {
        assertFalse(covers(top = 420, height = 1920))
        // The same unit dragged further up the screen as the feed scrolls.
        assertFalse(covers(top = 240, height = 1920))
        // And near the top, still with feed visible beneath it.
        assertFalse(covers(top = 150, height = 1920))
    }

    /**
     * The margin, stated so it is not discovered by being wrong.
     *
     * Two numbers sit close together and both are real. The threat is a
     * full-width 9:16 inline reel unit, which measures 1920 of 2400 — 80%. The
     * gate is 85%. So a genuine player has 360px of total inset to play with
     * before it drops under the bar and stops being recognised, and a status bar
     * plus a gesture bar plus Instagram's bottom navigation has to fit inside
     * that budget.
     *
     * These are the shapes at the edges of it. If Reels ever stops being caught
     * on a real handset, this is the first place to look — and the fix is not to
     * lower HEIGHT_RATIO, which would re-open the inline unit at 80%, but to
     * measure what the player actually reports and reconsider the pair together.
     */
    @Test
    fun `a player inset by both system bars still covers`() {
        // Status bar 96, gesture bar 130. Comfortable.
        assertTrue(covers(top = 96, height = 2174))
        // Status bar 96 and a tall bottom navigation, 264. At the limit.
        assertTrue(covers(top = 96, height = 2040))
        // One pixel more inset than the gate allows. Documented, not endorsed.
        assertFalse(covers(top = 96, height = 2039))
    }

    @Test
    fun `an ordinary feed post does not`() {
        // Full width, a third of the screen.
        assertFalse(covers(top = 700, height = 800))
        // A tile in the reels tray: neither wide nor tall.
        assertFalse(covers(top = 300, height = 560, width = 320))
    }

    /**
     * The span threshold, at the boundary.
     *
     * 85% of 2400 is 2040. This is the number that excludes the inline reel unit
     * above, and the one place it is worth pinning to the pixel — it was set at
     * 60% before, which is *below* the 80% that a full-width 9:16 feed unit
     * actually measures, so the gate was open on exactly the shape it named.
     */
    @Test
    fun `the span threshold is bounded`() {
        assertTrue(covers(top = 0, height = 2040))
        assertFalse(covers(top = 0, height = 2039))
    }

    /**
     * A feed of full-height items, dragged.
     *
     * Span cannot see this on its own: unclipped bounds report the item's whole
     * height even when most of it is off screen. Position is what catches it.
     */
    @Test
    fun `a tall item dragged out of the window is not covering it`() {
        // 2400 tall, scrolled so only the bottom third is visible.
        assertFalse(covers(top = -1600, height = 2400))
        // And scrolled so only the top third is.
        assertFalse(covers(top = 1600, height = 2400))
    }

    @Test
    fun `a narrow full-height column is rejected`() {
        // 85% of 1080 is 918. A side panel is tall but not wide.
        assertFalse(covers(top = 0, height = 2400, width = 900))
        assertTrue(covers(top = 0, height = 2400, width = 919))
    }

    /**
     * A player that overhangs the window still covers it.
     *
     * getBoundsInScreen reports unclipped bounds, so a pager mid-fling can
     * report a negative top or a bottom past the window. That is more coverage,
     * not less.
     */
    @Test
    fun `bounds extending past the window still count`() {
        assertTrue(covers(top = -200, height = 2800))
    }

    /**
     * Split screen: the window is half the display.
     *
     * The ratios are measured against the window for this reason. Against the
     * display, a genuine full-bleed player in the bottom half of a split screen
     * measures 50% tall and was never recognised — Reels simply worked in
     * multi-window, which is not a subtle hole once anyone finds it.
     */
    @Test
    fun `a player fills a split-screen window`() {
        val topOfLowerHalf = 1200
        assertTrue(
            FeedSurface.coversWindow(
                top = topOfLowerHalf,
                bottom = 2400,
                width = 1080,
                windowTop = topOfLowerHalf,
                windowBottom = 2400,
                windowWidth = 1080,
            )
        )
        // And a feed post inside that same half-height window does not.
        assertFalse(
            FeedSurface.coversWindow(
                top = topOfLowerHalf + 300,
                bottom = topOfLowerHalf + 700,
                width = 1080,
                windowTop = topOfLowerHalf,
                windowBottom = 2400,
                windowWidth = 1080,
            )
        )
    }

    @Test
    fun `a window of no size never matches`() {
        assertFalse(
            FeedSurface.coversWindow(0, 2400, 1080, windowTop = 0, windowBottom = 0, windowWidth = 0)
        )
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

    // --- which way it scrolls, asked rather than guessed --------------------

    /**
     * The stories bug.
     *
     * Instagram's stories viewer is full-screen and pages left and right between
     * people. On a portrait phone it is taller than it is wide, so the shape-based
     * guess called it a vertical pager and blocked it as Reels — a story tapped
     * from the ordinary feed threw the user out of the app. What the node reports
     * about itself settles it: left-and-right is not the short-form player,
     * whatever shape it happens to be.
     */
    @Test
    fun `a fullscreen horizontal pager is not a player however tall it is`() {
        assertFalse(
            FeedSurface.scrollsVertically(
                canScrollUpDown = false,
                canScrollLeftRight = true,
                scrollable = true,
                width = 1080,
                height = 2400,
            )
        )
    }

    @Test
    fun `a node that pages up and down is a player`() {
        assertTrue(
            FeedSurface.scrollsVertically(
                canScrollUpDown = true,
                canScrollLeftRight = false,
                scrollable = true,
                width = 1080,
                height = 2400,
            )
        )
    }

    /** Some pagers report both. Vertical wins — it can still be scrolled that way. */
    @Test
    fun `a node that pages both ways counts as a player`() {
        assertTrue(
            FeedSurface.scrollsVertically(
                canScrollUpDown = true,
                canScrollLeftRight = true,
                scrollable = true,
                width = 1080,
                height = 2400,
            )
        )
    }

    @Test
    fun `a node that says nothing falls back to its shape`() {
        // The old guess, kept for the trays it was written for.
        assertTrue(
            FeedSurface.scrollsVertically(
                canScrollUpDown = false,
                canScrollLeftRight = false,
                scrollable = true,
                width = 1080,
                height = 2400,
            )
        )
        assertFalse(
            FeedSurface.scrollsVertically(
                canScrollUpDown = false,
                canScrollLeftRight = false,
                scrollable = true,
                width = 1080,
                height = 620,
            )
        )
        assertFalse(
            FeedSurface.scrollsVertically(
                canScrollUpDown = false,
                canScrollLeftRight = false,
                scrollable = false,
                width = 1080,
                height = 2400,
            )
        )
    }

    // --- the combination, which is the actual guard ------------------------

    /**
     * One surface as the service sees it: an id, a rectangle, and whether it or
     * a covering ancestor scrolls vertically. `scrollsVertically` stands for the
     * service's self-or-ancestor test, which is why the home feed's own
     * RecyclerView makes it true for everything inside it — that ancestor is
     * always there, and it is why id and geometry have to carry the decision.
     */
    private data class Surface(
        val name: String,
        val id: String,
        val top: Int,
        val height: Int,
        val width: Int = 1080,
        val scrollsVertically: Boolean = true,
    )

    private fun Surface.wouldBlock(ruleId: String): Boolean =
        FeedSurface.idMatches(id, ruleId) &&
            covers(top, height, width) &&
            FeedSurface.isVerticalScroller(scrollsVertically, width, height)

    @Test
    fun `nothing on the instagram home feed blocks it`() {
        val homeFeed = listOf(
            // The one that was getting through: a real reel, inline, mid-feed.
            Surface("inline reel unit", "clips_viewer", top = 420, height = 1920),
            Surface("inline reel preview", "clips_viewer_preview", top = 420, height = 1920),
            Surface("reels tray", "clips_viewer", top = 300, height = 620),
            Surface("reel thumbnail", "reel_viewer_thumbnail", top = 700, height = 600, width = 340),
            Surface("the feed itself", "recycler_view", top = 0, height = 2400),
        )

        homeFeed.forEach { surface ->
            listOf("clips_viewer", "reel_viewer").forEach { rule ->
                assertFalse(
                    "${surface.name} would have blocked the Instagram home feed",
                    surface.wouldBlock(rule),
                )
            }
        }
    }

    @Test
    fun `nothing on the youtube home feed or a watch page blocks it`() {
        val youtube = listOf(
            Surface("home feed list", "results", top = 0, height = 2400),
            Surface("shorts shelf", "reel_shelf_recycler", top = 800, height = 1100),
            // A watch page: the video sits at the top, comments scroll below.
            Surface("watch player", "player_view", top = 96, height = 610),
            Surface("watch page list", "watch_list", top = 706, height = 1694),
        )

        youtube.forEach { surface ->
            listOf("reel_recycler", "reel_player_page_container", "reel_watch_fragment_root")
                .forEach { rule ->
                    assertFalse(
                        "${surface.name} would have blocked ordinary YouTube",
                        surface.wouldBlock(rule),
                    )
                }
        }
    }

    @Test
    fun `the actual reels player passes every gate`() {
        assertTrue(
            Surface("reels viewer", "clips_viewer", top = 0, height = 2400)
                .wouldBlock("clips_viewer")
        )
    }

    @Test
    fun `the actual shorts player passes every gate`() {
        // The pager itself, edge to edge.
        assertTrue(
            Surface("shorts pager", "reel_recycler", top = 0, height = 2400)
                .wouldBlock("reel_recycler")
        )
        // And a page inside it, inset by the status bar, scrollable only via the
        // pager above it.
        assertTrue(
            Surface("shorts page", "reel_player_page_container", top = 96, height = 2304)
                .wouldBlock("reel_player_page_container")
        )
    }
}
