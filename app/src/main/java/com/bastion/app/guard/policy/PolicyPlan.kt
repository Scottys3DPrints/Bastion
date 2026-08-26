package com.bastion.app.guard.policy

import com.bastion.app.data.db.BlockMode
import com.bastion.app.data.db.ConditionType
import com.bastion.app.data.db.GuardedAppEntity
import com.bastion.app.data.db.PolicyEntity
import com.bastion.app.data.db.PolicySource
import com.bastion.app.data.db.Response
import com.bastion.app.data.db.TargetType

/**
 * What a set of guarded apps means, as policies.
 *
 * Pure, and deriving the whole set from the whole list rather than one app at a
 * time. That is not a style preference — it is the fix for a bug the per-app
 * version had built into its shape.
 *
 * Surfaces belong to a *service*, and a service can be reached by more than one
 * package: Messenger and Facebook are both Facebook, TikTok and TikTok Lite are
 * both TikTok. Applying one app meant clearing its service's surface policies
 * and rewriting them, so unguarding Messenger took Facebook's protection down
 * with it even though Facebook was still guarded. Which app you touched last
 * decided what was protected.
 *
 * A function of the whole list has no order to get wrong.
 */
object PolicyPlan {

    fun forApps(apps: List<GuardedAppEntity>): List<PolicyEntity> {
        val out = LinkedHashMap<String, PolicyEntity>()
        val close = Response.CLOSE.level

        for (app in apps) {
            fun appPolicy(condition: ConditionType, params: ConditionParams = ConditionParams()) {
                val id = "app_${app.packageName}_${condition.name.lowercase()}"
                out[id] = PolicyEntity(
                    id = id,
                    targetType = TargetType.APP,
                    targetKey = app.packageName,
                    conditionType = condition,
                    conditionParams = if (condition == ConditionType.ALWAYS) ""
                    else Conditions.encode(params),
                    response = close,
                    enabled = app.enabled,
                    source = PolicySource.USER,
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
                    val service = Catalogue.SERVICE_BY_PACKAGE[app.packageName] ?: continue
                    Catalogue.surfacesOfService(service).forEach { surface ->
                        val id = "surface_${surface.id}"
                        // Last writer wins, and only upwards: if one package of a
                        // service is guarded and another is switched off, the
                        // service stays guarded. A surface is protected when
                        // anything that reaches it says so.
                        val existing = out[id]
                        if (existing == null || (!existing.enabled && app.enabled)) {
                            out[id] = PolicyEntity(
                                id = id,
                                targetType = TargetType.SURFACE,
                                targetKey = surface.id,
                                conditionType = ConditionType.ALWAYS,
                                response = close,
                                enabled = app.enabled,
                                source = PolicySource.USER,
                            )
                        }
                    }
                }
            }
        }
        return out.values.toList()
    }
}
