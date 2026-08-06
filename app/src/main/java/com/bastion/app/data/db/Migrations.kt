package com.bastion.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Every schema change Bastion has ever made, in order.
 *
 * This file is what makes "install over the top, forever" true. The data behind
 * it is not ordinary app state — it is a man's covenant, his signature, his
 * reasons, and every day he has counted. Losing it would undo the thing the app
 * exists to build, so the rules here are absolute:
 *
 *   1. NEVER add `fallbackToDestructiveMigration()`. It converts "I forgot a
 *      migration" from a loud crash into a silently erased journey. A crash is
 *      recoverable; a wipe is not.
 *   2. Bump `BastionDatabase.version` and add a Migration here in the same
 *      commit. Room's exported schema in app/schemas is the source of truth for
 *      what the previous version looked like.
 *   3. Migrations only ever ADD. Adding a table or a nullable column with a
 *      default is safe. Dropping or renaming a column loses data that cannot be
 *      reconstructed — prefer leaving a dead column in place forever.
 *
 * Adding a whole new table needs no data care at all, so new features are cheap.
 * Example, for when version 2 arrives:
 *
 *     val MIGRATION_1_2 = Migration(1, 2) { db ->
 *         db.execSQL("ALTER TABLE urge_log ADD COLUMN weather TEXT")
 *     }
 */
object Migrations {

    /**
     * Register each new migration here. Room applies them in sequence, so a
     * phone that skipped several versions still lands correctly on the newest
     * schema without an uninstall.
     */
    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
    )
}

/**
 * v6 → v7. A schedule, and a day that can say more than "done".
 *
 * Everything was daily before, which made the app lie twice about a habit meant
 * for three times a week: it showed up every morning as an outstanding failure,
 * and its streak broke four times a week on days it was never due.
 *
 * Defaults are chosen so no existing habit changes behaviour:
 *
 *  - `scheduleType` DAILY is what every habit already was.
 *  - `weekdaysCsv` blank, `everyNDays` 2, `timesPerWeek` 3 are inert while the
 *    type is DAILY; they are the sensible starting points if he changes it.
 *  - `startEpochDay` 0 means "scheduled since the epoch", so no habit suddenly
 *    has days that do not count. New habits stamp the real day at adoption —
 *    backfilling one here would be a guess, and guessing it late would move
 *    existing streaks.
 *  - `status` DONE is the only thing a completion row has ever meant.
 *
 * So every streak, every domain score and the whole Becoming profile read
 * identically across the upgrade.
 */
private val MIGRATION_6_7 = Migration(6, 7) { db ->
    db.execSQL("ALTER TABLE habit ADD COLUMN scheduleType TEXT NOT NULL DEFAULT 'DAILY'")
    db.execSQL("ALTER TABLE habit ADD COLUMN weekdaysCsv TEXT NOT NULL DEFAULT ''")
    db.execSQL("ALTER TABLE habit ADD COLUMN everyNDays INTEGER NOT NULL DEFAULT 2")
    db.execSQL("ALTER TABLE habit ADD COLUMN timesPerWeek INTEGER NOT NULL DEFAULT 3")
    db.execSQL("ALTER TABLE habit ADD COLUMN startEpochDay INTEGER NOT NULL DEFAULT 0")
    db.execSQL("ALTER TABLE habit ADD COLUMN endEpochDay INTEGER")
    db.execSQL("ALTER TABLE habit_completion ADD COLUMN status TEXT NOT NULL DEFAULT 'DONE'")
}

/**
 * v5 → v6. The journal: an hour, a target, and a count.
 *
 * Every column carries a default that means exactly what the old schema already
 * meant, so nothing is reinterpreted and no habit changes behaviour on upgrade.
 *
 *  - `timeOfDay` defaults to ANYTIME, which is what a habit with no hour has
 *    always been. Nobody's regimen silently reshuffles into Morning.
 *  - `targetCount` defaults to 1 and `unit` to blank: a tick, which is the only
 *    thing a habit could be before this.
 *  - `count` on a completion defaults to 1. A row already meant "done once".
 *    This says the same thing out loud, so every streak, every domain score and
 *    the whole four-week Becoming profile read identically across the upgrade.
 *
 * The one thing deliberately *not* done here is backfilling a sensible hour by
 * guessing from the habit's name. It would be wrong often enough to matter, and
 * a man opening the new journal to find his habits sorted into hours he never
 * chose would trust the next thing the app did rather less.
 */
