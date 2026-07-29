package com.bastion.app.feature.guardui

import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bastion.app.core.design.BastionCard
import com.bastion.app.core.design.BastionColors
import com.bastion.app.core.design.DawnBackground
import com.bastion.app.core.design.PrimaryButton
import com.bastion.app.core.design.QuietButton
import com.bastion.app.core.design.SectionLabel
import com.bastion.app.data.BastionGraph
import com.bastion.app.data.db.BlockMode
import com.bastion.app.data.db.FeedRuleEntity
import com.bastion.app.data.db.GuardedAppEntity
import com.bastion.app.data.prefs.Settings
import com.bastion.app.guard.accessibility.BastionAccessibilityService
import com.bastion.app.guard.browser.FilteredBrowserActivity
import com.bastion.app.guard.vpn.BastionVpnService
import kotlinx.coroutines.launch

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun GuardScreen() {
    val context = LocalContext.current
    val graph = remember { BastionGraph.from(context) }
    val scope = rememberCoroutineScope()

    val settings by graph.settings.settings.collectAsStateWithLifecycle(initialValue = Settings())
    val guardedApps by graph.guard.guardedApps.collectAsStateWithLifecycle(initialValue = emptyList())
    val feedRules by graph.guard.feedRules.collectAsStateWithLifecycle(initialValue = emptyList())
    val pendingChanges by graph.guard.pendingChanges.collectAsStateWithLifecycle(initialValue = emptyList())
    val serviceRunning by BastionAccessibilityService.isRunning.collectAsStateWithLifecycle()
    val filterRunning by BastionVpnService.isRunning.collectAsStateWithLifecycle()
    val blockedCount by BastionVpnService.blockedCount.collectAsStateWithLifecycle()

    var showAppPicker by remember { mutableStateOf(false) }
    var showLearnMode by remember { mutableStateOf(false) }

    // Every weakening is confirmed before it is enqueued. The cooling-off lock
    // makes a mis-tap expensive to undo, so the tap has to be deliberate.
    var confirmUnguard by remember { mutableStateOf<GuardedAppEntity?>(null) }
    var confirmFilterOff by remember { mutableStateOf(false) }
    var confirmRelax by remember { mutableStateOf<Pair<GuardedAppEntity, BlockMode>?>(null) }

    // When the partner lock is armed, weakening anything needs the code the
    // partner holds. `pendingWeakening` parks the confirmed action until it is
    // entered — this is the difference between the lock existing in the schema
    // and the lock actually being a wall.
    var pendingWeakening by remember { mutableStateOf<(suspend () -> Unit)?>(null) }
    var lockArmed by remember { mutableStateOf(false) }
    LaunchedEffect(settings.partnerLockEnabled) {
        lockArmed = settings.partnerLockEnabled && graph.social.hasPasscode()
    }

    /** Runs [action] now, or holds it behind the partner's code if the lock is armed. */
    fun weaken(action: suspend () -> Unit) {
        if (lockArmed) pendingWeakening = action else scope.launch { action() }
    }

    // A cooling-off timer that visibly never moves reads as broken, and this one
    // is the app's proof that the lock is real. Computed once at composition, it
    // sat frozen until some unrelated state happened to recompose.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(pendingChanges.isNotEmpty()) {
        while (pendingChanges.isNotEmpty()) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000)
        }
    }

    LaunchedEffect(Unit) { graph.guard.seedIfEmpty() }

    // Reconciled on every resume, which is the moment the user is most likely to
    // be looking: switch Guard off in system settings, come back here, and the
    // gap is stated rather than glossed over.
    var guardBreached by remember { mutableStateOf(false) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        scope.launch {
            com.bastion.app.guard.GuardWatchdog.reconcile(context)
            guardBreached = com.bastion.app.guard.GuardWatchdog.isBreached(context)
        }
        onPauseOrDispose { }
    }

    val vpnConsent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            BastionVpnService.start(context)
            scope.launch { graph.settings.setVpnEnabled(true) }
        }
    }

    DawnBackground(intensity = 0.4f) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 52.dp, bottom = 32.dp)
        ) {
            Text("Guard", style = MaterialTheme.typography.displaySmall, color = BastionColors.TextPrimary)
            Spacer(Modifier.height(18.dp))

            if (guardBreached) {
                BastionCard(accent = BastionColors.Amber) {
                    Text(
                        "You asked for Guard to be on",
                        style = MaterialTheme.typography.titleMedium,
                        color = BastionColors.TextPrimary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "It's off. Guarded feeds are open.",
                        style = MaterialTheme.typography.bodySmall,
                        color = BastionColors.TextMuted,
                    )
                    Spacer(Modifier.height(14.dp))
                    PrimaryButton(
                        "Turn it back on",
                        { BastionAccessibilityService.openSettings(context) },
                        Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    // Written, never sent — the same rule as the rest of Brotherhood.
                    QuietButton(
                        "Tell my partner",
                        {
                            scope.launch {
                                com.bastion.app.guard.GuardWatchdog.partnerAlertIntent(context)
                                    ?.let { context.startActivity(it) }
                            }
                        },
                        Modifier.fillMaxWidth(),
                        BastionColors.SageBright,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            // --- Bastion Guard service ---
            BastionCard(accent = if (serviceRunning) BastionColors.Sage else BastionColors.Amber) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(serviceRunning)
                    Spacer(Modifier.size(10.dp))
                    Text(
                        if (serviceRunning) "Bastion Guard is on" else "Bastion Guard is off",
                        style = MaterialTheme.typography.titleMedium,
                        color = BastionColors.TextPrimary,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (serviceRunning) "Apps open. Guarded feeds don't."
                    else "Blocks Reels, Shorts and For You while the app stays usable.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BastionColors.TextMuted,
                )
                if (!serviceRunning) {
                    Spacer(Modifier.height(14.dp))
                    PrimaryButton(
                        "Turn on in Settings",
                        { BastionAccessibilityService.openSettings(context) },
                        Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // --- Content filter ---
            BastionCard(accent = if (filterRunning) BastionColors.Sage else null) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Content filter", style = MaterialTheme.typography.titleMedium, color = BastionColors.TextPrimary)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (filterRunning) "On · $blockedCount lookups blocked"
                            else "Blocks adult domains across every app and browser",
                            style = MaterialTheme.typography.bodySmall,
                            color = BastionColors.TextMuted,
                        )
                    }
                    Switch(
                        checked = filterRunning,
                        onCheckedChange = { wanted ->
                            if (wanted) {
                                val consent = BastionVpnService.prepareIntent(context)
                                if (consent != null) vpnConsent.launch(consent)
                                else {
                                    BastionVpnService.start(context)
                                    scope.launch { graph.settings.setVpnEnabled(true) }
                                }
                            } else {
                                confirmFilterOff = true
                            }
                        },
                        colors = switchColors(),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Apps with their own encrypted DNS can route around this. One layer of three.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BastionColors.TextMuted,
                )
                Spacer(Modifier.height(12.dp))
                QuietButton(
                    "Open Bastion browser",
                    {
                        context.startActivity(Intent(context, FilteredBrowserActivity::class.java))
                    },
                    Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(12.dp))

            // --- Guarded apps ---
            BastionCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionLabel("Guarded apps")
                    LinkButton("Add →") { showAppPicker = true }
                }
                Spacer(Modifier.height(14.dp))

                if (guardedApps.isEmpty()) {
                    Text(
                        "Nothing guarded yet. Instagram and YouTube are the usual first two.",
                        style = MaterialTheme.typography.bodySmall,
                        color = BastionColors.TextMuted,
                    )
                }

                guardedApps.forEach { app ->
                    GuardedAppRow(
                        app = app,
                        onModeChange = { mode ->
                            // Loosening waits and is confirmed; tightening is
                            // instant and needs no ceremony. Relaxing a mode is
                            // the same kind of decision as removing the guard
                            // altogether, so it gets the same dialog.
                            if (mode.isWeakerThan(app.mode)) {
                                confirmRelax = app to mode
                            } else {
                                scope.launch {
                                    graph.guard.upsertApp(
                                        app.copy(mode = mode, updatedAt = System.currentTimeMillis())
                                    )
                                }
                            }
                        },
                        onRemove = { confirmUnguard = app },
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }

            Spacer(Modifier.height(12.dp))

            // --- Feed rules ---
            BastionCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionLabel("Feed rules · ${feedRules.count { it.enabled }} active")
                    LinkButton("Learn →") { showLearnMode = true }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "A rule stopped firing? Learn it again in seconds.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BastionColors.TextMuted,
                )
                Spacer(Modifier.height(14.dp))
                feedRules.groupBy { it.packageName }.forEach { (pkg, rules) ->
                    Text(
                        feedGroupName(pkg, rules),
                        style = MaterialTheme.typography.titleSmall,
                        color = BastionColors.TextPrimary,
                    )
                    rules.forEach { rule ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${rule.matchType.name.lowercase()} · ${rule.matchValue}",
                                style = MaterialTheme.typography.bodySmall,
                                color = BastionColors.TextMuted,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = rule.enabled,
                                onCheckedChange = { enabled ->
                                    scope.launch {
                                        if (enabled) graph.guard.upsertRule(rule.copy(enabled = true))
                                        else graph.guard.requestWeakening(
                                            "Disable rule ${rule.label}",
                                            payload = "rule:${rule.id}:off",
                                        )
                                    }
                                },
                                colors = switchColors(),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(12.dp))

            // --- Tamper resistance ---
            BastionCard(accent = BastionColors.Bronze) {
                SectionLabel("Cooling-off lock")
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tightening is instant. Weakening waits ${settings.coolingOffHours}h.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BastionColors.TextMuted,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 2, 6, 24).forEach { hours ->
                        HourChip(hours, settings.coolingOffHours == hours) {
                            scope.launch {
                                // Lengthening the delay is a tightening; shortening waits its own delay.
                                if (hours >= settings.coolingOffHours) graph.settings.setCoolingOffHours(hours)
                                else graph.guard.requestWeakening(
                                    "Shorten the cooling-off delay to ${hours}h",
                                    payload = "cooloff:$hours",
                                )
                            }
                        }
                    }
                }

                if (pendingChanges.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    SectionLabel("Waiting", color = BastionColors.Amber)
                    Spacer(Modifier.height(8.dp))
                    pendingChanges.forEach { change ->
                        val remaining = ((change.effectiveAt - now) / 60_000L).coerceAtLeast(0)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(change.description, style = MaterialTheme.typography.bodyMedium, color = BastionColors.TextPrimary)
                                Text(
                                    if (remaining > 60) "in ${remaining / 60}h ${remaining % 60}m" else "in ${remaining}m",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = BastionColors.Amber,
                                )
                            }
                            LinkButton("Cancel", BastionColors.SageBright) {
                                scope.launch { graph.guard.cancelChange(change.id) }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            GrayscaleCard(settings = settings, graph = graph)
        }
    }

    if (showAppPicker) {
        ModalBottomSheet(
            onDismissRequest = { showAppPicker = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = BastionColors.Surface,
        ) {
            AppPickerSheet(
                alreadyGuarded = guardedApps.map { it.packageName }.toSet(),
                onPick = { pkg, label, mode ->
                    scope.launch {
                        graph.guard.upsertApp(
                            GuardedAppEntity(packageName = pkg, label = label, mode = mode)
                        )
                        showAppPicker = false
                    }
                },
            )
        }
    }

    confirmUnguard?.let { app ->
        ConfirmDialog(
            title = "Stop guarding ${app.label}?",
            body = "This won't take effect for ${settings.coolingOffHours} hours. " +
                "You can cancel it any time before then.",
            confirmLabel = "Request it",
            onConfirm = {
                weaken {
                    graph.guard.requestWeakening(
                        "Stop guarding ${app.label}",
                        payload = "remove:${app.packageName}",
                    )
                }
            },
            onDismiss = { confirmUnguard = null },
        )
    }

    pendingWeakening?.let { action ->
        PasscodeDialog(
            onVerify = { code -> graph.social.verifyPasscode(code) },
            onUnlocked = {
                scope.launch { action() }
                pendingWeakening = null
            },
            onDismiss = { pendingWeakening = null },
        )
    }

    confirmRelax?.let { (app, mode) ->
        ConfirmDialog(
            title = "Relax ${app.label} to ${mode.label()}?",
            body = "This won't take effect for ${settings.coolingOffHours} hours. " +
                "You can cancel it any time before then.",
            confirmLabel = "Request it",
            onConfirm = {
                weaken {
                    graph.guard.requestWeakening(
                        "Relax ${app.label} to ${mode.label()}",
                        payload = "app:${app.packageName}:${mode.name}",
                    )
                }
            },
            onDismiss = { confirmRelax = null },
        )
    }

    if (confirmFilterOff) {
        ConfirmDialog(
            title = "Turn the content filter off?",
            body = "This won't take effect for ${settings.coolingOffHours} hours. " +
                "You can cancel it any time before then.",
            confirmLabel = "Request it",
            onConfirm = {
                weaken {
                    graph.guard.requestWeakening(
                        "Turn off the content filter",
                        payload = "vpn:off",
                    )
                }
            },
            onDismiss = { confirmFilterOff = false },
        )
    }

    if (showLearnMode) {
        ModalBottomSheet(
            onDismissRequest = {
                showLearnMode = false
                BastionAccessibilityService.learnMode.value = false
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = BastionColors.Surface,
        ) {
            LearnModeSheet(onClose = {
                showLearnMode = false
                BastionAccessibilityService.learnMode.value = false
            })
        }
    }
}

@Composable
private fun GrayscaleCard(settings: Settings, graph: BastionGraph) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    // The grant lands over adb while this screen is open, so a value read once
    // at composition left the fallback showing until the process was restarted.
    var hasPermission by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        hasPermission = context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
        onPauseOrDispose {}
    }

    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_800)
            copied = false
        }
    }

    BastionCard {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Temptation dampening", style = MaterialTheme.typography.titleMedium, color = BastionColors.TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (hasPermission) "True grayscale on guarded apps"
                    else "Dimming veil on guarded apps",
                    style = MaterialTheme.typography.bodySmall,
                    color = BastionColors.TextMuted,
                )
            }
            Switch(
                checked = settings.grayscaleEnabled,
                onCheckedChange = { scope.launch { graph.settings.setGrayscale(it) } },
                colors = switchColors(),
            )
        }
        if (!hasPermission) {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "True grayscale needs one adb command:",
                    style = MaterialTheme.typography.bodySmall,
                    color = BastionColors.TextMuted,
                    modifier = Modifier.weight(1f),
                )
                LinkButton(if (copied) "Copied" else "Copy") {
                    clipboard.setText(AnnotatedString(ADB_GRANT_COMMAND))
                    copied = true
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(BastionColors.MidnightDeep)
                    .padding(12.dp)
            ) {
                Text(
                    ADB_GRANT_COMMAND,
                    style = MaterialTheme.typography.bodySmall,
                    color = BastionColors.SageBright,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Until then: a dimming veil, which is weaker.",
                style = MaterialTheme.typography.bodySmall,
                color = BastionColors.TextMuted,
            )
        }
    }
}

