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
     * How much of the window's width the player has to span.
     *
     * A genuine viewer is edge to edge. Nothing narrower is the screen you are
     * looking at; it is a column, a sheet or a card sitting on one.
     */
    const val WIDTH_RATIO = 0.85

    /**
     * How much of the window's height the player has to span.
     *
     * This is the number that actually separates the two, and it has to be this
     * high. A full-width 9:16 reel unit inline in the home feed is 1920px on a
     * 1080x2400 phone — 80% of the screen. Any threshold below that lets the
     * ordinary feed through, which is how 60% managed to: the old gate was set
     * beneath the very shape it was meant to exclude.
     *
     * A genuine player is immersive by design and spans 90%+ even when it is
     * inset by both the status bar and the navigation bar. 85% sits in the gap
     * between the two, with room for a status bar and a gesture bar and none for
     * a post in a list.
     */
    const val HEIGHT_RATIO = 0.85

    /**
     * How far from the window's top and bottom edges the player may stop.
     *
     * This is the check that was missing, and its absence was the whole bug.
     * The old rule asked only *how big* a node was — taller than 60% of the
     * screen and nearly full width — and never asked **where it was**. An inline
     * reel in Instagram's ordinary home feed is a 9:16 unit at full width: on a
     * 1080x2400 phone that is about 1920px tall, comfortably past 60%, and it
     * sits in the middle of a feed with posts above and below it. It passed. The
     * user scrolled his normal feed, a tall reel unit drifted through, and
     * Bastion threw him out of the app — the exact failure the size gate had
     * been added to prevent.
     *
     * Position is the other half of the answer. The real player is pinned to the
     * window: it starts at the top edge and ends at the bottom one, and it stays
     * there while you scroll, because scrolling moves the video inside it rather
     * than moving it. A tile in a feed is offset by whatever is above it and
     * slides as you drag.
     *
     * Slack is generous here because [HEIGHT_RATIO] is doing the discriminating.
     * What this adds is the case size cannot see: a feed of full-height items,
     * where an item taller than the window is dragged halfway out of it. Unclipped
     * bounds still report the full height, so span alone would call it covered
     * while most of it is off screen.
     */
    const val EDGE_SLACK = 0.15

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

    /**
     * Whether this node *covers* the window rather than merely being large
     * inside it.
     *
     * Measured against the window, not the display. Those are the same rectangle
     * right up until the phone is in split screen, where the display is twice
     * the window and a genuine full-bleed player measures 50% tall — so the old
     * denominator meant Reels was simply not recognised in multi-window. Failing
     * open is the wrong direction for the one check the promise rests on.
     *
     * Coordinates are screen-absolute, as [android.view.accessibility.AccessibilityNodeInfo.getBoundsInScreen]
     * reports them, so the window's own top offset has to come in rather than
     * being assumed to be zero.
     */
    fun coversWindow(
        top: Int,
        bottom: Int,
        width: Int,
        windowTop: Int,
        windowBottom: Int,
        windowWidth: Int,
    ): Boolean {
        val windowHeight = windowBottom - windowTop
        if (windowHeight <= 0 || windowWidth <= 0) return false
        val slack = windowHeight * EDGE_SLACK
        return (bottom - top) >= windowHeight * HEIGHT_RATIO &&
            width > windowWidth * WIDTH_RATIO &&
            top <= windowTop + slack &&
            bottom >= windowBottom - slack
    }

    /**
     * The player is a vertical pager. The home feed's reel tray scrolls
     * *horizontally*, so it fails here even on the rare occasion it is large
     * enough to pass the size test.
     *
     * The fallback, used only when a node does not say which way it scrolls.
     * Guessing from the aspect ratio is all this can do, and on a portrait phone
     * it guesses "vertical" for anything full-screen — which is wrong for every
     * full-screen *horizontal* pager there is. Prefer [scrollsVertically].
     */
    fun isVerticalScroller(scrollable: Boolean, width: Int, height: Int): Boolean =
        scrollable && height > width

    /**
     * Which way it actually scrolls, from what the node itself reports.
     *
     * Android has exposed directional scroll actions since API 23 — well below
     * Bastion's floor — and asking the node beats inferring from its shape. The
     * inference was quietly wrong in one case that matters: a full-screen
     * horizontal pager on a portrait phone is taller than it is wide, so the
     * aspect-ratio test called Instagram's *stories* viewer a vertical pager and
     * blocked it as though it were Reels. Tapping a friend's story from the
     * ordinary feed got the user thrown out of the app, which from where he was
     * standing is indistinguishable from the normal feed being blocked.
     *
     * A node that pages left and right is not the short-form player, whatever
     * shape it is. A node that says nothing falls back to the old guess, which is
     * still right for the trays it was written for.
     */
    fun scrollsVertically(
        canScrollUpDown: Boolean,
        canScrollLeftRight: Boolean,
        scrollable: Boolean,
        width: Int,
        height: Int,
    ): Boolean = when {
        canScrollUpDown -> true
        canScrollLeftRight -> false
        else -> isVerticalScroller(scrollable, width, height)
    }
}
