package com.bastion.app.feature.track

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bastion.app.core.design.Bar
import com.bastion.app.core.design.BarChart
import com.bastion.app.core.design.BastionBottomSheet
import com.bastion.app.core.design.BastionCard
import com.bastion.app.core.design.BastionRow
import com.bastion.app.core.design.ProportionBar
import com.bastion.app.core.design.BastionScaffold
import com.bastion.app.core.design.BastionColors
import com.bastion.app.core.design.BastionFilterChip
import com.bastion.app.core.design.CalendarLegend
import com.bastion.app.core.design.CalendarMonth
import com.bastion.app.core.design.ChartColors
import com.bastion.app.core.design.DawnBackground
import com.bastion.app.core.design.DayMark
import com.bastion.app.core.design.MetricTile
import com.bastion.app.core.design.PrimaryButton
import com.bastion.app.core.design.QuietButton
import com.bastion.app.core.design.EmptyState
import com.bastion.app.core.design.Section
import com.bastion.app.core.design.SectionLabel
import com.bastion.app.core.design.Space
import com.bastion.app.core.design.StreakRing
import com.bastion.app.data.BastionGraph
import com.bastion.app.domain.splitValues
import com.bastion.app.data.content.BenefitCard
import com.bastion.app.data.db.DayStatus
import com.bastion.app.data.db.UrgeLogEntity
import com.bastion.app.data.prefs.Settings
import com.bastion.app.data.repo.JourneyState
import com.bastion.app.domain.Analytics
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter


private val MILESTONES = listOf(7, 14, 30, 60, 90, 180, 365)

@OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)
@Composable
fun TrackScreen(faithMode: Boolean, onOpenProfile: () -> Unit) {
    val context = LocalContext.current
    val graph = remember { BastionGraph.from(context) }
    val scope = rememberCoroutineScope()

    val state by graph.journey.state.collectAsStateWithLifecycle(initialValue = JourneyState())
    val settings by graph.settings.settings.collectAsStateWithLifecycle(initialValue = Settings())
    val urges by graph.journey.recentUrges.collectAsStateWithLifecycle(initialValue = emptyList())
    val days by graph.journey.allDays.collectAsStateWithLifecycle(initialValue = emptyList())

    var month by remember { mutableStateOf(YearMonth.now()) }
    var benefits by remember { mutableStateOf<List<BenefitCard>>(emptyList()) }
    var showLogSheet by remember { mutableStateOf(false) }
    var showRecovery by remember { mutableStateOf(false) }
    var showLogChoice by remember { mutableStateOf(false) }
    var editingDay by remember { mutableStateOf<LocalDate?>(null) }

    LaunchedEffect(state.currentStreak) {
        benefits = graph.content.unlockedBenefits(state.currentStreak)
    }

    val slipDays = remember(days) {
        days.filter { it.status == DayStatus.SLIP }.map { it.epochDay }.toSet()
    }
    // The effective start, not the install date: backfilled history moves it,
    // and the calendar has to grey the same days the counts ignore.
    val marks = remember(month, slipDays, state.startEpochDay) {
        buildMarks(month, slipDays, state.startEpochDay)
    }
    val nextMilestone = remember(state.currentStreak) {
        MILESTONES.firstOrNull { it > state.currentStreak }
    }
    val insights = remember(urges) { Analytics.insights(urges).take(2) }

    BastionScaffold(
        title = "Progress",
        dawnIntensity = 0.45f,
        action = {
            IconButton(onClick = onOpenProfile) {
                Icon(
                    Icons.Filled.AccountCircle,
                    contentDescription = "You, partner and settings",
                    tint = BastionColors.TextMuted,
                )
            }
        },
        floatingAction = {
            // One action, one place. There were two inline buttons competing
            // mid-scroll ("Log urge" and "Log slip"), which asked the user to
            // classify what happened before they had said anything at all.
            ExtendedFloatingActionButton(
                onClick = { showLogChoice = true },
                containerColor = BastionColors.Bronze,
                contentColor = BastionColors.MidnightDeep,
            ) { Text("Log", style = MaterialTheme.typography.labelLarge) }
        },
    ) {
        // The hero, and the only one here.
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            StreakRing(
                days = state.currentStreak,
                caption = if (state.currentStreak == 1) "DAY" else "DAYS",
                progress = nextMilestone?.let { state.currentStreak.toFloat() / it } ?: 1f,
            )
            Spacer(Modifier.height(Space.md))
            Text(
                nextMilestone?.let {
                    val left = it - state.currentStreak
                    "$left ${if (left == 1) "day" else "days"} to $it"
                } ?: "Beyond every milestone",
                style = MaterialTheme.typography.labelMedium,
                color = BastionColors.TextMuted,
            )
            Spacer(Modifier.height(Space.lg))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                MetricTile("${state.longestStreak}", "BEST", accent = BastionColors.BronzeBright)
                MetricTile("${state.totalCleanDays}", "CLEAN", accent = ChartColors.Clean)
                MetricTile(
                    if (urges.isEmpty()) "—" else "${(urges.count { it.resisted } * 100) / urges.size}%",
                    "HELD",
                )
                MetricTile("${state.rank.tier}", "RANK", accent = BastionColors.BronzeBright)
            }
        }

        // --- Your month -------------------------------------------------
        Section(
            label = "Your month",
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { month = month.minusMonths(1) }) {
                        Icon(
                            Icons.Filled.ChevronLeft,
                            contentDescription = "Previous month",
                            tint = BastionColors.TextMuted,
                        )
                    }
                    Text(
                        month.format(DateTimeFormatter.ofPattern("MMM yyyy")),
                        style = MaterialTheme.typography.labelMedium,
                        color = BastionColors.TextSecondary,
                    )
                    IconButton(
                        onClick = { if (month < YearMonth.now()) month = month.plusMonths(1) },
                        enabled = month < YearMonth.now(),
                    ) {
                        Icon(
                            Icons.Filled.ChevronRight,
                            contentDescription = "Next month",
                            tint = BastionColors.TextMuted,
                        )
                    }
                }
            },
        ) {
            // The hint sits above the calendar, not beside the legend: the Log
            // button floats over the bottom-right of this section and was
            // clipping it to "Tap...".
            Text(
                "Tap a day to log it.",
                style = MaterialTheme.typography.labelSmall,
                color = BastionColors.TextMuted,
            )
            Spacer(Modifier.height(Space.md))
            CalendarMonth(month = month, marks = marks, onDayClick = { editingDay = it })
            Spacer(Modifier.height(Space.md))
            CalendarLegend()
        }

        // --- Patterns ---------------------------------------------------
        //
        // Two charts and up to two insights were four separate bordered cards.
        // They answer one question between them, so they are one section.
        Section("Patterns") {
            if (urges.isEmpty()) {
                EmptyState(
                    text = "Log an urge and Bastion starts finding the hours and days it hits hardest.",
                )
            } else {
                SectionLabel("When urges hit")
                Spacer(Modifier.height(Space.md))
                BarChart(bars = hourBuckets(urges), labelEvery = 1)
                Spacer(Modifier.height(Space.section))
                SectionLabel("Hardest days")
                Spacer(Modifier.height(Space.md))
                BarChart(bars = weekdayBars(urges), labelEvery = 1)

                // The dimensions the longer log pays for.
                //
                // Held to the same bar as every other claim on this screen:
                // Analytics refuses to name a pattern under six logs, and these
                // charts must not undercut it. One entry rendered as a
                // full-height bar reads as "this is your problem" on a sample of
                // one — the app sounding certain about a man's life from a
                // single Tuesday is exactly how it loses the right to be
                // believed later.
                val enoughToSpeak = urges.size >= Analytics.MIN_SAMPLE

                val feelings = if (enoughToSpeak) {
                    topCounts(urges.flatMap { it.feelingList() })
                } else emptyList()
                if (feelings.isNotEmpty()) {
                    Spacer(Modifier.height(Space.section))
                    SectionLabel("What you were carrying")
                    Spacer(Modifier.height(Space.md))
                    BarChart(bars = feelings, labelEvery = 1)
                }

                val places = if (enoughToSpeak) topCounts(urges.flatMap { it.place.splitValues() }) else emptyList()
                if (places.isNotEmpty()) {
                    Spacer(Modifier.height(Space.section))
                    SectionLabel("Where it finds you")
                    Spacer(Modifier.height(Space.md))
                    BarChart(bars = places, labelEvery = 1)
                }

                val sought = urges.count { it.soughtOut == true }
                val found = urges.count { it.soughtOut == false }
                if (enoughToSpeak && sought + found > 0) {
                    Spacer(Modifier.height(Space.section))
                    SectionLabel("Found you, or went looking")
                    Spacer(Modifier.height(Space.md))
                    Text(
                        // Stated as a fact about the split, without a verdict
                        // attached either way. Both numbers point at different
                        // work: one is a guard to tighten, the other is not.
                        "$found found you · $sought you went looking for",
                        style = MaterialTheme.typography.bodyMedium,
                        color = BastionColors.TextSecondary,
                    )
                    Spacer(Modifier.height(Space.sm))
                    com.bastion.app.core.design.ProportionBar(
                        fraction = found.toFloat() / (found + sought),
                    )
                }

                val helped = if (enoughToSpeak) topCounts(urges.flatMap { it.whatHelped.splitValues() }) else emptyList()
                if (helped.isNotEmpty()) {
                    Spacer(Modifier.height(Space.section))
                    SectionLabel("What has actually worked")
                    Spacer(Modifier.height(Space.md))
                    BarChart(bars = helped, labelEvery = 1)
                }

                insights.forEach { insight ->
                    Spacer(Modifier.height(Space.section))
                    Text(
                        insight.headline,
                        style = MaterialTheme.typography.titleSmall,
                        color = BastionColors.TextPrimary,
                    )
                    insight.defence?.let {
                        Spacer(Modifier.height(Space.xs))
                        Text(
                            when (it) {
                                is Analytics.Defence.TightenAtHour -> "Tighten guards before then →"
                                is Analytics.Defence.HardenApp -> "Guard ${it.label} →"
                                Analytics.Defence.AddPartner -> "Add a partner →"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = BastionColors.BronzeBright,
                        )
                    }
                }
            }
        }

        // --- Unlocked ---------------------------------------------------
        if (benefits.isNotEmpty()) {
            Section("Unlocked") {
                benefits.take(6).forEach { card -> BenefitRow(card) }
            }
        }

        BecomingSection(graph)
    }

    if (showLogChoice) {
        LogChoiceSheet(
            onResisted = { showLogChoice = false; showLogSheet = true },
            onSlipped = { showLogChoice = false; showRecovery = true },
            onDismiss = { showLogChoice = false },
        )
    }

    if (showLogSheet) {
        BastionBottomSheet(onDismiss = { showLogSheet = false }) {
            LogFlow(
                resisted = true,
                onCancel = { showLogSheet = false },
                onSave = { entry ->
                    scope.launch {
                        graph.journey.saveLog(entry, heldContextApp = true)
                        showLogSheet = false
                    }
                },
            )
        }
    }

    // Tapping a day in the calendar.
    //
    // A day already marked as a slip opens the small correction sheet, because
    // the reason to tap one is almost always a mis-tap rather than a wish to go
    // back through it. A day not yet marked opens the same grace-first flow the
    // Log button opens — the two used to differ, so whether a man met "You're
    // human." or a blunt "Log a slip" button depended on which control he
    // happened to reach for. Same event, same treatment.
    editingDay?.let { date ->
        val existing = remember(days, date) {
            days.firstOrNull { it.epochDay == date.toEpochDay() }
        }
        BastionBottomSheet(onDismiss = { editingDay = null }) {
            if (existing?.status == DayStatus.SLIP) {
                DayLogSheet(
                    date = date,
                    note = existing.note,
                    onUpdateNote = { note ->
                        scope.launch {
                            graph.journey.logSlip(date.toEpochDay(), note)
                            editingDay = null
                        }
                    },
                    onClean = {
                        scope.launch {
                            graph.journey.clearDay(date.toEpochDay())
                            editingDay = null
                        }
                    },
                )
            } else {
                RecoveryFlow(
                    faithMode = faithMode,
                    rankName = state.rank.displayName(faithMode),
                    date = date,
                    onDone = { entry ->
                        scope.launch {
                            graph.journey.saveLog(entry)
                            editingDay = null
                        }
                    },
                )
            }
        }
    }

    if (showRecovery) {
        BastionBottomSheet(onDismiss = { showRecovery = false }) {
            RecoveryFlow(
                faithMode = faithMode,
                rankName = state.rank.displayName(faithMode),
                onDone = { entry ->
                    scope.launch {
                        graph.journey.saveLog(entry)
                        showRecovery = false
                    }
                },
            )
        }
    }
}