/**
 * Text-styled actions that are still real buttons. Guard is used one-handed in a
 * bad moment, so every action carries button semantics and a 48dp target rather
 * than being a clickable label.
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

/** Weakening is the direction that costs something, so a mis-tap must not start the clock. */
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

/**
 * Asks for the code the accountability partner holds.
 *
 * Deliberately offers no way past itself. There is no "forgot the code" escape,
 * because an escape hatch is exactly what the weak-moment self would reach for —
 * the way through is to phone the partner, which is the entire point of having
 * handed him the code in the first place.
 */
@Composable
private fun PasscodeDialog(
    onVerify: suspend (String) -> Boolean,
    onUnlocked: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BastionColors.Surface,
        title = {
            Text(
                "Your partner's code",
                style = MaterialTheme.typography.titleMedium,
                color = BastionColors.TextPrimary,
            )
        },
        text = {
            Column {
                Text(
                    "You asked him to hold this so tonight's version of you couldn't undo it alone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BastionColors.TextSecondary,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it; wrong = false },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword,
                    ),
                    isError = wrong,
                    supportingText = if (wrong) {
                        { Text("Not that one.", color = BastionColors.Amber) }
                    } else null,
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
            TextButton(
                enabled = code.isNotBlank() && !checking,
                onClick = {
                    scope.launch {
                        checking = true
                        if (onVerify(code)) onUnlocked() else wrong = true
                        checking = false
                    }
                },
            ) {
                Text("Unlock", color = BastionColors.BronzeBright)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Leave it locked", color = BastionColors.TextMuted)
            }
        },
    )
}

