package com.bastion.app.guard.policy

import com.bastion.app.data.db.CategoryEntity
import com.bastion.app.data.db.Confidence
import com.bastion.app.data.db.MatchType
import com.bastion.app.data.db.Response
import com.bastion.app.data.db.SignalEntity
import com.bastion.app.data.db.SurfaceEntity

/**
 * What Bastion ships knowing: the categories, the destinations inside them, and
 * the evidence that recognises each one.
 *
 * Pure Kotlin with no Android imports, like [com.bastion.app.guard.accessibility.FeedSurface]
 * and [com.bastion.app.guard.accessibility.GuardedScreens] — the two most
 * reliable pieces of this app, and reliable precisely because they had to be
 * testable on a laptop.
 *
 * ## Every value here is carried over, not invented
 *
 * The signal values are the built-in feed rules, unchanged and re-homed. They
 * were won one at a time, several of them on a phone with Learn Mode after a
 * guess had failed, and a few of them carry the scar tissue of a false positive
 * that threw a man out of something he was allowed to be in. Rewriting them
 * while restructuring around them would have thrown away the only part of the
 * old model that was never wrong.
 *
 * Signal ids are deliberately the *old* `feed_rule` ids. That makes the 7 → 8
 * migration a rename rather than a reinterpretation: a row a man had switched
 * off stays off, because it is matched by id and not by guesswork.
 */
object Catalogue {

    // --- Services --------------------------------------------------------
    //
    // A service is what a man names — "TikTok" — and a package is one way to
    // reach it. v1 used the package as the owner of a rule and then made
    // address rules universal, at which point `com.zhiliaoapp.musically` was
    // doing duty as a label for TikTok-the-service and the schema was lying.
    // Both facts are kept, and kept apart.

    const val INSTAGRAM = "instagram"
    const val YOUTUBE = "youtube"
    const val TIKTOK = "tiktok"
    const val FACEBOOK = "facebook"
    const val SNAPCHAT = "snapchat"
    const val X = "x"
    const val REDDIT = "reddit"

    const val PKG_INSTAGRAM = "com.instagram.android"
    const val PKG_YOUTUBE = "com.google.android.youtube"
    const val PKG_TIKTOK = "com.zhiliaoapp.musically"
    const val PKG_TIKTOK_LITE = "com.ss.android.ugc.trill"
    const val PKG_FACEBOOK = "com.facebook.katana"
    const val PKG_SNAPCHAT = "com.snapchat.android"
    const val PKG_X = "com.twitter.android"
    const val PKG_REDDIT = "com.reddit.frontpage"

    /** Which packages reach a service. One service, possibly several doors. */
    val PACKAGES_BY_SERVICE: Map<String, List<String>> = mapOf(
        INSTAGRAM to listOf(PKG_INSTAGRAM),
        YOUTUBE to listOf(PKG_YOUTUBE),
        TIKTOK to listOf(PKG_TIKTOK, PKG_TIKTOK_LITE),
        FACEBOOK to listOf(PKG_FACEBOOK),
        SNAPCHAT to listOf(PKG_SNAPCHAT),
        X to listOf(PKG_X),
        REDDIT to listOf(PKG_REDDIT),
    )

    /** The reverse, for the migration and for the per-app inspector. */
    val SERVICE_BY_PACKAGE: Map<String, String> =
        PACKAGES_BY_SERVICE.flatMap { (service, pkgs) -> pkgs.map { it to service } }.toMap()

    // --- Categories ------------------------------------------------------

    const val PORN = "PORN"
    const val SHORT_FEED = "SHORT_FEED"

    /**
     * The shipped catalogue, at the size Phase 1 needs.
     *
     * The full list in the spec — borderline, dating, anonymous chat, the ways
     * round, the phone after dark — is Phase 3's, and arrives with the screen
     * that lets a man choose between them. Shipping the labels before the
     * screen would put names in the database that nothing can turn on, which is
     * the same "configured but wired to nothing" failure the restructure exists
     * to end.
     *
     * `chosen` is false on both. Phase 1 changes no behaviour, and behaviour in
     * v1 comes from what a man guarded, which the migration turns into policies
     * of its own.
     */
    fun categories(): List<CategoryEntity> = listOf(
        CategoryEntity(
            key = PORN,
            label = "Pornography",
            chosen = false,
            response = Response.CLOSE.level,
        ),
        CategoryEntity(
            key = SHORT_FEED,
            label = "Endless feeds",
            chosen = false,
            response = Response.CLOSE.level,
        ),
    )