/**
 * Correcting a day that is already marked.
 *
 * This used to be the whole calendar path: a terse sheet with a "Log a slip"
 * button that wrote the day and closed. Recording a slip now goes through the
 * same grace-first flow as every other route to the same event, so what is left
 * here is only the correction — undo first, because the commonest reason to open
 * a marked day is a mis-tap rather than a wish to revisit the night.
 */
@Composable
private fun DayLogSheet(
    date: LocalDate,
    note: String?,
    onUpdateNote: (String?) -> Unit,
    onClean: () -> Unit,
) {
    var text by remember(date) { mutableStateOf(note.orEmpty()) }
    val today = LocalDate.now()

    Column {
        Text(
            when (date) {
                today -> "Today"
                today.minusDays(1) -> "Yesterday"
                else -> date.format(DateTimeFormatter.ofPattern("EEEE d MMMM"))
            },
            style = MaterialTheme.typography.titleLarge,
            color = BastionColors.TextPrimary,
        )
        Spacer(Modifier.height(Space.sm))
        Text(
            "Logged as a slip.",
            style = MaterialTheme.typography.bodySmall,
            color = BastionColors.Amber,
        )

        Spacer(Modifier.height(Space.lg))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("What was going on? (optional)") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Space.md),
            minLines = 2,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = BastionColors.Bronze,
                unfocusedBorderColor = BastionColors.Outline,
                focusedTextColor = BastionColors.TextPrimary,
                unfocusedTextColor = BastionColors.TextPrimary,
                cursorColor = BastionColors.Bronze,
            ),
        )

        Spacer(Modifier.height(Space.lg))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            PrimaryButton("Mark clean", onClean, Modifier.weight(1f))
            QuietButton(
                "Update note",
                { onUpdateNote(text.trim().takeIf(String::isNotBlank)) },
                Modifier.weight(1f),
                BastionColors.Amber,
            )
        }
    }
}

