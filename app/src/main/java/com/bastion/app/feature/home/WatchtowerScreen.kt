package com.bastion.app.feature.home

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bastion.app.core.design.BastionCard
import com.bastion.app.core.design.BastionColors
import com.bastion.app.core.design.DawnBackground
import com.bastion.app.core.design.HabitRing
import com.bastion.app.core.design.PrimaryButton
import com.bastion.app.core.design.RankMedallion
import com.bastion.app.core.design.ScriptureStyle
import com.bastion.app.core.design.SectionLabel
import com.bastion.app.core.design.StatPill
import com.bastion.app.data.BastionGraph
import com.bastion.app.data.content.BenefitCard
import com.bastion.app.data.content.DailyBrief
import com.bastion.app.data.prefs.Settings
import com.bastion.app.data.repo.JourneyState
import com.bastion.app.feature.panic.PanicActivity
import kotlinx.coroutines.launch

/**
 * The Watchtower.
 *
 * Rank sits at the top and the streak underneath it, and that ordering is the
 * whole philosophy in one layout decision: what he has built is the headline,
 * what he is currently holding is the detail.
 */
@Composable
fun WatchtowerScreen(
    faithMode: Boolean,
    onOpenMentor: () -> Unit,
    onOpenTrack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val graph = remember { BastionGraph.from(context) }
    val scope = rememberCoroutineScope()

    val state by graph.journey.state.collectAsStateWithLifecycle(initialValue = JourneyState())
    val settings by graph.settings.settings.collectAsStateWithLifecycle(initialValue = Settings())
    val habits by graph.growth.activeHabits.collectAsStateWithLifecycle(initialValue = emptyList())
    // Remembered: completionsToday() builds a new Flow per call, and collecting a
    // fresh instance on every recomposition resubscribes the query each frame.
    val todayFlow = remember(graph) { graph.growth.completionsToday() }
    val completions by todayFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    var brief by remember { mutableStateOf<DailyBrief?>(null) }
    var benefit by remember { mutableStateOf<BenefitCard?>(null) }
    var nextBenefit by remember { mutableStateOf<BenefitCard?>(null) }

    LaunchedEffect(state.currentStreak, state.totalDays) {
        brief = graph.content.briefForDay(graph.journey.dayOfJourney())
        benefit = graph.content.unlockedBenefits(state.currentStreak).firstOrNull()
        nextBenefit = graph.content.nextBenefit(state.currentStreak)
    }

    DawnBackground {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(top = 52.dp, bottom = 108.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Settings",
                        tint = BastionColors.TextMuted,
                        modifier = Modifier.clickable(onClick = onOpenSettings),
                    )
                }
                Greeting(name = settings.name, faithMode = faithMode)
                Spacer(Modifier.height(22.dp))

                RankMedallion(
                    rankName = state.rank.displayName(faithMode),
                    rankTier = state.rank.tier,
                    progressToNext = state.progressToNextRank,
                )
                Spacer(Modifier.height(18.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    StatPill("${state.currentStreak}", "day streak", BastionColors.TextPrimary)
                    StatPill("${state.totalCleanDays}", "clean days", BastionColors.SageBright)
                    StatPill("${state.points}", "points", BastionColors.BronzeBright)
                }

                state.pointsToNextRank?.let { remaining ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "$remaining points to ${com.bastion.app.domain.Rank.next(state.rank)?.displayName(faithMode)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = BastionColors.TextMuted,
                    )
                }

                Spacer(Modifier.height(26.dp))
                brief?.let { BriefCard(it, faithMode) }

                Spacer(Modifier.height(14.dp))
                BenefitCardView(benefit, nextBenefit, state.currentStreak, onOpenTrack)

                if (habits.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    BastionCard {
                        SectionLabel("Today's regimen")
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            habits.take(4).forEach { habit ->
                                val done = completions.any { it.habitId == habit.id }
                                HabitRing(
                                    label = habit.name,
                                    emoji = habit.emoji,
                                    done = done,
                                    onToggle = {
                                        scope.launch { graph.growth.toggleHabit(habit.id, !done) }
                                    },
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                BastionCard {
                    SectionLabel("If tonight gets hard")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "The Mentor is here at any hour, offline, and never repeats anything to anyone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = BastionColors.TextSecondary,
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Talk it through →",
                        style = MaterialTheme.typography.labelLarge,
                        color = BastionColors.SageBright,
                        modifier = Modifier.clickable(onClick = onOpenMentor),
                    )
                }
            }

            // Always reachable, never more than one tap away, wherever he has
            // scrolled to.
            PrimaryButton(
                text = "Hold the Line",
                onClick = {
                    context.startActivity(
                        Intent(context, PanicActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
            )
        }
    }
}

@Composable
private fun Greeting(name: String, faithMode: Boolean) {
    val hour = remember { java.time.LocalTime.now().hour }
    val part = when {
        hour < 12 -> "Morning"
        hour < 18 -> "Afternoon"
        else -> "Evening"
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        SectionLabel(if (faithMode) "The watchtower" else "The watchtower")
        Spacer(Modifier.height(6.dp))
        Text(
            if (name.isBlank()) part else "$part, $name",
            style = MaterialTheme.typography.headlineMedium,
            color = BastionColors.TextPrimary,
        )
    }
}

@Composable
private fun BriefCard(brief: DailyBrief, faithMode: Boolean) {
    val side = brief.side(faithMode)
    BastionCard(accent = BastionColors.Bronze) {
        SectionLabel("Daily brief · ${brief.theme}")
        Spacer(Modifier.height(14.dp))
        Text(side.anchor, style = ScriptureStyle, color = BastionColors.TextPrimary)
        Spacer(Modifier.height(8.dp))
        Text(side.anchorRef, style = MaterialTheme.typography.labelMedium, color = BastionColors.BronzeBright)
        Spacer(Modifier.height(16.dp))
        Text(side.title, style = MaterialTheme.typography.titleMedium, color = BastionColors.TextPrimary)
        Spacer(Modifier.height(6.dp))
        Text(side.body, style = MaterialTheme.typography.bodyMedium, color = BastionColors.TextSecondary)
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        ) {
            Column {
                SectionLabel("Today", color = BastionColors.SageBright)
                Spacer(Modifier.height(6.dp))
                Text(
                    side.microChallenge,
                    style = MaterialTheme.typography.bodyLarge,
                    color = BastionColors.TextPrimary,
                )
            }
        }
    }
}

@Composable
private fun BenefitCardView(
    unlocked: BenefitCard?,
    next: BenefitCard?,
    streak: Int,
    onOpenTrack: () -> Unit,
) {
    BastionCard(accent = BastionColors.Sage) {
        if (unlocked != null) {
            SectionLabel("Day ${unlocked.day} · what's happening", color = BastionColors.SageBright)
            Spacer(Modifier.height(10.dp))
            Text(unlocked.title, style = MaterialTheme.typography.titleMedium, color = BastionColors.TextPrimary)
            Spacer(Modifier.height(6.dp))
            Text(unlocked.body, style = MaterialTheme.typography.bodyMedium, color = BastionColors.TextSecondary)
            Spacer(Modifier.height(12.dp))
            ConfidenceNote(unlocked.confidence)
        } else {
            SectionLabel("The timeline starts today", color = BastionColors.SageBright)
            Spacer(Modifier.height(8.dp))
            Text(
                "As the days add up, Bastion will show you what is actually changing — and will " +
                    "tell you honestly which of it is well established and which is just what men report.",
                style = MaterialTheme.typography.bodyMedium,
                color = BastionColors.TextSecondary,
            )
        }
        next?.let {
            Spacer(Modifier.height(14.dp))
            Text(
                "Next unlock: day ${it.day} — ${it.day - streak} to go",
                style = MaterialTheme.typography.bodySmall,
                color = BastionColors.TextMuted,
                modifier = Modifier.clickable(onClick = onOpenTrack),
            )
        }
    }
}

/**
 * Says out loud how strong the evidence is. A recovery app that overclaims gets
 * believed until the day it doesn't, and then nothing it says counts.
 */
@Composable
fun ConfidenceNote(confidence: String) {
    val (label, colour) = when (confidence.lowercase()) {
        "established" -> "Well established" to BastionColors.SageBright
        "emerging" -> "Emerging evidence" to BastionColors.SteelBright
        else -> "Commonly reported, not proven" to BastionColors.TextMuted
    }
    Text(label, style = MaterialTheme.typography.labelSmall, color = colour)
}
