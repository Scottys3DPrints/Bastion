package com.bastion.app.domain

import com.bastion.app.data.db.UrgeLogEntity
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * Turns the urge log into the handful of sentences that actually change
 * behaviour: when you are vulnerable, what precedes it, and where you are
 * strong. Insight is only worth surfacing when it points at a defence.
 */
object Analytics {

    /** Minimum sample before Bastion claims to have spotted anything. */
    /**
     * Below this, Bastion says it does not know yet.
     *
     * Public because the Progress charts have to honour the same bar. A screen
     * that refuses to name a pattern under six logs, then draws a full-height
     * bar chart from one, has not been honest — it has just moved the
     * overclaiming somewhere with no words on it.
     */
    const val MIN_SAMPLE = 6

    /**
     * A headline and, where possible, one tap that acts on it.
     *
     * There is deliberately no explanatory body. An insight that needs a
     * paragraph to justify itself is not an insight — it is an essay, and it
     * will not be read on a phone.
     */
    data class Insight(
        val headline: String,
        val defence: Defence?,
    )

    /** A one-tap action that turns an insight into an actual guard. */
    sealed interface Defence {
        data class TightenAtHour(val hour: Int) : Defence
        data class HardenApp(val packageName: String, val label: String) : Defence
        data object AddPartner : Defence
    }

    fun insights(urges: List<UrgeLogEntity>, appLabels: Map<String, String> = emptyMap()): List<Insight> {
        if (urges.size < MIN_SAMPLE) return listOf(warmUpInsight(urges.size))
        return buildList {
            peakWindow(urges)?.let { add(it) }
            leadingApp(urges, appLabels)?.let { add(it) }
            strongestDay(urges)?.let { add(it) }
            leadingTrigger(urges)?.let { add(it) }
            resistanceRate(urges)?.let { add(it) }
        }
    }

    private fun warmUpInsight(count: Int) = Insight(
        headline = "${MIN_SAMPLE - count} more logs and your patterns appear",
        defence = null,
    )

    /** The three-hour window holding the most urges. */
    private fun peakWindow(urges: List<UrgeLogEntity>): Insight? {
        val byHour = IntArray(24)
        urges.forEach { byHour[it.hour()]++ }
        var bestStart = 0
        var bestCount = -1
        for (start in 0 until 24) {
            val count = (0..2).sumOf { byHour[(start + it) % 24] }
            if (count > bestCount) { bestCount = count; bestStart = start }
        }
        val share = bestCount.toFloat() / urges.size
        if (share < 0.35f) return null
        val end = (bestStart + 3) % 24
        return Insight(
            headline = "${pct(share)} of urges hit ${hour(bestStart)}–${hour(end)}",
            defence = Defence.TightenAtHour(bestStart),
        )
    }

    /** Which app was in the foreground immediately before urges. */
    private fun leadingApp(urges: List<UrgeLogEntity>, labels: Map<String, String>): Insight? {
        val counts = urges.mapNotNull { it.contextApp }.groupingBy { it }.eachCount()
        val (pkg, count) = counts.maxByOrNull { it.value } ?: return null
        val share = count.toFloat() / urges.size
        if (share < 0.3f || count < 4) return null
        val label = labels[pkg] ?: pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        return Insight(
            headline = "$label precedes ${pct(share)} of them",
            defence = Defence.HardenApp(pkg, label),
        )
    }

    /** Named positively on purpose — this one is about where he is strong. */
    private fun strongestDay(urges: List<UrgeLogEntity>): Insight? {
        if (urges.size < 12) return null
        val byDay = urges.groupingBy { it.dayOfWeek() }.eachCount()
        val quietest = DayOfWeek.entries.minByOrNull { byDay[it] ?: 0 } ?: return null
        val busiest = DayOfWeek.entries.maxByOrNull { byDay[it] ?: 0 } ?: return null
        if (busiest == quietest) return null
        val name = quietest.getDisplayName(TextStyle.FULL, Locale.getDefault())
        return Insight(
            headline = "${name}s are your strongest day",
            defence = null,
        )
    }

    private fun leadingTrigger(urges: List<UrgeLogEntity>): Insight? {
        // Split, not grouped whole. Triggers are stored comma-separated now
        // because a slip usually has several, and grouping the raw column would
        // count "Late night,Boredom" as its own trigger — a bucket of one that
        // can never win, while the two real answers inside it go uncounted.
        val counts = urges
            .flatMap { it.trigger.splitValues() }
            .groupingBy { it }
            .eachCount()
        val (trigger, count) = counts.maxByOrNull { it.value } ?: return null
        if (count < 4) return null
        return Insight(
            headline = "\"$trigger\" is your top trigger",
            defence = null,
        )
    }

    private fun resistanceRate(urges: List<UrgeLogEntity>): Insight? {
        val resisted = urges.count { it.resisted }
        val share = resisted.toFloat() / urges.size
        return Insight(
            headline = "Held the line $resisted of ${urges.size} times",
            defence = null,
        )
    }

    private fun UrgeLogEntity.hour(): Int =
        Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).hour

    private fun UrgeLogEntity.dayOfWeek(): DayOfWeek =
        Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).dayOfWeek

    private fun pct(value: Float) = "${(value * 100).toInt()}%"

    private fun hour(h: Int) = when {
        h == 0 -> "midnight"
        h == 12 -> "midday"
        h < 12 -> "${h}am"
        else -> "${h - 12}pm"
    }
}

/**
 * One stored column into the answers it holds.
 *
 * Several of the log's columns carry more than one answer, comma-separated, and
 * every count over them has to split first or the pattern it reports is a
 * pattern in the punctuation.
 */
internal fun String?.splitValues(): List<String> =
    this?.split(',')?.map(String::trim)?.filter(String::isNotBlank).orEmpty()
