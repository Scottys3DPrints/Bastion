package com.bastion.app.data.repo

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
     * [dayLogs] is every logged day as `epochDay to isSlip`.
     * [earliestUrgeDay] is the oldest urge of any kind, or null if none.
     */
    fun derive(
        today: Long,
        installedEpochDay: Long,
        dayLogs: List<Pair<Long, Boolean>>,
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
            .filter { (day, isSlip) -> isSlip && day <= today }
            .map { it.first }
            .sorted()

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
        val totalCleanDays = (totalDays - slipCount).coerceAtLeast(0)

        val lastSlip = slipDays.lastOrNull()
        val currentStreak = if (lastSlip == null) totalDays else (today - lastSlip).toInt()

        // Rank counts only the days he has actually walked with the app.
        //
        // Points come off clean days, so inferring the start from backfilled
        // history would let one tap on a date two years back buy several ranks
        // at once. History is history and rank is earned; keeping them on
        // separate clocks is what lets the first be generous without making the
        // second meaningless.
        val daysSinceInstalled = ((today - installed).toInt() + 1).coerceAtLeast(1)
        val cleanDaysEarned =
            (daysSinceInstalled - slipDays.count { it >= installed }).coerceAtLeast(0)

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
            startEpochDay = start,
        )
    }

    /** Longest run of consecutive days that contained no slip. */
    fun longestStreak(start: Long, today: Long, slipDays: List<Long>): Int {
        val relevant = slipDays.filter { it in start..today }.sorted()
        var best = 0
        var cursor = start
        for (slip in relevant) {
            best = maxOf(best, (slip - cursor).toInt())
            cursor = slip + 1
        }
        return maxOf(best, (today - cursor + 1).toInt()).coerceAtLeast(0)
    }
}
