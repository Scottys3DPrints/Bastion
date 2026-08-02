package com.bastion.app.feature.home

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bastion.app.core.design.BastionCard
import com.bastion.app.core.design.BastionColors
import com.bastion.app.core.design.BastionScaffold
import com.bastion.app.core.design.HabitRing
import com.bastion.app.core.design.RankMedallion
import com.bastion.app.core.design.ScriptureCompactStyle
import com.bastion.app.core.design.Section
import com.bastion.app.core.design.SectionLabel
import com.bastion.app.core.design.BastionRow
import com.bastion.app.core.design.EmptyState
import com.bastion.app.core.design.Skeleton
import com.bastion.app.core.design.Space
import com.bastion.app.data.BastionGraph
import com.bastion.app.data.content.DailyBrief
import com.bastion.app.data.prefs.Settings
import com.bastion.app.data.repo.JourneyState
import com.bastion.app.feature.panic.PanicActivity
import kotlinx.coroutines.launch

/**
 * The Watchtower — what about right now.
 *
 * Everything historical moved to Progress. This screen used to carry a
 * three-metric row, a benefit card and a mentor card as well, all of which
 * rendered again on Track; when the same numbers appear in two places, neither
 * place is *the* place, and the app reads as several dashboards competing.
 *
 * What is left is what a man needs before he puts the phone down: who he has
 * become, what he is holding, today's word, today's habits. One link out to the
 * history, and the one action that matters when it is going wrong.
 */
@Composable
fun WatchtowerScreen(
    faithMode: Boolean,
    onOpenProgress: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenPartner: () -> Unit,
    onOpenMentor: () -> Unit,
    onOpenHabits: () -> Unit,
) {
    val context = LocalContext.current
    val graph = remember { BastionGraph.from(context) }
    val scope = rememberCoroutineScope()

    val state by graph.journey.state.collectAsStateWithLifecycle(initialValue = JourneyState())
    val settings by graph.settings.settings.collectAsStateWithLifecycle(initialValue = Settings())
    val habits by graph.growth.activeHabits.collectAsStateWithLifecycle(initialValue = emptyList())
    val todayFlow = remember(graph) { graph.growth.completionsToday() }
    val completions by todayFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val partner by graph.social.partner.collectAsStateWithLifecycle(initialValue = null)

    var brief by remember { mutableStateOf<DailyBrief?>(null) }
    var word by remember {
        mutableStateOf<com.bastion.app.data.content.MotivationItem?>(null)
    }

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
    }

    // Today's word. Keyed on the epoch day, so it is the same line all day and
    // a different one tomorrow — a home screen whose quote changes every time
    // you glance at it teaches you not to read it.
    val today = remember { java.time.LocalDate.now().toEpochDay() }
    LaunchedEffect(today, faithMode) {
        word = graph.content.motivationForMoment(
            faithMode = faithMode,
            moment = "daily",
            daySeed = today,
        )
    }

    val hour = remember { java.time.LocalTime.now().hour }

    BastionScaffold(
        // "Today", matching the tab that opens it.
        //
        // This screen was titled "Watchtower" and reached from a tab labelled
        // "Today", on the theory that the poetry could live in the title while
        // the plain word did the navigating. In practice a man taps one word and
        // lands on a different one, which reads as having arrived in the wrong
        // place — and he has no way to know the two are the same screen.
        // Thematic names are worth having right up until they cost someone
        // their bearings.
        title = "Today",
        dawnIntensity = com.bastion.app.core.design.dawnIntensityForHour(hour),
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
            Column(horizontalAlignment = Alignment.End) {
                // The break-glass plan, kept within thumb reach of the panic
                // action because the two are reached for in the same moment —
                // but visibly the smaller of the pair, since it is the heavier
                // decision and should not be the easier tap.
                com.bastion.app.feature.guardui.LockdownCompactButton(settings = settings)
                Spacer(Modifier.height(Space.md))
                ExtendedFloatingActionButton(
                    onClick = {
                        context.startActivity(
                            Intent(context, PanicActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    containerColor = BastionColors.Bronze,
                    contentColor = BastionColors.MidnightDeep,
                ) { Text("Hold the Line", style = MaterialTheme.typography.labelLarge) }
            }
        },
    ) {
        // The hero, and the only one on this screen.
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                greeting(settings.name),
                style = MaterialTheme.typography.titleMedium,
                color = BastionColors.TextSecondary,
            )
            Spacer(Modifier.height(Space.lg))
            RankMedallion(
                rankName = state.rank.displayName(faithMode),
                rankTier = state.rank.tier,
                progressToNext = state.progressToNextRank,
            )
            Spacer(Modifier.height(Space.md))
            // One number, not a metric row. The rest live on Progress, which is
            // now the only place any statistic is rendered in depth.
            Text(
                "${state.currentStreak} ${if (state.currentStreak == 1) "day" else "days"} clean",
                style = MaterialTheme.typography.titleMedium,
                color = BastionColors.TextPrimary,
            )
            TextButton(onClick = onOpenProgress) {
                Text(
                    "See your progress →",
                    style = MaterialTheme.typography.labelMedium,
                    color = BastionColors.TextMuted,
                )
            }
        }

        // Placeholders rather than nothing, so the page settles into its final
        // shape instead of growing two blocks under the reader's thumb.
        word?.let { TodaysWord(it) } ?: Section("Today's word") { Skeleton(lines = 2) }

        brief?.let { BriefCard(it, faithMode) } ?: Skeleton(lines = 4)

        Section("Today") {
            if (habits.isEmpty()) {
                // The whole block used to vanish when a man had no habits, so
                // the screen silently lost a third of itself and never said the
                // one thing he needed to hear: that this is the part he fills in.
                EmptyState(
                    "No habits yet. This is the part that replaces the old one.",
                    actionLabel = "Add your first habit",
                    onAction = onOpenHabits,
                )
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
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

        // The people, on the screen a man actually opens.
        //
        // "One person who knows beats any blocker" is the app's own claim about
        // what works, and both of the things that deliver it — his partner and
        // the Mentor — were behind a bare avatar in the corner that gives no
        // clue it is a menu. The feature the product says matters most was the
        // one a new user was least likely to ever find.
        Section("Your corner") {
            val current = partner
            if (current == null) {
                BastionRow(
                    title = "Name someone",
                    subtitle = "Someone who'll take a message at midnight.",
                    trailing = { Text("→", color = BastionColors.SageBright) },
                    onClick = onOpenPartner,
                )
            } else {
                BastionRow(
                    title = current.name,
                    subtitle = "Your partner",
                    onClick = onOpenPartner,
                    trailing = {
                        // One tap to the thing that helps, rather than one tap
                        // to a screen with the thing that helps on it.
                        TextButton(
                            onClick = {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_SENDTO,
                                        android.net.Uri.parse("smsto:${current.contact}"),
                                    ).putExtra("sms_body", "Got a minute? Could use a word.")
                                )
                            },
                        ) {
                            Text(
                                "Message",
                                style = MaterialTheme.typography.labelMedium,
                                color = BastionColors.SageBright,
                            )
                        }
                    },
                )
            }
            BastionRow(
                title = "The Mentor",
                subtitle = "Any hour, offline, stays on this phone.",
                trailing = { Text("→", color = BastionColors.SageBright) },
                onClick = onOpenMentor,
            )
        }
    }
}

