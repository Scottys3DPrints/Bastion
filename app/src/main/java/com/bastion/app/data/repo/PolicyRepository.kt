package com.bastion.app.data.repo

import com.bastion.app.data.db.BlockMode
import com.bastion.app.data.db.ConditionType
import com.bastion.app.data.db.GuardedAppEntity
import com.bastion.app.data.db.PolicyDao
import com.bastion.app.data.db.PolicyEntity
import com.bastion.app.data.db.PolicyEventEntity
import com.bastion.app.data.db.PolicySource
import com.bastion.app.data.db.Response
import com.bastion.app.data.db.SignalEntity
import com.bastion.app.data.db.TargetType
import com.bastion.app.guard.policy.Catalogue
import com.bastion.app.guard.policy.ConditionParams
import com.bastion.app.guard.policy.Conditions
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * The v2 model, and the one place that keeps it in step with the v1 tables.
 *
 * ## Why both models are written for one release
 *
 * The v1 tables are not read by the guard any more, but they are still written,
 * and that is deliberate rather than laziness. `guarded_app` and `feed_rule` are
 * what a backup file contains and what a rollback would land on. If the UI wrote
 * only policies, then restoring last week's backup — or installing the previous
 * APK after a bad release — would silently revert a man's protection to whatever
 * it was before, with nothing to say so.
 *
 * So for one release the old tables shadow the new ones. They come out in 8 → 9,
 * once this has shipped clean, and the mirroring goes with them.
 */
class PolicyRepository(private val dao: PolicyDao) {

    val categories: Flow<List<com.bastion.app.data.db.CategoryEntity>> = dao.categories()
    val surfaces: Flow<List<com.bastion.app.data.db.SurfaceEntity>> = dao.surfaces()
    val signals: Flow<List<SignalEntity>> = dao.signals()
    val policies: Flow<List<PolicyEntity>> = dao.policies()
    val recentEvents: Flow<List<PolicyEventEntity>> = dao.recent()

    suspend fun allSurfaces() = dao.allSurfaces()
    suspend fun allCategories() = dao.allCategories()
    suspend fun allSignals() = dao.allSignals()
    suspend fun allPolicies() = dao.allPolicies()

    /**
     * Put the shipped catalogue on a phone that has never seen it.
     *
     * A fresh install runs no migration, so without this the whole v2 model
     * would exist only on phones that upgraded. That asymmetry is the kind of
     * thing that looks fine for months and then produces a bug report nobody
     * can reproduce.
     *
     * Additive by id: a surface or signal already present is left exactly as it
     * is, so a man's own on/off decisions are never reset by a launch.
     */
    suspend fun seedCatalogue() {
        val knownCategories = dao.allCategories().map { it.key }.toSet()
        dao.upsertCategories(Catalogue.categories().filter { it.key !in knownCategories })

        val knownSurfaces = dao.allSurfaces().map { it.id }.toSet()
        dao.upsertSurfaces(Catalogue.surfaces().filter { it.id !in knownSurfaces })

        val knownSignals = dao.allSignals().map { it.id }.toSet()
        dao.upsertSignals(Catalogue.signals().filter { it.id !in knownSignals })
    }

    /**
     * Deliver catalogue changes to a phone that already has one, and retire
     * what this version no longer ships.
     *
     * The same two jobs `syncBuiltInRules` does for v1, and the same hard rule:
     * `enabled` comes from disk, never from the shipped definition, so a sync
     * can never quietly switch something on or off. Only labels, scope and
     * confidence — the parts that are ours — are brought up to date.
     *
     * Anything captured with Learn Mode is left alone entirely. It belongs to
     * the man who captured it.
     */
    suspend fun syncCatalogue() {
        seedCatalogue()

        val shipped = Catalogue.signals().associateBy { it.id }
        dao.allSignals().filter { it.builtIn }.forEach { row ->
            val current = shipped[row.id]
            if (current == null) {
                dao.deleteSignal(row.id)
                return@forEach
            }
            val updated = row.copy(
                surfaceId = current.surfaceId,
                matchType = current.matchType,
                matchValue = current.matchValue,
                scopePackage = current.scopePackage,
                confidence = current.confidence,
            )
            if (updated != row) dao.upsertSignal(updated)
        }

        val shippedSurfaces = Catalogue.surfaces().associateBy { it.id }
        dao.allSurfaces().filter { it.builtIn }.forEach { row ->
            val current = shippedSurfaces[row.id]
            if (current == null) {
                dao.deleteSurface(row.id)
                return@forEach
            }
            val updated = row.copy(
                categoryKey = current.categoryKey,
                serviceKey = current.serviceKey,
                label = current.label,
            )
            if (updated != row) dao.upsertSurface(updated)
        }
    }

    // --- Mirroring the v1 UI onto policies --------------------------------

