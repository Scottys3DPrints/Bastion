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
        // v1 is the baseline shipped in Bastion 1.0. Nothing to migrate yet.
    )
}
