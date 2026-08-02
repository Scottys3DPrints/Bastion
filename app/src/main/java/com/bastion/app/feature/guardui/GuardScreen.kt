package com.bastion.app.feature.guardui

import android.content.Context
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.bastion.app.core.design.ChoiceRow
import com.bastion.app.core.design.LinkButton
import com.bastion.app.core.design.BastionBottomSheet
import com.bastion.app.core.design.BastionRow
import com.bastion.app.core.design.BastionScaffold
import com.bastion.app.core.design.Section
import com.bastion.app.core.design.Space
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
import com.bastion.app.guard.lockdown.BastionDeviceAdmin
import com.bastion.app.guard.vpn.BastionVpnService
import kotlinx.coroutines.launch

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun GuardScreen(onOpenProfile: () -> Unit) {
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
    var confirmUnlock by remember { mutableStateOf(false) }
    var blockedByLockdown by remember { mutableStateOf(false) }

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

    /**
     * Queues a weakening behind the cooling-off delay, but ONLY once the user has
     * locked in.
     *
     * Before that, every change is instant. Setting the guards up means adding
     * the wrong app, changing your mind about a mode, removing something you
     * added by mistake — and a delay on all of that made the app feel broken
     * rather than firm. The lock is a decision you make when the setup is right,
     * not a tax on arriving at it.
     */
    fun weakenOrQueue(description: String, payload: String, immediate: suspend () -> Unit) {
        // A running lockdown refuses every weakening outright, whether or not the
        // cooling-off lock is on. The two are deliberately independent, and
        // without this the danger button would be undone in seconds by simply
        // deleting the guarded apps — which is exactly what a man in that state
        // would think to try.
        if (com.bastion.app.guard.lockdown.Lockdown.isActive(settings)) {
            blockedByLockdown = true
            return
        }
        if (!settings.tamperLockEnabled) {
            weaken(immediate)
        } else {
            weaken { graph.guard.requestWeakening(description, payload) }
        }
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

    // Reads the breach; recording the intent happens app-wide in MainActivity,
    // because it must not depend on this screen being the one in front.
    var guardBreached by remember { mutableStateOf(false) }
    var breachedFor by remember { mutableStateOf("") }
    val ownerClipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var ownerCommandCopied by remember { mutableStateOf(false) }
    LaunchedEffect(ownerCommandCopied) {
        if (ownerCommandCopied) {
            kotlinx.coroutines.delay(1_800)
            ownerCommandCopied = false
        }
    }
    var confirmStandDown by remember { mutableStateOf(false) }
    var rulesExpanded by remember { mutableStateOf(false) }
    var detailRuleId by remember { mutableStateOf<String?>(null) }
    androidx.lifecycle.compose.LifecycleResumeEffect(serviceRunning) {
        scope.launch {
            guardBreached = com.bastion.app.guard.GuardWatchdog.isBreached(context)
            breachedFor = com.bastion.app.guard.GuardWatchdog.describeDuration(
                com.bastion.app.guard.GuardWatchdog.breachDurationMillis(context)
            )
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

    BastionScaffold(
        title = "Guard",
        dawnIntensity = 0.4f,
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

        if (guardBreached) {
            BastionCard(accent = BastionColors.Amber) {
                Text(
                    "You asked for Guard to be on",
                    style = MaterialTheme.typography.titleMedium,
                    color = BastionColors.TextPrimary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    // How long matters more than the fact: "since just now"
                    // is a slip, "for 3 days" is a decision.
                    "It's off. Guarded feeds have been open $breachedFor.",
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
                Spacer(Modifier.height(4.dp))
                // The honest exit. Without it the only way to stop the
                // six-hourly nag was to clear the app's data, which takes
                // the whole journey with it.
                LinkButton("I'm done with Guard") { confirmStandDown = true }
            }
        }

        // --- Guard strength: what is actually armed -------------------
        //
        // Pinned above everything else because it is the question the
        // screen exists to answer. Each unarmed row is its own one-tap
        // route to the system screen that arms it, which is what turns a
        // status display into the setup checklist as well.
        val layers = rememberGuardLayers(settings)
        GuardStrengthCard(
            layers = layers,
            onArm = { layer ->
                when (layer) {
                    GuardLayer.FEED_GUARD -> BastionAccessibilityService.openSettings(context)
                    GuardLayer.CONTENT_FILTER -> {
                        val consent = BastionVpnService.prepareIntent(context)
                        if (consent != null) {
                            vpnConsent.launch(consent)
                        } else {
                            BastionVpnService.start(context)
                            scope.launch { graph.settings.setVpnEnabled(true) }
                        }
                    }
                    GuardLayer.PRIVATE_DNS -> openPrivateDnsSettings(context)
                    GuardLayer.SCREEN_LOCK -> runCatching {
                        context.startActivity(
                            BastionDeviceAdmin.activationIntent(context)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                    // One tap, and that is the whole feature. This used to
                    // offer an adb command as the "upgrade" once the switch
                    // was already on — for a stronger mode that was never
                    // built.
                    GuardLayer.GRAYSCALE ->
                        scope.launch { graph.settings.setGrayscale(true) }
                    GuardLayer.NOTIFICATIONS -> openNotificationSettings(context)
                }
            },
        )


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
                Spacer(Modifier.height(8.dp))
                // Android 13 hides accessibility behind "restricted settings"
                // for sideloaded apps, and the option to lift it is buried in
                // App info under the overflow menu. Updates install through a
                // PackageInstaller session now, which should stop it coming
                // back — but when a build re-gates anyway, this is the door.
                QuietButton(
                    "Greyed out? Open App info",
                    { com.bastion.app.core.update.SelfInstaller.openAppInfo(context) },
                    Modifier.fillMaxWidth(),
                )
            }
        }


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


        // --- Guarded apps ---
        Column(Modifier.fillMaxWidth()) {
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


        // --- Feed rules ---
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel("Feed rules · ${feedRules.count { it.enabled }} active")
                LinkButton("Learn →") { showLearnMode = true }
            }
            Spacer(Modifier.height(8.dp))
            // Collapsed by default. Two dozen rows of matcher internals is
            // the single densest thing on this screen and almost never what
            // someone came here to change — the summary answers "is it
            // covered", and the list is one tap away when a rule breaks.
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { rulesExpanded = !rulesExpanded }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    feedRuleSummary(feedRules),
                    style = MaterialTheme.typography.bodySmall,
                    color = BastionColors.TextMuted,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (rulesExpanded) "Hide" else "Show",
                    style = MaterialTheme.typography.labelMedium,
                    color = BastionColors.BronzeBright,
                )
            }
            if (rulesExpanded) {
            Spacer(Modifier.height(14.dp))
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
                        // The label, not the matcher. "view_id ·
                        // com.instagram.android:id/clips_viewer" is what
                        // the rule is made of; "Instagram Reels" is what it
                        // does, and a non-technical reader took the former
                        // for breakage. The internals stay one tap away for
                        // when a rule needs re-learning.
                        Column(
                            Modifier
                                .weight(1f)
                                .clickable { detailRuleId = if (detailRuleId == rule.id) null else rule.id }
                        ) {
                            Text(
                                rule.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = BastionColors.TextSecondary,
                            )
                            if (detailRuleId == rule.id) {
                                Text(
                                    "${rule.matchType.name.lowercase()} · ${rule.matchValue}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = BastionColors.TextMuted,
                                )
                            }
                        }
                        Switch(
                            checked = rule.enabled,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    if (enabled) graph.guard.upsertRule(rule.copy(enabled = true))
                                    else if (!settings.tamperLockEnabled) {
                                        graph.guard.upsertRule(rule.copy(enabled = false))
                                    } else graph.guard.requestWeakening(
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
        }


        // --- Tamper resistance ---
        BastionCard(accent = BastionColors.Bronze) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    SectionLabel("Cooling-off lock")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        // The two states are named plainly, because the whole
                        // point is knowing which one you are in before you
                        // change something.
                        if (settings.tamperLockEnabled)
                            "Locked in. Weakening waits ${settings.coolingOffHours}h."
                        else
                            "Not locked in. Every change is instant.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (settings.tamperLockEnabled) BastionColors.BronzeBright
                        else BastionColors.TextMuted,
                    )
                }
                Switch(
                    checked = settings.tamperLockEnabled,
                    onCheckedChange = { wanted ->
                        // Locking in is instant; unlocking is itself a
                        // weakening and waits, or the lock would be a button
                        // that turns itself off.
                        if (wanted) scope.launch {
                            graph.settings.setTamperLock(true)
                            // The flag alone was the whole feature. Locking in
                            // now also closes the doors Android lets an app
                            // close, and starts the short watch that notices
                            // Guard going down within a minute.
                            com.bastion.app.guard.lockdown.DeviceOwner.apply(context, true)
                            com.bastion.app.core.alarm.LockInWatchScheduler.sync(context, true)
                        }
                        else confirmUnlock = true
                    },
                    colors = switchColors(),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                // Never "cannot be turned off". The accessibility toggle stays
                // reachable no matter what — Android keeps it that way on
                // purpose — and a promise the app cannot keep would poison
                // every promise it can.
                com.bastion.app.guard.lockdown.DeviceOwner.statusLine(
                    context,
                    settings.tamperLockEnabled,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = BastionColors.TextMuted,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Guard itself can always be switched off in Android's settings. " +
                    "Locked in, that puts a wall in front of you that needs the partner's " +
                    "code or the wait — and Bastion keeps putting it back. Home still " +
                    "leaves it unless the command below has been run. A wall, not a cage.",
                style = MaterialTheme.typography.bodySmall,
                color = BastionColors.TextMuted,
            )

            if (!com.bastion.app.guard.lockdown.DeviceOwner.isDeviceOwner(context)) {
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Close uninstall and factory reset — one adb command, once:",
                        style = MaterialTheme.typography.bodySmall,
                        color = BastionColors.TextMuted,
                        modifier = Modifier.weight(1f),
                    )
                    LinkButton(if (ownerCommandCopied) "Copied" else "Copy") {
                        ownerClipboard.setText(
                            androidx.compose.ui.text.AnnotatedString(
                                com.bastion.app.guard.lockdown.DeviceOwner.setupCommand(context)
                            )
                        )
                        ownerCommandCopied = true
                    }
                }
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(BastionColors.MidnightDeep)
                        .padding(12.dp)
                ) {
                    Text(
                        com.bastion.app.guard.lockdown.DeviceOwner.setupCommand(context),
                        style = MaterialTheme.typography.bodySmall,
                        color = BastionColors.SageBright,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Only works on a phone with no accounts added yet, so it is a " +
                        "fresh-device thing. Without it, everything above still applies " +
                        "except the uninstall and reset blocks.",
                    style = MaterialTheme.typography.labelSmall,
                    color = BastionColors.TextMuted,
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 2, 6, 24).forEach { hours ->
                    HourChip(hours, settings.coolingOffHours == hours) {
                        scope.launch {
                            // Lengthening the delay is a tightening; shortening waits its own delay.
                            if (hours >= settings.coolingOffHours || !settings.tamperLockEnabled) {
                                graph.settings.setCoolingOffHours(hours)
                            } else graph.guard.requestWeakening(
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

        // --- Settings ---
        //
        // Everything that is configured rather than acted on. The break-glass
        // button itself lives on the home screen, where it can be reached
        // without going looking; only its plan belongs here.
        SectionLabel("Settings")

        LockdownPlanCard(settings = settings, graph = graph)

        PrivateDnsCard()

        GrayscaleCard(settings = settings, graph = graph)
    }

    if (showAppPicker) {
        BastionBottomSheet(onDismiss = { showAppPicker = false }) {
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
            body = if (settings.tamperLockEnabled) {
                "This won't take effect for ${settings.coolingOffHours} hours. " +
                    "You can cancel it any time before then."
            } else {
                "You haven't locked in, so this happens straight away."
            },
            confirmLabel = "Request it",
            onConfirm = {
                weakenOrQueue(
                    "Stop guarding ${app.label}",
                    "remove:${app.packageName}",
                ) { graph.guard.removeApp(app.packageName) }
            },
            onDismiss = { confirmUnguard = null },
        )
    }

    pendingWeakening?.let { action ->
        PasscodeDialog(
            onAttempt = { code -> graph.passcodeGate.attempt(code) },
            initialWait = { graph.passcodeGate.waitMillis() },
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
            body = if (settings.tamperLockEnabled) {
                "This won't take effect for ${settings.coolingOffHours} hours. " +
                    "You can cancel it any time before then."
            } else {
                "You haven't locked in, so this happens straight away."
            },
            confirmLabel = "Request it",
            onConfirm = {
                weakenOrQueue(
                    "Relax ${app.label} to ${mode.label()}",
                    "app:${app.packageName}:${mode.name}",
                ) { graph.guard.upsertApp(app.copy(mode = mode, updatedAt = System.currentTimeMillis())) }
            },
            onDismiss = { confirmRelax = null },
        )
    }

    if (confirmStandDown) {
        ConfirmDialog(
            title = "Stop using Guard?",
            body = "Bastion will stop expecting it and stop warning you. Your streak, " +
                "history and guarded-app list all stay. Turn Guard back on any time and " +
                "this picks up where it left off.",
            confirmLabel = "Stop expecting it",
            onConfirm = {
                // Refused outright during a lockdown, and gated by the partner's
                // code otherwise. This is the widest weakening on the screen —
                // it switches off the only thing that notices the others are
                // gone — so it must not be the cheapest one to reach.
                if (com.bastion.app.guard.lockdown.Lockdown.isActive(settings)) {
                    blockedByLockdown = true
                } else {
                    weaken {
                        com.bastion.app.guard.GuardWatchdog.standDown(context)
                        guardBreached = false
                    }
                }
            },
            onDismiss = { confirmStandDown = false },
        )
    }

    if (blockedByLockdown) {
        ConfirmDialog(
            title = "Lockdown is running",
            body = "Guards can't be weakened until it ends. That is the point of it.",
            confirmLabel = "All right",
            onConfirm = {},
            onDismiss = { blockedByLockdown = false },
        )
    }

    if (confirmUnlock) {
        ConfirmDialog(
            title = "Unlock the guards?",
            body = "Unlocking waits ${settings.coolingOffHours} hours, like any other weakening. " +
                "Until then everything stays as it is.",
            confirmLabel = "Request it",
            onConfirm = {
                weaken { graph.guard.requestWeakening("Unlock the guards", "unlock") }
            },
            onDismiss = { confirmUnlock = false },
        )
    }

    if (confirmFilterOff) {
        ConfirmDialog(
            title = "Turn the content filter off?",
            body = if (settings.tamperLockEnabled) {
                "This won't take effect for ${settings.coolingOffHours} hours. " +
                    "You can cancel it any time before then."
            } else {
                "You haven't locked in, so this happens straight away."
            },
            confirmLabel = "Request it",
            onConfirm = {
                weakenOrQueue("Turn off the content filter", "vpn:off") {
                    graph.settings.setVpnEnabled(false)
                    BastionVpnService.stop(context)
                }
            },
            onDismiss = { confirmFilterOff = false },
        )
    }

    if (showLearnMode) {
        BastionBottomSheet(
            onDismiss = {
                showLearnMode = false
                BastionAccessibilityService.learnMode.value = false
            },
        ) {
            LearnModeSheet(onClose = {
                showLearnMode = false
                BastionAccessibilityService.learnMode.value = false
            })
        }
    }
}

/**
 * Closes the biggest hole in the content filter.
 *
 * Bastion's own filter intercepts the system resolver, which an app shipping its
 * own DNS-over-HTTPS simply walks around — that limitation is stated honestly
 * elsewhere in this screen, but stating it is not the same as narrowing it.
 *
 * Android's Private DNS setting is enforced by the platform below the app layer,
 * so pointing it at a filtering resolver covers ground the local VPN cannot.
 * It is one field, typed once, and it survives Bastion being uninstalled — which
 * is a feature rather than a flaw.
 */
@Composable
private fun PrivateDnsCard() {
    val context = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_800)
            copied = false
        }
    }

    Column(Modifier.fillMaxWidth()) {
        SectionLabel("Close the DNS-over-HTTPS gap")
        Spacer(Modifier.height(8.dp))
        Text(
            "Set this as Private DNS. It filters below the app layer, where Bastion can't reach.",
            style = MaterialTheme.typography.bodySmall,
            color = BastionColors.TextMuted,
        )
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(BastionColors.MidnightDeep)
                .padding(12.dp)
        ) {
            Text(
                PRIVATE_DNS_HOST,
                style = MaterialTheme.typography.bodySmall,
                color = BastionColors.SageBright,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuietButton(
                text = if (copied) "Copied" else "Copy",
                onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(PRIVATE_DNS_HOST))
                    copied = true
                },
                modifier = Modifier.weight(1f),
                accent = BastionColors.SageBright,
            )
            QuietButton(
                text = "Open settings",
                onClick = {
                    // Falls back to the top-level settings screen: the Private DNS
                    // action is not present on every OEM build, and a dead button
                    // would be worse than one extra tap.
                    val opened = runCatching {
                        context.startActivity(
                            Intent("android.settings.WIRELESS_SETTINGS")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }.isSuccess
                    if (!opened) {
                        runCatching {
                            context.startActivity(
                                Intent(android.provider.Settings.ACTION_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Cloudflare's family resolver: blocks adult content at the resolver itself. */
private const val PRIVATE_DNS_HOST = "family.cloudflare-dns.com"

@Composable
private fun GrayscaleCard(settings: Settings, graph: BastionGraph) {
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Temptation dampening", style = MaterialTheme.typography.titleMedium, color = BastionColors.TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    "A dimming veil over guarded apps",
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
        Spacer(Modifier.height(10.dp))
        Text(
            // What it is, with no overclaim. The card used to offer an adb
            // command to grant WRITE_SECURE_SETTINGS and promise "true
            // grayscale" in exchange; nothing in the app ever wrote a system
            // setting, so the grant bought a change of wording and nothing
            // else. The veil is the real mechanism, and it is a nudge rather
            // than a wall.
            "Takes the shine off a feed without hiding it. A nudge, not a wall.",
            style = MaterialTheme.typography.bodySmall,
            color = BastionColors.TextMuted,
        )
    }
}

/**
 * Text-styled actions that are still real buttons. Guard is used one-handed in a
 * bad moment, so every action carries button semantics and a 48dp target rather
 * than being a clickable label.
 */

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
    onAttempt: suspend (String) -> com.bastion.app.core.security.PasscodeGate.Result,
    initialWait: suspend () -> Long,
    onUnlocked: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var waitMillis by remember { mutableStateOf(0L) }

    // Counts down live rather than reading once on open: a dialog frozen at
    // "wait 4 min" gives no sign it is still running, and the natural response
    // is to close and reopen it — which is exactly the hammering being slowed.
    LaunchedEffect(Unit) { waitMillis = initialWait() }
    LaunchedEffect(waitMillis) {
        if (waitMillis > 0) {
            kotlinx.coroutines.delay(1_000)
            waitMillis = (waitMillis - 1_000).coerceAtLeast(0L)
        }
    }

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
                    enabled = waitMillis == 0L,
                    isError = wrong || waitMillis > 0,
                    supportingText = when {
                        waitMillis > 0 -> {
                            {
                                Text(
                                    "Too many tries. " +
                                        com.bastion.app.core.security.formatWait(waitMillis) +
                                        " to go.",
                                    color = BastionColors.Amber,
                                )
                            }
                        }
                        wrong -> {
                            { Text("Not that one.", color = BastionColors.Amber) }
                        }
                        else -> null
                    },
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
                enabled = code.isNotBlank() && !checking && waitMillis == 0L,
                onClick = {
                    scope.launch {
                        checking = true
                        when (val result = onAttempt(code)) {
                            is com.bastion.app.core.security.PasscodeGate.Result.Unlocked ->
                                onUnlocked()
                            is com.bastion.app.core.security.PasscodeGate.Result.Wrong -> {
                                wrong = true
                                waitMillis = result.waitMillis
                            }
                            is com.bastion.app.core.security.PasscodeGate.Result.Wait ->
                                waitMillis = result.millis
                        }
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
        // SCHEDULE and TIME_LIMIT are deliberately absent.
        //
        // Both need a parameter — a window, or a number of minutes — and there
        // is nowhere to set either, so choosing them silently applied defaults
        // of 00:00-00:00 and a fixed cap. The options looked like features and
        // behaved like dead ends, which is worse than not offering them. They
        // come back when their editors do. A mode an older build already set
        // stays listed, so the row keeps telling the truth about what is running.
        val modes = buildList {
            add(BlockMode.FEED_ONLY)
            add(BlockMode.FULL)
            if (app.mode !in this) add(app.mode)
        }
        ChoiceRow(
            options = modes,
            selected = app.mode,
            label = { it.label() },
            onSelect = onModeChange,
            modifier = Modifier.fillMaxWidth(),
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

    var showAll by remember { mutableStateOf(false) }

    Column(
        Modifier
            // heightIn, not a fixed height: at a large system font scale a
            // fixed 520dp clipped this sheet mid-sentence.
            .heightIn(min = 320.dp, max = 560.dp)
    ) {
        Text("Learn mode", style = MaterialTheme.typography.headlineSmall, color = BastionColors.TextPrimary)
        Spacer(Modifier.height(Space.sm))
        Text(
            "Open the screen you want closed, come back, pick its identifier.",
            style = MaterialTheme.typography.bodyMedium,
            color = BastionColors.TextSecondary,
        )
        Spacer(Modifier.height(Space.xs))
        Text(
            "Identifiers only — no text is read.",
            style = MaterialTheme.typography.bodySmall,
            color = BastionColors.TextMuted,
        )
        Spacer(Modifier.height(Space.lg))

        val current = capture
        if (current == null) {
            Text("Nothing captured yet.", style = MaterialTheme.typography.bodyMedium, color = BastionColors.TextMuted)
        } else {
            SectionLabel(current.packageName)
            Spacer(Modifier.height(Space.sm))

            // The live verdict. A rule could be saved, look correct, and never
            // fire; this says plainly whether the screen just captured is
            // covered, so a broken rule is a twenty-second fix instead of
            // something discovered by not being stopped.
            Text(
                if (current.blockedNow) "This screen would be blocked."
                else "This screen would NOT be blocked.",
                style = MaterialTheme.typography.titleSmall,
                color = if (current.blockedNow) BastionColors.SageBright else BastionColors.Amber,
            )
            Spacer(Modifier.height(Space.md))

            val qualifying = current.viewIds.filter { it.wouldBlock }
            val shown = if (showAll) current.viewIds else qualifying
            if (qualifying.isEmpty() && !showAll) {
                Text(
                    "Nothing on this screen looks like a full-screen vertical player. " +
                        "Open the feed itself, not a preview of it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BastionColors.TextMuted,
                )
                Spacer(Modifier.height(Space.sm))
            }

            Column(Modifier.verticalScroll(rememberScrollState())) {
                shown.forEach { learned ->
                    val id = learned.id
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
                            .padding(vertical = Space.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            id,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (learned.wouldBlock) BastionColors.SageBright
                            else BastionColors.TextMuted,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        if (learned.wouldBlock) {
                            Text(
                                "would block",
                                style = MaterialTheme.typography.labelSmall,
                                color = BastionColors.SageBright,
                            )
                        }
                    }
                }
                if (!showAll && current.viewIds.size > qualifying.size) {
                    LinkButton(
                        "Show all ${current.viewIds.size} identifiers",
                        BastionColors.TextMuted,
                    ) { showAll = true }
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

/**
 * Private DNS lives in a different place on almost every OEM skin, and the
 * dedicated action is not present on all of them, so this walks down from most
 * to least specific rather than risking a dead button.
 */
private fun openPrivateDnsSettings(context: Context) {
    val candidates = listOf(
        Intent("android.settings.PRIVATE_DNS_SETTINGS"),
        Intent("android.settings.WIRELESS_SETTINGS"),
        Intent(android.provider.Settings.ACTION_SETTINGS),
    )
    for (intent in candidates) {
        val opened = runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (opened) return
    }
}

/**
 * The app's own notification settings rather than a permission request.
 *
 * Android stops showing the runtime prompt after it has been dismissed twice,
 * and a button that silently does nothing is worse than one extra tap. This
 * screen always works and also covers the case where the permission was
 * granted and the channel was muted afterwards.
 */
private fun openNotificationSettings(context: Context) {
    val opened = runCatching {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.isSuccess
    if (!opened) {
        runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

/**
 * "Instagram, YouTube and 2 more · 14 rules" — coverage, not internals.
 *
 * Named apps rather than a bare count, because the question behind this line is
 * "is the app I'm worried about covered", and a number cannot answer it.
 */
private fun feedRuleSummary(rules: List<com.bastion.app.data.db.FeedRuleEntity>): String {
    val active = rules.filter { it.enabled }
    if (active.isEmpty()) return "No rules active — feeds open everywhere."

    val names = active.map { it.packageName }.distinct().map(::appNameForPackage)
    val shown = names.take(2).joinToString(", ")
    val rest = names.size - 2
    val apps = if (rest > 0) "$shown and $rest more" else shown
    return "$apps · ${active.size} ${if (active.size == 1) "rule" else "rules"}"
}

/** Best-effort friendly name; falls back to the package's last segment. */
private fun appNameForPackage(pkg: String): String = when (pkg) {
    "com.instagram.android" -> "Instagram"
    "com.google.android.youtube" -> "YouTube"
    "com.zhiliaoapp.musically", "com.ss.android.ugc.trill" -> "TikTok"
    "com.facebook.katana" -> "Facebook"
    "com.snapchat.android" -> "Snapchat"
    "com.twitter.android" -> "X"
    "com.reddit.frontpage" -> "Reddit"
    else -> pkg.substringAfterLast('.').replaceFirstChar(Char::uppercase)
}

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
