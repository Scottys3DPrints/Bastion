package com.bastion.app

import com.bastion.app.data.db.MatchType
import com.bastion.app.data.repo.GuardRepository
import com.bastion.app.guard.accessibility.FeedSurface
import com.bastion.app.guard.accessibility.GuardedScreens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feeds reached through a browser rather than through an app.
 *
 * Blocking Instagram's app and leaving instagram.com one tap away in Chrome is
 * a door with no wall behind it, and the little browser Messenger opens when a
 * friend sends a link is the same door again — arguably the likelier one, since
 * it arrives unasked in the middle of a conversation.
 *
 * The whole risk of this feature is the privacy line. Bastion's contract is that
 * message bodies, posts and field contents are never read, and a rule that
 * scanned page text would break it on the first conversation. So only strings
 * that *are* an address are ever considered, and the tests that matter most here
 * are the ones proving a message is not one.
 */
class BrowserFeedRuleTest {

    // --- what counts as an address ------------------------------------------

    @Test
    fun `an address is recognised`() {
        assertTrue(FeedSurface.looksLikeUrl("instagram.com/reels"))
        assertTrue(FeedSurface.looksLikeUrl("https://www.instagram.com/reels/"))
        assertTrue(FeedSurface.looksLikeUrl("m.facebook.com/watch"))
    }

    /**
     * The privacy line, and the case it exists for.
     *
     * A friend sending a reel link inside a conversation must not wall the
     * conversation. Every one of these has whitespace, which is the first thing
     * checked and the reason a sentence can never be mistaken for an address.
     */
    @Test
    fun `a message is never mistaken for an address`() {
        assertFalse(FeedSurface.looksLikeUrl("have you seen instagram.com/reels yet"))
        assertFalse(FeedSurface.looksLikeUrl("check this out"))
        assertFalse(FeedSurface.looksLikeUrl("instagram.com/reels is funny"))
        assertFalse(FeedSurface.looksLikeUrl(""))
        assertFalse(FeedSurface.looksLikeUrl("   "))
    }

    @Test
    fun `something without a host is not an address`() {
        assertFalse(FeedSurface.looksLikeUrl("reels"))
        assertFalse(FeedSurface.looksLikeUrl("Search"))
        assertFalse(FeedSurface.looksLikeUrl("a.b"))
    }

    /** A page of prose could otherwise arrive as one enormous unbroken string. */
    @Test
    fun `an absurdly long string is not an address`() {
        assertFalse(FeedSurface.looksLikeUrl("instagram.com/" + "a".repeat(300)))
    }

    // --- matching a destination ---------------------------------------------

    /**
     * One destination arrives written several ways depending on how it was
     * reached, and a rule should not have to name each of them.
     */
    @Test
    fun `the same destination matches however it is written`() {
        listOf(
            "instagram.com/reels",
            "https://instagram.com/reels",
            "https://www.instagram.com/reels/",
            "m.instagram.com/reels",
            "instagram.com/reels?next=abc",
            "INSTAGRAM.COM/REELS",
        ).forEach {
            assertTrue("$it should match", FeedSurface.urlMatches(it, "instagram.com/reels"))
        }
    }

    /**
     * Paths, not whole hosts. Blocking instagram.com outright would close
     * messages and search along with the reels, which is exactly the
     * distinction feed-only guarding exists to draw.
     */
    @Test
    fun `the rest of the site stays open`() {
        assertFalse(FeedSurface.urlMatches("instagram.com/direct/inbox", "instagram.com/reels"))
        assertFalse(FeedSurface.urlMatches("instagram.com/explore", "instagram.com/reels"))
        assertFalse(FeedSurface.urlMatches("facebook.com/messages", "facebook.com/reel"))
    }

    /** A host that merely ends with the blocked one is a different site. */
    @Test
    fun `a lookalike host does not match`() {
        assertFalse(FeedSurface.urlMatches("notinstagram.com/reels", "instagram.com/reels"))
        assertFalse(FeedSurface.urlMatches("instagram.com.evil.co/reels", "instagram.com/reels"))
    }

    /**
     * A site rule reaches its own subdomains, and enumerating them is a game
     * against a company that can add one tomorrow.
     *
     * `web.facebook.com` and `mbasic.facebook.com` are the same site through a
     * different front door. A rule that said "all of Facebook" and then let
     * mbasic through would be a wall with a gap in it that only the determined
     * find — which is exactly the person it is there for.
     */
    @Test
    fun `a site rule covers its subdomains`() {
        listOf(
            "facebook.com",
            "www.facebook.com",
            "m.facebook.com",
            "web.facebook.com",
            "mbasic.facebook.com",
            "web.facebook.com/reel/123",
        ).forEach {
            assertTrue("$it should match", FeedSurface.urlMatches(it, "facebook.com"))
        }
    }

