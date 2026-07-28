package com.bastion.app.feature.settings

import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import com.bastion.app.core.alarm.DailyBriefScheduler
import com.bastion.app.core.design.BastionCard
import com.bastion.app.core.design.BastionColors
import com.bastion.app.core.design.DawnBackground
import com.bastion.app.core.design.PrimaryButton
import com.bastion.app.core.design.QuietButton
import com.bastion.app.core.design.SectionLabel
import com.bastion.app.core.update.UpdateChecker
import com.bastion.app.data.BastionGraph
import com.bastion.app.data.prefs.Settings
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = remember { BastionGraph.from(context) }
    val scope = rememberCoroutineScope()
    val settings by graph.settings.settings.collectAsStateWithLifecycle(initialValue = Settings())

    DawnBackground(intensity = 0.3f) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 52.dp, bottom = 32.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Settings", style = MaterialTheme.typography.displaySmall, color = BastionColors.TextPrimary)
                Text(
                    "Close",
                    style = MaterialTheme.typography.labelLarge,
                    color = BastionColors.TextMuted,
                    modifier = Modifier.clickable(onClick = onBack),
                )
            }
            Spacer(Modifier.height(20.dp))

            // --- Mode ---
            BastionCard {
                SectionLabel("Mode")
                Spacer(Modifier.height(10.dp))
                Text(
                    "Same engine, two vocabularies. Switching changes the daily briefs, the library, " +
                        "the Mentor's voice and your rank titles. Nothing you have built is affected.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BastionColors.TextSecondary,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ModeButton("Faith", settings.faithMode) {
                        scope.launch { graph.settings.setFaithMode(true) }
                    }
                    ModeButton("Discipline", !settings.faithMode) {
                        scope.launch { graph.settings.setFaithMode(false) }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // --- Daily brief ---
            BastionCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Daily brief", style = MaterialTheme.typography.titleMedium, color = BastionColors.TextPrimary)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Each morning: an anchor, a word tied to where you are, and one thing to do today.",
                            style = MaterialTheme.typography.bodySmall,
                            color = BastionColors.TextMuted,
                        )
                    }
                    Switch(
                        checked = settings.briefEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                graph.settings.setBriefEnabled(enabled)
                                if (enabled) DailyBriefScheduler.schedule(context, settings.briefHour, settings.briefMinute)
                                else DailyBriefScheduler.cancel(context)
                            }
                        },
                        colors = switchColors(),
                    )
                }
                if (settings.briefEnabled) {
                    Spacer(Modifier.height(14.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Arrives at", style = MaterialTheme.typography.bodyMedium, color = BastionColors.TextSecondary)
                        Text(
                            "%02d:%02d".format(settings.briefHour, settings.briefMinute),
                            style = MaterialTheme.typography.headlineSmall,
                            color = BastionColors.BronzeBright,
                            modifier = Modifier.clickable {
                                TimePickerDialog(
                                    context,
                                    { _, hour, minute ->
                                        scope.launch {
                                            graph.settings.setBriefTime(hour, minute)
                                            DailyBriefScheduler.schedule(context, hour, minute)
                                        }
                                    },
                                    settings.briefHour,
                                    settings.briefMinute,
                                    true,
                                ).show()
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            UpdateCard(settings = settings, graph = graph)
            Spacer(Modifier.height(12.dp))

            // --- Journey ---
            BastionCard {
                SectionLabel("Your journey")
                Spacer(Modifier.height(10.dp))
                val start = settings.journeyStartEpochDay
                Text(
                    if (start > 0) "Started ${LocalDate.ofEpochDay(start)}" else "Not started",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BastionColors.TextSecondary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Bastion is designed to be installed once. Updates land on top and keep everything — " +
                        "your covenant, your signature, every day you have counted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BastionColors.TextMuted,
                )
            }

            Spacer(Modifier.height(12.dp))

            // --- Privacy ---
            BastionCard(accent = BastionColors.Sage) {
                SectionLabel("Privacy", color = BastionColors.SageBright)
                Spacer(Modifier.height(10.dp))
                Text(
                    "No account. No analytics. No telemetry. Your covenant, your logs and your Why video " +
                        "live in this app's private storage and are excluded from Google's cloud backup, " +
                        "so they cannot leave that way either.\n\n" +
                        "Bastion Guard reads which screen is open and nothing else — never messages, never " +
                        "photos, never field contents. Learn Mode captures screen identifiers only.\n\n" +
                        "The only network traffic Bastion ever makes is forwarding DNS lookups to your " +
                        "chosen resolver" +
                        if (settings.updateManifestUrl.isNotBlank())
                            ", and checking the update address you entered above."
                        else ".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BastionColors.TextSecondary,
                )
            }

            Spacer(Modifier.height(12.dp))
            BastionCard {
                Text(
                    "Bastion is a support tool, not treatment. If this sits alongside anxiety, depression, " +
                        "trauma or something heavier, that deserves a real clinician — and asking for one is " +
                        "not a failure of will.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BastionColors.TextMuted,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Version ${UpdateChecker.currentVersion}",
                    style = MaterialTheme.typography.labelSmall,
                    color = BastionColors.TextMuted,
                )
            }
        }
    }
}

@Composable
private fun UpdateCard(settings: Settings, graph: BastionGraph) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val checker = remember { UpdateChecker(context) }

    var url by remember(settings.updateManifestUrl) { mutableStateOf(settings.updateManifestUrl) }
    var status by remember { mutableStateOf<String?>(null) }
    var available by remember { mutableStateOf<UpdateChecker.Manifest?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var ready by remember { mutableStateOf<File?>(null) }

    BastionCard(accent = if (available != null) BastionColors.Bronze else null) {
        SectionLabel("Updates")
        Spacer(Modifier.height(10.dp))
        Text(
            "Installed: ${UpdateChecker.currentVersion}",
            style = MaterialTheme.typography.bodyMedium,
            color = BastionColors.TextPrimary,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Point this at wherever you publish new builds and Bastion will update itself in place. " +
                "Leave it blank and the app never touches the network beyond DNS.",
            style = MaterialTheme.typography.bodySmall,
            color = BastionColors.TextMuted,
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Update manifest URL") },
            placeholder = { Text("https://…/bastion-update.json") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = BastionColors.Bronze,
                unfocusedBorderColor = BastionColors.Outline,
                focusedLabelColor = BastionColors.BronzeBright,
                unfocusedLabelColor = BastionColors.TextMuted,
                focusedTextColor = BastionColors.TextPrimary,
                unfocusedTextColor = BastionColors.TextPrimary,
                cursorColor = BastionColors.Bronze,
            ),
        )

        if (url != settings.updateManifestUrl) {
            Spacer(Modifier.height(10.dp))
            QuietButton(
                "Save address",
                { scope.launch { graph.settings.setUpdateUrl(url) } },
                Modifier.fillMaxWidth(),
                BastionColors.BronzeBright,
            )
        }

        if (settings.updateManifestUrl.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Check automatically",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BastionColors.TextSecondary,
                )
                Switch(
                    checked = settings.autoCheckUpdates,
                    onCheckedChange = { scope.launch { graph.settings.setAutoCheckUpdates(it) } },
                    colors = switchColors(),
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        when {
            busy -> {
                Text(
                    status ?: "Working…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BastionColors.TextSecondary,
                )
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = BastionColors.Bronze,
                    trackColor = BastionColors.SurfaceHigh,
                )
            }

            ready != null -> {
                Text(
                    "Verified and ready to install. Your data stays exactly where it is.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BastionColors.SageBright,
                )
                Spacer(Modifier.height(12.dp))
                PrimaryButton(
                    "Install now",
                    {
                        if (checker.canInstall()) checker.install(ready!!)
                        else checker.openInstallPermissionSettings()
                    },
                    Modifier.fillMaxWidth(),
                )
            }

            available != null -> {
                val manifest = available!!
                Text(
                    "Version ${manifest.versionName} is available.",
                    style = MaterialTheme.typography.titleMedium,
                    color = BastionColors.BronzeBright,
                )
                if (manifest.notes.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(manifest.notes, style = MaterialTheme.typography.bodyMedium, color = BastionColors.TextSecondary)
                }
                Spacer(Modifier.height(12.dp))
                PrimaryButton(
                    "Download",
                    {
                        scope.launch {
                            busy = true
                            status = "Downloading ${manifest.versionName}…"
                            val result = checker.download(manifest) { progress = it }
                            busy = false
                            result.onSuccess { ready = it; status = null }
                                .onFailure { status = it.message }
                        }
                    },
                    Modifier.fillMaxWidth(),
                )
            }

            else -> {
                status?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = BastionColors.TextSecondary)
                    Spacer(Modifier.height(10.dp))
                }
                QuietButton(
                    "Check for updates",
                    {
                        scope.launch {
                            busy = true
                            status = "Checking…"
                            when (val result = checker.check(settings.updateManifestUrl)) {
                                is UpdateChecker.Result.Available -> {
                                    available = result.manifest
                                    status = null
                                }
                                UpdateChecker.Result.UpToDate -> status = "You're on the latest version."
                                UpdateChecker.Result.NotConfigured -> status = "Set an update address first."
                                is UpdateChecker.Result.Failed -> status = result.reason
                            }
                            graph.settings.markUpdateChecked()
                            busy = false
                        }
                    },
                    Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) BastionColors.BronzeDeep else BastionColors.SurfaceRaised)
            .border(
                if (selected) 1.5.dp else 1.dp,
                if (selected) BastionColors.Bronze else BastionColors.Outline,
                RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) BastionColors.BronzeBright else BastionColors.TextMuted,
        )
    }
}

@Composable
private fun switchColors() = SwitchDefaults.colors(
    checkedThumbColor = BastionColors.MidnightDeep,
    checkedTrackColor = BastionColors.Bronze,
    uncheckedThumbColor = BastionColors.TextMuted,
    uncheckedTrackColor = BastionColors.SurfaceHigh,
    uncheckedBorderColor = BastionColors.Outline,
)
