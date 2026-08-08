package com.bastion.app.feature.grow

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bastion.app.core.design.BastionChip
import com.bastion.app.core.design.BastionColors
import com.bastion.app.core.design.BastionScaffold
import com.bastion.app.core.design.EmptyState
import com.bastion.app.core.design.SectionLabel
import com.bastion.app.core.design.Space
import com.bastion.app.data.BastionGraph
import com.bastion.app.data.db.HabitCompletionEntity
import com.bastion.app.data.db.HabitEntity
import com.bastion.app.data.db.LogStatus
import com.bastion.app.data.repo.HabitMath
import java.time.LocalDate
import kotlin.math.roundToInt

/** The windows the whole regimen can be looked at over. */
private enum class Timeframe(val label: String, val days: Int) {
    WEEK("7 days", 7),
    MONTH("30 days", 30),
    QUARTER("90 days", 90),
    YEAR("365 days", 365),
}

/**
 * The whole regimen, over time.
 *
 * The detail screen answers "is this one habit holding". This answers the
 * question underneath it — whether the regimen as a thing is holding, which is
 * not the same and is not visible from any single habit. Three habits at 90%
 * and one abandoned reads as a good month on every page except this one.
 *
 * Everything here is computed against *scheduled* days. Against calendar days a
 * regimen of three-times-a-week habits kept perfectly would read 43%, and a
 * number that punishes a man for keeping exactly what he agreed to is worse
 * than no number.
 */
@Composable
fun HabitsProgressScreen(onBack: () -> Unit, onOpenHabit: (String) -> Unit) {
    val context = LocalContext.current
    val graph = remember { BastionGraph.from(context) }

    var timeframe by remember { mutableStateOf(Timeframe.MONTH) }

    val habits by graph.growth.activeHabits.collectAsStateWithLifecycle(initialValue = emptyList())
    val today = remember { LocalDate.now().toEpochDay() }
    val since = today - timeframe.days + 1
    val completionsFlow = remember(graph, since) { graph.growth.completionsSince(since) }
    val completions by completionsFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    BastionScaffold(title = "Habit progress", dawnIntensity = 0.3f, onBack = onBack) {
        if (habits.isEmpty()) {
            EmptyState("No habits yet. There is nothing to chart until there is something to keep.")
            return@BastionScaffold
        }

        val stats = remember(habits, completions, timeframe) {
            habits.map { habit ->
                val mine = completions.filter { it.habitId == habit.id }
                HabitStats(
                    habit = habit,
                    scheduled = (maxOf(since, habit.startEpochDay)..today)
                        .count { graph.growth.isDue(habit, it) },
                    done = mine.count {
                        it.status == LogStatus.DONE &&
                            HabitMath.isComplete(it.count, habit.targetCount)
                    },
                    skipped = mine.count { it.status == LogStatus.SKIPPED },
                    failed = mine.count { it.status == LogStatus.FAILED },
                )
            }
        }

        val totalScheduled = stats.sumOf { it.scheduled }
        val totalDone = stats.sumOf { it.done }
        val rate = if (totalScheduled <= 0) 0 else (totalDone * 100f / totalScheduled).roundToInt()

        TimeframePicker(timeframe) { timeframe = it }

        // The headline on its own card with the number at display size, then the
        // three counts beneath it. Four equal boxes across a narrow phone gave
        // each one under eighty pixels, which wrapped "Skipped" onto two lines
        // and buried the one figure the screen exists to deliver.
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
                    "$rate%",
                    style = MaterialTheme.typography.displaySmall,
                    color = if (rate >= 80) BastionColors.SageBright else BastionColors.TextPrimary,
                )
                Text(
                    "$totalDone of $totalScheduled kept",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BastionColors.TextSecondary,
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
                if (rate > 0) {
                    Box(
                        Modifier
                            .fillMaxWidth(rate / 100f)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (rate >= 80) BastionColors.Sage else BastionColors.SagePartial
                            )
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            SummaryCard("$totalDone", "Done", BastionColors.SageBright, Modifier.weight(1f))
            SummaryCard(
                stats.sumOf { it.skipped }.toString(),
                "Skipped",
                BastionColors.SteelBright,
                Modifier.weight(1f),
            )
            SummaryCard(
                stats.sumOf { it.failed }.toString(),
                "Missed",
                BastionColors.Amber,
                Modifier.weight(1f),
            )
        }

        SectionLabel("Daily completion")
        DailyBars(
            habits = habits,
            completions = completions,
            since = since,
            today = today,
            isDue = { h, d -> graph.growth.isDue(h, d) },
        )

        SectionLabel("Every habit")
        stats.sortedByDescending { it.rate }.forEach { s ->
            HabitStatCard(s) { onOpenHabit(s.habit.id) }
        }
    }
}

