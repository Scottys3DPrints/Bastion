package com.bastion.app.data.repo

/**
 * Streaks, rates and calendars — as arithmetic, away from the database.
 *
 * Every number the habit detail screen shows is computed here, from a set of
 * days and nothing else. That is deliberate: a streak is the single number a
 * habit tracker is judged on, it is the one a man will notice being wrong, and
 * an off-by-one in it is invisible in a screenshot and obvious after a week.
 * Pure functions over `Set<Long>` can be checked against a calendar on a laptop.
 *
 * Days are epoch days throughout, never `LocalDate`, so nothing here can be
 * moved by a timezone, a clock change or a device date roll — the same reason
 * the lockdown clocks are anchored the way they are.
 */
object HabitMath {

    /**
     * Days running back from [today], counting today only if it was done.
     *
     * The "only if" is the whole design. A streak that breaks at midnight tells a
     * man he has failed at 00:01 for something he still has all day to do, which
     * is both untrue and the precise moment a tracker gets deleted. So today is
     * *optional*: miss it and the streak still reads from yesterday, unbroken,
     * until the day is actually over. Miss yesterday and it is genuinely gone.
     */
    fun currentStreak(done: Set<Long>, today: Long): Int {
        var day = if (today in done) today else today - 1
        var streak = 0
        while (day in done) {
            streak++
            day--
        }
        return streak
    }

    /**
     * The longest run ever put together, for the detail screen.
     *
     * Walks the sorted days once rather than probing a range, so a habit kept for
     * three years costs the same as one kept for three weeks.
     */
    fun bestStreak(done: Set<Long>): Int {
        if (done.isEmpty()) return 0
        val sorted = done.sorted()
        var best = 1
        var run = 1
        for (i in 1 until sorted.size) {
            run = if (sorted[i] == sorted[i - 1] + 1) run + 1 else 1
            if (run > best) best = run
        }
        return best
    }

    /**
     * What fraction of the window was kept, as 0..1.
     *
     * Measured from the day the habit was *adopted*, never from the start of the
     * window. Counting the weeks before a man had the habit at all would open
     * every new habit at 4% and call it a completion rate — a number that is
     * technically defensible and reads as failure for something he started
     * yesterday and has not yet missed.
     */
    fun completionRate(done: Set<Long>, from: Long, to: Long): Float {
        val days = (to - from + 1)
        if (days <= 0L) return 0f
        val kept = done.count { it in from..to }
        return (kept.toFloat() / days).coerceIn(0f, 1f)
    }

    /**
     * The heatmap's days, oldest first, aligned so each week starts on the same
     * weekday as [today].
     *
     * Returns exactly `weeks * 7` days ending on [today], which is what lets the
     * grid be drawn as fixed columns of seven with no ragged edge and no empty
     * leading cells to reason about.
     */
    fun calendarDays(today: Long, weeks: Int): List<Long> {
        val total = weeks * 7
        val first = today - total + 1
        return (0 until total).map { first + it }
    }

    /**
     * Whether a day counts as complete, given how much was done and the target.
     *
     * A tick habit has a target of 1 and is complete on any row at all, which is
     * what a row has always meant. A counting habit is only complete when it
     * reaches its number — partial days show as partial and are honestly not a
     * streak day, because a streak that counts one glass of water out of eight
     * is a streak measuring nothing.
     */
    fun isComplete(count: Int, targetCount: Int): Boolean = count >= maxOf(1, targetCount)

    /**
     * The days that count towards a streak, from raw counts.
     *
     * The place where partial days are dropped, so every caller above gets the
     * same answer to "did this day count" and no screen can disagree with
     * another about what a streak is.
     */
    fun completeDays(counts: Map<Long, Int>, targetCount: Int): Set<Long> =
        counts.filterValues { isComplete(it, targetCount) }.keys
}