@Composable
private fun StatusDot(active: Boolean) {
    Box(
        Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(if (active) BastionColors.Sage else BastionColors.Amber)
    )
}

@Composable
private fun GuardedAppRow(
    app: GuardedAppEntity,
    onModeChange: (BlockMode) -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(BastionColors.SurfaceRaised)
            .padding(14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(app.label, style = MaterialTheme.typography.titleSmall, color = BastionColors.TextPrimary)
            LinkButton("Remove", BastionColors.TextMuted, onRemove)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BlockMode.entries.forEach { mode ->
                ModeChip(mode, app.mode == mode) { onModeChange(mode) }
            }
        }
    }
}

@Composable
private fun ModeChip(mode: BlockMode, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) BastionColors.BronzeDeep else BastionColors.Surface)
            .border(
                1.dp,
                if (selected) BastionColors.Bronze else BastionColors.Outline,
                RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Text(
            mode.label(),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) BastionColors.BronzeBright else BastionColors.TextMuted,
        )
    }
}

@Composable
private fun HourChip(hours: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) BastionColors.BronzeDeep else BastionColors.SurfaceRaised)
            .border(
                1.dp,
                if (selected) BastionColors.Bronze else BastionColors.Outline,
                RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            if (hours == 24) "24h" else "${hours}h",
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) BastionColors.BronzeBright else BastionColors.TextMuted,
        )
    }
}

