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
import com.bastion.app.core.design.ChoiceRow
import com.bastion.app.core.design.BastionRow
import com.bastion.app.core.design.BastionScaffold
import com.bastion.app.core.design.RowDivider
import com.bastion.app.core.design.GroupDivider
import com.bastion.app.core.design.Section
import com.bastion.app.core.design.SettingsGroup
import com.bastion.app.core.design.Space
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
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenPartner: () -> Unit,
    onOpenMentor: () -> Unit,
) {
    val context = LocalContext.current
    val graph = remember { BastionGraph.from(context) }
    val scope = rememberCoroutineScope()
    val settings by graph.settings.settings.collectAsStateWithLifecycle(initialValue = Settings())
    val notifications = com.bastion.app.core.design.rememberNotificationPermission()
    var showHowItWorks by remember { mutableStateOf(false) }

    BastionScaffold(
        // "You" named nothing. A screen title should tell a stranger what is
        // behind it before they tap.
        title = "Settings",
        dawnIntensity = 0.3f,
        onBack = onBack,
    ) {
        // The two things that left the tab bar. Neither is a daily
        // destination, and both belong beside the settings that configure
        // them — a partner you can reach and a mentor you can talk to are
        // part of who is holding you to this, not a place you visit.
        SettingsGroup(
            title = "People",
            subtitle = "Who is walking this with you.",
        ) {
            BastionRow(
                title = "Your partner",
                // "Who hears about it" assumed you already knew what "it" was
                // and what hearing about it meant.
                subtitle = "The person you've asked to keep you accountable. " +
                    "They can be told if you slip or turn protection off.",
                trailing = { Text("→", color = BastionColors.SageBright) },
                onClick = onOpenPartner,
            )
            GroupDivider()
            BastionRow(
                title = "The Mentor",
                // "Offline" is a developer's word for a user's reassurance.
                subtitle = "A private guide you can talk to any time. Works with " +
                    "no internet, and nothing you say leaves your phone.",
                trailing = { Text("→", color = BastionColors.SageBright) },
                onClick = onOpenMentor,
            )
            GroupDivider()
            BastionRow(
                title = "How Bastion protects you",
                subtitle = "The four layers, in four sentences.",
                trailing = { Text("→", color = BastionColors.SageBright) },
                onClick = { showHowItWorks = true },
            )
        }

        SettingsGroup(
            title = "Mode",
            subtitle = "Faith adds scripture and prayer. Discipline keeps it secular. " +
                "Your streak, guards and settings stay the same either way.",
        ) {
            Spacer(Modifier.height(Space.md))
            ChoiceRow(
                options = listOf(true, false),
                selected = settings.faithMode,
                label = { if (it) "Faith" else "Discipline" },
                onSelect = { faith -> scope.launch { graph.settings.setFaithMode(faith) } },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Space.md))
        }


        SettingsGroup(
            title = "Daily message",
            subtitle = "A short message each morning — one thought and one small " +
                "thing to do that day.",
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Send it to me",
                        style = MaterialTheme.typography.bodyLarge,
                        color = BastionColors.TextPrimary,
                    )
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        // State, plainly, without making anyone read the switch.
                        if (settings.briefEnabled) "On — arrives as a notification"
                        else "Off — no daily message",
                        style = MaterialTheme.typography.labelSmall,
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
                GroupDivider()
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Arrives at", style = MaterialTheme.typography.bodyMedium, color = BastionColors.TextSecondary)
                    // A button, not tappable text: the time is the control
                    // here, and nothing about a plain label says so.
                    androidx.compose.material3.TextButton(
                        onClick = {
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
                    ) {
                        Text(
                            "%02d:%02d".format(settings.briefHour, settings.briefMinute),
                            style = MaterialTheme.typography.headlineSmall,
                            color = BastionColors.BronzeBright,
                        )
                    }
                }
            }
        }

        BackupCard(graph = graph)

        UpdateCard(settings = settings, graph = graph)

        SettingsGroup(
            title = "Your journey",
            subtitle = "When you started, and the option to start again.",
        ) {
            Spacer(Modifier.height(Space.md))
            val start = settings.journeyStartEpochDay
            Text(
                if (start > 0) "Started ${LocalDate.ofEpochDay(start)}" else "Not started",
                style = MaterialTheme.typography.bodyMedium,
                color = BastionColors.TextSecondary,
            )
        }


        if (showHowItWorks) HowBastionProtectsYou { showHowItWorks = false }

        // --- Privacy ---
        //
        // A group like every other group. It was the one lone Sage-accented
        // card on a screen of grouped rows, which made the section about not
        // collecting anything look like an advertisement for itself.
        SettingsGroup(
            title = "Privacy",
            subtitle = "What Bastion does and does not know about you.",
        ) {
            Spacer(Modifier.height(Space.sm))
            listOf(
                "No account, no sign-up, and nothing about you is collected",
                "Everything stays on this phone. It is never backed up to a cloud",
                "Bastion can see which screen you are on — never what is on it",
                "The only time it uses the internet: checking website addresses " +
                    "against its blocklist, and looking for updates when you ask",
            ).forEach { line ->
                Row(Modifier.padding(vertical = Space.xs)) {
                    Text("·  ", style = MaterialTheme.typography.bodyMedium, color = BastionColors.SageBright)
                    Text(
                        line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = BastionColors.TextSecondary,
                    )
                }
            }
            Spacer(Modifier.height(Space.sm))
        }

        Column(Modifier.fillMaxWidth()) {
            Text(
                "A support tool, not treatment. Anything heavier deserves a real clinician — " +
                    "asking is not a failure of will.",
                style = MaterialTheme.typography.bodySmall,
                color = BastionColors.TextMuted,
            )
            Spacer(Modifier.height(Space.md))
            Text(
                "Version ${UpdateChecker.currentVersion}",
                style = MaterialTheme.typography.labelSmall,
                color = BastionColors.TextMuted,
            )
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

    // The same container as every other group on this screen. It was a bare
    // column, so the one section that can lose a man his whole history had less
    // visual definition than the notification toggle above it.
    SettingsGroup(
        title = "Backup",
        subtitle = "Save a private, password-protected copy of your journey — streak, " +
            "covenant and history — to a file only you hold. If you lose your " +
            "phone you can restore it. Nothing is ever uploaded.",
    ) {
        Spacer(Modifier.height(Space.md))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            QuietButton("Export", { mode = BackupMode.EXPORT; status = null }, Modifier.weight(1f))
            QuietButton("Restore", { mode = BackupMode.IMPORT; status = null }, Modifier.weight(1f))
        }

        status?.let {
            Spacer(Modifier.height(Space.md))
            Text(it, style = MaterialTheme.typography.bodySmall, color = BastionColors.SageBright)
        }
        Spacer(Modifier.height(Space.md))
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
                            "Pick a password you won't forget. It is the only key to " +
                                "this file — if you lose it, nobody can open the backup, " +
                                "including us. That is what keeps it private."
                        else
                            "The passphrase you chose when exporting.",
                        style = MaterialTheme.typography.bodySmall,
                        color = BastionColors.TextMuted,
                    )
                    Spacer(Modifier.height(Space.md))
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(Space.md),
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
    var advanced by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val checker = remember { UpdateChecker(context) }

    var url by remember(settings.updateManifestUrl) { mutableStateOf(settings.updateManifestUrl) }
    var status by remember { mutableStateOf<String?>(null) }
    var available by remember { mutableStateOf<UpdateChecker.Manifest?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var ready by remember { mutableStateOf<File?>(null) }

    // One container idiom on this screen, so a group is a group whether it holds
    // a switch or a download. The card came back only when there is genuinely an
    // update waiting — that is the one time this block is an event rather than a
    // setting, and the bronze edge is how the rest of the app says so.
    SettingsGroup(
        title = if (available != null) "App update ready" else "App updates",
        subtitle = "Bastion updates itself in place — your streak, guards and " +
            "history are never touched.",
    ) {
        Spacer(Modifier.height(Space.sm))
        Text(
            "You have version ${UpdateChecker.currentVersion}",
            style = MaterialTheme.typography.bodyMedium,
            color = BastionColors.TextPrimary,
        )

        com.bastion.app.core.design.WhatsThis(
            "Bastion isn't on the Play Store, so it checks a small file on the " +
                "internet to see whether a newer version exists, then installs it " +
                "over the top of itself. It only looks when you ask it to, unless " +
                "you switch on automatic checking below."
        )

        // The address is plumbing. It was the first thing on this card, labelled
        // "Update manifest URL", which is a phrase with no meaning to anyone who
        // did not write it — and it is not a setting most people should ever
        // touch.
        com.bastion.app.core.design.LinkButton(
            if (advanced) "Hide advanced" else "Advanced",
            BastionColors.TextMuted,
        ) { advanced = !advanced }

        if (advanced) {
        Spacer(Modifier.height(Space.xs))
        Text(
            "Where Bastion looks for new versions. Leave this as it is unless " +
                "you were told to change it.",
            style = MaterialTheme.typography.bodySmall,
            color = BastionColors.TextMuted,
        )
        Spacer(Modifier.height(Space.sm))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Update address") },
            placeholder = { Text("https://…/bastion-update.json") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Space.md),
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
            Spacer(Modifier.height(Space.md))
            QuietButton(
                "Save address",
                { scope.launch { graph.settings.setUpdateUrl(url) } },
                Modifier.fillMaxWidth(),
                BastionColors.BronzeBright,
            )
        }
        }

        if (settings.updateManifestUrl.isNotBlank()) {
            Spacer(Modifier.height(Space.md))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Check on its own",
                        style = MaterialTheme.typography.bodyMedium,
                        color = BastionColors.TextSecondary,
                    )
                    Text(
                        if (settings.autoCheckUpdates) "On — looks for new versions daily"
                        else "Off — only when you tap Check",
                        style = MaterialTheme.typography.labelSmall,
                        color = BastionColors.TextMuted,
                    )
                }
                Switch(
                    checked = settings.autoCheckUpdates,
                    onCheckedChange = { scope.launch { graph.settings.setAutoCheckUpdates(it) } },
                    colors = switchColors(),
                )
            }
        }

        Spacer(Modifier.height(Space.lg))

        when {
            busy -> {
                Text(
                    status ?: "Working…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BastionColors.TextSecondary,
                )
                Spacer(Modifier.height(Space.md))
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
                Spacer(Modifier.height(Space.md))
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
                    Spacer(Modifier.height(Space.sm))
                    Text(manifest.notes, style = MaterialTheme.typography.bodyMedium, color = BastionColors.TextSecondary)
                }
                Spacer(Modifier.height(Space.md))
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
                    Spacer(Modifier.height(Space.md))
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
private fun switchColors() = SwitchDefaults.colors(
    checkedThumbColor = BastionColors.MidnightDeep,
    checkedTrackColor = BastionColors.Bronze,
    uncheckedThumbColor = BastionColors.TextMuted,
    uncheckedTrackColor = BastionColors.SurfaceHigh,
    uncheckedBorderColor = BastionColors.Outline,
)

/**
 * The four layers, in four sentences.
 *
 * The commonest confusion is not what any single switch does — it is how they
 * fit. Someone sees five toggles across two screens and cannot say which
 * protects what, so they either turn everything on without understanding it or
 * nothing on for the same reason. This is the one screen that answers "what
 * does all this even do", and it deliberately ends by admitting each layer is
 * beatable on its own; a wall is what they are together.
 */
@Composable
private fun HowBastionProtectsYou(onClose: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        containerColor = BastionColors.Surface,
        title = {
            Text(
                "How Bastion protects you",
                style = MaterialTheme.typography.headlineSmall,
                color = BastionColors.TextPrimary,
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "It works in layers.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BastionColors.TextSecondary,
                )
                Spacer(Modifier.height(Space.lg))
                listOf(
                    "Feed blocking" to
                        "Lets apps like Instagram open normally, but closes Reels, " +
                            "Shorts and For You as soon as they appear.",
                    "The website filter" to
                        "Blocks adult sites across every app and browser on the phone.",
                    "Lock-in" to
                        "Makes turning protection off slow and deliberate, so a weak " +
                            "moment can't undo your setup in seconds.",
                    "Your partner" to
                        "Holds a code you need to weaken anything, and can be told " +
                            "when something changes. You are not doing this alone.",
                ).forEachIndexed { index, (name, what) ->
                    Text(
                        "${index + 1}. $name",
                        style = MaterialTheme.typography.titleSmall,
                        color = BastionColors.BronzeBright,
                    )
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        what,
                        style = MaterialTheme.typography.bodySmall,
                        color = BastionColors.TextSecondary,
                    )
                    Spacer(Modifier.height(Space.lg))
                }
                Text(
                    "No single layer is perfect, and Bastion will tell you where each " +
                        "one falls short. Together they are a wall.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BastionColors.TextMuted,
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onClose) {
                Text("Got it", color = BastionColors.BronzeBright)
            }
        },
    )
}
