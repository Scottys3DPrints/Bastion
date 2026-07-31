package com.bastion.app.feature.brotherhood

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

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
    var confirmRemove by remember { mutableStateOf(false) }
    val current = partner

    // Deleting the partner used to delete their passcode along with them, which
    // meant the lock could be lifted by removing the person holding it — a clean
    // bypass of the one mechanism meant to be un-bypassable. While the lock is
    // armed, removal is refused and says why.
    val settings by graph.settings.settings.collectAsStateWithLifecycle(
        initialValue = com.bastion.app.data.prefs.Settings()
    )
    var lockHasCode by remember { mutableStateOf(false) }
    LaunchedEffect(settings.partnerLockEnabled) { lockHasCode = graph.social.hasPasscode() }
    val removalLocked = settings.partnerLockEnabled && lockHasCode

    DawnBackground(intensity = 0.35f) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 52.dp, bottom = 32.dp)
        ) {
            Text("Brotherhood", style = MaterialTheme.typography.displaySmall, color = BastionColors.TextPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                "One person who knows beats any blocker.",
                style = MaterialTheme.typography.bodySmall,
                color = BastionColors.TextMuted,
            )
            Spacer(Modifier.height(20.dp))

            if (current == null) {
                BastionCard {
                    SectionLabel("Your partner")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Someone who'll take a message at midnight.",
                        style = MaterialTheme.typography.bodySmall,
                        color = BastionColors.TextMuted,
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
                        "Bastion writes the message. You press send.",
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
                        enabled = name.isNotBlank() && contact.isTextable(),
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
                                        .putExtra("sms_body", "Got a minute? Could use a word.")
                                )
                            },
                            Modifier.weight(1f),
                            enabled = current.contact.isTextable(),
                        )
                        QuietButton(
                            "Remove",
                            { confirmRemove = true },
                            Modifier.weight(1f),
                        )
                    }
                }
            }

            if (current != null) {
                Spacer(Modifier.height(14.dp))
                PartnerLockCard(graph)
            }

            Spacer(Modifier.height(14.dp))

            BastionCard {
                SectionLabel("Today's check-in")
                Spacer(Modifier.height(10.dp))
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
                    if (current != null && current.contact.isTextable()) {
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
                        "None yet.",
                        style = MaterialTheme.typography.bodySmall,
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
                            checkInDate(entry.epochDay),
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
                    "That's its own kind of hard, and worth naming. The Mentor is here meanwhile.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BastionColors.TextSecondary,
                )
                Spacer(Modifier.height(14.dp))
                LinkButton("Talk to the Mentor →", BastionColors.SageBright, onOpenMentor)
            }
        }
    }

    if (confirmRemove && current != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            containerColor = BastionColors.Surface,
            titleContentColor = BastionColors.TextPrimary,
            textContentColor = BastionColors.TextSecondary,
            title = {
                Text(
                    if (removalLocked) "The partner lock is on" else "Remove ${current.name}?",
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                Text(
                    if (removalLocked)
                        "Removing ${current.name} would delete the code they hold, which would lift the " +
                            "lock. Turn the partner lock off first — that needs their code."
                    else "Your check-ins stay. You can add them back any time.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                if (!removalLocked) {
                    LinkButton("Remove", BastionColors.BronzeBright) {
                        scope.launch { graph.social.removePartner(current.id) }
                        confirmRemove = false
                    }
                } else {
                    LinkButton("All right", BastionColors.TextMuted) { confirmRemove = false }
                }
            },
            dismissButton = {
                if (!removalLocked) {
                    LinkButton("Not now", BastionColors.TextMuted) { confirmRemove = false }
                }
            },
        )
    }
}

/**
 * Hands the partner a code that gates every weakening of a guard.
 *
 * The point is that the man setting it up should not be the man who can undo it
 * alone at 1am. So the code is meant to be typed *by the partner*, on this
 * phone, and not written down anywhere the user can reach — the screen says so
 * plainly, because a lock the owner knows the combination to is just a delay.
 */
