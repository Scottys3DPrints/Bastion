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
    fun idMatches(idSegment: String?, matchValue: String): Boolean {
        if (idSegment == null || matchValue.isBlank()) return false
        if (idSegment.equals(matchValue, ignoreCase = true)) return true
        // A rule also matches an id that *extends* it at a name boundary:
        // clips_viewer catches clips_viewer_view_pager.
        //
        // This used to be forbidden, and the reason it was forbidden has since
        // been fixed somewhere better. The original bug was `contains`, which
        // matched clips_viewer_preview — an inline tile in the ordinary home
        // feed — and threw a man out of a feed he was allowed to be in. Exact
        // equality fixed that at a time when nothing else could.
        //
        // Since then the geometry gate arrived: a match must also cover the
        // window and page vertically, which excludes every thumbnail, tray and
        // preview on its own, and is checked by the home-feed tests below.
        // Exact equality is now doing no work that geometry does not already do
        // — and it has a cost geometry does not have. These ids belong to other
        // companies' apps and get renamed without notice, usually by gaining a
        // suffix, and when that happens the rule stops firing with no symptom
        // beyond the block quietly not happening.
        //
        // The boundary matters. Requiring the next character to be '_' keeps
        // `reel_recycler` from catching `reel_recycler2` style neighbours while
        // still following an id that grew a descriptive tail.
        return idSegment.length > matchValue.length &&
            idSegment.startsWith(matchValue, ignoreCase = true) &&
            idSegment[matchValue.length] == '_'
    }

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
    /**
     * Whether an address matches a blocked destination.
     *
     * Compared on host and path only, after normalising away the scheme, a
     * leading `www.` or `m.`, and any query string. Instagram's reels live at
     * `instagram.com/reels`, and the same page arrives as `www.instagram.com/
     * reels/`, `m.instagram.com/reels?next=...` and `https://instagram.com/
     * reels/` depending on how it was reached — all four are one destination and
     * a rule should not have to name them separately.
     */
    fun urlMatches(text: String, fragment: String): Boolean {
        if (fragment.isBlank()) return false
        val url = normaliseUrl(text)
        val rule = normaliseUrl(fragment)
        if (url.isEmpty() || rule.isEmpty()) return false
        if (url == rule) return true
        // The boundary, and it is load-bearing now that rules name whole hosts.
        //
        // A bare startsWith matches instagram.com.evil.co against instagram.com,
        // because the lookalike genuinely begins with the real host — a rule
        // meant to close one site would have quietly claimed every domain
        // registered underneath a name that starts the same way. Requiring the
        // next character to be a path separator means the rule has to have
        // consumed the whole host, and it does the same job one level down:
        // youtube.com/shorts no longer catches youtube.com/shortsomething.
        return url.length > rule.length &&
            url.startsWith(rule) &&
            url[rule.length] == '/'
    }

    private fun normaliseUrl(raw: String): String {
        var s = raw.trim().lowercase()
        s = s.removePrefix("https://").removePrefix("http://")
        s = s.substringBefore('?').substringBefore('#')
        s = s.removePrefix("www.").removePrefix("m.")
        return s.trimEnd('/')
    }

    /**
     * Whether a string is an address rather than something a man wrote.
     *
     * This is the privacy boundary for browser rules and it is deliberately
     * strict. Bastion's contract is that message bodies, posts and field
     * contents are never read, and a rule that scanned page text for "reel"
     * would break it on the first conversation. So only strings that *are* an
     * address are ever considered: no whitespace, short, containing a dot, and
     * either carrying a scheme or looking like host/path.
     *
     * Text that merely contains a link — a message with a URL in a sentence —
     * fails on the whitespace test, which is the case that matters most.
     */
    fun looksLikeUrl(text: String): Boolean {
        val s = text.trim()
        if (s.isEmpty() || s.length > MAX_URL_LENGTH) return false
        if (s.any { it.isWhitespace() }) return false
        val bare = s.removePrefix("https://").removePrefix("http://")
        val host = bare.substringBefore('/')
        return host.contains('.') && host.length >= 4 && !host.endsWith('.')
    }

    private const val MAX_URL_LENGTH = 200

    /**
     * Whether a node is plausibly the address bar rather than a link on a page.
     *
     * A browser's URL bar sits at the top of the window and spans most of its
     * width. A link inside a conversation or an article does neither, and this
     * is what stops a friend sending a reel from walling the chat he sent it in.
     *
     * Used only as the fallback. Every real browser exposes a known identifier
     * for its address bar, and that is checked first; this catches the in-app
     * browsers — Messenger's, Instagram's — which do not.
     */
    fun isAddressBar(
        top: Int,
        width: Int,
        windowTop: Int,
        windowHeight: Int,
        windowWidth: Int,
    ): Boolean {
        if (windowHeight <= 0 || windowWidth <= 0) return false
        return top <= windowTop + windowHeight * ADDRESS_BAR_BAND &&
            width >= windowWidth * ADDRESS_BAR_WIDTH
    }

    /** The top fifth of the window. Enough for a toolbar under a status bar. */
    const val ADDRESS_BAR_BAND = 0.20

    /** Half the width. A link in a paragraph is rarely this wide, a bar always is. */
    const val ADDRESS_BAR_WIDTH = 0.5

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
