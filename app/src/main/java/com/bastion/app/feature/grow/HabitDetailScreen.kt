package com.bastion.app.feature.grow

import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bastion.app.core.design.BastionColors
import com.bastion.app.core.design.BastionScaffold
import com.bastion.app.core.design.SectionLabel
import com.bastion.app.core.design.Space
import com.bastion.app.data.BastionGraph
import com.bastion.app.data.db.HabitEntity
import com.bastion.app.data.db.LogStatus
import com.bastion.app.data.repo.HabitMath
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** How many scheduled days the history list shows before it stops. */
private const val HISTORY_DAYS = 60

/**
 * One habit, on its own screen.
 *
 * It was a bottom sheet, and a sheet was the wrong container: a heatmap with
 * four scales, a range navigator, four counters and a history list do not
 * belong in something you dismiss by dragging down, and the drag kept fighting
 * the horizontal paging inside the grid. A screen also gets a back stack, which
 * is what makes "open a habit, look, come back" behave the way a thumb expects.
 *
 * Ordered the way the questions get asked: is it holding right now (the
 * streaks), how has it gone overall (the counters), what is the shape of it
 * (the heatmap), what actually happened (the history), and only then how it is
 * set up. Settings last on purpose — a man opens this to look, not to fiddle.
 */
@Composable
fun HabitDetailScreen(habitId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = remember { BastionGraph.from(context) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val habits by graph.growth.activeHabits.collectAsStateWithLifecycle(initialValue = emptyList())
    val habit = habits.firstOrNull { it.id == habitId }

    val history by remember(graph, habitId) { graph.growth.historyOf(habitId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    BastionScaffold(
        title = habit?.name ?: "Habit",
        dawnIntensity = 0.3f,
        onBack = onBack,
    ) {
        if (habit == null) {
            // Dropped while it was open, or opened from a stale link. Saying so
            // beats an empty screen that looks like a failure to load.
            Text(
                "This habit is no longer in your regimen.",
                style = MaterialTheme.typography.bodyMedium,
                color = BastionColors.TextTertiary,
            )
            return@BastionScaffold
        }

        val today = remember { LocalDate.now().toEpochDay() }
        val isDue: (Long) -> Boolean = { graph.growth.isDue(habit, it) }

        val kept = remember(history, habit) {
            history.filter {
                it.status == LogStatus.DONE && HabitMath.isComplete(it.count, habit.targetCount)
            }.associate { it.epochDay to true }
        }
        val streak = remember(kept, habit, today) {
            HabitMath.scheduledStreak(kept, today, habit.startEpochDay, isDue)
        }
        val best = remember(kept, habit, today) {
            HabitMath.scheduledBestStreak(kept, today, habit.startEpochDay, isDue)
        }

        val from = habit.startEpochDay.takeIf { it > 0 }
            ?: history.minOfOrNull { it.epochDay } ?: today
        val scheduled = remember(habit, from, today) { (from..today).count(isDue) }
        val done = kept.size
        val skipped = history.count { it.status == LogStatus.SKIPPED }
        val failed = history.count { it.status == LogStatus.FAILED }
        val rate = if (scheduled <= 0) 0 else (done * 100f / scheduled).roundToInt()

        // The header on one card: what it is, how often, and why he took it on.
        // Loose on the gradient these were three unrelated lines of text with no
        // edge to line up against, and the emoji floated at whatever width its
        // glyph happened to be.
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Space.md))
                .background(BastionColors.Surface)
                .padding(Space.lg),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(Space.sm))
                        .background(BastionColors.SurfaceHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(habit.emoji, style = MaterialTheme.typography.headlineSmall)
                }
                Spacer(Modifier.width(Space.md))
                Column {
                    Text(
                        habit.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = BastionColors.TextPrimary,
                    )
                    Text(
                        habit.scheduleLabelPublic(),
                        style = MaterialTheme.typography.bodySmall,
                        color = BastionColors.TextTertiary,
                    )
                }
            }
            if (habit.why.isNotBlank()) {
                Spacer(Modifier.height(Space.md))
                Text(
                    habit.why,
                    style = MaterialTheme.typography.bodyMedium,
                    color = BastionColors.TextSecondary,
                )
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            StreakTile("🔥", streak, "Current streak", BastionColors.BronzeBright, Modifier.weight(1f))
            StreakTile("🏆", best, "Best streak", BastionColors.SageBright, Modifier.weight(1f))
        }

        // Two rows of two, not four across. At four to a row on a narrow phone
        // each box was under eighty pixels wide, which wraps "Skipped" and puts
        // a three-digit count on two lines — the numbers were there and unable
        // to be read, which is the same as not being there.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            StatBox("$rate%", "Kept", BastionColors.SteelBright, Modifier.weight(1f))
            StatBox("$done", "Days done", BastionColors.SageBright, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            StatBox("$skipped", "Skipped", BastionColors.SteelBright, Modifier.weight(1f))
            StatBox("$failed", "Missed", BastionColors.Amber, Modifier.weight(1f))
        }

        SectionLabel("Activity")
        HabitHeatmap(
            habit = habit,
            history = history,
            isDue = isDue,
            onSetDay = { day, status ->
                scope.launch { graph.growth.setHabitStatus(habit, status, day) }
            },
            onClearDay = { day ->
                scope.launch { graph.growth.setHabitDay(habit, day, false) }
            },
        )
        Text(
            "Tap a day to keep it, hold to mark it missed.",
            style = MaterialTheme.typography.bodySmall,
            color = BastionColors.TextTertiary,
        )

        SectionLabel("History")
        HistoryList(habit = habit, history = history, isDue = isDue, today = today)

        SectionLabel("Setup")
        HabitSettings(
            habit = habit,
            onEdit = { scope.launch { graph.growth.updateHabit(it) } },
        )
    }
}