/**
 * The one question the Log action has to ask.
 *
 * Two inline buttons used to sit mid-scroll — "Log urge" and "Log slip" — which
 * made the user classify the night before saying anything about it, in the
 * exact typography as everything else on the screen. Asking once, in a sheet,
 * both simplifies the page and puts the harder answer behind a beat of thought.
 */
@Composable
private fun LogChoiceSheet(
    onResisted: () -> Unit,
    onSlipped: () -> Unit,
    onDismiss: () -> Unit,
) {
    BastionBottomSheet(onDismiss = onDismiss, title = "What happened?") {
        PrimaryButton("I felt it and held", onResisted, Modifier.fillMaxWidth())
        Spacer(Modifier.height(Space.md))
        QuietButton("I slipped", onSlipped, Modifier.fillMaxWidth(), BastionColors.Amber)
    }
}

/**
 * The man he is becoming — four weeks of what was actually done.
 *
 * Lived in Grow behind a segment called "Becoming", which is the app's private
 * word for it and told a stranger nothing. It is a record of the past four
 * weeks, and this screen is where the app already says the past lives.
 */
@Composable
private fun BecomingSection(graph: BastionGraph) {
    val habits by graph.growth.allHabits.collectAsStateWithLifecycle(initialValue = emptyList())
    val badges by graph.growth.badges.collectAsStateWithLifecycle(initialValue = emptyList())
    val since = remember { LocalDate.now().toEpochDay() - 28 }
    val completionsFlow = remember(graph, since) { graph.growth.completionsSince(since) }
    val completions by completionsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val scores = remember(completions, habits) { graph.growth.domainScores(completions, habits) }

    Section("The man you're becoming") {
        Text(
            "Last four weeks. Moves slowly on purpose.",
            style = MaterialTheme.typography.bodySmall,
            color = BastionColors.TextMuted,
        )
        Spacer(Modifier.height(Space.lg))

        if (scores.isEmpty()) {
            EmptyState("Keep a habit or two and this fills in.")
        }

        scores.entries.sortedByDescending { it.value }.forEach { (domain, score) ->
            Column(Modifier.padding(vertical = Space.sm)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(domain, style = MaterialTheme.typography.titleSmall, color = BastionColors.TextPrimary)
                    Text(
                        "${(score * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = BastionColors.BronzeBright,
                    )
                }
                Spacer(Modifier.height(Space.sm))
                ProportionBar(fraction = score)
            }
        }
    }

    if (badges.isNotEmpty()) {
        Section("Badges") {
            badges.forEach { badge ->
                BastionRow(title = badge.name, leading = {
                    Text("◇", color = BastionColors.BronzeBright)
                })
            }
        }
    }
}

