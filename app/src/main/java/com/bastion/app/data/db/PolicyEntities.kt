package com.bastion.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/*
 * Protection Model v2 — the storage half.
 *
 * The model v1 could not express is small to state: what a man is avoiding,
 * where it lives, how it is recognised, when the rule is live, and how hard
 * Bastion pushes back are five separate questions, and `BlockMode` held them in
 * one enum with four values. "Instagram: Reels always closed, whole app after
 * 22:00, fifteen minutes a day" is three true sentences and the old schema could
 * store exactly one of them.
 *
 * So each of the five gets its own noun and its own table, and nothing mixes
 * dimensions again:
 *
 *   Category  what he is avoiding, in his words   — chosen by him
 *     Surface   one destination he would name      — Bastion's catalogue
 *       Signal    one piece of evidence            — catalogue + Learn Mode
 *   Policy    = target x condition x response
 *   PolicyEvent  what actually happened, so a block can explain itself
 *
 * Nothing here is transmitted anywhere, and nothing here reads message content.
 */

// --- Response: the friction ladder ---------------------------------------

/**
 * How hard Bastion pushes back, from counting to sealed.
 *
 * Ordered, and the order is load-bearing: [level] is what "strongest response
 * wins" compares, and what the cooling-off lock uses to tell tightening from
 * loosening. Raising is instant; lowering waits. That is the app's oldest law
 * and it now applies to a rung rather than to `BlockMode` strictness.
 *
 * Only WATCH and CLOSE are enforced today. PAUSE and COST are the ladder's
 * middle and arrive with their UI; SEALED is CLOSE plus a loosening gate. They
 * exist in the model now so that the enforcer, the migration and the tests are
 * written against the finished shape rather than being widened later — widening
 * an enum that decides how hard to block is exactly the change nobody wants to
 * make in a hurry.
 */
enum class Response(val level: Int) {
    /** Nothing is blocked; arrivals are counted. The honest opening move. */
    WATCH(0),

    /** Full-screen interrupt with a way through. */
    PAUSE(1),

    /** A way through that costs something real. */
    COST(2),

    /** Closed on arrival: home, then the shield. */
    CLOSE(3),

    /** Closed, and cannot be loosened without the cooling-off wait. */
    SEALED(4),
    ;

    companion object {
        /** Tolerant on the way in: an unreadable rung must not disarm a guard. */
        fun ofLevel(level: Int): Response =
            entries.firstOrNull { it.level == level } ?: CLOSE
    }
}

/** What a policy points at. */
enum class TargetType { CATEGORY, SURFACE, APP }

/** When a policy is live. */
enum class ConditionType { ALWAYS, CURFEW, DAILY_BUDGET, OPEN_COUNT, OVERNIGHT, RISK_AT_LEAST }

/** Where a policy came from, so a man can tell his own choices from ours. */
enum class PolicySource { DEFAULT, USER, STANCE }

/** What happened when a surface was reached. */
enum class Outcome { ARRIVED, PAUSED, CONTINUED, COST_PAID, CLOSED }

/**
 * How much a signal is trusted, and therefore how hard it is allowed to push.
 *
 * A view id or an address names a destination: being there is the whole of the
 * evidence. A title is a net rather than a wall — it matches words against a
 * shipped list, and a false positive throws a man out of a lecture. So the
 * confidence of the evidence caps the response it can produce, which is the
 * structural version of a rule TitleFilter's own comments have always stated.
 */
enum class Confidence(val cap: Response) {
    /** A guess. Never closes on its own. */
    LOW(Response.PAUSE),

    /** Circumstantial. */
    MEDIUM(Response.CLOSE),

    /** Names a destination. */
    HIGH(Response.SEALED),
    ;

    companion object {
        fun ofOrdinal(value: Int): Confidence = entries.getOrElse(value) { HIGH }
    }
}

// --- The tables ----------------------------------------------------------

/**
 * A thing a man is avoiding, in the words he would use.
 *
 * This is the whole configuration surface in v2: he chooses categories, and the
 * app resolves each into domains, surfaces and installed packages, then shows
 * him the resolution. Per-app settings survive as an override and an inspector,
 * never as the way protection is set up.
 */