    // --- Surfaces --------------------------------------------------------

    fun surfaces(): List<SurfaceEntity> = listOf(
        surface("ig_reels", INSTAGRAM, SHORT_FEED, "Instagram Reels"),
        surface("ig_site", INSTAGRAM, SHORT_FEED, "All of Instagram"),

        surface("yt_shorts", YOUTUBE, SHORT_FEED, "YouTube Shorts"),
        surface("yt_explicit", YOUTUBE, PORN, "Videos with explicit titles"),
        surface("yt_site", YOUTUBE, SHORT_FEED, "All of YouTube"),

        surface("tt_foryou", TIKTOK, SHORT_FEED, "TikTok For You"),
        surface("tt_site", TIKTOK, SHORT_FEED, "All of TikTok"),

        surface("fb_reels", FACEBOOK, SHORT_FEED, "Facebook Reels and Watch"),
        surface("fb_site", FACEBOOK, SHORT_FEED, "All of Facebook"),

        surface("snap_spotlight", SNAPCHAT, SHORT_FEED, "Snapchat Spotlight"),
        surface("x_video", X, SHORT_FEED, "X video feed"),
        surface("reddit_video", REDDIT, SHORT_FEED, "Reddit video and Popular"),
    )

    // --- Signals ---------------------------------------------------------

    /**
     * The evidence, with the old rule values intact.
     *
     * Read the `scope` column rather than the value: it is the whole of what
     * changed. A view id is a fact about one app's layout and says so. An
     * address is a fact about the destination and carries null, which means
     * anywhere — no sentinel package, no browser list, and no ninth generation
     * of "which window is this?".
     */
    fun signals(): List<SignalEntity> = listOf(
        // --- Instagram ---------------------------------------------------
        //
        // `clips_viewer` is the Reels tab. `reel_viewer` is *stories*, and that
        // is not a typo in either direction: Instagram called stories "reels"
        // years before the Reels product existed, and named Reels "clips"
        // internally when it shipped. The rule that matched stories closed the
        // app every time a friend's story was opened — the exact failure
        // feed-only guarding exists to avoid, wearing the name of the thing it
        // was meant to catch. There is no story signal to want here.
        signal("ig_reels", PKG_INSTAGRAM, MatchType.VIEW_ID, "clips_viewer", PKG_INSTAGRAM),
        // Reels opened from Search, which the Reels-tab signal never saw.
        // Captured with Learn Mode on the phone after a week of guessing at
        // Instagram's naming and being wrong every time.
        //
        // The same capture offered `swipeable_nav_view_pager_inner_recycler_view`
        // and `layout_container_swipeable`, both marked as would-block, and both
        // traps: they are the main tab pager, on screen for the home feed and
        // the profile too, so either would have closed the whole app. Name the
        // destination, never the container it happens to sit in.
        signal("ig_reels", PKG_INSTAGRAM, MatchType.VIEW_ID, "root_clips_layout", PKG_INSTAGRAM),
        signal(
            "ig_reels", PKG_INSTAGRAM, MatchType.VIEW_ID,
            "clips_linear_layout_container", PKG_INSTAGRAM,
        ),
        signal("ig_reels", PKG_INSTAGRAM, MatchType.URL, "instagram.com/reel", null),
        signal("ig_site", PKG_INSTAGRAM, MatchType.URL, "instagram.com", null, enabled = false),

        // --- YouTube -----------------------------------------------------
        signal("yt_shorts", PKG_YOUTUBE, MatchType.VIEW_ID, "reel_recycler", PKG_YOUTUBE),
        signal(
            "yt_shorts", PKG_YOUTUBE, MatchType.VIEW_ID,
            "reel_player_page_container", PKG_YOUTUBE,
        ),
        signal(
            "yt_shorts", PKG_YOUTUBE, MatchType.VIEW_ID,
            "reel_watch_fragment_root", PKG_YOUTUBE,
        ),
        signal("yt_shorts", PKG_YOUTUBE, MatchType.URL, "youtube.com/shorts", null),
        // The one signal in the app that reads what is written rather than how
        // the screen is built, and the only one that is LOW.
        //
        // It exists because a YouTube watch page is the same page for a lecture
        // and for the thing a man came here to stop: no view id separates those,
        // and youtube.com is never going on a domain list. It carries no match
        // value of its own — the words are the shipped list, see TitleFilter.
        //
        // LOW is not a hedge, it is the cap. A net is not a wall, and a net that
        // could close an app on its own would eventually throw a man out of a
        // lecture. See ConfidenceCapTest.
        signal(
            "yt_explicit", PKG_YOUTUBE, MatchType.TITLE, "adult", null,
            confidence = Confidence.LOW,
        ),
        signal("yt_site", PKG_YOUTUBE, MatchType.URL, "youtube.com", null, enabled = false),

        // --- TikTok ------------------------------------------------------
        signal("tt_foryou", PKG_TIKTOK, MatchType.VIEW_ID, "feed_tab_view", PKG_TIKTOK),
        signal("tt_foryou", PKG_TIKTOK, MatchType.VIEW_ID, "viewpager_container", PKG_TIKTOK),
        signal("tt_foryou", PKG_TIKTOK_LITE, MatchType.VIEW_ID, "feed_tab_view", PKG_TIKTOK_LITE),
        signal("tt_foryou", PKG_TIKTOK, MatchType.URL, "tiktok.com/foryou", null),
        signal("tt_foryou", PKG_TIKTOK, MatchType.URL, "vt.tiktok.com", null),
        signal("tt_site", PKG_TIKTOK, MatchType.URL, "tiktok.com", null, enabled = false),

        // --- Facebook ----------------------------------------------------
        signal("fb_reels", PKG_FACEBOOK, MatchType.VIEW_ID, "video_home", PKG_FACEBOOK),
        signal("fb_reels", PKG_FACEBOOK, MatchType.URL, "facebook.com/reel", null),
        signal("fb_reels", PKG_FACEBOOK, MatchType.URL, "facebook.com/watch", null),
        // Nobody sends facebook.com/reel/1234; Facebook rewrites it to an
        // fb.watch link on the way out, and that is the door reels come through.
        signal("fb_reels", PKG_FACEBOOK, MatchType.URL, "fb.watch", null),
        // On by default, and the one default here that costs something.
        //
        // Messenger's web view, a custom tab and the Google app's tab all show
        // an origin with no path after it, so no path signal can ever fire in
        // them. Asked eight times, answered eight ways; this is the only answer
        // that works.
        signal("fb_site", PKG_FACEBOOK, MatchType.URL, "facebook.com", null),

        // --- The rest ----------------------------------------------------
        signal("snap_spotlight", PKG_SNAPCHAT, MatchType.VIEW_ID, "spotlight", PKG_SNAPCHAT),
        signal("snap_spotlight", PKG_SNAPCHAT, MatchType.URL, "snapchat.com/spotlight", null),

        signal("x_video", PKG_X, MatchType.VIEW_ID, "immersive_player", PKG_X),

        signal(
            "reddit_video", PKG_REDDIT, MatchType.VIEW_ID,
            "video_container_view_pager", PKG_REDDIT,
        ),
        signal("reddit_video", PKG_REDDIT, MatchType.URL, "reddit.com/r/popular", null),
    )

