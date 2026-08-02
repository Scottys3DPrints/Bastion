package com.bastion.app.data.repo

import com.bastion.app.data.db.CheckInEntity
import com.bastion.app.data.db.DayLogEntity
import com.bastion.app.data.db.DayStatus
import com.bastion.app.data.db.HabitDao
import com.bastion.app.data.db.JourneyDao
import com.bastion.app.data.db.LessonReadEntity
import com.bastion.app.data.db.ProgressDao
import com.bastion.app.data.db.UrgeLogEntity
import com.bastion.app.data.prefs.SettingsStore
import com.bastion.app.domain.Rank
import com.bastion.app.domain.RankPoints
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit

data class JourneyState(
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val totalCleanDays: Int = 0,
    val totalDays: Int = 0,
    val slipCount: Int = 0,
    val points: Int = 0,
    val rank: Rank = Rank.RECRUIT,
    val progressToNextRank: Float = 0f,
    val pointsToNextRank: Int? = null,
    val urgesResisted: Int = 0,
    val badges: Int = 0,
)

/**
 * The single source of truth for "how am I doing".
 *
 * Deliberately derives the streak from logged slips rather than requiring a
 * daily check-in: a man who forgets to open the app for three days has not
 * failed, and Bastion must never imply he has.
 */
class JourneyRepository(
    private val journeyDao: JourneyDao,
    private val habitDao: HabitDao,
    private val progressDao: ProgressDao,
    private val settings: SettingsStore,
) {

    /**
     * Re-emits when the calendar day rolls over.
     *
     * Without it, `LocalDate.now()` was sampled once when the flow last emitted:
     * an app left open across midnight showed yesterday's streak until something
     * unrelated happened to change. A man who checks his phone at 12:01 should
     * see the day he just earned.
     */
    private val dayTicks: Flow<Long> = flow {
        while (true) {
            val today = LocalDate.now()
            emit(today.toEpochDay())
            val nextMidnight = today.plusDays(1).atStartOfDay(ZoneId.systemDefault())
            val waitMillis = Duration.between(ZonedDateTime.now(ZoneId.systemDefault()), nextMidnight)
                .toMillis()
                .coerceIn(1_000L, TimeUnit.HOURS.toMillis(24))
            delay(waitMillis)
        }
    }

    val state: Flow<JourneyState> = combine(
        journeyDao.allDays(),
        habitDao.totalCompletions(),
        progressDao.checkInCount(),
        journeyDao.resistedCount(),
        combine(
            progressDao.lessonsRead().map { it.size },
            progressDao.badgeCount(),
            settings.settings,
            dayTicks,
        ) { lessons, badges, s, today -> Aux(lessons, badges, s, today) },
    ) { days, habitCompletions, checkIns, resisted, aux ->

        val (lessons, badges, s, today) = aux
        val start = if (s.journeyStartEpochDay > 0) s.journeyStartEpochDay else today

        // Clamped to today: a slip dated in the future would otherwise inflate
        // the count and skew points, and no one can relapse tomorrow.
        val slipDays = days
            .filter { it.status == DayStatus.SLIP && it.epochDay <= today }
            .map { it.epochDay }
            .sorted()

        val totalDays = ((today - start).toInt() + 1).coerceAtLeast(1)
        val slipCount = slipDays.count { it >= start }
        val totalCleanDays = (totalDays - slipCount).coerceAtLeast(0)

        val lastSlip = slipDays.lastOrNull()
        val currentStreak = if (lastSlip == null) totalDays else (today - lastSlip).toInt()

        val points = totalCleanDays * RankPoints.CLEAN_DAY +
            habitCompletions * RankPoints.HABIT_COMPLETED +
            checkIns * RankPoints.CHECK_IN +
            resisted * RankPoints.URGE_RESISTED +
            lessons * RankPoints.LESSON_READ +
            slipCount * RankPoints.SLIP_LOGGED_HONESTLY +
            s.panicCount * RankPoints.PANIC_SESSION_COMPLETED

        JourneyState(
            currentStreak = currentStreak,
            longestStreak = longestStreak(start, today, slipDays),
            totalCleanDays = totalCleanDays,
            totalDays = totalDays,
            slipCount = slipCount,
            points = points,
            rank = Rank.forPoints(points),
            progressToNextRank = Rank.progress(points),
            pointsToNextRank = Rank.pointsToNext(points),
            urgesResisted = resisted,
            badges = badges,
        )
    }

    /** The tail of the combine, bundled so the day tick fits within arity limits. */
    private data class Aux(
        val lessons: Int,
        val badges: Int,
        val settings: com.bastion.app.data.prefs.Settings,
        val today: Long,
    )

    /** Longest run of consecutive days that contained no slip. */
    private fun longestStreak(start: Long, today: Long, slipDays: List<Long>): Int {
        val relevant = slipDays.filter { it in start..today }.sorted()
        var best = 0
        var cursor = start
        for (slip in relevant) {
            best = maxOf(best, (slip - cursor).toInt())
            cursor = slip + 1
        }
        return maxOf(best, (today - cursor + 1).toInt()).coerceAtLeast(0)
    }

    suspend fun logSlip(epochDay: Long = LocalDate.now().toEpochDay(), note: String? = null) {
        // Never in the future, whatever the caller passes.
        val day = epochDay.coerceAtMost(LocalDate.now().toEpochDay())
        journeyDao.upsertDay(DayLogEntity(epochDay = day, status = DayStatus.SLIP, note = note))
    }

    /** Undo, for the man who tapped the wrong thing. No interrogation. */
    suspend fun clearDay(epochDay: Long) {
        journeyDao.upsertDay(DayLogEntity(epochDay = epochDay, status = DayStatus.CLEAN))
    }

    /**
     * [epochDay] exists so a slip can be recorded for the night it happened
     * rather than the morning it gets admitted. It used to be hardcoded to
     * today, which meant the calendar had to write slips down a second, blunter
     * path — and the two paths disagreed about what a slip even was.
     */
    suspend fun logUrge(
        resisted: Boolean,
        intensity: Int,
        mood: String? = null,
        trigger: String? = null,
        contextApp: String? = null,
        place: String? = null,
        note: String? = null,
        feelings: List<String> = emptyList(),
        device: String? = null,
        soughtOut: Boolean? = null,
        durationMinutes: Int? = null,
        whatHelped: String? = null,
        epochDay: Long = LocalDate.now().toEpochDay(),
        /**
         * When it actually happened. Defaults to now; a past entry passes the
         * real moment so that "when urges hit" stays an honest clock rather than
         * a chart of when this man happens to open the app.
         */
        atMillis: Long = System.currentTimeMillis(),
    ) {
        // Never in the future, whatever the caller passes.
        val today = epochDay.coerceAtMost(LocalDate.now().toEpochDay())
        val urge = UrgeLogEntity(
            id = UUID.randomUUID().toString(),
            timestamp = atMillis.coerceAtMost(System.currentTimeMillis()),
            epochDay = today,
            resisted = resisted,
            intensity = intensity,
            mood = mood,
            trigger = trigger,
            contextApp = contextApp,
            place = place,
            note = note,
            feelings = feelings.takeIf { it.isNotEmpty() }?.joinToString(","),
            device = device,
            soughtOut = soughtOut,
            durationMinutes = durationMinutes,
            whatHelped = whatHelped,
        )
        // The urge and the slip it became are a single fact; they are written
        // together or not at all.
        val day = if (resisted) null
        else DayLogEntity(epochDay = today, status = DayStatus.SLIP, note = note ?: trigger)

        journeyDao.upsertUrgeAndDay(urge, day)
    }

    /**
     * Writes a filled-in [com.bastion.app.feature.track.LogEntry].
     *
     * The flow builds one object over five screens; unpacking it at each call
     * site meant three places had to agree on twelve arguments, and the one
     * that forgot `epochDay` filed a Tuesday under Friday.
     */
    suspend fun saveLog(
        entry: com.bastion.app.feature.track.LogEntry,
        /** Only for an urge happening right now — a past one has no foreground app. */
        heldContextApp: Boolean = false,
    ) {
        val now = LocalDate.now()
        logUrge(
            resisted = entry.resisted,
            intensity = entry.intensity,
            trigger = entry.trigger,
            contextApp = if (heldContextApp && entry.date == now) {
                com.bastion.app.guard.accessibility.BastionAccessibilityService.foregroundApp.value
            } else null,
            place = entry.place,
            note = entry.note,
            feelings = entry.feelings,
            device = entry.device,
            soughtOut = entry.soughtOut,
            durationMinutes = entry.durationMinutes,
            whatHelped = entry.whatHelped,
            epochDay = entry.date.toEpochDay(),
            atMillis = entry.atMillis(),
        )
    }

    suspend fun checkIn(mood: Int, note: String?) {
        progressDao.upsertCheckIn(
            CheckInEntity(
                id = UUID.randomUUID().toString(),
                epochDay = LocalDate.now().toEpochDay(),
                mood = mood,
                note = note,
            )
        )
    }

    suspend fun markLessonRead(lessonId: String) {
        progressDao.markLessonRead(LessonReadEntity(lessonId, System.currentTimeMillis()))
    }

    val recentUrges = journeyDao.recentUrges()
    val allDays = journeyDao.allDays()
    val badges = progressDao.badges()
    val checkIns = progressDao.recentCheckIns()

    suspend fun urgesSince(millis: Long) = journeyDao.urgesSince(millis)

    /** Day of the journey, 1-based, used to pick the Daily Brief. */
    suspend fun dayOfJourney(): Int {
        val s = settings.current()
        val start = if (s.journeyStartEpochDay > 0) s.journeyStartEpochDay else LocalDate.now().toEpochDay()
        return ((LocalDate.now().toEpochDay() - start).toInt() + 1).coerceAtLeast(1)
    }
}
