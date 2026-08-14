package com.bastion.app

import com.bastion.app.data.db.BlockMode
import com.bastion.app.feature.guardui.RuleState
import com.bastion.app.feature.guardui.ruleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a feed rule is actually doing anything.
 *
 * The accessibility service walks a chain: is Guard running, is this package in
 * the guarded table, is its mode Feeds only, is the rule enabled. Break any link
 * and the rule is inert — and the old screen drew an inert rule and a working
 * one identically. A man could switch on "Instagram Reels" while Instagram was
 * not guarded, watch nothing happen, and conclude the app was broken. It was
 * not; it was obeying a chain nobody had shown him.
 *
 * These assertions are the chain, stated once, so the screen and the service
 * cannot drift apart about what "on" means.
 */
class FeedRuleStateTest {

    @Test
    fun `all four links intact means it is working`() {
        assertEquals(
            RuleState.Working,
            ruleState(guardRunning = true, guardedMode = BlockMode.FEED_ONLY, anyRuleEnabled = true),
        )
    }

    /**
     * The failure that started this. A rule for an app that is not guarded never
     * reaches the matcher, because the service returns the moment the package
     * misses the guarded table.
     */
    @Test
    fun `a rule for an unguarded app does nothing`() {
        assertEquals(
            RuleState.NotGuarded,
            ruleState(guardRunning = true, guardedMode = null, anyRuleEnabled = true),
        )
    }

    /**
     * Not guarded outranks every other complaint.
     *
     * If the app is absent from the table, the rule being off and Guard being
     * down are both true and both beside the point — fixing either changes
     * nothing. The screen must name the link that is actually load-bearing, or
     * it sends a man to fix the wrong thing.
     */
    @Test
    fun `not guarded is reported ahead of the other faults`() {
        assertEquals(
            RuleState.NotGuarded,
            ruleState(guardRunning = false, guardedMode = null, anyRuleEnabled = false),
        )
    }

    /** In whole-app mode the feed rules are bypassed, which is not a fault. */
    @Test
    fun `a fully blocked app does not need feed rules`() {
        assertEquals(
            RuleState.WholeAppBlocked,
            ruleState(guardRunning = true, guardedMode = BlockMode.FULL, anyRuleEnabled = true),
        )
        // And that stays true whether or not the app rules are switched on: in
        // this mode the app never opens, so a rule naming a screen inside it
        // has nothing to describe. Address rules are the exception and get
        // their own state — see the browser-rules case below.
        assertEquals(
            RuleState.WholeAppBlocked,
            ruleState(guardRunning = true, guardedMode = BlockMode.FULL, anyRuleEnabled = false),
        )
    }

    @Test
    fun `every rule switched off is reported as such`() {
        assertEquals(
            RuleState.AllOff,
            ruleState(guardRunning = true, guardedMode = BlockMode.FEED_ONLY, anyRuleEnabled = false),
        )
    }

    /**
     * Guard being off is the last link checked, and deliberately so: it is the
     * only one that is true of every rule at once, so reporting it first would
     * hide a genuinely misconfigured app behind a global complaint.
     */
    @Test
    fun `an otherwise correct rule reports guard being off`() {
        assertEquals(
            RuleState.GuardOff,
            ruleState(guardRunning = false, guardedMode = BlockMode.FEED_ONLY, anyRuleEnabled = true),
        )
    }

    /** Only one state claims to be working, and it needs the whole chain. */
    @Test
    fun `nothing else is ever reported as working`() {
        val combinations = listOf(true, false).flatMap { running ->
            listOf(null, BlockMode.FEED_ONLY, BlockMode.FULL).flatMap { mode ->
                listOf(true, false).map { enabled -> Triple(running, mode, enabled) }
            }
        }
        combinations.forEach { (running, mode, enabled) ->
            val state = ruleState(running, mode, enabled)
            if (state == RuleState.Working) {
                assertTrue(
                    "reported working with running=$running mode=$mode enabled=$enabled",
                    running && mode == BlockMode.FEED_ONLY && enabled,
                )
            }
        }
    }

    /** Every state says something a man could act on. */
    @Test
    fun `every state explains itself`() {
        listOf(
            RuleState.Working, RuleState.AllOff, RuleState.NotGuarded,
            RuleState.WholeAppBlocked, RuleState.GuardOff,
        ).forEach {
            assertTrue("${it::class.simpleName} has no explanation", it.line.isNotBlank())
        }
    }

    /**
     * Unless the group also reaches a browser, which a fully blocked app does
     * not cover.
     *
     * "Not needed, the whole app is already blocked" was true while every rule
     * named a screen inside an app. It stopped being true the moment a group
     * also carried addresses: a man who blocked Instagram outright and was told
     * these were unnecessary would have gone on opening instagram.com in
     * Chrome, believing he had closed it.
     */
    @Test
    fun `a fully blocked app still says what its browser rules do`() {
        assertEquals(
            RuleState.BrowserOnly,
            ruleState(
                guardRunning = true,
                guardedMode = BlockMode.FULL,
                anyRuleEnabled = true,
                hasBrowserRules = true,
            ),
        )
    }

    /**
     * And nothing describes itself as working for a service nobody guarded.
     *
     * This is the state behind the report that started this: Instagram reels
     * closing on a phone where Instagram had never been added. Whatever the
     * switches inside the group say, an unguarded service does nothing at all.
     */
    @Test
    fun `an unguarded service is inert whatever its switches say`() {
        listOf(true, false).forEach { rulesOn ->
            listOf(true, false).forEach { browser ->
                assertEquals(
                    RuleState.NotGuarded,
                    ruleState(
                        guardRunning = true,
                        guardedMode = null,
                        anyRuleEnabled = rulesOn,
                        hasBrowserRules = browser,
                    ),
                )
            }
        }
    }
}
