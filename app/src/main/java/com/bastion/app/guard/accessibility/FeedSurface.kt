package com.bastion.app.guard.accessibility

/**
 * The rules that decide whether a matched node is the short-form player itself
 * or just a piece of it embedded in a feed you are allowed to be in.
 *
 * Pure arithmetic on purpose. The decision that mattered — "is this Reels, or
 * is this the ordinary home feed with a reel tile in it" — lived inside the
 * accessibility service, where it could only be exercised by holding a real
 * phone and scrolling Instagram. Every false positive was therefore found by
 * the user. Here the same rules can be checked against the exact shapes that
 * were getting it wrong.
 */
internal object FeedSurface {

    /**
     * How much of the screen the player has to cover.
     *
     * Height is the looser of the two on purpose: a status bar, a navigation
     * bar or a half-open comment sheet eats real height from a genuine viewer,
     * while anything actually full-screen still spans nearly the full width.
     */
    const val HEIGHT_RATIO = 0.60
    const val WIDTH_RATIO = 0.85

    /**
     * Exact segment equality, never `contains`.
     *
     * `contains("reel_viewer")` also matched `reel_viewer_thumbnail` and
     * `clips_viewer_preview` — the inline previews Instagram embeds in the
     * ordinary home feed. A rule names one destination, not every id that
     * happens to share its prefix.
     */
    fun idMatches(idSegment: String?, matchValue: String): Boolean =
        idSegment != null && idSegment.equals(matchValue, ignoreCase = true)

    /** The real viewer fills the display; an inline tile is a fraction of it. */
    fun isNearFullscreen(
        width: Int,
        height: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): Boolean = height > screenHeight * HEIGHT_RATIO && width > screenWidth * WIDTH_RATIO

    /**
     * The player is a vertical pager. The home feed's reel tray scrolls
     * *horizontally*, so it fails here even on the rare occasion it is large
     * enough to pass the size test.
     */
    fun isVerticalScroller(scrollable: Boolean, width: Int, height: Int): Boolean =
        scrollable && height > width
}