private data class HabitStats(
    val habit: HabitEntity,
    val scheduled: Int,
    val done: Int,
    val skipped: Int,
    val failed: Int,
) {
    val rate: Float get() = if (scheduled <= 0) 0f else done.toFloat() / scheduled
}

@Composable
private fun TimeframePicker(selected: Timeframe, onSelect: (Timeframe) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        Timeframe.entries.forEach { t ->
            BastionChip(t.label, t == selected, Modifier.weight(1f)) { onSelect(t) }
        }
    }
}

@Composable
private fun SummaryCard(value: String, label: String, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(Space.sm))
            .background(BastionColors.Surface)
            .padding(vertical = Space.md, horizontal = Space.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = accent)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = BastionColors.TextTertiary,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * One bar per day: how much of what was due got done.
 *
 * Height is the proportion, not the count, so a Tuesday with one habit due and
 * kept stands as tall as a Monday with five — which is the honest comparison.
 * A count would make light days look like failures.
 */
@Composable
private fun DailyBars(
    habits: List<HabitEntity>,
    completions: List<HabitCompletionEntity>,
    since: Long,
    today: Long,
    isDue: (HabitEntity, Long) -> Boolean,
) {
    val doneByDay = remember(completions, habits) {
        val targets = habits.associate { it.id to it.targetCount }
        completions
            .filter {
                it.status == LogStatus.DONE &&
                    HabitMath.isComplete(it.count, targets[it.habitId] ?: 1)
            }
            .groupingBy { it.epochDay }
            .eachCount()
    }
    val days = remember(since, today) { (since..today).toList() }
    // A year of bars is a smear at phone width, so long windows sample down to
    // something a thumb can actually distinguish.
    val step = maxOf(1, days.size / 60)
    val shown = days.filterIndexed { i, _ -> i % step == 0 }

    Row(
        Modifier.fillMaxWidth().height(90.dp),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        shown.forEach { day ->
            val due = habits.count { isDue(it, day) }
            val done = doneByDay[day] ?: 0
            val fraction = if (due <= 0) 0f else (done.toFloat() / due).coerceIn(0f, 1f)

            Box(
                Modifier
                    .weight(1f)
                    .height((6 + 84 * fraction).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(
                        when {
                            due == 0 -> BastionColors.OutlineSoft
                            fraction >= 1f -> BastionColors.Sage
                            fraction > 0f -> BastionColors.SagePartial
                            else -> BastionColors.TrackEmpty
                        }
                    )
            )
        }
    }
    Spacer(Modifier.height(Space.xs))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            LocalDate.ofEpochDay(since).toString(),
            style = MaterialTheme.typography.labelSmall,
            color = BastionColors.TextTertiary,
        )
        Text(
            "Today",
            style = MaterialTheme.typography.labelSmall,
            color = BastionColors.TextTertiary,
        )
    }
}

/** One habit's line in the breakdown, tapping through to its own screen. */
@Composable
private fun HabitStatCard(stats: HabitStats, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Space.sm))
            .clickable { onOpen() }
            .padding(vertical = Space.md, horizontal = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stats.habit.emoji, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(
                stats.habit.name,
                style = MaterialTheme.typography.bodyMedium,
                color = BastionColors.TextPrimary,
            )
            Spacer(Modifier.height(Space.xs))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(BastionColors.TrackEmpty),
            ) {
                if (stats.rate > 0f) {
                    Box(
                        Modifier
                            .fillMaxWidth(stats.rate)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                if (stats.rate >= 0.8f) BastionColors.Sage
                                else BastionColors.SagePartial
                            )
                    )
                }
            }
        }
        Spacer(Modifier.width(Space.md))
        Text(
            "${(stats.rate * 100).roundToInt()}%",
            style = MaterialTheme.typography.bodyMedium,
            color = if (stats.rate >= 0.8f) BastionColors.SageBright else BastionColors.TextSecondary,
        )
    }
}
