package com.bastion.app.feature.track

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bastion.app.core.design.BastionColors
import com.bastion.app.core.design.BastionFilterChip
import com.bastion.app.core.design.LinkButton
import com.bastion.app.core.design.PrimaryButton
import com.bastion.app.core.design.QuietButton
import com.bastion.app.core.design.SectionLabel
import com.bastion.app.core.design.Space
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Everything one log can say.
 *
 * Collected in one object rather than eight callback parameters, because the
 * flow can be entered at different steps and left at any of them, and a
 * half-filled entry still has to be saveable.
 */
data class LogEntry(
    val resisted: Boolean,
    val date: LocalDate = LocalDate.now(),
    val time: LocalTime = LocalTime.now(),
    val intensity: Int = 3,
    val trigger: String? = null,
    val feelings: List<String> = emptyList(),
    val place: String? = null,
    val device: String? = null,
    val soughtOut: Boolean? = null,
    val durationMinutes: Int? = null,
    val note: String? = null,
    val whatHelped: String? = null,
) {
    fun atMillis(): Long =
        LocalDateTime.of(date, time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

/** What set it off. One answer — the situation, not the feeling. */
val LOG_TRIGGERS = listOf(
    "Late night", "Boredom", "Stress", "Loneliness", "Tiredness",
    "Social media", "Anger", "Home alone", "Anxiety", "Alcohol",
    "Argument", "Scrolling", "After work", "Waking up", "Travel",
)

/**
 * What he was carrying, as many as apply.
 *
 * Deliberately includes the ones that are uncomfortable to tick. A list that
 * offers only "stressed" and "bored" teaches a man that the honest answer —
 * rejected, ashamed, numb — is not one the app wants to hear, and the log stops
 * being worth keeping.
 */
val LOG_FEELINGS = listOf(
    "Bored", "Stressed", "Lonely", "Tired", "Anxious", "Angry",
    "Sad", "Numb", "Rejected", "Restless", "Ashamed", "Celebrating",
    "Curious", "Aroused",
)

val LOG_PLACES = listOf("Bedroom", "Bathroom", "Living room", "Desk", "Work", "In bed", "Car", "Out", "Someone else's")

val LOG_DEVICES = listOf("Phone", "Laptop", "Tablet", "TV", "Work computer", "Someone else's")

/** What he reached for instead, for the ones he held. */
val LOG_HELPED = listOf(
    "Left the room", "Cold water", "Push-ups", "Went outside", "Messaged someone",
    "Prayed", "Opened Bastion", "Waited it out", "Went to sleep", "Ate something",
)

private const val STEPS = 5

/**
 * The log, in five short questions instead of one long form.
 *
 * The old sheet asked three things — strength, one trigger, a note — and that
 * was too little to ever tell a man anything he did not already know. A log that
 * cannot find a pattern is a diary, and a diary about this is mostly a record of
 * shame.
 *
 * So it asks properly: when, how hard, what he was feeling, where he was and on
 * what, and whether he went looking. That is enough to answer the question the
 * whole screen exists for — *when does this actually happen to me* — and it is
 * the same set of dimensions a man would be asked in a real recovery programme.
 *
 * Against that, the risk: a long form at 2am is a form nobody fills in. Three
 * things hold it down. One question per screen, so no step ever looks like work.
 * Every step after the first is skippable and says so. And the answers are chips,
 * not typing — the only free text in the whole flow is optional and last.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogFlow(
    resisted: Boolean,
    /** Pre-set when the calendar sent him here; the "when" step is skipped. */
    forDate: LocalDate? = null,
    /** "Continue" when the recovery flow still has something to say afterwards. */
    saveLabel: String = "Save",
    onCancel: () -> Unit,
    onSave: (LogEntry) -> Unit,
) {
    var entry by remember {
        mutableStateOf(
            LogEntry(
                resisted = resisted,
                date = forDate ?: LocalDate.now(),
                // A past day logged at 9am did not happen at 9am. Late evening is
                // the honest guess, and he can move it.
                time = if (forDate != null && forDate != LocalDate.now()) LocalTime.of(22, 0)
                else LocalTime.now(),
            )
        )
    }
    // Arriving from the calendar, the day is already answered.
    var step by remember { mutableIntStateOf(if (forDate != null) 1 else 0) }

    Column(Modifier.fillMaxWidth()) {
        StepProgress(step = step, of = STEPS)
        Spacer(Modifier.height(Space.lg))

        AnimatedContent(
            targetState = step,
            transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
            label = "logStep",
        ) { current ->
            Column(Modifier.fillMaxWidth().heightIn(min = 300.dp)) {
                when (current) {
                    0 -> WhenStep(entry) { entry = it }
                    1 -> StrengthStep(entry) { entry = it }
                    2 -> FeelingStep(entry) { entry = it }
                    3 -> PlaceStep(entry) { entry = it }
                    else -> LastStep(entry) { entry = it }
                }
            }
        }

        Spacer(Modifier.height(Space.lg))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            if (step > 0) {
                QuietButton("Back", { step-- }, Modifier.weight(1f))
            } else {
                QuietButton("Cancel", onCancel, Modifier.weight(1f))
            }
            PrimaryButton(
                if (step == STEPS - 1) saveLabel else "Next",
                { if (step == STEPS - 1) onSave(entry) else step++ },
                Modifier.weight(1f),
            )
        }
        // Every step past the first can be left blank. Saying so is what keeps
        // the flow from reading as an interrogation — and a skipped answer is a
        // more honest record than a guessed one.
        if (step in 1 until STEPS - 1) {
            Spacer(Modifier.height(Space.xs))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                LinkButton("Skip the rest", BastionColors.TextMuted) { onSave(entry) }
            }
        }
    }
}

