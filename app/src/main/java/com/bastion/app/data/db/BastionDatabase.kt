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
    ],
    version = 5,
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
