package com.bastion.app

import com.bastion.app.data.db.ScheduleType
import com.bastion.app.data.repo.HabitMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The streak, pinned to a calendar.
 *
 * This is the number a habit tracker is judged on and the one a man will notice
 * being wrong. An off-by-one is invisible in a screenshot and obvious after a
 * week — by which point he has stopped believing the rest of the numbers too.
 *
 * Day 1000 stands in for "today" throughout; nothing here depends on a real
 * date, which is the point of epoch days.
 */
class HabitMathTest {

    private val today = 1000L

    // --- the current streak ------------------------------------------------

    @Test
    fun `an unbroken run ending today counts today`() {
        assertEquals(3, HabitMath.currentStreak(setOf(998L, 999L, 1000L), today))
    }

    /**
     * The rule the whole design rests on.
     *
     * A streak that breaks at midnight tells a man he has failed at 00:01 for
     * something he still has all day to do. That is both untrue and the exact
     * moment a tracker gets deleted, so today is optional: the run reads from
     * yesterday until the day is genuinely over.
     */
    @Test
    fun `today not yet done does not break a run`() {
        assertEquals(2, HabitMath.currentStreak(setOf(998L, 999L), today))
    }

    @Test
    fun `missing yesterday does break it`() {
        // Done the day before yesterday, and nothing since. That is over.
        assertEquals(0, HabitMath.currentStreak(setOf(998L), today))
    }

    @Test
    fun `a run that skips a day only counts back to the gap`() {
        assertEquals(2, HabitMath.currentStreak(setOf(990L, 991L, 999L, 1000L), today))
    }

    @Test
    fun `today alone is a streak of one`() {
        assertEquals(1, HabitMath.currentStreak(setOf(1000L), today))
    }

    @Test
    fun `nothing logged is no streak`() {
        assertEquals(0, HabitMath.currentStreak(emptySet(), today))
    }

    // --- the best ever ------------------------------------------------------

    @Test
    fun `the best run is the longest one anywhere in the record`() {
        // 10..14 is five; the recent one is two.
        val done = setOf(10L, 11L, 12L, 13L, 14L, 999L, 1000L)
        assertEquals(5, HabitMath.bestStreak(done))
    }

    @Test
    fun `a single day is a best of one, and nothing is zero`() {
        assertEquals(1, HabitMath.bestStreak(setOf(42L)))
        assertEquals(0, HabitMath.bestStreak(emptySet()))
    }

    @Test
    fun `the best run can be the current one`() {
        assertEquals(3, HabitMath.bestStreak(setOf(500L, 998L, 999L, 1000L)))
    }

    // --- how much of it was kept -------------------------------------------

    @Test
    fun `a rate is kept days over the window`() {
        // Five days in the window, three of them kept.
        val done = setOf(996L, 998L, 1000L)
        assertEquals(0.6f, HabitMath.completionRate(done, from = 996L, to = 1000L), 0.001f)
    }

    /**
     * Measured from adoption, never from the edge of the window.
     *
     * A habit started yesterday and kept both days is at 100%, not at 2% of a
     * quarter he did not have it for. The second number is technically
     * defensible and reads as failure for something he has not yet missed.
     */
    @Test
    fun `a habit started yesterday and kept is at full marks`() {
        assertEquals(1f, HabitMath.completionRate(setOf(999L, 1000L), from = 999L, to = today), 0.001f)
    }

    @Test
    fun `days outside the window are not counted`() {
        // The two old ones must not inflate a two-day window.
        val done = setOf(500L, 501L, 1000L)
        assertEquals(0.5f, HabitMath.completionRate(done, from = 999L, to = 1000L), 0.001f)
    }

    @Test
    fun `a backwards or empty window is zero rather than a crash`() {
        assertEquals(0f, HabitMath.completionRate(setOf(1000L), from = 1000L, to = 999L), 0.001f)
    }

    // --- the calendar -------------------------------------------------------

    @Test
    fun `the calendar is whole weeks ending today`() {
        val days = HabitMath.calendarDays(today, weeks = 12)
        assertEquals(84, days.size)
        assertEquals(today, days.last())
        assertEquals(today - 83, days.first())
        // Contiguous, so chunking into sevens gives real weeks.
        assertTrue(days.zipWithNext().all { (a, b) -> b == a + 1 })
    }