/** Where he is in it, without a number. Five dashes, filled as he goes. */
@Composable
private fun StepProgress(step: Int, of: Int) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        repeat(of) { index ->
            val done = index <= step
            val alpha by animateFloatAsState(
                if (done) 1f else 0.25f,
                tween(220),
                label = "stepDash",
            )
            Box(
                Modifier
                    .weight(1f)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (done) BastionColors.Bronze.copy(alpha = alpha)
                        else BastionColors.Outline.copy(alpha = alpha)
                    )
            )
        }
    }
}

@Composable
private fun StepTitle(text: String, hint: String? = null) {
    Text(text, style = MaterialTheme.typography.headlineSmall, color = BastionColors.TextPrimary)
    hint?.let {
        Spacer(Modifier.height(Space.xs))
        Text(it, style = MaterialTheme.typography.bodySmall, color = BastionColors.TextMuted)
    }
    Spacer(Modifier.height(Space.lg))
}

/**
 * When.
 *
 * The step that makes a past night loggable at all. Three shortcuts cover almost
 * every real case; the day and hour beneath them handle the rest without a
 * date-picker dialog, which on a bottom sheet is a modal on top of a modal.
 */
@Composable
private fun WhenStep(entry: LogEntry, onChange: (LogEntry) -> Unit) {
    val today = LocalDate.now()
    StepTitle(
        "When was it?",
        "It counts against the day it happened, not the day you told anyone.",
    )

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        WhenChip("Just now", entry.date == today && entry.time.hour == LocalTime.now().hour) {
            onChange(entry.copy(date = today, time = LocalTime.now()))
        }
        WhenChip("Earlier today", entry.date == today && entry.time.hour != LocalTime.now().hour) {
            onChange(entry.copy(date = today, time = LocalTime.of(22, 0)))
        }
        WhenChip("Yesterday", entry.date == today.minusDays(1)) {
            onChange(entry.copy(date = today.minusDays(1), time = LocalTime.of(22, 0)))
        }
    }

    Spacer(Modifier.height(Space.section))
    SectionLabel("Day")
    Spacer(Modifier.height(Space.sm))
    // Two weeks back is the honest window. Further than that and a man is
    // reconstructing rather than remembering, and the data gets worse the harder
    // he tries.
    FlowRowChips(
        options = (0L..13L).map { today.minusDays(it) },
        label = { date ->
            when (date) {
                today -> "Today"
                today.minusDays(1) -> "Yesterday"
                else -> date.format(DateTimeFormatter.ofPattern("EEE d"))
            }
        },
        selected = { it == entry.date },
        onSelect = { onChange(entry.copy(date = it)) },
    )

    Spacer(Modifier.height(Space.section))
    SectionLabel("Roughly what time")
    Spacer(Modifier.height(Space.sm))
    FlowRowChips(
        options = listOf(0, 3, 6, 9, 12, 15, 18, 21, 23),
        label = { hour ->
            when (hour) {
                0 -> "Midnight"
                12 -> "Midday"
                23 -> "Late"
                else -> LocalTime.of(hour, 0).format(DateTimeFormatter.ofPattern("h a"))
            }
        },
        selected = { it == entry.time.hour },
        onSelect = { onChange(entry.copy(time = LocalTime.of(it, 0))) },
    )
}

