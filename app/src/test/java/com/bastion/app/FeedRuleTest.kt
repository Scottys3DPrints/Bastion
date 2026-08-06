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
    fun `every built-in rule matches on a view id`() {
        val offenders = rules.filter { it.matchType != MatchType.VIEW_ID }
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
        rules.groupBy { it.packageName }.forEach { (pkg, forApp) ->
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
        rules.forEach { rule ->
            assertTrue(
                "'${rule.matchValue}' has a slash in it; ids are compared as the " +
                    "segment after the last one, so this can never match",
                !rule.matchValue.contains('/'),
            )
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
