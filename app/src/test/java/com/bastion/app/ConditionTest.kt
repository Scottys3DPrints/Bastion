package com.bastion.app

import com.bastion.app.data.db.ConditionType
import com.bastion.app.data.db.PolicyEntity
import com.bastion.app.data.db.Response
import com.bastion.app.data.db.TargetType
import com.bastion.app.guard.policy.ConditionParams
import com.bastion.app.guard.policy.Conditions
import com.bastion.app.guard.policy.PolicyContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a policy is live.
 *
 * Curfew arithmetic used to live in a private method on the accessibility
 * service, where it could not be reached without a phone. It wraps midnight,
 * which is the case it exists for and the one nobody checks by hand.
 */
class ConditionTest {

    private fun policy(
        type: ConditionType,
        params: ConditionParams = ConditionParams(),
        target: String = "com.instagram.android",
        enabled: Boolean = true,
    ) = PolicyEntity(
        id = "p",
        targetType = TargetType.APP,
        targetKey = target,
        conditionType = type,
        conditionParams = Conditions.encode(params),
        response = Response.CLOSE.level,
        enabled = enabled,
    )

    private fun ctx(
        minute: Int,
        day: Int = 3,
        used: Map<String, Long> = emptyMap(),
        opens: Map<String, Int> = emptyMap(),
        overnight: Boolean = false,
        risk: Int = 0,
    ) = PolicyContext(
        packageName = "com.instagram.android",
        minuteOfDay = minute,
        isoDayOfWeek = day,
        usedMillis = used,
        opens = opens,
        isOvernight = overnight,
        risk = risk,
    )

    // --- always ----------------------------------------------------------

    @Test
    fun `always is always, unless the policy is switched off`() {
        assertTrue(Conditions.holds(policy(ConditionType.ALWAYS), ctx(0)))
        assertTrue(Conditions.holds(policy(ConditionType.ALWAYS), ctx(13 * 60)))
        assertFalse(
            Conditions.holds(policy(ConditionType.ALWAYS, enabled = false), ctx(13 * 60)),
        )
    }

    // --- curfew ----------------------------------------------------------

    @Test
    fun `a curfew that wraps midnight covers both sides of it`() {
        val night = policy(
            ConditionType.CURFEW,
            ConditionParams(startMinute = 23 * 60, endMinute = 6 * 60),
        )
        assertTrue("23:30 is inside", Conditions.holds(night, ctx(23 * 60 + 30)))
        assertTrue("02:00 is inside", Conditions.holds(night, ctx(2 * 60)))
        assertFalse("midday is outside", Conditions.holds(night, ctx(12 * 60)))
        assertFalse("22:59 is outside", Conditions.holds(night, ctx(22 * 60 + 59)))
        assertTrue("23:00 exactly is inside", Conditions.holds(night, ctx(23 * 60)))
        assertFalse("06:00 exactly is outside — end is exclusive", Conditions.holds(night, ctx(6 * 60)))
    }

    @Test
    fun `a curfew inside one day behaves like an ordinary window`() {
        val work = policy(
            ConditionType.CURFEW,
            ConditionParams(startMinute = 9 * 60, endMinute = 17 * 60),
        )
        assertTrue(Conditions.holds(work, ctx(12 * 60)))
        assertFalse(Conditions.holds(work, ctx(8 * 60)))
        assertFalse(Conditions.holds(work, ctx(17 * 60)))
    }

    /**
     * The subtle half, and the one that would have shipped broken.
     *
     * At 01:00 on Saturday, inside a Friday 23:00–06:00 curfew, the day being
     * asked about is *Friday*. Testing today's date against the day list would
     * end every Friday-night curfew at midnight — the exact hour it is for.
     */
    @Test
    fun `a wrapped curfew belongs to the day it started on`() {
        val fridayNight = policy(
            ConditionType.CURFEW,
            ConditionParams(startMinute = 23 * 60, endMinute = 6 * 60, daysCsv = "5"),
        )
        assertTrue(
            "23:30 Friday",
            Conditions.holds(fridayNight, ctx(23 * 60 + 30, day = 5)),
        )
        assertTrue(
            "01:00 Saturday still belongs to Friday night",
            Conditions.holds(fridayNight, ctx(60, day = 6)),
        )
        assertFalse(
            "01:00 Friday belongs to Thursday night, which was not chosen",
            Conditions.holds(fridayNight, ctx(60, day = 5)),
        )
        assertFalse(
            "23:30 Saturday is not Friday",
            Conditions.holds(fridayNight, ctx(23 * 60 + 30, day = 6)),
        )
    }