@Composable
private fun AppPickerSheet(
    alreadyGuarded: Set<String>,
    onPick: (pkg: String, label: String, mode: BlockMode) -> Unit,
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        apps = pm.queryIntentActivities(intent, 0)
            .mapNotNull { info ->
                val pkg = info.activityInfo.packageName
                if (pkg == context.packageName) null
                else pkg to info.loadLabel(pm).toString()
            }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }

    Column(
        Modifier
            .padding(horizontal = 22.dp)
            .padding(bottom = 34.dp)
            .height(520.dp)
    ) {
        Text("Guard an app", style = MaterialTheme.typography.headlineSmall, color = BastionColors.TextPrimary)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = BastionColors.Bronze,
                unfocusedBorderColor = BastionColors.Outline,
                focusedTextColor = BastionColors.TextPrimary,
                unfocusedTextColor = BastionColors.TextPrimary,
            ),
        )
        Spacer(Modifier.height(14.dp))
        Column(Modifier.verticalScroll(rememberScrollState())) {
            apps.filter { it.second.contains(query, ignoreCase = true) }
                .filterNot { alreadyGuarded.contains(it.first) }
                .forEach { (pkg, label) ->
                    val suggested = com.bastion.app.data.repo.GuardRepository.SUGGESTED_PACKAGES
                        .firstOrNull { it.first == pkg }?.second ?: BlockMode.FEED_ONLY
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(pkg, label, suggested) }
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(label, style = MaterialTheme.typography.bodyLarge, color = BastionColors.TextPrimary)
                        Text(
                            suggested.label(),
                            style = MaterialTheme.typography.labelSmall,
                            color = BastionColors.TextMuted,
                        )
                    }
                }
        }
    }
}

