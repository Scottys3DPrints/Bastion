package com.bastion.app.feature.track

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * When an urge or a slip happened — as arithmetic, away from the screen.
 *
 * The step this serves had three faults, and the worst of them was not a layout
 * problem. "Earlier today" set the time to ten at night whatever the hour, so
 * tapping it over lunch filed the urge at 10pm — a moment that had not happened
 * yet. Nothing in the flow objected; the save clamps the timestamp but keeps the
 * hour, so the pattern chart quietly gained an evening that was really a
 * lunchtime. A log a man cannot trust is worse than no log, and the whole screen
 * exists to be trusted.
 *
 * Pure so the awkward cases can be checked against a clock on a laptop: the
 * shortcut taken at 00:30, the day that has barely started, the hour that has
 * not arrived.
 */
object LogMoment {

    /** A one-tap answer, and the moment it means. */
    data class Quick(val label: String, val at: LocalDateTime)

    /**
     * The shortcuts, computed from [now] and never landing in the future.
     *
     * "This morning" disappears before the morning has happened rather than
     * offering a time that has not arrived, which is the fault this replaces.
     * A man at 00:30 is offered "Just now" and "Last night" and nothing that
     * pretends the day is further along than it is.
     */
    fun quickMoments(now: LocalDateTime): List<Quick> = buildList {
        add(Quick("Just now", now))
        add(Quick("An hour ago", now.minusHours(1)))

        val thisMorning = now.toLocalDate().atTime(8, 0)
        if (thisMorning.isBefore(now)) add(Quick("This morning", thisMorning))

        // Last night is yesterday's evening, which is always safely past, and is
        // the answer a man reaching for this most often wants.
        add(Quick("Last night", now.toLocalDate().minusDays(1).atTime(22, 0)))
    }

    /** Whether a chosen moment has not happened yet. */
    fun isFuture(date: LocalDate, time: LocalTime, now: LocalDateTime): Boolean =
        LocalDateTime.of(date, time).isAfter(now)

    /**
     * Which hours can be offered on a given day.
     *
     * Every two hours, evenly. The old set was 0, 3, 6, 9, 12, 15, 18, 21, 23 —
     * three-hour steps that turned into a two-hour step at the end, so the
     * spacing of the row said nothing about the spacing of the day.
     */
    fun hourOptions(): List<Int> = (0..22 step 2).toList()

    /**
     * Whether an hour is reachable on [date] given [now].
     *
     * Offered and disabled rather than hidden. A grid that changes length as the
     * day goes on makes a man hunt for a chip that was there an hour ago, and
     * greying it out says *why* it cannot be chosen.
     */
    fun hourAvailable(hour: Int, date: LocalDate, now: LocalDateTime): Boolean =
        !isFuture(date, LocalTime.of(hour, 0), now)

    /**
     * The nearest offered hour at or before [time], so a shortcut lights up the
     * hour row rather than leaving it looking untouched.
     */
    fun nearestHour(time: LocalTime): Int = hourOptions().last { it <= time.hour }

    /**
     * The chosen moment in words: "Yesterday at 10 PM · 14 hours ago".
     *
     * The step had no read-out at all, so with three shortcut chips, fourteen
     * day chips and nine hour chips there was nothing on screen that simply said
     * what had been selected — and the shortcut highlighting was derived from
     * incidental comparisons rather than from the value, so it could light up a
     * chip the man had never tapped.
     */
    fun describe(date: LocalDate, time: LocalTime, now: LocalDateTime): String {
        val day = when (date) {
            now.toLocalDate() -> "Today"
            now.toLocalDate().minusDays(1) -> "Yesterday"
            else -> date.format(DateTimeFormatter.ofPattern("EEEE d MMM"))
        }
        val clock = time.format(DateTimeFormatter.ofPattern("h a"))
        return "$day at $clock · ${ago(LocalDateTime.of(date, time), now)}"
    }

    /** "just now", "3 hours ago", "2 days ago". */
    fun ago(at: LocalDateTime, now: LocalDateTime): String {
        val minutes = Duration.between(at, now).toMinutes()
        return when {
            // Never says "in the future" because the screen never permits one;
            // if a clock change ever produces it, the honest reading of a moment
            // that has not happened is that it is happening now.
            minutes <= 1L -> "just now"
            minutes < 60L -> "$minutes minutes ago"
            minutes < 120L -> "an hour ago"
            minutes < 60L * 24 -> "${minutes / 60} hours ago"
            minutes < 60L * 48 -> "a day ago"
            else -> "${minutes / (60 * 24)} days ago"
        }
    }
}
