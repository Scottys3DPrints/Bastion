package com.bastion.app.core.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/*
 * Chart marks for Bastion.
 *
 * Rules held throughout, because they are what separates a chart that is read
 * from one that is merely looked at:
 *   - thin marks, recessive grid, no chart junk;
 *   - data-ends rounded and anchored to the baseline, never floating;
 *   - a 2px surface gap between adjacent fills so bars never fuse;
 *   - labels wear text colours, never the series colour;
 *   - one axis, always. Two measures of different scale get two charts.
 */

/** Rounds only the end of a bar that points away from its baseline. */
private fun DrawScope.drawBar(
    left: Float,
    top: Float,
    width: Float,
    bottom: Float,
    color: Color,
    radius: Float,
) {
    if (bottom - top <= 0.5f) return
    val r = radius.coerceAtMost((bottom - top) / 2f).coerceAtMost(width / 2f)
    val path = Path().apply {
        moveTo(left, bottom)
        lineTo(left, top + r)
        quadraticTo(left, top, left + r, top)
        lineTo(left + width - r, top)
        quadraticTo(left + width, top, left + width, top + r)
        lineTo(left + width, bottom)
        close()
    }
    drawPath(path, color)
}

data class Bar(val label: String, val value: Float, val highlight: Boolean = false)

/**
 * Single-series magnitude. One hue on purpose — the axis carries the comparison,
 * so colour is free to stay out of the way. Only the peak is labelled.
 */
@Composable
fun BarChart(
    bars: List<Bar>,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 116.dp,
    color: Color = ChartColors.Magnitude,
    labelEvery: Int = 1,
) {
    if (bars.isEmpty()) return
    val max = bars.maxOf { it.value }.coerceAtLeast(1f)
    val peak = bars.indexOfFirst { it.value == bars.maxOf { b -> b.value } }
    val progress by animateFloatAsState(1f, tween(600), label = "bars")

    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
        ) {
            val gap = 2.dp.toPx()
            val slot = size.width / bars.size
            val barWidth = (slot - gap).coerceAtLeast(1f)
            val radius = 4.dp.toPx()
            val baseline = size.height

            // A single recessive baseline. No horizontal gridlines: with one
            // series and labelled extremes they would be pure decoration.
            drawLine(
                color = ChartColors.Grid,
                start = Offset(0f, baseline),
                end = Offset(size.width, baseline),
                strokeWidth = 1.dp.toPx(),
            )

            bars.forEachIndexed { index, bar ->
                val h = (bar.value / max) * (size.height - 6.dp.toPx()) * progress
                drawBar(
                    left = index * slot + gap / 2f,
                    top = baseline - h,
                    width = barWidth,
                    bottom = baseline,
                    color = if (bar.highlight) color else color.copy(alpha = 0.55f),
                    radius = radius,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            bars.forEachIndexed { index, bar ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (index % labelEvery == 0) {
                        Text(
                            bar.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (index == peak) BastionColors.TextSecondary else BastionColors.TextMuted,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** One day in the calendar. */
enum class DayMark { CLEAN, SLIP, NONE, FUTURE }

/**
 * A month at a glance — the single most useful view in a tracker, because it
 * shows the shape of a month rather than a number.
 *
 * Slips carry a notch as well as a colour: status must never be encoded by hue
 * alone, and a man scanning this should be able to find them without relying on
 * colour vision at all.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
@Composable
fun CalendarMonth(
    month: YearMonth,
    marks: Map<Int, DayMark>,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
) {
    val first = month.atDay(1)
    // Monday-first, matching the weekday labels below.
    val leading = (first.dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7
    val length = month.lengthOfMonth()
    val rows = ((leading + length + 6) / 7)

    // The date is the thing being looked up. A grid of coloured squares shows
    // the shape of a month but cannot answer "what happened on the 14th".
    val measurer = rememberTextMeasurer()
    val dayStyle = MaterialTheme.typography.labelSmall

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = BastionColors.TextMuted,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(7f / rows.toFloat())
        ) {
            val cell = size.width / 7f
            val gap = 3.dp.toPx()
            val side = cell - gap
            val radius = CornerRadius(7.dp.toPx(), 7.dp.toPx())

            for (day in 1..length) {
                val index = leading + day - 1
                val col = index % 7
                val row = index / 7
                val left = col * cell + gap / 2f
                val top = row * (size.height / rows) + gap / 2f
                val date = month.atDay(day)

                val mark = when {
                    date.isAfter(today) -> DayMark.FUTURE
                    else -> marks[day] ?: DayMark.NONE
                }

                val fill = when (mark) {
                    DayMark.CLEAN -> ChartColors.Clean
                    DayMark.SLIP -> ChartColors.Slip
                    DayMark.NONE -> ChartColors.Empty
                    DayMark.FUTURE -> ChartColors.Empty.copy(alpha = 0.45f)
                }

                drawRoundRect(
                    color = fill,
                    topLeft = Offset(left, top),
                    size = Size(side, side.coerceAtMost(size.height / rows - gap)),
                    cornerRadius = radius,
                )

                // Dark ink on the filled cells, muted on the empty ones — the
                // number has to stay legible on every state the cell can take.
                val cellHeight = side.coerceAtMost(size.height / rows - gap)
                val ink = when (mark) {
                    DayMark.CLEAN, DayMark.SLIP -> BastionColors.MidnightDeep
                    DayMark.NONE -> BastionColors.TextMuted
                    DayMark.FUTURE -> BastionColors.TextMuted.copy(alpha = 0.4f)
                }
                val laid = measurer.measure(
                    text = androidx.compose.ui.text.AnnotatedString("$day"),
                    style = dayStyle.copy(color = ink),
                )
                drawText(
                    textLayoutResult = laid,
                    topLeft = Offset(
                        left + (side - laid.size.width) / 2f,
                        top + (cellHeight - laid.size.height) / 2f,
                    ),
                )

                // Secondary encoding for slips, tucked into the corner so it marks
                // the day without striking through the date. Colour alone must
                // never be the only thing carrying meaning.
                if (mark == DayMark.SLIP) {
                    drawCircle(
                        color = BastionColors.MidnightDeep.copy(alpha = 0.9f),
                        radius = 2.6.dp.toPx(),
                        center = Offset(left + side - 7.dp.toPx(), top + 7.dp.toPx()),
                    )
                }

                if (date == today) {
                    val h = side.coerceAtMost(size.height / rows - gap)
                    drawRoundRect(
                        color = BastionColors.BronzeBright,
                        topLeft = Offset(left, top),
                        size = Size(side, h),
                        cornerRadius = radius,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }
        }
    }
}

/** Compact key for the calendar. Shape as well as colour, for the same reason. */
@Composable
fun CalendarLegend(modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        LegendSwatch(ChartColors.Clean, "Clean")
        LegendSwatch(ChartColors.Slip, "Slip", notched = true)
        LegendSwatch(ChartColors.Empty, "No data")
    }
}

@Composable
private fun LegendSwatch(color: Color, label: String, notched: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(10.dp)) {
            drawRoundRect(color = color, cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()))
            if (notched) {
                drawCircle(
                    color = BastionColors.MidnightDeep.copy(alpha = 0.9f),
                    radius = 1.8.dp.toPx(),
                    center = Offset(size.width * 0.72f, size.height * 0.28f),
                )
            }
        }
        Spacer(Modifier.size(5.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = BastionColors.TextMuted)
    }
}

/**
 * The hero number. A ring rather than a chart, because one value comparing
 * itself to a target is not a chart's job.
 */
@Composable
fun StreakRing(
    days: Int,
    caption: String,
    progress: Float,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 168.dp,
    color: Color = BastionColors.Bronze,
) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), tween(900), label = "ring")

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = 9.dp.toPx()
            val inset = stroke / 2f
            val arc = Size(this.size.width - stroke, this.size.height - stroke)

            drawArc(
                color = BastionColors.SurfaceHigh,
                startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(inset, inset), size = arc,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = color,
                startAngle = -90f, sweepAngle = 360f * animated, useCenter = false,
                topLeft = Offset(inset, inset), size = arc,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "$days",
                style = MaterialTheme.typography.displayLarge,
                color = BastionColors.TextPrimary,
            )
            Text(
                caption,
                style = MaterialTheme.typography.labelSmall,
                color = BastionColors.TextMuted,
            )
        }
    }
}

