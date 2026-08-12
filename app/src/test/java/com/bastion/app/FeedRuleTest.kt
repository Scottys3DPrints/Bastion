package com.bastion.app

import com.bastion.app.data.db.MatchType
import com.bastion.app.data.repo.GuardRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape of the built-in rules, pinned.
 *
 * Feed-only guarding is the app's central promise and the way it fails is
 * asymmetric. A rule that stops matching costs a block. A rule that matches too
 * widely costs the whole app — Instagram opens, the rule fires, and "let me
 * message a friend without the feed" quietly becomes a total block the user
 * never asked for. That has happened twice from two different causes, and both
 * are guarded here rather than in a comment.
 */
class FeedRuleTest {

    private val rules = GuardRepository.builtInFeedRules()

    /**
     * Name the destination, never the signpost.
     *
     * The Reels and Shorts *tab buttons* were matched once by content
     * description. Those buttons live in the bottom navigation bar, which is on
     * screen everywhere in the app, so the rule fired the moment Instagram
     * opened. MIGRATION_2_3 deletes those rows; this stops another being added.
     *
     * A view id names one container on one screen. That is the only kind of
     * claim a built-in rule is allowed to make, because it ships to people who
     * cannot debug it.
     */
    @Test
    fun `every built-in rule matches on a container or an address`() {
        // URL is legitimate and is how the browser rules work; a label is not.
        // The distinction this protects is signpost versus destination, and an
        // address is a destination.
        val offenders = rules.filter {
            it.matchType != MatchType.VIEW_ID && it.matchType != MatchType.URL
        }
        assertTrue(
            "These built-in rules match on a label rather than a container, which " +
                "is how feed-only became a total block: " +
                offenders.joinToString { "${it.packageName}/${it.matchValue} (${it.matchType})" },
            offenders.isEmpty(),
        )
    }

    /**
     * The substring bug, from the other end.
     *
     * Matching is exact segment equality now, so `clips_viewer` no longer
     * catches `clips_viewer_preview`. That fix is only worth anything while no
     * rule is itself a prefix of another rule for the same app: two such rules
     * mean the tighter one can never be the reason for a block, and a redesign
     * that moves the id would look like it was still covered.
     */
    @Test
    fun `no rule is a prefix of another rule for the same app`() {
        // Among the rules that ship switched on, and within a match type.
        //
        // A view id and an address are compared against different things, so one
        // cannot shadow the other. The whole-site browser rules are a deliberate
        // superset of the path ones — facebook.com does contain facebook.com/reel
        // — which is why they ship switched off in a real browser, where the path
        // is visible and the narrow rule can do the work.
        //
        // Inside an in-app browser they ship on, and there the shadowing is the
        // point rather than a bug: those show a domain and no path, so the path
        // rule has nothing to compare and the site rule is the only one that can
        // ever fire. Shadowing an unreachable rule costs nothing.
        // The custom-tab window belongs with the in-app browsers here for the
        // one reason that matters: it shows an origin and no path, so a path
        // rule has nothing to compare against and a whole-site rule is not a
        // blunt instrument there — it is the only instrument.
        val inApp = GuardRepository.IN_APP_BROWSERS + GuardRepository.CUSTOM_TAB
        rules.filter { it.enabled }
            .filterNot { it.packageName in inApp && !it.matchValue.contains('/') }
            .groupBy { it.packageName to it.matchType }.forEach { (key, forApp) ->
            val pkg = key.first
            forApp.forEach { rule ->
                val shadowed = forApp.filter {
                    it !== rule && it.matchValue.startsWith(rule.matchValue)
                }
                assertTrue(
                    "In $pkg, '${rule.matchValue}' is a prefix of " +
                        shadowed.joinToString { "'${it.matchValue}'" },
                    shadowed.isEmpty(),
                )
            }
        }
    }

    /** Ids are compared after the last slash, so a rule carrying one never matches. */
    @Test
    fun `no rule carries a package prefix or a slash`() {
        rules.filter { it.matchType == MatchType.VIEW_ID }.forEach { rule ->
            // View ids only. A URL rule is a path and slashes are the point.
            assertTrue(
                "'${rule.matchValue}' has a slash in it; ids are compared as the " +
                    "segment after the last one, so this can never match",
                !rule.matchValue.contains('/'),
            )
        }
        rules.forEach { rule ->
            assertTrue("A rule with a blank match value can never mean anything", rule.matchValue.isNotBlank())
            assertTrue("A rule needs an app to belong to", rule.packageName.isNotBlank())
            assertTrue("A rule the user cannot read is a rule they cannot fix", rule.label.isNotBlank())
        }
    }

    /** Duplicate ids collide on upsert, so one silently replaces the other. */
    @Test
    fun `rule ids are unique`() {
        assertEquals(rules.size, rules.map { it.id }.distinct().size)
    }

    /**
     * A browser ships with a rule that its own address bar can satisfy.
     *
     * This is the invariant behind five failed attempts at Facebook reels in
     * Messenger. A path rule needs a path on screen. Chrome shows one; the web
     * view inside Messenger shows the domain alone, so every rule naming
     * `/reel` was matched against something that never contained it. The fix is
     * not a better matcher — it is shipping each browser the rule it can
     * actually satisfy.
     *
     * So: in-app browsers get the site rule on, real browsers get the path
     * rules and keep the site off. Both halves are asserted, because either one
     * silently flipping is a browser that stops blocking or an app that gets
     * closed entirely.
     */
    @Test
    fun `every browser ships a rule its address bar can satisfy`() {
        val urlRules = rules.filter { it.matchType == MatchType.URL }
        val browsers = urlRules.map { it.packageName }.distinct()
        assertTrue("No browser rules ship at all", browsers.isNotEmpty())

        browsers.forEach { pkg ->
            val on = urlRules.filter { it.packageName == pkg && it.enabled }
            val sitesOn = on.filter { !it.matchValue.contains('/') }
            if (pkg in GuardRepository.IN_APP_BROWSERS + GuardRepository.CUSTOM_TAB) {
                assertTrue(
                    "$pkg is a web view with no path in its address bar, so a " +
                        "path rule can never fire there and it needs a whole-site " +
                        "rule switched on. None is.",
                    sitesOn.isNotEmpty(),
                )
            } else {
                assertTrue(
                    "$pkg shows a full address, so the path rules do the work and " +
                        "the whole-site rules must stay off — on, they close the " +
                        "entire site when the user asked for the feed: " +
                        sitesOn.joinToString { it.matchValue },
                    sitesOn.isEmpty(),
                )
                assertTrue("$pkg has no enabled path rule to block anything", on.isNotEmpty())
            }
        }
    }

    /**
     * The apps this actually covers, so removing one is a decision rather than
     * an accident during a refactor.
     */
    @Test
    fun `the short-form feeds are all covered`() {
        val covered = rules.map { it.packageName }.toSet()
        listOf(
            "com.instagram.android",
            "com.google.android.youtube",
            "com.zhiliaoapp.musically",
            "com.facebook.katana",
            "com.snapchat.android",
        ).forEach {
            assertTrue("No built-in rule covers $it any more", it in covered)
        }
    }
}
