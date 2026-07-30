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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = remember { BastionGraph.from(context) }
    val scope = rememberCoroutineScope()
    val settings by graph.settings.settings.collectAsStateWithLifecycle(initialValue = Settings())
    val notifications = com.bastion.app.core.design.rememberNotificationPermission()

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
                Spacer(Modifier.height(8.dp))
                Text(
                    "Changes the writing, not your progress.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BastionColors.TextMuted,
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
                            "An anchor and one thing to do.",
                            style = MaterialTheme.typography.bodySmall,
                            color = BastionColors.TextMuted,
                        )
                    }
                    Switch(
                        checked = settings.briefEnabled,
                        onCheckedChange = { enabled ->
                            // Ask for notification permission at the moment the
                            // brief is switched on. Scheduling an alarm whose
                            // notification the system will silently drop is the
                            // kind of feature that looks fine and does nothing.
                            if (enabled) notifications.requestIfNeeded()
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
            BackupCard(graph = graph)

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
            }

            Spacer(Modifier.height(12.dp))

            // --- Privacy ---
            BastionCard(accent = BastionColors.Sage) {
                SectionLabel("Privacy", color = BastionColors.SageBright)
                Spacer(Modifier.height(12.dp))
                listOf(
                    "No account, no analytics, no telemetry",
                    "Everything stays in private storage, cloud backup off",
                    "Guard reads which screen is open — never its contents",
                    "Network use: DNS lookups, and update checks you trigger",
                ).forEach { line ->
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Text("·  ", style = MaterialTheme.typography.bodyMedium, color = BastionColors.SageBright)
                        Text(
                            line,
                            style = MaterialTheme.typography.bodyMedium,
                            color = BastionColors.TextSecondary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            BastionCard {
                Text(
                    "A support tool, not treatment. Anything heavier deserves a real clinician — " +
                        "asking is not a failure of will.",
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

/**
 * Backup, for the one risk a deliberately server-less app creates.
 *
 * Nothing here syncs anywhere, which is the right call for this data — but the
 * cost is that a lost or reset phone takes the covenant, the streak and the
 * whole journey with it. A file the user holds, sealed with a passphrase only he
 * knows, is the answer that does not require trusting anyone.
 */
@Composable
private fun BackupCard(graph: BastionGraph) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf<BackupMode?>(null) }
    var passphrase by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<ByteArray?>(null) }

    val createFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val bytes = pending
        pending = null
        if (uri == null || bytes == null) return@rememberLauncherForActivityResult
        scope.launch {
            status = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                }
                "Saved. Keep the passphrase safe — it cannot be recovered."
            }.getOrElse { "Could not write the file." }
        }
    }

    val openFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val code = passphrase
        scope.launch {
            status = runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: error("empty")
                val days = graph.backup.import(bytes, code)
                "Restored. $days days of history are back."
            }.getOrElse {
                if (it is com.bastion.app.core.security.BackupCodec.WrongPassphrase) {
                    "Wrong passphrase, or not a Bastion backup."
                } else {
                    "Could not read that file."
                }
            }
            passphrase = ""
        }
    }

    BastionCard {
        SectionLabel("Backup")
        Spacer(Modifier.height(6.dp))
        Text(
            "An encrypted file you keep. Nothing is uploaded.",
            style = MaterialTheme.typography.bodySmall,
            color = BastionColors.TextMuted,
        )
        Spacer(Modifier.height(14.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuietButton("Export", { mode = BackupMode.EXPORT; status = null }, Modifier.weight(1f))
            QuietButton("Restore", { mode = BackupMode.IMPORT; status = null }, Modifier.weight(1f))
        }

        status?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = BastionColors.SageBright)
        }
    }

    mode?.let { current ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { mode = null; passphrase = "" },
            containerColor = BastionColors.Surface,
            title = {
                Text(
                    if (current == BackupMode.EXPORT) "Choose a passphrase" else "Enter the passphrase",
                    color = BastionColors.TextPrimary,
                )
            },
            text = {
                Column {
                    Text(
                        if (current == BackupMode.EXPORT)
                            "Forget it and the backup is gone. There is no recovery — that is what keeps it private."
                        else
                            "The passphrase you chose when exporting.",
                        style = MaterialTheme.typography.bodySmall,
                        color = BastionColors.TextMuted,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BastionColors.Bronze,
                            unfocusedBorderColor = BastionColors.Outline,
                            focusedTextColor = BastionColors.TextPrimary,
                            unfocusedTextColor = BastionColors.TextPrimary,
                            cursorColor = BastionColors.Bronze,
                        ),
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    enabled = com.bastion.app.core.security.BackupCodec.isAcceptable(passphrase),
                    onClick = {
                        val code = passphrase
                        mode = null
                        if (current == BackupMode.EXPORT) {
                            scope.launch {
                                pending = graph.backup.export(code)
                                passphrase = ""
                                createFile.launch(graph.backup.suggestedFileName())
                            }
                        } else {
                            openFile.launch(arrayOf("*/*"))
                        }
                    },
                ) {
                    Text(
                        if (current == BackupMode.EXPORT) "Export" else "Restore",
                        color = BastionColors.BronzeBright,
                    )
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { mode = null; passphrase = "" }) {
                    Text("Cancel", color = BastionColors.TextMuted)
                }
            },
        )
    }
}

private enum class BackupMode { EXPORT, IMPORT }

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
        Spacer(Modifier.height(6.dp))
        Text(
            "Updates install over the top. Nothing is lost.",
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
