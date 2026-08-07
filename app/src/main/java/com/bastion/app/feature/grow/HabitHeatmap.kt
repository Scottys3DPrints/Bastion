package com.bastion.app.feature.grow

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bastion.app.core.design.BastionColors
import com.bastion.app.core.design.Space
import com.bastion.app.data.db.HabitCompletionEntity
import com.bastion.app.data.db.HabitEntity
import com.bastion.app.data.db.LogStatus
import com.bastion.app.data.repo.HabitMath
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * The activity grid, at four scales.
 *
 * A single fixed window answers one question. Four answer different ones: a
 * week says what this week looks like, a month says whether the habit survived
 * a bad patch, a quarter shows where the gaps cluster, and a year says whether
 * the whole thing is holding. The same cells throughout, so the eye learns one
 * language and reads all four.
 *
 * Colour carries meaning rather than decoration, and the distinctions are the
 * ones the journal already makes:
 *
 *  - **Sage, full** — kept, and for a counting habit that means the target was
 *    actually reached. Partial progress gets its own weaker shades, because a
 *    grid where "nearly" and "done" look identical is a grid that flatters.
 *  - **Amber** — missed, and said so.
 *  - **Steel** — skipped on purpose. Not a failure and not pretending to be.
 *  - **Faint outline** — a day the habit was never due. Present so the shape of
 *    a Mon/Wed/Fri habit is legible as a rhythm rather than as gaps.
 *  - **Empty** — due, and nothing recorded.
 *
 * Tap a day to mark it kept, long-press to mark it missed, both bounded by the
 * days actually on screen.
 */
@Composable
fun HabitHeatmap(
    habit: HabitEntity,
    history: List<HabitCompletionEntity>,
    isDue: (Long) -> Boolean,
    onSetDay: (Long, LogStatus) -> Unit,
    onClearDay: (Long) -> Unit,
) {
    var period by remember { mutableStateOf(HabitMath.HeatPeriod.MONTH) }
    var weekOffset by remember { mutableIntStateOf(0) }
    val today = remember { LocalDate.now().toEpochDay() }

    // Paging back and then switching to a shorter window could otherwise leave
    // the offset outside what that window can reach.
    LaunchedEffect(period) {
        weekOffset = HabitMath.clampWeekOffset(weekOffset, today, habit.startEpochDay)
    }

    val byDay = remember(history) { history.associateBy { it.epochDay } }
    val days = remember(period, weekOffset, today) {
        HabitMath.heatmapDays(period, weekOffset, today)
    }

    PeriodPicker(selected = period) { period = it }

    if (period != HabitMath.HeatPeriod.YEAR) {
        Spacer(Modifier.height(Space.sm))
        RangeNavigator(
            days = days,
            canGoBack = HabitMath.clampWeekOffset(
                weekOffset - period.stepWeeks, today, habit.startEpochDay,
            ) != weekOffset,
            canGoForward = weekOffset < 0,
            onBack = {
                weekOffset = HabitMath.clampWeekOffset(
                    weekOffset - period.stepWeeks, today, habit.startEpochDay,
                )
            },
            onForward = {
                weekOffset = HabitMath.clampWeekOffset(
                    weekOffset + period.stepWeeks, today, habit.startEpochDay,
                )
            },
        )
    }

    Spacer(Modifier.height(Space.md))

    val grid: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            // A weekday axis so a column means something. Without it the grid is
            // a texture; with it, "I always miss Sundays" is readable.
            Column(
                verticalArrangement = Arrangement.spacedBy(3.dp),
                horizontalAlignment = Alignment.End,
            ) {
                listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                    Box(Modifier.height(CELL).width(14.dp), contentAlignment = Alignment.Center) {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = BastionColors.TextTertiary,
                        )
                    }
                }
            }
            Spacer(Modifier.width(Space.xs))
            days.chunked(7).forEach { week ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    week.forEach { day ->
                        HeatCell(
                            day = day,
                            habit = habit,
                            completion = byDay[day],
                            due = isDue(day),
                            future = day > today,
                            onTap = {
                                if (byDay[day]?.status == LogStatus.DONE) onClearDay(day)
                                else onSetDay(day, LogStatus.DONE)
                            },
                            onLongPress = { onSetDay(day, LogStatus.FAILED) },
                        )
                    }
                }
            }
        }
    }

    // A year is 53 columns and will not fit any phone, so it scrolls rather
    // than shrinking cells to the point of being unreadable.
    if (period == HabitMath.HeatPeriod.YEAR) {
        val scroll = rememberScrollState()
        LaunchedEffect(Unit) { scroll.scrollTo(scroll.maxValue) }
        Row(Modifier.fillMaxWidth().horizontalScroll(scroll)) { grid() }
    } else {
        grid()
    }

    Spacer(Modifier.height(Space.md))
    Legend()
}