/** A streak, given the room it deserves. It is the number he came to see. */
@Composable
private fun StreakTile(
    emoji: String,
    value: Int,
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(Space.md))
            .background(BastionColors.Surface)
            .padding(Space.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(emoji, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(Space.xs))
        Text(
            value.toString(),
            style = MaterialTheme.typography.headlineMedium,
            color = if (value > 0) accent else BastionColors.TextTertiary,
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = BastionColors.TextTertiary)
    }
}

@Composable
private fun StatBox(
    value: String,
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
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
 * Scheduled days, newest first — and only scheduled ones.
 *
 * Listing every calendar day would bury the record under Tuesdays a
 * three-days-a-week habit was never due on, and each of those would read as a
 * blank line rather than as nothing having been asked.
 */
@Composable
private fun HistoryList(
    habit: HabitEntity,
    history: List<com.bastion.app.data.db.HabitCompletionEntity>,
    isDue: (Long) -> Boolean,
    today: Long,
) {
    val byDay = remember(history) { history.associateBy { it.epochDay } }
    val start = habit.startEpochDay.takeIf { it > 0 } ?: (today - HISTORY_DAYS)
    val days = remember(habit, today) {
        (today downTo maxOf(start, today - HISTORY_DAYS)).filter(isDue)
    }

    if (days.isEmpty()) {
        Text(
            "Nothing scheduled yet.",
            style = MaterialTheme.typography.bodySmall,
            color = BastionColors.TextTertiary,
        )
        return
    }

    Column(Modifier.fillMaxWidth()) {
        days.forEach { day ->
            val log = byDay[day]
            val date = LocalDate.ofEpochDay(day)
            val kept = log?.status == LogStatus.DONE &&
                HabitMath.isComplete(log.count, habit.targetCount)

            Row(
                Modifier.fillMaxWidth().padding(vertical = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} " +
                        "${date.dayOfMonth} " +
                        date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (day == today) BastionColors.TextPrimary else BastionColors.TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                val (mark, tint) = when {
                    kept -> "Kept" to BastionColors.SageBright
                    log?.status == LogStatus.DONE ->
                        "${log.count} of ${habit.targetCount}" to BastionColors.SagePartial
                    log?.status == LogStatus.SKIPPED -> "Skipped" to BastionColors.SteelBright
                    log?.status == LogStatus.FAILED -> "Missed" to BastionColors.Amber
                    day == today -> "Due today" to BastionColors.TextTertiary
                    else -> "—" to BastionColors.TextMuted
                }
                Text(mark, style = MaterialTheme.typography.bodySmall, color = tint)
            }
        }
    }
}

/** "Every day", "Mon, Wed, Fri", "3× per week" — public so the screen can say it. */
internal fun HabitEntity.scheduleLabelPublic(): String = when (scheduleType) {
    com.bastion.app.data.db.ScheduleType.DAILY -> "Every day"
    com.bastion.app.data.db.ScheduleType.TIMES_PER_WEEK -> "$timesPerWeek× per week"
    com.bastion.app.data.db.ScheduleType.EVERY_N_DAYS -> "Every $everyNDays days"
    com.bastion.app.data.db.ScheduleType.WEEKDAYS -> {
        val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        if (weekdays.isEmpty()) "Every day"
        else weekdays.sorted().joinToString(", ") { names[it - 1] }
    }
}
