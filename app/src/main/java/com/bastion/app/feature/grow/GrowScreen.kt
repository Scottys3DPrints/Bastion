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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.mutableLongStateOf
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
import com.bastion.app.core.design.Space
import com.bastion.app.core.design.ChoiceRow
import com.bastion.app.core.design.LinkButton
import com.bastion.app.core.design.BastionBottomSheet
import com.bastion.app.core.design.BastionScaffold
import com.bastion.app.core.design.PrimaryButton
import com.bastion.app.core.design.ProportionBar
import com.bastion.app.core.design.QuietButton
import com.bastion.app.core.design.BastionRow
import com.bastion.app.core.design.EmptyState
import com.bastion.app.core.design.Section
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

/**
 * Three doors, in words a stranger already knows.
 *
 * There were five — Regimen, Challenges, Becoming, Library, Armory — crammed
 * into one segmented strip, which is five screens hidden behind a control too
 * narrow to read. Two of the labels were the app's private vocabulary: nobody
 * arrives knowing that "Armory" holds quotes or that "Becoming" is a chart of
 * what he has done.
 *
 * So: "Regimen" is Habits, because that is the word. Library absorbed the
 * Armory — lessons and lines are both things to read, and splitting them across
 * two tabs meant the answer to "where is that verse I kept" was a coin toss.
 * Becoming left entirely for Progress, where a four-week record of what he
 * actually did belongs, and which the app already defines as the place for how
 * it is going over time.
 */
private enum class GrowTab(val label: String) {
    HABITS("Habits"),
    CHALLENGES("Challenges"),
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
fun GrowScreen(
    faithMode: Boolean,
    onOpenProfile: () -> Unit,
    onOpenHabit: (String) -> Unit,
    onOpenHabitProgress: () -> Unit,
) {
    val context = LocalContext.current
    val graph = remember { BastionGraph.from(context) }
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(GrowTab.HABITS) }
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
            GrowTab.HABITS -> RegimenTab(
                graph = graph,
                onAdd = { showHabitPicker = true },
                onOpenHabit = onOpenHabit,
                onOpenProgress = onOpenHabitProgress,
            )
            GrowTab.CHALLENGES -> ChallengesTab(graph, faithMode)
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

/**
 * The habits tab: a journal, not a checklist.
 *
 * What used to be here was a flat list with a tick per row. It said what a man
 * signed up for and never said what was due, which is the question he opens the
 * tab to ask. The journal lives in [HabitJournal], one habit's own record lives
 * on [HabitDetailScreen], and the regimen as a whole on [HabitsProgressScreen];
 * this holds the only state that spans them — which day is being looked at.
 */
@Composable
private fun RegimenTab(
    graph: BastionGraph,
    onAdd: () -> Unit,
    onOpenHabit: (String) -> Unit,
    onOpenProgress: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val habits by graph.growth.activeHabits.collectAsStateWithLifecycle(initialValue = emptyList())
    var confirmDrop by remember { mutableStateOf<HabitEntity?>(null) }
    var selectedDay by remember { mutableLongStateOf(LocalDate.now().toEpochDay()) }

    Text(
        "Three kept beats ten intended.",
        style = MaterialTheme.typography.bodySmall,
        color = BastionColors.TextMuted,
    )

    if (habits.isEmpty()) {
        EmptyState(
            "No habits yet. The point of this screen is what replaces the old one.",
            actionLabel = "Add your first habit",
            onAction = onAdd,
        )
    } else {
        HabitJournal(
            graph = graph,
            habits = habits,
            selectedDay = selectedDay,
            onSelectDay = { selectedDay = it },
            onOpenHabit = { onOpenHabit(it.id) },
            onBump = { scope.launch2 { graph.growth.bumpHabit(it, selectedDay) } },
            onSetStatus = { habit, status ->
                scope.launch2 { graph.growth.setHabitStatus(habit, status, selectedDay) }
            },
        )
        QuietButton("See your progress", onOpenProgress, Modifier.fillMaxWidth())
        QuietButton("Add a habit", onAdd, Modifier.fillMaxWidth())
    }

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

    // Split rather than stacked. Every challenge used to render as a full card
    // with its name, tagline, description and a Start button, so a catalogue of
    // a dozen was a dozen identical boxes to scroll past — and the one he was
    // actually doing looked exactly like the eleven he was not.
    val started = catalogue.filter { c -> progress.any { it.challengeId == c.id && it.active } }
    val finished = catalogue.filter { c ->
        progress.any { it.challengeId == c.id && !it.active && it.completedAt != null }
    }
    val available = catalogue - started.toSet() - finished.toSet()

    if (started.isNotEmpty()) {
        Section("Underway") {
            started.forEach { challenge ->
                ChallengeCard(
                    challenge = challenge,
                    progress = progress.firstOrNull { it.challengeId == challenge.id },
                    onStart = { scope.launch2 { graph.growth.startChallenge(challenge.id) } },
                    onCompleteToday = { day ->
                        scope.launch2 { graph.growth.completeChallengeDay(challenge.id, day) }
                    },
                )
                Spacer(Modifier.height(Space.md))
            }
        }
    }

    Section(if (started.isEmpty()) "Pick one" else "More") {
        if (available.isEmpty()) {
            EmptyState("You've started everything here. That is its own kind of answer.")
        }
        available.forEach { challenge ->
            BastionRow(
                title = challenge.name,
                subtitle = challenge.tagline,
                onClick = { scope.launch2 { graph.growth.startChallenge(challenge.id) } },
                trailing = {
                    Text(
                        "${challenge.days} days",
                        style = MaterialTheme.typography.labelSmall,
                        color = BastionColors.TextMuted,
                    )
                },
            )
        }
    }

    if (finished.isNotEmpty()) {
        Section("Finished") {
            finished.forEach { challenge ->
                BastionRow(
                    title = challenge.name,
                    trailing = {
                        Text(
                            "✓",
                            style = MaterialTheme.typography.labelLarge,
                            color = BastionColors.SageBright,
                        )
                    },
                )
            }
        }
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
        Spacer(Modifier.height(Space.sm))
        Text(challenge.tagline, style = MaterialTheme.typography.bodySmall, color = BastionColors.BronzeBright)
        Spacer(Modifier.height(Space.md))
        Text(challenge.description, style = MaterialTheme.typography.bodyMedium, color = BastionColors.TextSecondary)

        if (active) {
            val task = challenge.taskFor(currentDay)
            Spacer(Modifier.height(Space.lg))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Space.md))
                    .background(BastionColors.SurfaceHigh)
                    .padding(Space.lg)
            ) {
                Column {
                    SectionLabel("Day $currentDay of ${challenge.days} · $doneDays done", color = BastionColors.BronzeBright)
                    Spacer(Modifier.height(Space.sm))
                    Text(
                        task?.task ?: "Keep going.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = BastionColors.TextPrimary,
                    )
                    val detail = task?.detail
                    if (!detail.isNullOrBlank()) {
                        Spacer(Modifier.height(Space.sm))
                        Text(detail, style = MaterialTheme.typography.bodySmall, color = BastionColors.TextSecondary)
                    }
                }
            }
            Spacer(Modifier.height(Space.md))
            PrimaryButton(
                if (todayDone) "Day $currentDay done ✓" else "Mark day $currentDay done",
                { onCompleteToday(currentDay) },
                Modifier.fillMaxWidth(),
                enabled = !todayDone,
            )
        } else if (progress?.completedAt != null) {
            Spacer(Modifier.height(Space.md))
            Text("Completed ✓", style = MaterialTheme.typography.labelLarge, color = BastionColors.SageBright)
        } else {
            Spacer(Modifier.height(Space.lg))
            QuietButton("Start", onStart, Modifier.fillMaxWidth(), BastionColors.BronzeBright)
        }
    }
}

