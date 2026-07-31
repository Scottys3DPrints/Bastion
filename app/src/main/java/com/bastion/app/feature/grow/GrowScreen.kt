package com.bastion.app.feature.grow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bastion.app.core.design.BastionCard
import com.bastion.app.core.design.BastionColors
import com.bastion.app.core.design.ChoiceRow
import com.bastion.app.core.design.LinkButton
import com.bastion.app.core.design.BastionBottomSheet
import com.bastion.app.core.design.BastionScaffold
import com.bastion.app.core.design.PrimaryButton
import com.bastion.app.core.design.ProportionBar
import com.bastion.app.core.design.QuietButton
import com.bastion.app.core.design.SectionLabel
import com.bastion.app.data.BastionGraph
import com.bastion.app.data.content.Challenge
import com.bastion.app.data.content.HabitDef
import com.bastion.app.data.content.Lesson
import com.bastion.app.data.db.ChallengeProgressEntity
import com.bastion.app.data.db.HabitEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate

private enum class GrowTab(val label: String) {
    REGIMEN("Regimen"),
    CHALLENGES("Challenges"),
    BECOMING("Becoming"),
    LIBRARY("Library"),
}

/**
 * The BECOME pillar — the part most quit-porn apps leave out.
 *
 * Porn thrives in a vacuum of boredom, stress and aimlessness. Filling that
 * vacuum is not a nice-to-have bolted onto a blocker; it is the actual mechanism.
 * A man does not just quit — he replaces it with a life.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun GrowScreen(faithMode: Boolean, onOpenProfile: () -> Unit) {
    val context = LocalContext.current
    val graph = remember { BastionGraph.from(context) }
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(GrowTab.REGIMEN) }
    var showHabitPicker by remember { mutableStateOf(false) }
    var openLesson by remember { mutableStateOf<Lesson?>(null) }

    BastionScaffold(
        title = "Grow",
        dawnIntensity = 0.35f,
        action = {
            IconButton(onClick = onOpenProfile) {
                Icon(
                    Icons.Filled.AccountCircle,
                    contentDescription = "You, partner and settings",
                    tint = BastionColors.TextMuted,
                )
            }
        },
    ) {
        ChoiceRow(
            options = GrowTab.entries,
            selected = tab,
            label = { it.label },
            onSelect = { tab = it },
            modifier = Modifier.fillMaxWidth(),
        )

        when (tab) {
            GrowTab.REGIMEN -> RegimenTab(graph, onAdd = { showHabitPicker = true })
            GrowTab.CHALLENGES -> ChallengesTab(graph, faithMode)
            GrowTab.BECOMING -> BecomingTab(graph)
            GrowTab.LIBRARY -> LibraryTab(graph, faithMode) { openLesson = it }
        }
    }

    if (showHabitPicker) {
        BastionBottomSheet(onDismiss = { showHabitPicker = false }) {
            HabitPickerSheet(graph, faithMode) { def ->
                scope.launch2 { graph.growth.adoptHabit(def, 99) }
                showHabitPicker = false
            }
        }
    }

    openLesson?.let { lesson ->
        BastionBottomSheet(onDismiss = { openLesson = null }) {
            LessonSheet(lesson) {
                scope.launch2 { graph.journey.markLessonRead(lesson.id) }
                openLesson = null
            }
        }
    }
}

@Composable
private fun RegimenTab(graph: BastionGraph, onAdd: () -> Unit) {
    val scope = rememberCoroutineScope()
    val habits by graph.growth.activeHabits.collectAsStateWithLifecycle(initialValue = emptyList())
    val todayFlow = remember(graph) { graph.growth.completionsToday() }
    val today by todayFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var confirmDrop by remember { mutableStateOf<HabitEntity?>(null) }

    Text(
        "Three kept beats ten intended.",
        style = MaterialTheme.typography.bodySmall,
        color = BastionColors.TextMuted,
    )
    Spacer(Modifier.height(14.dp))

    habits.forEach { habit ->
        val done = today.any { it.habitId == habit.id }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (done) BastionColors.SageDeep.copy(alpha = 0.25f) else BastionColors.Surface)
                .border(
                    1.dp,
                    if (done) BastionColors.Sage else BastionColors.OutlineSoft,
                    RoundedCornerShape(14.dp),
                )
                .clickable(role = Role.Button) { scope.launch2 { graph.growth.toggleHabit(habit.id, !done) } }
                .padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(habit.emoji, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(habit.name, style = MaterialTheme.typography.titleSmall, color = BastionColors.TextPrimary)
                Text(
                    habit.why.ifBlank { habit.domain },
                    style = MaterialTheme.typography.bodySmall,
                    color = BastionColors.TextMuted,
                )
            }
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(if (done) BastionColors.Sage else BastionColors.SurfaceHigh),
                contentAlignment = Alignment.Center,
            ) {
                if (done) Text("✓", color = BastionColors.MidnightDeep, style = MaterialTheme.typography.labelMedium)
            }
            LinkButton("Drop", BastionColors.TextMuted) { confirmDrop = habit }
        }
    }

    Spacer(Modifier.height(14.dp))
    QuietButton("Add a habit", onAdd, Modifier.fillMaxWidth())

    // Deactivated rather than deleted, so the four weeks of record behind the
    // Becoming profile survive a habit leaving the regimen.
    confirmDrop?.let { habit ->
        ConfirmDialog(
            title = "Drop ${habit.name}?",
            body = "It leaves the regimen. What you've already done still counts.",
            confirmLabel = "Drop it",
            onConfirm = { scope.launch2 { graph.growth.setHabitActive(habit, false) } },
            onDismiss = { confirmDrop = null },
        )
    }
}

@Composable
private fun ChallengesTab(graph: BastionGraph, faithMode: Boolean) {
    val scope = rememberCoroutineScope()
    var catalogue by remember { mutableStateOf<List<Challenge>>(emptyList()) }
    val progress by graph.growth.allChallenges.collectAsStateWithLifecycle(initialValue = emptyList())

    LaunchedEffect(faithMode) {
        catalogue = graph.growth.challengeCatalogue().filter { it.visibleIn(faithMode) }
    }

    catalogue.forEach { challenge ->
        val state = progress.firstOrNull { it.challengeId == challenge.id }
        ChallengeCard(
            challenge = challenge,
            progress = state,
            onStart = { scope.launch2 { graph.growth.startChallenge(challenge.id) } },
            onCompleteToday = { day ->
                scope.launch2 { graph.growth.completeChallengeDay(challenge.id, day) }
            },
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ChallengeCard(
    challenge: Challenge,
    progress: ChallengeProgressEntity?,
    onStart: () -> Unit,
    onCompleteToday: (Int) -> Unit,
) {
    val active = progress?.active == true
    val currentDay = progress?.let {
        ((LocalDate.now().toEpochDay() - it.startedEpochDay).toInt() + 1).coerceIn(1, challenge.days)
    } ?: 1
    val done = progress?.completedDaysCsv?.split(',')?.filter { it.isNotBlank() }?.toSet().orEmpty()
    val doneDays = done.size
    val todayDone = currentDay.toString() in done

    BastionCard(accent = if (active) BastionColors.Bronze else null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(challenge.name, style = MaterialTheme.typography.titleMedium, color = BastionColors.TextPrimary)
            Text(
                "${challenge.days} days",
                style = MaterialTheme.typography.labelSmall,
                color = BastionColors.TextMuted,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(challenge.tagline, style = MaterialTheme.typography.bodySmall, color = BastionColors.BronzeBright)
        Spacer(Modifier.height(10.dp))
        Text(challenge.description, style = MaterialTheme.typography.bodyMedium, color = BastionColors.TextSecondary)

        if (active) {
            val task = challenge.taskFor(currentDay)
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(BastionColors.SurfaceHigh)
                    .padding(14.dp)
            ) {
                Column {
                    SectionLabel("Day $currentDay of ${challenge.days} · $doneDays done", color = BastionColors.BronzeBright)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        task?.task ?: "Keep going.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = BastionColors.TextPrimary,
                    )
                    val detail = task?.detail
                    if (!detail.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(detail, style = MaterialTheme.typography.bodySmall, color = BastionColors.TextSecondary)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            PrimaryButton(
                if (todayDone) "Day $currentDay done ✓" else "Mark day $currentDay done",
                { onCompleteToday(currentDay) },
                Modifier.fillMaxWidth(),
                enabled = !todayDone,
            )
        } else if (progress?.completedAt != null) {
            Spacer(Modifier.height(12.dp))
            Text("Completed ✓", style = MaterialTheme.typography.labelLarge, color = BastionColors.SageBright)
        } else {
            Spacer(Modifier.height(14.dp))
            QuietButton("Start", onStart, Modifier.fillMaxWidth(), BastionColors.BronzeBright)
        }
    }
}

/** The "Man You're Becoming" profile — driven by what was done, not intended. */
@Composable
private fun BecomingTab(graph: BastionGraph) {
    val habits by graph.growth.allHabits.collectAsStateWithLifecycle(initialValue = emptyList())
    val badges by graph.growth.badges.collectAsStateWithLifecycle(initialValue = emptyList())
    val since = remember { LocalDate.now().toEpochDay() - 28 }
    val completionsFlow = remember(graph, since) { graph.growth.completionsSince(since) }
    val completions by completionsFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    val scores = remember(completions, habits) { graph.growth.domainScores(completions, habits) }

    BastionCard {
        SectionLabel("The man you're becoming")
        Spacer(Modifier.height(6.dp))
        Text(
            "Last four weeks. Moves slowly on purpose.",
            style = MaterialTheme.typography.bodySmall,
            color = BastionColors.TextMuted,
        )
        Spacer(Modifier.height(18.dp))

        if (scores.isEmpty()) {
            Text(
                "Add habits and this fills in.",
                style = MaterialTheme.typography.bodySmall,
                color = BastionColors.TextMuted,
            )
        }

        scores.entries.sortedByDescending { it.value }.forEach { (domain, score) ->
            Column(Modifier.padding(vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(domain, style = MaterialTheme.typography.titleSmall, color = BastionColors.TextPrimary)
                    Text(
                        "${(score * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = BastionColors.BronzeBright,
                    )
                }
                Spacer(Modifier.height(6.dp))
                ProportionBar(fraction = score)
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    BastionCard {
        SectionLabel("Badges · ${badges.size}")
        Spacer(Modifier.height(12.dp))
        if (badges.isEmpty()) {
            Text(
                "Finish a challenge or hit a milestone and they land here.",
                style = MaterialTheme.typography.bodyMedium,
                color = BastionColors.TextMuted,
            )
        }
        badges.forEach { badge ->
            Text("◇ ${badge.name}", style = MaterialTheme.typography.bodyLarge, color = BastionColors.BronzeBright)
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun LibraryTab(graph: BastionGraph, faithMode: Boolean, onOpen: (Lesson) -> Unit) {
    var lessons by remember { mutableStateOf<List<Lesson>>(emptyList()) }

    LaunchedEffect(faithMode) {
        lessons = graph.content.education().lessons.filter { it.visibleIn(faithMode) }
    }

    lessons.groupBy { it.category }.forEach { (category, items) ->
        SectionLabel(category)
        Spacer(Modifier.height(10.dp))
        items.forEach { lesson ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(BastionColors.Surface)
                    .clickable(role = Role.Button) { onOpen(lesson) }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    lesson.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = BastionColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${lesson.readMinutes} min",
                    style = MaterialTheme.typography.labelSmall,
                    color = BastionColors.TextMuted,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun LessonSheet(lesson: Lesson, onDone: () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 22.dp)
            .padding(bottom = 34.dp)
            .height(600.dp)
    ) {
        Text(lesson.title, style = MaterialTheme.typography.headlineSmall, color = BastionColors.TextPrimary)
        Spacer(Modifier.height(14.dp))
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            lesson.body.split("\n\n").forEach { paragraph ->
                if (paragraph.startsWith("## ")) {
                    Text(
                        paragraph.removePrefix("## "),
                        style = MaterialTheme.typography.titleMedium,
                        color = BastionColors.BronzeBright,
                    )
                } else {
                    Text(paragraph, style = MaterialTheme.typography.bodyLarge, color = BastionColors.TextSecondary)
                }
                Spacer(Modifier.height(14.dp))
            }
            if (lesson.keyTakeaway.isNotBlank()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(BastionColors.SurfaceHigh)
                        .padding(16.dp)
                ) {
                    Text(lesson.keyTakeaway, style = MaterialTheme.typography.bodyLarge, color = BastionColors.TextPrimary)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        PrimaryButton("Done", onDone, Modifier.fillMaxWidth())
    }
}

@Composable
private fun HabitPickerSheet(graph: BastionGraph, faithMode: Boolean, onPick: (HabitDef) -> Unit) {
    var catalogue by remember { mutableStateOf<List<HabitDef>>(emptyList()) }
    LaunchedEffect(faithMode) {
        catalogue = graph.growth.catalogue().filter { it.visibleIn(faithMode) }
    }

    Column(
        Modifier
            .padding(horizontal = 22.dp)
            .padding(bottom = 34.dp)
            .height(520.dp)
    ) {
        Text("Add a habit", style = MaterialTheme.typography.headlineSmall, color = BastionColors.TextPrimary)
        Spacer(Modifier.height(14.dp))
        Column(Modifier.verticalScroll(rememberScrollState())) {
            catalogue.groupBy { it.domain }.forEach { (domain, items) ->
                SectionLabel(domain)
                Spacer(Modifier.height(8.dp))
                items.forEach { def ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) { onPick(def) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(def.icon, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.size(12.dp))
                        Column {
                            Text(def.name, style = MaterialTheme.typography.bodyLarge, color = BastionColors.TextPrimary)
                            Text(def.why, style = MaterialTheme.typography.bodySmall, color = BastionColors.TextMuted)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/** Text-styled actions that are still real buttons: 48dp target, button semantics. */

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BastionColors.Surface,
        titleContentColor = BastionColors.TextPrimary,
        textContentColor = BastionColors.TextSecondary,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            LinkButton(confirmLabel) {
                onConfirm()
                onDismiss()
            }
        },
        dismissButton = { LinkButton("Not now", BastionColors.TextMuted, onDismiss) },
    )
}


/** Small helper so tab bodies stay readable rather than nesting scope plumbing. */
private fun CoroutineScope.launch2(block: suspend () -> Unit) {
    launch { block() }
}
