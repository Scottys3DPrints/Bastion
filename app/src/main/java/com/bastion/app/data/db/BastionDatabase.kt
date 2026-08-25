package com.bastion.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

/**
 * Enums are stored as their names rather than ordinals so that reordering an
 * enum later can never silently reinterpret existing rows — the queries in
 * [JourneyDao] and [GuardDao] match on those names directly.
 */
class BastionConverters {
    @TypeConverter fun dayStatusTo(value: DayStatus): String = value.name
    @TypeConverter fun dayStatusFrom(value: String): DayStatus = DayStatus.valueOf(value)

    @TypeConverter fun blockModeTo(value: BlockMode): String = value.name
    @TypeConverter fun blockModeFrom(value: String): BlockMode = BlockMode.valueOf(value)

    @TypeConverter fun matchTypeTo(value: MatchType): String = value.name
    @TypeConverter fun matchTypeFrom(value: String): MatchType = MatchType.valueOf(value)

    @TypeConverter fun visionTypeTo(value: VisionType): String = value.name
    @TypeConverter fun visionTypeFrom(value: String): VisionType = VisionType.valueOf(value)

    @TypeConverter fun changeStatusTo(value: ChangeStatus): String = value.name
    @TypeConverter fun changeStatusFrom(value: String): ChangeStatus = ChangeStatus.valueOf(value)

    @TypeConverter fun timeOfDayTo(value: TimeOfDay): String = value.name
    @TypeConverter fun timeOfDayFrom(value: String): TimeOfDay =
        // Tolerant on the way in, strict on the way out. valueOf throws on
        // anything it does not recognise, and a single unreadable row would take
        // the whole habit list down rather than one habit — this is the journal's
        // spine, so it fails soft to the default that means "no particular hour".
        runCatching { TimeOfDay.valueOf(value) }.getOrDefault(TimeOfDay.ANYTIME)

    // Same tolerance, same reason: one unreadable row must not take the journal
    // down with it. The defaults are each the pre-schedule behaviour.
    @TypeConverter fun scheduleTypeTo(value: ScheduleType): String = value.name
    @TypeConverter fun scheduleTypeFrom(value: String): ScheduleType =
        runCatching { ScheduleType.valueOf(value) }.getOrDefault(ScheduleType.DAILY)

    @TypeConverter fun logStatusTo(value: LogStatus): String = value.name
    @TypeConverter fun logStatusFrom(value: String): LogStatus =
        runCatching { LogStatus.valueOf(value) }.getOrDefault(LogStatus.DONE)

    // Protection Model v2. Each reads tolerantly and fails to the safe side,
    // which for a guard means the *stricter* side: an unreadable target or
    // condition must never widen what a policy covers or make it always-live.
    @TypeConverter fun targetTypeTo(value: TargetType): String = value.name
    @TypeConverter fun targetTypeFrom(value: String): TargetType =
        runCatching { TargetType.valueOf(value) }.getOrDefault(TargetType.APP)

    @TypeConverter fun conditionTypeTo(value: ConditionType): String = value.name
    @TypeConverter fun conditionTypeFrom(value: String): ConditionType =
        runCatching { ConditionType.valueOf(value) }.getOrDefault(ConditionType.ALWAYS)

    @TypeConverter fun policySourceTo(value: PolicySource): String = value.name
    @TypeConverter fun policySourceFrom(value: String): PolicySource =
        runCatching { PolicySource.valueOf(value) }.getOrDefault(PolicySource.DEFAULT)

    @TypeConverter fun outcomeTo(value: Outcome): String = value.name
    @TypeConverter fun outcomeFrom(value: String): Outcome =
        runCatching { Outcome.valueOf(value) }.getOrDefault(Outcome.ARRIVED)
}

@Database(
    entities = [
        DayLogEntity::class,
        UrgeLogEntity::class,
        HabitEntity::class,
        HabitCompletionEntity::class,
        ChallengeProgressEntity::class,
        CovenantEntity::class,
        GuardedAppEntity::class,
        FeedRuleEntity::class,
        BlockedDomainEntity::class,
        AllowedDomainEntity::class,
        VisionItemEntity::class,
        BadgeEntity::class,
        PartnerEntity::class,
        CheckInEntity::class,
        MentorMessageEntity::class,
        LessonReadEntity::class,
        GuardChangeRequestEntity::class,
        AppUsageEntity::class,
        FeedSeenEntity::class,
        CategoryEntity::class,
        SurfaceEntity::class,
        SignalEntity::class,
        PolicyEntity::class,
        PolicyEventEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
@TypeConverters(BastionConverters::class)
abstract class BastionDatabase : RoomDatabase() {

    abstract fun journeyDao(): JourneyDao
    abstract fun habitDao(): HabitDao
    abstract fun progressDao(): ProgressDao
    abstract fun covenantDao(): CovenantDao
    abstract fun guardDao(): GuardDao
    abstract fun socialDao(): SocialDao
    abstract fun backupDao(): BackupDao
    abstract fun feedDao(): FeedDao
    abstract fun policyDao(): PolicyDao

    companion object {
        /**
         * Note what is deliberately absent: `fallbackToDestructiveMigration()`.
         *
         * Bastion is designed to be installed once and updated in place forever.
         * If a future version ever ships without its migration, this will throw
         * loudly on launch rather than quietly deleting the covenant, the
         * signature and every counted day. That is the correct trade — see
         * [Migrations] for the rules.
         */
        fun build(context: Context): BastionDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                BastionDatabase::class.java,
                "bastion.db",
            )
                .addMigrations(*Migrations.ALL)
                .build()
    }
}