/**
 * Everything there is to read, behind one door.
 *
 * Lessons lived in "Library" and the 390 lines lived in "Armory", which meant
 * the answer to "where is that verse I kept" depended on remembering which of
 * two words the app had filed it under. They are both reading; the filter says
 * what kind.
 */
@Composable
private fun LibraryTab(graph: BastionGraph, faithMode: Boolean, onOpen: (Lesson) -> Unit) {
    val scope = rememberCoroutineScope()
    val settings by graph.settings.settings.collectAsStateWithLifecycle(
        initialValue = com.bastion.app.data.prefs.Settings()
    )
    var lessons by remember { mutableStateOf<List<Lesson>>(emptyList()) }
    var lines by remember {
        mutableStateOf<List<com.bastion.app.data.content.MotivationItem>>(emptyList())
    }
    var filter by remember { mutableStateOf(LIBRARY_ALL) }
    var keptOnly by remember { mutableStateOf(false) }

    LaunchedEffect(faithMode) {
        lessons = graph.content.education().lessons.filter { it.visibleIn(faithMode) }
        lines = graph.content.motivationFor(faithMode)
    }

    val kept = settings.savedMotivation.toSet()
    val filters = remember(lines) {
        listOf(LIBRARY_ALL, LIBRARY_LESSONS) + lines.map { it.type }.distinct().sorted()
    }
    val shownLessons =
        if (keptOnly || filter !in listOf(LIBRARY_ALL, LIBRARY_LESSONS)) emptyList() else lessons
    val shownLines = lines
        .filter { filter == LIBRARY_ALL || it.type == filter }
        .filter { !keptOnly || it.id in kept }
        .let { if (filter == LIBRARY_LESSONS) emptyList() else it }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionLabel("${lessons.size} lessons · ${lines.size} lines")
        LinkButton(
            if (keptOnly) "Show all" else "Kept only",
            if (keptOnly) BastionColors.BronzeBright else BastionColors.TextMuted,
        ) { keptOnly = !keptOnly }
    }
    Spacer(Modifier.height(Space.md))

    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        filters.forEach { option ->
            com.bastion.app.core.design.BastionFilterChip(
                label = libraryLabel(option),
                selected = filter == option,
                onClick = { filter = option },
            )
        }
    }
    Spacer(Modifier.height(Space.md))

    if (shownLessons.isEmpty() && shownLines.isEmpty()) {
        EmptyState(
            if (keptOnly) "Nothing kept yet. Tap Keep on a line that lands."
            else "Nothing here in this mode.",
        )
    }

    shownLessons.groupBy { it.category }.forEach { (category, items) ->
        Section(category) {
            items.forEach { lesson ->
                BastionRow(
                    title = lesson.title,
                    onClick = { onOpen(lesson) },
                    trailing = {
                        Text(
                            "${lesson.readMinutes} min",
                            style = MaterialTheme.typography.labelSmall,
                            color = BastionColors.TextMuted,
                        )
                    },
                )
            }
        }
        Spacer(Modifier.height(Space.md))
    }

    shownLines.forEach { item ->
        ArmoryRow(
            item = item,
            kept = item.id in kept,
            onToggleKeep = { scope.launch2 { graph.settings.toggleSavedMotivation(item.id) } },
        )
    }
}

