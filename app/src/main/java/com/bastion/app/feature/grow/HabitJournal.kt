package com.bastion.app.feature.grow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bastion.app.core.design.BastionColors
import com.bastion.app.core.design.SectionLabel
import com.bastion.app.core.design.Space
import com.bastion.app.data.BastionGraph
import com.bastion.app.data.db.HabitCompletionEntity
import com.bastion.app.data.db.HabitEntity
import com.bastion.app.data.db.LogStatus
import com.bastion.app.data.db.ScheduleType
import com.bastion.app.data.db.TimeOfDay
import com.bastion.app.data.repo.HabitMath
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * The journal: what is due, in the order the day happens.
 *
 * The old Regimen tab was a flat list with a tick on each row. It answered "what
 * did I sign up for" and never answered "what is due now", which is the question
 * a man actually opens a habit tracker to ask. Sectioning by hour is the whole
 * difference — the morning ones are still visible at 9pm, but they have plainly
 * gone by, and a flat list cannot say that.
 *
 * Three things came with it, each earning its place rather than arriving as
 * features:
 *
 *  - **A week of days across the top.** Habits get missed and logged later; a
 *    tracker that only accepts today turns an honest catch-up into a lie or a
 *    gap. Seven days back is enough to be useful and short enough that it never
 *    becomes a place to rewrite history.
 *  - **Counts, not just ticks.** "Drink water" is eight times a day. Expressed
 *    as a tick it becomes a single dishonest tap, and the number it was supposed
 *    to encourage disappears.
 *  - **Streaks on the row.** The one number that makes a chain worth not
 *    breaking, and the reason the section exists at all.
 *
 * Bastion's palette throughout, deliberately. The structure is Habitify's; the
 * look is this app's, because a habits screen that looked like a different
 * product would read as something bolted on rather than part of the same promise.
 */

/** Morning first, Anytime last — the order the day actually happens in. */
private val SECTION_ORDER = listOf(
    TimeOfDay.MORNING,
    TimeOfDay.AFTERNOON,
    TimeOfDay.EVENING,
    TimeOfDay.ANYTIME,
)

/** How far back the strip lets you log. See the note above on why it is short. */
private const val STRIP_DAYS = 7

@Composable
fun HabitJournal(
    graph: BastionGraph,
    selectedDay: Long,
    onSelectDay: (Long) -> Unit,
    onOpenHabit: (HabitEntity) -> Unit,
    onBump: (HabitEntity) -> Unit,
    onSetStatus: (HabitEntity, LogStatus) -> Unit,
    habits: List<HabitEntity>,
) {
    val completionsFlow = remember(graph, selectedDay) { graph.growth.completionsOn(selectedDay) }
    val completions by completionsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val byId = remember(completions) { completions.associateBy { it.habitId } }

    val today = remember { LocalDate.now().toEpochDay() }

    // Only what is actually due on the day being looked at. A habit set to
    // Mon/Wed/Fri is not an outstanding failure on a Tuesday, and showing it as
    // one was the whole reason a weekly habit was unusable.
    val due = remember(habits, selectedDay) {
        habits.filter { graph.growth.isDue(it, selectedDay) }
    }
    val doneToday = due.count {
        val c = byId[it.id]
        c?.status == LogStatus.DONE && HabitMath.isComplete(c.count, it.targetCount)
    }

    DateStrip(today = today, selected = selectedDay, onSelect = onSelectDay)
    Spacer(Modifier.height(Space.lg))
    SummaryRing(done = doneToday, total = due.size)

    if (due.isEmpty()) {
        Spacer(Modifier.height(Space.lg))
        Text(
            "Nothing due today. That is the schedule working, not a day off.",
            style = MaterialTheme.typography.bodyMedium,
            color = BastionColors.TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }

    Column(Modifier.fillMaxWidth()) {
        SECTION_ORDER.forEach { slot ->
            val inSlot = due.filter { it.timeOfDay == slot }
            if (inSlot.isEmpty()) return@forEach

            val doneInSlot = inSlot.count {
                val c = byId[it.id]
                c?.status == LogStatus.DONE && HabitMath.isComplete(c.count, it.targetCount)
            }

            Spacer(Modifier.height(Space.lg))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel(
                    slot.label,
                    color = if (doneInSlot == inSlot.size) BastionColors.SageBright
                    else BastionColors.TextMuted,
                )
                // The count, not a progress bar. A bar for three items is
                // decoration; "2/3" is the same information in less space and
                // reads at a glance from across a room.
                Text(
                    "$doneInSlot/${inSlot.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (doneInSlot == inSlot.size) BastionColors.SageBright
                    else BastionColors.TextMuted,
                )
            }
            Spacer(Modifier.height(Space.sm))

            inSlot.forEach { habit ->
                HabitJournalRow(
                    habit = habit,
                    completion = byId[habit.id],
                    graph = graph,
                    selectedDay = selectedDay,
                    onOpen = { onOpenHabit(habit) },
                    onBump = { onBump(habit) },
                    onSetStatus = { onSetStatus(habit, it) },
                )
            }
        }
    }
}