    /** Every surface belonging to a service, for the FEED_ONLY migration. */
    fun surfacesOfService(serviceKey: String): List<SurfaceEntity> =
        surfaces().filter { it.serviceKey == serviceKey }

    // --- Builders --------------------------------------------------------

    private fun surface(
        id: String,
        serviceKey: String,
        categoryKey: String,
        label: String,
    ) = SurfaceEntity(
        id = id,
        categoryKey = categoryKey,
        serviceKey = serviceKey,
        label = label,
    )

    /**
     * [legacyPackage] is only ever used to build the id.
     *
     * It is the package the old `feed_rule` row was filed under, and reusing it
     * keeps every signal id byte-identical to the rule id it replaces. That is
     * what lets the migration carry a man's own on/off decisions across without
     * having to interpret them.
     */
    private fun signal(
        surfaceId: String,
        legacyPackage: String,
        matchType: MatchType,
        matchValue: String,
        scope: String?,
        confidence: Confidence = Confidence.HIGH,
        enabled: Boolean = true,
    ) = SignalEntity(
        id = legacySignalId(legacyPackage, matchValue),
        surfaceId = surfaceId,
        matchType = matchType,
        matchValue = matchValue,
        scopePackage = scope,
        confidence = confidence.ordinal,
        enabled = enabled,
    )

    /** The `feed_rule` id scheme, kept so ids survive the move. */
    fun legacySignalId(pkg: String, matchValue: String): String =
        "builtin_${pkg}_$matchValue".take(120)
}
