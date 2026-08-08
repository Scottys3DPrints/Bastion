package com.bastion.app.feature.grow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bastion.app.core.design.BastionChip
import com.bastion.app.core.design.BastionColors
import com.bastion.app.core.design.Space
import com.bastion.app.data.db.HabitEntity
import com.bastion.app.data.db.ScheduleType
import com.bastion.app.data.db.TimeOfDay
import java.time.LocalDate

/**
 * How a habit is set up: how often, when in the day, how many.
 *
 * Every control writes straight through — there is no Save. A settings screen
 * with a Save button invents a way to lose changes, and none of these is a
 * decision worth confirming: they are all one tap to set and one tap to undo.
 */
@Composable
fun HabitSettings(habit: HabitEntity, onEdit: (HabitEntity) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Label("How often")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            ScheduleType.entries.forEach { type ->
                BastionChip(
                    label = when (type) {
                        ScheduleType.DAILY -> "Daily"
                        ScheduleType.WEEKDAYS -> "Days"
                        ScheduleType.EVERY_N_DAYS -> "Every N"
                        ScheduleType.TIMES_PER_WEEK -> "Weekly"
                    },
                    selected = habit.scheduleType == type,
                    modifier = Modifier.weight(1f),
                ) {
                    // "Every N days" re-anchors to today rather than keeping the
                    // original adoption date. Anchoring to the old date would
                    // silently change which days count and rewrite a streak he
                    // has already been shown.
                    onEdit(
                        habit.copy(
                            scheduleType = type,
                            startEpochDay = if (type == ScheduleType.EVERY_N_DAYS) {
                                LocalDate.now().toEpochDay()
                            } else habit.startEpochDay,
                        )
                    )
                }
            }
        }

        when (habit.scheduleType) {
            ScheduleType.WEEKDAYS -> {
                Spacer(Modifier.height(Space.sm))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { index, letter ->
                        val iso = index + 1
                        val on = iso in habit.weekdays
                        BastionChip(letter, on, Modifier.weight(1f)) {
                            val next = if (on) habit.weekdays - iso else habit.weekdays + iso
                            onEdit(habit.copy(weekdaysCsv = next.sorted().joinToString(",")))
                        }
                    }
                }
            }
            ScheduleType.EVERY_N_DAYS -> {
                Spacer(Modifier.height(Space.sm))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    listOf(2, 3, 4, 7).forEach { n ->
                        BastionChip("$n days", habit.everyNDays == n, Modifier.weight(1f)) {
                            onEdit(
                                habit.copy(
                                    everyNDays = n,
                                    startEpochDay = LocalDate.now().toEpochDay(),
                                )
                            )
                        }
                    }
                }
            }
            ScheduleType.TIMES_PER_WEEK -> {
                Spacer(Modifier.height(Space.sm))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    listOf(1, 2, 3, 5).forEach { n ->
                        BastionChip("$n×", habit.timesPerWeek == n, Modifier.weight(1f)) {
                            onEdit(habit.copy(timesPerWeek = n))
                        }
                    }
                }
                Spacer(Modifier.height(Space.sm))
                Note(
                    "Any days you like — the week has a quota, not a calendar. " +
                        "There is no day-streak on this, because there would be " +
                        "nothing for it to measure."
                )
            }
            ScheduleType.DAILY -> Unit
        }

        Spacer(Modifier.height(Space.lg))
        Label("When in the day")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            TimeOfDay.entries.forEach { slot ->
                BastionChip(slot.label, habit.timeOfDay == slot, Modifier.weight(1f)) {
                    onEdit(habit.copy(timeOfDay = slot))
                }
            }
        }

        Spacer(Modifier.height(Space.lg))
        Label("How many a day")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            listOf(1, 2, 3, 5, 8).forEach { n ->
                BastionChip(if (n == 1) "Once" else "$n×", habit.targetCount == n, Modifier.weight(1f)) {
                    onEdit(habit.copy(targetCount = n))
                }
            }
        }
        Spacer(Modifier.height(Space.sm))
        Note(
            if (habit.counts) {
                "Tap the circle to add one. One tap past ${habit.targetCount} clears the day."
            } else {
                "A single tick. Tap the circle to mark it done."
            }
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = BastionColors.TextSecondary,
    )
    Spacer(Modifier.height(Space.sm))
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = BastionColors.TextTertiary)
}