private val CELL = 16.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeatCell(
    day: Long,
    habit: HabitEntity,
    completion: HabitCompletionEntity?,
    due: Boolean,
    future: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val level = if (completion?.status == LogStatus.DONE) {
        HabitMath.heatLevel(completion.count, habit.targetCount)
    } else 0f

    val status = completion?.status
    val shape = RoundedCornerShape(4.dp)

    // Every state gets a shape as well as a colour, and this is the part that
    // matters most in the whole grid.
    //
    // Kept against missed measures 1.21:1 — they are told apart by hue and by
    // nothing else, so to anyone with a red-green deficiency (about one man in
    // twelve) a perfect month and a wrecked one look identical. Colour cannot be
    // the only carrier. So: kept is a solid square, part-done is a smaller solid
    // square inside the cell, missed is hollow with a heavy ring, skipped is a
    // small dot, due-and-unrecorded is a flat dark fill, and a day that was
    // never due is a thin outline. Those read at arm's length in greyscale.
    Box(
        Modifier
            .size(CELL)
            .alpha(if (future) 0.3f else 1f)
            // Tap keeps the day, long press marks it missed. One modifier for
            // both, on the cell itself, so the target is exactly the day it
            // marks rather than a region of the grid.
            .combinedClickable(
                enabled = !future,
                onClick = onTap,
                onLongClick = onLongPress,
            ),
        contentAlignment = Alignment.Center,
    ) {
        when {
            future -> Unit

            status == LogStatus.DONE && level >= 1f ->
                Box(Modifier.size(CELL).clip(shape).background(BastionColors.Sage))

            // Part-done shrinks rather than fading. Alpha toward the background
            // is what made a three-eighths day look like an empty one, and it
            // gave the eye nothing to measure "how much" against.
            status == LogStatus.DONE ->
                Box(
                    Modifier
                        .size(CELL * (0.45f + 0.4f * level))
                        .clip(shape)
                        .background(BastionColors.SagePartial)
                )

            // Hollow, not filled. A ring is the one shape nothing else here uses.
            status == LogStatus.FAILED ->
                Box(
                    Modifier
                        .size(CELL)
                        .clip(shape)
                        .border(3.dp, BastionColors.Amber, shape)
                )

            status == LogStatus.SKIPPED -> {
                Box(Modifier.size(CELL).clip(shape).background(BastionColors.TrackEmpty))
                Box(
                    Modifier
                        .size(CELL * 0.4f)
                        .clip(RoundedCornerShape(50))
                        .background(BastionColors.SteelBright)
                )
            }

            // Due, nothing recorded.
            due -> Box(Modifier.size(CELL).clip(shape).background(BastionColors.TrackEmpty))

            // Never due. Drawn rather than left blank so the rhythm of a
            // three-days-a-week habit reads as a rhythm instead of a fortnight
            // of misses — which only works if the outline can be seen, and
            // OutlineSoft at 1.3:1 could not.
            else ->
                Box(
                    Modifier
                        .size(CELL)
                        .clip(shape)
                        .border(1.dp, BastionColors.OutlineStrong, shape)
                )
        }
    }
}

@Composable
private fun PeriodPicker(
    selected: HabitMath.HeatPeriod,
    onSelect: (HabitMath.HeatPeriod) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        HabitMath.HeatPeriod.entries.forEach { p ->
            val on = p == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(32.dp)
                    .clip(RoundedCornerShape(Space.sm))
                    .background(if (on) BastionColors.SurfaceHigh else Color.Transparent)
                    .clickable { onSelect(p) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    p.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (on) BastionColors.TextPrimary else BastionColors.TextTertiary,
                )
            }
        }
    }
}

/**
 * Which stretch of time is on screen, and the way back through it.
 *
 * The arrows disable at the ends rather than disappearing, so the control does
 * not change shape as you reach the edge of the record.
 */
@Composable
private fun RangeNavigator(
    days: List<Long>,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
) {
    val first = LocalDate.ofEpochDay(days.first())
    val last = LocalDate.ofEpochDay(days.last())
    val label = buildString {
        append(first.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()))
        append(' ').append(first.dayOfMonth)
        append(" – ")
        if (first.month != last.month) {
            append(last.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())).append(' ')
        }
        append(last.dayOfMonth)
        if (first.year != last.year) append(", ").append(last.year)
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavArrow("‹", canGoBack, onBack)
        Text(label, style = MaterialTheme.typography.bodySmall, color = BastionColors.TextSecondary)
        NavArrow("›", canGoForward, onForward)
    }
}

@Composable
private fun NavArrow(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(Space.sm))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) BastionColors.TextPrimary else BastionColors.TextMuted,
        )
    }
}

/**
 * Says what the marks mean, drawn exactly as the grid draws them.
 *
 * The swatches were flat colour squares while the cells had become solid,
 * hollow and dotted — so the key taught the wrong language and a hollow amber
 * ring had nothing in the legend that looked like it.
 */
@Composable
private fun Legend() {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        LegendItem("Kept") {
            Box(Modifier.size(LEGEND).clip(LEGEND_SHAPE).background(BastionColors.Sage))
        }
        LegendItem("Missed") {
            Box(Modifier.size(LEGEND).clip(LEGEND_SHAPE).border(2.dp, BastionColors.Amber, LEGEND_SHAPE))
        }
        LegendItem("Skipped") {
            Box(
                Modifier.size(LEGEND).clip(LEGEND_SHAPE).background(BastionColors.TrackEmpty),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(LEGEND * 0.4f)
                        .clip(RoundedCornerShape(50))
                        .background(BastionColors.SteelBright)
                )
            }
        }
        LegendItem("Due") {
            Box(Modifier.size(LEGEND).clip(LEGEND_SHAPE).background(BastionColors.TrackEmpty))
        }
    }
}

private val LEGEND = 12.dp
private val LEGEND_SHAPE = RoundedCornerShape(3.dp)

@Composable
private fun LegendItem(label: String, mark: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        mark()
        Spacer(Modifier.width(Space.xs))
        Text(label, style = MaterialTheme.typography.labelSmall, color = BastionColors.TextTertiary)
    }
}
