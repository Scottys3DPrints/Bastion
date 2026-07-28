package com.bastion.app.feature.brotherhood

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.bastion.app.core.design.BastionCard
import com.bastion.app.core.design.BastionColors
import com.bastion.app.core.design.DawnBackground
import com.bastion.app.core.design.PrimaryButton
import com.bastion.app.core.design.QuietButton
import com.bastion.app.core.design.SectionLabel
import com.bastion.app.data.BastionGraph
import kotlinx.coroutines.launch

/**
 * Brotherhood.
 *
 * Accountability, never surveillance. Every share is opt-in, chosen by the man
 * himself, and sent by him — Bastion opens a message he can read and edit rather
 * than reporting on him behind his back. A partner who is watching you is a
 * different and far more fraught product than a partner you are walking with.
 */
@Composable
fun BrotherhoodScreen(onOpenMentor: () -> Unit) {
    val context = LocalContext.current
    val graph = remember { BastionGraph.from(context) }
    val scope = rememberCoroutineScope()

    val partner by graph.social.partner.collectAsStateWithLifecycle(initialValue = null)
    val checkIns by graph.journey.checkIns.collectAsStateWithLifecycle(initialValue = emptyList())

    var name by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var shareSlips by remember { mutableStateOf(false) }
    var shareGuard by remember { mutableStateOf(true) }
    var mood by remember { mutableFloatStateOf(3f) }
    var note by remember { mutableStateOf("") }

    DawnBackground(intensity = 0.35f) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 52.dp, bottom = 32.dp)
        ) {
            Text("Brotherhood", style = MaterialTheme.typography.displaySmall, color = BastionColors.TextPrimary)
            Spacer(Modifier.height(8.dp))
            Text(
                "Isolation is where this wins. One person who knows changes the odds more than any blocker.",
                style = MaterialTheme.typography.bodyMedium,
                color = BastionColors.TextSecondary,
            )
            Spacer(Modifier.height(20.dp))

            val current = partner
            if (current == null) {
                BastionCard {
                    SectionLabel("Your partner")
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "A friend, a mentor, a pastor, a sponsor — someone who will take a message at " +
                            "midnight and not think less of you.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = BastionColors.TextSecondary,
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Their name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = contact,
                        onValueChange = { contact = it },
                        label = { Text("Phone number") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors(),
                    )
                    Spacer(Modifier.height(16.dp))
                    ShareToggle("Tell them when I slip", shareSlips) { shareSlips = it }
                    ShareToggle("Tell them if I weaken a guard", shareGuard) { shareGuard = it }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Bastion never sends anything on its own. It writes the message and hands it to " +
                            "you — you press send.",
                        style = MaterialTheme.typography.bodySmall,
                        color = BastionColors.TextMuted,
                    )
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton(
                        "Save",
                        {
                            scope.launch {
                                graph.social.savePartner(
                                    name = name.trim(),
                                    contact = contact.trim(),
                                    shareCheckIns = true,
                                    shareSlips = shareSlips,
                                    shareGuardChanges = shareGuard,
                                )
                            }
                        },
                        Modifier.fillMaxWidth(),
                        enabled = name.isNotBlank() && contact.isNotBlank(),
                    )
                }
            } else {
                BastionCard(accent = BastionColors.Sage) {
                    SectionLabel("Walking with you")
                    Spacer(Modifier.height(8.dp))
                    Text(current.name, style = MaterialTheme.typography.headlineSmall, color = BastionColors.TextPrimary)
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton(
                            "Message",
                            {
                                context.startActivity(
                                    Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${current.contact}"))
                                )
                            },
                            Modifier.weight(1f),
                        )
                        QuietButton(
                            "Remove",
                            { scope.launch { graph.social.removePartner(current.id) } },
                            Modifier.weight(1f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            BastionCard {
                SectionLabel("Today's check-in")
                Spacer(Modifier.height(12.dp))
                Text(
                    "How is it, honestly?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BastionColors.TextSecondary,
                )
                Slider(
                    value = mood,
                    onValueChange = { mood = it },
                    valueRange = 1f..5f,
                    steps = 3,
                    colors = SliderDefaults.colors(
                        thumbColor = BastionColors.Bronze,
                        activeTrackColor = BastionColors.Bronze,
                        inactiveTrackColor = BastionColors.SurfaceHigh,
                    ),
                )
                Text(
                    moodLabel(mood.toInt()),
                    style = MaterialTheme.typography.labelMedium,
                    color = BastionColors.BronzeBright,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Anything you want to say") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors(),
                )
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton(
                        "Log it",
                        {
                            scope.launch {
                                graph.journey.checkIn(mood.toInt(), note.takeIf { it.isNotBlank() })
                                note = ""
                            }
                        },
                        Modifier.weight(1f),
                    )
                    if (current != null) {
                        QuietButton(
                            "Send it",
                            {
                                scope.launch {
                                    graph.journey.checkIn(mood.toInt(), note.takeIf { it.isNotBlank() })
                                }
                                context.startActivity(
                                    Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${current.contact}"))
                                        .putExtra(
                                            "sms_body",
                                            "Check-in: ${moodLabel(mood.toInt()).lowercase()}." +
                                                if (note.isNotBlank()) " $note" else "",
                                        )
                                )
                                note = ""
                            },
                            Modifier.weight(1f),
                            BastionColors.SageBright,
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            BastionCard {
                SectionLabel("Recent check-ins")
                Spacer(Modifier.height(12.dp))
                if (checkIns.isEmpty()) {
                    Text(
                        "None yet. Even a one-word check-in on a flat day is worth more than a paragraph " +
                            "on a good one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = BastionColors.TextMuted,
                    )
                }
                checkIns.take(10).forEach { entry ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                moodLabel(entry.mood),
                                style = MaterialTheme.typography.bodyMedium,
                                color = BastionColors.TextPrimary,
                            )
                            entry.note?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = BastionColors.TextMuted)
                            }
                        }
                        Text(
                            java.time.LocalDate.ofEpochDay(entry.epochDay).toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = BastionColors.TextMuted,
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            BastionCard {
                SectionLabel("No one to tell yet?")
                Spacer(Modifier.height(8.dp))
                Text(
                    "That is its own kind of hard, and it is worth naming rather than pushing down. " +
                        "The Mentor is here in the meantime — and it will point you to real human support " +
                        "when that is what's needed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BastionColors.TextSecondary,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "Talk to the Mentor →",
                    style = MaterialTheme.typography.labelLarge,
                    color = BastionColors.SageBright,
                    modifier = Modifier.clickable(onClick = onOpenMentor),
                )
            }
        }
    }
}

@Composable
private fun ShareToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = BastionColors.TextSecondary)
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = BastionColors.MidnightDeep,
                checkedTrackColor = BastionColors.Bronze,
                uncheckedThumbColor = BastionColors.TextMuted,
                uncheckedTrackColor = BastionColors.SurfaceHigh,
                uncheckedBorderColor = BastionColors.Outline,
            ),
        )
    }
}

private fun moodLabel(mood: Int): String = when (mood) {
    1 -> "Struggling"
    2 -> "Shaky"
    3 -> "Steady"
    4 -> "Good"
    else -> "Strong"
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = BastionColors.Bronze,
    unfocusedBorderColor = BastionColors.Outline,
    focusedLabelColor = BastionColors.BronzeBright,
    unfocusedLabelColor = BastionColors.TextMuted,
    focusedTextColor = BastionColors.TextPrimary,
    unfocusedTextColor = BastionColors.TextPrimary,
    cursorColor = BastionColors.Bronze,
)
