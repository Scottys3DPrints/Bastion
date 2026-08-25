package com.bastion.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bastion.app.guard.policy.Catalogue
import com.bastion.app.guard.policy.ConditionParams
import com.bastion.app.guard.policy.Conditions

/**
 * v7 → v8. Protection Model v2: the five tables, and everything already on the
 * phone carried into them.
 *
 * Additive throughout. `feed_rule` and `guarded_app` are read and then left
 * exactly where they are — not dropped, not emptied, not renamed. For one
 * release the old model is dead weight that still round-trips, which is what
 * makes a rollback a downgrade rather than a data loss. They come out in 8 → 9,
 * once a release has shipped clean.
 *
 * ## What a man had, and where it lands
 *
 * | v1 | v2 |
 * |---|---|
 * | `FULL` | APP policy, ALWAYS, Close |
 * | `SCHEDULE` | APP policy, CURFEW(start,end), Close |
 * | `TIME_LIMIT` | APP policy, DAILY_BUDGET(minutes), Close |
 * | `FEED_ONLY` | SURFACE policies over that service's surfaces, ALWAYS, Close |
 *
 * Everything lands on Close because Close is what v1 did. The ladder below it
 * exists in the schema and nothing migrates onto it: an upgrade is not the
 * moment to discover that a guard has quietly become a suggestion.
 *
 * ## The one behaviour that changes, stated rather than buried
 *
 * Surfaces belong to a *service*, and TikTok is one service reached by two
 * packages. So a man who guarded TikTok now also has TikTok Lite's feed
 * recognised, where in v1 the Lite view id sat under its own package and never
 * fired unless he had separately guarded that too. It tightens and cannot
 * loosen, and it is the correct reading of what he asked for — he named TikTok,
 * not a package name he has never seen.
 */
internal val MIGRATION_7_8 = Migration(7, 8) { db ->
    createTables(db)
    seedCatalogue(db)
    carryFeedRules(db)
    carryGuardedApps(db)
}