/**
 * The day at a glance, as a proportion rather than a ring.
 *
 * A drawn arc was the first version and it was worse: at the size that fits
 * above a list it is a smudge, and the number underneath was doing all the work
 * anyway. A bar reads at arm's length, matches the proportion bars used
 * elsewhere in the app, and says the same thing.
 */
@Composable
private fun SummaryRing(done: Int, total: Int) {
    val fraction = if (total <= 0) 0f else done.toFloat() / total
    val allDone = total > 0 && done == total

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                if (allDone) "All of it, kept." else "$done of $total kept",
                style = MaterialTheme.typography.bodyMedium,
                color = if (allDone) BastionColors.SageBright else BastionColors.TextSecondary,
            )
            Text(
                "${(fraction * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = if (allDone) BastionColors.SageBright else BastionColors.TextMuted,
            )
        }
        Spacer(Modifier.height(Space.sm))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(BastionColors.SurfaceRaised),
        ) {
            if (fraction > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (allDone) BastionColors.Sage else BastionColors.SageDeep),
                )
            }
        }
    }
}

/**
 * The week, with today on the right.
 *
 * Today sits at the end rather than the middle because the past is the only
 * direction that exists — there is nothing to log tomorrow, and offering the day
 * after next as a tappable cell invites a man to tick something he has not done.
 */
@Composable
private fun DateStrip(today: Long, selected: Long, onSelect: (Long) -> Unit) {
    val days = remember(today) { ((STRIP_DAYS - 1) downTo 0).map { today - it } }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        days.forEach { day ->
            val date = LocalDate.ofEpochDay(day)
            val isSelected = day == selected
            val isToday = day == today

            Column(
                Modifier
                    .clip(RoundedCornerShape(Space.md))
                    .clickable { onSelect(day) }
                    .background(if (isSelected) BastionColors.SurfaceHigh else Color.Transparent)
                    .padding(horizontal = Space.sm, vertical = Space.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = BastionColors.TextMuted,
                )
                Spacer(Modifier.height(Space.xs))
                Text(
                    date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        isSelected -> BastionColors.TextPrimary
                        isToday -> BastionColors.BronzeBright
                        else -> BastionColors.TextSecondary
                    },
                )
            }
        }
    }
}

/**
 * One habit on one day.
 *
 * The row body opens the habit; only the control on the right logs it. Those
 * were the same tap before, which meant there was nowhere to put a detail screen
 * and no way to see a streak without leaving the tab. Separating them is what
 * makes the rest of this possible, and it matches what a man's thumb already
 * expects from every other tracker he has used.
 */
