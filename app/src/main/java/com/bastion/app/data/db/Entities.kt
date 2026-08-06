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

/**
 * When in the day a habit belongs, which is the spine of the journal.
 *
 * A flat list says what you signed up for. Sections say what is due *now* — and
 * that is the difference between a list you read and a list you act on. The
 * morning ones are still there at 9pm, but they have visibly gone by, which is
 * information a flat list cannot give you.
 *
 * ANYTIME is the default and stays first-class rather than being a dumping
 * ground: plenty of habits genuinely have no hour, and forcing a man to invent
 * one is how a tracker starts feeling like paperwork.
 */
enum class TimeOfDay(val label: String) {
    ANYTIME("Anytime"),
    MORNING("Morning"),
    AFTERNOON("Afternoon"),
    EVENING("Evening"),
}

/**
 * How often a habit is actually due.
 *
 * Everything was daily before, which quietly made the app lie twice: a habit
 * meant for three times a week showed up every morning as an outstanding
 * failure, and its streak broke four times a week for days it was never
 * supposed to be done on. A schedule is not a preference — it is what makes
 * "did I keep this" a question with a true answer.
 */
enum class ScheduleType(val label: String) {
    DAILY("Every day"),
    WEEKDAYS("Certain days"),
    EVERY_N_DAYS("Every few days"),
    TIMES_PER_WEEK("Times per week"),
}

/**
 * What a day says, which is more than done or not.
 *
 * NONE is the absence of a row rather than a stored value. The other three are
 * different claims and deserve different marks:
 *
 *  - **DONE** — kept, and the only one that extends a streak.
 *  - **SKIPPED** — deliberately not today, and said so. It does not extend a
 *    streak and it is not pretending to. The distinction that matters is
 *    against a silent gap, which could mean anything.
 *  - **FAILED** — meant to, did not. Recording it is worth more than leaving
 *    the day blank: a man who marks his own misses is keeping an honest book,
 *    and the calendar he looks at in a month is only useful if he did.
 */
enum class LogStatus { DONE, SKIPPED, FAILED }

@Entity(tableName = "habit")
@Serializable
data class HabitEntity(
    @PrimaryKey val id: String,
    val name: String,
    val domain: String,
    val emoji: String,
    /**
     * The human phrase from the catalogue — "3x per week", "10 minutes".
     * Descriptive, never arithmetic. [targetCount] is the number that counts.
     */
    val target: String,
    val why: String,
    val active: Boolean = true,
    val sortOrder: Int = 0,
    val timeOfDay: TimeOfDay = TimeOfDay.ANYTIME,
    /**
     * How many times a day this is done. 1 is a tick; more is a counter.
     *
     * The two are the same mechanism with a different face, which is the point:
     * "read scripture" is once, "drink water" is eight times, and a tracker that
     * can only express the first turns the second into a lie you tell it once a
     * day.
     */
    val targetCount: Int = 1,
    /** "glasses", "pages", "minutes". Blank for a plain tick. */
    val unit: String = "",

    val scheduleType: ScheduleType = ScheduleType.DAILY,
    /**
     * Which days, as ISO weekday numbers — 1 is Monday, 7 is Sunday.
     *
     * A CSV rather than a table. Room would want a second entity and a join for
     * what is at most seven small integers that are always read together and
     * never queried across, and the Becoming profile already scans this table.
     */
    val weekdaysCsv: String = "",
    /** For [ScheduleType.EVERY_N_DAYS], counted from [startEpochDay]. */
    val everyNDays: Int = 2,
    /** For [ScheduleType.TIMES_PER_WEEK]: every day is eligible, the week has a quota. */
    val timesPerWeek: Int = 3,
    /**
     * The day the habit was taken on. Nothing before it is scheduled, ever.
     *
     * This is what stops a habit adopted today from showing a year of failures
     * behind it, and it anchors EVERY_N_DAYS so "every third day" means every
     * third day *from when you started*, not from an arbitrary epoch.
     */
    val startEpochDay: Long = 0L,
    /** Optional end. Null means it runs until dropped. */
    val endEpochDay: Long? = null,

    val updatedAt: Long = System.currentTimeMillis(),
) {
    /** Whether this is a counter rather than a tick. */
    val counts: Boolean get() = targetCount > 1

    /** The weekday numbers, parsed. Bad data is dropped rather than thrown. */
    val weekdays: List<Int>
        get() = weekdaysCsv.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }
}

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
    /**
     * How much of the day's target was done.
     *
     * A row used to mean "done", full stop, so a counting habit could only ever
     * be all or nothing. The row still means "something happened today" —
     * everything that counted rows before this still counts them — but it now
     * also says how much, and the day is only *complete* when this reaches the
     * habit's targetCount. Existing rows migrate to 1, which is exactly what
     * they already meant.
     */
    val count: Int = 1,
    /**
     * Done, skipped or failed. There is no NONE — that is the missing row.
     *
     * Existing rows migrate to DONE, which is the only thing a row has ever
     * meant, so nothing already recorded changes its mind on upgrade.
     */
    val status: LogStatus = LogStatus.DONE,
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
