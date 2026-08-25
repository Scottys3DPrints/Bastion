package com.bastion.app

import com.bastion.app.data.db.CategoryEntity
import com.bastion.app.data.db.ConditionType
import com.bastion.app.data.db.Confidence
import com.bastion.app.data.db.PolicyEntity
import com.bastion.app.data.db.Response
import com.bastion.app.data.db.SurfaceEntity
import com.bastion.app.data.db.TargetType
import com.bastion.app.guard.policy.ConditionParams
import com.bastion.app.guard.policy.Conditions
import com.bastion.app.guard.policy.PolicyContext
import com.bastion.app.guard.policy.PolicyEngine
import com.bastion.app.guard.policy.ResolvedSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision, argued with on a laptop.
 *
 * This is the test file that could not have existed before the refactor. The
 * decision used to live inside `evaluate()`, tangled with lockdown state, learn
 * mode, the veil and grayscale, and needed a phone in a hand to run — which is
 * why every hard bug in it (the inline-reel false positive, the stories viewer,
 * the Messenger web view) was found by the user rather than by a test.
 */
class PolicyEngineTest {

    private val ig = "com.instagram.android"

    private val reels = SurfaceEntity(
        id = "ig_reels", categoryKey = "SHORT_FEED", serviceKey = "instagram",
        label = "Instagram Reels",
    )
    private val surfaces = mapOf(reels.id to reels)

    private fun category(chosen: Boolean) = mapOf(
        "SHORT_FEED" to CategoryEntity(
            key = "SHORT_FEED", label = "Endless feeds",
            chosen = chosen, response = Response.CLOSE.level,
        ),
    )

    private fun policy(
        id: String,
        target: TargetType,
        key: String,
        response: Response,
        condition: ConditionType = ConditionType.ALWAYS,
        params: String = "",
        enabled: Boolean = true,
    ) = PolicyEntity(
        id = id, targetType = target, targetKey = key,
        conditionType = condition, conditionParams = params,
        response = response.level, enabled = enabled,
    )

    private fun ctx(minute: Int = 12 * 60, used: Map<String, Long> = emptyMap()) =
        PolicyContext(
            packageName = ig,
            minuteOfDay = minute,
            isoDayOfWeek = 3,
            usedMillis = used,
        )

    private fun onReels(confidence: Confidence = Confidence.HIGH) =
        listOf(ResolvedSurface("ig_reels", "sig_clips", confidence))

    // --- the core rule ---------------------------------------------------

    @Test
    fun `a surface policy closes the surface it names`() {
        val decision = PolicyEngine.decide(
            present = onReels(),
            policies = listOf(policy("p", TargetType.SURFACE, "ig_reels", Response.CLOSE)),
            surfaces = surfaces,
            categories = category(chosen = false),
            ctx = ctx(),
        )
        assertNotNull(decision)
        assertEquals(Response.CLOSE, decision!!.response)
        assertEquals("ig_reels", decision.surfaceId)
        assertTrue(decision.blocks)
    }

    /**
     * The sentence v1 could not hold. "Reels always closed" and "the whole app
     * after 23:00" are both true of Instagram, and `BlockMode` could store one.
     */
    @Test
    fun `the strongest response wins when several policies cover one screen`() {
        val decision = PolicyEngine.decide(
            present = onReels(),
            policies = listOf(
                policy("watch", TargetType.SURFACE, "ig_reels", Response.WATCH),
                policy("close", TargetType.SURFACE, "ig_reels", Response.CLOSE),
                policy("pause", TargetType.APP, ig, Response.PAUSE),
            ),
            surfaces = surfaces,
            categories = category(chosen = false),
            ctx = ctx(),
        )
        assertEquals(Response.CLOSE, decision!!.response)
    }

    @Test
    fun `nothing on screen and no app policy means no decision at all`() {
        val decision = PolicyEngine.decide(
            present = emptyList(),
            policies = listOf(policy("p", TargetType.SURFACE, "ig_reels", Response.CLOSE)),
            surfaces = surfaces,
            categories = category(chosen = false),
            ctx = ctx(),
        )
        assertNull(decision)
    }

    // --- the leaks that must not happen ----------------------------------