@Composable
private fun HabitJournalRow(
    habit: HabitEntity,
    completion: HabitCompletionEntity?,
    graph: BastionGraph,
    selectedDay: Long,
    onOpen: () -> Unit,
    onBump: () -> Unit,
    onSetStatus: (LogStatus) -> Unit,
) {
    val status = completion?.status
    val count = if (status == LogStatus.DONE) completion.count else 0
    val done = status == LogStatus.DONE && HabitMath.isComplete(count, habit.targetCount)

    val history by remember(graph, habit.id) { graph.growth.historyOf(habit.id) }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    // Scheduled days only, so a Mon/Wed/Fri habit is not broken by a Tuesday.
    val streak = remember(history, habit) {
        val kept = history
            .filter { it.status == LogStatus.DONE && HabitMath.isComplete(it.count, habit.targetCount) }
            .associate { it.epochDay to true }
        HabitMath.scheduledStreak(
            status = kept,
            today = LocalDate.now().toEpochDay(),
            startDay = habit.startEpochDay,
        ) { graph.growth.isDue(habit, it) }
    }

    // A quota has no chain, so it shows the week instead. A day-streak on a
    // habit with no fixed days would be a number about nothing.
    val weekKept = remember(history, habit, selectedDay) {
        if (habit.scheduleType != ScheduleType.TIMES_PER_WEEK) 0
        else HabitMath.weekKept(
            history.filter {
                it.status == LogStatus.DONE && HabitMath.isComplete(it.count, habit.targetCount)
            }.map { it.epochDay }.toSet(),
            selectedDay,
        )
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Space.md))
            .clickable { onOpen() }
            // Swipe right keeps it, swipe left marks it missed — the same
            // gestures the row already invites, without a menu in the way.
            .pointerInput(habit.id, selectedDay) {
                detectHorizontalDragGestures { _, drag ->
                    if (drag > SWIPE_TRIGGER_PX) onSetStatus(LogStatus.DONE)
                    else if (drag < -SWIPE_TRIGGER_PX) onSetStatus(LogStatus.FAILED)
                }
            }
            .padding(vertical = Space.md, horizontal = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(habit.emoji, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.width(Space.md))

        Column(Modifier.weight(1f)) {
            Text(
                habit.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (status != null) BastionColors.TextMuted else BastionColors.TextPrimary,
            )
            val sub = buildString {
                when {
                    habit.scheduleType == ScheduleType.TIMES_PER_WEEK ->
                        append("$weekKept of ${habit.timesPerWeek} this week")
                    streak > 0 -> append("🔥 $streak day${if (streak == 1) "" else "s"}")
                }
                if (habit.counts && status != LogStatus.SKIPPED && status != LogStatus.FAILED) {
                    if (isNotEmpty()) append(" · ")
                    append("$count of ${habit.targetCount}")
                    if (habit.unit.isNotBlank()) append(" ${habit.unit}")
                }
                if (status == LogStatus.SKIPPED) { clear(); append("Skipped") }
                if (status == LogStatus.FAILED) { clear(); append("Missed") }
                if (isEmpty()) append(habit.scheduleLabel())
            }
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    status == LogStatus.FAILED -> BastionColors.Amber
                    status == LogStatus.SKIPPED -> BastionColors.TextMuted
                    streak > 0 || weekKept > 0 -> BastionColors.SageBright
                    else -> BastionColors.TextMuted
                },
                maxLines = 1,
            )
        }

        Spacer(Modifier.width(Space.sm))
        LogControl(
            count = count,
            target = habit.targetCount,
            done = done,
            status = status,
            onBump = onBump,
        )
    }
}

/** How far a drag has to travel before it counts as a swipe. */
private const val SWIPE_TRIGGER_PX = 18f

