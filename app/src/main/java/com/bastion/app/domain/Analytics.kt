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
    private const val MIN_SAMPLE = 6

    data class Insight(
        val headline: String,
        val detail: String,
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
        headline = "Still listening",
        detail = "Log a few more moments — urges you resisted count just as much as ones you didn't — " +
            "and Bastion will start showing you your own patterns. ${MIN_SAMPLE - count} to go.",
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
            headline = "${pct(share)} of your urges land between ${hour(bestStart)} and ${hour(end)}",
            detail = "That window is where the fight actually happens. Tightening your guards before it " +
                "starts is worth more than willpower once it has.",
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
            detail = "It isn't the whole story, but it is the doorway. Guarding the feed inside $label " +
                "leaves you the messages and takes away the spiral.",
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
            detail = "Whatever you do differently on a $name — the routine, the people, the sleep — " +
                "is working. Worth copying into ${busiest.getDisplayName(TextStyle.FULL, Locale.getDefault())}s.",
            defence = null,
        )
    }

    private fun leadingTrigger(urges: List<UrgeLogEntity>): Insight? {
        val counts = urges.mapNotNull { it.trigger?.takeIf(String::isNotBlank) }.groupingBy { it }.eachCount()
        val (trigger, count) = counts.maxByOrNull { it.value } ?: return null
        if (count < 4) return null
        return Insight(
            headline = "\"$trigger\" is your most common trigger",
            detail = "Naming it is most of the work. The next step is deciding now — calmly, in daylight — " +
                "exactly what you will do the next time it shows up.",
            defence = null,
        )
    }

    private fun resistanceRate(urges: List<UrgeLogEntity>): Insight? {
        val resisted = urges.count { it.resisted }
        val share = resisted.toFloat() / urges.size
        return Insight(
            headline = "You've held the line ${pct(share)} of the time",
            detail = "$resisted of ${urges.size} logged urges did not become anything. That number is the " +
                "one that compounds.",
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
