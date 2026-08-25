package com.bastion.app.guard.policy

import com.bastion.app.data.db.CategoryEntity
import com.bastion.app.data.db.PolicyEntity
import com.bastion.app.data.db.Response
import com.bastion.app.data.db.SurfaceEntity
import com.bastion.app.data.db.TargetType

/**
 * Which policies cover what is on screen, and what the strongest of them says.
 *
 * Pure Kotlin, no Android imports, no clock of its own. Everything it needs
 * arrives in [PolicyContext]. That constraint is the point of the whole
 * restructure: the decision that used to live tangled inside `evaluate()` —
 * next to lockdown state, learn mode, the veil and grayscale — is now a
 * function of its inputs, and can be argued with on a laptop.
 *
 * ## Strongest wins
 *
 * Several policies can cover one screen at once, and in v2 that is normal
 * rather than a conflict to resolve. "Reels always closed", "the whole app
 * after 23:00" and "twenty minutes a day across all feeds" are three true
 * sentences about Instagram; v1 could store one of them because `BlockMode`
 * held what, when and how-hard in a single enum with four values. Here each is
 * its own policy and the highest rung on offer is the one that happens.
 */
object PolicyEngine {

    /**
     * @param present the surfaces recognised on screen, with the signal for each
     * @param policies every policy; disabled ones and false conditions drop out
     * @param surfaces the catalogue, for resolving a surface to its category
     * @param categories chosen state and per-category response
     */
    fun decide(
        present: List<ResolvedSurface>,
        policies: List<PolicyEntity>,
        surfaces: Map<String, SurfaceEntity>,
        categories: Map<String, CategoryEntity>,
        ctx: PolicyContext,
    ): Decision? {
        val candidates = mutableListOf<Decision>()

        // --- App policies: about the app, whatever screen is showing --------
        //
        // These carry no signal, so nothing caps them. "Close this app" is not
        // evidence about a destination, it is a decision about the whole door.
        for (policy in policies) {
            if (policy.targetType != TargetType.APP) continue
            if (policy.targetKey != ctx.packageName) continue
            if (!Conditions.holds(policy, ctx)) continue
            candidates += Decision(
                response = Response.ofLevel(policy.response),
                tier = Tier.REACTIVE,
                reason = appReason(policy, ctx),
                policyId = policy.id,
            )
        }

        // --- Surface and category policies ---------------------------------
        for (found in present) {
            val surface = surfaces[found.surfaceId] ?: continue
            if (!surface.enabled) continue

            for (policy in policies) {
                val targets = when (policy.targetType) {
                    TargetType.SURFACE -> policy.targetKey == surface.id
                    // A category only reaches a surface if he actually chose it.
                    // A category left unchosen must never leak a block through
                    // a surface that happens to belong to it.
                    TargetType.CATEGORY ->
                        policy.targetKey == surface.categoryKey &&
                            categories[surface.categoryKey]?.chosen == true
                    TargetType.APP -> false
                }
                if (!targets) continue
                if (!Conditions.holds(policy, ctx)) continue

                val asked = Response.ofLevel(policy.response)
                val cap = found.confidence.cap
                val granted = if (asked.level > cap.level) cap else asked
                candidates += Decision(
                    response = granted,
                    tier = Tier.REACTIVE,
                    reason = surfaceReason(surface, policy, ctx, granted != asked),
                    policyId = policy.id,
                    surfaceId = surface.id,
                    signalId = found.signalId,
                    capped = granted != asked,
                )
            }
        }

        // Ties go to the more specific: a decision that names the surface it
        // closed can explain itself, and one that only names the app cannot.
        return candidates.maxWithOrNull(
            compareBy({ it.response.level }, { if (it.surfaceId != null) 1 else 0 }),
        )
    }

    // --- Receipts ---------------------------------------------------------
    //
    // Written here, where the facts are, rather than reconstructed at the
    // shield where none of them are still in scope. Plain sentences: no jargon,
    // no rule ids, and nothing that reads as a verdict on the man.

    private fun appReason(policy: PolicyEntity, ctx: PolicyContext): String =
        "This app is closed" + whenClause(policy, ctx) + "."

    private fun surfaceReason(
        surface: SurfaceEntity,
        policy: PolicyEntity,
        ctx: PolicyContext,
        capped: Boolean,
    ): String {
        val head = "${surface.label} is closed" + whenClause(policy, ctx) + "."
        return if (capped) {
            "$head Held one step below what you asked for, because this one was " +
                "recognised by the words on screen rather than by the screen itself."
        } else {
            head
        }
    }

    private fun whenClause(policy: PolicyEntity, ctx: PolicyContext): String {
        val p = Conditions.paramsOf(policy)
        return when (policy.conditionType) {
            com.bastion.app.data.db.ConditionType.ALWAYS -> ""
            com.bastion.app.data.db.ConditionType.CURFEW ->
                " between ${clock(p.startMinute)} and ${clock(p.endMinute)}"
            com.bastion.app.data.db.ConditionType.DAILY_BUDGET ->
                " — ${p.minutes ?: 0} minutes a day is used up"
            com.bastion.app.data.db.ConditionType.OPEN_COUNT ->
                " — you have opened it ${p.opens ?: 0} times today"
            com.bastion.app.data.db.ConditionType.OVERNIGHT -> " overnight"
            com.bastion.app.data.db.ConditionType.RISK_AT_LEAST -> ""
        }
    }

    private fun clock(minute: Int?): String {
        val m = minute ?: return "—"
        return "%02d:%02d".format(m / 60 % 24, m % 60)
    }
}