/** "Every day", "Mon, Wed, Fri", "3× per week" — the schedule in words. */
private fun HabitEntity.scheduleLabel(): String = when (scheduleType) {
    ScheduleType.DAILY -> "Every day"
    ScheduleType.TIMES_PER_WEEK -> "$timesPerWeek× per week"
    ScheduleType.EVERY_N_DAYS -> "Every $everyNDays days"
    ScheduleType.WEEKDAYS -> {
        val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        if (weekdays.isEmpty()) "Every day" else weekdays.sorted().joinToString(", ") { names[it - 1] }
    }
}

/**
 * The tick, or the counter, depending on the habit — and the same control either
 * way.
 *
 * A counting habit fills as it goes and wraps back to empty one tap past its
 * target, which is the undo. A long press hidden in a menu would be more
 * discoverable in a design review and less discoverable with one thumb at 6am.
 */
@Composable
private fun LogControl(
    count: Int,
    target: Int,
    done: Boolean,
    status: LogStatus?,
    onBump: () -> Unit,
) {
    val fill = when {
        done -> BastionColors.Sage
        status == LogStatus.FAILED -> BastionColors.Amber
        else -> BastionColors.SurfaceRaised
    }
    val edge = when {
        done -> BastionColors.Sage
        status == LogStatus.FAILED -> BastionColors.Amber
        status == LogStatus.SKIPPED -> BastionColors.Steel
        count > 0 -> BastionColors.SageDeep
        else -> BastionColors.Outline
    }

    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(fill)
            .border(width = 2.dp, color = edge, shape = CircleShape)
            .clickable { onBump() },
        contentAlignment = Alignment.Center,
    ) {
        when {
            done -> Text(
                "✓",
                color = BastionColors.MidnightDeep,
                style = MaterialTheme.typography.labelMedium,
            )
            status == LogStatus.FAILED -> Text(
                "✕",
                color = BastionColors.MidnightDeep,
                style = MaterialTheme.typography.labelMedium,
            )
            // A skip is a deliberate pass, and it should not look like a miss.
            status == LogStatus.SKIPPED -> Text(
                "»",
                color = BastionColors.SteelBright,
                style = MaterialTheme.typography.labelMedium,
            )
            // Partial progress shows the number rather than a fraction of a ring.
            // At 34dp a ring arc is a smudge; a digit is legible.
            target > 1 && count > 0 -> Text(
                count.toString(),
                color = BastionColors.SageBright,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/**
 * Twelve weeks, seven rows, one column per week.
 *
 * A month grid shows a month, and a habit's whole point is the shape over
 * longer than that — whether the gaps cluster on weekends, whether it fell apart
 * in one bad fortnight or has been fraying all along. Weeks as columns fits a
 * quarter into a sheet without scrolling.
 *
 * Cells are tappable. Logging a day from here is the honest version of catching
 * up, and it is bounded by what is actually on screen rather than by a date
 * picker that would let a man fill in a year he did not live.
 */
@Composable
fun HabitCalendar(
    history: List<HabitCompletionEntity>,
    habit: HabitEntity,
    weeks: Int,
    onToggleDay: (Long, Boolean) -> Unit,
) {
    val today = remember { LocalDate.now().toEpochDay() }
    val counts = remember(history) { history.associate { it.epochDay to it.count } }
    val days = remember(today, weeks) { HabitMath.calendarDays(today, weeks) }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        days.chunked(7).forEach { week ->
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                week.forEach { day ->
                    val c = counts[day] ?: 0
                    val complete = HabitMath.isComplete(c, habit.targetCount)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(12.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                when {
                                    complete -> BastionColors.Sage
                                    c > 0 -> BastionColors.SageDeep
                                    else -> BastionColors.SurfaceRaised
                                }
                            )
                            .clickable { onToggleDay(day, !complete) },
                    )
                }
            }
        }
    }
}

/** One number and its name, for the detail sheet's header. */
@Composable
fun HabitStat(value: String, label: String, accent: Color = BastionColors.TextPrimary) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = accent)
        Spacer(Modifier.height(Space.xs))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = BastionColors.TextMuted,
            textAlign = TextAlign.Center,
        )
    }
}