/** A compact number with a caption. Used in rows of three or four. */
@Composable
fun MetricTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    accent: Color = BastionColors.TextPrimary,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineMedium, color = accent)
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = BastionColors.TextMuted,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * A horizontal proportion bar — one measure against its whole. Used for the
 * life-domain scores, where five stacked bars read faster than five rings.
 */
@Composable
fun ProportionBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = ChartColors.Clean,
    track: Color = BastionColors.SurfaceHigh,
    height: androidx.compose.ui.unit.Dp = 7.dp,
) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(700), label = "prop")
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val r = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(color = track, cornerRadius = r)
        if (animated > 0.001f) {
            drawRoundRect(
                color = color,
                size = Size(size.width * animated, size.height),
                cornerRadius = r,
            )
        }
    }
}

/**
 * Streak history as an area chart — the one place change-over-time genuinely
 * needs a line, because the shape of the recovery is the point.
 */
@Composable
fun StreakArea(
    values: List<Int>,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 96.dp,
    color: Color = ChartColors.Clean,
) {
    if (values.size < 2) return
    val max = (values.max()).coerceAtLeast(1)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val stepX = size.width / (values.size - 1).toFloat()
        val usable = size.height - 4.dp.toPx()

        fun pointAt(index: Int): Offset =
            Offset(index * stepX, size.height - (values[index] / max.toFloat()) * usable)

        val line = Path().apply {
            moveTo(pointAt(0).x, pointAt(0).y)
            for (i in 1 until values.size) lineTo(pointAt(i).x, pointAt(i).y)
        }
        val area = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }

        drawPath(area, ChartColors.fill(color))
        drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}
