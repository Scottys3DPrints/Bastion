package com.bastion.app

import com.bastion.app.data.db.Confidence
import com.bastion.app.guard.policy.Catalogue
import com.bastion.app.guard.policy.ResolvedSurface
import com.bastion.app.guard.policy.Suppression
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telling the Reels tab apart from a reel opened from Search.
 *
 * ## Measured, not guessed
 *
 * Learn Mode on the phone, same build, both screens:
 *
 * ```
 *                                  Reels tab   from Search
 *   root_clips_layout                  y            y
 *   clips_linear_layout_container      y            y
 *   clips_viewer_view_pager            y            -
 *   clips_media_component              y            -
 * ```
 *
 * The two Search identifiers cover the window on the Reels tab as well, which
 * is why switching the tab's own signal off changed nothing: the Search
 * destination was recognising the tab and closing it. Two switches, one
 * outcome, and nothing in the interface to explain why.
 *
 * `clips_viewer_view_pager` is the tab announcing itself, and the tab signal
 * `clips_viewer` already matches it at a name boundary. So Reels-from-Search
 * counts only where the tab's marker is absent.
 */
class SuppressionTest {

    private val tab = "ig_reels"
    private val search = "ig_reels_search"

    private fun surface(id: String, enabled: Boolean = true) =
        ResolvedSurface(id, "sig_$id", Confidence.HIGH, enabled)

    @Test
    fun `the catalogue declares search as suppressed by the tab`() {
        assertEquals(tab, Catalogue.SUPPRESSED_BY[search])
        assertEquals(
            "nothing suppresses the tab itself, or it could never close",
            null,
            Catalogue.SUPPRESSED_BY[tab],
        )
    }

    /** The case this exists for: the tab must survive a Search rule that is on. */
    @Test
    fun `on the Reels tab the search surface does not count`() {
        val result = Suppression.apply(
            matched = listOf(surface(search)),
            visible = setOf(search, tab),
        )
        assertTrue("the tab is on screen, so Search must not close it", result.isEmpty())
    }

    /**
     * The half that would be easy to lose. The tab is recognised from a signal
     * the man has switched OFF — that is the whole situation — so suppression
     * has to work from what is visible, never from what is actionable.
     */
    @Test
    fun `the tab suppresses even when its own signal is switched off`() {
        val matched = listOf(surface(search, enabled = true))
        // The tab matched, but its evidence is disabled, so it is visible and
        // not actionable. It must still win.
        val result = Suppression.apply(matched, visible = setOf(search, tab))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `a reel opened from Search still closes`() {
        val result = Suppression.apply(
            matched = listOf(surface(search)),
            // No tab marker on this screen — measured.
            visible = setOf(search),
        )
        assertEquals(listOf(search), result.map { it.surfaceId })
    }

    @Test
    fun `the tab closes normally when its own signal is on`() {
        val result = Suppression.apply(
            matched = listOf(surface(tab), surface(search)),
            visible = setOf(tab, search),
        )
        assertEquals(
            "the tab acts; Search is suppressed by it",
            listOf(tab),
            result.map { it.surfaceId },
        )
    }

    @Test
    fun `surfaces with no suppressor are untouched`() {
        val others = listOf(surface("yt_shorts"), surface("fb_site"), surface("tt_foryou"))
        assertEquals(others, Suppression.apply(others, visible = setOf("ig_reels")))
    }

    @Test
    fun `an empty screen suppresses nothing and invents nothing`() {
        assertTrue(Suppression.apply(emptyList(), emptySet()).isEmpty())
    }

    /** Every suppressor must be a real surface, or the rule silently never fires. */
    @Test
    fun `every declared suppressor is a shipped surface`() {
        val ids = Catalogue.surfaces().map { it.id }.toSet()
        Catalogue.SUPPRESSED_BY.forEach { (suppressed, suppressor) ->
            assertTrue("$suppressed is not a shipped surface", suppressed in ids)
            assertTrue("$suppressor is not a shipped surface", suppressor in ids)
        }
    }
}
