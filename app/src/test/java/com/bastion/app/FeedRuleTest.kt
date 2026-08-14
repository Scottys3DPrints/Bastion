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
        // The distinction this protects is signpost versus destination.
        //
        // TEXT and CONTENT_DESC are signposts: they matched the Reels and
        // Shorts *tab buttons*, which sit in the navigation bar on every screen
        // of the app, so the rule fired the moment the app opened. An address
        // is a destination. A view id names one container on one screen.
        //
        // TITLE is allowed and is not a signpost either, though it is the only
        // one that reads what is written. It does not match the rule's own
        // value against a label — it compares short on-screen text to a shipped
        // word list, on YouTube surfaces only, and the tab bar has no video
        // titles in it. See TitleFilter for the limits that make that true.
        val offenders = rules.filter {
            it.matchType == MatchType.TEXT || it.matchType == MatchType.CONTENT_DESC
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
        // cannot shadow the other.
        //
        // A whole-site rule is a deliberate superset of the path rules for the
        // same service — facebook.com does contain facebook.com/reel — and
        // where it ships on, that shadowing is the design. The windows those
        // reels arrive in show an origin and no path, so the path rule has
        // nothing to compare and the site rule is the only one that can fire.
        // Shadowing an unreachable rule costs nothing.
        rules.filter { it.enabled }
            .filterNot { it.matchType == MatchType.URL && !it.matchValue.contains('/') }
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
