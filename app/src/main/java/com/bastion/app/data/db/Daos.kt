package com.bastion.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface JourneyDao {

    @Upsert
    suspend fun upsertDay(day: DayLogEntity)

    @Query("SELECT * FROM day_log WHERE epochDay = :epochDay")
    suspend fun day(epochDay: Long): DayLogEntity?

    @Query("SELECT * FROM day_log ORDER BY epochDay DESC")
    fun allDays(): Flow<List<DayLogEntity>>

    @Query("SELECT * FROM day_log WHERE epochDay >= :from ORDER BY epochDay ASC")
    fun daysSince(from: Long): Flow<List<DayLogEntity>>

    @Query("SELECT COUNT(*) FROM day_log WHERE status = 'CLEAN'")
    fun totalCleanDays(): Flow<Int>

    @Query("SELECT MAX(epochDay) FROM day_log WHERE status = 'SLIP'")
    suspend fun lastSlipDay(): Long?

    @Query("SELECT * FROM day_log WHERE status = 'SLIP' ORDER BY epochDay DESC")
    fun slipDays(): Flow<List<DayLogEntity>>

    @Upsert
    suspend fun upsertUrge(urge: UrgeLogEntity)

    /**
     * One transaction, because these two writes are one event.
     *
     * Written separately, a crash between them left the urge recorded but the
     * slip missing — the streak would silently keep running over a relapse the
     * user had already admitted to.
     */
    @Transaction
    suspend fun upsertUrgeAndDay(urge: UrgeLogEntity, day: DayLogEntity?) {
        upsertUrge(urge)
        if (day != null) upsertDay(day)
    }

    @Query("SELECT * FROM urge_log ORDER BY timestamp DESC LIMIT :limit")
    fun recentUrges(limit: Int = 200): Flow<List<UrgeLogEntity>>

    @Query("SELECT * FROM urge_log WHERE timestamp >= :since ORDER BY timestamp DESC")
    suspend fun urgesSince(since: Long): List<UrgeLogEntity>

    @Query("SELECT COUNT(*) FROM urge_log WHERE resisted = 1")
    fun resistedCount(): Flow<Int>
}

@Dao
interface HabitDao {

    @Query("SELECT * FROM habit WHERE active = 1 ORDER BY sortOrder ASC")
    fun activeHabits(): Flow<List<HabitEntity>>

    @Query("SELECT * FROM habit ORDER BY sortOrder ASC")
    fun allHabits(): Flow<List<HabitEntity>>

    @Upsert
    suspend fun upsert(habit: HabitEntity)

    @Upsert
    suspend fun upsertAll(habits: List<HabitEntity>)

    @Delete
    suspend fun delete(habit: HabitEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun complete(completion: HabitCompletionEntity)

    @Query("DELETE FROM habit_completion WHERE habitId = :habitId AND epochDay = :epochDay")
    suspend fun uncomplete(habitId: String, epochDay: Long)

    @Query("SELECT * FROM habit_completion WHERE epochDay = :epochDay")
    fun completionsOn(epochDay: Long): Flow<List<HabitCompletionEntity>>

    @Query("SELECT * FROM habit_completion WHERE epochDay >= :from")
    fun completionsSince(from: Long): Flow<List<HabitCompletionEntity>>

    @Query("SELECT COUNT(*) FROM habit_completion")
    fun totalCompletions(): Flow<Int>
}

@Dao
interface ProgressDao {

    @Upsert
    suspend fun upsertChallenge(progress: ChallengeProgressEntity)

    @Query("SELECT * FROM challenge_progress WHERE active = 1")
    fun activeChallenges(): Flow<List<ChallengeProgressEntity>>

    @Query("SELECT * FROM challenge_progress")
    fun allChallenges(): Flow<List<ChallengeProgressEntity>>

    @Query("SELECT * FROM challenge_progress WHERE challengeId = :id")
    suspend fun challenge(id: String): ChallengeProgressEntity?

    @Upsert
    suspend fun awardBadge(badge: BadgeEntity)

    /** Finishing a challenge and earning its badge are one event, so one write. */
    @Transaction
    suspend fun completeChallenge(progress: ChallengeProgressEntity, badge: BadgeEntity?) {
        upsertChallenge(progress)
        if (badge != null) awardBadge(badge)
    }

    @Query("SELECT * FROM badge ORDER BY earnedAt DESC")
    fun badges(): Flow<List<BadgeEntity>>

    @Query("SELECT COUNT(*) FROM badge")
    fun badgeCount(): Flow<Int>

    @Upsert
    suspend fun markLessonRead(lesson: LessonReadEntity)

    @Query("SELECT * FROM lesson_read")
    fun lessonsRead(): Flow<List<LessonReadEntity>>

    @Upsert
    suspend fun upsertCheckIn(checkIn: CheckInEntity)

    @Query("SELECT * FROM check_in ORDER BY epochDay DESC LIMIT :limit")
    fun recentCheckIns(limit: Int = 60): Flow<List<CheckInEntity>>

    @Query("SELECT COUNT(*) FROM check_in")
    fun checkInCount(): Flow<Int>
}

@Dao
interface CovenantDao {

    @Upsert
    suspend fun upsert(covenant: CovenantEntity)

    @Query("SELECT * FROM covenant WHERE id = 1")
    fun covenant(): Flow<CovenantEntity?>