    /**
     * And the dot is what keeps that safe.
     *
     * Reaching down the host is only sound while the boundary is a real label
     * separator. Without it, matching a suffix would claim notfacebook.com and
     * matching a prefix would claim facebook.com.evil.co — the two mirror-image
     * ways a host test goes wrong, both of which this must refuse.
     */
    @Test
    fun `reaching down the host stops at the label boundary`() {
        assertFalse(FeedSurface.urlMatches("notfacebook.com", "facebook.com"))
        assertFalse(FeedSurface.urlMatches("myfacebook.com/reel", "facebook.com"))
        assertFalse(FeedSurface.urlMatches("facebook.com.evil.co", "facebook.com"))
        assertFalse(FeedSurface.urlMatches("facebook.company.co", "facebook.com"))
    }

    /** A path rule reaches subdomains too, and still refuses the rest of the site. */
    @Test
    fun `a path rule keeps its path while reaching subdomains`() {
        assertTrue(FeedSurface.urlMatches("web.facebook.com/reel/99", "facebook.com/reel"))
        assertFalse(FeedSurface.urlMatches("web.facebook.com/messages", "facebook.com/reel"))
    }

    /**
     * The share link, which is how a reel actually arrives in a chat.
     *
     * Nobody sends `facebook.com/reel/1234`; Facebook rewrites it to an
     * `fb.watch` link on the way out. Closing facebook.com and leaving fb.watch
     * open is closing the front door of a building with two, and the open one is
     * the door reels come through.
     */
    @Test
    fun `the share-link domains are a separate site and are covered`() {
        assertFalse("fb.watch is not a subdomain of facebook.com", FeedSurface.urlMatches("fb.watch/aB3xY", "facebook.com"))
        assertTrue(FeedSurface.urlMatches("fb.watch/aB3xY", "fb.watch"))
        assertTrue(FeedSurface.urlMatches("https://fb.watch/aB3xY/", "fb.watch"))
        assertTrue(FeedSurface.urlMatches("vt.tiktok.com/ZS123/", "vt.tiktok.com"))

        val shipped = GuardRepository.builtInFeedRules().map { it.matchValue }.toSet()
        assertTrue("fb.watch should ship as a rule", "fb.watch" in shipped)
        assertTrue("vt.tiktok.com should ship as a rule", "vt.tiktok.com" in shipped)
    }

    // --- the address bar, when the browser does not name it ------------------

    @Test
    fun `a bar at the top spanning the width is an address bar`() {
        assertTrue(
            FeedSurface.isAddressBar(
                top = 120, width = 900, windowTop = 0, windowHeight = 2400, windowWidth = 1080,
            )
        )
    }

    /**
     * A link partway down a conversation is not, however much it looks like a
     * URL. This is the geometry half of the same protection the whitespace test
     * gives on the text side.
     */
    @Test
    fun `a link in the middle of a page is not an address bar`() {
        assertFalse(
            FeedSurface.isAddressBar(
                top = 1400, width = 900, windowTop = 0, windowHeight = 2400, windowWidth = 1080,
            )
        )
        // Nor a narrow one at the top, which is a chip or a tab rather than a bar.
        assertFalse(
            FeedSurface.isAddressBar(
                top = 120, width = 300, windowTop = 0, windowHeight = 2400, windowWidth = 1080,
            )
        )
    }

    @Test
    fun `a window with no size never matches`() {
        assertFalse(FeedSurface.isAddressBar(0, 900, 0, 0, 0))
    }

    /**
     * The in-app browser case, and the reason a Facebook reel opened from a chat
     * went unblocked.
     *
     * Messenger draws the domain as a small centred subtitle under the page
     * title. It is nowhere near half the screen wide, so the width test never
     * recognised it and the rule never fired. The web view is the page;
     * everything above its top edge is the toolbar the host app built, whatever
     * size it chose to draw the label.
     */
    @Test
    fun `a narrow label above the web view is still the address`() {
        // A 300px-wide domain label at y=150, with the page starting at y=300.
        assertTrue(FeedSurface.isBrowserChrome(nodeBottom = 220, webViewTop = 300))
        // And the width test alone would have rejected exactly that node.
        assertFalse(
            FeedSurface.isAddressBar(
                top = 150, width = 300, windowTop = 0, windowHeight = 2400, windowWidth = 1080,
            )
        )
    }

