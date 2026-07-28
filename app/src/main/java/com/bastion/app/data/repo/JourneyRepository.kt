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
import java.time.LocalDate
import java.util.UUID

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

    val state: Flow<JourneyState> = combine(
        journeyDao.allDays(),
        habitDao.totalCompletions(),
        progressDao.checkInCount(),
        journeyDao.resistedCount(),
        combine(
            progressDao.lessonsRead().map { it.size },
            progressDao.badgeCount(),
            settings.settings,
        ) { lessons, badges, s -> Triple(lessons, badges, s) },
    ) { days, habitCompletions, checkIns, resisted, (lessons, badges, s) ->

        val today = LocalDate.now().toEpochDay()
        val start = if (s.journeyStartEpochDay > 0) s.journeyStartEpochDay else today
        val slipDays = days.filter { it.status == DayStatus.SLIP }.map { it.epochDay }.sorted()

        val totalDays = ((today - start).toInt() + 1).coerceAtLeast(1)
        val slipCount = slipDays.count { it >= start }
        val totalCleanDays = (totalDays - slipCount).coerceAtLeast(0)

        val lastSlip = slipDays.lastOrNull { it <= today }
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
        journeyDao.upsertDay(DayLogEntity(epochDay = epochDay, status = DayStatus.SLIP, note = note))
    }

    /** Undo, for the man who tapped the wrong thing. No interrogation. */
    suspend fun clearDay(epochDay: Long) {
        journeyDao.upsertDay(DayLogEntity(epochDay = epochDay, status = DayStatus.CLEAN))
    }

    suspend fun logUrge(
        resisted: Boolean,
        intensity: Int,
        mood: String?,
        trigger: String?,
        contextApp: String?,
        place: String?,
        note: String?,
    ) {
        val now = System.currentTimeMillis()
        journeyDao.upsertUrge(
            UrgeLogEntity(
                id = UUID.randomUUID().toString(),
                timestamp = now,
                epochDay = LocalDate.now().toEpochDay(),
                resisted = resisted,
                intensity = intensity,
                mood = mood,
                trigger = trigger,
                contextApp = contextApp,
                place = place,
                note = note,
            )
        )
        if (!resisted) logSlip(note = trigger)
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
