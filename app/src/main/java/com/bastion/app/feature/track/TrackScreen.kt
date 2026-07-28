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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bastion.app.core.design.BastionCard
import com.bastion.app.core.design.BastionColors
import com.bastion.app.core.design.DawnBackground
import com.bastion.app.core.design.PrimaryButton
import com.bastion.app.core.design.QuietButton
import com.bastion.app.core.design.SectionLabel
import com.bastion.app.core.design.StatPill
import com.bastion.app.data.BastionGraph
import com.bastion.app.data.content.BenefitCard
import com.bastion.app.data.repo.JourneyState
import com.bastion.app.domain.Analytics
import com.bastion.app.feature.home.ConfidenceNote
import kotlinx.coroutines.launch

private val TRIGGERS = listOf(
    "Late night", "Boredom", "Stress", "Loneliness", "Tiredness",
    "Social media", "Anger", "Home alone", "Anxiety", "Alcohol",
)

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
    val urges by graph.journey.recentUrges.collectAsStateWithLifecycle(initialValue = emptyList())

    var benefits by remember { mutableStateOf<List<BenefitCard>>(emptyList()) }
    var showLogSheet by remember { mutableStateOf(false) }
    var showRecovery by remember { mutableStateOf(false) }

    LaunchedEffect(state.currentStreak) {
        benefits = graph.content.unlockedBenefits(state.currentStreak)
    }

    val insights = remember(urges) { Analytics.insights(urges) }

    DawnBackground(intensity = 0.5f) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 52.dp, bottom = 32.dp)
        ) {
            Text("Track", style = MaterialTheme.typography.displaySmall, color = BastionColors.TextPrimary)
            Spacer(Modifier.height(18.dp))

            BastionCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    StatPill("${state.currentStreak}", "current")
                    StatPill("${state.longestStreak}", "longest", BastionColors.BronzeBright)
                    StatPill("${state.urgesResisted}", "resisted", BastionColors.SageBright)
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "Your rank of ${state.rank.displayName(faithMode)} is built from ${state.totalCleanDays} clean days, " +
                        "your habits and your check-ins. A slip restarts the streak. It does not touch the rank.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BastionColors.TextMuted,
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Log an urge", { showLogSheet = true }, Modifier.weight(1f))
                QuietButton("Log a slip", { showRecovery = true }, Modifier.weight(1f), BastionColors.Amber)
            }

            Spacer(Modifier.height(22.dp))
            SectionLabel("Your patterns")
            Spacer(Modifier.height(12.dp))
            insights.forEach { insight ->
                InsightCard(insight)
                Spacer(Modifier.height(10.dp))
            }

            Spacer(Modifier.height(14.dp))
            SectionLabel("Benefit timeline")
            Spacer(Modifier.height(12.dp))
            if (benefits.isEmpty()) {
                Text(
                    "Nothing unlocked yet — the first card lands on day one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BastionColors.TextMuted,
                )
            }
            benefits.forEach { card ->
                BastionCard(accent = BastionColors.Sage) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(BastionColors.SageDeep.copy(alpha = 0.4f))
                                .border(1.dp, BastionColors.Sage, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${card.day}",
                                style = MaterialTheme.typography.labelMedium,
                                color = BastionColors.SageBright,
                            )
                        }
                        Spacer(Modifier.size(12.dp))
                        Text(
                            card.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = BastionColors.TextPrimary,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(card.body, style = MaterialTheme.typography.bodyMedium, color = BastionColors.TextSecondary)
                    Spacer(Modifier.height(10.dp))
                    ConfidenceNote(card.confidence)
                }
                Spacer(Modifier.height(10.dp))
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
                onSave = { intensity, trigger, mood, note ->
                    scope.launch {
                        graph.journey.logUrge(
                            resisted = true,
                            intensity = intensity,
                            mood = mood,
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

@Composable
private fun InsightCard(insight: Analytics.Insight) {
    BastionCard {
        Text(insight.headline, style = MaterialTheme.typography.titleMedium, color = BastionColors.TextPrimary)
        Spacer(Modifier.height(8.dp))
        Text(insight.detail, style = MaterialTheme.typography.bodyMedium, color = BastionColors.TextSecondary)
        insight.defence?.let { defence ->
            Spacer(Modifier.height(12.dp))
            val label = when (defence) {
                is Analytics.Defence.TightenAtHour -> "Tighten guards before then →"
                is Analytics.Defence.HardenApp -> "Guard ${defence.label}'s feed →"
                Analytics.Defence.AddPartner -> "Add someone who knows →"
            }
            Text(label, style = MaterialTheme.typography.labelLarge, color = BastionColors.BronzeBright)
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun UrgeLogSheet(
    onSave: (intensity: Int, trigger: String?, mood: String?, note: String?) -> Unit,
    onCancel: () -> Unit,
) {
    var intensity by remember { mutableFloatStateOf(3f) }
    var trigger by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }

    Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 34.dp)) {
        Text("You resisted", style = MaterialTheme.typography.headlineSmall, color = BastionColors.TextPrimary)
        Spacer(Modifier.height(6.dp))
        Text(
            "Worth logging. Resisted urges are what the pattern analysis is built from — and they earn rank.",
            style = MaterialTheme.typography.bodyMedium,
            color = BastionColors.TextSecondary,
        )

        Spacer(Modifier.height(22.dp))
        SectionLabel("How strong was it?")
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

        Spacer(Modifier.height(14.dp))
        SectionLabel("What set it off?")
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TRIGGERS.forEach { option ->
                SelectChip(option, trigger == option) { trigger = if (trigger == option) null else option }
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("Anything worth remembering?") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = sheetFieldColors(),
        )

        Spacer(Modifier.height(20.dp))
        PrimaryButton(
            "Save",
            { onSave(intensity.toInt(), trigger, null, note.takeIf { it.isNotBlank() }) },
            Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        QuietButton("Not now", onCancel, Modifier.fillMaxWidth())
    }
}

/**
 * The recovery flow.
 *
 * This is the screen that decides whether a man keeps the app or deletes it in
 * disgust. There is no red, no "failure", no lost progress, and no interrogation
 * — just an honest look at what happened and the plain fact that his rank is
 * untouched. Compassion here is not softness; it is the thing that actually
 * breaks the shame-relapse loop.
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
                Text(
                    "You're human.",
                    style = MaterialTheme.typography.headlineMedium,
                    color = BastionColors.TextPrimary,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    if (faithMode)
                        "There is no condemnation waiting for you here, and none waiting for you with God either. " +
                            "You are not back at the beginning. You are a man who is being made new, who fell today, " +
                            "and who told the truth about it — which is the opposite of hiding.\n\n" +
                            "Let's look at what happened, without flinching and without shame."
                    else
                        "This is a data point, not a verdict on you. You are not back at the beginning. " +
                            "You are a man who has built something real, who fell today, and who chose to look " +
                            "at it honestly rather than pretend.\n\n" +
                            "Let's work out what happened, without flinching and without shame.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = BastionColors.TextSecondary,
                )
                Spacer(Modifier.height(24.dp))
                PrimaryButton("Let's look at it", { stage = 1 }, Modifier.fillMaxWidth())
            }

            1 -> {
                Text("What was happening?", style = MaterialTheme.typography.headlineSmall, color = BastionColors.TextPrimary)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Not to judge it — to find the lever. Most slips have a shape, and the shape is fixable.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BastionColors.TextSecondary,
                )
                Spacer(Modifier.height(18.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TRIGGERS.forEach { option ->
                        SelectChip(option, trigger == option) { trigger = option }
                    }
                }
                Spacer(Modifier.height(18.dp))
                OutlinedTextField(
                    value = reflection,
                    onValueChange = { reflection = it },
                    label = { Text("What would have helped, ten minutes earlier?") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = sheetFieldColors(),
                )
                Spacer(Modifier.height(20.dp))
                PrimaryButton("Continue", { stage = 2 }, Modifier.fillMaxWidth())
            }

            else -> {
                Text("Here's your lever", style = MaterialTheme.typography.headlineSmall, color = BastionColors.TextPrimary)
                Spacer(Modifier.height(12.dp))
                Text(
                    tipFor(trigger),
                    style = MaterialTheme.typography.bodyLarge,
                    color = BastionColors.TextSecondary,
                )
                Spacer(Modifier.height(22.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(BastionColors.SurfaceHigh)
                        .padding(18.dp)
                ) {
                    Column {
                        SectionLabel("What this changed", color = BastionColors.BronzeBright)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Your streak restarts tomorrow. Your rank is still $rankName — every clean day, " +
                                "habit and check-in you have banked is still yours, and always will be.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = BastionColors.TextPrimary,
                            textAlign = TextAlign.Start,
                        )
                    }
                }
                Spacer(Modifier.height(22.dp))
                PrimaryButton(
                    "Keep walking",
                    { onDone(trigger, reflection.takeIf { it.isNotBlank() }) },
                    Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun tipFor(trigger: String?): String = when (trigger) {
    "Late night" -> "Late-night slips are usually a sleep problem wearing a different mask. Put the phone " +
        "on the other side of the room and set a hard lights-out. Decide it now, while it's easy."
    "Boredom" -> "Boredom is a vacuum, and this habit is very good at filling vacuums. The fix isn't more " +
        "willpower — it's having something already queued up for the empty hour."
    "Stress" -> "You were regulating, not indulging. That's worth knowing. Pick one physical outlet — a walk, " +
        "the gym, cold water — and make it the automatic response before the other one gets there first."
    "Loneliness" -> "This one is honest and it matters: the hunger underneath was for connection, not content. " +
        "Message someone real tomorrow, even briefly. It does more than any blocker."
    "Social media" -> "The feed did the priming. Guarding Reels, Shorts or the For You page in the Guard tab " +
        "removes the on-ramp rather than fighting at the destination."
    "Tiredness" -> "Tired brains have almost no brake. Protect sleep first; most of the willpower you think " +
        "you're missing is just rest."
    "Anger" -> "Anger looks for discharge. Give it a physical one you've chosen in advance, and let the " +
        "decision be made before the feeling arrives."
    "Anxiety" -> "This was self-soothing. If anxiety is a steady presence rather than an occasional one, " +
        "that's worth taking to a doctor or therapist in its own right — it's often the root, not the branch."
    "Alcohol" -> "Alcohol removes the brake before the urge even shows up. The decision that matters here " +
        "is the one about drinking, made earlier in the evening."
    "Home alone" -> "Solitude plus a screen plus no plan is the classic setup. Change one of the three — " +
        "usually the plan is the easiest."
    else -> "Have a look at the pattern analysis on this screen once you've logged a few moments. " +
        "Your own data will point at the lever more accurately than any general advice can."
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
            .padding(horizontal = 14.dp, vertical = 9.dp)
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
    focusedLabelColor = BastionColors.BronzeBright,
    unfocusedLabelColor = BastionColors.TextMuted,
    focusedTextColor = BastionColors.TextPrimary,
    unfocusedTextColor = BastionColors.TextPrimary,
    cursorColor = BastionColors.Bronze,
)