    /**
     * A link inside the page is not the address, which is the protection the
     * width test was there for and which this must not lose.
     */
    @Test
    fun `a link on the page is not the address`() {
        assertFalse(FeedSurface.isBrowserChrome(nodeBottom = 900, webViewTop = 300))
        // Exactly at the boundary counts as chrome; one pixel below does not.
        assertTrue(FeedSurface.isBrowserChrome(nodeBottom = 300, webViewTop = 300))
        assertFalse(FeedSurface.isBrowserChrome(nodeBottom = 301, webViewTop = 300))
    }

    /** No web view found means the test cannot speak, and says nothing. */
    @Test
    fun `without a web view the chrome test never matches`() {
        assertFalse(FeedSurface.isBrowserChrome(nodeBottom = 10, webViewTop = 0))
        assertFalse(FeedSurface.isBrowserChrome(nodeBottom = 0, webViewTop = 0))
    }

    // --- the width guess, withdrawn where it is unsafe ------------------------

    /**
     * A link somebody sent must never wall the conversation it arrived in.
     *
     * The whole-site rules turn a guess into a hazard. `facebook.com/reel` is
     * not a thing anyone types to a friend, so a width false-positive had
     * nothing to match and cost nothing. `facebook.com` is precisely what people
     * send each other, and a wide link bubble near the top of a short chat sits
     * inside the band the width test calls a toolbar.
     *
     * So in a messaging app the width guess is withdrawn and the web view is the
     * only evidence accepted. This is the test that stops a blocker from taking
     * away someone's messages.
     */
    @Test
    fun `outside a real browser with no page open the width guess is withdrawn`() {
        assertFalse(FeedSurface.addressBarWidthCounts(realBrowser = false, webViewFound = false))
    }

    /** With a page actually open, the in-app browser is a browser again. */
    @Test
    fun `an open page restores the width guess anywhere`() {
        assertTrue(FeedSurface.addressBarWidthCounts(realBrowser = false, webViewFound = true))
    }

    /**
     * Chrome keeps it either way. Its omnibox is the case the width test was
     * written for, and Chrome does not always expose a node the web-view walk
     * recognises — removing the fallback there would break what works.
     */
    @Test
    fun `a real browser keeps the width guess with or without a web view`() {
        assertTrue(FeedSurface.addressBarWidthCounts(realBrowser = true, webViewFound = false))
        assertTrue(FeedSurface.addressBarWidthCounts(realBrowser = true, webViewFound = true))
    }

    // --- the browser nobody listed -------------------------------------------

    /**
     * The universal rules exist and are switched on, because they are what
     * makes "any browser" true instead of "any browser I listed".
     *
     * The failing case has been the same every time: a man reaches for a
     * browser nobody thought of — the Google app, a reader, whatever opened the
     * link — and finds it uncovered. These rules belong to no package and apply
     * to any app the phone says can open a web page.
     */
    // --- the window a link opens in ------------------------------------------

    // --- the shipped rules ---------------------------------------------------

    @Test
    fun `the reel paths ship switched on`() {
        val on = GuardRepository.builtInFeedRules()
            .filter { it.matchType == MatchType.URL && it.enabled }
            .map { it.matchValue }
            .toSet()
        listOf(
            "facebook.com/reel",
            "facebook.com/watch",
            "instagram.com/reel",
            "youtube.com/shorts",
        ).forEach { assertTrue("$it should be on by default", it in on) }
    }

    /**
     * And the whole-site rules ship off in a real browser, on in a web view.
     *
     * In Chrome the site rule is a bigger hammer than most men want — it closes
     * all of Facebook when what was asked for was the reels — so it should be
     * chosen rather than discovered after the fact.
     *
     * Inside Messenger it is not a bigger hammer, it is the only hammer. That
     * web view shows a domain and no path, so the path rule is matched against
     * something that cannot contain it; and closing facebook.com there takes
     * nothing away, because the messaging this app exists to protect is the app
     * the user is already standing in.
     */
    @Test
    fun `a path rule matches the longer spellings of the same feed`() {
        listOf(
            "facebook.com/reel/1234567890",
            "facebook.com/reels",
            "facebook.com/reels/",
            "m.facebook.com/reels?source=x",
            "https://www.facebook.com/reel/99",
        ).forEach {
            assertTrue("$it should match", FeedSurface.urlMatches(it, "facebook.com/reel"))
        }
    }

    /** The rest of the site stays open, which is the entire point of the path. */
    @Test
    fun `a path rule leaves messages alone`() {
        listOf(
            "facebook.com",
            "facebook.com/messages",
            "facebook.com/marketplace",
            "m.facebook.com/friends",
        ).forEach {
            assertFalse("$it should be open", FeedSurface.urlMatches(it, "facebook.com/reel"))
        }
    }

