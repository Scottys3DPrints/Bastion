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
