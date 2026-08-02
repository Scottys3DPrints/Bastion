package com.bastion.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/*
 * Local-first storage. Two conventions make a future sync backend a bolt-on
 * rather than a rewrite:
 *   1. user-generated rows use UUID string keys, so two devices (or a server)
 *      can merge without primary-key collisions;
 *   2. every syncable row carries updatedAt, so last-writer-wins reconciliation
 *      is possible without schema surgery.
 * Nothing here is transmitted anywhere today.
 */

enum class DayStatus { CLEAN, SLIP, UNLOGGED }

@Entity(tableName = "day_log", indices = [Index("status")])
@Serializable
data class DayLogEntity(
    @PrimaryKey val epochDay: Long,
    val status: DayStatus,
    val note: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "urge_log", indices = [Index("timestamp")])
@Serializable
data class UrgeLogEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val epochDay: Long,
    /** False means it became a slip. Both are logged the same calm way. */
    val resisted: Boolean,
    /** 1..5 */
    val intensity: Int,
    val mood: String?,
    val trigger: String?,
    /** Package name of the app in the foreground, when Guard could see it. */
    val contextApp: String?,
    val place: String?,
    val note: String?,
    /**
     * The emotional weather, comma-separated, because it is never one thing.
     *
     * [trigger] is the situation that set it off; this is what he was carrying
     * into it. Kept separate rather than folded together: "late night" and
     * "lonely" are different facts, and a man who can see that his hard nights
     * are the lonely ones rather than the late ones knows something worth
     * knowing.
     */
    val feelings: String? = null,
    /** Phone, laptop, tablet, TV. Where the pattern actually lives. */
    val device: String? = null,
    /**
     * Whether he went looking or it arrived.
     *
     * The single most useful thing to separate, and the one most apps refuse to
     * ask: stumbling into it on a feed and deciding to seek it out are different
     * problems with different answers. Null when he would rather not say.
     */
    val soughtOut: Boolean? = null,
    /** How long it lasted, in minutes, when he knows. */
    val durationMinutes: Int? = null,
    /** What worked, for a held urge — the thing worth doing again. */
    val whatHelped: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "habit")
@Serializable
data class HabitEntity(
    @PrimaryKey val id: String,
    val name: String,
    val domain: String,
    val emoji: String,
    val target: String,
    val why: String,
    val active: Boolean = true,
    val sortOrder: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
)

// The composite primary key leads with habitId, so a range scan over epochDay
// (which is what the Becoming profile does every time it opens) could not use it.
@Entity(
    tableName = "habit_completion",
    primaryKeys = ["habitId", "epochDay"],
    indices = [Index("epochDay")],
)
@Serializable
data class HabitCompletionEntity(
    val habitId: String,
    val epochDay: Long,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "challenge_progress")
