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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.bastion.app.core.design.BastionCard
import com.bastion.app.core.design.BastionColors
import com.bastion.app.core.design.CalendarLegend
import com.bastion.app.core.design.CalendarMonth
import com.bastion.app.core.design.ChartColors
import com.bastion.app.core.design.DawnBackground
import com.bastion.app.core.design.DayMark
import com.bastion.app.core.design.MetricTile
import com.bastion.app.core.design.PrimaryButton
import com.bastion.app.core.design.QuietButton
import com.bastion.app.core.design.SectionLabel
import com.bastion.app.core.design.StreakRing
import com.bastion.app.data.BastionGraph
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

private val TRIGGERS = listOf(
    "Late night", "Boredom", "Stress", "Loneliness", "Tiredness",
    "Social media", "Anger", "Home alone", "Anxiety", "Alcohol",
)

private val MILESTONES = listOf(7, 14, 30, 60, 90, 180, 365)

@OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)
@Composable
fun TrackScreen(faithMode: Boolean) {
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

    LaunchedEffect(state.currentStreak) {
        benefits = graph.content.unlockedBenefits(state.currentStreak)
    }

    val slipDays = remember(days) {
        days.filter { it.status == DayStatus.SLIP }.map { it.epochDay }.toSet()
    }
    val marks = remember(month, slipDays, settings.journeyStartEpochDay) {
        buildMarks(month, slipDays, settings.journeyStartEpochDay)
    }
    val nextMilestone = remember(state.currentStreak) {
        MILESTONES.firstOrNull { it > state.currentStreak }
    }
    val insights = remember(urges) { Analytics.insights(urges).take(2) }

    DawnBackground(intensity = 0.45f) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 44.dp, bottom = 28.dp)
        ) {
            // --- Hero -------------------------------------------------------
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                StreakRing(
                    days = state.currentStreak,
                    caption = if (state.currentStreak == 1) "DAY" else "DAYS",
                    progress = nextMilestone?.let { state.currentStreak.toFloat() / it } ?: 1f,
                )
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    nextMilestone?.let {
                        val left = it - state.currentStreak
                        "${left} ${if (left == 1) "day" else "days"} to $it"
                    } ?: "Beyond every milestone",
                    style = MaterialTheme.typography.labelMedium,
                    color = BastionColors.TextMuted,
                )
            }

            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                MetricTile("${state.longestStreak}", "BEST", accent = BastionColors.BronzeBright)
                MetricTile("${state.totalCleanDays}", "CLEAN", accent = ChartColors.Clean)
                MetricTile(
                    if (urges.isEmpty()) "—" else "${(urges.count { it.resisted } * 100) / urges.size}%",
                    "HELD",
                )
                MetricTile("${state.rank.tier}", "RANK", accent = BastionColors.BronzeBright)
            }

            Spacer(Modifier.height(20.dp))

            // --- Calendar ---------------------------------------------------
            BastionCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.ChevronLeft,
                        contentDescription = "Previous month",
                        tint = BastionColors.TextMuted,
                        modifier = Modifier.clickable { month = month.minusMonths(1) },
                    )
                    Text(
                        month.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                        style = MaterialTheme.typography.titleMedium,
                        color = BastionColors.TextPrimary,
                    )
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = "Next month",
                        tint = if (month < YearMonth.now()) BastionColors.TextMuted else BastionColors.Outline,
                        modifier = Modifier.clickable {
                            if (month < YearMonth.now()) month = month.plusMonths(1)
                        },
                    )
                }
                Spacer(Modifier.height(16.dp))
                CalendarMonth(month = month, marks = marks)
                Spacer(Modifier.height(14.dp))
                CalendarLegend()
            }

            Spacer(Modifier.height(12.dp))

            // --- Log actions ------------------------------------------------
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Log urge", { showLogSheet = true }, Modifier.weight(1f))
                QuietButton("Log slip", { showRecovery = true }, Modifier.weight(1f), BastionColors.Amber)
            }

            Spacer(Modifier.height(20.dp))

            // --- When urges hit ---------------------------------------------
            if (urges.isNotEmpty()) {
                BastionCard {
                    SectionLabel("When urges hit")
                    Spacer(Modifier.height(16.dp))
                    BarChart(bars = hourBuckets(urges), labelEvery = 1)
                }

                Spacer(Modifier.height(12.dp))

                BastionCard {
                    SectionLabel("Hardest days")
                    Spacer(Modifier.height(16.dp))
                    BarChart(bars = weekdayBars(urges), labelEvery = 1)
                }

                Spacer(Modifier.height(12.dp))
            }

            // --- Insights, one line each ------------------------------------
            insights.forEach { insight ->
                BastionCard {
                    Text(
                        insight.headline,
                        style = MaterialTheme.typography.titleSmall,
                        color = BastionColors.TextPrimary,
                    )
                    insight.defence?.let {
                        Spacer(Modifier.height(8.dp))
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
                Spacer(Modifier.height(10.dp))
            }

            // --- Benefits, compact ------------------------------------------
            if (benefits.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                SectionLabel("Unlocked")
                Spacer(Modifier.height(12.dp))
                benefits.take(6).forEach { card ->
                    BenefitRow(card)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    if (showLogSheet) {
        ModalBottomSheet(
            onDismissRequest = { showLogSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = BastionColors.Surface,
        ) {
            UrgeLogSheet(
                onSave = { intensity, trigger, note ->
                    scope.launch {
                        graph.journey.logUrge(
                            resisted = true,
                            intensity = intensity,
                            mood = null,
                            trigger = trigger,
                            contextApp = com.bastion.app.guard.accessibility
                                .BastionAccessibilityService.foregroundApp.value,
                            place = null,
                            note = note,
                        )
                        showLogSheet = false
                    }
                },
                onCancel = { showLogSheet = false },
            )
        }
    }

    if (showRecovery) {
        ModalBottomSheet(
            onDismissRequest = { showRecovery = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = BastionColors.Surface,
        ) {
            RecoveryFlow(
                faithMode = faithMode,
                rankName = state.rank.displayName(faithMode),
                onDone = { trigger, reflection ->
                    scope.launch {
                        graph.journey.logUrge(
                            resisted = false,
                            intensity = 5,
                            mood = null,
                            trigger = trigger,
                            contextApp = null,
                            place = null,
                            note = reflection,
                        )
                        showRecovery = false
                    }
                },
            )
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
            journeyStart > 0 && epoch < journeyStart -> DayMark.NONE
            epoch in slipDays -> DayMark.SLIP
            else -> DayMark.CLEAN
        }
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

@Composable
private fun BenefitRow(card: BenefitCard) {
    var expanded by remember { mutableStateOf(false) }
    BastionCard {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
            Spacer(Modifier.size(12.dp))
            Text(
                card.title,
                style = MaterialTheme.typography.titleSmall,
                color = BastionColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            ConfidenceDot(card.confidence)
        }
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            Text(
                card.body,
                style = MaterialTheme.typography.bodyMedium,
                color = BastionColors.TextSecondary,
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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun UrgeLogSheet(
    onSave: (intensity: Int, trigger: String?, note: String?) -> Unit,
    onCancel: () -> Unit,
) {
    var intensity by remember { mutableFloatStateOf(3f) }
    var trigger by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }

    Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 34.dp)) {
        Text("You resisted", style = MaterialTheme.typography.headlineSmall, color = BastionColors.TextPrimary)
        Spacer(Modifier.height(18.dp))

        SectionLabel("Strength")
        Slider(
            value = intensity,
            onValueChange = { intensity = it },
            valueRange = 1f..5f,
            steps = 3,
            colors = SliderDefaults.colors(
                thumbColor = BastionColors.Bronze,
                activeTrackColor = BastionColors.Bronze,
                inactiveTrackColor = BastionColors.SurfaceHigh,
            ),
        )

        Spacer(Modifier.height(10.dp))
        SectionLabel("Trigger")
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TRIGGERS.forEach { option ->
                SelectChip(option, trigger == option) { trigger = if (trigger == option) null else option }
            }
        }

        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            placeholder = { Text("Note (optional)") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = sheetFieldColors(),
        )

        Spacer(Modifier.height(18.dp))
        PrimaryButton(
            "Save",
            { onSave(intensity.toInt(), trigger, note.takeIf { it.isNotBlank() }) },
            Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        QuietButton("Cancel", onCancel, Modifier.fillMaxWidth())
    }
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
    onDone: (trigger: String?, reflection: String?) -> Unit,
) {
    var stage by remember { mutableIntStateOf(0) }
    var trigger by remember { mutableStateOf<String?>(null) }
    var reflection by remember { mutableStateOf("") }

    Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 34.dp)) {
        when (stage) {
            0 -> {
                Text("You're human.", style = MaterialTheme.typography.headlineMedium, color = BastionColors.TextPrimary)
                Spacer(Modifier.height(12.dp))
                Text(
                    if (faithMode) "No condemnation here. You're not back at the beginning."
                    else "A data point, not a verdict. You're not back at the beginning.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = BastionColors.TextSecondary,
                )
                Spacer(Modifier.height(22.dp))
                PrimaryButton("Look at it", { stage = 1 }, Modifier.fillMaxWidth())
            }

            1 -> {
                Text("What was happening?", style = MaterialTheme.typography.headlineSmall, color = BastionColors.TextPrimary)
                Spacer(Modifier.height(16.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TRIGGERS.forEach { option ->
                        SelectChip(option, trigger == option) { trigger = option }
                    }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = reflection,
                    onValueChange = { reflection = it },
                    placeholder = { Text("What would have helped, ten minutes earlier?") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = sheetFieldColors(),
                )
                Spacer(Modifier.height(18.dp))
                PrimaryButton("Continue", { stage = 2 }, Modifier.fillMaxWidth())
            }

            else -> {
                Text("Your lever", style = MaterialTheme.typography.headlineSmall, color = BastionColors.TextPrimary)
                Spacer(Modifier.height(12.dp))
                Text(tipFor(trigger), style = MaterialTheme.typography.bodyLarge, color = BastionColors.TextSecondary)
                Spacer(Modifier.height(20.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(BastionColors.SurfaceHigh)
                        .padding(16.dp)
                ) {
                    Text(
                        "Streak restarts. Rank stays $rankName.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = BastionColors.TextPrimary,
                    )
                }
                Spacer(Modifier.height(20.dp))
                PrimaryButton(
                    "Keep walking",
                    { onDone(trigger, reflection.takeIf { it.isNotBlank() }) },
                    Modifier.fillMaxWidth(),
                )
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
private fun SelectChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) BastionColors.BronzeDeep else BastionColors.SurfaceRaised)
            .border(
                1.dp,
                if (selected) BastionColors.Bronze else BastionColors.Outline,
                RoundedCornerShape(20.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) BastionColors.BronzeBright else BastionColors.TextSecondary,
        )
    }
}

@Composable
private fun sheetFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = BastionColors.Bronze,
    unfocusedBorderColor = BastionColors.Outline,
    focusedTextColor = BastionColors.TextPrimary,
    unfocusedTextColor = BastionColors.TextPrimary,
    cursorColor = BastionColors.Bronze,
)