    /**
     * The asymmetry, stated. A host rule stays strict because a lookalike
     * domain genuinely begins with the real host; a path rule does not need to,
     * because the host has already matched exactly.
     */
    @Test
    fun `the host boundary stays strict while the path one relaxes`() {
        assertFalse(FeedSurface.urlMatches("facebook.com.evil.co/reel", "facebook.com"))
        assertFalse(FeedSurface.urlMatches("facebookmarketplace.co", "facebook.com"))
        assertTrue(FeedSurface.urlMatches("facebook.com/reelsomething", "facebook.com/reel"))
    }

    /**
     * Hosts, because a browser does not reliably show a path.
     *
     * The first version matched instagram.com/reel and was reported as not
     * working. Chrome's omnibox trims what it displays and an in-app browser —
     * Messenger's, the one this was asked for — shows the bare domain or the
     * page title in its header. A path rule compared against "instagram.com" is
     * a rule that can never fire.
     */
    @Test
    fun `the sites are covered by host so a trimmed address still matches`() {
        val values = GuardRepository.builtInFeedRules()
            .filter { it.matchType == MatchType.URL }
            .map { it.matchValue }
            .toSet()
        listOf("instagram.com", "facebook.com", "tiktok.com").forEach {
            assertTrue("no rule for $it", it in values)
        }
        // The case that was failing: the address bar shows the host alone.
        assertTrue(FeedSurface.urlMatches("instagram.com", "instagram.com"))
        assertTrue(FeedSurface.urlMatches("facebook.com", "facebook.com"))
        // And the full address still matches the same rule.
        assertTrue(FeedSurface.urlMatches("https://www.instagram.com/reels/", "instagram.com"))
        assertTrue(FeedSurface.urlMatches("m.facebook.com/watch", "facebook.com"))
    }

    /** Ids stay unique once the cross product is generated, or upserts collide. */
    @Test
    fun `a host rule does not catch a different site`() {
        assertFalse(FeedSurface.urlMatches("notinstagram.com", "instagram.com"))
        assertFalse(FeedSurface.urlMatches("instagram.com.evil.co/x", "instagram.com"))
        assertFalse(FeedSurface.urlMatches("facebookmarketplace.co", "facebook.com"))
    }

    /**
     * YouTube is matched by path, and its whole-site rule ships off.
     *
     * Shorts is a place inside YouTube, not the whole of it, and a man who
     * blocked the whole site to stop scrolling Shorts has lost every lecture
     * and repair video with it. The bare host rule exists — it is the only
     * thing that can fire in a window showing no path — but it is his to switch
     * on rather than a default.
     */
    @Test
    fun `youtube is matched by path and not closed outright`() {
        assertTrue(FeedSurface.urlMatches("youtube.com/shorts/abc", "youtube.com/shorts"))
        assertFalse(FeedSurface.urlMatches("youtube.com/watch?v=abc", "youtube.com/shorts"))

        val site = GuardRepository.builtInFeedRules()
            .filter { it.matchType == MatchType.URL && it.matchValue == "youtube.com" }
        assertTrue("the whole-site rule should exist as an option", site.isNotEmpty())
        site.forEach { assertFalse("youtube.com must ship off", it.enabled) }
    }
    // --- one group per service -----------------------------------------------

    /**
     * Rules belong to the service they block, and to nothing else.
     *
     * Eight generations were spent answering "which app or window is this?" — a
     * copy of every address rule under Chrome, under Firefox, under Messenger,
     * under a sentinel for unlisted apps, under another for the window a link
     * opens in. Five groups for one decision, and the browser that mattered was
     * always the one not on the list.
     *
     * This is what replaced it: a rule sits under the service it blocks, and
     * the address rules apply everywhere on their own. A package here that is
     * not a service means the old shape is growing back.
     */
    @Test
    fun `every rule belongs to a real service`() {
        val services = setOf(
            "com.instagram.android",
            "com.google.android.youtube",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.facebook.katana",
            "com.snapchat.android",
            "com.twitter.android",
            "com.reddit.frontpage",
        )
        val strays = GuardRepository.builtInFeedRules()
            .map { it.packageName }
            .distinct()
            .filterNot { it in services }
        assertEquals("these are not services", emptyList<String>(), strays)
    }

