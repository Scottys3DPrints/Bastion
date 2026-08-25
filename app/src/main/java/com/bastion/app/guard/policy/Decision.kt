package com.bastion.app.guard.policy

import com.bastion.app.data.db.Confidence
import com.bastion.app.data.db.Response

/**
 * How strong a block actually is, said out loud.
 *
 * The README has always carried an "honest limits" paragraph. This makes it a
 * property of the model instead, so every decision can name its own tier and no
 * screen can imply a wall where there is a speed bump.
 *
 * The distinction is not pedantry. A man who knows his blocker is REACTIVE on
 * Instagram and STRUCTURAL on porn sites is better protected than one who
 * believes both are walls — because he knows which of the two still needs him.
 */
enum class Tier(val phrase: String) {
    /** Held before anything renders. Defeated only by turning the filter off. */
    STRUCTURAL("Held at the network"),

    /**
     * The screen was reached and then closed.
     *
     * Accessibility blocking is reactive by nature and presenting it as a wall
     * overstates it. This is the tier the guard runs at.
     */
    REACTIVE("Closed on arrival"),

    /** Friction only. Defeated by tapping through. */
    ADVISORY("Slows it down"),
}

/**
 * One surface found on screen, and the single piece of evidence that found it.
 *
 * The signal is carried rather than discarded because two things need it later:
 * the confidence cap, which decides how hard this evidence is allowed to push,
 * and the receipt, which has to be able to say *which* signal matched rather
 * than "a rule did".
 */
data class ResolvedSurface(
    val surfaceId: String,
    val signalId: String,
    val confidence: Confidence,
)

/**
 * What Bastion decided, and everything needed to explain it afterwards.
 *
 * [reason] is written at decision time rather than reconstructed later, because
 * the facts that justify a block — which condition was true, whether a cap
 * applied — are all in scope here and none of them are in scope at the shield.
 */
data class Decision(
    val response: Response,
    val tier: Tier,
    val reason: String,
    val policyId: String? = null,
    val surfaceId: String? = null,
    val signalId: String? = null,
    /** True when a signal's confidence held the response below what was asked. */
    val capped: Boolean = false,
) {
    /** WATCH counts an arrival and takes nothing away. Everything above acts. */
    val blocks: Boolean get() = response.level > Response.WATCH.level
}