@Composable
private fun PartnerLockCard(graph: BastionGraph) {
    val scope = rememberCoroutineScope()
    val settings by graph.settings.settings.collectAsStateWithLifecycle(
        initialValue = com.bastion.app.data.prefs.Settings()
    )
    var hasCode by remember { mutableStateOf(false) }
    var entering by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var needsPartner by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { hasCode = graph.social.hasPasscode() }

    BastionCard(accent = if (settings.partnerLockEnabled && hasCode) BastionColors.Bronze else null) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Partner lock",
                    style = MaterialTheme.typography.titleMedium,
                    color = BastionColors.TextPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (hasCode) "Weakening a guard needs his code."
                    else "Set a code only he knows.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BastionColors.TextMuted,
                )
            }
            Switch(
                checked = settings.partnerLockEnabled && hasCode,
                onCheckedChange = { wanted ->
                    if (wanted && !hasCode) entering = true
                    else scope.launch { graph.settings.setPartnerLock(wanted) }
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = BastionColors.MidnightDeep,
                    checkedTrackColor = BastionColors.Bronze,
                    uncheckedThumbColor = BastionColors.TextMuted,
                    uncheckedTrackColor = BastionColors.SurfaceHigh,
                    uncheckedBorderColor = BastionColors.Outline,
                ),
            )
        }

        if (needsPartner) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Save your partner's name and number first — the code hangs off him.",
                style = MaterialTheme.typography.bodySmall,
                color = BastionColors.Amber,
            )
        }

        if (entering) {
            Spacer(Modifier.height(14.dp))
            Text(
                "Hand him the phone. What he types here, you shouldn't know.",
                style = MaterialTheme.typography.bodySmall,
                color = BastionColors.SageBright,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = code,
                // Filtered rather than merely validated: the NumberPassword
                // keyboard still offers a comma and a minus sign, and a code
                // with one in it is a code his partner cannot retype.
                onValueChange = { entered -> code = entered.filter(Char::isDigit).take(12) },
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword,
                ),
                label = { Text("His code") },
                supportingText = {
                    Text(
                        "At least 6 digits.",
                        style = MaterialTheme.typography.bodySmall,
                        color = BastionColors.TextMuted,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors(),
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(
                    "Lock it",
                    {
                        scope.launch {
                            // Only arm the lock if the code actually landed. It
                            // cannot land with no partner saved, and switching
                            // the lock on regardless made the UI claim a
                            // protection that did not exist.
                            if (graph.social.setPartnerPasscode(code)) {
                                graph.settings.setPartnerLock(true)
                                hasCode = true
                                code = ""
                                entering = false
                            } else {
                                needsPartner = true
                            }
                        }
                    },
                    Modifier.weight(1f),
                    enabled = code.length >= graph.social.minPasscodeLength,
                )
                QuietButton("Cancel", { code = ""; entering = false }, Modifier.weight(1f))
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

/**
 * Text-styled actions that are still real buttons — button semantics and a 48dp
 * target, because this screen gets used one-handed at the worst hour.
 */
@Composable
private fun LinkButton(
    label: String,
    color: Color = BastionColors.BronzeBright,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = color),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Permissive on purpose: people write numbers with spaces, dashes, brackets and
 * a country code. Only the digit count says whether a `smsto:` can go anywhere.
 */
private fun String.isTextable(): Boolean = count(Char::isDigit) >= 6

/** A date the man can place without doing arithmetic. */
private fun checkInDate(epochDay: Long): String {
    val date = LocalDate.ofEpochDay(epochDay)
    return when (LocalDate.now().toEpochDay() - epochDay) {
        0L -> "Today"
        1L -> "Yesterday"
        in 2L..6L -> date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        else -> date.format(DateTimeFormatter.ofPattern("d MMM"))
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