// --- data shaping ----------------------------------------------------------

private fun buildMarks(
    month: YearMonth,
    slipDays: Set<Long>,
    journeyStart: Long,
): Map<Int, DayMark> {
    val today = LocalDate.now()
    return (1..month.lengthOfMonth()).associateWith { day ->
        val date = month.atDay(day)
        val epoch = date.toEpochDay()
        when {
            date.isAfter(today) -> DayMark.FUTURE
            // A logged slip outranks the journey-start cutoff.
            //
            // The cutoff exists so days before a man started are not painted
            // green as though he had held them — but it used to come first, so a
            // slip he deliberately recorded for a day before his start date
            // vanished. He fills in five screens, saves, and the calendar shows
            // nothing: every reason to believe the app dropped it.
            epoch in slipDays -> DayMark.SLIP
            journeyStart > 0 && epoch < journeyStart -> DayMark.NONE
            else -> DayMark.CLEAN
        }
    }
}

/** The stored CSV, back as a list. Empty when he skipped the question. */
private fun UrgeLogEntity.feelingList(): List<String> = feelings.splitValues()

/**
 * The five commonest answers, biggest first.
 *
 * Capped at five because these lists are long — fourteen feelings, nine places —
 * and a bar chart with fourteen labels on a phone is a grey smear. The tail is
 * genuinely not interesting: what a man needs is the two or three that keep
 * coming up.
 */
private fun topCounts(values: List<String>): List<Bar> {
    if (values.isEmpty()) return emptyList()
    val counts = values.groupingBy { it }.eachCount().entries
        .sortedByDescending { it.value }
        .take(5)
    val peak = counts.firstOrNull()?.value ?: 0
    return counts.map { (label, count) ->
        Bar(label, count.toFloat(), highlight = count == peak && peak > 0)
    }
}

/** Three-hour buckets: 24 bars would be noise on a phone. */
private fun hourBuckets(urges: List<UrgeLogEntity>): List<Bar> {
    val labels = listOf("12a", "3a", "6a", "9a", "12p", "3p", "6p", "9p")
    val counts = IntArray(8)
    urges.forEach { urge ->
        val hour = Instant.ofEpochMilli(urge.timestamp).atZone(ZoneId.systemDefault()).hour
        counts[hour / 3]++
    }
    val peak = counts.maxOrNull() ?: 0
    return labels.mapIndexed { i, label ->
        Bar(label, counts[i].toFloat(), highlight = counts[i] == peak && peak > 0)
    }
}

