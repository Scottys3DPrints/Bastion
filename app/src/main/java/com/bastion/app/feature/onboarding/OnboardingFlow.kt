package com.bastion.app.feature.onboarding

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bastion.app.core.design.BastionColors
import com.bastion.app.core.design.DawnBackground
import com.bastion.app.core.design.PrimaryButton
import com.bastion.app.core.design.QuietButton
import com.bastion.app.core.design.ScriptureStyle
import com.bastion.app.core.design.SectionLabel
import com.bastion.app.data.BastionGraph
import com.bastion.app.guard.accessibility.BastionAccessibilityService
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

/**
 * Not a settings wizard — a commitment ceremony.
 *
 * The order is deliberate. A man states who he wants to become *before* he is
 * shown a single switch, because the switches only matter in service of that.
 * The Covenant is signed by hand and kept where he will see it.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun OnboardingFlow(onComplete: () -> Unit) {
    val context = LocalContext.current
    val graph = remember { BastionGraph.from(context) }
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf(0) }
    var faithMode by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("") }
    var frequency by remember { mutableStateOf("") }
    val triggers = remember { mutableStateListOf<String>() }
    var whyText by remember { mutableStateOf("") }
    var whyVideoPath by remember { mutableStateOf<String?>(null) }
    val strokes = remember { mutableStateListOf<Stroke2D>() }
    var padSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    val oath = remember(faithMode, name) { oathText(faithMode, name) }

    DawnBackground {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 26.dp, vertical = 20.dp)
        ) {
            StepIndicator(step = step, total = 6)
            Spacer(Modifier.height(20.dp))

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                when (step) {
                    0 -> WelcomeStep()
                    1 -> ModeStep(faithMode) { faithMode = it }
                    2 -> DiagnosticStep(
                        name = name,
                        onName = { name = it },
                        frequency = frequency,
                        onFrequency = { frequency = it },
                        triggers = triggers,
                    )
                    3 -> WhyStep(
                        whyText = whyText,
                        onWhyText = { whyText = it },
                        videoPath = whyVideoPath,
                        onVideoPath = { whyVideoPath = it },
                    )
                    4 -> CovenantStep(
                        oath = oath,
                        strokes = strokes,
                        onPadSized = { padSize = it },
                    )
                    5 -> GuardrailsStep()
                }
            }

            Spacer(Modifier.height(16.dp))
            PrimaryButton(
                text = when (step) {
                    0 -> "Begin"
                    4 -> "Sign the covenant"
                    5 -> "Enter Bastion"
                    else -> "Continue"
                },
                onClick = {
                    if (step < 5) {
                        step++
                    } else {
                        scope.launch {
                            val signaturePath = saveSignature(
                                context = context,
                                strokes = strokes.map { it.toList() },
                                sourceWidth = padSize.width.toFloat(),
                                sourceHeight = padSize.height.toFloat(),
                            )
                            graph.growth.saveCovenant(
                                oathText = oath,
                                signaturePath = signaturePath,
                                whyText = whyText.takeIf { it.isNotBlank() },
                                whyMediaPath = whyVideoPath,
                                whyMediaType = whyVideoPath?.let { "video" },
                            )
                            graph.settings.setFaithMode(faithMode)
                            graph.settings.setName(name.trim())
                            graph.settings.setTriggers(triggers.toList())
                            graph.settings.setBaseline(frequency)
                            graph.settings.setJourneyStart(LocalDate.now().toEpochDay())
                            graph.guard.seedIfEmpty()
                            seedStarterHabits(graph, faithMode)
                            graph.settings.setOnboarded(true)
                            onComplete()
                        }
                    }
                },
                enabled = when (step) {
                    2 -> name.isNotBlank()
                    // Not merely "a stroke exists" — a stray tap used to sign a
                    // covenant with a blank image.
                    4 -> hasRealSignature(strokes.map { it.toList() })
                    else -> true
                },
                modifier = Modifier.fillMaxWidth(),
            )

            // Includes the final step: an over-tap on "Continue" should never be
            // a one-way door.
            if (step in 1..5) {
                Spacer(Modifier.height(8.dp))
                QuietButton("Back", { step-- }, Modifier.fillMaxWidth())
            }
        }
    }
}

private suspend fun seedStarterHabits(graph: BastionGraph, faithMode: Boolean) {
    val catalogue = graph.growth.catalogue().filter { it.visibleIn(faithMode) }
    // Three is enough to build a rhythm and few enough to actually keep.
    catalogue.take(3).forEachIndexed { index, def -> graph.growth.adoptHabit(def, index) }
}

@Composable
private fun StepIndicator(step: Int, total: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { index ->
            Box(
                Modifier
                    .weight(1f)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    // `index < step`, not `<=`: the welcome screen used to show
                    // its first segment already filled, as if a step had been
                    // completed before anything was done.
                    .background(if (index < step) BastionColors.Bronze else BastionColors.SurfaceHigh)
            )
        }
    }
}

@Composable
private fun WelcomeStep() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(40.dp))
        Text("◇", style = MaterialTheme.typography.displayLarge, color = BastionColors.Bronze)
        Spacer(Modifier.height(28.dp))
        Text(
            "Bastion",
            style = MaterialTheme.typography.displayMedium,
            color = BastionColors.TextPrimary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Not built on shame — shame is what keeps the cycle turning.\n\n" +
                "Built on who you're becoming.",
            style = MaterialTheme.typography.bodyLarge,
            color = BastionColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "Three minutes.",
            style = MaterialTheme.typography.labelMedium,
            color = BastionColors.TextMuted,
        )
    }
}

@Composable
private fun ModeStep(faithMode: Boolean, onChange: (Boolean) -> Unit) {
    Column {
        SectionLabel("Choose your language")
        Spacer(Modifier.height(8.dp))
        Text(
            "Switch any time.",
            style = MaterialTheme.typography.bodySmall,
            color = BastionColors.TextMuted,
        )
        Spacer(Modifier.height(20.dp))

        ChoiceCard(
            title = "Faith",
            body = "Scripture and prayer. Grace-centred.",
            selected = faithMode,
            onClick = { onChange(true) },
        )
        Spacer(Modifier.height(12.dp))
        ChoiceCard(
            title = "Discipline",
            body = "Stoic self-mastery. No religious language.",
            selected = !faithMode,
            onClick = { onChange(false) },
        )
    }
}

@Composable
private fun ChoiceCard(title: String, body: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) BastionColors.SurfaceHigh else BastionColors.Surface)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) BastionColors.Bronze else BastionColors.OutlineSoft,
                shape = RoundedCornerShape(18.dp),
            )
            .clickable(onClick = onClick)
            .padding(20.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            color = if (selected) BastionColors.BronzeBright else BastionColors.TextPrimary,
        )
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = BastionColors.TextSecondary)
    }
}

private val TRIGGER_OPTIONS = listOf(
    "Late at night", "Boredom", "Stress", "Loneliness", "Tiredness",
    "After social media", "Anger", "Being alone at home", "Anxiety", "Alcohol",
)

private val FREQUENCY_OPTIONS = listOf("Most days", "A few times a week", "About weekly", "Less than weekly")

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DiagnosticStep(
    name: String,
    onName: (String) -> Unit,
    frequency: String,
    onFrequency: (String) -> Unit,
    triggers: MutableList<String>,
) {
    Column {
        SectionLabel("An honest baseline")
        Spacer(Modifier.height(12.dp))
        Text(
            "Stays on your phone. It's how you'll see how far you've come.",
            style = MaterialTheme.typography.bodySmall,
            color = BastionColors.TextMuted,
        )
        Spacer(Modifier.height(22.dp))

        OutlinedTextField(
            value = name,
            onValueChange = onName,
            label = { Text("What should Bastion call you?") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = bastionFieldColors(),
        )

        Spacer(Modifier.height(24.dp))
        SectionLabel("Roughly how often, right now?")
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FREQUENCY_OPTIONS.forEach { option ->
                Chip(option, frequency == option) { onFrequency(option) }
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel("When is it hardest? Pick any.")
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TRIGGER_OPTIONS.forEach { option ->
                Chip(option, triggers.contains(option)) {
                    if (triggers.contains(option)) triggers.remove(option) else triggers.add(option)
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) BastionColors.BronzeDeep else BastionColors.Surface)
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
private fun WhyStep(
    whyText: String,
    onWhyText: (String) -> Unit,
    videoPath: String?,
    onVideoPath: (String?) -> Unit,
) {
    val context = LocalContext.current
    var pendingUri by remember { mutableStateOf<Uri?>(null) }

    val recorder = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { success ->
        if (success) onVideoPath(pendingUri?.toString())
    }

    Column {
        SectionLabel("Your why")
        Spacer(Modifier.height(12.dp))
        Text(
            "You'll see this at the exact moment you need it.",
            style = MaterialTheme.typography.bodySmall,
            color = BastionColors.TextMuted,
        )
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = whyText,
            onValueChange = onWhyText,
            label = { Text("I'm doing this because…") },
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp),
            shape = RoundedCornerShape(12.dp),
            colors = bastionFieldColors(),
        )

        Spacer(Modifier.height(20.dp))
        Text(
            "Stronger still: thirty seconds on camera. Your own face at 1am works.",
            style = MaterialTheme.typography.bodySmall,
            color = BastionColors.TextMuted,
        )
        Spacer(Modifier.height(12.dp))

        QuietButton(
            text = if (videoPath == null) "Record my why" else "Re-record",
            onClick = {
                val file = File(context.filesDir, "why_video.mp4")
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context, "${context.packageName}.files", file,
                )
                pendingUri = uri
                recorder.launch(uri)
            },
            modifier = Modifier.fillMaxWidth(),
            accent = BastionColors.SageBright,
        )
        if (videoPath != null) {
            Spacer(Modifier.height(10.dp))
            Text("Recorded. It stays on this device.", style = MaterialTheme.typography.bodySmall, color = BastionColors.SageBright)
        }
    }
}

@Composable
private fun CovenantStep(
    oath: String,
    strokes: MutableList<Stroke2D>,
    onPadSized: (androidx.compose.ui.unit.IntSize) -> Unit,
) {
    Column {
        SectionLabel("The covenant")
        Spacer(Modifier.height(18.dp))
        Text(oath, style = ScriptureStyle, color = BastionColors.TextPrimary)
        Spacer(Modifier.height(28.dp))
        SignaturePad(strokes = strokes, modifier = Modifier.onSizeChanged(onPadSized))
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            com.bastion.app.core.design.LinkButton(
                "Clear",
                BastionColors.TextMuted,
            ) { strokes.clear() }
        }
        // The oath is long enough to fill the scroll area exactly, which left
        // "Clear" half-cut against the button row. Buy it some room.
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun GuardrailsStep() {
    val context = LocalContext.current
    val notifications = com.bastion.app.core.design.rememberNotificationPermission()
    Column {
        SectionLabel("Your guards")
        Spacer(Modifier.height(12.dp))
        Text(
            "Instagram opens. Reels doesn't. Same for Shorts and For You.",
            style = MaterialTheme.typography.bodyLarge,
            color = BastionColors.TextPrimary,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Reads which screen you're on — never its contents. Nothing leaves this phone.",
            style = MaterialTheme.typography.bodySmall,
            color = BastionColors.TextMuted,
        )
        Spacer(Modifier.height(22.dp))
        QuietButton(
            text = "Turn on Bastion Guard",
            onClick = { BastionAccessibilityService.openSettings(context) },
            modifier = Modifier.fillMaxWidth(),
            accent = BastionColors.BronzeBright,
        )
        // Requested here rather than silently declared. Without it every
        // notification Bastion posts is dropped without a word — the daily brief
        // and the warning that Guard is off both vanish.
        if (notifications.needed && !notifications.granted) {
            Spacer(Modifier.height(10.dp))
            QuietButton(
                text = "Allow the daily brief",
                onClick = { notifications.requestIfNeeded() },
                modifier = Modifier.fillMaxWidth(),
                accent = BastionColors.SageBright,
            )
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "Or set it up later in the Guard tab.",
            style = MaterialTheme.typography.bodySmall,
            color = BastionColors.TextMuted,
        )
    }
}

@Composable
private fun bastionFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = BastionColors.Bronze,
    unfocusedBorderColor = BastionColors.Outline,
    focusedLabelColor = BastionColors.BronzeBright,
    unfocusedLabelColor = BastionColors.TextMuted,
    focusedTextColor = BastionColors.TextPrimary,
    unfocusedTextColor = BastionColors.TextPrimary,
    cursorColor = BastionColors.Bronze,
)

private fun oathText(faithMode: Boolean, name: String): String {
    val who = name.trim().ifBlank { "I" }
    return if (faithMode) {
        "I, $who, make a covenant with my eyes.\n\n" +
            "Not because I am strong, but because I am loved, and because the man God is making " +
            "of me has no use for this.\n\n" +
            "When I fall, I will not hide. I will get up, tell the truth, and keep walking. " +
            "Grace is not a reward for holding the line — it is why I can."
    } else {
        "I, $who, give my word to myself.\n\n" +
            "Not because I am ashamed, but because I have decided who I am becoming, and this " +
            "does not belong to him.\n\n" +
            "When I fall, I will not hide it or quit. I will look at it honestly, learn what it " +
            "cost me, and keep walking. My word to myself is worth keeping."
    }
}