    // --- what counts as done ------------------------------------------------

    @Test
    fun `a tick habit is done on any count at all`() {
        assertTrue(HabitMath.isComplete(1, targetCount = 1))
        assertFalse(HabitMath.isComplete(0, targetCount = 1))
    }

    /**
     * A counting habit is not done until it reaches its number.
     *
     * One glass of water out of eight is not a streak day. A streak that counts
     * it is measuring nothing, and the man reading it knows that first.
     */
    @Test
    fun `a counting habit is only done at its target`() {
        assertFalse(HabitMath.isComplete(7, targetCount = 8))
        assertTrue(HabitMath.isComplete(8, targetCount = 8))
        // Over the target still counts — an extra glass is not a failure.
        assertTrue(HabitMath.isComplete(9, targetCount = 8))
    }

    /** A target of zero would make every day vacuously complete. */
    @Test
    fun `a nonsense target still needs something done`() {
        assertFalse(HabitMath.isComplete(0, targetCount = 0))
        assertTrue(HabitMath.isComplete(1, targetCount = 0))
    }

    @Test
    fun `partial days are dropped before a streak is counted`() {
        val counts = mapOf(998L to 8, 999L to 3, 1000L to 8)
        val complete = HabitMath.completeDays(counts, targetCount = 8)
        assertEquals(setOf(998L, 1000L), complete)
        // And the gap on 999 breaks the run, leaving only today.
        assertEquals(1, HabitMath.currentStreak(complete, today))
    }

    @Test
    fun `the same counts against a tick habit are all complete`() {
        val counts = mapOf(998L to 8, 999L to 3, 1000L to 8)
        assertEquals(3, HabitMath.currentStreak(HabitMath.completeDays(counts, 1), today))
    }

    // --- which days a habit is even due on ---------------------------------

    /**
     * Epoch day 0 was a Thursday. Everything else follows from that, and getting
     * it wrong would put every "certain days" habit on the wrong days of the
     * week — visibly wrong on a phone and invisible in a code review.
     */
    @Test
    fun `epoch day zero was a thursday`() {
        assertEquals(4, HabitMath.isoWeekday(0L))
        assertEquals(5, HabitMath.isoWeekday(1L))
        assertEquals(7, HabitMath.isoWeekday(3L))
        assertEquals(1, HabitMath.isoWeekday(4L))
        // And it holds going backwards, where a naive modulo returns negatives.
        assertEquals(3, HabitMath.isoWeekday(-1L))
        assertEquals(1, HabitMath.isoWeekday(-3L))
    }

    @Test
    fun `a week starts on its monday`() {
        val monday = HabitMath.weekStart(today)
        assertEquals(1, HabitMath.isoWeekday(monday))
        assertTrue(monday <= today && today - monday < 7)
    }

    private fun due(
        day: Long,
        type: ScheduleType,
        start: Long = 0L,
        end: Long? = null,
        weekdays: List<Int> = emptyList(),
        everyN: Int = 2,
    ) = HabitMath.isScheduledOn(day, start, end, type, weekdays, everyN)

    @Test
    fun `a daily habit is due every day, but never before it was adopted`() {
        assertTrue(due(today, ScheduleType.DAILY, start = 900L))
        assertTrue(due(900L, ScheduleType.DAILY, start = 900L))
        // The day before adoption. This is what stops a habit taken on this
        // morning from showing a year of failures behind it.
        assertFalse(due(899L, ScheduleType.DAILY, start = 900L))
    }

    @Test
    fun `an ended habit stops being due`() {
        assertTrue(due(995L, ScheduleType.DAILY, start = 900L, end = 995L))
        assertFalse(due(996L, ScheduleType.DAILY, start = 900L, end = 995L))
    }

