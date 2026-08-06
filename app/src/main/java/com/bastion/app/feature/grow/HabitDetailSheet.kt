package com.bastion.app.feature.grow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bastion.app.core.design.BastionColors
import com.bastion.app.core.design.QuietButton
import com.bastion.app.core.design.SectionLabel
import com.bastion.app.core.design.Space
import com.bastion.app.data.BastionGraph
import com.bastion.app.data.db.HabitEntity
import com.bastion.app.data.db.TimeOfDay
import com.bastion.app.data.repo.HabitMath
import java.time.LocalDate
import kotlin.math.roundToInt

/** How much history the calendar shows. A quarter — see [HabitCalendar]. */
private const val CALENDAR_WEEKS = 12

/**
 * One habit, and whether it is actually holding.
 *
 * Three numbers and a shape. The numbers are the ones a man checks; the shape is
 * the one that tells him something he did not already know — that the gaps fall
 * on weekends, or that a good month is carrying a bad fortnight.
 *
 * The rate is measured from the day the habit was adopted, not from the edge of
 * the window, so a habit started on Tuesday does not open at 4% and read as
 * failure. See [HabitMath.completionRate].
 */
@Composable
fun HabitDetailSheet(
    graph: BastionGraph,
    habit: HabitEntity,
    onEdit: (HabitEntity) -> Unit,
    onToggleDay: (Long, Boolean) -> Unit,
    onDrop: () -> Unit,
) {
    val history by remember(graph, habit.id) { graph.growth.historyOf(habit.id) }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val today = remember { LocalDate.now().toEpochDay() }
    val complete = remember(history, habit.targetCount) {
        HabitMath.completeDays(history.associate { it.epochDay to it.count }, habit.targetCount)
    }
    val streak = remember(complete, today) { HabitMath.currentStreak(complete, today) }
    val best = remember(complete) { HabitMath.bestStreak(complete) }
    val rate = remember(complete, history, today) {
        // From adoption, or from the first thing ever logged if the habit predates
        // the column that records when it was taken on.
        val from = history.minOfOrNull { it.epochDay } ?: today
        HabitMath.completionRate(complete, from, today)
    }

    Text(
        "${habit.emoji}  ${habit.name}",
        style = MaterialTheme.typography.headlineSmall,
        color = BastionColors.TextPrimary,
    )
    if (habit.why.isNotBlank()) {
        Spacer(Modifier.height(Space.sm))
        Text(habit.why, style = MaterialTheme.typography.bodyMedium, color = BastionColors.TextSecondary)
    }

    Spacer(Modifier.height(Space.section))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        HabitStat(
            streak.toString(),
            "day streak",
            if (streak > 0) BastionColors.SageBright else BastionColors.TextMuted,
        )
        HabitStat(best.toString(), "best ever", BastionColors.BronzeBright)
        HabitStat("${(rate * 100).roundToInt()}%", "kept", BastionColors.SteelBright)
    }

    Spacer(Modifier.height(Space.section))
    SectionLabel("The last twelve weeks")
    Spacer(Modifier.height(Space.sm))
    HabitCalendar(history = history, habit = habit, weeks = CALENDAR_WEEKS, onToggleDay = onToggleDay)
    Spacer(Modifier.height(Space.sm))
    Text(
        "Tap a day to log it. Catching up honestly beats a gap you meant to fill.",
        style = MaterialTheme.typography.bodySmall,
        color = BastionColors.TextMuted,
    )

    Spacer(Modifier.height(Space.section))
    SectionLabel("When")
    Spacer(Modifier.height(Space.sm))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        TimeOfDay.entries.forEach { slot ->
            SlotChip(
                label = slot.label,
                selected = habit.timeOfDay == slot,
                modifier = Modifier.weight(1f),
            ) { onEdit(habit.copy(timeOfDay = slot)) }
        }
    }

    Spacer(Modifier.height(Space.section))
    SectionLabel("How many a day")
    Spacer(Modifier.height(Space.sm))
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        listOf(1, 2, 3, 5, 8).forEach { n ->
            SlotChip(
                label = if (n == 1) "Once" else "$n×",
                selected = habit.targetCount == n,
                modifier = Modifier.weight(1f),
            ) { onEdit(habit.copy(targetCount = n)) }
        }
    }
    Spacer(Modifier.height(Space.sm))
    Text(
        if (habit.counts) {
            "Tap the circle to add one. One tap past ${habit.targetCount} clears the day."
        } else {
            "A single tick. Tap the circle to mark it done."
        },
        style = MaterialTheme.typography.bodySmall,
        color = BastionColors.TextMuted,
    )

    Spacer(Modifier.height(Space.section))
    QuietButton("Drop this habit", onDrop, Modifier.fillMaxWidth())
}

/**
 * A chip that is a chip, not a bordered box.
 *
 * Written here rather than pulled from the design system because nothing there
 * is quite this — ChoiceRow owns a whole row and takes an enum, and these are
 * two independent little groups sitting inside a sheet.
 */
@Composable
private fun SlotChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Space.md)
    Box(
        modifier
            .height(38.dp)
            .clip(shape)
            .background(if (selected) BastionColors.SurfaceHigh else BastionColors.Surface)
            .border(
                width = 1.dp,
                color = if (selected) BastionColors.Sage else BastionColors.OutlineSoft,
                shape = shape,
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) BastionColors.TextPrimary else BastionColors.TextMuted,
        )
    }
}
