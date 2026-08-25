package com.bastion.app.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes for Protection Model v2.
 *
 * Separate from [GuardDao] rather than bolted onto it, because for one release
 * both models exist side by side: the v1 tables are still written so a rollback
 * has data to land on, and keeping the two sets of queries apart is what makes
 * it obvious which model a piece of code is talking to.
 */
@Dao
interface PolicyDao {

    // --- Categories ------------------------------------------------------

    @Query("SELECT * FROM protection_category ORDER BY key ASC")
    fun categories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM protection_category")
    suspend fun allCategories(): List<CategoryEntity>

    @Upsert
    suspend fun upsertCategories(rows: List<CategoryEntity>)

    @Upsert
    suspend fun upsertCategory(row: CategoryEntity)

    // --- Surfaces --------------------------------------------------------

    @Query("SELECT * FROM surface ORDER BY serviceKey ASC, label ASC")
    fun surfaces(): Flow<List<SurfaceEntity>>

    @Query("SELECT * FROM surface")
    suspend fun allSurfaces(): List<SurfaceEntity>

    @Upsert
    suspend fun upsertSurfaces(rows: List<SurfaceEntity>)

    @Upsert
    suspend fun upsertSurface(row: SurfaceEntity)

    @Query("DELETE FROM surface WHERE id = :id")
    suspend fun deleteSurface(id: String)

    // --- Signals ---------------------------------------------------------

    @Query("SELECT * FROM surface_signal ORDER BY surfaceId ASC")
    fun signals(): Flow<List<SignalEntity>>

    @Query("SELECT * FROM surface_signal")
    suspend fun allSignals(): List<SignalEntity>

    @Query("SELECT * FROM surface_signal WHERE enabled = 1")
    suspend fun enabledSignals(): List<SignalEntity>

    @Upsert
    suspend fun upsertSignals(rows: List<SignalEntity>)

    @Upsert
    suspend fun upsertSignal(row: SignalEntity)

    @Query("DELETE FROM surface_signal WHERE id = :id")
    suspend fun deleteSignal(id: String)

    /**
     * Stamped when a signal fires, and never read on the hot path.
     *
     * This is the whole of drift detection's write cost: one indexed update on
     * a block, which already happens at human speed rather than at scroll speed.
     */
    @Query("UPDATE surface_signal SET lastMatchedAt = :ts WHERE id = :id")
    suspend fun markMatched(id: String, ts: Long)

    // --- Policies --------------------------------------------------------

    @Query("SELECT * FROM policy ORDER BY targetType ASC, targetKey ASC")
    fun policies(): Flow<List<PolicyEntity>>

    @Query("SELECT * FROM policy")
    suspend fun allPolicies(): List<PolicyEntity>

    @Query("SELECT * FROM policy WHERE enabled = 1")
    suspend fun enabledPolicies(): List<PolicyEntity>

    @Query("SELECT * FROM policy WHERE targetType = :type AND targetKey = :key")
    suspend fun policiesFor(type: TargetType, key: String): List<PolicyEntity>

    @Upsert
    suspend fun upsertPolicies(rows: List<PolicyEntity>)

    @Upsert
    suspend fun upsertPolicy(row: PolicyEntity)

    @Query("DELETE FROM policy WHERE id = :id")
    suspend fun deletePolicy(id: String)

    @Query("DELETE FROM policy WHERE targetType = :type AND targetKey = :key")
    suspend fun deletePoliciesFor(type: TargetType, key: String)

    // --- Events ----------------------------------------------------------

    @Upsert
    suspend fun record(event: PolicyEventEntity)

    @Query("SELECT * FROM policy_event ORDER BY ts DESC LIMIT :limit")
    fun recent(limit: Int = 100): Flow<List<PolicyEventEntity>>

    @Query("SELECT * FROM policy_event ORDER BY ts DESC LIMIT 1")
    suspend fun latest(): PolicyEventEntity?

    /**
     * Kept short on purpose.
     *
     * This table exists to explain the last thing that happened and to show a
     * week of Watch, neither of which needs a year of history. A log that grows
     * without limit is a log that eventually becomes the reason someone clears
     * app data, and clearing app data here costs a man his covenant.
     */
    @Query("DELETE FROM policy_event WHERE ts < :cutoff")
    suspend fun prune(cutoff: Long)
}
