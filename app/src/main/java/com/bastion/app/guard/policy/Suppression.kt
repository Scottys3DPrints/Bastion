package com.bastion.app.guard.policy

/**
 * Which of the destinations on screen actually count.
 *
 * Two surfaces can be present at once when an app builds them from the same
 * parts. Instagram does: a reel opened from Search is drawn with the same
 * covering containers as the Reels tab, so the Search surface matches on the
 * tab as well, and a man who closed Search reels found the tab closed too with
 * nothing in the interface to explain it.
 *
 * The fix is a precedence rule, not a better identifier. Where a destination is
 * declared to be suppressed by another, it only counts when that other one is
 * not on the screen.
 *
 * Pure, because this is the kind of rule that is easy to get backwards and the
 * cost of getting it backwards is a feed left open.
 */
object Suppression {

    /**
     * [matched] is what could act -- surfaces whose evidence is switched on.
     * [visible] is every surface recognised on the screen, switched on or not.
     *
     * The difference matters and is the whole point: the Reels tab is still the
     * Reels tab when a man has stopped blocking it, and that is precisely when
     * this has to keep telling the two apart.
     */
    fun apply(matched: List<ResolvedSurface>, visible: Set<String>): List<ResolvedSurface> =
        matched.filterNot { surface ->
            val suppressor = Catalogue.SUPPRESSED_BY[surface.surfaceId]
            suppressor != null && suppressor in visible
        }
}