    /**
     * One guarded app, expressed as policies.
     *
     * Replaces rather than merges: the policies for an app are derived entirely
     * from its row, so leaving an older shape behind would mean a man who moved
     * Instagram from "after 23:00" to "feeds only" still had the curfew running.
     * That is the class of bug the old model produced constantly, and it comes
     * from adding without removing.
     */
    suspend fun applyGuardedApp(app: GuardedAppEntity) {
        clearPoliciesFor(app.packageName)

        val close = Response.CLOSE.level
        val enabled = app.enabled

        suspend fun appPolicy(
            condition: ConditionType,
            params: ConditionParams = ConditionParams(),
        ) {
            dao.upsertPolicy(
                PolicyEntity(
                    id = "app_${app.packageName}_${condition.name.lowercase()}",
                    targetType = TargetType.APP,
                    targetKey = app.packageName,
                    conditionType = condition,
                    conditionParams = if (condition == ConditionType.ALWAYS) ""
                    else Conditions.encode(params),
                    response = close,
                    enabled = enabled,
                    source = PolicySource.USER,
                ),
            )
        }

        when (app.mode) {
            BlockMode.FULL -> appPolicy(ConditionType.ALWAYS)

            BlockMode.SCHEDULE -> appPolicy(
                ConditionType.CURFEW,
                ConditionParams(startMinute = app.scheduleStart, endMinute = app.scheduleEnd),
            )

            BlockMode.TIME_LIMIT -> appPolicy(
                ConditionType.DAILY_BUDGET,
                ConditionParams(minutes = app.timeLimitMinutes),
            )

            BlockMode.FEED_ONLY -> {
                val service = Catalogue.SERVICE_BY_PACKAGE[app.packageName] ?: return
                Catalogue.surfacesOfService(service).forEach { surface ->
                    dao.upsertPolicy(
                        PolicyEntity(
                            id = "surface_${surface.id}",
                            targetType = TargetType.SURFACE,
                            targetKey = surface.id,
                            conditionType = ConditionType.ALWAYS,
                            response = close,
                            enabled = enabled,
                            source = PolicySource.USER,
                        ),
                    )
                }
            }
        }
    }

    /**
     * Everything an app was carrying, removed.
     *
     * Both its own policies and the surface policies that only existed because
     * its service was guarded. Unguarding an app has to leave nothing behind —
     * a leftover surface policy is a block with no visible owner, which is the
     * worst kind there is: it cannot be found by looking at the app it closes.
     */
    suspend fun clearPoliciesFor(packageName: String) {
        dao.deletePoliciesFor(TargetType.APP, packageName)
        val service = Catalogue.SERVICE_BY_PACKAGE[packageName] ?: return
        Catalogue.surfacesOfService(service).forEach {
            dao.deletePoliciesFor(TargetType.SURFACE, it.id)
        }
    }

    /**
     * A rule switched on or off in the v1 UI, carried onto its signal.
     *
     * Matched by id, which the catalogue keeps identical to the old rule id, so
     * this is a copy rather than an interpretation.
     */
    suspend fun mirrorRuleState(ruleId: String, enabled: Boolean) {
        val signal = dao.allSignals().firstOrNull { it.id == ruleId } ?: return
        if (signal.enabled == enabled) return
        dao.upsertSignal(signal.copy(enabled = enabled, updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteSignal(id: String) = dao.deleteSignal(id)

    /**
     * Rebuild the v2 model from a restored backup.
     *
     * A restore writes the v1 tables straight through the backup DAO, which is
     * correct — those are what the file contains. But the guard reads policies,
     * so without this a man restores his backup, sees every app listed exactly
     * as he left it, and is protected by none of them. That is the worst
     * failure this app can have: not a block that fires wrongly, but a screen
     * that says he is covered when he is not.
     *
     * Shaped like the 7 -> 8 migration on purpose. They are the same job —
     * turning v1 rows into v2 ones — and the day they disagree is the day a
     * restore quietly means something different from an upgrade.
     */
    suspend fun restoreFrom(
        apps: List<GuardedAppEntity>,
        rules: List<com.bastion.app.data.db.FeedRuleEntity>,
    ) {
        apps.forEach { applyGuardedApp(it) }

        rules.forEach { rule ->
            if (rule.builtIn) {
                mirrorRuleState(rule.id, rule.enabled)
                return@forEach
            }
            val surfaceId = "learned_${rule.packageName}"
            dao.upsertSurface(
                com.bastion.app.data.db.SurfaceEntity(
                    id = surfaceId,
                    categoryKey = Catalogue.SHORT_FEED,
                    serviceKey = Catalogue.SERVICE_BY_PACKAGE[rule.packageName]
                        ?: rule.packageName,
                    label = "Learned in ${rule.packageName}",
                    builtIn = false,
                ),
            )
            dao.upsertSignal(
                SignalEntity(
                    id = rule.id,
                    surfaceId = surfaceId,
                    matchType = rule.matchType,
                    matchValue = rule.matchValue,
                    scopePackage = if (rule.matchType == com.bastion.app.data.db.MatchType.URL) {
                        null
                    } else {
                        rule.packageName
                    },
                    enabled = rule.enabled,
                    builtIn = false,
                ),
            )
            // A captured surface needs a policy or it is evidence nobody asked
            // to act on. The man captured it to close something; this is that.
            dao.upsertPolicy(
                PolicyEntity(
                    id = "surface_$surfaceId",
                    targetType = TargetType.SURFACE,
                    targetKey = surfaceId,
                    conditionType = ConditionType.ALWAYS,
                    response = Response.CLOSE.level,
                    source = PolicySource.USER,
                ),
            )
        }
    }

    // --- Runtime ----------------------------------------------------------

    suspend fun markMatched(signalId: String, at: Long = System.currentTimeMillis()) =
        dao.markMatched(signalId, at)

    suspend fun record(
        packageName: String,
        outcome: com.bastion.app.data.db.Outcome,
        response: Response,
        policyId: String? = null,
        surfaceId: String? = null,
        signalId: String? = null,
        risk: Int = 0,
    ) {
        dao.record(
            PolicyEventEntity(
                id = UUID.randomUUID().toString(),
                ts = System.currentTimeMillis(),
                policyId = policyId,
                surfaceId = surfaceId,
                signalId = signalId,
                packageName = packageName,
                outcome = outcome,
                response = response.level,
                risk,
            ),
        )
    }

    /** Thirty days is more than enough to explain the last thing that happened. */
    suspend fun pruneEvents(now: Long = System.currentTimeMillis()) =
        dao.prune(now - 30L * 24 * 60 * 60 * 1000)
}
