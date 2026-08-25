package com.bastion.app

import com.bastion.app.data.db.Migrations
import com.bastion.app.guard.policy.Catalogue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The 7 → 8 migration, checked on a laptop.
 *
 * A migration is the one piece of this app that cannot be tried again. It runs
 * once, on a phone that already holds a man's covenant and every day he has
 * counted, and if the table it builds differs from the one Room expects by a
 * single column, Room throws on launch — and this app has no destructive
 * fallback, by design, so that is a phone with a blocker that will not start.
 *
 * There is no emulator here to run the real thing against, so this pins the two
 * halves that can be checked without one: that the hand-written DDL is
 * byte-for-byte what Room's exported schema says it should be, and that the
 * old tables are still standing afterwards.
 */
class PolicyMigrationTest {

    private val schema = File("schemas/com.bastion.app.data.db.BastionDatabase/8.json")
    private val migration =
        File("src/main/java/com/bastion/app/data/db/Migration7to8.kt").readText()

    private val newTables =
        setOf("protection_category", "surface", "surface_signal", "policy", "policy_event")

    /**
     * Collapse whitespace, including either side of a bracket.
     *
     * SQL does not care, and the migration wraps its DDL across several lines
     * for the same reason any long statement is wrapped. What this must still
     * catch is a column that is missing, renamed, re-typed, or that changed
     * nullability — and none of those survive this normalisation.
     */
    private fun normalise(sql: String) = sql
        .replace(Regex("\\s+"), " ")
        .replace("( ", "(")
        .replace(" )", ")")
        .trim()
        .trimEnd(';')

    private fun entities() = Json.parseToJsonElement(schema.readText())
        .jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray
        .map { it.jsonObject }

    /**
     * The failure this exists for: someone adds a column to a v2 entity, Room
     * updates its expected schema, and the migration keeps creating the old
     * shape. Everything compiles. Every test but this one passes. The upgrade
     * fails on the phone.
     */
    /**
     * The migration source, as the SQL it will actually run.
     *
     * Long statements are split across Kotlin string concatenation, so the
     * source text carries `" + "` in the middle of statements that SQLite will
     * never see. Rejoining them is what lets this compare literals rather than
     * formatting.
     */
    private fun executedSql() = normalise(migration).replace("\" + \"", "")

    @Test
    fun `the migration creates exactly the tables room expects`() {
        val flat = executedSql()
        var checked = 0

        for (entity in entities()) {
            val table = entity["tableName"]!!.jsonPrimitive.content
            if (table !in newTables) continue
            checked++

            val expected = normalise(entity["createSql"]!!.jsonPrimitive.content)
                .replace("\${TABLE_NAME}", table)
            assertTrue(
                "Migration 7->8 does not create `$table` the way Room expects.\n" +
                    "Room wants:\n  $expected",
                flat.contains(expected),
            )

            entity["indices"]?.jsonArray.orEmpty().forEach { index ->
                val idx = normalise(index.jsonObject["createSql"]!!.jsonPrimitive.content)
                    .replace("\${TABLE_NAME}", table)
                assertTrue(
                    "Migration 7->8 is missing an index on `$table`.\nRoom wants:\n  $idx",
                    flat.contains(idx),
                )
            }
        }

        assertEquals("all five v2 tables must be checked", newTables.size, checked)
    }

    /**
     * Nothing is dropped in this migration, and that is deliberate.
     *
     * For one release the v1 tables are dead weight that still round-trips, so a
     * rollback is a downgrade rather than a loss. They come out in 8 → 9, after
     * a release has shipped clean.
     */
    @Test
    fun `the old tables are still standing after the upgrade`() {
        val tables = entities().map { it["tableName"]!!.jsonPrimitive.content }
        assertTrue("feed_rule must survive 7->8 for rollback", "feed_rule" in tables)
        assertTrue("guarded_app must survive 7->8 for rollback", "guarded_app" in tables)

        val flat = normalise(migration).lowercase()
        assertTrue("7->8 must not drop feed_rule", !flat.contains("drop table `feed_rule`"))
        assertTrue("7->8 must not drop guarded_app", !flat.contains("drop table `guarded_app`"))
        assertTrue("7->8 must not delete from feed_rule", !flat.contains("delete from `feed_rule`"))
        assertTrue(
            "7->8 must not delete from guarded_app",
            !flat.contains("delete from `guarded_app`"),
        )
    }