    @Query("SELECT * FROM covenant WHERE id = 1")
    suspend fun covenantOnce(): CovenantEntity?

    @Upsert
    suspend fun upsertVision(item: VisionItemEntity)

    @Query("SELECT * FROM vision_item ORDER BY sortOrder ASC, createdAt DESC")
    fun visionItems(): Flow<List<VisionItemEntity>>

    @Query("DELETE FROM vision_item WHERE id = :id")
    suspend fun deleteVision(id: String)
}

@Dao
interface GuardDao {

    @Query("SELECT * FROM guarded_app ORDER BY label ASC")
    fun guardedApps(): Flow<List<GuardedAppEntity>>

    @Query("SELECT * FROM guarded_app WHERE enabled = 1")
    suspend fun enabledGuardedApps(): List<GuardedAppEntity>

    @Upsert
    suspend fun upsertApp(app: GuardedAppEntity)

    @Query("DELETE FROM guarded_app WHERE packageName = :packageName")
    suspend fun removeApp(packageName: String)

    @Query("SELECT * FROM feed_rule ORDER BY packageName ASC, label ASC")
    fun feedRules(): Flow<List<FeedRuleEntity>>

    @Query("SELECT * FROM feed_rule WHERE enabled = 1")
    suspend fun enabledFeedRules(): List<FeedRuleEntity>

    @Upsert
    suspend fun upsertRule(rule: FeedRuleEntity)

    @Upsert
    suspend fun upsertRules(rules: List<FeedRuleEntity>)

    @Query("DELETE FROM feed_rule WHERE id = :id")
    suspend fun deleteRule(id: String)

    @Query("SELECT COUNT(*) FROM feed_rule")
    suspend fun feedRuleCount(): Int

    @Upsert
    suspend fun upsertDomains(domains: List<BlockedDomainEntity>)

    @Upsert
    suspend fun upsertDomain(domain: BlockedDomainEntity)

    @Query("DELETE FROM blocked_domain WHERE domain = :domain")
    suspend fun removeDomain(domain: String)

    @Query("SELECT * FROM blocked_domain WHERE enabled = 1")
    suspend fun enabledDomains(): List<BlockedDomainEntity>

    @Query("SELECT * FROM blocked_domain WHERE userAdded = 1 ORDER BY domain ASC")
    fun userDomains(): Flow<List<BlockedDomainEntity>>

    @Query("SELECT COUNT(*) FROM blocked_domain")
    suspend fun domainCount(): Int

    @Upsert
    suspend fun upsertAllowed(domains: List<AllowedDomainEntity>)

    @Query("SELECT * FROM allowed_domain")
    suspend fun allowedDomains(): List<AllowedDomainEntity>

    @Upsert
    suspend fun upsertUsage(usage: AppUsageEntity)

    @Query("SELECT * FROM app_usage WHERE packageName = :packageName AND epochDay = :epochDay")
    suspend fun usage(packageName: String, epochDay: Long): AppUsageEntity?

    @Upsert
    suspend fun upsertChangeRequest(request: GuardChangeRequestEntity)

    @Query("SELECT * FROM guard_change_request WHERE status = 'PENDING' ORDER BY effectiveAt ASC")
    fun pendingChanges(): Flow<List<GuardChangeRequestEntity>>

    @Query("SELECT * FROM guard_change_request WHERE status = 'PENDING' AND effectiveAt <= :now")
    suspend fun maturedChanges(now: Long): List<GuardChangeRequestEntity>

    @Query("UPDATE guard_change_request SET status = :status WHERE id = :id")
    suspend fun setChangeStatus(id: String, status: ChangeStatus)
}

@Dao
interface SocialDao {

    @Upsert
    suspend fun upsertPartner(partner: PartnerEntity)

    @Query("SELECT * FROM partner LIMIT 1")
    fun partner(): Flow<PartnerEntity?>

    @Query("SELECT * FROM partner LIMIT 1")
    suspend fun partnerOnce(): PartnerEntity?

    @Query("DELETE FROM partner WHERE id = :id")
    suspend fun removePartner(id: String)

    @Upsert
    suspend fun addMessage(message: MentorMessageEntity)

    @Query("SELECT * FROM mentor_message ORDER BY timestamp ASC LIMIT :limit")
    fun mentorHistory(limit: Int = 300): Flow<List<MentorMessageEntity>>

    @Query("DELETE FROM mentor_message")
    suspend fun clearMentorHistory()
}

@Dao
interface FeedDao {

    @Query("SELECT itemId FROM feed_seen")
    suspend fun seenIds(): List<String>

    @Query("SELECT * FROM feed_seen WHERE epochDay = :epochDay ORDER BY seenAt")
    suspend fun seenOn(epochDay: Long): List<FeedSeenEntity>

    @Query("SELECT COUNT(*) FROM feed_seen WHERE epochDay = :epochDay")
    fun countOnFlow(epochDay: Long): kotlinx.coroutines.flow.Flow<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markSeen(rows: List<FeedSeenEntity>)

    /**
     * Lets the well refill once everything has been drawn from it.
     *
     * Without this a long-running user eventually sees "caught up" forever,
     * which turns a gift into a dead end. Clearing the record is not the same
     * as an infinite feed: the day's portion is still finite, the cards are
     * simply allowed to come round again.
     */
    @Query("DELETE FROM feed_seen")
    suspend fun clearSeen()
}
