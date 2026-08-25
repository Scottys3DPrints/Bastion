package com.bastion.app.guard.policy

import com.bastion.app.data.db.ConditionType
import com.bastion.app.data.db.PolicyEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The parameters a condition can carry, all optional.
 *
 * One flat shape rather than a sealed hierarchy per condition, because these
 * are stored as a JSON string in a column and read back by code that must never
 * throw on a row it does not recognise. A future condition adds a field and
 * every older row keeps parsing.
 */
@Serializable
data class ConditionParams(
    /** Minutes from midnight. */
    val startMinute: Int? = null,
    val endMinute: Int? = null,
    /** ISO day numbers, 1 = Monday. Blank or absent means every day. */
    val daysCsv: String? = null,
    /** DAILY_BUDGET. */
    val minutes: Int? = null,
    /** OPEN_COUNT. */
    val opens: Int? = null,
    /** RISK_AT_LEAST. */
    val level: Int? = null,
) {
    val days: List<Int>
        get() = daysCsv.orEmpty().split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..7 }
}

/**
 * Everything a condition is allowed to know, gathered before any decision.
 *
 * Passed in rather than read, so the whole engine is pure. This is the entire
 * reason the v2 pipeline exists: the hard bugs in v1 — the inline-reel false
 * positive, the stories viewer, the Messenger web view — were all found by the
 * user rather than by a test, because none of the logic could run without a
 * phone in a hand.
 *
 * [usedMillis] and [opens] are keyed by whatever the policy targets: a package
 * for an app policy, a category key for a category one. That is what lets a
 * budget be spent across a whole category rather than per app — closing
 * Instagram at its limit and sending a man to TikTok with a fresh ten minutes
 * is not a budget, it is a sequence of them.
 */
data class PolicyContext(
    val packageName: String,
    /** Minutes from local midnight. */
    val minuteOfDay: Int,
    /** ISO day of week, 1 = Monday. */
    val isoDayOfWeek: Int,
    val usedMillis: Map<String, Long> = emptyMap(),
    val opens: Map<String, Int> = emptyMap(),
    /** Charging, screen dark, local night. A proxy for "in bed", labelled as one. */
    val isOvernight: Boolean = false,
    /** 0 calm .. 3 high. Always 0 until the risk model ships. */
    val risk: Int = 0,
)

/**
 * Whether a policy is live right now.
 *
 * Pure, total, and never throws: a condition that cannot be read contributes
 * nothing rather than defaulting to true. An unreadable row must not be able to
 * invent a block a man never asked for — the failure mode of a guard has to be
 * "does less than expected", never "does something unexplained".
 */
object Conditions {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun paramsOf(policy: PolicyEntity): ConditionParams =
        if (policy.conditionParams.isBlank()) ConditionParams()
        else runCatching { json.decodeFromString<ConditionParams>(policy.conditionParams) }
            .getOrDefault(ConditionParams())

    fun encode(params: ConditionParams): String = json.encodeToString(params)

    fun holds(policy: PolicyEntity, ctx: PolicyContext): Boolean {
        if (!policy.enabled) return false
        val p = paramsOf(policy)
        return when (policy.conditionType) {
            ConditionType.ALWAYS -> true

            ConditionType.CURFEW -> {
                val start = p.startMinute ?: return false
                val end = p.endMinute ?: return false
                withinWindow(ctx.minuteOfDay, start, end) && runsToday(p.days, ctx, start, end)
            }

            // Strictly greater: a budget of ten minutes is not spent at ten
            // minutes exactly, it is spent once he goes past it. Matches what
            // TIME_LIMIT already did, so no migrated policy changes its moment.
            ConditionType.DAILY_BUDGET -> {
                val minutes = p.minutes ?: return false
                (ctx.usedMillis[policy.targetKey] ?: 0L) >= minutes * 60_000L
            }

            ConditionType.OPEN_COUNT -> {
                val limit = p.opens ?: return false
                (ctx.opens[policy.targetKey] ?: 0) >= limit
            }

            ConditionType.OVERNIGHT -> ctx.isOvernight

            ConditionType.RISK_AT_LEAST -> ctx.risk >= (p.level ?: return false)
        }
    }

    /**
     * Windows routinely wrap midnight; 22:00 to 06:00 is the common case.
     *
     * Identical to the arithmetic the service has always used, moved here so it
     * can finally be tested. End is exclusive.
     */
    fun withinWindow(minuteOfDay: Int, start: Int, end: Int): Boolean =
        if (start <= end) minuteOfDay in start until end
        else minuteOfDay >= start || minuteOfDay < end

    /**
     * Which day a wrapped window belongs to.
     *
     * The subtle half of a curfew across midnight: at 01:00 on Saturday inside a
     * Friday 23:00–06:00 window, the day being asked about is *Friday*. Testing
     * today's date against the day list would let every Friday night curfew
     * expire at midnight — which is the hour it exists for.
     *
     * An empty list means every day, matching the existing `curfewDays`
     * semantics in SettingsStore.
     */
    fun runsToday(days: List<Int>, ctx: PolicyContext, start: Int, end: Int): Boolean {
        if (days.isEmpty()) return true
        val wrapped = start > end
        val inTail = wrapped && ctx.minuteOfDay < end
        val day = if (inTail) previousDay(ctx.isoDayOfWeek) else ctx.isoDayOfWeek
        return day in days
    }

    private fun previousDay(isoDay: Int): Int = if (isoDay == 1) 7 else isoDay - 1
}