private fun createTables(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `protection_category` (
            `key` TEXT NOT NULL,
            `label` TEXT NOT NULL,
            `chosen` INTEGER NOT NULL,
            `response` INTEGER NOT NULL,
            `escalateAtRisk` INTEGER,
            `updatedAt` INTEGER NOT NULL,
            PRIMARY KEY(`key`)
        )
        """.trimIndent(),
    )

    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `surface` (
            `id` TEXT NOT NULL,
            `categoryKey` TEXT NOT NULL,
            `serviceKey` TEXT NOT NULL,
            `label` TEXT NOT NULL,
            `builtIn` INTEGER NOT NULL,
            `enabled` INTEGER NOT NULL,
            `updatedAt` INTEGER NOT NULL,
            PRIMARY KEY(`id`)
        )
        """.trimIndent(),
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_surface_categoryKey` ON `surface` (`categoryKey`)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_surface_serviceKey` ON `surface` (`serviceKey`)")

    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `surface_signal` (
            `id` TEXT NOT NULL,
            `surfaceId` TEXT NOT NULL,
            `matchType` TEXT NOT NULL,
            `matchValue` TEXT NOT NULL,
            `scopePackage` TEXT,
            `confidence` INTEGER NOT NULL,
            `enabled` INTEGER NOT NULL,
            `builtIn` INTEGER NOT NULL,
            `lastMatchedAt` INTEGER NOT NULL,
            `updatedAt` INTEGER NOT NULL,
            PRIMARY KEY(`id`)
        )
        """.trimIndent(),
    )
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_surface_signal_surfaceId` " +
            "ON `surface_signal` (`surfaceId`)",
    )
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_surface_signal_scopePackage` " +
            "ON `surface_signal` (`scopePackage`)",
    )
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_surface_signal_matchType` " +
            "ON `surface_signal` (`matchType`)",
    )

    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `policy` (
            `id` TEXT NOT NULL,
            `targetType` TEXT NOT NULL,
            `targetKey` TEXT NOT NULL,
            `conditionType` TEXT NOT NULL,
            `conditionParams` TEXT NOT NULL,
            `response` INTEGER NOT NULL,
            `escalateAtRisk` INTEGER,
            `enabled` INTEGER NOT NULL,
            `source` TEXT NOT NULL,
            `updatedAt` INTEGER NOT NULL,
            PRIMARY KEY(`id`)
        )
        """.trimIndent(),
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_policy_targetType` ON `policy` (`targetType`)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_policy_targetKey` ON `policy` (`targetKey`)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_policy_enabled` ON `policy` (`enabled`)")

    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `policy_event` (
            `id` TEXT NOT NULL,
            `ts` INTEGER NOT NULL,
            `policyId` TEXT,
            `surfaceId` TEXT,
            `signalId` TEXT,
            `packageName` TEXT NOT NULL,
            `outcome` TEXT NOT NULL,
            `response` INTEGER NOT NULL,
            `riskAtTime` INTEGER NOT NULL,
            PRIMARY KEY(`id`)
        )
        """.trimIndent(),
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_policy_event_ts` ON `policy_event` (`ts`)")
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_policy_event_packageName` " +
            "ON `policy_event` (`packageName`)",
    )
}

private fun seedCatalogue(db: SupportSQLiteDatabase) {
    val now = System.currentTimeMillis()
    Catalogue.categories().forEach { c ->
        db.execSQL(
            "INSERT OR REPLACE INTO protection_category " +
                "(`key`,`label`,`chosen`,`response`,`escalateAtRisk`,`updatedAt`) " +
                "VALUES (?,?,?,?,NULL,?)",
            arrayOf<Any>(c.key, c.label, if (c.chosen) 1 else 0, c.response, now),
        )
    }
    Catalogue.surfaces().forEach { s ->
        db.execSQL(
            "INSERT OR REPLACE INTO surface " +
                "(`id`,`categoryKey`,`serviceKey`,`label`,`builtIn`,`enabled`,`updatedAt`) " +
                "VALUES (?,?,?,?,1,1,?)",
            arrayOf<Any>(s.id, s.categoryKey, s.serviceKey, s.label, now),
        )
    }
    Catalogue.signals().forEach { sig ->
        db.execSQL(
            "INSERT OR REPLACE INTO surface_signal " +
                "(`id`,`surfaceId`,`matchType`,`matchValue`,`scopePackage`,`confidence`," +
                "`enabled`,`builtIn`,`lastMatchedAt`,`updatedAt`) VALUES (?,?,?,?,?,?,?,1,0,?)",
            arrayOf(
                sig.id, sig.surfaceId, sig.matchType.name, sig.matchValue,
                sig.scopePackage, sig.confidence, if (sig.enabled) 1 else 0, now,
            ),
        )
    }
}

/**
 * `feed_rule` → `surface_signal`, keeping every decision a man made.
 *
 * Built-in rows are matched by id, because the catalogue deliberately reuses the
 * old id scheme. So a rule he had switched off — "All of Facebook", most often —
 * arrives switched off, and one he had switched on arrives on. That is the whole
 * reason the ids were kept rather than regenerated: matching on values would
 * have meant interpreting his choices, and interpreting them is how they get
 * quietly reversed.
 *
 * Rows he captured himself are not built-in and have nowhere in the catalogue to
 * land, so each app's captures are gathered under one surface named for that
 * app. Nothing a man found with Learn Mode is lost, and it stops being an
 * orphan row with no owner — which is the other half of what surfaces are for.
 */
private fun carryFeedRules(db: SupportSQLiteDatabase) {
    val now = System.currentTimeMillis()
    val shipped = Catalogue.signals().associateBy { it.id }
    val learnedSurfaces = mutableSetOf<String>()

    db.query("SELECT `id`,`packageName`,`label`,`matchType`,`matchValue`,`enabled`,`builtIn` FROM `feed_rule`")
        .use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val pkg = cursor.getString(1)
                val label = cursor.getString(2)
                val matchType = cursor.getString(3)
                val matchValue = cursor.getString(4)
                val enabled = cursor.getInt(5)
                val builtIn = cursor.getInt(6) == 1

                if (builtIn) {
                    // Only its enabled state is his; everything else about a
                    // shipped row is ours and is already seeded.
                    if (shipped.containsKey(id)) {
                        db.execSQL(
                            "UPDATE surface_signal SET `enabled` = ?, `updatedAt` = ? WHERE `id` = ?",
                            arrayOf<Any>(enabled, now, id),
                        )
                    }
                    // A built-in row this version no longer ships is simply not
                    // carried. Generation 12 already deletes those from
                    // `feed_rule`; this is the same judgement, not a new one.
                    continue
                }

                val surfaceId = "learned_$pkg"
                if (learnedSurfaces.add(surfaceId)) {
                    db.execSQL(
                        "INSERT OR IGNORE INTO surface " +
                            "(`id`,`categoryKey`,`serviceKey`,`label`,`builtIn`,`enabled`,`updatedAt`) " +
                            "VALUES (?,?,?,?,0,1,?)",
                        arrayOf<Any>(
                            surfaceId,
                            Catalogue.SHORT_FEED,
                            Catalogue.SERVICE_BY_PACKAGE[pkg] ?: pkg,
                            "Learned in $pkg",
                            now,
                        ),
                    )
                }
                // A captured rule is always a view id in a named app, so it is
                // scoped to that app and trusted like any other view id. Its
                // label is kept as the value it matched on is kept: he chose it.
                val scope = if (matchType == MatchType.URL.name) null else pkg
                db.execSQL(
                    "INSERT OR REPLACE INTO surface_signal " +
                        "(`id`,`surfaceId`,`matchType`,`matchValue`,`scopePackage`,`confidence`," +
                        "`enabled`,`builtIn`,`lastMatchedAt`,`updatedAt`) VALUES (?,?,?,?,?,2,?,0,0,?)",
                    arrayOf(id, surfaceId, matchType, matchValue, scope, enabled, now),
                )
                // `label` is intentionally unused: a surface carries the name a
                // man reads, and a signal is evidence rather than a heading.
                @Suppress("UNUSED_EXPRESSION") label
            }
        }
}

/**
 * `guarded_app` → `policy`, one app at a time.
 *
 * A row that was disabled stays disabled. A mode that cannot be read lands on
 * FULL, because the failure direction for a guard is "does more than expected"
 * and never "silently does nothing".
 */
private fun carryGuardedApps(db: SupportSQLiteDatabase) {
    val now = System.currentTimeMillis()
    val close = com.bastion.app.data.db.Response.CLOSE.level

    db.query(
        "SELECT `packageName`,`mode`,`scheduleStart`,`scheduleEnd`,`timeLimitMinutes`,`enabled` " +
            "FROM `guarded_app`",
    ).use { cursor ->
        while (cursor.moveToNext()) {
            val pkg = cursor.getString(0)
            val mode = runCatching { BlockMode.valueOf(cursor.getString(1)) }
                .getOrDefault(BlockMode.FULL)
            val start = cursor.getInt(2)
            val end = cursor.getInt(3)
            val minutes = cursor.getInt(4)
            val enabled = cursor.getInt(5)

            fun appPolicy(condition: ConditionType, params: String) {
                db.execSQL(
                    "INSERT OR REPLACE INTO policy " +
                        "(`id`,`targetType`,`targetKey`,`conditionType`,`conditionParams`," +
                        "`response`,`escalateAtRisk`,`enabled`,`source`,`updatedAt`) " +
                        "VALUES (?,?,?,?,?,?,NULL,?,?,?)",
                    arrayOf<Any>(
                        "app_${pkg}_${condition.name.lowercase()}",
                        TargetType.APP.name, pkg, condition.name, params,
                        close, enabled, PolicySource.USER.name, now,
                    ),
                )
            }

            when (mode) {
                BlockMode.FULL -> appPolicy(ConditionType.ALWAYS, "")

                BlockMode.SCHEDULE -> appPolicy(
                    ConditionType.CURFEW,
                    Conditions.encode(ConditionParams(startMinute = start, endMinute = end)),
                )

                BlockMode.TIME_LIMIT -> appPolicy(
                    ConditionType.DAILY_BUDGET,
                    Conditions.encode(ConditionParams(minutes = minutes)),
                )

                BlockMode.FEED_ONLY -> {
                    // Feed-only is the mode that was never really about the app.
                    // It becomes one policy per destination inside the service,
                    // which is the shape it always wanted: what closes is the
                    // reel, and what stays open is everything else in there.
                    val service = Catalogue.SERVICE_BY_PACKAGE[pkg]
                    val surfaces = service?.let { Catalogue.surfacesOfService(it) }.orEmpty()
                    surfaces.forEach { surface ->
                        db.execSQL(
                            "INSERT OR REPLACE INTO policy " +
                                "(`id`,`targetType`,`targetKey`,`conditionType`,`conditionParams`," +
                                "`response`,`escalateAtRisk`,`enabled`,`source`,`updatedAt`) " +
                                "VALUES (?,?,?,?,'',?,NULL,?,?,?)",
                            arrayOf<Any>(
                                "surface_${surface.id}",
                                TargetType.SURFACE.name, surface.id, ConditionType.ALWAYS.name,
                                close, enabled, PolicySource.USER.name, now,
                            ),
                        )
                    }
                }
            }
        }
    }
}