@Composable
private fun WhenChip(label: String, selected: Boolean, onClick: () -> Unit) {
    BastionFilterChip(label = label, selected = selected, onClick = onClick)
}

@Composable
private fun StrengthStep(entry: LogEntry, onChange: (LogEntry) -> Unit) {
    var value by remember { mutableFloatStateOf(entry.intensity.toFloat()) }
    StepTitle(
        if (entry.resisted) "How hard did it push?" else "How strong was it?",
        "Not a score. It is how the hard ones get told apart from the rest.",
    )

    Text(
        strengthWord(value.toInt()),
        style = MaterialTheme.typography.headlineMedium,
        color = BastionColors.BronzeBright,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(Space.md))
    Slider(
        value = value,
        onValueChange = {
            value = it
            onChange(entry.copy(intensity = it.toInt()))
        },
        valueRange = 1f..5f,
        steps = 3,
        colors = SliderDefaults.colors(
            thumbColor = BastionColors.Bronze,
            activeTrackColor = BastionColors.Bronze,
            inactiveTrackColor = BastionColors.SurfaceHigh,
        ),
    )

    Spacer(Modifier.height(Space.section))
    SectionLabel("What set it off")
    Spacer(Modifier.height(Space.sm))
    FlowRowChips(
        options = LOG_TRIGGERS,
        label = { it },
        selected = { it == entry.trigger },
        // Tapping the chosen one again clears it — there is no other way back to
        // "I don't know", and a guessed trigger poisons the pattern it feeds.
        onSelect = { onChange(entry.copy(trigger = if (entry.trigger == it) null else it)) },
    )
}

private fun strengthWord(level: Int): String = when (level) {
    1 -> "A flicker"
    2 -> "Noticeable"
    3 -> "Strong"
    4 -> "Very strong"
    else -> "Overwhelming"
}

/**
 * The feeling underneath.
 *
 * The most useful question in the flow, and the one a blocker never asks. The
 * situation says when; this says why, and it is where the answer usually is.
 */
@Composable
private fun FeelingStep(entry: LogEntry, onChange: (LogEntry) -> Unit) {
    val chosen = remember { mutableStateListOf<String>().apply { addAll(entry.feelings) } }
    StepTitle("What were you feeling?", "As many as fit. It is never just one.")
    FlowRowChips(
        options = LOG_FEELINGS,
        label = { it },
        selected = { it in chosen },
        onSelect = {
            if (it in chosen) chosen.remove(it) else chosen.add(it)
            onChange(entry.copy(feelings = chosen.toList()))
        },
    )
}

/**
 * Where, on what, and whether he went looking.
 *
 * The last one is the question most apps will not ask, and the one that most
 * changes what to do next: stumbling into it on a feed is a guard problem, going
 * to find it is not. Asked plainly, with no flinch and no third option that
 * means "don't judge me" — and it can be left blank like everything else.
 */