/**
 * Today's word: one line, its credit, and nothing else.
 *
 * A Section rather than a card on purpose. The brief below it is already a
 * bordered surface, and two boxed blocks stacked would be exactly the density
 * this screen was cut back from.
 */
@Composable
private fun TodaysWord(item: com.bastion.app.data.content.MotivationItem) {
    Section("Today's word") {
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
 *
 * One of the two bordered surfaces this screen is allowed. It earns it: it is a
 * single highlighted thing to read, which is exactly what a card is for.
 */
@Composable
private fun BriefCard(brief: DailyBrief, faithMode: Boolean) {
    val side = brief.side(faithMode)
    var expanded by remember { mutableStateOf(false) }

    BastionCard(accent = BastionColors.Bronze) {
        SectionLabel(brief.theme)
        Spacer(Modifier.height(Space.md))
        Text(side.anchor, style = ScriptureCompactStyle, color = BastionColors.TextPrimary)
        Spacer(Modifier.height(Space.sm))
        Text(
            side.anchorRef,
            style = MaterialTheme.typography.labelSmall,
            color = BastionColors.BronzeBright,
        )

        Spacer(Modifier.height(Space.lg))
        SectionLabel("Today", color = com.bastion.app.core.design.ChartColors.Clean)
        Spacer(Modifier.height(Space.sm))
        Text(
            side.microChallenge,
            style = MaterialTheme.typography.bodyLarge,
            color = BastionColors.TextPrimary,
        )

        TextButton(
            onClick = { expanded = !expanded },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = Space.xs,
                vertical = Space.sm,
            ),
        ) {
            Text(
                if (expanded) "Less" else "Read more",
                style = MaterialTheme.typography.labelMedium,
                color = BastionColors.TextMuted,
            )
        }
        AnimatedVisibility(expanded) {
            Column {
                Text(
                    side.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = BastionColors.TextSecondary,
                )
                side.prompt?.let {
                    Spacer(Modifier.height(Space.md))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = BastionColors.SageBright,
                    )
                }
                Spacer(Modifier.height(Space.xs))
            }
        }
    }
}