private val MIGRATION_5_6 = Migration(5, 6) { db ->
    db.execSQL("ALTER TABLE habit ADD COLUMN timeOfDay TEXT NOT NULL DEFAULT 'ANYTIME'")
    db.execSQL("ALTER TABLE habit ADD COLUMN targetCount INTEGER NOT NULL DEFAULT 1")
    db.execSQL("ALTER TABLE habit ADD COLUMN unit TEXT NOT NULL DEFAULT ''")
    db.execSQL("ALTER TABLE habit_completion ADD COLUMN count INTEGER NOT NULL DEFAULT 1")
}

/**
 * v4 → v5. Five more things a log can say.
 *
 * Every column is nullable with no default, so every urge already recorded stays
 * exactly as it was and simply has nothing to say about where he was or what he
 * was feeling — which is the truth, since it was never asked. No existing value
 * is read, rewritten or reinterpreted.
 *
 * `place` and `mood` are not here: both have existed since v1 and were never
 * collected by any screen. They are wired up now rather than added.
 */
private val MIGRATION_4_5 = Migration(4, 5) { db ->
    db.execSQL("ALTER TABLE urge_log ADD COLUMN feelings TEXT")
    db.execSQL("ALTER TABLE urge_log ADD COLUMN device TEXT")
    db.execSQL("ALTER TABLE urge_log ADD COLUMN soughtOut INTEGER")
    db.execSQL("ALTER TABLE urge_log ADD COLUMN durationMinutes INTEGER")
    db.execSQL("ALTER TABLE urge_log ADD COLUMN whatHelped TEXT")
}

/**
 * v3 → v4. Adds the table that lets the feed be finite.
 *
 * Purely additive: a new table, nothing touched. Someone who has never opened
 * the feed simply has an empty one, and every day counted before this update
 * survives untouched — which is the only property that actually matters here.
 */
private val MIGRATION_3_4 = Migration(3, 4) { db ->
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS feed_seen (
            itemId TEXT NOT NULL PRIMARY KEY,
            epochDay INTEGER NOT NULL,
            seenAt INTEGER NOT NULL
        )
        """.trimIndent()
    )
}

/**
 * v2 -> v3. Deletes the built-in rules that matched a feed *tab* rather than the
 * feed itself.
 *
 * Those rules keyed on the content description of the Reels and Shorts buttons,
 * which live in the bottom navigation bar and are therefore on screen the whole
 * time an app is open. Feed-only silently became a total block: Instagram could
 * not be opened at all. Shipping a corrected seed fixes new installs; existing
 * ones already hold the bad rows, so they have to be removed here.
 *
 * Scoped to builtIn = 1 so a rule the user captured with Learn Mode is never
 * touched, even if it happens to match on a description.
 */
private val MIGRATION_2_3 = Migration(2, 3) { db ->
    db.execSQL("DELETE FROM feed_rule WHERE builtIn = 1 AND matchType = 'CONTENT_DESC'")
}

/**
 * v1 → v2. Adds `updatedAt` to the five tables that were missing it, and the
 * indices the hot paths were scanning without.
 *
 * Additive only: not a single existing row is rewritten, so a covenant signed
 * on v1 survives exactly as it was. New columns default to 0 rather than the
 * current time, which is honest — we do not know when those rows were written,
 * and inventing a timestamp would corrupt any future sync reconciliation.
 */
private val MIGRATION_1_2 = Migration(1, 2) { db ->
    listOf("blocked_domain", "allowed_domain", "badge", "lesson_read", "app_usage").forEach { table ->
        db.execSQL("ALTER TABLE $table ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
    }

    // Names must match Room's generated convention exactly, or validation fails
    // on the next launch with a schema mismatch.
    db.execSQL("CREATE INDEX IF NOT EXISTS index_day_log_status ON day_log(status)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_habit_completion_epochDay ON habit_completion(epochDay)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_blocked_domain_enabled ON blocked_domain(enabled)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_check_in_epochDay ON check_in(epochDay)")
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS index_guard_change_request_status_effectiveAt " +
            "ON guard_change_request(status, effectiveAt)"
    )
}