@Composable
private fun LessonSheet(lesson: Lesson, onDone: () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = Space.gutter)
            .padding(bottom = Space.xl)
            // heightIn, not height. At a system font scale of 1.5-2x a fixed
            // box holds fewer lines than it did at 1x, so the content clips
            // mid-word — which reads as broken rendering rather than as a
            // too-small box.
            .heightIn(max = 600.dp)
    ) {
        Text(lesson.title, style = MaterialTheme.typography.headlineSmall, color = BastionColors.TextPrimary)
        Spacer(Modifier.height(Space.lg))
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
                Spacer(Modifier.height(Space.lg))
            }
            if (lesson.keyTakeaway.isNotBlank()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Space.md))
                        .background(BastionColors.SurfaceHigh)
                        .padding(Space.lg)
                ) {
                    Text(lesson.keyTakeaway, style = MaterialTheme.typography.bodyLarge, color = BastionColors.TextPrimary)
                }
            }
        }
        Spacer(Modifier.height(Space.lg))
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
            .padding(horizontal = Space.gutter)
            .padding(bottom = Space.xl)
            .heightIn(min = 320.dp, max = 560.dp)
    ) {
        Text("Add a habit", style = MaterialTheme.typography.headlineSmall, color = BastionColors.TextPrimary)
        Spacer(Modifier.height(Space.lg))
        Column(Modifier.verticalScroll(rememberScrollState())) {
            catalogue.groupBy { it.domain }.forEach { (domain, items) ->
                SectionLabel(domain)
                Spacer(Modifier.height(Space.sm))
                items.forEach { def ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) { onPick(def) }
                            .padding(vertical = Space.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(def.icon, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.size(Space.md))
                        Column {
                            Text(def.name, style = MaterialTheme.typography.bodyLarge, color = BastionColors.TextPrimary)
                            Text(def.why, style = MaterialTheme.typography.bodySmall, color = BastionColors.TextMuted)
                        }
                    }
                }
                Spacer(Modifier.height(Space.md))
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

@Composable
private fun ArmoryRow(
    item: com.bastion.app.data.content.MotivationItem,
    kept: Boolean,
    onToggleKeep: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = Space.md)) {
        item.title?.let {
            Text(it, style = MaterialTheme.typography.titleSmall, color = BastionColors.TextPrimary)
            Spacer(Modifier.height(Space.xs))
        }
        Text(
            item.text,
            style = MaterialTheme.typography.bodyMedium,
            color = BastionColors.TextSecondary,
        )
        Spacer(Modifier.height(Space.sm))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                item.credit() ?: libraryLabel(item.type),
                style = MaterialTheme.typography.labelSmall,
                color = BastionColors.TextMuted,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            LinkButton(
                if (kept) "Kept" else "Keep",
                if (kept) BastionColors.BronzeBright else BastionColors.TextMuted,
                onToggleKeep,
            )
        }
        com.bastion.app.core.design.RowDivider()
    }
}

private const val LIBRARY_ALL = "all"
private const val LIBRARY_LESSONS = "lessons"

/** "urge_line" is what the file calls it; "Urge lines" is what a person calls it. */
private fun libraryLabel(type: String): String = when (type) {
    LIBRARY_ALL -> "All"
    LIBRARY_LESSONS -> "Lessons"
    "quote" -> "Quotes"
    "scripture" -> "Scripture"
    "prayer" -> "Prayers"
    "reframe" -> "Reframes"
    "urge_line" -> "Urge lines"
    "affirmation" -> "Affirmations"
    "story" -> "Stories"
    "fact" -> "Facts"
    else -> type.replace('_', ' ').replaceFirstChar(Char::uppercase)
}