private fun weekdayBars(urges: List<UrgeLogEntity>): List<Bar> {
    val labels = listOf("M", "T", "W", "T", "F", "S", "S")
    val counts = IntArray(7)
    urges.forEach { urge ->
        val dow = Instant.ofEpochMilli(urge.timestamp).atZone(ZoneId.systemDefault()).dayOfWeek.value
        counts[dow - 1]++
    }
    val peak = counts.maxOrNull() ?: 0
    return labels.mapIndexed { i, label ->
        Bar(label, counts[i].toFloat(), highlight = counts[i] == peak && peak > 0)
    }
}

// --- pieces ----------------------------------------------------------------

/**
 * A benefit unlocked by days clean.
 *
 * Was a hand-rolled row with its own padding and its own idea of where the
 * trailing mark sits; it now goes through [BastionRow] like every other repeated
 * thing in the app, so the tap target, the semantics and the rhythm come from
 * one place. What is bespoke is only what is genuinely particular to it: the day
 * number in a circle, and the confidence dot.
 */
@Composable
private fun BenefitRow(card: BenefitCard) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        BastionRow(
            title = card.title,
            onClick = { expanded = !expanded },
            leading = {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(ChartColors.Clean.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${card.day}",
                        style = MaterialTheme.typography.labelMedium,
                        color = ChartColors.Clean,
                    )
                }
            },
            trailing = { ConfidenceDot(card.confidence) },
        )
        androidx.compose.animation.AnimatedVisibility(expanded) {
            Text(
                card.body,
                style = MaterialTheme.typography.bodyMedium,
                color = BastionColors.TextSecondary,
                modifier = Modifier.padding(bottom = Space.md),
            )
        }
    }
}

/**
 * How solid the evidence is, as a dot rather than a sentence. An app that
 * overclaims gets believed until the day it doesn't, and then nothing it says
 * counts — but that warning does not need a paragraph every time.
 */
@Composable
fun ConfidenceDot(confidence: String) {
    val colour = when (confidence.lowercase()) {
        "established" -> ChartColors.Clean
        "emerging" -> ChartColors.Neutral
        else -> BastionColors.TextMuted
    }
    Box(
        Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(colour)
    )
}

