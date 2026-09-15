package com.bastion.app.data.repo

import com.bastion.app.data.db.DayStatus
import com.bastion.app.domain.Rank
import com.bastion.app.domain.RankPoints

/**
 * Every number the app shows about a man's journey, as arithmetic.
 *
 * Pulled out of the flow it feeds so it can be tested directly. These rules are
 * fiddly — two different start dates, a streak measured from the last slip
 * rather than from the beginning, points that must not be buyable — and they are
 * the numbers a man checks daily and would notice being wrong. A bug here is not
 * a rendering glitch; it is the app lying to him about his own record.
 */
object JourneyMath {

    /**
     * [installedEpochDay] is when he signed the covenant; 0 before onboarding.
     * [dayLogs] is every day he has actually said something about.
     * [earliestUrgeDay] is the oldest urge of any kind, or null if none.
     *
     * ## A day he has not spoken about is not a day he won
     *
     * This took `epochDay to isSlip`, and every day absent from the list counted
     * as clean: the streak was `today - lastSlip`, clean days were
     * `totalDays - slipCount`, and points came off the same subtraction. Install
     * the app, never open it again, and the rank climbs on its own — seventeen
     * days clean, three hundred points, for a man who has not told it a single
     * thing. A number that goes up whether or not he shows up is not a record of
     * anything, and it is the one number he checks daily.
     *
     * Absence is now absence. A clean day is a day he marked clean, and
     * [DayStatus.UNLOGGED] is a real answer the arithmetic respects rather than
     * a gap it fills in for him.
     */
    fun derive(
        today: Long,
        installedEpochDay: Long,
        dayLogs: List<Pair<Long, DayStatus>>,
        earliestUrgeDay: Long?,
        habitCompletions: Int = 0,
        checkIns: Int = 0,
        resisted: Int = 0,
        lessons: Int = 0,
        badges: Int = 0,
        panicCount: Int = 0,
        /**
         * Days ticked off inside a challenge, across every challenge taken.
         *
         * This argument did not exist and [RankPoints.CHALLENGE_DAY] was
         * therefore dead: the constant said a challenge day was worth three
         * points, the sum below never mentioned it, and a man could finish a
         * thirty-day challenge and watch his rank not move. A reward the app
         * advertises and does not pay is worse than one it never offered.
         */
        challengeDays: Int = 0,
    ): JourneyState {
        val installed = if (installedEpochDay > 0) installedEpochDay else today

        // Clamped to today: a slip dated in the future would otherwise inflate
        // the count and skew points, and no one can relapse tomorrow.
        val slipDays = dayLogs
            .filter { (day, status) -> status == DayStatus.SLIP && day <= today }
            .map { it.first }
            .sorted()

        // Marked clean, by him, on purpose.
        val cleanDays = dayLogs
            .filter { (day, status) -> status == DayStatus.CLEAN && day <= today }
            .map { it.first }
            .sorted()
        val cleanSet = cleanDays.toSet()

        // The journey starts at the oldest thing he has told the app about.
        //
        // It used to start on the day he installed, full stop, which made
        // logging history pointless on a fresh install: log a slip from three
        // days ago and the ring read "3 days" while BEST and CLEAN both read
        // "1", and the slip itself was filtered out of the count for being older
        // than the journey. He recorded a fact and the app argued with it. What
        // he tells it about is what happened, whether or not it was watching at
        // the time.
        val earliestLogged = listOfNotNull(
            dayLogs.map { it.first }.filter { it <= today }.minOrNull(),
            earliestUrgeDay?.takeIf { it <= today },
        ).minOrNull()
        val start = minOf(installed, earliestLogged ?: installed)

        val totalDays = ((today - start).toInt() + 1).coerceAtLeast(1)
        val slipCount = slipDays.count { it >= start }
        val totalCleanDays = cleanDays.count { it >= start }

        val currentStreak = currentStreak(today, cleanSet)

        // Rank counts only the days he has actually walked with the app.
        //
        // Points come off clean days, so inferring the start from backfilled
        // history would let one tap on a date two years back buy several ranks
        // at once. History is history and rank is earned; keeping them on
        // separate clocks is what lets the first be generous without making the
        // second meaningless.
        val cleanDaysEarned = cleanDays.count { it >= installed }

        val points = cleanDaysEarned * RankPoints.CLEAN_DAY +
            habitCompletions * RankPoints.HABIT_COMPLETED +
            checkIns * RankPoints.CHECK_IN +
            resisted * RankPoints.URGE_RESISTED +
            lessons * RankPoints.LESSON_READ +
            slipCount * RankPoints.SLIP_LOGGED_HONESTLY +
            panicCount * RankPoints.PANIC_SESSION_COMPLETED +
            challengeDays * RankPoints.CHALLENGE_DAY

        return JourneyState(
            currentStreak = currentStreak,
            longestStreak = longestStreak(cleanDays),
            totalCleanDays = totalCleanDays,
            totalDays = totalDays,
            slipCount = slipCount,
            points = points,
            rank = Rank.forPoints(points),
            progressToNextRank = Rank.progress(points),
            pointsToNextRank = Rank.pointsToNext(points),
            urgesResisted = resisted,
            badges = badges,
            startEpochDay = start,
        )
    }

    /**
     * The run of marked-clean days ending now.
     *
     * Today is optional, deliberately, and it is the one softness left in here.
     * A streak that breaks at midnight because the day is not over yet would
     * punish a man for not having finished his day, so an unmarked *today* leaves
     * yesterday's streak standing. An unmarked day in the past is a different
     * thing: it is a day he never spoke about, and nothing about it says it was
     * clean.
     */
    fun currentStreak(today: Long, cleanDays: Set<Long>): Int {
        var day = if (today in cleanDays) today else today - 1
        var streak = 0
        while (day in cleanDays) {
            streak++
            day--
        }
        return streak
    }

    /** The longest run of consecutive marked-clean days. */
    fun longestStreak(cleanDays: List<Long>): Int {
        val sorted = cleanDays.distinct().sorted()
        var best = 0
        var run = 0
        var previous: Long? = null
        for (day in sorted) {
            run = if (previous != null && day == previous + 1) run + 1 else 1
            best = maxOf(best, run)
            previous = day
        }
        return best
    }
}