    /** Version 8 needs a migration like every version before it. */
    @Test
    fun `the chain reaches eight`() {
        assertEquals(7, Migrations.ALL.size)
    }

    /**
     * Signal ids are the old `feed_rule` ids, and that is what carries a man's
     * own on/off decisions across.
     *
     * If this drifts, the migration silently stops matching his rows: every
     * switch he had turned off comes back on, which is the kind of "helpful"
     * reversal the whole lock-in design exists to prevent.
     */
    @Test
    fun `catalogue signal ids still follow the feed rule scheme`() {
        val signals = Catalogue.signals()
        assertTrue("catalogue must not be empty", signals.isNotEmpty())
        signals.forEach { signal ->
            assertTrue(
                "signal ${signal.id} no longer looks like a feed_rule id",
                signal.id.startsWith("builtin_"),
            )
            assertTrue(
                "signal ${signal.id} must end in the value it matches on",
                signal.id.endsWith(signal.matchValue) || signal.id.length == 120,
            )
        }
        assertEquals(
            "ids must be unique or the migration will overwrite one with another",
            signals.size,
            signals.map { it.id }.toSet().size,
        )
    }

    /**
     * A restore has to rebuild what the guard actually reads.
     *
     * The backup file holds v1 rows and the restore writes them straight
     * through, which is right — that is what the file contains. But the guard
     * reads policies now, so without a rebuild a man restores his backup, sees
     * every app listed exactly as he left it, and is protected by none of them.
     *
     * That is the worst failure this app can have. Not a block that fires
     * wrongly — a screen that tells him he is covered when he is not. Pinned
     * here for the same reason NoUngatedOffSwitchTest exists: it reads source,
     * which is crude, and needs nobody to remember to think about it.
     */
    @Test
    fun `restoring a backup rebuilds the policies`() {
        val restore = File("src/main/java/com/bastion/app/data/repo/BackupRepository.kt").readText()
        assertTrue(
            "import() must rebuild policies from the rows it restored, or a restored " +
                "phone shows its guards and enforces none of them",
            restore.contains("policy.restoreFrom("),
        )
        val body = restore.substringAfter("suspend fun import(")
        val rebuild = body.indexOf("policy.restoreFrom(")
        val guard = body.lastIndexOf("if (!locked)", rebuild)
        assertTrue(
            "the rebuild must stay inside the locked-in check: while locked in the " +
                "guards are deliberately not restored, and rebuilding anyway would " +
                "restore them by the back door",
            guard in 0 until rebuild,
        )
    }

    /** Every signal must hang off a surface that actually exists. */
    @Test
    fun `every signal belongs to a real surface in a real category`() {
        val surfaces = Catalogue.surfaces().associateBy { it.id }
        val categories = Catalogue.categories().associateBy { it.key }

        Catalogue.signals().forEach { signal ->
            assertTrue(
                "signal ${signal.id} points at unknown surface ${signal.surfaceId}",
                surfaces.containsKey(signal.surfaceId),
            )
        }
        surfaces.values.forEach { surface ->
            assertTrue(
                "surface ${surface.id} is in unknown category ${surface.categoryKey}",
                categories.containsKey(surface.categoryKey),
            )
            assertTrue(
                "surface ${surface.id} names a service with no packages",
                Catalogue.PACKAGES_BY_SERVICE.containsKey(surface.serviceKey),
            )
        }
    }
}
