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
     * The same run, but counting only the days the habit was actually due.
     *
     * This is the version that matters once a habit is not daily. A habit set
     * to Mon/Wed/Fri must not have its chain broken by a Tuesday it was never
     * supposed to be done on — which is exactly what the plain [currentStreak]
     * above would do, and why "three times a week" was unusable before.
     *
     * Walks back from [today] to [startDay]:
     *  - a day the habit was not due is passed over entirely, neither extending
     *    nor breaking;
     *  - today with nothing logged is passed over too, for the reason given on
     *    [currentStreak] — a man has all day;
     *  - DONE extends;
     *  - anything else, including SKIPPED, ends it. A skip is an honest "not
     *    today", not a day kept, and a streak that counts it is measuring
     *    something other than what the number claims.
     */
    fun scheduledStreak(
        status: Map<Long, Boolean>,
        today: Long,
        startDay: Long,
        isScheduled: (Long) -> Boolean,
    ): Int {
        var streak = 0
        var day = today
        while (day >= startDay) {
            if (!isScheduled(day)) {
                day--
                continue
            }
            val logged = status[day]
            if (day == today && logged == null) {
                day--
                continue
            }
            if (logged == true) streak++ else break
            day--
        }
        return streak
    }

    /**
     * The longest scheduled run ever put together.
     *
     * Forwards from [startDay], because "best" is a property of the whole
     * record rather than of its end, and the same skip rule applies.
     */
    fun scheduledBestStreak(
        status: Map<Long, Boolean>,
        today: Long,
        startDay: Long,
        isScheduled: (Long) -> Boolean,
    ): Int {
        var best = 0
        var running = 0
        var day = startDay
        while (day <= today) {
            if (!isScheduled(day)) {
                day++
                continue
            }
            if (status[day] == true) {
                running++
                if (running > best) best = running
            } else {
                running = 0
            }
            day++
        }
        return best
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

    /**
     * Whether a habit is due on a given day.
     *
     * Nothing before the day it was adopted is ever scheduled, which is what
     * stops a habit taken on this morning from showing a year of misses behind
     * it — and it anchors [ScheduleType.EVERY_N_DAYS] so "every third day"
     * counts from when he started rather than from an arbitrary epoch.
     *
     * TIMES_PER_WEEK treats every day as eligible on purpose. A quota is not a
     * calendar: three times a week means any three, and marking four specific
     * days as "due" would invent a schedule he did not set. The week's progress
     * is counted separately, by [weekKept].
     */
    fun isScheduledOn(
        day: Long,
        startDay: Long,
        endDay: Long?,
        type: com.bastion.app.data.db.ScheduleType,
        weekdays: List<Int>,
        everyNDays: Int,
    ): Boolean {
        if (day < startDay) return false
        if (endDay != null && day > endDay) return false
        return when (type) {
            com.bastion.app.data.db.ScheduleType.DAILY -> true
            com.bastion.app.data.db.ScheduleType.TIMES_PER_WEEK -> true
            // An empty day list would otherwise mean "never", which reads on
            // screen as a habit that has silently stopped existing.
            com.bastion.app.data.db.ScheduleType.WEEKDAYS ->
                weekdays.isEmpty() || isoWeekday(day) in weekdays
            com.bastion.app.data.db.ScheduleType.EVERY_N_DAYS -> {
                val n = maxOf(1, everyNDays)
                (day - startDay) % n == 0L
            }
        }
    }

    /**
     * ISO weekday for an epoch day: 1 Monday .. 7 Sunday.
     *
     * Epoch day 0 was a Thursday, which is where the 3 comes from. Done as
     * arithmetic rather than via LocalDate so this stays testable on the JVM
     * and immune to the device's timezone.
     */
    fun isoWeekday(day: Long): Int = ((day + 3).mod(7L) + 1).toInt()

    /** The Monday of the week containing [day], as an epoch day. */
    fun weekStart(day: Long): Long = day - (isoWeekday(day) - 1)

    /**
     * How many of a "times per week" quota have been kept in [day]'s week.
     *
     * The quota's own progress, which is what the row shows instead of a
     * streak: "2 of 3 this week" is the true statement, and a day-streak on a
     * habit with no fixed days would be a number about nothing.
     */
    fun weekKept(done: Set<Long>, day: Long): Int {
        val start = weekStart(day)
        return (start until start + 7).count { it in done }
    }

    /**
     * How much of the record a heatmap is showing.
     *
     * Four windows rather than one, because the same grid answers different
     * questions at different scales: a week says what this week looks like, a
     * year says whether the whole thing is holding. All but YEAR scroll a week
     * at a time, so the columns always line up on the same weekday and the grid
     * never reflows under a thumb.
     */
    enum class HeatPeriod(val label: String, val weeks: Int, val stepWeeks: Int) {
        WEEK("Week", 1, 1),
        MONTH("Month", 4, 4),
        QUARTER("Quarter", 12, 1),
        YEAR("Year", 53, 0),
    }

    /**
     * The days a heatmap shows, oldest first, always whole weeks.
     *
     * [weekOffset] is in weeks back from the current one and is expected to
     * have been through [clampWeekOffset] already. YEAR ignores it — a year is
     * anchored to today and scrolls horizontally instead, because paging a year
     * by weeks would take fifty taps to cross.
     */
    fun heatmapDays(
        period: HeatPeriod,
        weekOffset: Int,
        today: Long,
    ): List<Long> {
        val thisWeek = weekStart(today)
        if (period == HeatPeriod.YEAR) {
            // Ends on the last day of this week so the final column is whole,
            // which is what stops the grid ending in a ragged half column.
            val end = thisWeek + 6
            val start = end - (period.weeks * 7 - 1)
            return (start..end).toList()
        }
        val displayWeekStart = thisWeek + weekOffset * 7L
        val end = displayWeekStart + 6
        val start = end - (period.weeks * 7 - 1)
        return (start..end).toList()
    }

    /**
     * Keeps paging inside the habit's own life.
     *
     * Forward stops at the current week — there is no history in the future.
     * Back stops at the week the habit was adopted, so a man cannot page into
     * years of blank cells from before it existed and read them as failure.
     */
    fun clampWeekOffset(desired: Int, today: Long, startDay: Long): Int {
        val thisWeek = weekStart(today)
        val firstWeek = weekStart(startDay)
        val minOffset = ((firstWeek - thisWeek) / 7).toInt()
        return desired.coerceIn(minOf(minOffset, 0), 0)
    }

    /**
     * Which shade a day gets, as 0..1, for a counting habit.
     *
     * Full colour is reserved for a day that actually met its target; partial
     * progress gets its own steps so seven glasses out of eight is visibly not
     * the same as eight. A grid where "nearly" and "done" look identical is a
     * grid that flatters.
     */
    fun heatLevel(count: Int, targetCount: Int): Float {
        val target = maxOf(1, targetCount)
        if (count <= 0) return 0f
        return (count.toFloat() / target).coerceIn(0f, 1f)
    }
}
