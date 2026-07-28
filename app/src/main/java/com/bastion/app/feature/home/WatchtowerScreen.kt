package com.bastion.app.feature.home

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import com.bastion.app.core.design.ChartColors
import com.bastion.app.core.design.DawnBackground
import com.bastion.app.core.design.HabitRing
import com.bastion.app.core.design.MetricTile
import com.bastion.app.core.design.PrimaryButton
import com.bastion.app.core.design.RankMedallion
import com.bastion.app.core.design.ScriptureCompactStyle
import com.bastion.app.core.design.SectionLabel
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
 * Rank above streak, which is the whole philosophy in one layout decision: what
 * he has built is the headline, what he is currently holding is the detail.
 *
 * Everything here is glanceable. The long-form writing lives one tap away — a
 * home screen that must be *read* is a home screen that stops being opened.
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
    val todayFlow = remember(graph) { graph.growth.completionsToday() }
    val completions by todayFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    var brief by remember { mutableStateOf<DailyBrief?>(null) }
    var benefit by remember { mutableStateOf<BenefitCard?>(null) }

    // Crossing a rank threshold is the emotional payoff of the whole model, so
    // it gets a moment rather than silently incrementing a number. Compared
    // against a persisted tier so it fires once, on the crossing, and never
    // again on a recomposition or relaunch.
    var celebrating by remember { mutableStateOf<com.bastion.app.domain.Rank?>(null) }
    LaunchedEffect(state.rank, settings.lastSeenRankTier) {
        if (state.rank.tier > settings.lastSeenRankTier) {
            celebrating = state.rank
            graph.settings.setLastSeenRankTier(state.rank.tier)
        }
    }
    celebrating?.let { rank ->
        RankUpCeremony(
            rank = rank,
            faithMode = faithMode,
            onDismiss = { celebrating = null },
        )
    }

    LaunchedEffect(state.currentStreak, state.totalDays) {
        brief = graph.content.briefForDay(graph.journey.dayOfJourney())
        benefit = graph.content.unlockedBenefits(state.currentStreak).firstOrNull()
    }

    val hour = remember { java.time.LocalTime.now().hour }
    DawnBackground(intensity = com.bastion.app.core.design.dawnIntensityForHour(hour)) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    // Clears the pinned Hold the Line button, which floats above
                    // this content and would otherwise sit on top of the last card.
                    .padding(top = 44.dp, bottom = 128.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = "Settings",
                        tint = BastionColors.TextMuted,
                        modifier = Modifier.clickable(onClick = onOpenSettings),
                    )
                }

                Text(
                    greeting(settings.name),
                    style = MaterialTheme.typography.titleMedium,
                    color = BastionColors.TextSecondary,
                )
                Spacer(Modifier.height(16.dp))

                RankMedallion(
                    rankName = state.rank.displayName(faithMode),
                    rankTier = state.rank.tier,
                    progressToNext = state.progressToNextRank,
                )
                Spacer(Modifier.height(18.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    MetricTile("${state.currentStreak}", "STREAK")
                    MetricTile("${state.totalCleanDays}", "CLEAN", accent = ChartColors.Clean)
                    MetricTile("${state.points}", "POINTS", accent = BastionColors.BronzeBright)
                }

                Spacer(Modifier.height(22.dp))
                brief?.let { BriefCard(it, faithMode) }

                if (habits.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    BastionCard {
                        SectionLabel("Today")
                        Spacer(Modifier.height(14.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            habits.take(4).forEach { habit ->
                                val done = completions.any { it.habitId == habit.id }
                                HabitRing(
                                    label = habit.name,
                                    emoji = habit.emoji,
                                    done = done,
                                    onToggle = { scope.launch { graph.growth.toggleHabit(habit.id, !done) } },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }

                benefit?.let { card ->
                    Spacer(Modifier.height(12.dp))
                    BastionCard(accent = ChartColors.Clean) {
                        SectionLabel("Day ${card.day}", color = ChartColors.Clean)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            card.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = BastionColors.TextPrimary,
                        )
                        Spacer(Modifier.height(4.dp))
                        // A TextButton rather than clickable text: this is a real
                        // action and needs button semantics and a 48dp target,
                        // because the app gets used one-handed under stress.
                        androidx.compose.material3.TextButton(
                            onClick = onOpenTrack,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 4.dp,
                                vertical = 8.dp,
                            ),
                        ) {
                            Text(
                                "See the timeline →",
                                style = MaterialTheme.typography.labelMedium,
                                color = BastionColors.TextMuted,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                BastionCard {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenMentor),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                "Talk to the Mentor",
                                style = MaterialTheme.typography.titleSmall,
                                color = BastionColors.TextPrimary,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "Any hour · offline",
                                style = MaterialTheme.typography.labelSmall,
                                color = BastionColors.TextMuted,
                            )
                        }
                        Text("→", style = MaterialTheme.typography.titleMedium, color = BastionColors.SageBright)
                    }
                }
            }

            // The panic button floats above the scroll so it is reachable from
            // anywhere. Without a scrim it read as a bar slicing through
            // whatever card happened to be behind it; the fade makes it a layer.
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            0f to androidx.compose.ui.graphics.Color.Transparent,
                            0.45f to BastionColors.MidnightDeep.copy(alpha = 0.92f),
                            1f to BastionColors.MidnightDeep,
                        )
                    )
                    .padding(top = 28.dp)
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 20.dp),
            ) {
                PrimaryButton(
                    text = "Hold the Line",
                    onClick = {
                        context.startActivity(
                            Intent(context, PanicActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun greeting(name: String): String {
    val hour = java.time.LocalTime.now().hour
    val part = when {
        hour < 12 -> "Morning"
        hour < 18 -> "Afternoon"
        else -> "Evening"
    }
    return if (name.isBlank()) part else "$part, $name"
}

/**
 * Anchor, title and today's one action. The reflection sits behind a tap —
 * present for the man who wants it, invisible to the man who just wants to log
 * a habit and put the phone down.
 */
@Composable
private fun BriefCard(brief: DailyBrief, faithMode: Boolean) {
    val side = brief.side(faithMode)
    var expanded by remember { mutableStateOf(false) }

    BastionCard(accent = BastionColors.Bronze) {
        SectionLabel(brief.theme)
        Spacer(Modifier.height(12.dp))
        Text(side.anchor, style = ScriptureCompactStyle, color = BastionColors.TextPrimary)
        Spacer(Modifier.height(8.dp))
        Text(
            side.anchorRef,
            style = MaterialTheme.typography.labelSmall,
            color = BastionColors.BronzeBright,
        )

        Spacer(Modifier.height(18.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                SectionLabel("Today", color = ChartColors.Clean)
                Spacer(Modifier.height(6.dp))
                Text(
                    side.microChallenge,
                    style = MaterialTheme.typography.bodyLarge,
                    color = BastionColors.TextPrimary,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            if (expanded) "Less" else "Read more",
            style = MaterialTheme.typography.labelMedium,
            color = BastionColors.TextMuted,
            modifier = Modifier.clickable { expanded = !expanded },
        )
        AnimatedVisibility(expanded) {
            Column {
                Spacer(Modifier.height(12.dp))
                Text(
                    side.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = BastionColors.TextSecondary,
                )
                side.prompt?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = BastionColors.SageBright,
                        textAlign = TextAlign.Start,
                    )
                }
            }
        }
    }
}