    /**
     * The v1 bug this replaces, in its new clothes.
     *
     * Instagram's reels were being closed on a phone where Instagram had never
     * been guarded, because address rules were applied to everyone and the
     * app's own view-id rules leaked in beside them. A category left unchosen
     * must reach nothing at all.
     */
    @Test
    fun `an unchosen category reaches nothing`() {
        val policies = listOf(policy("cat", TargetType.CATEGORY, "SHORT_FEED", Response.CLOSE))

        assertNull(
            "an unchosen category must not close anything",
            PolicyEngine.decide(onReels(), policies, surfaces, category(chosen = false), ctx()),
        )
        assertEquals(
            "the same category, chosen, closes it",
            Response.CLOSE,
            PolicyEngine.decide(
                onReels(), policies, surfaces, category(chosen = true), ctx(),
            )!!.response,
        )
    }

    @Test
    fun `a disabled policy contributes nothing`() {
        assertNull(
            PolicyEngine.decide(
                present = onReels(),
                policies = listOf(
                    policy("p", TargetType.SURFACE, "ig_reels", Response.CLOSE, enabled = false),
                ),
                surfaces = surfaces,
                categories = category(chosen = false),
                ctx = ctx(),
            ),
        )
    }

    @Test
    fun `a condition that is false contributes nothing`() {
        val curfew = policy(
            "night", TargetType.APP, ig, Response.CLOSE,
            condition = ConditionType.CURFEW,
            params = Conditions.encode(ConditionParams(startMinute = 23 * 60, endMinute = 6 * 60)),
        )
        assertNull(
            "midday is outside a 23:00-06:00 curfew",
            PolicyEngine.decide(emptyList(), listOf(curfew), surfaces, category(false), ctx(12 * 60)),
        )
        assertNotNull(
            "half past eleven at night is inside it",
            PolicyEngine.decide(
                emptyList(), listOf(curfew), surfaces, category(false), ctx(23 * 60 + 30),
            ),
        )
    }

    @Test
    fun `an app policy does not fire for a different app`() {
        val decision = PolicyEngine.decide(
            present = emptyList(),
            policies = listOf(policy("p", TargetType.APP, "com.other.app", Response.CLOSE)),
            surfaces = surfaces,
            categories = category(chosen = false),
            ctx = ctx(),
        )
        assertNull(decision)
    }

    @Test
    fun `a disabled surface is not recognised even when a policy names it`() {
        val decision = PolicyEngine.decide(
            present = onReels(),
            policies = listOf(policy("p", TargetType.SURFACE, "ig_reels", Response.CLOSE)),
            surfaces = mapOf(reels.id to reels.copy(enabled = false)),
            categories = category(chosen = false),
            ctx = ctx(),
        )
        assertNull(decision)
    }

    // --- Watch -----------------------------------------------------------

    /**
     * Watch is a decision, not the absence of one. It has to come back so the
     * arrival can be counted, and it must not block.
     */
    @Test
    fun `watch decides without blocking`() {
        val decision = PolicyEngine.decide(
            present = onReels(),
            policies = listOf(policy("p", TargetType.SURFACE, "ig_reels", Response.WATCH)),
            surfaces = surfaces,
            categories = category(chosen = false),
            ctx = ctx(),
        )
        assertNotNull(decision)
        assertEquals(Response.WATCH, decision!!.response)
        assertFalse("Watch must never block", decision.blocks)
    }

    // --- the receipt -----------------------------------------------------

    @Test
    fun `a decision can say why in plain words`() {
        val decision = PolicyEngine.decide(
            present = onReels(),
            policies = listOf(
                policy(
                    "night", TargetType.SURFACE, "ig_reels", Response.CLOSE,
                    condition = ConditionType.CURFEW,
                    params = Conditions.encode(
                        ConditionParams(startMinute = 23 * 60, endMinute = 6 * 60),
                    ),
                ),
            ),
            surfaces = surfaces,
            categories = category(chosen = false),
            ctx = ctx(23 * 60 + 30),
        )!!
        assertTrue(
            "the reason should name the surface: ${decision.reason}",
            decision.reason.contains("Instagram Reels"),
        )
        assertTrue(
            "the reason should name the hours: ${decision.reason}",
            decision.reason.contains("23:00") && decision.reason.contains("06:00"),
        )
    }

    /** A decision that names a surface can explain itself; one that doesn't, can't. */
    @Test
    fun `a tie is won by the decision that names the surface`() {
        val decision = PolicyEngine.decide(
            present = onReels(),
            policies = listOf(
                policy("app", TargetType.APP, ig, Response.CLOSE),
                policy("surface", TargetType.SURFACE, "ig_reels", Response.CLOSE),
            ),
            surfaces = surfaces,
            categories = category(chosen = false),
            ctx = ctx(),
        )!!
        assertEquals("surface", decision.policyId)
        assertEquals("ig_reels", decision.surfaceId)
    }
}
