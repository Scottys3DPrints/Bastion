package com.bastion.app.feature.grow

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
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
 * The activity grid, at four scales — laid out two different ways on purpose.
 *
 * Every range used to be drawn the same way, as weeks-in-columns, and at short
 * ranges that was simply wrong. A week became a single sixteen-pixel column of
 * seven cells: a narrow strip up the left edge that looked like a rendering
 * fault. A month was four such columns, about seventy pixels of grid on a
 * three-hundred-and-sixty pixel screen, marooned beside an empty half. Nothing
 * ever grew to fit the width, because the cells were a fixed size.
 *
 * So the layout now follows the question being asked:
 *
 *  - **Week and month read as a calendar.** Seven columns, Monday to Sunday,
 *    filling the width, with the date in each cell. At that size there is room
 *    for the number, and a number is what turns "some day in the middle" into
 *    "the 14th" — you can look at a gap and know which day you missed.
 *  - **Quarter and year read as a contribution graph.** Weeks in columns, seven
 *    rows. Twelve weeks as a calendar would be twelve rows deep and a scroll in
 *    itself; as columns it is one compact block that fills the width and shows
 *    the shape of a season at a glance.
 *
 * One mark language across both, so the eye only learns it once. Every state is
 * a *shape* as well as a colour, because kept-against-missed measures 1.2:1 and
 * hue alone leaves a colour-blind man unable to tell a perfect month from a
 * wrecked one:
 *
 *  - **kept** — solid fill
 *  - **part-done** — a bar along the bottom, as wide as the progress
 *  - **missed** — hollow, with a heavy ring
 *  - **skipped** — a dot
 *  - **due, nothing logged** — flat dark fill
 *  - **never due** — a thin outline, so the rhythm of a three-days-a-week habit
 *    reads as a rhythm rather than as a fortnight of gaps
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

    val cell: @Composable (Long, Boolean, Modifier) -> Unit = { day, showDate, modifier ->
        HeatCell(
            day = day,
            habit = habit,
            completion = byDay[day],
            due = isDue(day),
            future = day > today,
            today = day == today,
            showDate = showDate,
            modifier = modifier,
            onTap = {
                if (byDay[day]?.status == LogStatus.DONE) onClearDay(day)
                else onSetDay(day, LogStatus.DONE)
            },
            onLongPress = { onSetDay(day, LogStatus.FAILED) },
        )
    }

    PeriodPicker(selected = period) { period = it }

    if (period != HabitMath.HeatPeriod.YEAR) {
        Spacer(Modifier.height(Space.md))
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

    when (period) {
        HabitMath.HeatPeriod.WEEK, HabitMath.HeatPeriod.MONTH -> CalendarGrid(days, cell)
        HabitMath.HeatPeriod.QUARTER -> ContributionGrid(days, cell, scrolling = false)
        HabitMath.HeatPeriod.YEAR -> ContributionGrid(days, cell, scrolling = true)
    }

    Spacer(Modifier.height(Space.md))
    Legend()
}

private val DOW = listOf("M", "T", "W", "T", "F", "S", "S")

/**
 * Seven columns, Monday first, filling whatever width there is.
 *
 * `weight(1f)` rather than a fixed cell, which is the actual fix: the grid now
 * grows to the screen instead of sitting at whatever size looked right on one
 * device. `aspectRatio(1f)` keeps the cells square as they grow.
 */
@Composable
private fun CalendarGrid(
    days: List<Long>,
    cell: @Composable (Long, Boolean, Modifier) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CAL_GAP)) {
            DOW.forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = BastionColors.TextTertiary,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.height(Space.xs))
        days.chunked(7).forEach { week ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = CAL_GAP),
                horizontalArrangement = Arrangement.spacedBy(CAL_GAP),
            ) {
                week.forEach { day ->
                    cell(day, true, Modifier.weight(1f).aspectRatio(1f))
                }
            }
        }
    }
}

/**
 * Weeks in columns, seven rows — the shape of a season.
 *
 * A quarter fills the width by weight, so twelve weeks spread across the screen
 * rather than clustering at one end. A year is fifty-three columns and fits no
 * phone, so that one keeps a fixed cell and scrolls, with the weekday axis left
 * outside the scrolling region so it stays put while the grid moves under it.
 */
@Composable
private fun ContributionGrid(
    days: List<Long>,
    cell: @Composable (Long, Boolean, Modifier) -> Unit,
    scrolling: Boolean,
) {
    val weeks = days.chunked(7)

    Row(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(end = Space.xs),
            verticalArrangement = Arrangement.spacedBy(GRID_GAP),
        ) {
            DOW.forEach {
                Box(
                    Modifier.height(if (scrolling) YEAR_CELL else QUARTER_ROW).width(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = BastionColors.TextTertiary,
                    )
                }
            }
        }

        val grid: @Composable RowScope.() -> Unit = {
            weeks.forEach { week ->
                Column(
                    if (scrolling) Modifier.width(YEAR_CELL) else Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(GRID_GAP),
                ) {
                    week.forEach { day ->
                        cell(
                            day,
                            false,
                            if (scrolling) Modifier.size(YEAR_CELL)
                            else Modifier.fillMaxWidth().height(QUARTER_ROW),
                        )
                    }
                }
            }
        }

        if (scrolling) {
            val scroll = rememberScrollState()
            // Opens on the present. A year that opened on last August would show
            // an empty corner of the record as though that were the news.
            LaunchedEffect(Unit) { scroll.scrollTo(scroll.maxValue) }
            Row(
                Modifier.weight(1f).horizontalScroll(scroll),
                horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
            ) { grid() }
        } else {
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
            ) { grid() }
        }
    }
}