    @Test
    fun `a certain-days habit is due only on those weekdays`() {
        // Mon, Wed, Fri.
        val mwf = listOf(1, 3, 5)
        val monday = HabitMath.weekStart(today)
        assertTrue(due(monday, ScheduleType.WEEKDAYS, weekdays = mwf))
        assertFalse(due(monday + 1, ScheduleType.WEEKDAYS, weekdays = mwf))
        assertTrue(due(monday + 2, ScheduleType.WEEKDAYS, weekdays = mwf))
        assertFalse(due(monday + 6, ScheduleType.WEEKDAYS, weekdays = mwf))
    }

    /** No days chosen must not mean "never" — that reads as a vanished habit. */
    @Test
    fun `a certain-days habit with no days chosen is due every day`() {
        assertTrue(due(today, ScheduleType.WEEKDAYS, weekdays = emptyList()))
        assertTrue(due(today + 1, ScheduleType.WEEKDAYS, weekdays = emptyList()))
    }

    @Test
    fun `an every-n-days habit counts from the day it started`() {
        assertTrue(due(900L, ScheduleType.EVERY_N_DAYS, start = 900L, everyN = 3))
        assertFalse(due(901L, ScheduleType.EVERY_N_DAYS, start = 900L, everyN = 3))
        assertFalse(due(902L, ScheduleType.EVERY_N_DAYS, start = 900L, everyN = 3))
        assertTrue(due(903L, ScheduleType.EVERY_N_DAYS, start = 900L, everyN = 3))
    }

    /** A quota is not a calendar: any day is a legitimate day to do it. */
    @Test
    fun `a times-per-week habit is eligible every day`() {
        assertTrue(due(today, ScheduleType.TIMES_PER_WEEK, start = 900L))
        assertTrue(due(today - 1, ScheduleType.TIMES_PER_WEEK, start = 900L))
    }

    @Test
    fun `the week's quota counts only that week`() {
        val monday = HabitMath.weekStart(today)
        val done = setOf(monday, monday + 2, monday - 1, monday + 7)
        // The Sunday before and the Monday after are other weeks.
        assertEquals(2, HabitMath.weekKept(done, today))
    }

    // --- streaks that respect the schedule ---------------------------------

    /**
     * The regression this whole schedule exists for.
     *
     * A Mon/Wed/Fri habit kept on Monday and Wednesday must read as a streak of
     * two. Counted as a daily habit it reads zero, because Tuesday looks like a
     * miss — so before schedules existed, a three-times-a-week habit broke its
     * own chain four times a week and the number was worthless.
     */
    @Test
    fun `unscheduled days do not break a run`() {
        val mwf = listOf(1, 3, 5)
        val monday = HabitMath.weekStart(today)
        val wednesday = monday + 2
        val isDue = { d: Long -> due(d, ScheduleType.WEEKDAYS, start = monday, weekdays = mwf) }

        val kept = mapOf(monday to true, wednesday to true)
        assertEquals(
            2,
            HabitMath.scheduledStreak(kept, today = wednesday, startDay = monday, isScheduled = isDue),
        )
    }

    @Test
    fun `a missed scheduled day does break it`() {
        val mwf = listOf(1, 3, 5)
        val monday = HabitMath.weekStart(today)
        val friday = monday + 4
        val isDue = { d: Long -> due(d, ScheduleType.WEEKDAYS, start = monday, weekdays = mwf) }

        // Monday kept, Wednesday missed, Friday kept. Only Friday survives.
        val kept = mapOf(monday to true, friday to true)
        assertEquals(
            1,
            HabitMath.scheduledStreak(kept, today = friday, startDay = monday, isScheduled = isDue),
        )
    }

    @Test
    fun `today still does not have to be done yet`() {
        val isDue = { _: Long -> true }
        val kept = mapOf(998L to true, 999L to true)
        assertEquals(
            2,
            HabitMath.scheduledStreak(kept, today = today, startDay = 990L, isScheduled = isDue),
        )
    }

    @Test
    fun `the best scheduled run is found anywhere in the record`() {
        val isDue = { _: Long -> true }
        val kept = mapOf(990L to true, 991L to true, 992L to true, 999L to true, 1000L to true)
        assertEquals(
            3,
            HabitMath.scheduledBestStreak(kept, today = today, startDay = 990L, isScheduled = isDue),
        )
    }

    // --- the heatmap's windows ---------------------------------------------