@Entity(tableName = "protection_category")
@Serializable
data class CategoryEntity(
    @PrimaryKey val key: String,
    val label: String,
    val chosen: Boolean,
    /** A [Response] level, stored as its number so a new rung needs no migration. */
    val response: Int,
    /** Risk level at which this tightens by one rung; null never escalates. */
    val escalateAtRisk: Int? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * One destination a man would name out loud: "Instagram Reels".
 *
 * A surface owns many signals, and that redundancy is the point. When Instagram
 * renames a view id the surface does not die, it degrades — and because the
 * surface is a named thing rather than a loose row, it can say so.
 */
@Entity(tableName = "surface", indices = [Index("categoryKey"), Index("serviceKey")])
@Serializable
data class SurfaceEntity(
    @PrimaryKey val id: String,
    val categoryKey: String,
    /**
     * The service, honestly named.
     *
     * v1 used `packageName` as the owner of a rule and then made address rules
     * universal, at which point `com.zhiliaoapp.musically` had stopped being a
     * package and become a label for TikTok-the-service. The schema was lying,
     * and eleven generations of built-in-rule cleanup were the cost. A service
     * key says what it is.
     */
    val serviceKey: String,
    val label: String,
    val builtIn: Boolean = true,
    val enabled: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * One piece of evidence that a man is standing on a surface.
 *
 * [scopePackage] is the field that retires the old fight. A signal that is
 * genuinely app-specific — a view id inside Instagram — says so. One that is a
 * property of the destination wherever it is reached — the address
 * `instagram.com/reel` — carries null, meaning any app. There is no sentinel
 * package, no "which browser is this", and no list of browsers to keep current.
 */
@Entity(
    tableName = "surface_signal",
    indices = [Index("surfaceId"), Index("scopePackage"), Index("matchType")],
)
@Serializable
data class SignalEntity(
    @PrimaryKey val id: String,
    val surfaceId: String,
    val matchType: MatchType,
    val matchValue: String,
    /** null means "anywhere". */
    val scopePackage: String? = null,
    /** A [Confidence] ordinal. Caps the response this signal can produce. */
    val confidence: Int = Confidence.HIGH.ordinal,
    val enabled: Boolean = true,
    val builtIn: Boolean = true,
    /**
     * When this last matched anything, for drift detection.
     *
     * A view id belongs to another company's app and gets renamed without
     * notice. When that happens the signal stops matching and nothing says so —
     * the guard decays in silence and the first person to find out is the man it
     * was protecting. Bastion already knows how often he opens the app; a signal
     * whose app is used constantly and which has never fired is a signal to ask
     * about. 0 means "never yet".
     */
    val lastMatchedAt: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * target x condition x response — the whole of "what happens, and when".
 *
 * Several policies may cover the same screen; the strongest response wins. That
 * is what lets "Reels always closed" and "the whole app after 23:00" both be
 * true of Instagram at the same time, which is the sentence v1 could not hold.
 */
@Entity(
    tableName = "policy",
    indices = [Index("targetType"), Index("targetKey"), Index("enabled")],
)
@Serializable
data class PolicyEntity(
    @PrimaryKey val id: String,
    val targetType: TargetType,
    val targetKey: String,
    val conditionType: ConditionType,
    /** JSON. Empty for ALWAYS and OVERNIGHT, which take no parameters. */
    val conditionParams: String = "",
    /** A [Response] level. */
    val response: Int,
    val escalateAtRisk: Int? = null,
    val enabled: Boolean = true,
    val source: PolicySource = PolicySource.DEFAULT,
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * What actually happened, so a block can explain itself.
 *
 * Two jobs. It is the evidence behind "why did this close?" — the surface, the
 * signal, the policy, the condition, the tier. And it is the only honest way to
 * learn what a man's evenings look like before anything is taken away from him,
 * which is what makes the Watch rung worth offering first.
 *
 * It records that a surface was reached. It never records what was on it.
 */
@Entity(tableName = "policy_event", indices = [Index("ts"), Index("packageName")])
data class PolicyEventEntity(
    @PrimaryKey val id: String,
    val ts: Long,
    val policyId: String? = null,
    val surfaceId: String? = null,
    val signalId: String? = null,
    val packageName: String,
    val outcome: Outcome,
    /** The response level actually applied, after any cap or escalation. */
    val response: Int,
    val riskAtTime: Int = 0,
)
