package com.bastion.app

import com.bastion.app.data.db.Migrations
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Enforces the promise that Bastion is installed once and never reinstalled.
 *
 * The failure this guards against is quiet and unrecoverable: someone bumps the
 * database version to add a feature, forgets the migration, and every existing
 * install either crashes on launch or — if a destructive fallback ever crept in
 * — silently loses the covenant, the signature and the whole journey.
 *
 * This test fails at build time instead, on a laptop, where it costs nothing.
 */
class MigrationChainTest {

    private val schemaDir = File("schemas/com.bastion.app.data.db.BastionDatabase")

    private fun exportedVersions(): List<Int> =
        schemaDir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { it.nameWithoutExtension.toIntOrNull() }
            ?.sorted()
            .orEmpty()

    @Test
    fun `room exports a schema file for every version`() {
        assertTrue(
            "No exported schemas found. exportSchema must stay true and " +
                "room.schemaLocation must remain set, or migrations cannot be written.",
            schemaDir.isDirectory,
        )
        val versions = exportedVersions()
        assertTrue("no schema json files were exported", versions.isNotEmpty())
        assertEquals("schema versions must start at 1", 1, versions.first())
        assertEquals(
            "schema versions have a gap: $versions",
            (1..versions.last()).toList(),
            versions,
        )
    }

    @Test
    fun `every schema version bump has a migration to carry data across`() {
        val latest = exportedVersions().lastOrNull() ?: 1

        assertEquals(
            "Database is at version $latest but ${Migrations.ALL.size} migration(s) are " +
                "registered. Every bump needs exactly one migration — see Migrations.kt.",
            latest - 1,
            Migrations.ALL.size,
        )

        // The migrations must form an unbroken chain 1 -> 2 -> ... -> latest, so a
        // phone that skipped several releases still lands on the current schema.
        Migrations.ALL
            .sortedBy { it.startVersion }
            .forEachIndexed { index, migration ->
                assertEquals(
                    "migration chain is broken at index $index",
                    index + 1,
                    migration.startVersion,
                )
                assertEquals(
                    "migration ${migration.startVersion} must step exactly one version",
                    migration.startVersion + 1,
                    migration.endVersion,
                )
            }
    }

    /**
     * The bug the chain test cannot see.
     *
     * Bumping the version, adding a field to the entity and writing an
     * `ALTER TABLE` that misses one column produces a build that compiles, tests
     * green, and then throws `IllegalStateException: Migration didn't properly
     * handle` on launch — for every existing user, and only for them. A fresh
     * install works perfectly, so it survives every kind of testing except
     * updating a real phone.
     *
     * So the exported schemas are diffed against each other, and every column a
     * version added must appear in that version's migration SQL.
     */
    @Test
    fun `every added column appears in its migration`() {
        val migrationSource =
            File("src/main/java/com/bastion/app/data/db/Migrations.kt").readText()

        exportedVersions().zipWithNext { from, to ->
            val added = columnsByTable(to).flatMap { (table, columns) ->
                val before = columnsByTable(from)[table] ?: return@flatMap emptyList()
                (columns - before).map { table to it }
            }
            added.forEach { (table, column) ->
                // Matched against the whole file rather than the one migration:
                // parsing Kotlin here would be its own source of bugs, and a
                // column named in the wrong migration still fails the chain.
                assertTrue(
                    "v$from -> v$to adds $table.$column to the schema, but no migration " +
                        "mentions it. Every existing install would crash on launch.",
                    migrationSource.contains(column),
                )
            }
        }
    }

    /**
     * kotlinx rather than `org.json`, which on the JVM is Android's stub and
     * throws "not mocked" the moment it is touched.
     */
    private fun columnsByTable(version: Int): Map<String, Set<String>> {
        val root = Json.parseToJsonElement(File(schemaDir, "$version.json").readText())
        val entities = root.jsonObject.getValue("database").jsonObject
            .getValue("entities").jsonArray
        return entities.associate { entity ->
            val obj = entity.jsonObject
            obj.getValue("tableName").jsonPrimitive.content to
                obj.getValue("fields").jsonArray
                    .map { it.jsonObject.getValue("columnName").jsonPrimitive.content }
                    .toSet()
        }
    }

    @Test
    fun `destructive migration is never enabled`() {
        // A wipe would undo the exact thing the app exists to build, so this is
        // asserted against the source rather than trusted to code review.
        val source = File("src/main/java/com/bastion/app/data/db/BastionDatabase.kt").readText()
        val active = source.lineSequence()
            .map { it.trim() }
            .filterNot { it.startsWith("*") || it.startsWith("//") || it.startsWith("/*") }
            .joinToString("\n")

        assertTrue(
            "fallbackToDestructiveMigration would silently erase a user's journey",
            !active.contains("fallbackToDestructiveMigration"),
        )
    }
}