/**
 * The recovery flow.
 *
 * The one place in this redesign where the words stay. This screen decides
 * whether a man keeps the app or deletes it in disgust, and a terse UI at that
 * moment reads as indifference. Everything else got shorter; this did not.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RecoveryFlow(
    faithMode: Boolean,
    rankName: String,
    /** The night being recorded. Null means now, which is the common case. */
    date: LocalDate? = null,
    onDone: (LogEntry) -> Unit,
) {
    var stage by remember { mutableIntStateOf(0) }
    var captured by remember { mutableStateOf<LogEntry?>(null) }

    // A grace-first line for the worst moment to be reading anything. Loaded
    // rather than hard-coded so the words are not the same every single time a
    // man ends up here — which, for someone using this flow often, would make
    // it feel like a form rather than a hand.
    val graceContext = LocalContext.current
    val graceGraph = remember { BastionGraph.from(graceContext) }
    var grace by remember {
        mutableStateOf<com.bastion.app.data.content.MotivationItem?>(null)
    }
    LaunchedEffect(faithMode) {
        grace = graceGraph.content.motivationForMoment(
            faithMode = faithMode,
            moment = "relapse",
        )
    }

    Column {
        when (stage) {
            0 -> {
                Text("You're human.", style = MaterialTheme.typography.headlineMedium, color = BastionColors.TextPrimary)
                // Naming the day when it is not today, so a man logging Tuesday
                // on Friday can see he is not about to break this week's streak.
                date?.takeIf { it != LocalDate.now() }?.let {
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        "Recording " + if (it == LocalDate.now().minusDays(1)) "yesterday"
                        else it.format(DateTimeFormatter.ofPattern("EEEE d MMMM")),
                        style = MaterialTheme.typography.labelMedium,
                        color = BastionColors.TextMuted,
                    )
                }
                Spacer(Modifier.height(Space.md))
                Text(
                    if (faithMode) "No condemnation here. You're not back at the beginning."
                    else "A data point, not a verdict. You're not back at the beginning.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = BastionColors.TextSecondary,
                )
                grace?.let { item ->
                    Spacer(Modifier.height(Space.lg))
                    Text(
                        item.text,
                        style = com.bastion.app.core.design.ScriptureCompactStyle,
                        color = BastionColors.TextPrimary,
                    )
                    item.credit()?.let {
                        Spacer(Modifier.height(Space.sm))
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = BastionColors.BronzeBright,
                        )
                    }
                }
                Spacer(Modifier.height(Space.section))
                PrimaryButton("Look at it", { stage = 1 }, Modifier.fillMaxWidth())
            }

            // The same five questions a held urge gets.
            //
            // A slip used to be asked less than a resisted urge — one trigger
            // chip and a text box — which is exactly backwards. The nights that
            // went wrong are the ones with something to learn from, and asking
            // less about them was the app quietly agreeing that they are better
            // not looked at. Grace comes first and then it gets looked at
            // properly; that order is the whole argument.
            1 -> LogFlow(
                resisted = false,
                // Null when he came from the Log button, so the "when" step
                // appears and a slip can be dated back like anything else. It
                // was defaulted to today here, which meant the calendar was the
                // only route to logging a past slip — and the one screen that
                // asks "when was it?" skipped the question precisely when the
                // answer was least likely to be "now".
                forDate = date,
                saveLabel = "Continue",
                onCancel = { stage = 0 },
                onSave = { entry ->
                    captured = entry
                    stage = 2
                },
            )

            else -> {
                val entry = captured ?: LogEntry(resisted = false, date = date ?: LocalDate.now())
                Text("Your lever", style = MaterialTheme.typography.headlineSmall, color = BastionColors.TextPrimary)
                Spacer(Modifier.height(Space.md))
                Text(
                    // The first chosen trigger. There is one lever to offer and
                    // several triggers now, and picking the first he tapped is
                    // both stable and the one he reached for first.
                    tipFor(entry.triggers.firstOrNull()),
                    style = MaterialTheme.typography.bodyLarge,
                    color = BastionColors.TextSecondary,
                )
                Spacer(Modifier.height(Space.section))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Space.lg))
                        .background(BastionColors.SurfaceHigh)
                        .padding(Space.lg)
                ) {
                    Text(
                        "Streak restarts. Rank stays $rankName.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = BastionColors.TextPrimary,
                    )
                }
                Spacer(Modifier.height(Space.section))
                PrimaryButton("Keep walking", { onDone(entry) }, Modifier.fillMaxWidth())
            }
        }
    }
}

/** One line. A man who has just slipped will not read a paragraph. */
private fun tipFor(trigger: String?): String = when (trigger) {
    "Late night" -> "Phone across the room. Hard lights-out."
    "Boredom" -> "Queue something for the empty hour, in advance."
    "Stress" -> "Pick one physical outlet. Make it automatic."
    "Loneliness" -> "Message someone real tomorrow."
    "Social media" -> "Guard the feed, not the destination."
    "Tiredness" -> "Protect sleep first. The willpower follows."
    "Anger" -> "Choose its outlet before the feeling arrives."
    "Anxiety" -> "If it's constant, that's worth a doctor. Often it's the root."
    "Alcohol" -> "The decision that matters is the earlier one."
    "Home alone" -> "Solitude, screen, no plan. Change the plan."
    else -> "Log a few more and your own data will point at it."
}


@Composable
private fun sheetFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = BastionColors.Bronze,
    unfocusedBorderColor = BastionColors.Outline,
    focusedTextColor = BastionColors.TextPrimary,
    unfocusedTextColor = BastionColors.TextPrimary,
    cursorColor = BastionColors.Bronze,
)