@Composable
private fun PlaceStep(entry: LogEntry, onChange: (LogEntry) -> Unit) {
    StepTitle("Where were you?")
    FlowRowChips(
        options = LOG_PLACES,
        label = { it },
        selected = { it == entry.place },
        onSelect = { onChange(entry.copy(place = if (entry.place == it) null else it)) },
    )

    Spacer(Modifier.height(Space.section))
    SectionLabel("On what")
    Spacer(Modifier.height(Space.sm))
    FlowRowChips(
        options = LOG_DEVICES,
        label = { it },
        selected = { it == entry.device },
        onSelect = { onChange(entry.copy(device = if (entry.device == it) null else it)) },
    )

    Spacer(Modifier.height(Space.section))
    SectionLabel("Did it find you, or did you go looking?")
    Spacer(Modifier.height(Space.sm))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        BastionFilterChip(
            label = "It found me",
            selected = entry.soughtOut == false,
            onClick = {
                onChange(entry.copy(soughtOut = if (entry.soughtOut == false) null else false))
            },
        )
        BastionFilterChip(
            label = "I went looking",
            selected = entry.soughtOut == true,
            onClick = {
                onChange(entry.copy(soughtOut = if (entry.soughtOut == true) null else true))
            },
        )
    }
}

/**
 * The last screen: how long, what worked, anything else.
 *
 * "What helped" only for a held urge, and it is the single most valuable field
 * in the whole log — it is the only one that produces a list of things that have
 * actually worked for this particular man, in his own hand.
 */
@Composable
private fun LastStep(entry: LogEntry, onChange: (LogEntry) -> Unit) {
    StepTitle(
        if (entry.resisted) "What got you through?" else "Anything worth remembering?",
        "Optional. Future you is the only one who reads this.",
    )

    if (entry.resisted) {
        FlowRowChips(
            options = LOG_HELPED,
            label = { it },
            selected = { it == entry.whatHelped },
            onSelect = {
                onChange(entry.copy(whatHelped = if (entry.whatHelped == it) null else it))
            },
        )
        Spacer(Modifier.height(Space.section))
    }

    SectionLabel("How long did it last")
    Spacer(Modifier.height(Space.sm))
    FlowRowChips(
        options = listOf(2, 5, 15, 30, 60),
        label = { if (it >= 60) "An hour or more" else "$it min" },
        selected = { it == entry.durationMinutes },
        onSelect = {
            onChange(entry.copy(durationMinutes = if (entry.durationMinutes == it) null else it))
        },
    )

    Spacer(Modifier.height(Space.section))
    OutlinedTextField(
        value = entry.note.orEmpty(),
        onValueChange = { onChange(entry.copy(note = it.takeIf(String::isNotBlank))) },
        placeholder = {
            Text(
                if (entry.resisted) "What would make tomorrow easier?"
                else "What would have helped, ten minutes earlier?"
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 100.dp),
        shape = RoundedCornerShape(Space.md),
        colors = logFieldColors(),
    )
}

/**
 * The one chip layout, wrapping.
 *
 * A horizontal scroller hides options past the edge, and an option a man cannot
 * see is one he will not pick — which quietly biases every chart built on top of
 * these answers toward whatever happened to fit on screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> FlowRowChips(
    options: List<T>,
    label: (T) -> String,
    selected: (T) -> Boolean,
    onSelect: (T) -> Unit,
) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        options.forEach { option ->
            BastionFilterChip(
                label = label(option),
                selected = selected(option),
                onClick = { onSelect(option) },
            )
        }
    }
}

@Composable
private fun logFieldColors() = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
    focusedBorderColor = BastionColors.Bronze,
    unfocusedBorderColor = BastionColors.Outline,
    focusedTextColor = BastionColors.TextPrimary,
    unfocusedTextColor = BastionColors.TextPrimary,
    cursorColor = BastionColors.Bronze,
)
