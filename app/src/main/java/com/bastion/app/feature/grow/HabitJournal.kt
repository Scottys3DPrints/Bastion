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
            color = BastionColors.TextTertiary,
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
            val slotComplete = doneInSlot == inSlot.size

            Spacer(Modifier.height(Space.lg))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space.xs),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel(
                    slot.label,
                    color = if (slotComplete) BastionColors.SageBright
                    else BastionColors.TextSecondary,
                )
                // A pill rather than loose text. "2/3" floating beside a heading
                // read as part of the heading; enclosed, it reads as a count,
                // and it gets a background that carries the done/not-done state
                // without relying on colour alone.
                Box(
                    Modifier
                        .clip(RoundedCornerShape(Space.sm))
                        .background(
                            if (slotComplete) BastionColors.SageDeep
                            else BastionColors.SurfaceHigh
                        )
                        .padding(horizontal = Space.sm, vertical = 2.dp),
                ) {
                    Text(
                        "$doneInSlot/${inSlot.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (slotComplete) BastionColors.TextPrimary
                        else BastionColors.TextTertiary,
                    )
                }
            }
            Spacer(Modifier.height(Space.sm))

            // The section's habits on one surface, divided rather than stacked
            // as separate cards. Loose on the gradient they had no left edge to
            // line up against and the list read as floating text; one card per
            // habit was the wall of boxes the whole app was cut back from.
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Space.md))
                    .background(BastionColors.Surface),
            ) {
                inSlot.forEachIndexed { index, habit ->
                    if (index > 0) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 56.dp)
                                .height(1.dp)
                                .background(BastionColors.OutlineSoft)
                        )
                    }
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
}

/**
 * The day at a glance, on its own card, with the number given real size.
 *
 * A drawn ring was the first version and it was worse: at the size that fits
 * above a list it is a smudge, and the number was doing all the work anyway. So
 * the number gets the room instead — display-sized, because this is the one
 * figure the screen exists to deliver and it should be readable from a metre
 * away with the phone on a table.
 *
 * The track underneath uses [BastionColors.TrackEmpty] rather than a raised
 * surface. At 1.2:1 the old track was invisible, so a bar at 20% looked like a
 * bar at 0% — the empty part has to be visible for the filled part to mean
 * anything.
 */
@Composable
private fun SummaryRing(done: Int, total: Int) {
    val fraction = if (total <= 0) 0f else done.toFloat() / total
    val allDone = total > 0 && done == total

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Space.md))
            .background(BastionColors.Surface)
            .padding(Space.lg),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                "${(fraction * 100).toInt()}%",
                style = MaterialTheme.typography.displaySmall,
                color = if (allDone) BastionColors.SageBright else BastionColors.TextPrimary,
            )
            Text(
                if (allDone) "All of it, kept." else "$done of $total kept",
                style = MaterialTheme.typography.bodyMedium,
                color = if (allDone) BastionColors.SageBright else BastionColors.TextSecondary,
                modifier = Modifier.padding(bottom = Space.xs),
            )
        }
        Spacer(Modifier.height(Space.md))
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(BastionColors.TrackEmpty),
        ) {
            if (fraction > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (allDone) BastionColors.Sage else BastionColors.SagePartial),
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

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    date.dayOfWeek.getDisplayName(TextStyle.NARROW, com.bastion.app.core.AppDates.LOCALE),
                    style = MaterialTheme.typography.labelSmall,
                    color = BastionColors.TextTertiary,
                )
                Spacer(Modifier.height(Space.xs))
                // A filled circle for the selected day, a bronze ring for today
                // when it is not the one selected. Selection used to be a faint
                // rounded box at 1.3:1 against the background, which on a phone
                // in daylight was no mark at all — you could not tell which day
                // you were looking at, on a strip whose only job is to say so.
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) BastionColors.Sage else Color.Transparent)
                        .then(
                            if (isToday && !isSelected) {
                                Modifier.border(1.5.dp, BastionColors.Bronze, CircleShape)
                            } else Modifier
                        )
                        .clickable { onSelect(day) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        date.dayOfMonth.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal,
                        color = when {
                            // Dark on sage: 6.7:1. White on sage is only 2.5:1,
                            // so the obvious choice would have been the worse one.
                            isSelected -> BastionColors.MidnightDeep
                            isToday -> BastionColors.BronzeBright
                            else -> BastionColors.TextSecondary
                        },
                    )
                }
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
            .padding(vertical = Space.md, horizontal = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The emoji in a tinted well, so every row starts at the same left edge
        // whatever glyph it carries. Bare emoji vary enough in width that the
        // names beside them did not line up, which reads as sloppiness before
        // anyone works out why.
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(Space.sm))
                .background(if (done) BastionColors.SageDeep else BastionColors.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Text(habit.emoji, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.width(Space.md))

        Column(Modifier.weight(1f)) {
            Text(
                habit.name,
                style = MaterialTheme.typography.bodyLarge,
                // Handled rows step down one level, not two. TextMuted is
                // 3.5:1 — under the 4.5:1 body text needs — so a kept habit's
                // own name became the hardest thing on screen to read, which
                // is a strange reward for having done it.
                color = if (status != null) BastionColors.TextTertiary
                else BastionColors.TextPrimary,
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
                    status == LogStatus.SKIPPED -> BastionColors.SteelBright
                    streak > 0 || weekKept > 0 -> BastionColors.SageBright
                    else -> BastionColors.TextTertiary
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
        status == LogStatus.SKIPPED -> BastionColors.Steel
        else -> BastionColors.TrackEmpty
    }
    val edge = when {
        done -> BastionColors.Sage
        status == LogStatus.FAILED -> BastionColors.Amber
        status == LogStatus.SKIPPED -> BastionColors.Steel
        count > 0 -> BastionColors.SagePartial
        // Outline is 1.7:1 — an unticked circle was a rumour rather than a
        // control, which for the one thing on this screen a man is meant to
        // press is the wrong element to hide.
        else -> BastionColors.OutlineStrong
    }

    // 44dp, which is the smallest target Android's own guidance calls
    // comfortable. It was 34, and it sits next to a row that navigates — so
    // every near-miss opened the habit instead of logging it.
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable { onBump() },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(fill)
                .border(width = 2.dp, color = edge, shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            // Dark glyphs on every filled state. White on sage is 2.5:1 and on
            // amber worse; the dark is 6.7:1 and reads on both.
            when {
                done -> Glyph("✓", BastionColors.MidnightDeep)
                status == LogStatus.FAILED -> Glyph("✕", BastionColors.MidnightDeep)
                // A skip is a deliberate pass and should not look like a miss.
                status == LogStatus.SKIPPED -> Glyph("»", BastionColors.MidnightDeep)
                // Partial progress shows the number rather than an arc. At this
                // size a ring segment is a smudge; a digit is legible.
                target > 1 && count > 0 -> Glyph(count.toString(), BastionColors.SageBright)
            }
        }
    }
}

@Composable
private fun Glyph(text: String, color: Color) {
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
    )
}