    /**
     * And no browser or sentinel owns rules any more.
     *
     * Spelled out rather than derived, because the failure this catches is
     * somebody adding "just one" browser row back when a phone turns up that
     * seems uncovered — which is how the last shape grew, one reasonable row at
     * a time.
     */
    @Test
    fun `no browser or sentinel owns rules`() {
        val packages = GuardRepository.builtInFeedRules().map { it.packageName }.toSet()
        listOf(
            "com.android.chrome", "org.mozilla.firefox", "com.sec.android.app.sbrowser",
            "com.facebook.orca", "com.google.android.googlequicksearchbox",
            "*", "window:customtab",
        ).forEach { assertFalse("$it still owns rules", it in packages) }
    }

    /**
     * Instagram's story viewer is not its reels viewer, and Instagram's own
     * naming is a trap laid for exactly this mistake.
     *
     * It called stories "reels" years before the Reels product existed, and
     * named Reels "clips" when it shipped. So `reel_viewer` is the *story*
     * viewer — and the rule that named it, labelled "Instagram Reels (viewer)",
     * threw a man out of the app every time he opened a friend's story.
     * Feed-only guarding doing the precise thing it exists to prevent, wearing
     * the name of the thing it was meant to catch.
     */
    @Test
    fun `no instagram rule matches the story viewer`() {
        val ids = GuardRepository.builtInFeedRules()
            .filter { it.packageName == "com.instagram.android" }
            .filter { it.matchType == MatchType.VIEW_ID }
            .map { it.matchValue }
        assertTrue("clips_viewer is Reels and must stay", "clips_viewer" in ids)
        assertFalse("reel_viewer is the story viewer", "reel_viewer" in ids)
        // And no surviving rule may reach a story id by the prefix boundary.
        ids.forEach {
            assertFalse("'$it' reaches the story viewer", FeedSurface.idMatches("reel_viewer", it))
            assertFalse(
                "'$it' reaches a story sub-view",
                FeedSurface.idMatches("reel_viewer_media_container", it),
            )
        }
    }

    /**
     * YouTube carries the title rule, and it is the only service that does.
     *
     * It is the one place a rule reads what is written rather than how the
     * screen is built, and the reason is narrow: the watch page is the same
     * page for a lecture and for the thing a man came to stop. A title rule
     * under a messaging service would mean this had been pointed somewhere it
     * must never go.
     */
    @Test
    fun `only youtube reads titles`() {
        val titleRules = GuardRepository.builtInFeedRules()
            .filter { it.matchType == MatchType.TITLE }
        assertTrue("YouTube has no title rule", titleRules.isNotEmpty())
        titleRules.forEach {
            assertEquals(
                "a title rule outside YouTube reads text it has no business reading",
                "com.google.android.youtube",
                it.packageName,
            )
            assertTrue("the title rule must ship on", it.enabled)
        }
    }

    /** Each service reachable in a browser is covered under its own heading. */
    @Test
    fun `each service covers itself in a browser`() {
        val byService = GuardRepository.builtInFeedRules()
            .filter { it.matchType == MatchType.URL }
            .groupBy { it.packageName }
        mapOf(
            "com.instagram.android" to "instagram.com",
            "com.google.android.youtube" to "youtube.com",
            "com.facebook.katana" to "facebook.com",
            "com.zhiliaoapp.musically" to "tiktok.com",
        ).forEach { (pkg, host) ->
            val values = byService[pkg].orEmpty().map { it.matchValue }
            assertTrue("$pkg has no address rule naming $host", values.any { it.startsWith(host) })
        }
    }

    /**
     * The whole-site rules stay off, except Facebook's.
     *
     * A site rule closes a service outright in every browser, which is bigger
     * than a man asked for and belongs behind a switch. Facebook's is on
     * because the windows its reels arrive in — a chat's web view, a custom
     * tab, the Google app's tab — show an origin and no path, so no path rule
     * can fire in them. That was asked about until it was the only answer left.
     */
    @Test
    fun `only facebook ships closed site-wide`() {
        val on = GuardRepository.builtInFeedRules()
            .filter { it.matchType == MatchType.URL && !it.matchValue.contains('/') }
            .filter { it.enabled }
            .map { it.matchValue }
            .toSet()
        assertTrue("facebook.com must ship on", "facebook.com" in on)
        listOf("instagram.com", "youtube.com", "tiktok.com").forEach {
            assertFalse("$it must ship off — it closes the whole site", it in on)
        }
    }

    /** Ids must stay unique now that every service owns its own rows. */
    @Test
    fun `rule ids stay unique across services`() {
        val ids = GuardRepository.builtInFeedRules().map { it.id }
        assertEquals("duplicate ids collide on upsert", ids.size, ids.toSet().size)
    }

}