    @Test
    fun `every heatmap window is whole weeks and ends on a sunday`() {
        HabitMath.HeatPeriod.entries.forEach { period ->
            val days = HabitMath.heatmapDays(period, weekOffset = 0, today = today)
            assertEquals("$period is not whole weeks", period.weeks * 7, days.size)
            assertTrue("$period is not contiguous", days.zipWithNext().all { (a, b) -> b == a + 1 })
            // Chunking into sevens has to give real Monday-to-Sunday weeks, or
            // the weekday axis beside the grid is a lie.
            assertEquals("$period does not start on a Monday", 1, HabitMath.isoWeekday(days.first()))
            assertEquals("$period does not end on a Sunday", 7, HabitMath.isoWeekday(days.last()))
        }
    }

    @Test
    fun `every window includes today`() {
        HabitMath.HeatPeriod.entries.forEach { period ->
            assertTrue(
                "$period does not contain today",
                today in HabitMath.heatmapDays(period, weekOffset = 0, today = today),
            )
        }
    }

    @Test
    fun `paging back moves the window back by whole weeks`() {
        val now = HabitMath.heatmapDays(HabitMath.HeatPeriod.MONTH, 0, today)
        val back = HabitMath.heatmapDays(HabitMath.HeatPeriod.MONTH, -4, today)
        assertEquals(28, now.size)
        assertEquals(28, back.size)
        assertEquals(now.first() - 28, back.first())
        // Four weeks back from a four-week window leaves no gap and no overlap.
        assertEquals(now.first() - 1, back.last())
    }

    /** A year is anchored to today; paging it would take fifty taps to cross. */
    @Test
    fun `the year window ignores the offset`() {
        assertEquals(
            HabitMath.heatmapDays(HabitMath.HeatPeriod.YEAR, 0, today),
            HabitMath.heatmapDays(HabitMath.HeatPeriod.YEAR, -12, today),
        )
    }

    @Test
    fun `paging cannot go past this week or before the habit existed`() {
        // Forward is always clamped to the current week.
        assertEquals(0, HabitMath.clampWeekOffset(3, today, startDay = today - 100))
        // Back stops at the week the habit was adopted, so a man cannot page
        // into years of blank cells from before it existed and read them as
        // failure.
        val startedTwoWeeksAgo = today - 14
        assertEquals(-2, HabitMath.clampWeekOffset(-50, today, startedTwoWeeksAgo))
        assertEquals(-1, HabitMath.clampWeekOffset(-1, today, startedTwoWeeksAgo))
    }

    @Test
    fun `a habit adopted today cannot page back at all`() {
        assertEquals(0, HabitMath.clampWeekOffset(-5, today, startDay = today))
    }

    // --- heat shading -------------------------------------------------------

    @Test
    fun `a tick habit is either fully lit or not at all`() {
        assertEquals(1f, HabitMath.heatLevel(1, targetCount = 1), 0.001f)
        assertEquals(0f, HabitMath.heatLevel(0, targetCount = 1), 0.001f)
    }

    /**
     * Partial progress must not reach full colour.
     *
     * A grid where seven glasses out of eight looks identical to eight is a
     * grid that flatters, and the whole point of looking at it is to see the
     * truth about a month.
     */
    @Test
    fun `partial progress shades below full`() {
        assertTrue(HabitMath.heatLevel(7, targetCount = 8) < 1f)
        assertTrue(HabitMath.heatLevel(7, targetCount = 8) > HabitMath.heatLevel(2, 8))
        assertEquals(1f, HabitMath.heatLevel(8, targetCount = 8), 0.001f)
        // Over the target is still just full, never more.
        assertEquals(1f, HabitMath.heatLevel(99, targetCount = 8), 0.001f)
    }

    @Test
    fun `nothing scheduled at all is a streak of nothing rather than a hang`() {
        val isDue = { _: Long -> false }
        assertEquals(
            0,
            HabitMath.scheduledStreak(emptyMap(), today, startDay = 900L, isScheduled = isDue),
        )
        assertEquals(
            0,
            HabitMath.scheduledBestStreak(emptyMap(), today, startDay = 900L, isScheduled = isDue),
        )
    }
}
