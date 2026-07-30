package com.bastion.app.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/**
 * Whole-table reads and writes, used only by export and import.
 *
 * Kept apart from the feature DAOs on purpose: those expose observable queries
 * shaped for one screen, whereas a backup wants everything, once, in bulk.
 * Mixing the two would tempt a screen into loading an entire table.
 */
@Dao
interface BackupDao {

    @Query("SELECT * FROM day_log") suspend fun days(): List<DayLogEntity>
    @Query("SELECT * FROM urge_log") suspend fun urges(): List<UrgeLogEntity>
    @Query("SELECT * FROM habit") suspend fun habits(): List<HabitEntity>
    @Query("SELECT * FROM habit_completion") suspend fun completions(): List<HabitCompletionEntity>
    @Query("SELECT * FROM challenge_progress") suspend fun challenges(): List<ChallengeProgressEntity>
    @Query("SELECT * FROM badge") suspend fun badges(): List<BadgeEntity>
    @Query("SELECT * FROM check_in") suspend fun checkIns(): List<CheckInEntity>
    @Query("SELECT * FROM vision_item") suspend fun visionItems(): List<VisionItemEntity>
    @Query("SELECT * FROM lesson_read") suspend fun lessonsRead(): List<LessonReadEntity>
    @Query("SELECT * FROM guarded_app") suspend fun guardedApps(): List<GuardedAppEntity>
    @Query("SELECT * FROM feed_rule WHERE builtIn = 0") suspend fun learnedRules(): List<FeedRuleEntity>
    @Query("SELECT * FROM blocked_domain WHERE userAdded = 1") suspend fun userDomains(): List<BlockedDomainEntity>

    /**
     * Restores in one transaction, so a failure part-way cannot leave a half
     * journey behind — worse than no restore at all.
     *
     * Upserts rather than clearing first: importing onto a phone that already
     * has history merges the two instead of destroying whichever was there. Rows
     * carry stable ids, so re-importing the same backup is idempotent.
     */
    @Transaction
    suspend fun restore(
        days: List<DayLogEntity>,
        urges: List<UrgeLogEntity>,
        habits: List<HabitEntity>,
        completions: List<HabitCompletionEntity>,
        challenges: List<ChallengeProgressEntity>,
        badges: List<BadgeEntity>,
        checkIns: List<CheckInEntity>,
        visionItems: List<VisionItemEntity>,
        lessonsRead: List<LessonReadEntity>,
        guardedApps: List<GuardedAppEntity>,
        learnedRules: List<FeedRuleEntity>,
        userDomains: List<BlockedDomainEntity>,
        covenant: CovenantEntity?,
        partner: PartnerEntity?,
    ) {
        putDays(days)
        putUrges(urges)
        putHabits(habits)
        putCompletions(completions)
        putChallenges(challenges)
        putBadges(badges)
        putCheckIns(checkIns)
        putVisionItems(visionItems)
        putLessonsRead(lessonsRead)
        putGuardedApps(guardedApps)
        putRules(learnedRules)
        putDomains(userDomains)
        covenant?.let { putCovenant(it) }
        partner?.let { putPartner(it) }
    }

    @Upsert suspend fun putDays(rows: List<DayLogEntity>)
    @Upsert suspend fun putUrges(rows: List<UrgeLogEntity>)
    @Upsert suspend fun putHabits(rows: List<HabitEntity>)
    @Upsert suspend fun putCompletions(rows: List<HabitCompletionEntity>)
    @Upsert suspend fun putChallenges(rows: List<ChallengeProgressEntity>)
    @Upsert suspend fun putBadges(rows: List<BadgeEntity>)
    @Upsert suspend fun putCheckIns(rows: List<CheckInEntity>)
    @Upsert suspend fun putVisionItems(rows: List<VisionItemEntity>)
    @Upsert suspend fun putLessonsRead(rows: List<LessonReadEntity>)
    @Upsert suspend fun putGuardedApps(rows: List<GuardedAppEntity>)
    @Upsert suspend fun putRules(rows: List<FeedRuleEntity>)
    @Upsert suspend fun putDomains(rows: List<BlockedDomainEntity>)
    @Upsert suspend fun putCovenant(row: CovenantEntity)
    @Upsert suspend fun putPartner(row: PartnerEntity)
}