private val CAL_GAP = 4.dp
private val GRID_GAP = 3.dp
private val QUARTER_ROW = 18.dp
private val YEAR_CELL = 12.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeatCell(
    day: Long,
    habit: HabitEntity,
    completion: HabitCompletionEntity?,
    due: Boolean,
    future: Boolean,
    today: Boolean,
    showDate: Boolean,
    modifier: Modifier,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val status = completion?.status
    val level = if (status == LogStatus.DONE) {
        HabitMath.heatLevel(completion.count, habit.targetCount)
    } else 0f
    val kept = status == LogStatus.DONE && level >= 1f
    val partial = status == LogStatus.DONE && level < 1f
    val shape = RoundedCornerShape(if (showDate) 6.dp else 3.dp)

    val fill = when {
        future -> Color.Transparent
        kept -> BastionColors.Sage
        status == LogStatus.FAILED -> Color.Transparent
        !due -> Color.Transparent
        else -> BastionColors.TrackEmpty
    }

    Box(
        modifier
            .clip(shape)
            .background(fill)
            .then(
                when {
                    // Hollow. The one shape nothing else here uses, so a missed
                    // day is legible with no colour at all.
                    status == LogStatus.FAILED && !future ->
                        Modifier.border(2.dp, BastionColors.Amber, shape)
                    // Never due: drawn rather than left blank, so the rhythm of a
                    // three-days-a-week habit reads as a rhythm.
                    !due && !future ->
                        Modifier.border(1.dp, BastionColors.OutlineStrong, shape)
                    // Today gets a ring whatever its state, so the eye lands on
                    // the present without having to count columns.
                    today -> Modifier.border(2.dp, BastionColors.BronzeBright, shape)
                    else -> Modifier
                }
            )
            .alpha(if (future) 0.3f else 1f)
            .combinedClickable(enabled = !future, onClick = onTap, onLongClick = onLongPress),
        contentAlignment = Alignment.Center,
    ) {
        // Part-done as a bar along the bottom rather than a paler shade. Fading
        // toward the background made a three-eighths day look like an empty one
        // and gave the eye nothing to measure "how much" against; a bar has a
        // length, which is the thing being reported.
        if (partial) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(level.coerceIn(0.15f, 1f))
                    .height(if (showDate) 5.dp else 3.dp)
                    .background(BastionColors.SagePartial)
            )
        }
        if (status == LogStatus.SKIPPED && !future) SkipDot(showDate)

        if (showDate) {
            Text(
                LocalDate.ofEpochDay(day).dayOfMonth.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (today) FontWeight.Bold else FontWeight.Normal,
                // Dark on sage is 6.7:1; the light text that looks right in a
                // mock is 2.5:1 and unreadable in daylight.
                color = when {
                    kept -> BastionColors.MidnightDeep
                    status == LogStatus.FAILED -> BastionColors.Amber
                    future || !due -> BastionColors.TextMuted
                    else -> BastionColors.TextSecondary
                },
            )
        }
    }
}

@Composable
private fun BoxScope.SkipDot(large: Boolean) {
    Box(
        Modifier
            .align(if (large) Alignment.BottomCenter else Alignment.Center)
            .padding(bottom = if (large) 3.dp else 0.dp)
            .size(if (large) 5.dp else 4.dp)
            .clip(RoundedCornerShape(50))
            .background(BastionColors.SteelBright)
    )
}

@Composable
private fun PeriodPicker(
    selected: HabitMath.HeatPeriod,
    onSelect: (HabitMath.HeatPeriod) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Space.sm))
            .background(BastionColors.Surface)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        HabitMath.HeatPeriod.entries.forEach { p ->
            val on = p == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(34.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (on) BastionColors.Sage else Color.Transparent)
                    .clickable { onSelect(p) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    p.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    color = if (on) BastionColors.MidnightDeep else BastionColors.TextSecondary,
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
    fun month(d: LocalDate) = d.month.getDisplayName(TextStyle.SHORT, com.bastion.app.core.AppDates.LOCALE)
    val label = buildString {
        append(month(first)).append(' ').append(first.dayOfMonth)
        append(" – ")
        if (first.month != last.month) append(month(last)).append(' ')
        append(last.dayOfMonth)
        if (first.year != last.year) append(", ").append(last.year)
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavArrow("‹", canGoBack, onBack)
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = BastionColors.TextPrimary,
        )
        NavArrow("›", canGoForward, onForward)
    }
}

@Composable
private fun NavArrow(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(Space.sm))
            .background(if (enabled) BastionColors.Surface else Color.Transparent)
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
 * Flat colour swatches would teach the wrong language now that the cells are
 * solid, barred, hollow and dotted — a hollow amber ring needs something in the
 * key that looks like a hollow amber ring.
 */
@Composable
private fun Legend() {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        LegendItem("Kept") {
            Box(Modifier.size(LEGEND).clip(LEGEND_SHAPE).background(BastionColors.Sage))
        }
        LegendItem("Part") {
            Box(
                Modifier.size(LEGEND).clip(LEGEND_SHAPE).background(BastionColors.TrackEmpty),
                contentAlignment = Alignment.BottomStart,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(0.6f)
                        .height(3.dp)
                        .background(BastionColors.SagePartial)
                )
            }
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
                        .size(4.dp)
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