    @Test
    fun `an empty day list means every day`() {
        val night = policy(
            ConditionType.CURFEW,
            ConditionParams(startMinute = 23 * 60, endMinute = 6 * 60, daysCsv = ""),
        )
        (1..7).forEach { day ->
            assertTrue("day $day", Conditions.holds(night, ctx(23 * 60 + 30, day = day)))
        }
    }

    @Test
    fun `a curfew with no hours never fires`() {
        assertFalse(Conditions.holds(policy(ConditionType.CURFEW), ctx(23 * 60)))
    }

    // --- budgets ---------------------------------------------------------

    @Test
    fun `a daily budget fires once the minutes are used`() {
        val p = policy(ConditionType.DAILY_BUDGET, ConditionParams(minutes = 10))
        val key = "com.instagram.android"
        assertFalse(Conditions.holds(p, ctx(12 * 60, used = mapOf(key to 0L))))
        assertFalse(Conditions.holds(p, ctx(12 * 60, used = mapOf(key to 9 * 60_000L))))
        assertTrue(Conditions.holds(p, ctx(12 * 60, used = mapOf(key to 10 * 60_000L))))
        assertTrue(Conditions.holds(p, ctx(12 * 60, used = mapOf(key to 45 * 60_000L))))
    }

    /**
     * A budget is spent against whatever the policy targets.
     *
     * Per app, closing Instagram at its limit sends a man to TikTok with a
     * fresh ten minutes, which is not a budget but a sequence of them. Keying
     * on the target is what lets a category hold one.
     */
    @Test
    fun `a budget is counted against the thing the policy targets`() {
        val perCategory = PolicyEntity(
            id = "p",
            targetType = TargetType.CATEGORY,
            targetKey = "SHORT_FEED",
            conditionType = ConditionType.DAILY_BUDGET,
            conditionParams = Conditions.encode(ConditionParams(minutes = 20)),
            response = Response.CLOSE.level,
        )
        assertFalse(
            "one app's use is not the category's total",
            Conditions.holds(
                perCategory,
                ctx(12 * 60, used = mapOf("com.instagram.android" to 25 * 60_000L)),
            ),
        )
        assertTrue(
            "the category's own total is what counts",
            Conditions.holds(perCategory, ctx(12 * 60, used = mapOf("SHORT_FEED" to 20 * 60_000L))),
        )
    }

    @Test
    fun `an open count fires at the limit`() {
        val p = policy(ConditionType.OPEN_COUNT, ConditionParams(opens = 5))
        val key = "com.instagram.android"
        assertFalse(Conditions.holds(p, ctx(12 * 60, opens = mapOf(key to 4))))
        assertTrue(Conditions.holds(p, ctx(12 * 60, opens = mapOf(key to 5))))
    }

    // --- the rest --------------------------------------------------------

    @Test
    fun `overnight follows the flag it is given`() {
        val p = policy(ConditionType.OVERNIGHT)
        assertTrue(Conditions.holds(p, ctx(2 * 60, overnight = true)))
        assertFalse(Conditions.holds(p, ctx(2 * 60, overnight = false)))
    }

    @Test
    fun `risk fires at or above the level it names`() {
        val p = policy(ConditionType.RISK_AT_LEAST, ConditionParams(level = 2))
        assertFalse(Conditions.holds(p, ctx(12 * 60, risk = 1)))
        assertTrue(Conditions.holds(p, ctx(12 * 60, risk = 2)))
        assertTrue(Conditions.holds(p, ctx(12 * 60, risk = 3)))
    }

    /**
     * An unreadable row must contribute nothing rather than default to true.
     *
     * The failure mode of a guard has to be "does less than expected". A guard
     * that invents a block nobody asked for is one a man stops trusting, and a
     * blocker he does not trust is one he uninstalls.
     */
    @Test
    fun `unreadable parameters never invent a block`() {
        val broken = PolicyEntity(
            id = "p",
            targetType = TargetType.APP,
            targetKey = "com.instagram.android",
            conditionType = ConditionType.CURFEW,
            conditionParams = "{ this is not json",
            response = Response.CLOSE.level,
        )
        assertFalse(Conditions.holds(broken, ctx(23 * 60 + 30)))
    }
}