@Serializable
data class ChallengeProgressEntity(
    @PrimaryKey val challengeId: String,
    val startedEpochDay: Long,
    val completedDaysCsv: String = "",
    val active: Boolean = true,
    val completedAt: Long? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Exactly one row, id = 1. The oath a man signed, and the reason he signed it. */
@Entity(tableName = "covenant")
@Serializable
data class CovenantEntity(
    @PrimaryKey val id: Int = 1,
    val oathText: String,
    val signaturePath: String?,
    val signedAt: Long,
    val whyText: String?,
    val whyMediaPath: String?,
    /** "video", "audio" or null */
    val whyMediaType: String?,
    val updatedAt: Long = System.currentTimeMillis(),
)

enum class BlockMode {
    /** App cannot be opened at all. */
    FULL,

    /** App blocked only inside the scheduled window. */
    SCHEDULE,

    /** App opens normally; only the short-form feed inside it is interrupted. */
    FEED_ONLY,

    /** App opens, but locks after timeLimitMinutes of use per day. */
    TIME_LIMIT,
}

@Entity(tableName = "guarded_app")
@Serializable
data class GuardedAppEntity(
    @PrimaryKey val packageName: String,
    val label: String,
    val mode: BlockMode,
    /** Minutes from midnight. */
    val scheduleStart: Int = 22 * 60,
    val scheduleEnd: Int = 6 * 60,
    val timeLimitMinutes: Int = 10,
    val grayscale: Boolean = false,
    val enabled: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis(),
)

enum class MatchType { VIEW_ID, TEXT, CONTENT_DESC }

/**
 * A rule that identifies one specific screen inside another app — the Reels
 * viewer, the Shorts player, the For You feed.
 *
 * These live in the database rather than in code on purpose: when Instagram
 * reshuffles its view ids, the fix is editing a row (or capturing a new one with
 * Learn Mode), not shipping a new APK.
 */
@Entity(tableName = "feed_rule", indices = [Index("packageName")])
@Serializable
data class FeedRuleEntity(
    @PrimaryKey val id: String,
    val packageName: String,
    val label: String,
    val matchType: MatchType,
    val matchValue: String,
    val enabled: Boolean = true,
    val builtIn: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis(),
)

// Indexed on `enabled`: this table is fully scanned by filterData() on the
// Guard/VPN hot path.
@Entity(tableName = "blocked_domain", indices = [Index("enabled")])
@Serializable
data class BlockedDomainEntity(
    @PrimaryKey val domain: String,
    val userAdded: Boolean = false,
    val enabled: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Explicit allow-list, so "sussex" and "essex" never get caught by a keyword rule. */
@Entity(tableName = "allowed_domain")
data class AllowedDomainEntity(
    @PrimaryKey val domain: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

enum class VisionType { PHOTO, QUOTE, GOAL, VERSE }

@Entity(tableName = "vision_item")
@Serializable
data class VisionItemEntity(
    @PrimaryKey val id: String,
    val type: VisionType,
    val text: String?,
    val mediaPath: String?,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "badge")
@Serializable
data class BadgeEntity(
    @PrimaryKey val badgeId: String,
    val name: String,
    val earnedAt: Long,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "partner")
@Serializable
data class PartnerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val contact: String,
    val shareCheckIns: Boolean = true,
    val shareSlips: Boolean = false,
    val shareGuardChanges: Boolean = true,
    /** Passcode held by the partner, hashed. Null unless partner-lock is on. */
    val lockPasscodeHash: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "check_in", indices = [Index("epochDay")])
@Serializable
data class CheckInEntity(
    @PrimaryKey val id: String,
    val epochDay: Long,
    val mood: Int,
    val note: String?,
    val shared: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "mentor_message", indices = [Index("timestamp")])
data class MentorMessageEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val fromUser: Boolean,
    val text: String,
    val intentId: String? = null,
)

@Entity(tableName = "lesson_read")
@Serializable
data class LessonReadEntity(
    @PrimaryKey val lessonId: String,
    val readAt: Long,
    val updatedAt: Long = System.currentTimeMillis(),
)

enum class ChangeStatus { PENDING, APPLIED, CANCELLED }

/**
 * The cooling-off lock. Weakening a guard is never instant — the request sits
 * here until effectiveAt passes. This is the difference between a wish and a wall.
 */
@Entity(
    tableName = "guard_change_request",
    indices = [Index("status", "effectiveAt")],
)
data class GuardChangeRequestEntity(
    @PrimaryKey val id: String,
    val requestedAt: Long,
    val effectiveAt: Long,
    val description: String,
    /** Serialised intent for the change, applied verbatim once it matures. */
    val payload: String,
    val status: ChangeStatus = ChangeStatus.PENDING,
)

/** Rolling per-day foreground time, used by TIME_LIMIT mode. */
@Entity(tableName = "app_usage", primaryKeys = ["packageName", "epochDay"])
data class AppUsageEntity(
    val packageName: String,
    val epochDay: Long,
    val foregroundMillis: Long,
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * One card the feed has already shown.
 *
 * Exists so the feed can do the two things that make it not a slot machine:
 * never repeat a card, and know when the day's portion is finished so it can
 * say "you're caught up" instead of generating more forever. An infinite feed
 * needs no memory; a finite one is nothing but memory.
 *
 * The id is the motivation item's id, so this table stays tiny — it holds
 * references to bundled content, never the content itself.
 */
@Entity(tableName = "feed_seen")
data class FeedSeenEntity(
    @PrimaryKey val itemId: String,
    /** The day it was served, so "today's set" can be reconstructed. */
    val epochDay: Long,
    val seenAt: Long = System.currentTimeMillis(),
)