/**
 * Learn Mode. Open the screen you want blocked, and Bastion lists the view
 * identifiers on it so a rule can be rebuilt when an app changes its layout.
 * Identifiers only — no text is ever captured.
 */
@Composable
private fun LearnModeSheet(onClose: () -> Unit) {
    val context = LocalContext.current
    val graph = remember { BastionGraph.from(context) }
    val scope = rememberCoroutineScope()
    val capture by BastionAccessibilityService.learnedIds.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { BastionAccessibilityService.learnMode.value = true }

    Column(
        Modifier
            .padding(horizontal = 22.dp)
            .padding(bottom = 34.dp)
            .height(520.dp)
    ) {
        Text("Learn mode", style = MaterialTheme.typography.headlineSmall, color = BastionColors.TextPrimary)
        Spacer(Modifier.height(10.dp))
        Text(
            "Open the screen you want closed, come back, pick its identifier.",
            style = MaterialTheme.typography.bodyMedium,
            color = BastionColors.TextSecondary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Identifiers only — no text is read.",
            style = MaterialTheme.typography.bodySmall,
            color = BastionColors.TextMuted,
        )
        Spacer(Modifier.height(16.dp))

        val current = capture
        if (current == null) {
            Text("Nothing captured yet.", style = MaterialTheme.typography.bodyMedium, color = BastionColors.TextMuted)
        } else {
            SectionLabel(current.packageName)
            Spacer(Modifier.height(10.dp))
            Column(Modifier.verticalScroll(rememberScrollState())) {
                current.viewIds.forEach { id ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch {
                                    graph.guard.upsertRule(
                                        com.bastion.app.data.db.FeedRuleEntity(
                                            id = "learned_${current.packageName}_$id".take(120),
                                            packageName = current.packageName,
                                            label = "Learned · $id",
                                            matchType = com.bastion.app.data.db.MatchType.VIEW_ID,
                                            matchValue = id,
                                            builtIn = false,
                                        )
                                    )
                                    onClose()
                                }
                            }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(id, style = MaterialTheme.typography.bodyMedium, color = BastionColors.SageBright)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        QuietButton("Done", onClose, Modifier.fillMaxWidth())
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

private const val ADB_GRANT_COMMAND =
    "adb shell pm grant com.bastion.app android.permission.WRITE_SECURE_SETTINGS"

/**
 * Heading for a package's rules.
 *
 * Rule labels lead with the app ("Instagram Reels (viewer)"), so the first word
 * was standing in for the app name — which turns a learned rule's one-token
 * label into a heading reading "Learned". Take what the group's labels share,
 * and fall back to a whole label rather than a fragment.
 */
private fun feedGroupName(packageName: String, rules: List<FeedRuleEntity>): String {
    val labels = rules.map { it.label.substringBefore(" (").trim() }.filter { it.isNotBlank() }
    val shared = labels.reduceOrNull { acc, label ->
        acc.split(' ').zip(label.split(' ')).takeWhile { (a, b) -> a == b }.joinToString(" ") { it.first }
    }
    return shared?.takeIf { it.isNotBlank() }
        ?: labels.firstOrNull()
        ?: packageName.substringAfterLast('.')
}

private fun BlockMode.label(): String = when (this) {
    BlockMode.FULL -> "Fully blocked"
    BlockMode.SCHEDULE -> "Scheduled"
    BlockMode.TIME_LIMIT -> "Time limit"
    BlockMode.FEED_ONLY -> "Feed only"
}

/** Ordering by strictness, so the cooling-off lock knows which way a change goes. */
private fun BlockMode.strictness(): Int = when (this) {
    BlockMode.FULL -> 4
    BlockMode.SCHEDULE -> 3
    BlockMode.TIME_LIMIT -> 2
    BlockMode.FEED_ONLY -> 1
}

private fun BlockMode.isWeakerThan(other: BlockMode): Boolean = strictness() < other.strictness()
