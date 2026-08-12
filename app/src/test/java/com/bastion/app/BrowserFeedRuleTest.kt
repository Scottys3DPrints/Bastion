package com.bastion.app

import com.bastion.app.data.db.MatchType
import com.bastion.app.data.repo.GuardRepository
import com.bastion.app.guard.accessibility.FeedSurface
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

    /**
     * And Messenger arrives with Facebook closed, which is what was asked for
     * after seeing what it costs.
     */
    @Test
    fun `messenger ships with facebook closed`() {
        val messenger = GuardRepository.builtInFeedRules()
            .filter { it.packageName == "com.facebook.orca" && it.matchType == MatchType.URL }
        listOf("facebook.com", "fb.watch").forEach { value ->
            val rule = messenger.firstOrNull { it.matchValue == value }
            assertTrue("Messenger has no $value rule at all", rule != null)
            assertTrue("Messenger's $value rule must ship on", rule!!.enabled)
        }
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
    @Test
    fun `the universal rules ship and are on`() {
        val universal = GuardRepository.builtInFeedRules()
            .filter { it.packageName == GuardRepository.ANY_APP }
        assertTrue("no universal rules ship at all", universal.isNotEmpty())
        universal.forEach {
            assertTrue("${it.matchValue} must ship on", it.enabled)
            assertEquals("a universal rule can only be an address", MatchType.URL, it.matchType)
        }
        val values = universal.map { it.matchValue }.toSet()
        listOf("facebook.com/reel", "instagram.com/reel", "youtube.com/shorts").forEach {
            assertTrue("$it is not covered universally", it in values)
        }
    }

    /**
     * And the universal set names paths only.
     *
     * A whole-site rule applying to every app on the phone would close that
     * site everywhere at once — in the browser, in the app, in a link a friend
     * sent. That is a decision for one app at a time, never a default that
     * arrives with an update.
     */
    @Test
    fun `no universal rule closes a whole site`() {
        val sites = GuardRepository.builtInFeedRules()
            .filter { it.packageName == GuardRepository.ANY_APP }
            // A rule that names a host and nothing after it covers the whole site.
            .filter { !it.matchValue.contains('/') }
        assertEquals("these would close a whole site everywhere", emptyList<String>(), sites.map { it.matchValue })
    }

    /**
     * The Google app opens links in a tab that shows an origin and no path, so
     * the site rule is the only one that can fire there — the same finding, and
     * the same answer, as Messenger.
     */
    @Test
    fun `the google app ships with facebook closed`() {
        val google = GuardRepository.builtInFeedRules()
            .filter { it.packageName == "com.google.android.googlequicksearchbox" }
        assertTrue("the Google app has no rules at all", google.isNotEmpty())
        assertTrue(
            "the Google app must be treated as a web view, not a real browser",
            "com.google.android.googlequicksearchbox" in GuardRepository.IN_APP_BROWSERS,
        )
        listOf("facebook.com", "fb.watch").forEach { value ->
            val rule = google.firstOrNull { it.matchValue == value }
            assertTrue("the Google app has no $value rule", rule != null)
            assertTrue("the Google app's $value rule must ship on", rule!!.enabled)
        }
    }

    /**
     * The Google app is not a real browser for the purposes of the width guess.
     *
     * Its bar is not an omnibox, so guessing at one from "wide and near the
     * top" is guessing about a layout nobody designed to be guessed at.
     */
    @Test
    fun `the google app does not get the width guess for free`() {
        assertFalse(
            "com.google.android.googlequicksearchbox" in GuardRepository.REAL_BROWSERS
        )
    }

    // --- the shipped rules ---------------------------------------------------

    @Test
    fun `browsers and in-app browsers both get url rules`() {
        val urlRules = GuardRepository.builtInFeedRules().filter { it.matchType == MatchType.URL }
        val packages = urlRules.map { it.packageName }.toSet()
        listOf(
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.sec.android.app.sbrowser",
            // The three that open links in a web view of their own rather than
            // handing off to a browser. Messenger is the one the user asked for.
            "com.facebook.orca",
            "com.facebook.katana",
            "com.instagram.android",
        ).forEach {
            assertTrue("no browser rules for $it", it in packages)
        }
    }

    /**
     * The reels, not the whole site.
     *
     * Reported from a phone: Messenger was recognising facebook.com and closing
     * all of it, when what wanted closing was the reels. Blocking a man's
     * messages to stop him watching videos is the wrong trade, and it was only
     * made because a path rule could not fire while the address bar was going
     * unfound. Now that it is found, the path is back.
     */
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
    fun `the whole-site rules ship off in browsers and on in web views`() {
        val bySite = GuardRepository.builtInFeedRules()
            .filter { it.matchType == MatchType.URL }
            .filter { it.matchValue in setOf("instagram.com", "facebook.com") }
        assertTrue("the whole-site rules should still exist", bySite.isNotEmpty())

        val (inApp, real) = bySite.partition { it.packageName in GuardRepository.IN_APP_BROWSERS }
        assertTrue("no in-app browser carries a whole-site rule", inApp.isNotEmpty())
        inApp.forEach {
            assertTrue(
                "${it.packageName}/${it.matchValue} must ship on — it is the only " +
                    "rule that web view's address bar can ever satisfy",
                it.enabled,
            )
        }
        real.forEach {
            assertFalse("${it.packageName}/${it.matchValue} must ship off", it.enabled)
        }
    }

    /**
     * Facebook writes the same feed as /reel/<id> and as /reels, so a rule
     * naming /reel has to catch both or it misses half of what it is for.
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
    fun `generated rules keep unique ids`() {
        val rules = GuardRepository.builtInFeedRules()
        assertEquals(rules.size, rules.map { it.id }.distinct().size)
    }

    /**
     * A lookalike host must still not match, which is the protection that
     * survives the move from paths to hosts.
     *
     * Blocking the whole site in a browser is now deliberate — see above for
     * why a path cannot be relied on there — so the guard that matters is no
     * longer "is there a path" but "is this actually that site".
     */
    @Test
    fun `a host rule does not catch a different site`() {
        assertFalse(FeedSurface.urlMatches("notinstagram.com", "instagram.com"))
        assertFalse(FeedSurface.urlMatches("instagram.com.evil.co/x", "instagram.com"))
        assertFalse(FeedSurface.urlMatches("facebookmarketplace.co", "facebook.com"))
    }

    /**
     * YouTube keeps its path, and that is a stated limit rather than an
     * oversight: youtube.com in a browser is genuinely used for things that are
     * not Shorts, so the rule fires when the path is visible and does nothing
     * when the browser hides it.
     */
    @Test
    fun `youtube is still matched by path`() {
        val values = GuardRepository.builtInFeedRules()
            .filter { it.matchType == MatchType.URL }
            .map { it.matchValue }
        assertTrue("youtube.com/shorts" in values)
        assertFalse("a bare youtube.com rule would close the whole site", "youtube.com" in values)
    }
}
